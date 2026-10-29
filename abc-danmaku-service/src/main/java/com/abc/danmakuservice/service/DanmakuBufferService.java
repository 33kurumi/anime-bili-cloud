package com.abc.danmakuservice.service;

import com.abc.danmakuservice.config.DanmakuProperties;
import com.abc.danmakuservice.entity.Danmaku;
import com.abc.danmakuservice.util.DanmakuKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 弹幕写入缓冲层（Redis List）。
 *
 * <p>WebSocket 的 onMessage 直接写 Mongo 有两个问题：
 * <ol>
 *   <li>高频发送时每条弹幕一次 Mongo 写，Mongo 连接数和写放大都扛不住；</li>
 *   <li>Mongo 慢会阻塞 WebSocket 的 IO 线程，进而拖慢整个房间的广播。</li>
 * </ol>
 * 所以 onMessage 只做「校验 + 写 Redis List」，由 {@link DanmakuFlushTask} 每秒批量 LMPOP 出来再一次性
 * {@code insertMany}。Redis 的 LPUSH/RPOP 是 O(1)，单机轻松上万 QPS，天然削峰。
 *
 * <p>房间登记：另用一个 Set {@code danmaku:buffer:rooms} 记录"哪些房间当前有积压"，
 * flush 时只遍历这些房间，避免全库扫描房间列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DanmakuBufferService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DanmakuProperties properties;

    /** 把一条弹幕追加到所属房间的缓冲队列（右端入队），并登记房间 */
    public void append(Danmaku danmaku) {
        if (danmaku == null) {
            return;
        }
        String roomKey = DanmakuKeys.room(danmaku.getAnimeKey(), danmaku.getEpisodeNum());
        String bufferKey = DanmakuKeys.bufferByRoom(roomKey);
        try {
            String json = objectMapper.writeValueAsString(danmaku);
            redisTemplate.opsForList().rightPush(bufferKey, json);
            redisTemplate.opsForSet().add(DanmakuKeys.BUFFER_ROOMS, roomKey);

            Long size = redisTemplate.opsForList().size(bufferKey);
            long warnSize = properties.getBuffer().getWarnSize();
            if (size != null && size > warnSize) {
                log.warn("弹幕缓冲积压告警: room={} size={} 阈值={}", roomKey, size, warnSize);
            }
        } catch (Exception e) {
            // 缓冲写失败不能让发送方感知（弹幕本身是弱一致业务），但要留痕
            log.error("弹幕写入 Redis 缓冲失败: room={} err={}", roomKey, e.getMessage());
        }
    }

    /** 当前有积压的房间列表 */
    public Set<String> pendingRooms() {
        try {
            Set<String> rooms = redisTemplate.opsForSet().members(DanmakuKeys.BUFFER_ROOMS);
            return rooms == null ? Collections.emptySet() : new LinkedHashSet<>(rooms);
        } catch (Exception e) {
            log.warn("读取缓冲房间列表失败: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 从房间缓冲队列取出至多 batchSize 条。
     *
     * <p>优先用 Redis 6.2+ 的 {@code LPOP key count}（一次往返批量弹出，原子）；
     * 若目标 Redis 版本较老 / 客户端不支持，则降级为 {@code LRANGE + LTRIM}。
     * 降级路径不是严格原子（并发 flush 时可能重复读取），但 flush 是单实例单线程调度，
     * 且落库有幂等指纹兜底，因此可以接受。
     */
    public List<Danmaku> drain(String roomKey, int batchSize) {
        if (batchSize <= 0) {
            return Collections.emptyList();
        }
        String bufferKey = DanmakuKeys.bufferByRoom(roomKey);
        List<String> raw = null;
        boolean popped = false;
        try {
            raw = redisTemplate.opsForList().leftPop(bufferKey, batchSize);
            popped = true;
        } catch (Exception e) {
            log.warn("LPOP count 不可用，降级 LRANGE+LTRIM: room={} err={}", roomKey, e.getMessage());
        }
        if (!popped) {
            raw = redisTemplate.opsForList().range(bufferKey, 0, batchSize - 1L);
            if (raw != null && !raw.isEmpty()) {
                redisTemplate.opsForList().trim(bufferKey, raw.size(), -1);
            }
        }
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<Danmaku> result = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                result.add(objectMapper.readValue(json, Danmaku.class));
            } catch (Exception e) {
                // 脏数据（例如手工向 Redis 写入了非法 JSON）直接丢弃，不阻塞整批
                log.warn("弹幕缓冲反序列化失败已丢弃: room={} err={}", roomKey, e.getMessage());
            }
        }
        return result;
    }

    /** 房间队列已空时从"有积压房间"集合里摘除，避免 flush 每轮都白查一遍 */
    public void unregisterIfEmpty(String roomKey) {
        try {
            String bufferKey = DanmakuKeys.bufferByRoom(roomKey);
            Long size = redisTemplate.opsForList().size(bufferKey);
            if (size == null || size == 0L) {
                redisTemplate.opsForSet().remove(DanmakuKeys.BUFFER_ROOMS, roomKey);
            }
        } catch (Exception e) {
            log.warn("注销缓冲房间失败: room={} err={}", roomKey, e.getMessage());
        }
    }

    /** 指定房间当前积压条数（监控用） */
    public long pending(String roomKey) {
        try {
            Long size = redisTemplate.opsForList().size(DanmakuKeys.bufferByRoom(roomKey));
            return size == null ? 0L : size;
        } catch (Exception e) {
            return 0L;
        }
    }
}
