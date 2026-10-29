package com.abc.danmakuservice.util;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 番剧 / 剧集标题匹配打分工具（三级匹配中的 TITLE_EXACT / TITLE_FUZZY 依赖它）。
 *
 * <p>设计意图：上游 dandanplay 的剧集标题格式非常不统一（"第1话"、"第 1 集 标题"、
 * "EP01"、"01"），直接字符串相等几乎匹配不上。这里提供两种互补的相似度：
 * <ul>
 *   <li>Jaccard：字符二元组集合的交并比，抗「顺序小差异 / 多余后缀」</li>
 *   <li>编辑距离比：1 - levenshtein/maxLen，抗「少一个字 / 错一个字」</li>
 * </ul>
 * 取两者最大值作为最终相似度，阈值 0.6（见 {@code DanmakuSourceService}）。
 */
public final class DanmakuTitleMatcher {

    /** 集号前缀："第1话" / "第 12 集" / "第3話" */
    private static final Pattern EP_PREFIX = Pattern.compile("^第\\s*\\d+\\s*[话話集][\\s:：.、-]*");

    /** 常见集号前缀："EP01" / "E01" */
    private static final Pattern EP_PREFIX_EN = Pattern.compile("^(?i)(ep|e)\\s*\\d+[\\s:：.、-]*");

    /** 括号内容：【】()（）[] */
    private static final Pattern BRACKETS = Pattern.compile("[【】\\[\\]()（）]");

    private DanmakuTitleMatcher() {
    }

    /**
     * 标题归一化：去「第x话/EPxx」前缀、去括号、全角转半角、去空白标点、转小写。
     */
    public static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        String t = title.trim();
        t = EP_PREFIX.matcher(t).replaceFirst("");
        t = EP_PREFIX_EN.matcher(t).replaceFirst("");
        t = BRACKETS.matcher(t).replaceAll("");
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char half = DanmakuTextNormalizer.toHalfWidth(t.charAt(i));
            if (Character.isWhitespace(half)) {
                continue;
            }
            if (!Character.isLetterOrDigit(half)) {
                continue;
            }
            sb.append(Character.toLowerCase(half));
        }
        return sb.toString();
    }

    /** 两个标题的归一化后是否完全相等（TITLE_EXACT 用的判定） */
    public static boolean isSameTitle(String a, String b) {
        String na = normalizeTitle(a);
        String nb = normalizeTitle(b);
        return !na.isEmpty() && na.equals(nb);
    }

    /**
     * 综合相似度 = max(Jaccard(字符二元组), 编辑距离比)，取值 [0,1]。
     */
    public static double similarity(String a, String b) {
        String na = normalizeTitle(a);
        String nb = normalizeTitle(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return 0d;
        }
        if (na.equals(nb)) {
            return 1d;
        }
        return Math.max(jaccardByBigram(na, nb), levenshteinRatio(na, nb));
    }

    /** 字符二元组 Jaccard 系数；长度不足 2 时退化为字符集合 */
    public static double jaccardByBigram(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        if (a.length() < 2 || b.length() < 2) {
            return a.equals(b) ? 1d : 0d;
        }
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0d : (double) inter.size() / union.size();
    }

    /** 编辑距离比：1 - dist / maxLen */
    public static double levenshteinRatio(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        int dist = levenshtein(a, b);
        int maxLen = Math.max(a.length(), b.length());
        return 1d - (double) dist / maxLen;
    }

    /** 标准 Levenshtein 编辑距离（插入/删除/替换各计 1） */
    public static int levenshtein(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[m];
    }

    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }
}
