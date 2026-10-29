package com.abc.danmakuservice.service;

import com.abc.danmakuservice.config.DanmakuProperties;
import com.abc.danmakuservice.util.DanmakuKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户发送频率限制（防刷屏）。
 *
 * <p>实现：Redis 固定窗口计数器。键 = {@code danmaku:rate:{userId}:{windowIndex}}，
 * 每次发送 INCR，首次（返回 1）时设置过期时间。窗口内计数超过阈值即拒绝。
 *
 * <p>为什么用固定窗口而不是滑动窗口/令牌桶：固定窗口只要一次 INCR 就能判定，
 * 对"每秒可能上千条弹幕"的场景最省 Redis 调用；缺点是窗口边界处可能瞬时放行 2 倍流量，
 * 对毕设的防刷屏需求完全够用（已知限制，答辩可主动说明）。
 *
 * <p>Redis 不可用时采取「失败放行」策略：弹幕是弱一致业务，宁可短暂不限流，也不要整个功能不可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DanmakuRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final DanmakuProperties properties;

    /**
     * 尝试获取一次发送配额。
     *
     * @param userId 发送者 id（游客为 null，直接拒绝）
     * @return true=允许发送，false=已超限
     */
    public boolean tryAcquire(Long userId) {
        if (userId == null) {
            return false;
        }
        DanmakuProperties.Rate conf = properties.getRate();
        int windowSeconds = Math.max(1, conf.getWindowSeconds());
        long windowIndex = windowIndex(System.currentTimeMillis() / 1000, windowSeconds);
        String key = DanmakuKeys.rate(userId, windowIndex);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 首次写入时设置过期，避免计数器永久堆积；多留 5s 容忍时钟漂移
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds + 5L));
            }
            return isAllowed(count == null ? 0L : count, conf.getMaxPerWindow());
        } catch (Exception e) {
            log.warn("频率限制 Redis 异常，本次放行: userId={} err={}", userId, e.getMessage());
            return true;
        }
    }

    /** 纯判定：计数 <= 上限即允许（<= 而不是 <，因为 INCR 后 count 就是「本次是第几条」） */
    public static boolean isAllowed(long count, int maxPerWindow) {
        if (maxPerWindow <= 0) {
            return false;
        }
        return count <= maxPerWindow;
    }

    /** 当前时间属于第几个窗口（秒级时间戳 / 窗口长度） */
    public static long windowIndex(long epochSeconds, int windowSeconds) {
        return windowSeconds <= 0 ? epochSeconds : epochSeconds / windowSeconds;
    }
}
