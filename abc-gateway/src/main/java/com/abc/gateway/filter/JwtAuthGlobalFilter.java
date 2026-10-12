package com.abc.gateway.filter;

import com.abc.common.utils.JwtUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Global JWT authentication filter.
 * Skips whitelisted paths; validates token for all others.
 */
@Slf4j
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    /** Paths that don't require authentication */
    private static final List<String> WHITELIST = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/captcha",
            "/api/auth/refresh",
            "/api/anime/list",
            "/api/anime/hot",
            "/api/anime/calendar",
            "/api/anime/tags",
            "/api/datasource/",
            "/api/search/",
            // 观看类接口允许匿名（对标 B站）；规则管理 /api/source/rule* 仍需鉴权
            "/api/source/play",
            "/api/source/search",
            "/api/source/match",
            "/api/source/proxy",
            "/api/danmaku/list",
            "/api/danmaku/online",
            "/ws/",
            "/doc.html",
            "/webjars/",
            "/v3/api-docs",
            "/swagger-resources"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip whitelisted paths
        for (String white : WHITELIST) {
            if (path.startsWith(white)) {
                return chain.filter(exchange);
            }
        }

        // Extract Authorization header
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Bearer token found for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);

        // Validate JWT
        if (!JwtUtils.isTokenValid(token)) {
            log.debug("Invalid or expired JWT for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // Parse user info and pass downstream via custom headers
        Long userId = JwtUtils.getUserId(token);
        String username = JwtUtils.getUsername(token);
        String role = JwtUtils.getRole(token);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(userId))
                .header("X-Username", username)
                .header("X-Role", role != null ? role : "USER")
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -100; // high priority, run early
    }
}
