package com.abc.sourceservice.engine;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/** 规则引擎的 HTTP 抓取器：统一注入 UA / Referer，随机 UA 兜底。 */
@Slf4j
public final class HttpFetcher {

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final String[] UA_POOL = {
            DEFAULT_UA,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"
    };

    private HttpFetcher() { }

    public static String get(String url, RuleExecutionConfig cfg) {
        return request(url, cfg, false, null, 15000);
    }

    public static String post(String url, RuleExecutionConfig cfg, String body) {
        return request(url, cfg, true, body, 15000);
    }

    /**
     * 发起请求，返回响应体；非 200 抛 RuleException。
     */
    public static String request(String url, RuleExecutionConfig cfg, boolean post,
                                 String body, int timeoutMs) {
        String ua = (cfg != null && cfg.getUserAgent() != null && !cfg.getUserAgent().isBlank())
                ? cfg.getUserAgent()
                : UA_POOL[(int) (System.nanoTime() % UA_POOL.length)];
        try {
            HttpRequest req = post ? HttpRequest.post(url) : HttpRequest.get(url);
            req.header("User-Agent", ua)
               .header("Accept", "*/*")
               .setFollowRedirects(true)   // 跟随 301/302（很多站会跳转域名）
               .timeout(timeoutMs);
            if (cfg != null && cfg.getReferer() != null && !cfg.getReferer().isBlank()) {
                req.header("Referer", cfg.getReferer());
            }
            if (post && body != null) {
                req.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                req.body(body);
            }
            HttpResponse resp = req.execute();
            if (resp.getStatus() != 200) {
                throw new RuntimeException("HTTP " + resp.getStatus());
            }
            return resp.body();
        } catch (RuleException e) {
            throw e;
        } catch (Exception e) {
            throw new RuleException(RuleException.Reason.SEARCH_ERROR,
                    cfg != null ? cfg.getName() : "?", "请求失败: " + e.getMessage(), e);
        }
    }

    /** 带自定义请求头 GET（供 API 模式使用）。 */
    public static String getWithHeaders(String url, Map<String, String> headers, int timeoutMs) {
        try {
            HttpRequest req = HttpRequest.get(url).timeout(timeoutMs);
            if (headers != null) headers.forEach(req::header);
            HttpResponse resp = req.execute();
            if (resp.getStatus() != 200) throw new RuntimeException("HTTP " + resp.getStatus());
            return resp.body();
        } catch (Exception e) {
            throw new RuleException(RuleException.Reason.SEARCH_ERROR, "api",
                    "请求失败: " + e.getMessage(), e);
        }
    }
}
