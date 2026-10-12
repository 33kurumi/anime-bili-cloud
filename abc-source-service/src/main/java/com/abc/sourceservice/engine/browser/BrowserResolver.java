package com.abc.sourceservice.engine.browser;

import com.abc.sourceservice.engine.RuleExecutionConfig;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 无头浏览器直链解析 —— 复刻 Kazumi 的 WebView 拦截 / Animeko 的 CEF resolver。
 *
 * <p>流程：加载「播放页 URL」→ 让页面 JS 跑起来 → 监听所有网络响应 →
 * 用 {@code matchVideoUrl} 正则（学 Animeko）认出真正的 m3u8/mp4 → 返回直链 + 需要的 headers。
 *
 * <p>用 Playwright 驱动<b>本机已安装的 Chrome</b>（channel="chrome"），无需下载 Chromium。
 */
@Slf4j
@Component
public class BrowserResolver {

    /** 默认匹配：常见 m3u8/mp4/flv 直链 */
    private static final String DEFAULT_MATCH =
            "(?<v>https?://[^\"']+\\.(m3u8|mp4|flv)(\\?[^\"']*)?)";

    @Value("${abc.browser.channel:chrome}")
    private String channel;

    @Value("${abc.browser.headless:true}")
    private boolean headless;

    @Value("${abc.browser.timeout-ms:30000}")
    private int timeoutMs;

    private volatile Browser browser;

    /** 解析结果。 */
    public record ResolvedVideo(String url, Map<String, String> headers, String matchedBy) { }

    /** 懒加载共享浏览器实例（Playwright 启动较慢，复用）。 */
    private Browser browser() {
        if (browser == null) {
            synchronized (this) {
                if (browser == null) {
                    Playwright pw = Playwright.create();
                    BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(headless);
                    try {
                        if (channel != null && !channel.isBlank()) opts.setChannel(channel);
                        browser = pw.chromium().launch(opts);
                        log.info("Playwright 浏览器已启动: channel={}, headless={}", channel, headless);
                    } catch (Exception e) {
                        // channel 不可用时回退到自带 Chromium
                        log.warn("channel={} 启动失败，回退默认 Chromium: {}", channel, e.getMessage());
                        browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
                    }
                }
            }
        }
        return browser;
    }

    /**
     * 加载播放页并拦截真实视频地址。
     * @return 命中返回 ResolvedVideo；超时/无命中返回 null
     */
    public ResolvedVideo resolve(String episodePageUrl, RuleExecutionConfig cfg) {
        if (episodePageUrl == null || episodePageUrl.isBlank()) return null;

        Pattern videoPattern = compilePattern(cfg);
        List<String> hits = new CopyOnWriteArrayList<>();
        String ua = (cfg != null && cfg.getUserAgent() != null && !cfg.getUserAgent().isBlank())
                ? cfg.getUserAgent()
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

        BrowserContext ctx = null;
        try {
            Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                    .setUserAgent(ua)
                    .setJavaScriptEnabled(true);
            Map<String, String> extra = new LinkedHashMap<>();
            String referer = (cfg != null && cfg.getReferer() != null && !cfg.getReferer().isBlank())
                    ? cfg.getReferer() : (cfg != null ? cfg.getBaseUrl() : null);
            if (referer != null) extra.put("Referer", referer);
            if (!extra.isEmpty()) ctxOpts.setExtraHTTPHeaders(extra);

            ctx = browser().newContext(ctxOpts);
            Page page = ctx.newPage();

            // 拦截所有响应，按正则 + 内容类型识别视频，并抽取真实媒体地址
            page.onResponse(resp -> {
                try {
                    String url = resp.url();
                    if (url == null) return;
                    String ct = null;
                    try { ct = resp.headerValue("content-type"); } catch (Exception ignored) { }
                    boolean videoCt = ct != null && (ct.contains("mpegurl") || ct.contains("video/") || ct.contains("octet-stream"));
                    boolean urlMatch = videoPattern.matcher(url).find()
                            || url.contains(".m3u8") || url.contains(".mp4") || url.contains(".flv");
                    if (urlMatch && (videoCt || url.contains(".m3u8") || url.contains(".mp4") || url.contains(".flv"))) {
                        // 抽取真实媒体地址（如 maccms 播放器代理页 ?url=<real.mp4>）
                        String real = extractRealMedia(url);
                        if (real != null && !hits.contains(real)) {
                            hits.add(real);
                            log.info("[browser] 命中视频地址: {}", real);
                        }
                    }
                } catch (Exception ignored) { }
            });
            // 加载播放页，让页面 JS 跑起来
            page.navigate(episodePageUrl,
                    new Page.NavigateOptions().setTimeout(timeoutMs).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (hits.isEmpty() && System.currentTimeMillis() < deadline) {
                page.waitForTimeout(500);
            }

            if (hits.isEmpty()) {
                log.info("[browser] 未拦截到视频地址: {}", episodePageUrl);
                return null;
            }
            String found = hits.get(0);

            // 采集页面 cookie（很多直链需要）
            Map<String, String> headers = new LinkedHashMap<>();
            if (referer != null) headers.put("Referer", referer);
            headers.put("User-Agent", ua);
            try {
                StringBuilder ck = new StringBuilder();
                List<Cookie> cookies = ctx.cookies();
                for (Cookie c : cookies) {
                    if (ck.length() > 0) ck.append("; ");
                    ck.append(c.name).append("=").append(c.value);
                }
                if (ck.length() > 0) headers.put("Cookie", ck.toString());
            } catch (Exception ignored) { }

            return new ResolvedVideo(found, headers, "browser");
        } catch (Exception e) {
            log.warn("[browser] 解析失败 {}: {}", episodePageUrl, e.getMessage());
            return null;
        } finally {
            if (ctx != null) {
                try { ctx.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 从「播放器代理页 URL」里抽真实媒体地址。
     * 例：{@code /player/index.php?...&url=https://cdn/x.mp4} → 取 {@code url} 参数值。
     * 若本身已是裸媒体地址则原样返回。
     */
    public static String extractRealMedia(String raw) {
        if (raw == null) return null;
        int q = raw.indexOf('?');
        if (q > 0) {
            String query = raw.substring(q + 1);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq).toLowerCase();
                if (k.equals("url") || k.equals("vid") || k.equals("src") || k.equals("video")) {
                    String v = pair.substring(eq + 1);
                    try { v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8); }
                    catch (Exception ignored) { }
                    if (v.toLowerCase().contains(".m3u8") || v.toLowerCase().contains(".mp4")
                            || v.toLowerCase().contains(".flv")) {
                        return v;
                    }
                }
            }
        }
        return raw; // 无 url 参数：本身即媒体地址（或交给上游直接使用）
    }

    private Pattern compilePattern(RuleExecutionConfig cfg) {
        String expr = cfg != null ? cfg.getMatchVideoUrl() : null;
        if (expr == null || expr.isBlank()) expr = DEFAULT_MATCH;
        try {
            return Pattern.compile(expr);
        } catch (Exception e) {
            log.warn("matchVideoUrl 正则非法，使用默认: {}", expr);
            return Pattern.compile(DEFAULT_MATCH);
        }
    }
}
