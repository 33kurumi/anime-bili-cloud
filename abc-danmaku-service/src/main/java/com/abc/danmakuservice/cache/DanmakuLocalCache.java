package com.abc.danmakuservice.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自研进程内 L1 缓存（TTL + LRU + 命中率统计）。
 *
 * <p>为什么不用 Caffeine：本项目要求 Maven 离线构建（依赖只能来自本机 m2），
 * 引入 caffeine 会带来额外的联网下载风险；而弹幕时间窗缓存的需求就是
 * 「有界 + 过期 + 命中率」，用 JDK 自带的 {@link LinkedHashMap}（accessOrder=true）
 * 三十行就能实现，答辩时也更容易讲清楚 LRU 的淘汰位置。
 *
 * <p>线程安全：所有读写方法加同一把对象锁。弹幕查询是「读多写少」且单次开销只有
 * Map 操作，锁竞争可以忽略；相比 ConcurrentHashMap 省掉了手写双向链表的分段锁复杂度。
 *
 * <p>语义与 Caffeine 对齐：{@code get} 过期即视为 miss 并移除；{@code put} 超出容量按
 * 「最久未访问」淘汰；{@code invalidatePrefix} 支持按业务前缀批量失效（新弹幕到达时用）。
 */
public class DanmakuLocalCache<V> {

    /** LRU 容器：accessOrder=true，get/put 都会把节点移到队尾（最近使用） */
    private final LinkedHashMap<String, Entry<V>> map;

    private final int maxSize;
    private final long ttlMillis;

    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong evictCount = new AtomicLong();
    private final AtomicLong putCount = new AtomicLong();

    public DanmakuLocalCache(int maxSize, long ttlMillis) {
        this.maxSize = Math.max(1, maxSize);
        this.ttlMillis = Math.max(1L, ttlMillis);
        final DanmakuLocalCache<V> self = this;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry<V>> eldest) {
                boolean remove = size() > self.maxSize;
                if (remove) {
                    self.evictCount.incrementAndGet();
                }
                return remove;
            }
        };
    }

    /** 命中返回值；未命中或已过期返回 null，并累计统计 */
    public synchronized V get(String key) {
        if (key == null) {
            return null;
        }
        Entry<V> entry = map.get(key);
        if (entry == null) {
            missCount.incrementAndGet();
            return null;
        }
        if (isExpired(entry)) {
            map.remove(key);
            missCount.incrementAndGet();
            return null;
        }
        hitCount.incrementAndGet();
        return entry.value;
    }

    public synchronized void put(String key, V value) {
        if (key == null || value == null) {
            return;
        }
        map.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
        putCount.incrementAndGet();
    }

    public synchronized void invalidate(String key) {
        if (key != null) {
            map.remove(key);
        }
    }

    /** 按前缀批量失效，返回移除条数（用于「某集来了新弹幕 → 该集所有时间窗缓存失效」） */
    public synchronized int invalidatePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return 0;
        }
        List<String> toRemove = new ArrayList<>();
        for (String key : map.keySet()) {
            if (key.startsWith(prefix)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            map.remove(key);
        }
        return toRemove.size();
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    public long getHitCount() {
        return hitCount.get();
    }

    public long getMissCount() {
        return missCount.get();
    }

    public long getEvictCount() {
        return evictCount.get();
    }

    public long getPutCount() {
        return putCount.get();
    }

    /** 命中率 = hit / (hit + miss)；无访问时为 0 */
    public double hitRate() {
        long h = hitCount.get();
        long m = missCount.get();
        long total = h + m;
        return total == 0 ? 0d : (double) h / total;
    }

    /** 一行可读的统计串，便于定时打日志 / 答辩展示 */
    public String stats() {
        return String.format("size=%d hit=%d miss=%d evict=%d put=%d hitRate=%.1f%%",
                size(), getHitCount(), getMissCount(), getEvictCount(), getPutCount(), hitRate() * 100);
    }

    private boolean isExpired(Entry<V> entry) {
        return entry.expireAt > 0 && System.currentTimeMillis() > entry.expireAt;
    }

    private static final class Entry<V> {
        private final V value;
        private final long expireAt;

        private Entry(V value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
