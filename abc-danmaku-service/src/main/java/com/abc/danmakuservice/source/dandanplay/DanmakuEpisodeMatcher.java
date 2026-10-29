package com.abc.danmakuservice.source.dandanplay;

import com.abc.danmakuservice.source.dandanplay.DandanplayClient.DandanEpisode;
import com.abc.danmakuservice.util.DanmakuTitleMatcher;

import java.util.List;

/**
 * 三级匹配器（纯函数，无 Spring 依赖，便于 JUnit5 直接覆盖）。
 *
 * <p>为什么需要三级：
 * <ol>
 *   <li><b>BANGUMI_ID</b>：用 Bangumi subject id 换 dandan 官方剧集表，再按集号命中。
 *       只要番剧 id 对，就一定不会串集，这是最可靠的一级。</li>
 *   <li><b>TITLE_EXACT</b>：没有 bangumi id 时按标题归一化完全相等（或集标题只含"第x话"这类空壳）
 *       匹配，允许集号 ±1：日番经常出现"第0话/OVA 算作第1集"的偏移。</li>
 *   <li><b>TITLE_FUZZY</b>：标题相似度（Jaccard 字符二元组 / 编辑距离比）≥ {@link #FUZZY_THRESHOLD}，
 *       用于"第二季""剧场版""中文字幕组翻译不同"等场景。</li>
 * </ol>
 * 每一级都会把命中层级写回 {@link DanmakuMatchMethod}，落库后可以统计各级命中率。
 */
public final class DanmakuEpisodeMatcher {

    /** 模糊匹配阈值：0.6 是"半数以上字符二元组重合"的经验值，低于它误匹配明显变多 */
    public static final double FUZZY_THRESHOLD = 0.6;

    /** 集号容错：0（精确）、-1、+1；顺序即优先级 */
    private static final int[] EP_TOLERANCE = {0, -1, 1};

    private DanmakuEpisodeMatcher() {
    }

    /**
     * 在 dandan 剧集表里三级匹配目标集。
     *
     * @param episodes   dandan 剧集表
     * @param viaBangumi 剧集表是否由 Bangumi subject id 换取（决定能否走 BANGUMI_ID 一级）
     * @param episodeNum 目标集号
     * @param title      番剧标题（可空）
     * @return 匹配结果，未命中时 method=NONE
     */
    public static DanmakuMatchResult match(List<DandanEpisode> episodes,
                                           boolean viaBangumi,
                                           int episodeNum,
                                           String title) {
        if (episodes == null || episodes.isEmpty()) {
            return new DanmakuMatchResult(null, DanmakuMatchMethod.NONE, 0d);
        }

        // ── 一级：Bangumi ID 精确（剧集表来源可信） ──────────────────
        if (viaBangumi) {
            DandanEpisode exact = findByEpisodeNumber(episodes, episodeNum);
            if (exact != null) {
                return new DanmakuMatchResult(exact, DanmakuMatchMethod.BANGUMI_ID, 1d);
            }
        }

        String normalizedTitle = DanmakuTitleMatcher.normalizeTitle(title);

        // ── 二级：标题精确 + 集号（含 ±1 集容错） ───────────────────
        for (int delta : EP_TOLERANCE) {
            DandanEpisode candidate = findByEpisodeNumber(episodes, episodeNum + delta);
            if (candidate == null) {
                continue;
            }
            String episodeTitle = DanmakuTitleMatcher.normalizeTitle(candidate.episodeTitle());
            boolean titleConsistent = episodeTitle.isEmpty()
                    || episodeTitle.equals(normalizedTitle)
                    || (!normalizedTitle.isEmpty() && episodeTitle.startsWith(normalizedTitle));
            if (titleConsistent) {
                // 集号本身偏移时降一点分，便于事后统计"靠容错才匹配上"的比例
                double score = delta == 0 ? 1d : 0.9d;
                return new DanmakuMatchResult(candidate, DanmakuMatchMethod.TITLE_EXACT, score);
            }
        }

        // ── 三级：标题模糊（只在集号 ±1 范围内比较，避免跨集误配） ──
        DandanEpisode best = null;
        double bestScore = 0d;
        for (DandanEpisode e : episodes) {
            Integer num = episodeNumberOf(e.episodeNumber());
            if (num != null && Math.abs(num - episodeNum) > 1) {
                continue;
            }
            double score = DanmakuTitleMatcher.similarity(title, e.episodeTitle());
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (best != null && bestScore >= FUZZY_THRESHOLD) {
            return new DanmakuMatchResult(best, DanmakuMatchMethod.TITLE_FUZZY, bestScore);
        }

        // ── 兜底：集号缺失时按"第 N 个 = 第 N 集"取（可信度最低，记为模糊级） ──
        if (episodeNum >= 1 && episodeNum <= episodes.size()) {
            DandanEpisode byIndex = episodes.get(episodeNum - 1);
            if (byIndex.episodeNumber() == null || byIndex.episodeNumber().isBlank()) {
                return new DanmakuMatchResult(byIndex, DanmakuMatchMethod.TITLE_FUZZY, FUZZY_THRESHOLD);
            }
        }
        return new DanmakuMatchResult(null, DanmakuMatchMethod.NONE, bestScore);
    }

    /** 按 dandan 的 episodeNumber 字符串找集（支持 "1" / "01" / "1.5" 前缀数字） */
    public static DandanEpisode findByEpisodeNumber(List<DandanEpisode> episodes, int target) {
        if (episodes == null) {
            return null;
        }
        for (DandanEpisode e : episodes) {
            Integer num = episodeNumberOf(e.episodeNumber());
            if (num != null && num == target) {
                return e;
            }
        }
        return null;
    }

    /** 解析 episodeNumber 的前缀整数，解析不出来返回 null（"1.5" → 1，"01" → 1，"" → null） */
    public static Integer episodeNumberOf(String episodeNumber) {
        if (episodeNumber == null) {
            return null;
        }
        String s = episodeNumber.trim();
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return null;
        }
        try {
            long v = Long.parseLong(s.substring(0, i));
            return v > Integer.MAX_VALUE ? null : (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
