package com.abc.sourceservice.select;

import com.abc.sourceservice.select.SelectPipeline.StageOutcome;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分阶段选源状态机单测：全部用手写 fake StageRunner，不启 Spring、不连 Redis、不联网。
 *
 * <p>覆盖：缓存命中短路 → 指定源只在传 ruleId 时出现 → 精确/模糊降级 →
 * 阶段超时继续下一阶段 → 全失败仍返回候选 → 客户端取消 → 跨阶段候选去重。
 */
class SelectPipelineTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    // ─── 测试替身 ────────────────────────────────────────────────

    private static RankedCandidate cand(long ruleId, String name) {
        return new RankedCandidate(ruleId, name, "火影忍者", null, "https://site.example/detail/" + ruleId,
                0.95, 5, 0.8, 120, "UP", 88.5, List.of("权重=5"), 0.42);
    }

    private static Map<String, Object> playable(String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("proxyUrl", "/api/source/proxy?ruleId=1&u=x&ts=1&scope=direct&sign=s");
        m.put("sourceName", "A站");
        m.put("resolvedBy", "static");
        return m;
    }

    /** 记录访问过的阶段，并按预设返回结果。 */
    private static class FakeRunner implements SelectPipeline.StageRunner {
        final List<SelectStage> visited = new ArrayList<>();
        final Map<SelectStage, StageOutcome> outcomes = new ConcurrentHashMap<>();

        @Override
        public StageOutcome run(SelectStage stage, String keyword, Long designatedRuleId) throws Exception {
            visited.add(stage);
            StageOutcome out = outcomes.get(stage);
            return out == null ? StageOutcome.miss("fake miss: " + stage) : out;
        }
    }

    private SelectPipeline pipeline(SelectPipeline.StageRunner runner, SelectPipeline.Listener listener,
                                    java.util.function.BooleanSupplier cancelled, SelectPipeline.TimeoutPlan plan) {
        return new SelectPipeline(runner, listener, pool, cancelled, plan);
    }

    private static SelectPipeline.TimeoutPlan fastPlan() {
        return new SelectPipeline.TimeoutPlan(2000, 2000, 2000, 2000);
    }

    // ─── 用例 ────────────────────────────────────────────────────

    @Test
    @DisplayName("缓存命中直接短路，后续阶段不再执行")
    void cacheHitShortCircuits() {
        FakeRunner runner = new FakeRunner();
        runner.outcomes.put(SelectStage.CACHE, SelectPipeline.StageOutcome.hit(List.of(cand(1, "A站")), playable("https://cdn/x.m3u8")));

        SelectPipeline.Result r = pipeline(runner, null, () -> false, fastPlan()).run("火影忍者", "火影", null);

        assertTrue(r.hit());
        assertEquals(SelectStage.CACHE, r.stageUsed());
        assertEquals("https://cdn/x.m3u8", r.payload().get("url"));
        assertEquals("CACHE", r.payload().get("stageUsed"));
        assertEquals(List.of(SelectStage.CACHE), runner.visited);
    }

    @Test
    @DisplayName("未传 ruleId 时不进入指定源阶段")
    void skipsDesignatedStageWithoutRuleId() {
        FakeRunner runner = new FakeRunner();
        runner.outcomes.put(SelectStage.EXACT, SelectPipeline.StageOutcome.hit(List.of(cand(2, "B站")), playable("https://cdn/y.mp4")));

        SelectPipeline.Result r = pipeline(runner, null, () -> false, fastPlan()).run("火影忍者", "火影", null);

        assertTrue(r.hit());
        assertEquals(SelectStage.EXACT, r.stageUsed());
        assertEquals(List.of(SelectStage.CACHE, SelectStage.EXACT), runner.visited);
    }

    @Test
    @DisplayName("传了 ruleId 才插入指定源阶段，且优先级高于跨源精确匹配")
    void designatedStageRunsBeforeExact() {
        FakeRunner runner = new FakeRunner();
        runner.outcomes.put(SelectStage.DESIGNATED, SelectPipeline.StageOutcome.hit(List.of(cand(9, "指定源")), playable("https://cdn/z.m3u8")));

        SelectPipeline.Result r = pipeline(runner, null, () -> false, fastPlan()).run("火影忍者", "火影", 9L);

        assertEquals(SelectStage.DESIGNATED, r.stageUsed());
        assertEquals(List.of(SelectStage.CACHE, SelectStage.DESIGNATED), runner.visited);
    }

    @Test
    @DisplayName("精确匹配失败后自动降级到模糊匹配")
    void fallsBackToFuzzy() {
        FakeRunner runner = new FakeRunner();
        runner.outcomes.put(SelectStage.FUZZY, SelectPipeline.StageOutcome.hit(List.of(cand(3, "C站")), playable("https://cdn/f.m3u8")));

        SelectPipeline.Result r = pipeline(runner, null, () -> false, fastPlan()).run("火影忍者 疾风传", "火影忍者", null);

        assertTrue(r.hit());
        assertEquals(SelectStage.FUZZY, r.stageUsed());
        assertEquals(List.of(SelectStage.CACHE, SelectStage.EXACT, SelectStage.FUZZY), runner.visited);
    }

    @Test
    @DisplayName("某阶段超时不再等待，继续下一阶段（降级而不是整体失败）")
    void timeoutDegradesToNextStage() throws Exception {
        FakeRunner runner = new FakeRunner() {
            @Override
            public StageOutcome run(SelectStage stage, String keyword, Long designatedRuleId) throws Exception {
                visited.add(stage);
                if (stage == SelectStage.EXACT) {
                    Thread.sleep(400);
                    return StageOutcome.hit(List.of(cand(5, "慢源")), playable("https://cdn/slow.m3u8"));
                }
                if (stage == SelectStage.FUZZY) {
                    return StageOutcome.hit(List.of(cand(6, "快源")), playable("https://cdn/fast.m3u8"));
                }
                return StageOutcome.miss("miss");
            }
        };
        SelectPipeline.TimeoutPlan plan = new SelectPipeline.TimeoutPlan(2000, 2000, 40, 2000);
        long t0 = System.currentTimeMillis();

        SelectPipeline.Result r = pipeline(runner, null, () -> false, plan).run("火影忍者", "火影", null);

        assertTrue(r.hit());
        assertEquals(SelectStage.FUZZY, r.stageUsed());
        assertEquals("https://cdn/fast.m3u8", r.payload().get("url"));
        assertTrue(System.currentTimeMillis() - t0 < 1500, "超时后应立即降级，不应等满 400ms 的慢源");
    }

    @Test
    @DisplayName("全阶段失败：hit=false，但仍把已收集候选带回去给前端换源")
    void allStagesMissKeepsCandidates() {
        FakeRunner runner = new FakeRunner();
        runner.outcomes.put(SelectStage.EXACT, SelectPipeline.StageOutcome.miss(
                List.of(cand(1, "A站")), List.of(7L), "精确匹配无直链"));
        runner.outcomes.put(SelectStage.FUZZY, SelectPipeline.StageOutcome.miss(
                List.of(cand(2, "B站")), List.of(), "模糊匹配无直链"));

        SelectPipeline.Result r = pipeline(runner, null, () -> false, fastPlan()).run("火影忍者", "火影", null);

        assertFalse(r.hit());
        assertNull(r.stageUsed());
        assertEquals("NONE", r.payload().get("stageUsed"));
        assertNull(r.payload().get("url"));
        assertNotNull(r.payload().get("message"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) r.payload().get("candidates");
        assertEquals(2, candidates.size());
    }

    @Test
    @DisplayName("同一规则同一标题在不同阶段重复出现时去重")
    void dedupesCandidatesAcrossStages() {
        FakeRunner runner = new FakeRunner();
        runner.outcomes.put(SelectStage.EXACT, SelectPipeline.StageOutcome.miss(List.of(cand(1, "A站")), List.of(), "miss"));
        runner.outcomes.put(SelectStage.FUZZY, SelectPipeline.StageOutcome.miss(List.of(cand(1, "A站")), List.of(), "miss"));

        List<SelectEvent> events = new ArrayList<>();
        SelectPipeline.Result r = pipeline(runner, events::add, () -> false, fastPlan()).run("火影忍者", "火影", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) r.payload().get("candidates");
        assertEquals(1, candidates.size());
        long candidateEvents = events.stream().filter(e -> SelectEvent.TYPE_CANDIDATE.equals(e.type())).count();
        assertEquals(1, candidateEvents);
        assertTrue(events.stream().anyMatch(e -> SelectEvent.TYPE_FINAL.equals(e.type())));
        assertTrue(events.stream().anyMatch(e -> SelectEvent.TYPE_STAGE.equals(e.type())));
    }

    @Test
    @DisplayName("客户端取消：不执行任何阶段，直接返回 CANCELLED")
    void cancelledBeforeStart() {
        FakeRunner runner = new FakeRunner();
        AtomicInteger calls = new AtomicInteger();

        SelectPipeline.Result r = pipeline(runner, null, () -> true, fastPlan()).run("火影忍者", "火影", null);

        assertFalse(r.hit());
        assertEquals("CANCELLED", r.payload().get("stageUsed"));
        assertEquals(0, runner.visited.size());
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("监听器异常不能打断状态机")
    void listenerFailureIsSwallowed() {
        FakeRunner runner = new FakeRunner();
        runner.outcomes.put(SelectStage.CACHE, SelectPipeline.StageOutcome.hit(List.of(cand(1, "A站")), playable("https://cdn/x.m3u8")));

        SelectPipeline.Result r = pipeline(runner, e -> { throw new IllegalStateException("client gone"); },
                () -> false, fastPlan()).run("火影忍者", "火影", null);

        assertTrue(r.hit());
    }
}
