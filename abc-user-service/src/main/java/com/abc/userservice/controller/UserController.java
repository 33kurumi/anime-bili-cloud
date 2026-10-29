package com.abc.userservice.controller;

import com.abc.common.exception.BusinessException;
import com.abc.common.result.R;
import com.abc.userservice.entity.SearchHistory;
import com.abc.userservice.entity.UserFavorite;
import com.abc.userservice.entity.UserFollow;
import com.abc.userservice.entity.UserProgress;
import com.abc.userservice.entity.WatchHistory;
import com.abc.userservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户接口。
 *
 * <p>身份来源：网关校验 JWT 后注入 X-User-Id；这里所有写接口都要求该头存在，
 * 读接口中的「收藏列表 / 播放进度」允许匿名（未登录返回空 200，前端可无感渲染）。</p>
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 服务间内部调用令牌，防止外部直接调用 /ai-key/raw 拖库 */
    @Value("${abc.internal-token:abc-internal-token-2024}")
    private String internalToken;

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

    /** 观看历史。允许匿名：首页对游客展示时返回空数组，避免 401 打断页面渲染。 */
    @GetMapping("/history")
    public R<List<WatchHistory>> history(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                          @RequestParam(defaultValue = "20") int limit) {
        if (userId == null) {
            return R.ok(List.of());
        }
        return R.ok(userService.getWatchHistory(userId, limit));
    }

    @PostMapping("/history")
    public R<Void> recordHistory(@RequestHeader("X-User-Id") Long userId,
                                  @RequestBody WatchHistory history) {
        history.setUserId(userId);
        userService.recordHistory(history);
        return R.ok();
    }

    // ─── Favorite / 追番 ──────────────────────────────────────

    /**
     * 收藏列表。允许匿名：未登录返回空数组而非 401，前端首页/个人页可以无差别渲染。
     */
    @GetMapping("/favorite")
    public R<List<UserFavorite>> favorites(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                           @RequestParam(required = false) Integer status) {
        if (userId == null) {
            return R.ok(List.of());
        }
        return R.ok(userService.listFavorites(userId, status));
    }

    @PostMapping("/favorite")
    public R<UserFavorite> addFavorite(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                       @RequestBody UserFavorite favorite) {
        return R.ok(userService.upsertFavorite(requireLogin(userId), favorite));
    }

    @DeleteMapping("/favorite/{animeKey}")
    public R<Void> removeFavorite(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                  @PathVariable String animeKey) {
        userService.removeFavorite(requireLogin(userId), animeKey);
        return R.ok();
    }

    @GetMapping("/favorite/{animeKey}/check")
    public R<Map<String, Object>> checkFavorite(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                                @PathVariable String animeKey) {
        if (userId == null) {
            return R.ok(Map.of("favorite", false));
        }
        return R.ok(userService.checkFavorite(userId, animeKey));
    }

    // ─── Search History ───────────────────────────────────────

    @GetMapping("/search-history")
    public R<List<SearchHistory>> searchHistory(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                                @RequestParam(defaultValue = "10") int limit) {
        return R.ok(userService.listSearchHistory(requireLogin(userId), limit));
    }

    /**
     * 写搜索历史。anime-service 搜索成功后通过 Feign 调用（带 X-User-Id），
     * 内部调用走服务直连，不经过网关。
     */
    @PostMapping("/search-history")
    public R<Void> addSearchHistory(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                    @RequestBody Map<String, Object> body) {
        Object keyword = body.get("keyword");
        if (keyword == null || String.valueOf(keyword).isBlank()) {
            return R.ok();
        }
        Integer resultCount = body.get("resultCount") == null ? 0
                : Integer.valueOf(String.valueOf(body.get("resultCount")));
        String source = body.get("source") == null ? "WEB" : String.valueOf(body.get("source"));
        userService.addSearchHistory(requireLogin(userId), String.valueOf(keyword), resultCount, source);
        return R.ok();
    }

    @DeleteMapping("/search-history")
    public R<Void> clearSearchHistory(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        userService.clearSearchHistory(requireLogin(userId));
        return R.ok();
    }

    // ─── Progress（断点续播 / 多端同步）────────────────────────

    @GetMapping("/progress")
    public R<Map<String, Object>> getProgress(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                              @RequestParam(required = false) String since) {
        if (userId == null) {
            return R.ok(Map.of("items", List.of(), "lastSyncAt", ""));
        }
        return R.ok(userService.syncProgress(userId, since));
    }

    @PostMapping("/progress")
    public R<UserProgress> saveProgress(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                        @RequestBody UserProgress progress) {
        return R.ok(userService.saveProgress(requireLogin(userId), progress));
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

    /**
     * 查看用户 API Key 掩码（前端设置页回显用，形如 sk-a****cdef）。
     * 完整 Key 不再回传前端，避免 XSS/日志泄露。
     */
    @GetMapping("/ai-key")
    public R<String> getAiKey(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userService.getAiApiKeyMasked(userId));
    }

    /**
     * 供 ai-service 内部 Feign 调用：取用户原始 API Key。
     * 必须携带服务间令牌 X-Internal-Token（网关会剥离外部请求携带的该头）。
     */
    @GetMapping("/ai-key/raw")
    public R<String> getAiKeyRaw(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                 @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (token == null || !token.equals(internalToken)) {
            throw new BusinessException(403, "内部令牌无效");
        }
        if (userId == null) {
            throw new BusinessException(400, "缺少 X-User-Id");
        }
        return R.ok(userService.getAiApiKey(userId));
    }

    private Long requireLogin(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return userId;
    }
}
