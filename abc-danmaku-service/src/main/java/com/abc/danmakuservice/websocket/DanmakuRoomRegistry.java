package com.abc.danmakuservice.websocket;

import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket 房间注册表。
 *
 * <p>从原来的 {@code DanmakuWebSocketServer} 里把静态 ROOMS 抽出来，原因是：
 * 广播现在由 {@link DanmakuBroadcaster} 异步完成，flush 线程 / MQ 消费线程也需要
 * 访问房间信息；如果 ROOMS 还留在 @ServerEndpoint 类里，会形成"工具类循环依赖"，
 * 也让 WebSocket 端点承担了不该它承担的职责。拆出来后职责清晰：
 * 端点只管连接生命周期，注册表只管房间→会话映射。
 */
@Slf4j
public final class DanmakuRoomRegistry {

    /** roomKey（animeKey:episodeNum）→ 该房间的会话集合（CopyOnWriteArraySet 适配读多写少） */
    private static final Map<String, Set<Session>> ROOMS = new ConcurrentHashMap<>();

    private DanmakuRoomRegistry() {
    }

    /** 注册会话到房间 */
    public static void register(String roomKey, Session session) {
        ROOMS.computeIfAbsent(roomKey, k -> new CopyOnWriteArraySet<>()).add(session);
        log.debug("WS 加入房间: room={} online={}", roomKey, onlineCount(roomKey));
    }

    /** 从房间移除会话；房间空了就删 key，避免 Map 无限增长 */
    public static void unregister(String roomKey, Session session) {
        Set<Session> sessions = ROOMS.get(roomKey);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            ROOMS.remove(roomKey, sessions);
        }
        log.debug("WS 离开房间: room={} online={}", roomKey, onlineCount(roomKey));
    }

    /** 当前房间内的快照（不可变，遍历时不受并发注册影响） */
    public static Set<Session> sessions(String roomKey) {
        Set<Session> sessions = ROOMS.get(roomKey);
        if (sessions == null || sessions.isEmpty()) {
            return Collections.emptySet();
        }
        return Set.copyOf(sessions);
    }

    /** 在线人数 */
    public static int onlineCount(String roomKey) {
        Set<Session> sessions = ROOMS.get(roomKey);
        return sessions == null ? 0 : sessions.size();
    }

    /** 房间数（监控用） */
    public static int roomCount() {
        return ROOMS.size();
    }
}
