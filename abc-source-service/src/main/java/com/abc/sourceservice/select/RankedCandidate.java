package com.abc.sourceservice.select;

import java.util.ArrayList;
import java.util.List;

/**
 * 带排序证据的播放候选：在 {@code SourceAggregator.Candidate} 的“能不能播”信息之外，
 * 补齐“为什么排在这个位置”的元数据（权重/成功率/响应时间/健康度/综合分）。
 *
 * <p>rankReason 是要直接吐给前端“换源”面板的，所以必须是给人看的中文短句。
 */
public record RankedCandidate(
        Long ruleId,
        String sourceName,
        String matchedTitle,
        String directUrl,
        String episodePageUrl,
        double matchScore,
        int weight,
        double successRate,
        long responseTimeMs,
        String health,
        double rankScore,
        List<String> rankReason,
        double tieBreaker) {

    public boolean isDirect() {
        return directUrl != null && !directUrl.isBlank();
    }

    /** 追加“第 N 名”，让前端能直接展示排序结论。 */
    public RankedCandidate withRank(int rank, int total) {
        List<String> reasons = new ArrayList<>(rankReason == null ? List.of() : rankReason);
        reasons.add("排序名次=#" + rank + "/" + total);
        return new RankedCandidate(ruleId, sourceName, matchedTitle, directUrl, episodePageUrl,
                matchScore, weight, successRate, responseTimeMs, health, rankScore,
                List.copyOf(reasons), tieBreaker);
    }
}
