package com.abc.auth.service.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.util.RandomUtil;
import com.abc.auth.entity.User;
import com.abc.auth.mapper.UserMapper;
import com.abc.auth.service.AuthService;
import com.abc.common.dto.TokenDto;
import com.abc.common.enums.ResultCode;
import com.abc.common.exception.BusinessException;
import com.abc.common.utils.JwtUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final RedissonClient redissonClient;

    private static final long ACCESS_TOKEN_TTL = 2 * 60 * 60;        // 2 hours in seconds
    private static final long REFRESH_TOKEN_TTL = 7 * 24 * 60 * 60;  // 7 days in seconds

    @Override
    public User register(String username, String password, String email) {
        // Check if username already exists
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (count > 0) {
            throw new BusinessException(ResultCode.AUTH_USERNAME_EXIST.getCode(),
                    ResultCode.AUTH_USERNAME_EXIST.getMessage());
        }

        // Check if email already registered
        if (email != null && !email.isBlank()) {
            count = userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getEmail, email));
            if (count > 0) {
                throw new BusinessException(ResultCode.AUTH_EMAIL_EXIST.getCode(),
                        ResultCode.AUTH_EMAIL_EXIST.getMessage());
            }
        }

        // Create user
        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .email(email)
                .role("USER")
                .status(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        userMapper.insert(user);

        log.info("User registered: username={}, id={}", username, user.getId());
        return user;
    }

    @Override
    public TokenDto login(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BusinessException(ResultCode.AUTH_LOGIN_FAILED.getCode(),
                    ResultCode.AUTH_LOGIN_FAILED.getMessage());
        }
        if (user.getStatus() == 0) {
            throw new BusinessException(ResultCode.AUTH_ACCOUNT_DISABLED.getCode(),
                    ResultCode.AUTH_ACCOUNT_DISABLED.getMessage());
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ResultCode.AUTH_LOGIN_FAILED.getCode(),
                    ResultCode.AUTH_LOGIN_FAILED.getMessage());
        }

        String accessToken = JwtUtils.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = JwtUtils.generateRefreshToken(user.getId(), user.getUsername());

        // Store refresh token in Redis
        RBucket<String> bucket = redissonClient.getBucket("refresh_token:" + user.getId());
        bucket.set(refreshToken, Duration.ofSeconds(REFRESH_TOKEN_TTL));

        log.info("User logged in: username={}, id={}", username, user.getId());

        return TokenDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(ACCESS_TOKEN_TTL * 1000)
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build();
    }

    @Override
    public void logout(String accessToken) {
        Long userId = JwtUtils.getUserId(accessToken);
        if (userId == null) return;

        // Remove refresh token from Redis
        redissonClient.getBucket("refresh_token:" + userId).delete();

        // Blacklist access token until it expires
        long remaining = JwtUtils.getTokenExpiration(accessToken) - System.currentTimeMillis();
        if (remaining > 0) {
            RBucket<String> bucket = redissonClient.getBucket("blacklist:" + accessToken);
            bucket.set("logout", Duration.ofMillis(remaining));
        }

        log.info("User logged out: userId={}", userId);
    }

    @Override
    public TokenDto refreshToken(String refreshToken) {
        if (!JwtUtils.isTokenValid(refreshToken)) {
            throw new BusinessException(ResultCode.AUTH_TOKEN_INVALID.getCode(),
                    ResultCode.AUTH_TOKEN_INVALID.getMessage());
        }

        Long userId = JwtUtils.getUserId(refreshToken);
        if (userId == null) {
            throw new BusinessException(ResultCode.AUTH_TOKEN_INVALID.getCode(),
                    ResultCode.AUTH_TOKEN_INVALID.getMessage());
        }

        // Verify refresh token exists in Redis
        RBucket<String> bucket = redissonClient.getBucket("refresh_token:" + userId);
        String storedToken = bucket.get();
        if (storedToken == null || !storedToken.equals(refreshToken)) {
            throw new BusinessException(ResultCode.AUTH_TOKEN_INVALID.getCode(),
                    "Refresh token已失效，请重新登录");
        }

        // Generate new access token
        String username = JwtUtils.getUsername(refreshToken);
        String role = JwtUtils.getRole(refreshToken);
        String newAccessToken = JwtUtils.generateAccessToken(userId, username, role);

        log.info("Token refreshed for userId={}", userId);

        return TokenDto.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken) // keep same refresh token
                .expiresIn(ACCESS_TOKEN_TTL * 1000)
                .userId(userId)
                .username(username)
                .role(role)
                .build();
    }

    @Override
    public String getCaptcha(String captchaKey) {
        // Generate captcha
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 20);
        String code = captcha.getCode();

        // Store captcha code in Redis (valid for 5 minutes)
        RBucket<String> bucket = redissonClient.getBucket("captcha:" + captchaKey);
        bucket.set(code, Duration.ofMinutes(5));

        return captcha.getImageBase64Data();
    }
}
