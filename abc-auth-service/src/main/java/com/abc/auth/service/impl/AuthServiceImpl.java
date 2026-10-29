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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 认证服务实现。
 *
 * <p>本轮修复的审计问题：</p>
 * <ul>
 *   <li>S1-3 验证码「只发不验」：login/register 现在真正校验 {@code captcha:{key}}，
 *       一次性使用（读后即删），过期/错误直接 400；是否强制由 {@code abc.auth.captcha-required}
 *       控制（默认 false，因为当前前端登录页尚未接入验证码 UI；置 true 即强制）；</li>
 *   <li>新增登录失败限流：同一用户名 15 分钟内失败 10 次锁定，抵挡撞库；</li>
 *   <li>refresh token 复用且 role 恒为 null：refresh 时回查数据库拿最新角色，
 *       并轮换 refresh token（旧的立即失效）；</li>
 *   <li>注册无任何输入校验：用户名/密码/邮箱做格式与长度校验，避免脏数据与弱口令。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final RedissonClient redissonClient;

    private static final long ACCESS_TOKEN_TTL = 2 * 60 * 60;        // 2 小时（毫秒口径在 TokenDto 中换算）
    private static final long REFRESH_TOKEN_TTL = 7 * 24 * 60 * 60;  // 7 天（秒）
    private static final long CAPTCHA_TTL_MINUTES = 5;

    /**
     * 是否强制校验图形验证码。
     *
     * <p>默认 false：当前 abc-frontend 的登录/注册页只提交 username+password，未接入验证码 UI，
     * 若强制校验会直接导致前端无法登录（答辩现场穿帮）。因此做成开关：
     * 设为 true（环境变量 ABC_CAPTCHA_REQUIRED=true）后，登录/注册必须携带 captchaKey+captchaCode；
     * 即使开关关闭，只要请求里带了验证码参数，就照常真校验。</p>
     */
    @Value("${abc.auth.captcha-required:false}")
    private boolean captchaRequired;

    /** 同一用户名连续登录失败上限 */
    private static final int LOGIN_FAIL_LIMIT = 10;
    /** 登录失败计数窗口（分钟） */
    private static final long LOGIN_FAIL_WINDOW_MINUTES = 15;

    /** 用户名：3-20 位字母/数字/下划线/汉字（防注入式用户名、防超长；允许中文不误伤真实用户） */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_\\p{IsHan}]{3,20}$");
    /** 邮箱格式（简单校验即可，不追求 RFC 完备） */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @Override
    public User register(String username, String password, String email, String captchaKey, String captchaCode) {
        // 1. 先校验验证码：开启强制时挡住脚本刷注册；未开启时暂放行（前端未接入 UI）
        verifyCaptcha(captchaKey, captchaCode);
        validateRegisterInput(username, password, email);

        // 2. 用户名/邮箱查重
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (count > 0) {
            throw new BusinessException(ResultCode.AUTH_USERNAME_EXIST.getCode(),
                    ResultCode.AUTH_USERNAME_EXIST.getMessage());
        }
        if (email != null && !email.isBlank()) {
            count = userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getEmail, email));
            if (count > 0) {
                throw new BusinessException(ResultCode.AUTH_EMAIL_EXIST.getCode(),
                        ResultCode.AUTH_EMAIL_EXIST.getMessage());
            }
        }

        // 3. 落库：密码 BCrypt 加盐哈希，角色固定 USER（管理员只能由运维改库）
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .email(email)
                .role("USER")
                .status(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        userMapper.insert(user);

        log.info("User registered: username={}, id={}", username, user.getId());
        return user;
    }

    @Override
    public TokenDto login(String username, String password, String captchaKey, String captchaCode) {
        // 0. 暴力破解限流：先查失败计数，避免脚本反复压 BCrypt 放大 CPU 消耗
        String failKey = "login:fail:" + username;
        Integer failCount = redissonClient.<Integer>getBucket(failKey).get();
        if (failCount != null && failCount >= LOGIN_FAIL_LIMIT) {
            throw new BusinessException(429, "登录失败次数过多，请 " + LOGIN_FAIL_WINDOW_MINUTES + " 分钟后再试");
        }

        // 1. 验证码（开启强制时）先行
        verifyCaptcha(captchaKey, captchaCode);
        if (username == null || password == null) {
            throw new BusinessException(ResultCode.AUTH_LOGIN_FAILED.getCode(),
                    ResultCode.AUTH_LOGIN_FAILED.getMessage());
        }

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            recordLoginFailure(username);
            throw new BusinessException(ResultCode.AUTH_LOGIN_FAILED.getCode(),
                    ResultCode.AUTH_LOGIN_FAILED.getMessage());
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.AUTH_ACCOUNT_DISABLED.getCode(),
                    ResultCode.AUTH_ACCOUNT_DISABLED.getMessage());
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            recordLoginFailure(username);
            throw new BusinessException(ResultCode.AUTH_LOGIN_FAILED.getCode(),
                    ResultCode.AUTH_LOGIN_FAILED.getMessage());
        }
        // 登录成功：清空失败计数
        redissonClient.getBucket(failKey).delete();

        String accessToken = JwtUtils.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = JwtUtils.generateRefreshToken(user.getId(), user.getUsername());

        // refresh token 以 userId 为 key 存 Redis（7 天），实现「单端登录 + 可撤销」
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
        if (accessToken == null || !JwtUtils.isTokenValid(accessToken)) {
            return; // 令牌本身已失效，无需处理
        }
        Long userId = JwtUtils.getUserId(accessToken);
        if (userId == null) {
            return;
        }

        // 1. 删除 refresh token：之后无法再续期
        redissonClient.getBucket("refresh_token:" + userId).delete();

        // 2. access token 拉黑至其自然过期：网关会查 blacklist:{token}
        long remaining = JwtUtils.getTokenExpiration(accessToken) - System.currentTimeMillis();
        if (remaining > 0) {
            redissonClient.getBucket("blacklist:" + accessToken)
                    .set("logout", Duration.ofMillis(remaining));
        }
        log.info("User logged out: userId={}", userId);
    }

    @Override
    public TokenDto refreshToken(String refreshToken) {
        if (refreshToken == null || !JwtUtils.isTokenValid(refreshToken)) {
            throw new BusinessException(ResultCode.AUTH_TOKEN_INVALID.getCode(),
                    ResultCode.AUTH_TOKEN_INVALID.getMessage());
        }
        // access token 携带 role、refresh token 的 role 恒为 null：用这个特征拒绝拿 access token 换新令牌
        if (JwtUtils.getRole(refreshToken) != null) {
            throw new BusinessException(ResultCode.AUTH_TOKEN_INVALID.getCode(),
                    "请使用 refreshToken 换取访问令牌");
        }

        Long userId = JwtUtils.getUserId(refreshToken);
        if (userId == null) {
            throw new BusinessException(ResultCode.AUTH_TOKEN_INVALID.getCode(),
                    ResultCode.AUTH_TOKEN_INVALID.getMessage());
        }

        // 与 Redis 中的 refresh token 比对（登出后这里必然失败）
        RBucket<String> bucket = redissonClient.getBucket("refresh_token:" + userId);
        String storedToken = bucket.get();
        if (storedToken == null || !storedToken.equals(refreshToken)) {
            throw new BusinessException(ResultCode.AUTH_TOKEN_INVALID.getCode(),
                    "Refresh token已失效，请重新登录");
        }

        // 角色以数据库为准：原实现从 refresh token 里取 role 恒为 null，导致刷新后管理员掉权限
        User user = userMapper.selectById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            throw new BusinessException(ResultCode.AUTH_ACCOUNT_DISABLED.getCode(),
                    ResultCode.AUTH_ACCOUNT_DISABLED.getMessage());
        }

        String username = user.getUsername();
        String role = user.getRole();
        String newAccessToken = JwtUtils.generateAccessToken(userId, username, role);
        // 轮换 refresh token：即使旧 token 泄露，用过一次后也立即失效
        String newRefreshToken = JwtUtils.generateRefreshToken(userId, username);
        bucket.set(newRefreshToken, Duration.ofSeconds(REFRESH_TOKEN_TTL));

        log.info("Token refreshed for userId={}", userId);

        return TokenDto.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(ACCESS_TOKEN_TTL * 1000)
                .userId(userId)
                .username(username)
                .role(role)
                .build();
    }

    @Override
    public String getCaptcha(String captchaKey) {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 20);
        String code = captcha.getCode();

        // 验证码明文入 Redis（5 分钟），校验后立即删除；同一 key 重复获取会覆盖旧码
        redissonClient.getBucket("captcha:" + captchaKey)
                .set(code, Duration.ofMinutes(CAPTCHA_TTL_MINUTES));

        return captcha.getImageBase64Data();
    }

    /**
     * 校验一次性验证码：缺少参数、过期、不匹配都返回 400，且无论成功失败都把 key 删掉，
     * 防止同一验证码被暴力重放。
     */
    private void verifyCaptcha(String captchaKey, String captchaCode) {
        boolean absent = (captchaKey == null || captchaKey.isBlank())
                && (captchaCode == null || captchaCode.isBlank());
        // 前端未接入验证码 UI 时（默认）放行；开启开关或请求里带了验证码，就必须真校验
        if (absent && !captchaRequired) {
            log.debug("未开启强制验证码，跳过校验（abc.auth.captcha-required=false）");
            return;
        }
        if (captchaKey == null || captchaKey.isBlank()
                || captchaCode == null || captchaCode.isBlank()) {
            throw new BusinessException(400, "请输入验证码");
        }
        RBucket<String> bucket = redissonClient.getBucket("captcha:" + captchaKey);
        String expected = bucket.get();
        bucket.delete();
        if (expected == null) {
            throw new BusinessException(400, "验证码已过期，请刷新后重试");
        }
        if (!expected.equalsIgnoreCase(captchaCode.trim())) {
            throw new BusinessException(400, "验证码错误");
        }
    }

    /**
     * 记录一次登录失败：同一用户名 15 分钟内累计，达到上限后 login 入口直接拒绝。
     * 用 Redis 计数（而非本地 Map）保证多实例部署下限流一致。
     */
    private void recordLoginFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String key = "login:fail:" + username;
        RBucket<Integer> bucket = redissonClient.getBucket(key);
        Integer current = bucket.get();
        int next = current == null ? 1 : current + 1;
        bucket.set(next, Duration.ofMinutes(LOGIN_FAIL_WINDOW_MINUTES));
    }

    private void validateRegisterInput(String username, String password, String email) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(400, "用户名需为 3-20 位字母、数字、下划线或汉字");
        }
        if (password == null || password.length() < 6 || password.length() > 32) {
            throw new BusinessException(400, "密码长度需为 6-32 位");
        }
        if (email != null && !email.isBlank() && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new BusinessException(400, "邮箱格式不正确");
        }
    }
}
