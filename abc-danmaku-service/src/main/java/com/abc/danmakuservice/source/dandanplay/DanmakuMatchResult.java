package com.abc.danmakuservice.source.dandanplay;

import com.abc.danmakuservice.source.dandanplay.DandanplayClient.DandanEpisode;

/**
 * 一次三级匹配的结果：命中的 dandan 剧集 + 命中层级 + 相似度分值（BANGUMI_ID 记为 1.0）。
 *
 * @param episode 命中的上游剧集
 * @param method  命中层级
 * @param score   该层级的打分（用于日志与验收，TITLE_* 为相似度）
 */
public record DanmakuMatchResult(DandanEpisode episode, DanmakuMatchMethod method, double score) {

    public boolean matched() {
        return episode != null && method != null && method != DanmakuMatchMethod.NONE;
    }
}
