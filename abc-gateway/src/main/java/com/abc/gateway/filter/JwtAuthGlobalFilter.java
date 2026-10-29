package com.abc.gateway.filter;

import com.abc.common.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 统一鉴权网关过滤器（重构版）。
 *
 * <p>设计目标（对应审计 S0-1 网关绕过 / 身份伪造）：</p>
 * <ol>
 *   <li>网关是唯一的身份注入点：先无条件剥离客户端自带的身份头，杜绝下游服务被伪造头绕过；</li>
 *   <li>浏览类 GET 接口匿名放行（对标 B 站，未登录也能看番剧/搜索/评论/弹幕），
 *       写操作一律要求登录；</li>
 *   <li>登出后 accessToken 立即失效：查 Redis 黑名单 {@code blacklist:{token}}；</li>
 *   <li>管理类接口（/api/datasource/** 番剧入库、/api/admin/**）要求 ADMIN 角色；</li>
 *   <li>下游 WebSocket 握手（/ws/**）由 danmaku-service 自行校验 token，网关只负责路由。</li>
 * </ol>
 *
 * <p>说明：黑名单检查发生在事件循环线程，Redis 是阻塞客户端，因此用
 * {@link Schedulers#boundedElastic()} 切到弹性线程池执行，避免阻塞 Netty IO 线程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_ROLE = "X-Role";
    /** 服务间内部调用凭证：外部请求携带该头一律剥离，避免客户端伪装成内部服务 */
    public static final String HEADER_INTERNAL = "X-Internal-Token";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 完全放行（不区分 HTTP 方法）：登录注册、验证码、刷新令牌、WebSocket 握手、接口文档 */
    private static final List<String> PUBLIC_ALL = List.of(
            "/api/auth/**",
            "/ws/**",
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/actuator/**",
            "/favicon.ico"
    );

    /**
     * 匿名可浏览的路径模式（仅 GET 放行；带 token 时仍会解析身份注入 header，
     * 这样 /api/user/favorite 之类接口能对已登录用户返回真实数据）。
     */
    private static final List<String> ANONYMOUS_GET = List.of(
            "/api/anime/**",
            "/api/search/**",
            "/api/source/sources",
            "/api/source/sources/**",
            "/api/source/health",
            "/api/danmaku/list",
            "/api/danmaku/window",
            "/api/danmaku/heat",
            "/api/comment/**",
            "/api/rating/**",
            "/api/user/progress",
            "/api/user/history",
            "/api/user/favorite",
            "/api/user/favorite/**",
            // 桌宠在首页匿名展示需要模型名/人设信息，不含任何用户数据
            "/api/ai/model"
    );

    /** 仅管理员可访问（后台管理，全部方法） */
    private static final List<String> ADMIN_ONLY = List.of(
            "/api/admin/**"
    );

    /**
     * 写操作才要求管理员：番剧入库/同步/镜像切换属于运维动作。
     * 读接口（镜像状态查询等）普通登录用户即可访问——前端「用户中心」用它展示数据源健康度。
     */
    private static final List<String> ADMIN_WRITE = List.of(
            "/api/datasource/**"
    );

    private final RedissonClient redissonClient;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        // 1. CORS 预检请求直接放行（CorsWebFilter 负责响应头）
        if (HttpMethod.OPTIONS.equals(method)) {
            return chain.filter(exchange);
        }

        // 2. 剥离客户端可能伪造的身份头 / 内部调用头，网关只信任自己注入的值
        ServerHttpRequest.Builder builder = request.mutate().headers(headers -> {
            headers.remove(HEADER_USER_ID);
            headers.remove(HEADER_USERNAME);
            headers.remove(HEADER_ROLE);
            headers.remove(HEADER_INTERNAL);
        });

        // 3. 完全放行路径
        if (matchesAny(PUBLIC_ALL, path)) {
            return chain.filter(exchange.mutate().request(builder.build()).build());
        }

        boolean anonymousGet = HttpMethod.GET.equals(method) && matchesAny(ANONYMOUS_GET, path);
        String token = resolveToken(request);

        // 4. 无 token：浏览类 GET 匿名放行，其余 401
        if (token == null) {
            if (anonymousGet) {
                return chain.filter(exchange.mutate().request(builder.build()).build());
            }
            return unauthorized(exchange, "未登录或缺少访问令牌");
        }

        // 5. token 非法/过期：浏览类 GET 降级为匿名，其余 401
        if (!JwtUtils.isTokenValid(token)) {
            log.debug("无效或过期的 JWT，path={}", path);
            if (anonymousGet) {
                return chain.filter(exchange.mutate().request(builder.build()).build());
            }
            return unauthorized(exchange, "登录状态已失效，请重新登录");
        }

        Long userId = JwtUtils.getUserId(token);
        String username = JwtUtils.getUsername(token);
        String role = JwtUtils.getRole(token);

        // 6. 黑名单校验（logout 后的 accessToken 立即失效）
        return isBlacklisted(token).flatMap(blacklisted -> {
            if (blacklisted) {
                log.debug("令牌已被登出拉黑，path={}", path);
                if (anonymousGet) {
                    return chain.filter(exchange.mutate().request(builder.build()).build());
                }
                return unauthorized(exchange, "该令牌已退出登录，请重新登录");
            }
            // 7. 管理接口角色校验（读接口只要求登录，写接口要求 ADMIN）
            boolean adminRequired = matchesAny(ADMIN_ONLY, path)
                    || (!HttpMethod.GET.equals(method) && matchesAny(ADMIN_WRITE, path));
            if (adminRequired && !"ADMIN".equalsIgnoreCase(role)) {
                log.warn("非管理员访问管理接口，userId={}, path={}, role={}", userId, path, role);
                return forbidden(exchange, "需要管理员权限");
            }
            // 8. 注入可信身份头
            builder.header(HEADER_USER_ID, String.valueOf(userId));
            if (username != null) {
                builder.header(HEADER_USERNAME, username);
            }
            builder.header(HEADER_ROLE, role != null ? role : "USER");
            return chain.filter(exchange.mutate().request(builder.build()).build());
        });
    }

    /** 从 Authorization: Bearer xxx 中取出令牌；为空返回 null */
    private String resolveToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Redis 黑名单查询。Redis 不可用时降级放行（仅依赖 JWT 自身有效期），
     * 保证网关不因缓存故障整体不可用；异常会打 warn 日志便于运维发现。
     */
    private Mono<Boolean> isBlacklisted(String token) {
        return Mono.fromCallable(() -> redissonClient.getBucket("blacklist:" + token).isExists())
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Redis 黑名单校验失败，降级为仅校验 JWT：{}", e.getMessage());
                    return Mono.just(false);
                });
    }

    private boolean matchesAny(List<String> patterns, String path) {
        for (String pattern : patterns) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 返回统一 JSON 错误体，前端可直接读 message 提示、按 code 跳登录页 */
    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + status.value() + ",\"message\":\"" + message + "\",\"data\":null}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        return writeJson(exchange, HttpStatus.UNAUTHORIZED, message);
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        return writeJson(exchange, HttpStatus.FORBIDDEN, message);
    }

    @Override
    public int getOrder() {
        return -100; // 早于路由转发执行
    }
}
