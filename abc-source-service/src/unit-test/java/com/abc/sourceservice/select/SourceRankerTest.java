package com.abc.sourceservice.select;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五级比较器单测：纯逻辑，不起 Spring。
 *
 * <p>覆盖点：weight 优先 → successRate → responseTime → health → 随机；
 * 以及 rankReason 是否真的把「为什么排第一」说清楚了（前端要展示这几行）。
 */
class SourceRankerTest {

    private final SourceRanker ranker = new SourceRanker();

    private static SourceRanker.RankInput in(long id, String name, double score, int weight,
                                            double rate, long rt, String health) {
        return new SourceRanker.RankInput(id, name, "火影忍者", null, "https://a.com/1", score,
                weight, rate, rt, health);
    }

    @Test
    @DisplayName("第1级：权重高的排前面")
    void ordersByWeightFirst() {
        List<RankedCandidate> ranked = ranker.rank(List.of(
                in(1, "低权重", 0.99, 1, 0.99, 10, "UP"),
                in(2, "高权重", 0.50, 9, 0.50, 5000, "DOWN"),
                in(3, "中权重", 0.70, 5, 0.70, 100, "UNKNOWN")));
        assertEquals(3, ranked.size());
        assertEquals("高权重", ranked.get(0).sourceName());
        assertEquals("中权重", ranked.get(1).sourceName());
        assertEquals("低权重", ranked.get(2).sourceName());
        assertEquals(2, ranked.get(0).ruleId());
    }

    @Test
    @DisplayName("第2级：权重相同比成功率")
    void successRateBreaksTie() {
        List<RankedCandidate> ranked = ranker.rank(List.of(
                in(1, "A", 0.8, 5, 0.40, 100, "UP"),
                in(2, "B", 0.8, 5, 0.90, 900, "UP")));
        assertEquals("B", ranked.get(0).sourceName());
    }

    @Test
    @DisplayName("第3级：权重+成功率相同比响应时间，未知(-1)排最后")
    void responseTimeAscends() {
        List<RankedCandidate> ranked = ranker.rank(List.of(
                in(1, "慢", 0.8, 5, 0.9, 3000, "UP"),
                in(2, "未知", 0.8, 5, 0.9, -1, "UP"),
                in(3, "快", 0.8, 5, 0.9, 120, "UP")));
        assertEquals("快", ranked.get(0).sourceName());
        assertEquals("慢", ranked.get(1).sourceName());
        assertEquals("未知", ranked.get(2).sourceName());
    }

    @Test
    @DisplayName("第4级：前三级都相同时按 UP>UNKNOWN>DEGRADED>DOWN")
    void healthOrder() {
        List<RankedCandidate> ranked = ranker.rank(List.of(
                in(1, "DOWN源", 0.8, 5, 0.9, 100, "DOWN"),
                in(2, "UP源", 0.8, 5, 0.9, 100, "UP")));
        assertEquals("UP源", ranked.get(0).sourceName());
        assertEquals(0, SourceRanker.healthRank("UP"));
        assertEquals(3, SourceRanker.healthRank("DOWN"));
    }

    @Test
    @DisplayName("rankReason 带五级比较链说明和名次，直接给前端展示")
    void reasonExplainsOrdering() {
        List<RankedCandidate> ranked = ranker.rank(List.of(in(7, "A站", 0.9, 6, 0.8, 340, "UP")));
        RankedCandidate c = ranked.get(0);
        assertTrue(c.rankReason().stream().anyMatch(r -> r.contains("权重=6")));
        assertTrue(c.rankReason().stream().anyMatch(r -> r.contains("成功率=0.80")));
        assertTrue(c.rankReason().stream().anyMatch(r -> r.contains("响应=340ms")));
        assertTrue(c.rankReason().stream().anyMatch(r -> r.contains("排序名次=#1/1")));
        assertTrue(c.rankScore() > 0 && c.rankScore() <= 100);
        assertFalse(c.isDirect());
    }

    @Test
    @DisplayName("rankOne 用于指定源：单项也要有名次")
    void rankOne() {
        RankedCandidate one = ranker.rankOne(in(3, "指定源", 0.85, 0, 0.0, -1, null));
        assertEquals("指定源", one.sourceName());
        assertEquals("UNKNOWN", one.health());
        assertTrue(one.rankReason().contains("排序名次=#1/1"));
    }

    @Test
    @DisplayName("空输入不炸")
    void emptyInput() {
        assertTrue(ranker.rank(List.of()).isEmpty());
    }
}
