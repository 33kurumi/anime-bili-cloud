package com.abc.sourceservice.select;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 事件（对齐前端 EventSource 的 {@code event:} / {@code data:} 两段）。
 *
 * <p>事件类型只用三种，前端 switch 起来简单：
 * <ul>
 *   <li>{@code stage}     阶段开始/结束（含超时、降级原因）——画“进度条”用；</li>
 *   <li>{@code candidate} 某个源给出的候选及其排序证据——画“换源”列表用；</li>
 *   <li>{@code final}     终态（成功给 url/proxyUrl，失败给 message）——关流用。</li>
 * </ul>
 */
public record SelectEvent(String type, Map<String, Object> data) {

    public static final String TYPE_STAGE = "stage";
    public static final String TYPE_CANDIDATE = "candidate";
    public static final String TYPE_FINAL = "final";
    public static final String TYPE_ERROR = "error";

    public static SelectEvent stageStart(SelectStage stage, String keyword, long timeoutMs) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("stage", stage.name());
        d.put("label", stage.getLabel());
        d.put("status", "START");
        d.put("keyword", keyword == null ? "" : keyword);
        d.put("timeoutMs", timeoutMs);
        d.put("deadlineAt", System.currentTimeMillis() + timeoutMs);
        return new SelectEvent(TYPE_STAGE, d);
    }

    public static SelectEvent stageResult(SelectStage stage, String status, long timeoutMs, String note) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("stage", stage.name());
        d.put("label", stage.getLabel());
        d.put("status", status);
        d.put("timeoutMs", timeoutMs);
        if (note != null && !note.isBlank()) d.put("note", note);
        return new SelectEvent(TYPE_STAGE, d);
    }

    public static SelectEvent candidate(RankedCandidate c, int index) {
        return new SelectEvent(TYPE_CANDIDATE, candidateMap(c, index));
    }

    /** 候选 → 前端 DTO（字段名与 /api/source/play 的 candidates[] 完全一致）。 */
    public static Map<String, Object> candidateMap(RankedCandidate c, int index) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("ruleId", c.ruleId());
        m.put("name", c.sourceName());
        m.put("matchedTitle", c.matchedTitle());
        m.put("score", c.matchScore());
        m.put("rankScore", c.rankScore());
        m.put("rankReason", c.rankReason());
        m.put("weight", c.weight());
        m.put("successRate", c.successRate());
        m.put("responseTime", c.responseTimeMs());
        m.put("health", c.health());
        m.put("direct", c.isDirect());
        m.put("episodePageUrl", c.episodePageUrl() == null ? "" : c.episodePageUrl());
        return m;
    }

    public static SelectEvent finalEvent(Map<String, Object> payload) {
        return new SelectEvent(TYPE_FINAL, payload);
    }

    public static SelectEvent error(String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("message", message);
        return new SelectEvent(TYPE_ERROR, d);
    }
}
