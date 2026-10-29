package com.abc.userservice.service;

import com.abc.userservice.entity.SearchHistory;
import com.abc.userservice.entity.UserFavorite;
import com.abc.userservice.entity.UserFollow;
import com.abc.userservice.entity.UserProgress;
import com.abc.userservice.entity.WatchHistory;
import com.abc.userservice.mapper.SearchHistoryMapper;
import com.abc.userservice.mapper.UserFavoriteMapper;
import com.abc.userservice.mapper.UserFollowMapper;
import com.abc.userservice.mapper.UserProgressMapper;
import com.abc.userservice.mapper.WatchHistoryMapper;
import com.abc.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户服务：资料 / 关注 / 观看历史 / 收藏追番 / 搜索历史 / 播放进度 / 用户级 AI 设置。
 *
 * <p>本轮新增（对应审计 P1 能力缺失）：</p>
 * <ul>
 *   <li>收藏与追番（user_favorite）：GET/POST/DELETE /api/user/favorite；</li>
 *   <li>搜索历史（search_history）：由 anime-service 搜索成功后 Feign 写入，提供联想词与 AI 画像；</li>
 *   <li>播放进度（user_progress）：支持 since 游标增量同步，多端续播；</li>
 *   <li>AI Key 脱敏：对外只返回掩码，ai-service 通过 /ai-key/raw + 内部令牌取原文。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final JdbcTemplate jdbcTemplate;
    private final UserFollowMapper followMapper;
    private final WatchHistoryMapper historyMapper;
    private final UserFavoriteMapper favoriteMapper;
    private final SearchHistoryMapper searchHistoryMapper;
    private final UserProgressMapper progressMapper;

    // ─── Profile ──────────────────────────────────────────────

    public Map<String, Object> getProfile(Long userId) {
        Map<String, Object> profile = jdbcTemplate.queryForMap(
                "SELECT id, username, email, avatar, bio, role, status, created_at FROM user WHERE id = ?", userId);
        // 补充社交计数（个人主页展示），失败不影响主流程
        try {
            Long following = followMapper.selectCount(
                    new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId, userId));
            Long followers = followMapper.selectCount(
                    new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFolloweeId, userId));
            profile.put("followingCount", following);
            profile.put("followersCount", followers);
        } catch (Exception ignored) {
        }
        return profile;
    }

    /**
     * 更新用户资料。用参数化查询（? 占位符）防止 SQL 注入；
     * COALESCE 保留「字段为 null 则不修改」的语义。
     */
    public void updateProfile(Long userId, String avatar, String bio, String email) {
        jdbcTemplate.update(
                "UPDATE user SET avatar = COALESCE(?, avatar), "
                        + "bio = COALESCE(?, bio), "
                        + "email = COALESCE(?, email), "
                        + "updated_at = NOW() WHERE id = ?",
                avatar, bio, email, userId);
    }

    // ─── Follow ───────────────────────────────────────────────

    public void follow(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new BusinessException("不能关注自己");
        }
        Long count = followMapper.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getFolloweeId, targetUserId));
        if (count > 0) {
            throw new BusinessException(1102, "已关注该用户");
        }
        followMapper.insert(UserFollow.builder()
                .followerId(userId)
                .followeeId(targetUserId)
                .createdAt(LocalDateTime.now())
                .build());
    }

    public void unfollow(Long userId, Long targetUserId) {
        followMapper.delete(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getFolloweeId, targetUserId));
    }

    public List<UserFollow> getFollowing(Long userId) {
        return followMapper.selectList(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, userId)
                .orderByDesc(UserFollow::getCreatedAt));
    }

    public List<UserFollow> getFollowers(Long userId) {
        return followMapper.selectList(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFolloweeId, userId)
                .orderByDesc(UserFollow::getCreatedAt));
    }

    // ─── Watch History ────────────────────────────────────────

    public List<WatchHistory> getWatchHistory(Long userId, int limit) {
        return historyMapper.selectList(new LambdaQueryWrapper<WatchHistory>()
                .eq(WatchHistory::getUserId, userId)
                .orderByDesc(WatchHistory::getWatchedAt)
                .last("LIMIT " + limit));
    }

    public void recordHistory(WatchHistory history) {
        // upsert：同一用户同一番剧同一集只保留一条，更新进度与时间
        if (history.getAnimeKey() != null && history.getEpisodeNum() != null) {
            WatchHistory exist = historyMapper.selectOne(new LambdaQueryWrapper<WatchHistory>()
                    .eq(WatchHistory::getUserId, history.getUserId())
                    .eq(WatchHistory::getAnimeKey, history.getAnimeKey())
                    .eq(WatchHistory::getEpisodeNum, history.getEpisodeNum())
                    .last("LIMIT 1"));
            if (exist != null) {
                exist.setProgress(history.getProgress());
                exist.setWatchedAt(LocalDateTime.now());
                if (history.getTitle() != null) exist.setTitle(history.getTitle());
                if (history.getCoverUrl() != null) exist.setCoverUrl(history.getCoverUrl());
                historyMapper.updateById(exist);
                return;
            }
        }
        // 新增：显式设置 watchedAt（该字段无自动填充器，DB 列 NOT NULL，null 会报错）
        history.setWatchedAt(LocalDateTime.now());
        historyMapper.insert(history);
    }

    // ─── Favorite / 追番 ──────────────────────────────────────

    public List<UserFavorite> listFavorites(Long userId, Integer status) {
        LambdaQueryWrapper<UserFavorite> wrapper = new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .orderByDesc(UserFavorite::getUpdateTime);
        if (status != null) {
            wrapper.eq(UserFavorite::getStatus, status);
        }
        return favoriteMapper.selectList(wrapper.last("LIMIT 200"));
    }

    /**
     * 新增/更新收藏（同一用户同一 anime_key 唯一）。重复收藏时只更新状态/进度，
     * 不覆盖用户之前填写的标题和封面（除非显式传入）。
     */
    public UserFavorite upsertFavorite(Long userId, UserFavorite favorite) {
        if (favorite.getAnimeKey() == null || favorite.getAnimeKey().isBlank()) {
            throw new BusinessException(400, "animeKey 不能为空");
        }
        String animeKey = favorite.getAnimeKey().trim();
        if (favorite.getStatus() != null && (favorite.getStatus() < 1 || favorite.getStatus() > 4)) {
            throw new BusinessException(400, "收藏状态不合法（1想看/2在看/3看过/4弃番）");
        }
        LocalDateTime now = LocalDateTime.now();
        UserFavorite exist = favoriteMapper.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getAnimeKey, animeKey)
                .last("LIMIT 1"));
        if (exist != null) {
            if (favorite.getStatus() != null) exist.setStatus(favorite.getStatus());
            if (favorite.getIsFollow() != null) exist.setIsFollow(favorite.getIsFollow());
            if (favorite.getLastEpisode() != null) exist.setLastEpisode(favorite.getLastEpisode());
            if (favorite.getTitle() != null) exist.setTitle(favorite.getTitle());
            if (favorite.getCoverUrl() != null) exist.setCoverUrl(favorite.getCoverUrl());
            if (favorite.getBangumiId() != null) exist.setBangumiId(favorite.getBangumiId());
            exist.setUpdateTime(now);
            favoriteMapper.updateById(exist);
            return exist;
        }
        favorite.setUserId(userId);
        favorite.setAnimeKey(animeKey);
        if (favorite.getStatus() == null) favorite.setStatus(2);     // 默认「在看」
        if (favorite.getIsFollow() == null) favorite.setIsFollow(1);
        if (favorite.getLastEpisode() == null) favorite.setLastEpisode(0);
        favorite.setCreateTime(now);
        favorite.setUpdateTime(now);
        favoriteMapper.insert(favorite);
        return favorite;
    }

    public void removeFavorite(Long userId, String animeKey) {
        favoriteMapper.delete(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getAnimeKey, animeKey));
    }

    public Map<String, Object> checkFavorite(Long userId, String animeKey) {
        UserFavorite exist = favoriteMapper.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getAnimeKey, animeKey)
                .last("LIMIT 1"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("favorite", exist != null);
        out.put("status", exist == null ? null : exist.getStatus());
        return out;
    }

    // ─── Search History ───────────────────────────────────────

    /** anime-service 搜索成功后调用：写一条搜索历史，超过 100 条时删最旧的。 */
    public void addSearchHistory(Long userId, String keyword, Integer resultCount, String source) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String raw = keyword.trim();
        if (raw.length() > 64) {
            raw = raw.substring(0, 64);
        }
        searchHistoryMapper.insert(SearchHistory.builder()
                .userId(userId)
                .keyword(raw)
                .normKeyword(normalizeKeyword(raw))
                .resultCount(resultCount == null ? 0 : resultCount)
                .source(source == null || source.isBlank() ? "WEB" : source)
                .createTime(LocalDateTime.now())
                .build());

        Long count = searchHistoryMapper.selectCount(
                new LambdaQueryWrapper<SearchHistory>().eq(SearchHistory::getUserId, userId));
        if (count != null && count > 100) {
            jdbcTemplate.update(
                    "DELETE FROM search_history WHERE user_id = ? AND id NOT IN ("
                            + "SELECT id FROM (SELECT id FROM search_history WHERE user_id = ? "
                            + "ORDER BY create_time DESC, id DESC LIMIT 100) t)",
                    userId, userId);
        }
    }

    public List<SearchHistory> listSearchHistory(Long userId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        return searchHistoryMapper.selectList(new LambdaQueryWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userId)
                .orderByDesc(SearchHistory::getCreateTime)
                .last("LIMIT " + safeLimit));
    }

    public void clearSearchHistory(Long userId) {
        searchHistoryMapper.delete(new LambdaQueryWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userId));
    }

    /**
     * 关键词归一化：全角转半角、去掉标点/空白、转小写。
     * 用于联想词去重与 AI 画像里的关键词聚合。
     */
    private String normalizeKeyword(String keyword) {
        StringBuilder sb = new StringBuilder(keyword.length());
        for (char c : keyword.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);   // 全角 ASCII → 半角
            } else if (c == 0x3000) {
                c = ' ';
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.length() > 64 ? sb.substring(0, 64) : sb.toString();
    }

    // ─── Progress（断点续播）───────────────────────────────────

    public UserProgress saveProgress(Long userId, UserProgress progress) {
        if (progress.getAnimeKey() == null || progress.getAnimeKey().isBlank()) {
            throw new BusinessException(400, "animeKey 不能为空");
        }
        int episodeNum = progress.getEpisodeNum() == null ? 1 : progress.getEpisodeNum();
        LocalDateTime now = LocalDateTime.now();
        UserProgress exist = progressMapper.selectOne(new LambdaQueryWrapper<UserProgress>()
                .eq(UserProgress::getUserId, userId)
                .eq(UserProgress::getAnimeKey, progress.getAnimeKey())
                .eq(UserProgress::getEpisodeNum, episodeNum)
                .last("LIMIT 1"));
        if (exist != null) {
            if (progress.getPositionSec() != null) exist.setPositionSec(progress.getPositionSec());
            if (progress.getDurationSec() != null) exist.setDurationSec(progress.getDurationSec());
            if (progress.getFinished() != null) exist.setFinished(progress.getFinished());
            if (progress.getDevice() != null) exist.setDevice(progress.getDevice());
            exist.setUpdateTime(now);
            progressMapper.updateById(exist);
            return exist;
        }
        progress.setUserId(userId);
        progress.setEpisodeNum(episodeNum);
        if (progress.getFinished() == null) progress.setFinished(0);
        if (progress.getDevice() == null) progress.setDevice("web");
        progress.setUpdateTime(now);
        progressMapper.insert(progress);
        return progress;
    }

    /**
     * 增量拉取播放进度：since 为上一次同步的 lastSyncAt（ISO-8601，可空）。
     * 返回 { items, lastSyncAt }，前端保存 lastSyncAt 作为下次游标，实现多端续播。
     */
    public Map<String, Object> syncProgress(Long userId, String since) {
        LambdaQueryWrapper<UserProgress> wrapper = new LambdaQueryWrapper<UserProgress>()
                .eq(UserProgress::getUserId, userId);
        LocalDateTime sinceTime = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceTime = LocalDateTime.parse(since.trim());
            } catch (Exception e) {
                throw new BusinessException(400, "since 格式不正确，应为 ISO-8601 时间");
            }
            wrapper.gt(UserProgress::getUpdateTime, sinceTime);
        }
        wrapper.orderByAsc(UserProgress::getUpdateTime).last("LIMIT 200");
        List<UserProgress> items = progressMapper.selectList(wrapper);

        String lastSyncAt = null;
        if (!items.isEmpty()) {
            lastSyncAt = items.get(items.size() - 1).getUpdateTime().toString();
        } else if (sinceTime != null) {
            lastSyncAt = sinceTime.toString();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("lastSyncAt", lastSyncAt);
        return out;
    }

    // ─── 用户级 AI / 代理设置（供 ai-service 内部调用）─────────────

    public Map<String, Object> getAiSettings(Long userId) {
        Map<String, Object> m = jdbcTemplate.queryForMap(
                "SELECT ai_api_key, ai_persona, ai_proxy_port FROM user WHERE id = ?", userId);
        Map<String, Object> out = new LinkedHashMap<>();
        Object key = m.get("ai_api_key");
        out.put("hasKey", key != null && !key.toString().isBlank());
        out.put("persona", m.get("ai_persona") == null ? "" : m.get("ai_persona"));
        out.put("proxyPort", m.get("ai_proxy_port"));
        return out;
    }

    /** ai-service 内部使用：取用户原始 API Key（明文存储，仅内部令牌可访问）。 */
    public String getAiApiKey(Long userId) {
        try {
            return jdbcTemplate.queryForObject("SELECT ai_api_key FROM user WHERE id = ?", String.class, userId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对外脱敏：只暴露前 4 位 + 后 4 位，避免前端/日志泄露完整 Key（审计 S1-4）。
     */
    public String getAiApiKeyMasked(Long userId) {
        String key = getAiApiKey(userId);
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 8) {
            return "********";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    public void updateAiSettings(Long userId, String apiKey, String persona, Integer proxyPort) {
        // 前端回传掩码时不要覆盖真实 Key（掩码包含 ****）
        String storedKey = (apiKey == null || apiKey.contains("****")) ? null : apiKey;
        if (storedKey == null) {
            jdbcTemplate.update(
                    "UPDATE user SET ai_persona = COALESCE(?, ai_persona), "
                            + "ai_proxy_port = COALESCE(?, ai_proxy_port), updated_at = NOW() WHERE id = ?",
                    persona, proxyPort, userId);
            return;
        }
        jdbcTemplate.update(
                "UPDATE user SET ai_api_key = ?, ai_persona = COALESCE(?, ai_persona), "
                        + "ai_proxy_port = COALESCE(?, ai_proxy_port), updated_at = NOW() WHERE id = ?",
                storedKey, persona, proxyPort, userId);
    }
}
