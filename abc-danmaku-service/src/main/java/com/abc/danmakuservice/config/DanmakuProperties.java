package com.abc.danmakuservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹幕服务可配置项（对应 application.yml 中 {@code danmaku.*}）。
 *
 * <p>设计意图：把「缓冲/批量落库/频率/去重/屏蔽/两级缓存/MQ/时间窗」这些可调阈值集中到一个
 * 配置类里，而不是散落在代码常量中。答辩时可以现场改 yml（例如把 {@code danmaku.rate.max-per-window}
 * 从 20 改成 2）立即演示限流效果，而不需要重新改代码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "danmaku")
public class DanmakuProperties {

    /** Redis 缓冲 + 批量落库 */
    private Buffer buffer = new Buffer();

    /** 频率限制（防刷屏） */
    private Rate rate = new Rate();

    /** 5 秒窗去重合并 */
    private Dedup dedup = new Dedup();

    /** 屏蔽词 / 屏蔽正则 */
    private Shield shield = new Shield();

    /** 进程内 L1 缓存（自研 TTL+LRU，不引入 caffeine） */
    private LocalCache localCache = new LocalCache();

    /** RocketMQ 异步落库 */
    private Mq mq = new Mq();

    /** 时间窗查询 */
    private Window window = new Window();

    /** 热度分桶 */
    private Heat heat = new Heat();

    /** 发送端合法性校验 */
    private Send send = new Send();

    @Data
    public static class Buffer {
        /** 单次 flush 从 Redis List 取出的最大条数（LMPOP/批量口径） */
        private int batchSize = 500;
        /** flush 调度间隔（毫秒），与 @Scheduled 的 fixedDelay 对应 */
        private long flushIntervalMs = 1000;
        /** 单房间缓冲上限预警线（只告警不丢数据） */
        private long warnSize = 5000;
        /** ZSET 索引保留上限，超出后按排名裁剪最旧的（防止 Redis 无限膨胀） */
        private long zsetMaxSize = 50000;
        /** 最近 N 条弹幕的 Redis List 兼容缓存（P0 阶段已有，保留给老客户端） */
        private long recentCacheSize = 1000;
    }

    @Data
    public static class Rate {
        /** 统计窗口长度（秒） */
        private int windowSeconds = 60;
        /** 单窗口内单用户最大发送条数 */
        private int maxPerWindow = 20;
    }

    @Data
    public static class Dedup {
        /** 去重合并窗口（秒）：窗口内相同归一化文本合并为「原文 xN」 */
        private int windowSeconds = 5;
        /** 是否开启合并（关掉后只落库不合并广播文本） */
        private boolean enabled = true;
    }

    @Data
    public static class Shield {
        /** 屏蔽正则列表（Java 正则，大小写不敏感），命中则标记 blocked 且不广播 */
        private List<String> patterns = new ArrayList<>();
    }

    @Data
    public static class LocalCache {
        /** L1 最大条目数（LRU 淘汰） */
        private int maxSize = 10000;
        /** L1 条目 TTL（秒） */
        private int ttlSeconds = 60;
        /** 是否开启命中率统计日志 */
        private boolean statsEnabled = true;
    }

    @Data
    public static class Mq {
        /** 是否启用 RocketMQ 异步落库；false 时 flush 直接落 Mongo */
        private boolean enabled = true;
        /** 主题 */
        private String topic = "danmaku-topic";
        /** 消费者组 */
        private String consumerGroup = "abc-danmaku-consumer";
        /** 发送超时（毫秒） */
        private long sendTimeoutMs = 3000;
        /** 每次 MQ 消息携带的最大弹幕条数 */
        private int payloadMaxSize = 500;
    }

    @Data
    public static class Window {
        /** 单次时间窗最多返回条数（安全上限） */
        private int maxLimit = 2000;
        /** 默认时间窗长度（秒），客户端不传 endTime 时使用 */
        private double defaultSpanSeconds = 60;
        /** Redis ZSET(L2) 单次扫描条数 */
        private int scanSize = 1000;
    }

    @Data
    public static class Heat {
        /** 热度分桶宽度（秒） */
        private int bucketSeconds = 10;
        /** 热度最多统计到的时间点（秒），超出截断，防止异常时间戳撑爆结果 */
        private double maxTimelineSeconds = 4 * 60 * 60;
    }

    @Data
    public static class Send {
        /** 弹幕文本最大长度（字符数） */
        private int maxTextLength = 100;
        /** 视频内时间上限（秒）：超过视为非法（4 小时） */
        private double maxTimelineSeconds = 4 * 60 * 60;
        /** 游客是否可发弹幕（默认只读） */
        private boolean allowGuestSend = false;
    }
}
