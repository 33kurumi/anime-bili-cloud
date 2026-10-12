package com.abc.auth.service;

import com.abc.auth.entity.User;
import com.abc.common.dto.TokenDto;
import com.abc.common.exception.BusinessException;
import com.abc.common.utils.JwtUtils;

public interface AuthService {

    /**
     * Register a new user account.
     */
    User register(String username, String password, String email);

    /**
     * Login with username + password, return JWT tokens.
     */
    TokenDto login(String username, String password);

    /**
     * Logout — blacklist current access token.
     */
    void logout(String accessToken);

    /**
     * Refresh access token using refresh token.
     */
    TokenDto refreshToken(String refreshToken);

    /**
     * Get captcha image (base64).
     */
    String getCaptcha(String captchaKey);
}
