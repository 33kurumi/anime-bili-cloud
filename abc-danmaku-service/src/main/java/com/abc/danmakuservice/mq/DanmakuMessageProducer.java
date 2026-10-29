package com.abc.danmakuservice.mq;

import com.abc.danmakuservice.config.DanmakuProperties;
import com.abc.danmakuservice.entity.Danmaku;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹幕 MQ 生产者（RocketMQ 真实接入）。
 *
 * <p>为什么用 {@link ObjectProvider} 而不是直接 @Autowired：
 * 本地开发/答辩演示环境不一定起 RocketMQ，如果强依赖注入，容器启动就会失败。
 * 用 ObjectProvider 可以做到"有就发，没有就走本地降级路径"，保证服务永远可启动。
 *
 * <p>发送失败（Broker 不可用、超时）不抛异常给调用方，而是返回 false，
 * 由 {@code DanmakuFlushTask} 把这批弹幕直接用 Mongo 落库 —— 保证"不丢"，
 * 也不重发（sent 成功的批次不会再走本地落库），保证"不重"。
 */
@Slf4j
@Component
public class DanmakuMessageProducer {

    private final ObjectProvider<RocketMQTemplate> templateProvider;
    private final ObjectMapper objectMapper;
    private final DanmakuProperties properties;

    public DanmakuMessageProducer(ObjectProvider<RocketMQTemplate> templateProvider,
                                  ObjectMapper objectMapper,
                                  DanmakuProperties properties) {
        this.templateProvider = templateProvider;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** MQ 是否可用：配置打开 且 容器里确实有 RocketMQTemplate */
    public boolean available() {
        return properties.getMq().isEnabled() && templateProvider.getIfAvailable() != null;
    }

    /**
     * 同步发送一批弹幕。
     *
     * @return true=已投递到 MQ（消费端负责落库+广播）；false=投递失败，调用方需本地落库
     */
    public boolean send(String roomKey, String animeKey, Integer episodeNum, List<Danmaku> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null || !properties.getMq().isEnabled()) {
            return false;
        }
        int maxSize = Math.max(1, properties.getMq().getPayloadMaxSize());
        List<Danmaku> limited = items.size() > maxSize
                ? new ArrayList<>(items.subList(0, maxSize))
                : items;
        DanmakuBatchPayload payload = new DanmakuBatchPayload(roomKey, animeKey, episodeNum, limited);
        String destination = properties.getMq().getTopic();
        try {
            String json = objectMapper.writeValueAsString(payload);
            template.syncSend(destination, json);
            log.debug("弹幕批次已投递 MQ: room={} size={} topic={}", roomKey, limited.size(), destination);
            return true;
        } catch (Exception e) {
            // 记录降级原因，flush 任务会把该批直接落 Mongo
            log.warn("RocketMQ 发送失败，本批降级本地落库: room={} size={} topic={} err={}",
                    roomKey, limited.size(), destination, e.getMessage());
            return false;
        }
    }
}
