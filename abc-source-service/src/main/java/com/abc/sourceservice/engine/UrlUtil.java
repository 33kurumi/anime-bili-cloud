package com.abc.sourceservice.engine;

/** URL 归一化：把相对链接拼到 baseUrl 上（对齐 Kazumi normalizeEpisodeUrl）。 */
public final class UrlUtil {

    private UrlUtil() { }

    public static String normalize(String baseUrl, String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String url = raw.trim();
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (baseUrl == null || baseUrl.isBlank()) return url;

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (url.startsWith("//")) {
            // 协议相对
            String scheme = base.contains("://") ? base.substring(0, base.indexOf("://")) : "https";
            return scheme + ":" + url;
        }
        if (url.startsWith("/")) {
            return base + url;
        }
        return base + "/" + url;
    }
}
