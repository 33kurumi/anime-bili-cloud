package com.abc.danmakuservice.source.dandanplay;

import com.abc.danmakuservice.source.dandanplay.DandanplayClient.DandanEpisode;
import com.abc.danmakuservice.util.DanmakuTitleMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三级匹配（BANGUMI_ID → TITLE_EXACT → TITLE_FUZZY）单测。
 *
 * <p>匹配错集会直接导致"弹幕串台"，是弹幕体验最致命的 bug，
 * 所以匹配器写成纯函数并在这里覆盖每一级的命中与不命中场景。
 */
class DanmakuEpisodeMatcherTest {

    private static DandanEpisode ep(long id, String title, String number) {
        return new DandanEpisode(id, title, number);
    }

    @Test
    @DisplayName("一级：bangumiId 换来的剧集表按集号精确命中")
    void matchByBangumiId() {
        List<DandanEpisode> episodes = List.of(ep(101, "第1话 出会い", "1"), ep(102, "第2话 再会", "2"));
        DanmakuMatchResult result = DanmakuEpisodeMatcher.match(episodes, true, 2, "某番剧");
        assertTrue(result.matched());
        assertEquals(DanmakuMatchMethod.BANGUMI_ID, result.method());
        assertEquals(102L, result.episode().episodeId());
        assertEquals(1.0d, result.score());
    }

    @Test
    @DisplayName("二级：无 bangumiId 时标题归一化相等 + 集号命中")
    void matchByTitleExact() {
        List<DandanEpisode> episodes = List.of(ep(201, "第1话 某科学的超电磁炮", "1"));
        DanmakuMatchResult result = DanmakuEpisodeMatcher.match(episodes, false, 1, "某科学的超电磁炮");
        assertEquals(DanmakuMatchMethod.TITLE_EXACT, result.method());
        assertEquals(201L, result.episode().episodeId());
    }

    @Test
    @DisplayName("二级：集号 ±1 容错命中，得分降到 0.9")
    void matchByTitleExactWithEpisodeTolerance() {
        // 只有第 2 集，请求第 1 集：日番"第0话算第1集"很常见，允许 ±1 集
        List<DandanEpisode> episodes = List.of(ep(301, "第2话 进击的巨人", "2"));
        DanmakuMatchResult result = DanmakuEpisodeMatcher.match(episodes, false, 1, "进击的巨人");
        assertEquals(DanmakuMatchMethod.TITLE_EXACT, result.method());
        assertEquals(301L, result.episode().episodeId());
        assertEquals(0.9d, result.score());
    }

    @Test
    @DisplayName("三级：标题高度相似（≥0.6）但非完全相等时走模糊匹配")
    void matchByTitleFuzzy() {
        // 集标题少一个字，归一化后不相等，但二元组相似度 0.75 ≥ 0.6
        List<DandanEpisode> episodes = List.of(ep(401, "进击的巨", "1"));
        DanmakuMatchResult result = DanmakuEpisodeMatcher.match(episodes, false, 1, "进击的巨人");
        assertEquals(DanmakuMatchMethod.TITLE_FUZZY, result.method());
        assertTrue(result.score() >= DanmakuEpisodeMatcher.FUZZY_THRESHOLD);
    }

    @Test
    @DisplayName("三级：相似度不足时不乱配，返回 NONE")
    void noMatchWhenTitleTooDifferent() {
        List<DandanEpisode> episodes = List.of(ep(501, "完全无关的另一个番", "1"));
        DanmakuMatchResult result = DanmakuEpisodeMatcher.match(episodes, false, 1, "进击的巨人");
        assertFalse(result.matched());
        assertEquals(DanmakuMatchMethod.NONE, result.method());
    }

    @Test
    @DisplayName("剧集表为空时直接返回 NONE")
    void emptyEpisodes() {
        DanmakuMatchResult result = DanmakuEpisodeMatcher.match(List.of(), true, 1, "任意");
        assertFalse(result.matched());
        assertEquals(DanmakuMatchMethod.NONE, result.method());
    }

    @Test
    @DisplayName("集号解析：'01' → 1，'1.5' → 1，非数字 → null")
    void episodeNumberOf() {
        assertEquals(1, DanmakuEpisodeMatcher.episodeNumberOf("01"));
        assertEquals(1, DanmakuEpisodeMatcher.episodeNumberOf("1.5"));
        assertEquals(null, DanmakuEpisodeMatcher.episodeNumberOf("OVA"));
        assertEquals(null, DanmakuEpisodeMatcher.episodeNumberOf(null));
    }

    @Test
    @DisplayName("片头/季名等噪声词被归一化剥离，不影响相似度比较")
    void titleNormalization() {
        assertEquals(DanmakuTitleMatcher.normalizeTitle("第12话：某番剧"),
                DanmakuTitleMatcher.normalizeTitle("【某番剧】"));
        assertTrue(DanmakuTitleMatcher.similarity("进击的巨人", "进击的巨") > 0.6);
        assertNotNull(DanmakuTitleMatcher.normalizeTitle(null));
    }
}
