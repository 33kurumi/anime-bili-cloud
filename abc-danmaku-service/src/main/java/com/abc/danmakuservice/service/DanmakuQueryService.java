package com.abc.danmakuservice.service;

import com.abc.danmakuservice.cache.DanmakuLocalCache;
import com.abc.danmakuservice.config.DanmakuProperties;
import com.abc.danmakuservice.dto.HeatBucket;
import com.abc.danmakuservice.entity.Danmaku;
import com.abc.danmakuservice.util.DanmakuKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 弹幕读取服务：L1 本地缓存 → L2 Redis 缓存 → ZSET 时间索引 / Mongo 回源。
 *
 * <p>为什么要 ZSET 而不直接查 Mongo：Mongo 的范围查询即使有复合索引，
 * 也是"按页取 + 需要回表"；而弹幕播放器是"每 30~60 秒拉一个时间窗"的高频读，
 * 用 ZSET（score=视频内秒数，member=弹幕 JSON）做 ZRANGEBYSCORE 是纯内存 O(logN + M)，
 * 且返回的 member 就是完整弹幕，不需要回表。
 *
 * <p>一致性取舍：ZSET 只是"服务写入侧"的实时索引，历史批量导入（dandanplay）不会补建，
 * 因此当 ZSET 命中条数 < limit 时，会再查一次 Mongo 并按指纹合并（Mongo 有复合索引，代价可控）。
 * 只有 ZSET 已经"足够满"（≥limit）才走纯 ZSET 快路径。这是可讲清楚的正确性/性能权衡。
 */
@Slf4j
@Service
public class DanmakuQueryService {

    /** 热度统计时从 ZSET 读取的上限（超过部分按时间轴裁剪，避免拖慢接口） */
    private static final int HEAT_SCAN_LIMIT = 50000;

    private final StringRedisTemplate redisTemplate;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final DanmakuProperties properties;

    /** L1：时间窗查询结果（本地堆内，60s TTL，LRU） */
    private final DanmakuLocalCache<List<Danmaku>> windowCache;
    /** L1：热度分桶结果 */
    private final DanmakuLocalCache<List<HeatBucket>> heatCache;

    public DanmakuQueryService(StringRedisTemplate redisTemplate,
                               MongoTemplate mongoTemplate,
                               ObjectMapper objectMapper,
                               DanmakuProperties properties) {
        this.redisTemplate = redisTemplate;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        DanmakuProperties.LocalCache conf = properties.getLocalCache();
        long ttlMillis = Math.max(1, conf.getTtlSeconds()) * 1000L;
        this.windowCache = new DanmakuLocalCache<>(conf.getMaxSize(), ttlMillis);
        this.heatCache = new DanmakuLocalCache<>(Math.max(1000, conf.getMaxSize() / 10), ttlMillis);
    }

    // ─── 时间窗查询 ─────────────────────────────────────────────

    /**
     * 查询某个时间窗内的弹幕。
     *
     * @param startTime 起始秒（null→0）
     * @param endTime   结束秒（null→start+默认跨度）
     * @param limit     最大条数（null→上限）
     */
    public List<Danmaku> window(String animeKey, Integer episodeNum,
                                Double startTime, Double endTime, Integer limit) {
        DanmakuProperties.Window conf = properties.getWindow();
        double start = startTime == null ? 0d : Math.max(0d, startTime);
        double end = endTime == null ? start + conf.getDefaultSpanSeconds() : endTime;
        if (end <= start) {
            end = start + conf.getDefaultSpanSeconds();
        }
        int realLimit = limit == null || limit <= 0
                ? conf.getMaxLimit()
                : Math.min(limit, conf.getMaxLimit());

        String cacheKey = DanmakuKeys.windowCache(animeKey, episodeNum, start, end, realLimit);

        // L1
        List<Danmaku> cached = windowCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        // L2（Redis，跨实例共享）
        List<Danmaku> fromRedis = readWindowFromRedis(cacheKey);
        if (fromRedis != null) {
            windowCache.put(cacheKey, fromRedis);
            return fromRedis;
        }

        List<Danmaku> result = windowFromZset(animeKey, episodeNum, start, end, realLimit);
        boolean zsetEnough = result.size() >= realLimit;
        if (!zsetEnough) {
            List<Danmaku> fromMongo = windowFromMongo(animeKey, episodeNum, start, end, realLimit);
            result = mergeByFingerprint(result, fromMongo);
            if (result.size() > realLimit) {
                result = new ArrayList<>(result.subList(0, realLimit));
            }
            // 顺手把 Mongo 独有的弹幕补进 ZSET，让下次查询走快路径
            warmZset(animeKey, episodeNum, fromMongo, result);
        }

        windowCache.put(cacheKey, result);
        writeWindowToRedis(cacheKey, result);
        return result;
    }

    /** ZSET 时间窗查询：ZRANGEBYSCORE(score=时间秒) */
    private List<Danmaku> windowFromZset(String animeKey, Integer episodeNum,
                                         double start, double end, int limit) {
        String zsetKey = DanmakuKeys.zset(animeKey, episodeNum);
        try {
            Set<String> members = redisTemplate.opsForZSet()
                    .rangeByScore(zsetKey, start, end, 0, limit);
            if (members == null || members.isEmpty()) {
                return new ArrayList<>();
            }
            List<Danmaku> list = new ArrayList<>(members.size());
            for (String json : members) {
                Danmaku d = parse(json);
                if (d != null) {
                    list.add(d);
                }
            }
            list.sort(Comparator.comparingDouble(d -> d.getTimestamp() == null ? 0d : d.getTimestamp()));
            return list;
        } catch (Exception e) {
            log.warn("ZSET 时间窗查询失败，回源 Mongo: animeKey={} ep={} err={}", animeKey, episodeNum, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Mongo 回源（走 idx_anime_episode_time 复合索引） */
    private List<Danmaku> windowFromMongo(String animeKey, Integer episodeNum,
                                          double start, double end, int limit) {
        try {
            Criteria criteria = Criteria.where("animeKey").is(animeKey)
                    .and("episodeNum").is(episodeNum)
                    .and("timestamp").gte(start).lte(end);
            Query query = new Query(criteria).with(Sort.by(Sort.Direction.ASC, "timestamp")).limit(limit);
            return mongoTemplate.find(query, Danmaku.class);
        } catch (Exception e) {
            log.warn("Mongo 时间窗查询失败: animeKey={} ep={} err={}", animeKey, episodeNum, e.getMessage());
            return new ArrayList<>();
        }
    }

    // ─── 热度分桶 ───────────────────────────────────────────────

    /**
     * 弹幕热度分桶：按 {@code danmaku.heat.bucket-seconds}（默认 10 秒）统计每桶条数。
     * 返回只包含非空桶，按时间升序，前端按 index*桶宽 定位进度条。
     */
    public List<HeatBucket> heat(String animeKey, Integer episodeNum) {
        String cacheKey = DanmakuKeys.heatCache(animeKey, episodeNum);
        List<HeatBucket> cached = heatCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        int bucketSeconds = Math.max(1, properties.getHeat().getBucketSeconds());
        double maxTimeline = properties.getHeat().getMaxTimelineSeconds();

        List<Double> timestamps = new ArrayList<>();
        String zsetKey = DanmakuKeys.zset(animeKey, episodeNum);
        try {
            Set<String> members = redisTemplate.opsForZSet()
                    .rangeByScore(zsetKey, 0d, maxTimeline, 0, HEAT_SCAN_LIMIT);
            if (members != null) {
                for (String json : members) {
                    Danmaku d = parse(json);
                    if (d != null && d.getTimestamp() != null) {
                        timestamps.add(d.getTimestamp());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("热度 ZSET 读取失败，回源 Mongo: animeKey={} ep={} err={}", animeKey, episodeNum, e.getMessage());
        }
        if (timestamps.isEmpty()) {
            // ZSET 没有该集数据（例如历史导入）→ 回源 Mongo 只取时间字段需要的整条记录
            try {
                Query query = new Query(Criteria.where("animeKey").is(animeKey)
                        .and("episodeNum").is(episodeNum)
                        .and("timestamp").lte(maxTimeline))
                        .with(Sort.by(Sort.Direction.ASC, "timestamp"));
                for (Danmaku d : mongoTemplate.find(query, Danmaku.class)) {
                    if (d.getTimestamp() != null) {
                        timestamps.add(d.getTimestamp());
                    }
                }
            } catch (Exception e) {
                log.warn("热度 Mongo 回源失败: {}", e.getMessage());
            }
        }

        Map<Integer, Integer> buckets = new LinkedHashMap<>();
        for (Double ts : timestamps) {
            if (ts == null || ts < 0) {
                continue;
            }
            int index = (int) (ts / bucketSeconds);
            buckets.merge(index, 1, Integer::sum);
        }
        List<HeatBucket> result = new ArrayList<>(buckets.size());
        buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> result.add(new HeatBucket(
                        e.getKey(),
                        (double) e.getKey() * bucketSeconds,
                        (double) (e.getKey() + 1) * bucketSeconds,
                        e.getValue())));
        heatCache.put(cacheKey, result);
        return result;
    }

    // ─── 缓存维护 ───────────────────────────────────────────────

    /** 新弹幕落库后失效该集所有时间窗缓存与热度缓存（L1 按前缀失效 + L2 由 TTL 兜底） */
    public void invalidate(String animeKey, Integer episodeNum) {
        int removed = windowCache.invalidatePrefix(DanmakuKeys.windowCachePrefix(animeKey, episodeNum));
        heatCache.invalidate(DanmakuKeys.heatCache(animeKey, episodeNum));
        log.debug("时间窗缓存失效: animeKey={} ep={} 移除={} 条", animeKey, episodeNum, removed);
    }

    /** 缓存统计（/api/danmaku/stats 与答辩演示用） */
    public Map<String, Object> cacheStats() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("windowCache", windowCache.stats());
        map.put("heatCache", heatCache.stats());
        map.put("windowSize", windowCache.size());
        map.put("heatSize", heatCache.size());
        return map;
    }

    // ─── 内部工具 ───────────────────────────────────────────────

    private List<Danmaku> readWindowFromRedis(String cacheKey) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<List<Danmaku>>() {
            });
        } catch (Exception e) {
            log.debug("L2 缓存读取失败（按未命中处理）: err={}", e.getMessage());
            return null;
        }
    }

    private void writeWindowToRedis(String cacheKey, List<Danmaku> list) {
        try {
            int ttl = Math.max(1, properties.getLocalCache().getTtlSeconds());
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(list),
                    Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.debug("L2 缓存写入失败: err={}", e.getMessage());
        }
    }

    /** 把 Mongo 有、ZSET 没有的弹幕补进 ZSET（幂等：member 相同会覆盖 score） */
    private void warmZset(String animeKey, Integer episodeNum, List<Danmaku> fromMongo, List<Danmaku> merged) {
        if (fromMongo == null || fromMongo.isEmpty()) {
            return;
        }
        String zsetKey = DanmakuKeys.zset(animeKey, episodeNum);
        try {
            for (Danmaku d : merged) {
                double score = d.getTimestamp() == null ? 0d : d.getTimestamp();
                redisTemplate.opsForZSet().add(zsetKey, objectMapper.writeValueAsString(d), score);
            }
        } catch (Exception e) {
            log.debug("ZSET 预热失败: err={}", e.getMessage());
        }
    }

    /** 按 (source|text|round(ts,1)) 指纹去重合并，ZSET 结果优先（它在时间轴上更"新鲜"） */
    private List<Danmaku> mergeByFingerprint(List<Danmaku> primary, List<Danmaku> secondary) {
        Map<String, Danmaku> merged = new LinkedHashMap<>();
        for (Danmaku d : primary) {
            merged.putIfAbsent(fingerprint(d), d);
        }
        for (Danmaku d : secondary) {
            merged.putIfAbsent(fingerprint(d), d);
        }
        List<Danmaku> list = new ArrayList<>(merged.values());
        list.sort(Comparator.comparingDouble(d -> d.getTimestamp() == null ? 0d : d.getTimestamp()));
        return list;
    }

    private String fingerprint(Danmaku d) {
        double ts = d.getTimestamp() == null ? 0d : Math.round(d.getTimestamp() * 10d) / 10d;
        return d.getSource() + "|" + d.getText() + "|" + ts;
    }

    private Danmaku parse(String json) {
        try {
            return objectMapper.readValue(json, Danmaku.class);
        } catch (Exception e) {
            log.debug("弹幕 JSON 解析失败: err={}", e.getMessage());
            return null;
        }
    }
}
