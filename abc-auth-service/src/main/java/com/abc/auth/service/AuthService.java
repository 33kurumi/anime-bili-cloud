package com.abc.auth.service;

import com.abc.auth.entity.User;
import com.abc.common.dto.TokenDto;

/**
 * 认证服务。
 *
 * <p>安全约定（对应审计 S0-1 / S1-3）：注册与登录都必须携带一次性图片验证码，
 * 验证码由 /api/auth/captcha 下发并保存在 Redis（5 分钟、校验后即删），
 * 用于挡住脚本撞库；refresh token 采用轮换策略（refresh 时签发新的 refresh token）。</p>
 */
public interface AuthService {

    /**
     * 注册新用户。
     *
     * @param captchaKey  验证码会话 key（/api/auth/captcha 返回）
     * @param captchaCode 用户输入的验证码
     */
    User register(String username, String password, String email, String captchaKey, String captchaCode);

    /**
     * 登录：先校验验证码，再校验用户名密码，返回 JWT 双令牌。
     */
    TokenDto login(String username, String password, String captchaKey, String captchaCode);

    /**
     * 退出登录：删除 refresh token，并把 access token 加入 Redis 黑名单直至其自然过期。
     */
    void logout(String accessToken);

    /**
     * 用 refresh token 换取新令牌（同时轮换 refresh token，旧的立即失效）。
     */
    TokenDto refreshToken(String refreshToken);

    /**
     * 生成图片验证码，返回 Base64 图片数据。
     *
     * @param captchaKey 验证码会话 key（为空时由调用方生成随机 key）
     */
    String getCaptcha(String captchaKey);
}
