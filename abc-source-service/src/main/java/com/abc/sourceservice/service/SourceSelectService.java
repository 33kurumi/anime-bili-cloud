package com.abc.sourceservice.service;

import com.abc.sourceservice.entity.SourceRule;
import com.abc.sourceservice.select.RankedCandidate;
import com.abc.sourceservice.select.SelectEvent;
import com.abc.sourceservice.select.SelectPipeline;
import com.abc.sourceservice.select.SelectStage;
import com.abc.sourceservice.select.SourceBlacklist;
import com.abc.sourceservice.select.SourceRanker;
import com.abc.sourceservice.select.SourceRanker.RankInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 选源编排（把「纯状态机」{@link SelectPipeline} 和「真实数据源」粘起来）。
 *
 * <p>这是本次改造的核心：以前 {@code PlaybackService.play} 是一条 200 行的直线代码，
 * 缓存/指定源/多源择优/回退全揉在一起，超时也只有一个 20000ms 的全局兜底。
 * 现在拆成两半：
 * <ul>
 *   <li>{@link SelectPipeline}：管「阶段顺序 + 每阶段超时 + 降级 + 事件」——纯逻辑，可单测；</li>
 *   <li>本类：给每个阶段提供真实实现（缓存 → 指定源 → 精确搜索 → 模糊搜索）。</li>
 * </ul>
 * SSE 只是给状态机换了一个 Listener 实现，同步接口换一个空 Listener，逻辑完全共享。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceSelectService {

    /** 精确阶段最多尝试几个候选解析。 */
    private static final int EXACT_MAX_TRY = 3;
    /** 模糊阶段放宽到 5 个（搜到的东西更杂，多给几次机会）。 */
    private static final int FUZZY_MAX_TRY = 5;

    private final SourceAggregator aggregator;
    private final SourceRuleService ruleService;
    private final PlaybackService playbackService;
    private final TitleMatcher titleMatcher;
    private final SourceRanker ranker;
    private final SourceBlacklist blacklist;

    /** 阶段执行线程池：阶段本身是阻塞 IO，必须和工作线程分开，否则超时无处可逃。 */
    private final ExecutorService stagePool = Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "source-select-stage-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * 分阶段选源（SSE 与同步接口共用）。
     *
     * @param animeKey  集标识的番剧部分（缓存键）
     * @param episodeNum 集号
     * @param title     番剧标题（标准标题，优先用 cleanTitle）
     * @param keyword   调用方显式给的关键词（优先级高于 title）
     * @param designatedRuleId 指定源，可为 null
     * @param listener  SSE 监听器，同步场景传 null
     */
    public SelectPipeline.Result select(String animeKey, int episodeNum, String title, String keyword,
                                        Long designatedRuleId, SelectPipeline.Listener listener) {
        String exact = firstNonBlank(keyword, title);
        String fuzzy = buildFuzzyKeyword(exact);
        SelectPipeline.StageRunner runner =
                (stage, kw, designated) -> runStage(stage, animeKey, episodeNum, kw, designated);
        SelectPipeline.Listener safeListener = listener == null ? event -> { } : listener;
        SelectPipeline pipeline = new SelectPipeline(runner, safeListener, stagePool,
                () -> false, SelectPipeline.TimeoutPlan.defaults());
        return pipeline.run(exact, fuzzy, designatedRuleId);
    }

    /** 同步选源：给 {@code GET /api/source/play/{animeKey}/{ep}} 用。 */
    public Map<String, Object> playSync(String animeKey, int episodeNum, String title, String keyword,
                                        Long designatedRuleId) {
        long started = System.currentTimeMillis();
        SelectPipeline.Result result = select(animeKey, episodeNum, title, keyword, designatedRuleId, null);
        Map<String, Object> payload = new LinkedHashMap<>(result.payload());
        payload.putIfAbsent("hit", result.hit());
        if (result.stageUsed() == null) {
            payload.putIfAbsent("stageUsed", "NONE");
            payload.putIfAbsent("stageUsedLabel", "全部阶段失败");
        }
        payload.putIfAbsent("elapsedMs", System.currentTimeMillis() - started);
        payload.putIfAbsent("animeKey", animeKey);
        payload.putIfAbsent("episodeNum", episodeNum);
        return payload;
    }

    // ─── 阶段实现 ────────────────────────────────────────────────

    private SelectPipeline.StageOutcome runStage(SelectStage stage, String animeKey, int episodeNum,
                                                  String keyword, Long designatedRuleId) {
        switch (stage) {
            case CACHE:
                return runCache(animeKey, episodeNum, designatedRuleId);
            case DESIGNATED:
                return runDesignated(animeKey, episodeNum, keyword, designatedRuleId);
            case EXACT:
                return runCrossSource(animeKey, episodeNum, keyword, EXACT_MAX_TRY);
            case FUZZY:
                return runCrossSource(animeKey, episodeNum, keyword, FUZZY_MAX_TRY);
            default:
                return SelectPipeline.StageOutcome.miss("未知阶段");
        }
    }

    /** 阶段 1：缓存命中直接返回（0ms 级）。 */
    private SelectPipeline.StageOutcome runCache(String animeKey, int episodeNum, Long designatedRuleId) {
        String cacheKey = playbackService.cacheKey(animeKey, episodeNum, designatedRuleId);
        Map<String, Object> cached = playbackService.getCached(cacheKey);
        if (cached == null) {
            return SelectPipeline.StageOutcome.miss("缓存未命中");
        }
        Map<String, Object> payload = new LinkedHashMap<>(cached);
        payload.put("hit", true);
        payload.put("cacheKey", cacheKey);
        payload.put("cacheHit", true);
        return SelectPipeline.StageOutcome.hit(List.of(), payload);
    }

    /** 阶段 2：用户指定源（前端「切换线路」）。失败才拉黑，成功则解除。 */
    private SelectPipeline.StageOutcome runDesignated(String animeKey, int episodeNum, String keyword,
                                                      Long designatedRuleId) {
        if (designatedRuleId == null) {
            return SelectPipeline.StageOutcome.miss("未指定源");
        }
        if (keyword == null || keyword.isBlank()) {
            return SelectPipeline.StageOutcome.miss("缺少番剧标题，无法匹配指定源");
        }
        long started = System.currentTimeMillis();
        SourceAggregator.Candidate candidate = aggregator.resolveWithRule(designatedRuleId, keyword,
                episodeNum, (int) SelectStage.DESIGNATED.getTimeoutMs());
        long cost = System.currentTimeMillis() - started;
        if (candidate == null) {
            blacklist.markFailure(designatedRuleId, "指定源未匹配到该番剧");
            return SelectPipeline.StageOutcome.miss(List.of(), List.of(designatedRuleId), "指定源未匹配到该番剧");
        }
        List<RankedCandidate> ranked = List.of(rankOne(candidate, cost));
        Map<String, Object> resolved = playbackService.resolveCandidate(candidate, episodeNum);
        if (resolved.get("url") == null) {
            blacklist.markFailure(designatedRuleId, String.valueOf(resolved.getOrDefault("message", "解析失败")));
            return SelectPipeline.StageOutcome.miss(ranked, List.of(designatedRuleId),
                    "指定源解析失败：" + resolved.getOrDefault("message", "未知原因"));
        }
        blacklist.markSuccess(designatedRuleId);
        playbackService.putCached(playbackService.cacheKey(animeKey, episodeNum, designatedRuleId), resolved);
        return SelectPipeline.StageOutcome.hit(ranked, resolved);
    }

    /**
     * 阶段 3/4：跨源搜索 → 过滤黑名单 → 五级排序 → 依次解析前 N 个。
     * 解析成功即缓存并返回；失败的源进冷却，下一次选源不再浪费超时。
     */
    private SelectPipeline.StageOutcome runCrossSource(String animeKey, int episodeNum, String keyword, int maxTry) {
        if (keyword == null || keyword.isBlank()) {
            return SelectPipeline.StageOutcome.miss("缺少番剧标题，跳过跨源搜索");
        }
        int budget = (int) SelectStage.EXACT.getTimeoutMs();
        List<SourceAggregator.Candidate> candidates = aggregator.findCandidates(keyword, episodeNum, budget,
                ruleId -> blacklist.markFailure(ruleId, "搜索阶段失败"));
        List<RankedCandidate> ranked = rankCandidates(candidates);
        if (ranked.isEmpty()) {
            return SelectPipeline.StageOutcome.miss("没有可用候选（未匹配或全部处于冷却期）");
        }
        List<Long> failed = new ArrayList<>();
        int tryCount = Math.min(maxTry, ranked.size());
        String lastMessage = null;
        for (int i = 0; i < tryCount; i++) {
            RankedCandidate rc = ranked.get(i);
            if (blacklist.isBlacklisted(rc.ruleId())) {
                continue;
            }
            SourceAggregator.Candidate candidate = new SourceAggregator.Candidate(rc.ruleId(), rc.sourceName(),
                    rc.matchedTitle(), rc.directUrl(), rc.episodePageUrl(), rc.matchScore());
            Map<String, Object> resolved = playbackService.resolveCandidate(candidate, episodeNum);
            if (resolved.get("url") != null) {
                blacklist.markSuccess(rc.ruleId());
                playbackService.putCached(playbackService.cacheKey(animeKey, episodeNum, null), resolved);
                return SelectPipeline.StageOutcome.hit(ranked, resolved);
            }
            lastMessage = String.valueOf(resolved.getOrDefault("message", "解析失败"));
            failed.add(rc.ruleId());
            blacklist.markFailure(rc.ruleId(), lastMessage);
            log.info("候选 #{} {} 解析失败: {}", i + 1, rc.sourceName(), lastMessage);
        }
        return SelectPipeline.StageOutcome.miss(ranked, failed,
                "前 " + tryCount + " 个候选均未解析出直链" + (lastMessage == null ? "" : "（最后一次：" + lastMessage + "）"));
    }

    // ─── 排序辅助 ────────────────────────────────────────────────

    private List<RankedCandidate> rankCandidates(List<SourceAggregator.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RankInput> inputs = new ArrayList<>(candidates.size());
        for (SourceAggregator.Candidate c : candidates) {
            if (c == null || c.ruleId() == null) continue;
            SourceRule rule = ruleService.getById(c.ruleId());
            if (rule == null) continue;
            inputs.add(toRankInput(c, rule, aggregator.lastResponseTimeMs(c.ruleId())));
        }
        return ranker.rank(inputs);
    }

    private RankedCandidate rankOne(SourceAggregator.Candidate candidate, long responseTimeMs) {
        SourceRule rule = ruleService.getById(candidate.ruleId());
        if (rule == null) {
            return null;
        }
        return ranker.rankOne(toRankInput(candidate, rule, responseTimeMs));
    }

    private RankInput toRankInput(SourceAggregator.Candidate c, SourceRule rule, long responseTimeMs) {
        return new RankInput(c.ruleId(), c.sourceName(), c.matchedTitle(), c.directUrl(), c.episodePageUrl(),
                c.score(),
                rule.getWeight() == null ? 0 : rule.getWeight(),
                rule.getSuccessRate() == null ? 0.0 : rule.getSuccessRate(),
                responseTimeMs,
                rule.getHealthStatus());
    }

    /** 模糊关键词：先做标题归一化，再截断，避免整句长标题搜不到。 */
    private String buildFuzzyKeyword(String exact) {
        if (exact == null || exact.isBlank()) {
            return exact;
        }
        String normalized;
        try {
            normalized = titleMatcher.normalize(exact);
        } catch (Exception e) {
            normalized = exact;
        }
        if (normalized == null || normalized.isBlank()) {
            normalized = exact;
        }
        return normalized.length() > 24 ? normalized.substring(0, 24) : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** 供 Controller 组装 SSE 事件用（与 pipeline 的字段保持一致）。 */
    public static Map<String, Object> candidateMap(RankedCandidate c, int index) {
        return SelectEvent.candidateMap(c, index);
    }
}
