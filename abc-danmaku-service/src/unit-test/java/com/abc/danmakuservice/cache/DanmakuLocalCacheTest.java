package com.abc.danmakuservice.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自研 L1 缓存单测（TTL + LRU + 命中率）。
 *
 * <p>用 JDK 自带 LinkedHashMap 实现而不是引 Caffeine，
 * 这里重点验证三件事：读了能命中、到期会失效、超容量按最久未使用淘汰。
 */
class DanmakuLocalCacheTest {

    @Test
    @DisplayName("写入后能命中，未写入返回 null")
    void getAndMiss() {
        DanmakuLocalCache<String> cache = new DanmakuLocalCache<>(10, 60_000L);
        assertNull(cache.get("k1"));
        cache.put("k1", "v1");
        assertEquals("v1", cache.get("k1"));
    }

    @Test
    @DisplayName("超过 TTL 后视为未命中并被移除")
    void expireAfterTtl() throws InterruptedException {
        DanmakuLocalCache<String> cache = new DanmakuLocalCache<>(10, 30L);
        cache.put("k1", "v1");
        Thread.sleep(60L);
        assertNull(cache.get("k1"));
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("超过最大容量时淘汰最久未访问的条目（LRU）")
    void evictLeastRecentlyUsed() {
        DanmakuLocalCache<String> cache = new DanmakuLocalCache<>(2, 60_000L);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.get("a");     // 访问 a，让 b 变成最久未使用
        cache.put("c", "3");
        assertNotNull(cache.get("a"));
        assertNull(cache.get("b"));
        assertNotNull(cache.get("c"));
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("按前缀批量失效（某集来了新弹幕时清该集所有时间窗）")
    void invalidateByPrefix() {
        DanmakuLocalCache<String> cache = new DanmakuLocalCache<>(10, 60_000L);
        cache.put("w:1001:1:0-60:2000", "x");
        cache.put("w:1001:1:60-120:2000", "y");
        cache.put("w:1002:1:0-60:2000", "z");
        assertEquals(2, cache.invalidatePrefix("w:1001:1:"));
        assertNull(cache.get("w:1001:1:0-60:2000"));
        assertNotNull(cache.get("w:1002:1:0-60:2000"));
    }

    @Test
    @DisplayName("命中率统计正确")
    void hitRateStats() {
        DanmakuLocalCache<String> cache = new DanmakuLocalCache<>(10, 60_000L);
        cache.put("k", "v");
        cache.get("k");
        cache.get("k");
        cache.get("missing");
        assertEquals(2, cache.getHitCount());
        assertEquals(1, cache.getMissCount());
        assertTrue(cache.hitRate() > 0.6);
        assertTrue(cache.stats().contains("hit="));
    }
}
