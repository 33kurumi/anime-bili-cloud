package com.abc.sourceservice.controller;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.abc.sourceservice.entity.SourceRule;
import com.abc.sourceservice.security.ProxySigner;
import com.abc.sourceservice.security.SsrfBlockedException;
import com.abc.sourceservice.security.SsrfGuard;
import com.abc.sourceservice.service.SourceRuleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 流代理：解决防盗链（Referer/UA）——浏览器 &lt;video&gt; 无法自定义 Referer，
 * 由后端带上正确请求头拉流并转发（对齐 Animeko HttpStreamingMediaResolver 思路）。
 *
 * <p>流式转发：不把整段视频读进内存，边拉边写；透传 Range 以支持拖动/分段。
 *
 * <p>安全边界：
 * <ol>
 *   <li><b>SSRF</b>：u 由客户端随意拼接，若不校验，攻击者可让本服务去访问
 *       http://127.0.0.1:6379、http://169.254.169.254/ 等内网资源。
 *       因此每次真正发请求前都过 SsrfGuard.assertAllowed；
 *       重定向也是攻击面（公网域名 302 到内网），所以关掉自动跟随、手动逐跳校验。</li>
 *   <li><b>防白嫖</b>：ProxySigner 给 ruleId|u|ts|scope 做 HmacSHA256，
 *       代理端验签，过期（300s）/篡改/换目标一律 403。播放列表里的子资源用 scope=child。</li>
 * </ol>
 *
 * <p>验签可通过 source.proxy.enforce-sign=false 关闭（仅用于本地联调/压测），默认开启。
 */
@Slf4j
@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
public class StreamProxyController {

    /** 重定向最大跳数：HLS/视频 CDN 常见 1~2 跳，5 跳足够且能防环。 */
    private static final int MAX_REDIRECTS = 5;
    /** 单次请求超时（毫秒）。 */
    private static final int TIMEOUT_MS = 30000;

    private final SourceRuleService ruleService;
    private final SsrfGuard ssrfGuard;
    private final ProxySigner proxySigner;

    @Value("${source.proxy.enforce-sign:true}")
    private boolean enforceSign;

    @RequestMapping(value = "/proxy", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void proxy(@RequestParam Long ruleId,
                      @RequestParam("u") String encodedUrl,
                      @RequestParam(required = false) Long ts,
                      @RequestParam(required = false) String sign,
                      @RequestParam(required = false, defaultValue = ProxySigner.SCOPE_DIRECT) String scope,
                      HttpServletRequest request,
                      HttpServletResponse response) {
        String url = normalizeTarget(encodedUrl);
        response.setHeader("Access-Control-Allow-Origin", "*");

        // ① 验签：参数被改、过期、签名不符都拒绝
        if (enforceSign && !proxySigner.verify(ruleId, url, ts, sign, scope)) {
            log.info("代理请求验签失败: ruleId={}, scope={}, url={}", ruleId, scope, url);
            writeError(response, 403, "invalid or expired proxy signature");
            return;
        }

        SourceRule rule = ruleService.getById(ruleId);
        try {
            // ② SSRF：目标地址必须落在公网
            ssrfGuard.assertAllowed(url);

            HttpResponse resp = execute(url, rule, request);
            int hops = 0;
            // ③ 手动跟随重定向，逐跳做 SSRF 校验（自动跟随会绕过校验）
            while (isRedirect(resp.getStatus()) && hops++ < MAX_REDIRECTS) {
                String location = resp.header("Location");
                if (location == null || location.isBlank()) {
                    break;
                }
                resp.close();
                String next = resolve(url, location);
                ssrfGuard.assertAllowed(next);
                url = next;
                resp = execute(url, rule, request);
            }

            int status = resp.getStatus();
            String ct = resp.header("Content-Type");
            boolean isM3u8 = url.toLowerCase().contains(".m3u8")
                    || (ct != null && ct.contains("mpegurl"));

            if (isM3u8) {
                // HLS 清单：不能直接透传字节，需把其中的分片/子清单地址改写成走本代理
                response.setStatus(status);
                response.setContentType("application/vnd.apple.mpegurl");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                String rewritten = rewriteM3u8(resp.body(), url, ruleId);
                response.getWriter().write(rewritten);
                return;
            }

            response.setStatus(status);
            copyHeader(resp, response, "Content-Type", guessContentType(url));
            copyHeader(resp, response, "Content-Length", null);
            copyHeader(resp, response, "Content-Range", null);
            copyHeader(resp, response, "Accept-Ranges", "bytes");
            copyHeader(resp, response, "Content-Encoding", null);

            if (RequestMethod.HEAD.name().equalsIgnoreCase(request.getMethod())) {
                return;
            }
            try (InputStream in = resp.bodyStream();
                 OutputStream os = response.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
                os.flush();
            }
        } catch (SsrfBlockedException e) {
            // 命中内网/回环/非法协议：不打 502，明确 403，便于前端与审计日志区分
            log.warn("代理请求被 SSRF 拦截: ruleId={}, url={}, reason={}", ruleId, url, e.getMessage());
            writeError(response, 403, "target address blocked: " + e.getMessage());
        } catch (Exception e) {
            log.error("流代理失败: {}", url, e);
            writeError(response, 502, "proxy error: " + e.getMessage());
        }
    }

    // ─── 请求执行 ────────────────────────────────────────────────

    private HttpResponse execute(String url, SourceRule rule, HttpServletRequest request) {
        boolean head = RequestMethod.HEAD.name().equalsIgnoreCase(request.getMethod());
        HttpRequest req = head ? HttpRequest.head(url) : HttpRequest.get(url);
        req.timeout(TIMEOUT_MS).setFollowRedirects(false);
        if (rule != null) {
            String referer = rule.getReferer() != null && !rule.getReferer().isBlank()
                    ? rule.getReferer() : rule.getBaseUrl();
            if (referer != null) req.header("Referer", referer);
            if (rule.getUserAgent() != null && !rule.getUserAgent().isBlank()) {
                req.header("User-Agent", rule.getUserAgent());
            }
        }
        // 透传客户端的 Range（拖动进度/分段下载）
        String range = request.getHeader("Range");
        if (range != null && !range.isBlank()) req.header("Range", range);
        return req.execute();
    }

    // ─── m3u8 改写 ───────────────────────────────────────────────

    /**
     * 重写 m3u8：把分片/子清单/密钥的 URI 改写成经本代理的地址（保留 Referer 能力）。
     * 子资源用 scope=child 签名（窗口 1h），否则播放到一半分片就会 403。
     */
    private String rewriteM3u8(String body, String baseUrl, Long ruleId) {
        if (body == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                out.append(line).append('\n');
                continue;
            }
            if (t.startsWith("#")) {
                // 处理 #EXT-X-KEY / #EXT-X-MAP 里的 URI="..."
                if (t.contains("URI=\"")) {
                    int s = t.indexOf("URI=\"") + 5;
                    int e = t.indexOf('"', s);
                    if (e > s) {
                        String abs = resolve(baseUrl, t.substring(s, e));
                        t = t.substring(0, s) + proxyFor(abs, ruleId) + t.substring(e);
                    }
                }
                out.append(t).append('\n');
            } else {
                out.append(proxyFor(resolve(baseUrl, t), ruleId)).append('\n');
            }
        }
        return out.toString();
    }

    /** 子资源（分片/子清单/key）走 child 签名。 */
    private String proxyFor(String absUrl, Long ruleId) {
        return "/api/source/proxy?" + proxySigner.signedQuery(ruleId, absUrl, ProxySigner.SCOPE_CHILD);
    }

    private String resolve(String baseUrl, String uri) {
        try {
            return java.net.URI.create(baseUrl).resolve(uri).toString();
        } catch (Exception e) {
            return uri;
        }
    }

    /**
     * 归一化目标地址：Spring 已把 query 解码一次，正常情况这里原样返回。
     * 兼容历史调用方（URLEncoder 编了两次）只在「看起来还没解干净」时补解一次。
     */
    private String normalizeTarget(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        try {
            String decoded = URLDecoder.decode(u, StandardCharsets.UTF_8);
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded;
            }
        } catch (Exception ignored) {
            // 非法转义序列：交给 SSRF 校验去拒绝
        }
        return u;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private void writeError(HttpServletResponse response, int status, String message) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":" + status + ",\"message\":\""
                    + message.replace("\"", "'") + "\"}");
        } catch (Exception ignored) {
            // 响应已提交/连接已断开，无需处理
        }
    }

    private void copyHeader(HttpResponse resp, HttpServletResponse response, String name, String fallback) {
        String v = resp.header(name);
        if (v != null && !v.isBlank()) response.setHeader(name, v);
        else if (fallback != null) response.setHeader(name, fallback);
    }

    private String guessContentType(String url) {
        String u = url.toLowerCase();
        if (u.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if (u.contains(".ts")) return "video/mp2t";
        if (u.contains(".mp4")) return "video/mp4";
        if (u.contains(".flv")) return "video/x-flv";
        return "application/octet-stream";
    }
}
