package com.abc.userservice.controller;

import com.abc.common.result.R;
import com.abc.userservice.entity.UserFollow;
import com.abc.userservice.entity.WatchHistory;
import com.abc.userservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // ─── Profile ──────────────────────────────────────────────

    @GetMapping("/profile")
    public R<Map<String, Object>> getProfile(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userService.getProfile(userId));
    }

    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestHeader("X-User-Id") Long userId,
                                  @RequestBody Map<String, String> body) {
        userService.updateProfile(userId, body.get("avatar"), body.get("bio"), body.get("email"));
        return R.ok();
    }

    // ─── Follow ───────────────────────────────────────────────

    @PostMapping("/follow/{targetUserId}")
    public R<Void> follow(@RequestHeader("X-User-Id") Long userId,
                           @PathVariable Long targetUserId) {
        userService.follow(userId, targetUserId);
        return R.ok();
    }

    @DeleteMapping("/follow/{targetUserId}")
    public R<Void> unfollow(@RequestHeader("X-User-Id") Long userId,
                             @PathVariable Long targetUserId) {
        userService.unfollow(userId, targetUserId);
        return R.ok();
    }

    @GetMapping("/following")
    public R<List<UserFollow>> following(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userService.getFollowing(userId));
    }

    @GetMapping("/followers")
    public R<List<UserFollow>> followers(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userService.getFollowers(userId));
    }

    // ─── Watch History ────────────────────────────────────────

    @GetMapping("/history")
    public R<List<WatchHistory>> history(@RequestHeader("X-User-Id") Long userId,
                                          @RequestParam(defaultValue = "20") int limit) {
        return R.ok(userService.getWatchHistory(userId, limit));
    }

    @PostMapping("/history")
    public R<Void> recordHistory(@RequestHeader("X-User-Id") Long userId,
                                  @RequestBody WatchHistory history) {
        history.setUserId(userId);
        userService.recordHistory(history);
        return R.ok();
    }

    // ─── 用户级 AI / 代理设置 ──────────────────────────────────

    /** 查看 AI 设置（脱敏：key 只返回是否已设置）。 */
    @GetMapping("/ai-settings")
    public R<Map<String, Object>> getAiSettings(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userService.getAiSettings(userId));
    }

    /** 更新 AI 设置（apiKey / persona / proxyPort）。 */
    @PutMapping("/ai-settings")
    public R<Void> updateAiSettings(@RequestHeader("X-User-Id") Long userId,
                                    @RequestBody Map<String, Object> body) {
        String apiKey = body.get("apiKey") == null ? null : String.valueOf(body.get("apiKey"));
        String persona = body.get("persona") == null ? null : String.valueOf(body.get("persona"));
        Integer proxyPort = body.get("proxyPort") == null ? null : Integer.valueOf(String.valueOf(body.get("proxyPort")));
        userService.updateAiSettings(userId, apiKey, persona, proxyPort);
        return R.ok();
    }

    /** 供 ai-service 内部调用：取用户原始 API Key。 */
    @GetMapping("/ai-key")
    public R<String> getAiKey(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userService.getAiApiKey(userId));
    }
}
