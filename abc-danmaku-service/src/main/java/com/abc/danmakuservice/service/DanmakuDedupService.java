package com.abc.danmakuservice.service;

import com.abc.danmakuservice.config.DanmakuProperties;
import com.abc.danmakuservice.util.DanmakuKeys;
import com.abc.danmakuservice.util.DanmakuTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 5 秒窗口去重合并（借鉴 Kazumi 的弹幕聚合思路）。
 *
 * <p>场景：一句"哈哈哈哈哈"在 5 秒内被 10 个人同时发送，屏幕上会出现 10 条一模一样的弹幕，
 * 既遮挡画面又浪费带宽。做法是：按「归一化文本指纹」做 Redis INCR 计数，
 * 同一个指纹在窗口内只广播一次，并把广播文本改写为「原文 xN」。
 *
 * <p>关键取舍：<b>落库仍然保留每一条原文</b>（附带 count），只在广播层合并。
 * 这样既保证数据可追溯（后台能看到真实发送量），又保证屏幕干净。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DanmakuDedupService {

    private final StringRedisTemplate redisTemplate;
    private final DanmakuProperties properties;

    /**
     * 记录一次发送并返回窗口内累计次数。
     *
     * @return 1=窗口内首次（正常广播）；>1=重复（应合并为「原文 xN」）；Redis 异常时返回 1
     */
    public int markAndCount(String animeKey, Integer episodeNum, String text) {
        DanmakuProperties.Dedup conf = properties.getDedup();
        if (conf == null || !conf.isEnabled()) {
            return 1;
        }
        int windowSeconds = Math.max(1, conf.getWindowSeconds());
        String key = DanmakuKeys.dedup(animeKey, episodeNum, DanmakuTextNormalizer.fingerprint(text));
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
            }
            return count == null ? 1 : (int) Math.min(count, Integer.MAX_VALUE);
        } catch (Exception e) {
            log.warn("去重计数 Redis 异常，按首次处理: key={} err={}", key, e.getMessage());
            return 1;
        }
    }

    /** 广播文本：首次原样，重复合并为「原文 xN」 */
    public static String mergedText(String rawText, int count) {
        if (rawText == null) {
            return "";
        }
        return count > 1 ? rawText + " x" + count : rawText;
    }
}
