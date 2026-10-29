package com.abc.danmakuservice.mq;

import com.abc.danmakuservice.entity.Danmaku;

import java.util.List;

/**
 * 弹幕批量消息体（Mongo 落库 + 广播的 MQ 载荷）。
 *
 * <p>为什么按"房间 + 一批"发消息，而不是一条弹幕一条消息：
 * 弹幕高峰期每秒几十条，逐条发 MQ 会把 Broker 打满；批量发（默认 500 条/消息）
 * 让消费端一次 insertMany，吞吐提升一个数量级，也让"落库顺序"天然保持批内顺序。
 *
 * <p>字段冗余 roomKey / animeKey / episodeNum 是为了消费端不依赖弹幕实体字段做路由
 * （实体字段理论上可能被上游改写，路由信息应以发送端为准）。
 */
public record DanmakuBatchPayload(String roomKey, String animeKey, Integer episodeNum, List<Danmaku> items) {

    public DanmakuBatchPayload {
        if (items == null) {
            items = List.of();
        }
    }

    /** 是否为空批（消费端直接跳过） */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
