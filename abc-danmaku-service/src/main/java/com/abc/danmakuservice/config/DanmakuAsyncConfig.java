package com.abc.danmakuservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 弹幕异步执行配置。
 *
 * <p>设计意图：WebSocket 广播是 IO 操作，如果直接在 onMessage / 定时 flush 线程里
 * 调 session.getBasicRemote().sendText()，一个慢客户端就会阻塞整个房间的广播，
 * 严重时把 flush 线程也拖死（导致缓冲堆积）。所以广播必须走独立线程池：
 * 线程池满时用 CallerRunsPolicy 退化为同步发送，保证"宁可慢也不丢"。
 */
@Slf4j
@Configuration
@EnableScheduling
public class DanmakuAsyncConfig {

    /** 弹幕广播线程池（只在广播时使用，不做落库/写 MQ 等耗时操作） */
    @Bean("danmakuExecutor")
    public ThreadPoolTaskExecutor danmakuExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("danmaku-broadcast-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        log.info("弹幕广播线程池初始化完成: core=4 max=8 queue=2000 拒绝策略=CallerRuns");
        return executor;
    }
}
