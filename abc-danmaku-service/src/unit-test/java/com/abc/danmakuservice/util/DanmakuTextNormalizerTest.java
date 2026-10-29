package com.abc.danmakuservice.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹幕文本归一化单测（去重指纹的基础）。
 *
 * <p>归一化的目标是让「看起来一样」的弹幕得到同一个指纹，从而被合并：
 * 全角/半角、标点/空格、连续刷屏字符、大小写都不应该产生不同指纹。
 */
class DanmakuTextNormalizerTest {

    @Test
    @DisplayName("全角字符转半角并转小写")
    void normalizeFullWidth() {
        assertEquals("abc123", DanmakuTextNormalizer.normalize("ＡＢＣ１２３"));
    }

    @Test
    @DisplayName("标点与空白被移除")
    void normalizeRemovesPunctuation() {
        assertEquals("前方高能", DanmakuTextNormalizer.normalize("前方 高能！！！"));
    }

    @Test
    @DisplayName("连续 3 个以上相同字符压缩为 1 个（刷屏治理）")
    void normalizeSqueezeRepeats() {
        assertEquals("哈", DanmakuTextNormalizer.normalize("哈哈哈哈哈哈"));
        assertEquals("23", DanmakuTextNormalizer.normalize("23333333"));
    }

    @Test
    @DisplayName("全角与半角输入的指纹一致，去重才能生效")
    void fingerprintIgnoresWidthAndCase() {
        assertEquals(DanmakuTextNormalizer.fingerprint("ABC"),
                DanmakuTextNormalizer.fingerprint("ＡＢＣ"));
        assertNotEquals(DanmakuTextNormalizer.fingerprint("ABC"),
                DanmakuTextNormalizer.fingerprint("ABD"));
    }

    @Test
    @DisplayName("长度按码点计算，emoji 不会被算成多个字符")
    void codePointLengthCountsEmojiAsOne() {
        assertEquals(2, DanmakuTextNormalizer.codePointLength("😀😀"));
        assertEquals(3, DanmakuTextNormalizer.codePointLength("abc"));
        assertEquals(0, DanmakuTextNormalizer.codePointLength(null));
    }

    @Test
    @DisplayName("按码点截断不会把 emoji 截成半个")
    void truncateByCodePointKeepsSurrogatePair() {
        String truncated = DanmakuTextNormalizer.truncateByCodePoint("😀😀😀", 2);
        assertEquals(2, DanmakuTextNormalizer.codePointLength(truncated));
        assertTrue(truncated.length() >= 2);
    }
}
