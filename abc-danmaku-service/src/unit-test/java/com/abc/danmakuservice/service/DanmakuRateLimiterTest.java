package com.abc.danmakuservice.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 频率限制单测。
 *
 * <p>限流器本身依赖 Redis（INCR + EXPIRE），但"窗口划分"和"是否超限"是纯逻辑，
 * 抽成静态方法后可以离线单测，避免单测必须起一个 Redis。
 * 这样设计的好处：验收时 {@code mvn test} 在无中间件的机器上也能跑通。
 */
class DanmakuRateLimiterTest {

    @Test
    @DisplayName("窗口内计数不超过上限时放行")
    void allowedWithinLimit() {
        assertTrue(DanmakuRateLimiter.isAllowed(1, 20));
        assertTrue(DanmakuRateLimiter.isAllowed(20, 20));
    }

    @Test
    @DisplayName("窗口内计数超过上限时拒绝")
    void rejectedAboveLimit() {
        assertFalse(DanmakuRateLimiter.isAllowed(21, 20));
        assertFalse(DanmakuRateLimiter.isAllowed(100, 20));
    }

    @Test
    @DisplayName("窗口号按 windowSeconds 取整，保证同一分钟落到同一个 key")
    void windowIndexBucketing() {
        assertEquals(2L, DanmakuRateLimiter.windowIndex(120L, 60));
        assertEquals(2L, DanmakuRateLimiter.windowIndex(179L, 60));
        assertEquals(3L, DanmakuRateLimiter.windowIndex(180L, 60));
        assertEquals(1L, DanmakuRateLimiter.windowIndex(120L, 120));
    }

    @Test
    @DisplayName("匿名用户（userId=null）不允许发弹幕")
    void anonymousNotAllowed() {
        assertFalse(new DanmakuRateLimiter(null, null).tryAcquire(null));
    }
}
