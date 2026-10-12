package com.abc.danmakuservice.websocket;

import cn.hutool.json.JSONUtil;
import com.abc.danmakuservice.entity.Danmaku;
import com.abc.danmakuservice.repository.DanmakuRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket endpoint for real-time danmaku communication.
 * 房间键 = animeKey + ":" + episodeNum（统一标识，P0-B）。
 * Path: /ws/danmaku/{animeKey}/{episodeNum}
 */
@Slf4j
@Component
@ServerEndpoint("/ws/danmaku/{animeKey}/{episodeNum}")
public class DanmakuWebSocketServer {

    /** roomKey → Set of sessions watching that episode */
    private static final Map<String, CopyOnWriteArraySet<Session>> ROOMS = new ConcurrentHashMap<>();

    private static DanmakuRepository danmakuRepository;
    private static RedisTemplate<String, Object> redisTemplate;
    private static ObjectMapper objectMapper;

    @Autowired
    public void setDanmakuRepository(DanmakuRepository repo) { danmakuRepository = repo; }

    @Autowired
    @SuppressWarnings("unchecked")
    public void setRedisTemplate(RedisTemplate redisTemplate) {
        DanmakuWebSocketServer.redisTemplate = redisTemplate;
    }

    @Autowired
    public void setObjectMapper(ObjectMapper mapper) { objectMapper = mapper; }

    private static String roomKey(String animeKey, Integer episodeNum) {
        return animeKey + ":" + episodeNum;
    }

    @OnOpen
    public void onOpen(Session session,
                       @PathParam("animeKey") String animeKey,
                       @PathParam("episodeNum") Integer episodeNum) {
        String key = roomKey(animeKey, episodeNum);
        session.getUserProperties().put("roomKey", key);
        // 分别保存，避免 animeKey 本身含 ':'（如 "bgm:10380"）导致拼接串无法解析
        session.getUserProperties().put("animeKey", animeKey);
        session.getUserProperties().put("episodeNum", episodeNum);
        ROOMS.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("WebSocket connected: room={}, sessionId={}, online={}",
                key, session.getId(), ROOMS.get(key).size());
    }

    @OnClose
    public void onClose(Session session) {
        String key = (String) session.getUserProperties().get("roomKey");
        if (key != null && ROOMS.containsKey(key)) {
            ROOMS.get(key).remove(session);
            if (ROOMS.get(key).isEmpty()) {
                ROOMS.remove(key);
            }
        }
        log.info("WebSocket disconnected: sessionId={}", session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        String key = (String) session.getUserProperties().get("roomKey");
        String animeKey = (String) session.getUserProperties().get("animeKey");
        Integer episodeNum = (Integer) session.getUserProperties().get("episodeNum");
        if (key == null || animeKey == null || episodeNum == null) return;

        try {
            Danmaku danmaku = objectMapper.readValue(message, Danmaku.class);
            // 以连接时的参数为准，防止客户端乱传
            danmaku.setAnimeKey(animeKey);
            danmaku.setEpisodeNum(episodeNum);
            danmaku.setSource("USER");
            danmaku.setSendTime(LocalDateTime.now());
            if (danmaku.getPosition() == null) danmaku.setPosition(0);
            if (danmaku.getColor() == null) danmaku.setColor("#FFFFFF");

            // 1. 持久化到 MongoDB
            danmakuRepository.save(danmaku);

            // 2. 缓存到 Redis（每集最近 1000 条）
            String cacheKey = "danmaku:episode:" + key;
            redisTemplate.opsForList().rightPush(cacheKey, JSONUtil.toJsonStr(danmaku));
            redisTemplate.opsForList().trim(cacheKey, -1000, -1);

            // 3. 广播给同房间所有客户端
            broadcast(key, objectMapper.writeValueAsString(danmaku));

        } catch (Exception e) {
            log.error("Failed to handle danmaku message", e);
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("WebSocket error: sessionId={}", session.getId(), error);
    }

    private void broadcast(String roomKey, String message) {
        CopyOnWriteArraySet<Session> sessions = ROOMS.get(roomKey);
        if (sessions == null) return;
        for (Session s : sessions) {
            if (s.isOpen()) {
                try {
                    s.getBasicRemote().sendText(message);
                } catch (IOException e) {
                    log.error("Failed to send to session: {}", s.getId());
                }
            }
        }
    }

    /** Get online count for a room. */
    public static int getOnlineCount(String roomKey) {
        CopyOnWriteArraySet<Session> sessions = ROOMS.get(roomKey);
        return sessions == null ? 0 : sessions.size();
    }

    public static int getOnlineCount(String animeKey, Integer episodeNum) {
        return getOnlineCount(roomKey(animeKey, episodeNum));
    }
}
