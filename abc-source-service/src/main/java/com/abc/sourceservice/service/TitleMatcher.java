package com.abc.sourceservice.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 标题匹配器（研究核心 C2 的传统基线）。
 *
 * <p>把资源站的野标题（"进击的巨人 第一季" / "進撃の巨人 S1"）归一化后，
 * 与标准标题计算相似度。后续可在此接入 LLM 语义精排（混合匹配）。
 */
@Component
public class TitleMatcher {

    private static final Pattern SEASON_WORDS = Pattern.compile(
            "[第]?[0-9一二三四五六七八九十]+[季期]|season\\s*[0-9]+|s[0-9]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKETS = Pattern.compile("[（(【\\[].*?[）)】\\]]");

    /** 归一化：去括号内容、去季数词、全角转半角、去空白标点、转小写。 */
    public String normalize(String title) {
        if (title == null) return "";
        String t = BRACKETS.matcher(title).replaceAll(" ");
        t = SEASON_WORDS.matcher(t).replaceAll(" ");
        StringBuilder sb = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            char half = (c >= '！' && c <= '～') ? (char) (c - 0xFEE0) : c;
            if (Character.isWhitespace(half)) continue;
            if ("-_·:：,.，。、~～!！?？".indexOf(half) >= 0) continue;
            sb.append(Character.toLowerCase(half));
        }
        return sb.toString();
    }

    /** 相似度 0~1：归一化后的编辑距离比值。 */
    public double similarity(String a, String b) {
        String na = normalize(a), nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return 0.0;
        if (na.equals(nb)) return 1.0;
        if (na.contains(nb) || nb.contains(na)) return 0.85;
        int dist = levenshtein(na, nb);
        return 1.0 - (double) dist / Math.max(na.length(), nb.length());
    }

    /** 判断是否可作为候选匹配（阈值）。 */
    public boolean matches(String standardTitle, String candidateTitle, double threshold) {
        return similarity(standardTitle, candidateTitle) >= threshold;
    }

    static int levenshtein(String s, String t) {
        int n = s.length(), m = t.length();
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = s.charAt(i - 1) == t.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[m];
    }
}
