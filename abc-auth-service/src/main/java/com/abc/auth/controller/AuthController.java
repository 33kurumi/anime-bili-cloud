package com.abc.auth.controller;

import com.abc.auth.entity.User;
import com.abc.auth.service.AuthService;
import com.abc.common.dto.TokenDto;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 认证接口。
 *
 * <p>验证码流程（修复审计 S1-3）：</p>
 * <ol>
 *   <li>前端 GET /api/auth/captcha（可不传 key）→ 返回 captchaKey + Base64 图片；</li>
 *   <li>登录/注册时把 captchaKey + 用户输入的 captchaCode 一起提交；</li>
 *   <li>后端从 Redis 取出比对并立即删除，失败返回 400。</li>
 * </ol>
 */
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

        User user = authService.register(username, password, email,
                body.get("captchaKey"), body.get("captchaCode"));
        return R.ok(Map.of("userId", user.getId(), "username", user.getUsername()));
    }

    @PostMapping("/login")
    public R<TokenDto> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        TokenDto token = authService.login(username, password,
                body.get("captchaKey"), body.get("captchaCode"));
        return R.ok("登录成功", token);
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null) {
            String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
            authService.logout(token);
        }
        return R.ok("退出成功", null);
    }

    @PostMapping("/refresh")
    public R<TokenDto> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        TokenDto token = authService.refreshToken(refreshToken);
        return R.ok(token);
    }

    /**
     * 获取图片验证码。key 为空时由服务端生成随机 key 随响应下发，
     * 避免前端写死 "login" 导致所有用户共用同一个验证码槽位（旧实现可覆盖他人验证码）。
     */
    @GetMapping("/captcha")
    public R<Map<String, String>> getCaptcha(@RequestParam(required = false) String key) {
        String captchaKey = (key == null || key.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : key;
        String base64 = authService.getCaptcha(captchaKey);
        return R.ok(Map.of("captchaKey", captchaKey, "captchaImage", "data:image/png;base64," + base64));
    }
}
