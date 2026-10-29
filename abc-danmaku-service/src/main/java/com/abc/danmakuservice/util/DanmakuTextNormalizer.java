package com.abc.danmakuservice.util;

/**
 * 弹幕文本归一化工具（借鉴 Kazumi 的去重思路，纯函数、无状态，便于单测）。
 *
 * <p>为什么需要归一化：同一条弹幕在 5 秒内被多人刷、或者同一个人用全角/半角、
 * 加空格、加标点的方式重复发送，肉眼一样、字节不同。只有先归一化再比较，
 * 才能在广播层把「哈哈哈哈」「哈哈哈哈哈！！」「ＨＨＨ」这类刷屏合并掉。
 *
 * <p>归一化规则（可单测验证）：
 * <ol>
 *   <li>全角字符转半角（U+FF01~U+FF5E 减 0xFEE0，U+3000 转空格）</li>
 *   <li>去掉所有空白与标点（只保留字母、数字、汉字等 letter/digit）</li>
 *   <li>连续 3 个及以上相同字符压缩为 1 个（保留 2 个及以内的原始重复，避免误伤"233"）</li>
 *   <li>转小写</li>
 * </ol>
 */
public final class DanmakuTextNormalizer {

    private DanmakuTextNormalizer() {
    }

    /**
     * 归一化文本；null / 空白返回空串。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder filtered = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            char half = toHalfWidth(c);
            if (Character.isWhitespace(half) || Character.isSpaceChar(half)) {
                continue;
            }
            if (!Character.isLetterOrDigit(half)) {
                continue;
            }
            filtered.append(Character.toLowerCase(half));
        }
        return squeezeRepeats(filtered.toString());
    }

    /** 全角转半角（含全角空格） */
    public static char toHalfWidth(char c) {
        if (c == '\u3000') {
            return ' ';
        }
        if (c >= '\uFF01' && c <= '\uFF5E') {
            return (char) (c - 0xFEE0);
        }
        return c;
    }

    /**
     * 连续相同字符 ≥3 时压缩为 1 个；1~2 个保持原样。
     * 例："哈哈哈哈哈" → "哈"，"23333" → "23"，"233" → "233"（保留梗）。
     */
    public static String squeezeRepeats(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int j = i + 1;
            while (j < s.length() && s.charAt(j) == c) {
                j++;
            }
            int run = j - i;
            if (run >= 3) {
                out.append(c);
            } else {
                for (int k = 0; k < run; k++) {
                    out.append(c);
                }
            }
            i = j;
        }
        return out.toString();
    }

    /**
     * 去重指纹：归一化文本的哈希前缀。用作 Redis 去重键的稳定片段。
     * 用 32 位 hashCode 的十六进制已足够（只用于短窗口内判重，不做安全用途）。
     */
    public static String fingerprint(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return "empty";
        }
        return Integer.toHexString(normalized.hashCode());
    }

    /** 按字符（code point）截断，防止代理对（emoji）被截断成半个字符 */
    public static String truncateByCodePoint(String raw, int maxCodePoints) {
        if (raw == null) {
            return null;
        }
        if (maxCodePoints <= 0) {
            return "";
        }
        int count = raw.codePointCount(0, raw.length());
        if (count <= maxCodePoints) {
            return raw;
        }
        int end = raw.offsetByCodePoints(0, maxCodePoints);
        return raw.substring(0, end);
    }

    /** 统计字符数（code point），用于长度校验，避免 emoji 算 2 个字符 */
    public static int codePointLength(String raw) {
        return raw == null ? 0 : raw.codePointCount(0, raw.length());
    }
}
