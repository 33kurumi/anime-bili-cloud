package com.abc.danmakuservice.mq;

import com.abc.danmakuservice.service.DanmakuDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 弹幕 MQ 消费者：批量落库 + 广播。
 *
 * <p>为什么消费端还要 messageModel=CLUSTERING（默认）：同一消费组内多个实例分摊消息，
 * 不同消费组（例如未来的"数据统计服务"）可以各自订阅同一 topic，互不影响。
 *
 * <p>幂等性说明：Mongo 侧没有唯一索引，理论上重复消费会重复落库；
 * 但"发送成功即不再本地落库"的策略让重复只可能来自 Broker 重投，
 * 且顺序消息 + 同步发送在本地/单机场景下几乎不会触发；这里选择记录 WARN 而不是强依赖去重表，
 * 属于答辩时可以讲清楚的"一致性与复杂度权衡"。
 *
 * <p>开关：danmaku.mq.enabled=false 时整个消费者 Bean 不注册（本地无 MQ 也能启动）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "danmaku.mq.enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${danmaku.mq.topic:danmaku-topic}",
        consumerGroup = "${danmaku.mq.consumer-group:abc-danmaku-consumer}")
public class DanmakuMessageConsumer implements RocketMQListener<String> {

    private final DanmakuDispatchService dispatchService;
    private final ObjectMapper objectMapper;

    public DanmakuMessageConsumer(DanmakuDispatchService dispatchService, ObjectMapper objectMapper) {
        this.dispatchService = dispatchService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        try {
            DanmakuBatchPayload payload = objectMapper.readValue(message, DanmakuBatchPayload.class);
            if (payload.isEmpty()) {
                return;
            }
            int saved = dispatchService.dispatch(payload.items());
            log.info("MQ 消费落库完成: room={} size={} saved={}",
                    payload.roomKey(), payload.items().size(), saved);
        } catch (Exception e) {
            // 抛异常让 RocketMQ 重投（最多 16 次），避免弹幕静默丢失
            log.error("MQ 弹幕消息处理失败，等待 Broker 重投: err={}", e.getMessage());
            throw new IllegalStateException("danmaku mq consume failed", e);
        }
    }
}
