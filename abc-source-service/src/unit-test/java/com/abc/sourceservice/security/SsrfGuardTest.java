package com.abc.sourceservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF 白名单单测。
 *
 * <p>为什么这些断言重要：{@code /api/source/proxy?u=} 是攻击者可控字符串，
 * 没有这层校验，一个毕业设计上线当天就能变成「内网扫描器 + Redis 未授权访问跳板」。
 * 这里用的都是字面量 IP，不依赖 DNS，离线也能跑。
 */
class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard();

    @Test
    @DisplayName("回环/内网/云元数据地址一律拦截")
    void blocksPrivateTargets() {
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("http://127.0.0.1:6379/"));
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("http://localhost:8080/actuator"));
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("http://169.254.169.254/latest/meta-data/"));
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("http://10.0.0.5/"));
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("http://192.168.1.1/"));
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("http://172.16.0.1/"));
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("http://[::1]/"));
    }

    @Test
    @DisplayName("非 http(s) 协议拦截（file/gopher/dict/ftp）")
    void blocksBadProtocols() {
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("file:///etc/passwd"));
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("gopher://127.0.0.1:6379/_INFO"));
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed("ftp://1.2.3.4/x"));
        assertThrows(SsrfBlockedException.class, () -> guard.assertAllowed(""));
    }

    @Test
    @DisplayName("公网字面量 IP 放行")
    void allowsPublicTarget() {
        assertDoesNotThrow(() -> guard.assertAllowed("https://93.184.216.34/video.m3u8"));
        assertDoesNotThrow(() -> guard.assertAllowed("http://8.8.8.8/"));
    }

    @Test
    @DisplayName("IP 级别判定函数")
    void isBlockedAddress() throws Exception {
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("10.1.2.3")));
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("192.168.0.1")));
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("172.20.0.1")));
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("169.254.1.1")));
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("0.0.0.0")));
        assertFalse(SsrfGuard.isBlockedAddress(InetAddress.getByName("93.184.216.34")));
        assertFalse(SsrfGuard.isBlockedAddress(InetAddress.getByName("8.8.8.8")));
    }
}
