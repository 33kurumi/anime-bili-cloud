package com.abc.sourceservice.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 流代理 URL 签名（防「白嫖代理」与 SSRF 的二次校验）。
 *
 * <p>思路和论坛常见的「私有 CDN 防盗链」一致：服务端签，客户端带，
 * 代理端验——攻击者拿到一个签名 URL 只能重放这一条，不能改 {@code u} 参数去读任意地址。
 *
 * <p>签名材料：{@code ruleId|url|ts|scope}，算法 HmacSHA256，输出 64 位小写 hex。
 * 时间窗默认 300s（{@code source.proxy.window-seconds}）。
 * HLS 播放列表里的分片/子清单用 {@code scope=child}，窗口放宽到 1h：
 * 播放器会把播放列表里的 URL 复用很久，窗口太短会导致长视频播到一半分片 403；
 * 但因为是 Hmac 签名，放宽窗口不会带来「换目标」的风险。
 */
@Slf4j
@Component
public class ProxySigner {

    /** 直链/清单本身（首次请求）。 */
    public static final String SCOPE_DIRECT = "direct";
    /** HLS 播放列表里的分片、子清单、密钥。 */
    public static final String SCOPE_CHILD = "child";

    private static final long CLOCK_SKEW_SECONDS = 60L;
    private static final long CHILD_WINDOW_SECONDS = 3600L;

    private final String secret;
    private final long windowSeconds;
    private final long childWindowSeconds;

    public ProxySigner(@Value("${source.proxy.secret:abc-source-proxy-2025}") String secret,
                       @Value("${source.proxy.window-seconds:300}") long windowSeconds) {
        this.secret = (secret == null || secret.isBlank()) ? "abc-source-proxy-2025" : secret;
        this.windowSeconds = windowSeconds <= 0 ? 300L : windowSeconds;
        this.childWindowSeconds = Math.max(this.windowSeconds, CHILD_WINDOW_SECONDS);
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    /** HmacSHA256(ruleId|url|ts|scope) → 64 位小写 hex。 */
    public String sign(long ruleId, String url, long ts, String scope) {
        String sc = scope == null ? SCOPE_DIRECT : scope;
        String material = ruleId + "|" + url + "|" + ts + "|" + sc;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 生成带签名的查询串：ruleId=..&u=..&ts=..&scope=..&sign=.. */
    public String signedQuery(long ruleId, String url, String scope) {
        String sc = scope == null ? SCOPE_DIRECT : scope;
        long ts = System.currentTimeMillis() / 1000L;
        String sign = sign(ruleId, url, ts, sc);
        return "ruleId=" + ruleId
                + "&u=" + URLEncoder.encode(url, StandardCharsets.UTF_8)
                + "&ts=" + ts
                + "&scope=" + sc
                + "&sign=" + sign;
    }

    /** 给前端可播地址（/api/source/proxy?...&sign=...）。 */
    public String signedProxyUrl(long ruleId, String url) {
        return signedProxyUrl(ruleId, url, SCOPE_DIRECT);
    }

    public String signedProxyUrl(long ruleId, String url, String scope) {
        return "/api/source/proxy?" + signedQuery(ruleId, url, scope);
    }

    /**
     * 验签：参数缺失/时间窗过期/签名不符一律拒绝。
     * @param ts 秒级时间戳
     */
    public boolean verify(Long ruleId, String url, Long ts, String sign, String scope) {
        if (ruleId == null || url == null || url.isBlank()
                || ts == null || sign == null || sign.isBlank()) {
            return false;
        }
        String sc = (scope == null || scope.isBlank()) ? SCOPE_DIRECT : scope.toLowerCase(Locale.ROOT);
        long window = SCOPE_CHILD.equals(sc) ? childWindowSeconds : windowSeconds;
        long now = System.currentTimeMillis() / 1000L;
        if (ts > now + CLOCK_SKEW_SECONDS) {
            log.debug("代理签名时间戳在未来: ts={}, now={}", ts, now);
            return false;
        }
        long age = now - ts;
        if (age > window) {
            log.debug("代理签名已过期: age={}s > {}s", age, window);
            return false;
        }
        String expected = sign(ruleId, url, ts, sc);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sign.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
