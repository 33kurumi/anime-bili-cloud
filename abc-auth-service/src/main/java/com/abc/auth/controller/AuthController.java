package com.abc.auth.controller;

import com.abc.auth.entity.User;
import com.abc.auth.service.AuthService;
import com.abc.common.dto.TokenDto;
import com.abc.common.result.R;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String email = body.get("email");

        User user = authService.register(username, password, email);
        return R.ok(Map.of("userId", user.getId(), "username", user.getUsername()));
    }

    @PostMapping("/login")
    public R<TokenDto> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        TokenDto token = authService.login(username, password);
        return R.ok("登录成功", token);
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        authService.logout(token);
        return R.ok("退出成功", null);
    }

    @PostMapping("/refresh")
    public R<TokenDto> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        TokenDto token = authService.refreshToken(refreshToken);
        return R.ok(token);
    }

    @GetMapping("/captcha")
    public R<Map<String, String>> getCaptcha(@RequestParam(defaultValue = "login") String key) {
        String base64 = authService.getCaptcha(key);
        return R.ok(Map.of("captchaKey", key, "captchaImage", "data:image/png;base64," + base64));
    }
}
