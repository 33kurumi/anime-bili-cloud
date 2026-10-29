package com.abc.sourceservice.select;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的失败源黑名单：key = {@code source:blacklist:{ruleId}}，TTL 10 分钟。
 *
 * <p>多实例共享（网关/多副本 source-service 都能看到同一份冷却名单），
 * 比单机内存 Map 更符合微服务场景。Redis 不可用时降级为「不拉黑」，
 * 保证主流程不因缓存故障而中断（可用性优先）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSourceBlacklist implements SourceBlacklist {

    public static final String KEY_PREFIX = "source:blacklist:";
    public static final long TTL_SECONDS = 600L;

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public boolean isBlacklisted(long ruleId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + ruleId));
        } catch (Exception e) {
            log.warn("读取源黑名单失败（降级为不拉黑）: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void markFailure(long ruleId, String reason) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + ruleId,
                    reason == null ? "failure" : reason, TTL_SECONDS, TimeUnit.SECONDS);
            log.info("源 {} 进入黑名单 {}s，原因: {}", ruleId, TTL_SECONDS, reason);
        } catch (Exception e) {
            log.warn("写入源黑名单失败: {}", e.getMessage());
        }
    }

    @Override
    public void markSuccess(long ruleId) {
        try {
            redisTemplate.delete(KEY_PREFIX + ruleId);
        } catch (Exception e) {
            log.warn("清除源黑名单失败: {}", e.getMessage());
        }
    }
}
