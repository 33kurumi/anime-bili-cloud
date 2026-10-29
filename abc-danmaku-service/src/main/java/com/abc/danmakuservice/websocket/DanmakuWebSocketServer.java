package com.abc.danmakuservice.websocket;

import com.abc.common.utils.JwtUtils;
import com.abc.danmakuservice.config.DanmakuProperties;
import com.abc.danmakuservice.entity.Danmaku;
import com.abc.danmakuservice.service.DanmakuBufferService;
import com.abc.danmakuservice.service.DanmakuContentGuard;
import com.abc.danmakuservice.service.DanmakuDedupService;
import com.abc.danmakuservice.service.DanmakuRateLimiter;
import com.abc.danmakuservice.util.DanmakuKeys;
import com.abc.danmakuservice.util.DanmakuTextNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 弹幕 WebSocket 端点（改造后：onMessage 只做"校验 + 进缓冲"，绝不直接写库/广播）。
 *
 * <p>改造动机（答辩可讲）：
 * <ul>
 *   <li>原实现 onMessage 里同步 {@code repository.save()} + 同步 {@code getBasicRemote().sendText()}，
 *       一条弹幕要等 Mongo 落库 + 全房间发送完成才返回，弹幕高峰期 WS 线程被 DB 拖死；</li>
 *   <li>现在 onMessage 只做 O(1) 的内存校验、Redis 一次 INCR（限流）、一次 INCR（去重）和一次 RPUSH，
 *       实际落库由 {@code DanmakuFlushTask} 每秒批量完成，广播由独立线程池完成。</li>
 * </ul>
 *
 * <p>鉴权：token 从 {@code ?token=xxx} 查询参数或 {@code Sec-WebSocket-Protocol} 子协议
 * （浏览器原生 WebSocket 无法自定义 Header，只能借道子协议传 token）中获取，
 * 用公共模块 {@link JwtUtils} 校验。**客户端 JSON 里的 userId/username 一律忽略**，
 * 身份只认 token —— 这修掉了原来"信任客户端传 userId"的越权漏洞。
 * 未登录/令牌无效 → 游客身份：可接收弹幕，发送会被拒（回 error 帧）。
 */
@Slf4j
@Component
@ServerEndpoint(value = "/ws/danmaku/{animeKey}/{episodeNum}",
        configurator = DanmakuWebSocketServer.HandshakeConfigurator.class)
public class DanmakuWebSocketServer {

    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final String DEFAULT_COLOR = "#FFFFFF";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_ROOM = "roomKey";
    private static final String KEY_ANIME_KEY = "animeKey";
    private static final String KEY_EPISODE = "episodeNum";
    private static final String KEY_GUEST = "guest";
    private static final String ATTR_HEADERS = "wsHandshakeHeaders";

    // @ServerEndpoint 实例由 Tomcat 创建，不是 Spring Bean，无法直接 @Autowired 注入，
    // 因此用静态字段 + Spring 管理的 setter 注入（@Autowired 标注在实例 setter 上，容器启动时调用一次）。
    private static DanmakuBufferService bufferService;
    private static DanmakuRateLimiter rateLimiter;
    private static DanmakuDedupService dedupService;
    private static DanmakuContentGuard contentGuard;
    private static DanmakuBroadcaster broadcaster;
    private static ObjectMapper objectMapper;
    private static DanmakuProperties properties;

    @Autowired
    public void setBufferService(DanmakuBufferService bufferService) {
        DanmakuWebSocketServer.bufferService = bufferService;
    }

    @Autowired
    public void setRateLimiter(DanmakuRateLimiter rateLimiter) {
        DanmakuWebSocketServer.rateLimiter = rateLimiter;
    }

    @Autowired
    public void setDedupService(DanmakuDedupService dedupService) {
        DanmakuWebSocketServer.dedupService = dedupService;
    }

    @Autowired
    public void setContentGuard(DanmakuContentGuard contentGuard) {
        DanmakuWebSocketServer.contentGuard = contentGuard;
    }

    @Autowired
    public void setBroadcaster(DanmakuBroadcaster broadcaster) {
        DanmakuWebSocketServer.broadcaster = broadcaster;
    }

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        DanmakuWebSocketServer.objectMapper = objectMapper;
    }

    @Autowired
    public void setProperties(DanmakuProperties properties) {
        DanmakuWebSocketServer.properties = properties;
    }

    // ─── 握手：把 HTTP Header 带进 Endpoint（用于取子协议里的 token） ──────

    /**
     * 自定义 Configurator：{@code Session} 上拿不到原始握手 Header（Tomcat 只在握手阶段可见），
     * 所以在 modifyHandshake 里把 Header 复制到 EndpointConfig 的 userProperties，
     * onOpen 时再取出来解析 {@code Sec-WebSocket-Protocol} 里的 token。
     */
    public static class HandshakeConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            Map<String, List<String>> headers = request.getHeaders();
            if (headers != null) {
                sec.getUserProperties().put(ATTR_HEADERS, headers);
            }
        }
    }

    // ─── 生命周期 ──────────────────────────────────────────────────

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        String animeKey = pathParam(session, "animeKey");
        Integer episodeNum = parseInt(pathParam(session, "episodeNum"), 1);
        String roomKey = DanmakuKeys.room(animeKey, episodeNum);

        // 1) 解析 token：查询参数优先，其次子协议（浏览器原生 WS 无法带自定义 Header）
        String token = extractToken(session, config);
        Long userId = null;
        String username = null;
        try {
            if (token != null && !token.isEmpty()) {
                userId = JwtUtils.getUserId(token);
                username = JwtUtils.getUsername(token);
            }
        } catch (Exception e) {
            // 令牌过期/被篡改：按游客处理，不影响观看弹幕
            log.debug("WS token 解析失败，按游客处理: err={}", e.getMessage());
            userId = null;
            username = null;
        }
        boolean guest = userId == null;

        Map<String, Object> props = session.getUserProperties();
        props.put(KEY_ROOM, roomKey);
        props.put(KEY_ANIME_KEY, animeKey);
        props.put(KEY_EPISODE, episodeNum);
        props.put(KEY_USER_ID, userId);
        props.put(KEY_USERNAME, username);
        props.put(KEY_GUEST, guest);

        DanmakuRoomRegistry.register(roomKey, session);
        log.info("WS 连接建立: room={} session={} guest={} userId={} online={}",
                roomKey, session.getId(), guest, userId, DanmakuRoomRegistry.onlineCount(roomKey));
    }

    /**
     * 收到弹幕：只校验 + 写 Redis 缓冲，随后立即返回。
     * 落库与广播分别由定时任务、广播线程池完成，因此本方法不会阻塞 WS 线程。
     */
    @OnMessage
    public void onMessage(Session session, String message) {
        Map<String, Object> props = session.getUserProperties();
        String roomKey = (String) props.get(KEY_ROOM);
        String animeKey = (String) props.get(KEY_ANIME_KEY);
        Integer episodeNum = (Integer) props.get(KEY_EPISODE);
        Long userId = (Long) props.get(KEY_USER_ID);
        String username = (String) props.get(KEY_USERNAME);
        boolean guest = Boolean.TRUE.equals(props.get(KEY_GUEST));

        // 游客只读：能收不能发
        if (guest) {
            if (properties != null && properties.getSend().isAllowGuestSend()) {
                userId = -1L;
                username = username == null ? "游客" : username;
            } else {
                broadcaster.sendError(session, "请先登录后再发送弹幕");
                return;
            }
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(message);
        } catch (Exception e) {
            broadcaster.sendError(session, "弹幕格式错误");
            return;
        }
        if (node == null || node.isNull()) {
            broadcaster.sendError(session, "弹幕格式错误");
            return;
        }

        // 文本长度按"码点"计算，避免 emoji 被算成 2~4 个字符导致误判
        String text = node.path("text").asText("").trim();
        if (text.isEmpty()) {
            broadcaster.sendError(session, "弹幕内容不能为空");
            return;
        }
        int maxTextLength = properties.getSend().getMaxTextLength();
        if (DanmakuTextNormalizer.codePointLength(text) > maxTextLength) {
            broadcaster.sendError(session, "弹幕最多 " + maxTextLength + " 个字符");
            return;
        }

        // 时间轴合法性：0 ~ maxTimeline 秒，越界钳制而不是直接丢（体验优先）
        double maxTimeline = properties.getSend().getMaxTimelineSeconds();
        double timestamp = node.path("timestamp").asDouble(0d);
        if (Double.isNaN(timestamp) || Double.isInfinite(timestamp) || timestamp < 0) {
            timestamp = 0d;
        } else if (timestamp > maxTimeline) {
            timestamp = maxTimeline;
        }

        // 位置：0 滚动 / 1 顶部 / 2 底部，非法值回落到滚动
        int position = node.path("position").asInt(0);
        if (position < 0 || position > 2) {
            position = 0;
        }
        // 颜色必须是 #RRGGBB，非法值回落到白色（防 XSS 式的样式注入）
        String color = node.path("color").asText(DEFAULT_COLOR);
        if (color == null || !COLOR_PATTERN.matcher(color).matches()) {
            color = DEFAULT_COLOR;
        }
        int fontSize = node.path("fontSize").asInt(18);
        if (fontSize < 12 || fontSize > 64) {
            fontSize = 18;
        }

        // 频率限制：Redis 计数器，60 秒内最多 20 条（超限丢弃 + 错误帧）
        if (!rateLimiter.tryAcquire(userId)) {
            broadcaster.sendError(session, "发送太频繁，请稍后再试");
            return;
        }

        // 屏蔽词：命中则落库但标记 blocked，绝不广播（便于事后审计/申诉）
        boolean blocked = contentGuard.isShielded(text);
        // 5 秒窗去重：相同归一化文本合并计数，广播时显示 "原文 xN"
        int count = dedupService.markAndCount(animeKey, episodeNum, text);

        Danmaku danmaku = Danmaku.builder()
                .animeKey(animeKey)
                .episodeNum(episodeNum)
                .source("USER")
                .userId(userId)
                .username(username)
                .text(text)
                .color(color)
                .position(position)
                .fontSize(fontSize)
                .timestamp(timestamp)
                .sendTime(LocalDateTime.now())
                .count(count)
                .blocked(blocked)
                .build();

        // 只写 Redis 缓冲（RPUSH），由 DanmakuFlushTask 每秒批量落库 + 广播
        bufferService.append(danmaku);
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        cleanup(session);
        log.debug("WS 连接关闭: session={} reason={}", session.getId(), reason == null ? null : reason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        cleanup(session);
        log.warn("WS 异常: session={} err={}", session == null ? null : session.getId(), error.getMessage());
    }

    /** 房间在线人数（供 /api/danmaku/online 使用） */
    public static int getOnlineCount(String animeKey, Integer episodeNum) {
        return DanmakuRoomRegistry.onlineCount(DanmakuKeys.room(animeKey, episodeNum));
    }

    // ─── 内部工具 ─────────────────────────────────────────────────

    private void cleanup(Session session) {
        if (session == null) {
            return;
        }
        Object room = session.getUserProperties().get(KEY_ROOM);
        if (room != null) {
            DanmakuRoomRegistry.unregister((String) room, session);
        }
    }

    private static String pathParam(Session session, String name) {
        Map<String, String> params = session.getPathParameters();
        String value = params == null ? null : params.get(name);
        return value == null ? "" : value;
    }

    private static Integer parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 取 token：先查 {@code ?token=}（桌面端/自研客户端友好），
     * 再查 {@code Sec-WebSocket-Protocol} 子协议（浏览器唯一可行通道），
     * 支持 {@code token.xxx}、{@code bearer.xxx}、{@code bearer xxx} 三种写法。
     */
    private static String extractToken(Session session, EndpointConfig config) {
        try {
            List<String> queryTokens = session.getRequestParameterMap().get("token");
            if (queryTokens != null && !queryTokens.isEmpty()) {
                String v = queryTokens.get(0);
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        } catch (Exception e) {
            log.debug("读取 query token 失败: {}", e.getMessage());
        }
        Object headersObj = config == null ? null : config.getUserProperties().get(ATTR_HEADERS);
        if (headersObj instanceof Map<?, ?> headers) {
            for (Map.Entry<?, ?> entry : headers.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (!"sec-websocket-protocol".equalsIgnoreCase(name)) {
                    continue;
                }
                Object values = entry.getValue();
                if (values instanceof List<?> list) {
                    for (Object raw : list) {
                        String token = parseSubProtocolToken(String.valueOf(raw));
                        if (token != null) {
                            return token;
                        }
                    }
                } else if (values != null) {
                    String token = parseSubProtocolToken(String.valueOf(values));
                    if (token != null) {
                        return token;
                    }
                }
            }
        }
        // 兜底：Tomcat 协商出的子协议
        try {
            String negotiated = session.getNegotiatedSubprotocol();
            if (negotiated != null && !negotiated.isBlank()) {
                return parseSubProtocolToken(negotiated);
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    /** 子协议形如 "token.<jwt>" / "bearer.<jwt>"，也可能是逗号分隔的多个子协议 */
    private static String parseSubProtocolToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String part : raw.split(",")) {
            String candidate = part.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            String lower = candidate.toLowerCase();
            if (lower.startsWith("token.")) {
                return candidate.substring("token.".length());
            }
            if (lower.startsWith("bearer.")) {
                return candidate.substring("bearer.".length());
            }
            if (lower.startsWith("bearer ")) {
                return candidate.substring("bearer ".length()).trim();
            }
        }
        return null;
    }
}
