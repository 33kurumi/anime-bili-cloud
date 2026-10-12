package com.abc.sourceservice.controller;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.abc.sourceservice.entity.SourceRule;
import com.abc.sourceservice.service.SourceRuleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
 */
@Slf4j
@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
public class StreamProxyController {

    private final SourceRuleService ruleService;

    @GetMapping("/proxy")
    public void proxy(@RequestParam Long ruleId,
                      @RequestParam("u") String encodedUrl,
                      HttpServletRequest request,
                      HttpServletResponse response) {
        String url = URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8);
        SourceRule rule = ruleService.getById(ruleId);

        try {
            HttpRequest req = HttpRequest.get(url).timeout(30000).setFollowRedirects(true);
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

            HttpResponse resp = req.execute();
            int status = resp.getStatus();
            response.setHeader("Access-Control-Allow-Origin", "*");

            String ct = resp.header("Content-Type");
            boolean isM3u8 = url.toLowerCase().contains(".m3u8")
                    || (ct != null && ct.contains("mpegurl"));

            if (isM3u8) {
                // HLS 清单：不能直接透传字节，需把其中的分片/子清单地址改写成走本代理
                response.setStatus(status);
                response.setContentType("application/vnd.apple.mpegurl");
                String body = resp.body();
                String rewritten = rewriteM3u8(body, url, ruleId);
                response.getWriter().write(rewritten);
                return;
            }

            response.setStatus(status);
            copyHeader(resp, response, "Content-Type", guessContentType(url));
            copyHeader(resp, response, "Content-Length", null);
            copyHeader(resp, response, "Content-Range", null);
            copyHeader(resp, response, "Accept-Ranges", "bytes");
            copyHeader(resp, response, "Content-Encoding", null);

            try (InputStream in = resp.bodyStream();
                 OutputStream os = response.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
                os.flush();
            }
        } catch (Exception e) {
            log.error("流代理失败: {}", url, e);
            try {
                response.setStatus(502);
                response.getWriter().write("proxy error: " + e.getMessage());
            } catch (Exception ignored) { }
        }
    }

    /**
     * 重写 m3u8：把分片/子清单/密钥的 URI 改写成经本代理的地址（保留 Referer 能力）。
     */
    private String rewriteM3u8(String body, String baseUrl, Long ruleId) {
        if (body == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) { out.append(line).append('\n'); continue; }
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

    private String resolve(String baseUrl, String uri) {
        try {
            return java.net.URI.create(baseUrl).resolve(uri).toString();
        } catch (Exception e) {
            return uri;
        }
    }

    private String proxyFor(String absUrl, Long ruleId) {
        return "/api/source/proxy?ruleId=" + ruleId
                + "&u=" + java.net.URLEncoder.encode(absUrl, StandardCharsets.UTF_8);
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
