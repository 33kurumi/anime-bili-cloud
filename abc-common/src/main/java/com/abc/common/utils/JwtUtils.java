package com.abc.common.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT utility for generating, parsing, and validating tokens.
 * Used by auth-service (issue) and gateway (validate).
 */
@Slf4j
public final class JwtUtils {

    /** Default secret — override via constructor or system property in production */
    private static final String DEFAULT_SECRET = "abc-anime-bilibili-cloud-jwt-secret-key-2024-very-long-key-for-hs256";
    private static final long ACCESS_TOKEN_TTL = 2 * 60 * 60 * 1000L;       // 2 hours
    private static final long REFRESH_TOKEN_TTL = 7 * 24 * 60 * 60 * 1000L; // 7 days

    private JwtUtils() {}

    // ─── Token Generation ──────────────────────────────────────

    public static String generateAccessToken(Long userId, String username, String role) {
        return generateToken(userId, username, role, ACCESS_TOKEN_TTL);
    }

    public static String generateRefreshToken(Long userId, String username) {
        return generateToken(userId, username, null, REFRESH_TOKEN_TTL);
    }

    private static String generateToken(Long userId, String username, String role, long ttl) {
        Date now = new Date();
        JwtBuilder builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl))
                .signWith(getKey());
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.compact();
    }

    // ─── Token Parsing ─────────────────────────────────────────

    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static Long getUserId(String token) {
        try {
            String sub = parseToken(token).getSubject();
            return Long.parseLong(sub);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getUsername(String token) {
        try {
            return parseToken(token).get("username", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getRole(String token) {
        try {
            return parseToken(token).get("role", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Validation ────────────────────────────────────────────

    public static boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT invalid: {}", e.getMessage());
            return false;
        }
    }

    public static boolean isTokenExpired(String token) {
        try {
            parseToken(token);
            return false;
        } catch (ExpiredJwtException e) {
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    public static long getTokenExpiration(String token) {
        try {
            return parseToken(token).getExpiration().getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ─── Private helpers ───────────────────────────────────────

    private static SecretKey getKey() {
        String secret = System.getProperty("jwt.secret", DEFAULT_SECRET);
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
