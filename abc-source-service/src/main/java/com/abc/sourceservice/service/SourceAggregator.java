package com.abc.sourceservice.service;

import com.abc.sourceservice.engine.EngineModels.Road;
import com.abc.sourceservice.engine.EngineModels.SearchItem;
import com.abc.sourceservice.engine.RuleEngine;
import com.abc.sourceservice.engine.RuleException;
import com.abc.sourceservice.engine.RuleExecutionConfig;
import com.abc.sourceservice.engine.strategy.MaccmsStrategy;
import com.abc.sourceservice.entity.SourceRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.LongConsumer;

/**
 * 多源聚合搜索（对齐 Animeko MediaFetchSession 的多源 combine + 去重 + 择优）。
 *
 * <p>支持两类源：
 * <ul>
 *   <li>maccms：一次调用即拿到「条目 + 分集 + 明文直链」，候选携带 directUrl；</li>
 *   <li>xpath / api：返回详情页 URL，候选携带 episodePageUrl（需调用方再解析分集）。</li>
 * </ul>
 * 并发执行、按标题相似度择优、失败按 {@link RuleException} 分类降级。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceAggregator {

    private final SourceRuleService ruleService;
    private final TitleMatcher titleMatcher;

    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    /**
     * 每个源最近一次搜索耗时（毫秒）。选源排序的第 3 级要读它，
     * 用 ConcurrentHashMap 而不是再查 DB：这里是「运行时观测值」，不属于规则配置。
     */
    private final Map<Long, Long> lastResponseMs = new ConcurrentHashMap<>();

    /** 单个源的搜索结果（调试用）。 */
    public record SourceResult(String ruleName, Long ruleId, List<SearchItem> items, String error) { }

    /** 播放候选：directUrl 与 episodePageUrl 二者其一非空，isDirect 标明类型。 */
    public record Candidate(Long ruleId, String sourceName, String matchedTitle,
                            String directUrl, String episodePageUrl, double score) {
        public boolean isDirect() { return directUrl != null && !directUrl.isBlank(); }
    }

    // ─── 调试用：各源搜索结果 ──────────────────────────────────

    public List<SourceResult> searchAll(String keyword, int timeoutMs) {
        List<SourceRule> rules = ruleService.listEnabled();
        List<Future<SourceResult>> futures = new ArrayList<>();
        RuleEngine engine = new RuleEngine();

        for (SourceRule rule : rules) {
            RuleExecutionConfig cfg = ruleService.toConfig(rule);
            futures.add(pool.submit(() -> {
                try {
                    List<SearchItem> items;
                    if (cfg.isMaccms()) {
                        items = new ArrayList<>();
                        for (MaccmsStrategy.MaccmsItem it : engine.searchMaccms(cfg, keyword)) {
                            items.add(new SearchItem(it.name(), "maccms://" + it.vodId()));
                        }
                    } else {
                        items = engine.search(cfg, keyword);
                    }
                    ruleService.markHealth(rule.getId(), true);
                    return new SourceResult(rule.getName(), rule.getId(), items, null);
                } catch (RuleException e) {
                    ruleService.markHealth(rule.getId(), false);
                    return new SourceResult(rule.getName(), rule.getId(), List.of(),
                            e.getReason() + ": " + e.getMessage());
                } catch (Exception e) {
                    ruleService.markHealth(rule.getId(), false);
                    return new SourceResult(rule.getName(), rule.getId(), List.of(), e.getMessage());
                }
            }));
        }
        return collect(futures, timeoutMs);
    }

    // ─── 择优：选出与标准标题最匹配、且可播放的候选 ────────────

    /**
     * 跨源搜索并选出最佳播放候选。
     * @param cleanTitle 归一化前的番剧标题（用于相似度匹配）
     * @param ep         目标集号（maccms 直接取对应集直链）
     */
    public Candidate findBest(String cleanTitle, int ep, int timeoutMs, double threshold) {
        List<SourceRule> rules = ruleService.listEnabled();
        List<Future<List<Candidate>>> futures = new ArrayList<>();
        RuleEngine engine = new RuleEngine();

        for (SourceRule rule : rules) {
            RuleExecutionConfig cfg = ruleService.toConfig(rule);
            futures.add(pool.submit(() -> {
                List<Candidate> out = new ArrayList<>();
                try {
                    if (cfg.isMaccms()) {
                        for (MaccmsStrategy.MaccmsItem it : engine.searchMaccms(cfg, cleanTitle)) {
                            double score = titleMatcher.similarity(cleanTitle, it.name());
                            String direct = pickEpisode(it.roads(), ep);
                            out.add(new Candidate(rule.getId(), rule.getName(), it.name(), direct, null, score));
                        }
                    } else {
                        for (SearchItem item : engine.search(cfg, cleanTitle)) {
                            double score = titleMatcher.similarity(cleanTitle, item.getName());
                            out.add(new Candidate(rule.getId(), rule.getName(), item.getName(), null, item.getUrl(), score));
                        }
                    }
                    ruleService.markHealth(rule.getId(), true);
                } catch (Exception e) {
                    ruleService.markHealth(rule.getId(), false);
                    log.info("源 {} 搜索失败: {}", rule.getName(), e.getMessage());
                }
                return out;
            }));
        }

        // 收集全部候选，去重（同名同 URL），取最高分
        Candidate best = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Future<List<Candidate>> f : futures) {
            long wait = Math.max(1, deadline - System.currentTimeMillis());
            List<Candidate> list;
            try {
                list = f.get(wait, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                continue;
            } catch (Exception e) {
                continue;
            }
            for (Candidate c : list) {
                String key = c.sourceName() + "|" + c.matchedTitle();
                if (!seen.add(key)) continue;
                if (c.score() >= threshold && (best == null || c.score() > best.score())) {
                    best = c;
                }
            }
        }
        // 若没有过阈值的，退而取最高分（避免完全无结果）
        if (best == null) {
            for (Future<List<Candidate>> f : futures) {
                if (f.isDone() && !f.isCancelled()) {
                    try {
                        for (Candidate c : f.get()) {
                            if (best == null || c.score() > best.score()) best = c;
                        }
                    } catch (Exception ignored) { }
                }
            }
        }
        return best;
    }

    /** 从线路里取第 ep 集直链（越界取最后一集）。 */
    private String pickEpisode(List<Road> roads, int ep) {
        if (roads == null || roads.isEmpty()) return null;
        // 优先取第一条线路
        List<String> urls = roads.get(0).getUrls();
        if (urls == null || urls.isEmpty()) return null;
        int idx = Math.min(Math.max(ep - 1, 0), urls.size() - 1);
        return urls.get(idx);
    }

    // ─── 候选列表（用于"依次回退"）─────────────────────────────

    /**
     * 深度健康检测：对每个源「搜索 → 分集解析」，判定真实可用性。
     * 比只测搜索更准（能识别"搜得到但分集404/规则过期"的源）。
     * @return [{ruleId, name, mode, status, searchCount, epCount, note}]
     *   status: ok(绿,能解析到分集) / search_only(橙,搜得到但解析失败) / dead(红,搜不到)
     */
    public List<Map<String, Object>> healthCheck(String title, int timeoutMs) {
        List<SourceRule> rules = ruleService.listEnabled();
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        RuleEngine engine = new RuleEngine();
        for (SourceRule rule : rules) {
            RuleExecutionConfig cfg = ruleService.toConfig(rule);
            futures.add(pool.submit(() -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ruleId", rule.getId());
                m.put("name", rule.getName());
                m.put("mode", rule.getSearchMode());
                try {
                    if (cfg.isMaccms()) {
                        List<MaccmsStrategy.MaccmsItem> items = engine.searchMaccms(cfg, title);
                        int ep = items.isEmpty() ? 0
                                : (items.get(0).roads().isEmpty() ? 0 : items.get(0).roads().get(0).getUrls().size());
                        m.put("searchCount", items.size());
                        m.put("epCount", ep);
                        m.put("status", (!items.isEmpty() && ep > 0) ? "ok" : "dead");
                        m.put("note", (!items.isEmpty() && ep > 0) ? "" : "无直链");
                    } else {
                        List<SearchItem> items = engine.search(cfg, title);
                        m.put("searchCount", items.size());
                        if (items.isEmpty()) {
                            m.put("status", "dead"); m.put("epCount", 0); m.put("note", "搜不到");
                        } else {
                            // 深检：尝试解析分集（xpath/css/api 源）
                            try {
                                List<Road> roads = engine.chapters(cfg, items.get(0).getUrl());
                                int ep = roads.isEmpty() ? 0 : roads.get(0).getUrls().size();
                                m.put("epCount", ep);
                                m.put("status", ep > 0 ? "ok" : "search_only");
                                m.put("note", ep > 0 ? "" : "分集解析失败");
                            } catch (Exception e) {
                                m.put("status", "search_only"); m.put("epCount", 0);
                                m.put("note", "分集失败:" + String.valueOf(e.getMessage()).substring(0, Math.min(30, String.valueOf(e.getMessage()).length())));
                            }
                        }
                    }
                } catch (Exception e) {
                    m.put("status", "dead"); m.put("searchCount", 0); m.put("epCount", 0);
                    m.put("note", String.valueOf(e.getMessage()));
                }
                return m;
            }));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Future<Map<String, Object>> f : futures) {
            try { out.add(f.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)); }
            catch (Exception e) { f.cancel(true); }
        }
        return out;
    }

    /**
     * 跨源搜索，返回全部候选（按标题相似度降序、去重），供调用方依次尝试解析。
     */
    public List<Candidate> findCandidates(String cleanTitle, int ep, int timeoutMs) {
        return findCandidates(cleanTitle, ep, timeoutMs, null);
    }

    /**
     * 同上，额外把「哪个源失败了」回调出去（选源编排用它记失败源黑名单 + 前端事件）。
     * 失败回调只在异常路径触发；超时的源由调用方按 future 超时判定。
     */
    public List<Candidate> findCandidates(String cleanTitle, int ep, int timeoutMs, LongConsumer onRuleFailure) {
        List<SourceRule> rules = ruleService.listEnabled();
        List<Future<List<Candidate>>> futures = new ArrayList<>();
        RuleEngine engine = new RuleEngine();
        for (SourceRule rule : rules) {
            RuleExecutionConfig cfg = ruleService.toConfig(rule);
            futures.add(pool.submit(() -> {
                List<Candidate> out = new ArrayList<>();
                long startedAt = System.currentTimeMillis();
                try {
                    if (cfg.isMaccms()) {
                        for (MaccmsStrategy.MaccmsItem it : engine.searchMaccms(cfg, cleanTitle)) {
                            out.add(new Candidate(rule.getId(), rule.getName(), it.name(),
                                    pickEpisode(it.roads(), ep), null, titleMatcher.similarity(cleanTitle, it.name())));
                        }
                    } else {
                        for (SearchItem item : engine.search(cfg, cleanTitle)) {
                            out.add(new Candidate(rule.getId(), rule.getName(), item.getName(),
                                    null, item.getUrl(), titleMatcher.similarity(cleanTitle, item.getName())));
                        }
                    }
                    ruleService.markHealth(rule.getId(), true);
                } catch (Exception e) {
                    ruleService.markHealth(rule.getId(), false);
                    log.info("源 {} 搜索失败: {}", rule.getName(), e.getMessage());
                    if (onRuleFailure != null) {
                        onRuleFailure.accept(rule.getId());
                    }
                } finally {
                    lastResponseMs.put(rule.getId(), System.currentTimeMillis() - startedAt);
                }
                return out;
            }));
        }
        List<Candidate> all = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Future<List<Candidate>> f : futures) {
            try {
                List<Candidate> list = f.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                for (Candidate c : list) {
                    String key = c.sourceName() + "|" + c.matchedTitle();
                    if (seen.add(key)) all.add(c);
                }
            } catch (Exception e) {
                f.cancel(true);
            }
        }
        // 直连源（maccms，免浏览器、秒出）优先，其次按相似度
        all.sort((a, b) -> {
            if (a.isDirect() != b.isDirect()) return a.isDirect() ? -1 : 1;
            return Double.compare(b.score(), a.score());
        });
        return all;
    }

    /** 某个源最近一次搜索耗时（毫秒）；从未搜过返回 -1（排序时视为最慢）。 */
    public long lastResponseTimeMs(long ruleId) {
        Long v = lastResponseMs.get(ruleId);
        return v == null ? -1L : v;
    }

    // ─── 指定源解析（用于"切换线路"）───────────────────────────

    /** 列出所有启用的源（不含解析，用于前端"切换源"下拉）。 */
    public List<Map<String, Object>> listSources() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SourceRule r : ruleService.listEnabled()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("mode", r.getSearchMode());
            m.put("health", r.getHealthStatus());
            out.add(m);
        }
        return out;
    }

    /**
     * 用指定规则解析播放候选（不做跨源择优）。
     * @return Candidate；该源没搜到返回 null
     */
    public Candidate resolveWithRule(long ruleId, String cleanTitle, int ep, int timeoutMs) {
        SourceRule rule = ruleService.getById(ruleId);
        if (rule == null) return null;
        RuleExecutionConfig cfg = ruleService.toConfig(rule);
        RuleEngine engine = new RuleEngine();
        try {
            if (cfg.isMaccms()) {
                List<MaccmsStrategy.MaccmsItem> items = engine.searchMaccms(cfg, cleanTitle);
                if (items.isEmpty()) return null;
                MaccmsStrategy.MaccmsItem it = items.get(0);
                double score = titleMatcher.similarity(cleanTitle, it.name());
                return new Candidate(rule.getId(), rule.getName(), it.name(), pickEpisode(it.roads(), ep), null, score);
            } else {
                List<SearchItem> items = engine.search(cfg, cleanTitle);
                if (items.isEmpty()) return null;
                SearchItem it = items.get(0);
                double score = titleMatcher.similarity(cleanTitle, it.getName());
                return new Candidate(rule.getId(), rule.getName(), it.getName(), null, it.getUrl(), score);
            }
        } catch (Exception e) {
            log.info("指定源 {} 解析失败: {}", rule.getName(), e.getMessage());
            return null;
        }
    }

    private List<SourceResult> collect(List<Future<SourceResult>> futures, int timeoutMs) {
        List<SourceResult> results = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Future<SourceResult> f : futures) {
            long wait = Math.max(1, deadline - System.currentTimeMillis());
            try {
                results.add(f.get(wait, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                f.cancel(true);
                results.add(new SourceResult("timeout", null, List.of(), "TIMEOUT"));
            } catch (Exception e) {
                results.add(new SourceResult("error", null, List.of(), e.getMessage()));
            }
        }
        return results;
    }
}
