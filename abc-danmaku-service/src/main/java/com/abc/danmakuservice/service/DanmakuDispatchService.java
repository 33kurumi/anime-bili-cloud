package com.abc.danmakuservice.service;

import com.abc.common.dto.DanmakuMessageDto;
import com.abc.danmakuservice.config.DanmakuProperties;
import com.abc.danmakuservice.entity.Danmaku;
import com.abc.danmakuservice.repository.DanmakuRepository;
import com.abc.danmakuservice.util.DanmakuKeys;
import com.abc.danmakuservice.websocket.DanmakuBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 弹幕落库后的分发：Mongo 批量写 → Redis ZSET 时间索引 → 缓存失效 → WebSocket 广播。
 *
 * <p>这四步的顺序是刻意设计的：
 * <ol>
 *   <li><b>先落库</b>：广播是"尽力而为"的，历史查询是"必须能查到"的，落库失败就不该广播；</li>
 *   <li><b>再建 ZSET 索引</b>：{@code danmaku:zset:{room}} 的 score = 视频内时间（秒），
 *       member = 弹幕 JSON，供 {@code /api/danmaku/window} 时间窗查询走 O(logN) 的 ZRANGEBYSCORE；</li>
 *   <li><b>失效缓存</b>：否则客户端刚发的弹幕 60s 内查不到（L1 本地缓存 + L2 Redis 缓存都要失效）；</li>
 *   <li><b>最后广播</b>：异步线程池发送，不阻塞 flush 线程。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DanmakuDispatchService {

    private final DanmakuRepository danmakuRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DanmakuBroadcaster broadcaster;
    private final DanmakuQueryService queryService;
    private final DanmakuProperties properties;

    /**
     * 批量处理一批待落库弹幕。
     *
     * @return 实际落库条数（等于入参条数减去异常条数）
     */
    public int dispatch(List<Danmaku> batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        // ① Mongo 批量写（比逐条 save 少一个数量级的往返）
        danmakuRepository.saveAll(batch);

        // ② 按房间分组，写 ZSET 索引并把弹幕补进内存最近列表
        Map<String, List<Danmaku>> byRoom = new LinkedHashMap<>();
        for (Danmaku d : batch) {
            byRoom.computeIfAbsent(DanmakuKeys.room(d.getAnimeKey(), d.getEpisodeNum()),
                    k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<String, List<Danmaku>> entry : byRoom.entrySet()) {
            String roomKey = entry.getKey();
            List<Danmaku> roomBatch = entry.getValue();
            Danmaku first = roomBatch.get(0);
            indexZset(DanmakuKeys.zset(first.getAnimeKey(), first.getEpisodeNum()), roomBatch);
            // ③ 失效该集的时间窗缓存与热度缓存
            queryService.invalidate(first.getAnimeKey(), first.getEpisodeNum());
            // ④ 异步广播（被屏蔽的弹幕只落库、不广播）
            for (Danmaku d : roomBatch) {
                if (Boolean.TRUE.equals(d.getBlocked())) {
                    continue;
                }
                broadcaster.broadcast(roomKey, toMessage(d));
            }
        }
        return batch.size();
    }

    /** 写 ZSET 时间索引；超过上限时按 score 裁剪最旧的一段，防止单个房间无限增长 */
    private void indexZset(String zsetKey, List<Danmaku> roomBatch) {
        try {
            for (Danmaku d : roomBatch) {
                double score = d.getTimestamp() == null ? 0d : d.getTimestamp();
                redisTemplate.opsForZSet().add(zsetKey, objectMapper.writeValueAsString(d), score);
            }
            Long card = redisTemplate.opsForZSet().zCard(zsetKey);
            long max = properties.getBuffer().getZsetMaxSize();
            if (card != null && card > max) {
                // 移除 rank 最小的 (card - max) 条：即时间轴上最早的一批
                redisTemplate.opsForZSet().removeRange(zsetKey, 0, card - max - 1);
                log.debug("ZSET 超出上限已裁剪: key={} card={} max={}", zsetKey, card, max);
            }
        } catch (Exception e) {
            // ZSET 只是查询加速索引，写失败不影响落库与广播，下一轮历史查询会回源 Mongo
            log.warn("ZSET 时间索引写入失败: key={} err={}", zsetKey, e.getMessage());
        }
    }

    /** 实体 → 广播 DTO：去重合并后的文本在这里替换（落库仍是原文） */
    private DanmakuMessageDto toMessage(Danmaku d) {
        DanmakuMessageDto dto = new DanmakuMessageDto();
        dto.setAnimeKey(d.getAnimeKey());
        dto.setEpisodeNum(d.getEpisodeNum());
        dto.setUserId(d.getUserId());
        dto.setUsername(d.getUsername());
        dto.setText(DanmakuDedupService.mergedText(d.getText(), d.getCount() == null ? 1 : d.getCount()));
        dto.setColor(d.getColor());
        dto.setPosition(d.getPosition());
        dto.setFontSize(d.getFontSize());
        dto.setTimestamp(d.getTimestamp());
        return dto;
    }
}
