package com.abc.danmakuservice.websocket;

import com.abc.common.dto.DanmakuMessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 弹幕广播器：把"广播"从 onMessage 里彻底拿掉。
 *
 * <p>关键点：
 * <ol>
 *   <li>序列化 + 遍历 + sendText 全部丢到独立线程池，onMessage 只负责"校验 + 进缓冲"，返回时间恒定；</li>
 *   <li>getBasicRemote().sendText() 是阻塞的，且 Tomcat WebSocket 要求同一 session 不能并发写，
 *       所以对同一个 session 加 synchronized(session) 保护；</li>
 *   <li>单条发送失败（对方已断线）只记 DEBUG，不能因为一个客户端把整批广播搞崩。</li>
 * </ol>
 * 注意：广播出去的是**裸的 DanmakuMessageDto JSON**（不带 type/data 信封），
 * 因为前端 abc-frontend 的 PlayPage.vue 直接 JSON.parse 后读 text/color/position/timestamp 字段。
 */
@Slf4j
@Component
public class DanmakuBroadcaster {

    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper objectMapper;

    public DanmakuBroadcaster(@Qualifier("danmakuExecutor") ThreadPoolTaskExecutor executor,
                              ObjectMapper objectMapper) {
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    /** 异步广播一条弹幕到房间 */
    public void broadcast(String roomKey, DanmakuMessageDto message) {
        if (message == null) {
            return;
        }
        executor.execute(() -> {
            try {
                String json = objectMapper.writeValueAsString(message);
                sendToRoom(roomKey, json);
            } catch (Exception e) {
                log.warn("弹幕广播序列化失败: room={} err={}", roomKey, e.getMessage());
            }
        });
    }

    /** 异步广播已经序列化好的文本（MQ 消费侧复用） */
    public void broadcastRaw(String roomKey, String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        executor.execute(() -> sendToRoom(roomKey, json));
    }

    /**
     * 给单个会话发送错误帧。
     * 帧格式固定为 {"type":"error","message":"..."}，业务弹幕帧不带 type 字段，前端可据此区分。
     */
    public void sendError(Session session, String message) {
        Map<String, Object> frame = new LinkedHashMap<>(4);
        frame.put("type", "error");
        frame.put("message", message);
        try {
            send(session, objectMapper.writeValueAsString(frame));
        } catch (Exception e) {
            log.debug("错误帧发送失败: err={}", e.getMessage());
        }
    }

    /** 遍历房间内会话逐个发送（在广播线程池里执行） */
    private void sendToRoom(String roomKey, String json) {
        for (Session session : DanmakuRoomRegistry.sessions(roomKey)) {
            send(session, json);
        }
    }

    /**
     * 单会话发送：synchronized(session) 防止多个广播线程并发写同一个 RemoteEndpoint
     * （Tomcat 会抛 IllegalStateException: TEXT_FULL_WRITING）。
     */
    private void send(Session session, String text) {
        if (session == null || !session.isOpen()) {
            return;
        }
        synchronized (session) {
            try {
                session.getBasicRemote().sendText(text);
            } catch (Exception e) {
                log.debug("WS 发送失败（对端可能已断开）: err={}", e.getMessage());
            }
        }
    }
}
