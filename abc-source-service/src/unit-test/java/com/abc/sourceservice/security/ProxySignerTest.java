package com.abc.sourceservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流代理签名单测：验签必须能挡住「改地址」「改 ruleId」「过期重放」三类攻击。
 */
class ProxySignerTest {

    private static final String URL = "https://cdn.example.com/a/b/index.m3u8";

    private final ProxySigner signer = new ProxySigner("unit-test-secret", 300);
    private final ProxySigner other = new ProxySigner("another-secret", 300);

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    @Test
    @DisplayName("正常签名可通过验签")
    void roundTrip() {
        long ts = now();
        String sign = signer.sign(12L, URL, ts, ProxySigner.SCOPE_DIRECT);
        assertTrue(signer.verify(12L, URL, ts, sign, ProxySigner.SCOPE_DIRECT));
    }

    @Test
    @DisplayName("篡改 url / ruleId / scope 都不通过")
    void tamperFails() {
        long ts = now();
        String sign = signer.sign(12L, URL, ts, ProxySigner.SCOPE_DIRECT);
        assertFalse(signer.verify(12L, URL + "?x=1", ts, sign, ProxySigner.SCOPE_DIRECT));
        assertFalse(signer.verify(13L, URL, ts, sign, ProxySigner.SCOPE_DIRECT));
        assertFalse(signer.verify(12L, URL, ts, sign, ProxySigner.SCOPE_CHILD));
    }

    @Test
    @DisplayName("换密钥不能伪造")
    void wrongSecretFails() {
        long ts = now();
        String forged = other.sign(12L, URL, ts, ProxySigner.SCOPE_DIRECT);
        assertNotEquals(forged, signer.sign(12L, URL, ts, ProxySigner.SCOPE_DIRECT));
        assertFalse(signer.verify(12L, URL, ts, forged, ProxySigner.SCOPE_DIRECT));
    }

    @Test
    @DisplayName("过期与未来时间戳都拒绝")
    void timeWindow() {
        long now = now();
        String old = signer.sign(12L, URL, now - 1000, ProxySigner.SCOPE_DIRECT);
        assertFalse(signer.verify(12L, URL, now - 1000, old, ProxySigner.SCOPE_DIRECT));
        String future = signer.sign(12L, URL, now + 3600, ProxySigner.SCOPE_DIRECT);
        assertFalse(signer.verify(12L, URL, now + 3600, future, ProxySigner.SCOPE_DIRECT));
    }

    @Test
    @DisplayName("HLS 子资源 child 签名窗口更宽（1h），避免长视频中途 403")
    void childScopeHasLongerWindow() {
        long ts = now() - 1800;
        String child = signer.sign(12L, URL, ts, ProxySigner.SCOPE_CHILD);
        assertTrue(signer.verify(12L, URL, ts, child, ProxySigner.SCOPE_CHILD));
        String direct = signer.sign(12L, URL, ts, ProxySigner.SCOPE_DIRECT);
        assertFalse(signer.verify(12L, URL, ts, direct, ProxySigner.SCOPE_DIRECT));
    }

    @Test
    @DisplayName("缺参数直接拒绝，签名串本身可用")
    void missingParams() {
        assertFalse(signer.verify(null, URL, now(), "abc", ProxySigner.SCOPE_DIRECT));
        assertFalse(signer.verify(1L, null, now(), "abc", ProxySigner.SCOPE_DIRECT));
        assertFalse(signer.verify(1L, URL, null, "abc", ProxySigner.SCOPE_DIRECT));
        assertFalse(signer.verify(1L, URL, now(), null, ProxySigner.SCOPE_DIRECT));
        String q = signer.signedQuery(9L, URL, ProxySigner.SCOPE_CHILD);
        assertTrue(q.contains("ruleId=9"));
        assertTrue(q.contains("scope=child"));
        assertTrue(q.contains("sign="));
        assertTrue(signer.signedProxyUrl(9L, URL).startsWith("/api/source/proxy?"));
    }
}
