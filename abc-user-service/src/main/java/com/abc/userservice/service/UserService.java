package com.abc.userservice.service;

import com.abc.userservice.entity.UserFollow;
import com.abc.userservice.entity.WatchHistory;
import com.abc.userservice.mapper.UserFollowMapper;
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

@Service
@RequiredArgsConstructor
public class UserService {

    private final JdbcTemplate jdbcTemplate;
    private final UserFollowMapper followMapper;
    private final WatchHistoryMapper historyMapper;

    public Map<String, Object> getProfile(Long userId) {
        return jdbcTemplate.queryForMap(
                "SELECT id, username, email, avatar, bio, role, status, created_at FROM user WHERE id = ?", userId);
    }

    /**
     * 更新用户资料。
     * <p>用参数化查询（? 占位符）防止 SQL 注入；COALESCE 保留"字段为 null 则不修改"的语义。
     * 切勿再用字符串拼接 SQL。
     */
    public void updateProfile(Long userId, String avatar, String bio, String email) {
        jdbcTemplate.update(
                "UPDATE user SET avatar = COALESCE(?, avatar), "
                        + "bio = COALESCE(?, bio), "
                        + "email = COALESCE(?, email), "
                        + "updated_at = NOW() WHERE id = ?",
                avatar, bio, email, userId);
    }

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

    // ─── 用户级 AI / 代理设置（供 ai-service 内部调用）─────────────

    /** 读取用户的 ai_api_key / ai_persona / ai_proxy_port（脱敏：只返回是否已设置 + persona/port）。 */
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

    /** ai-service 内部使用：取用户原始 API Key（不解密，明文存储）。 */
    public String getAiApiKey(Long userId) {
        try {
            return jdbcTemplate.queryForObject("SELECT ai_api_key FROM user WHERE id = ?", String.class, userId);
        } catch (Exception e) {
            return null;
        }
    }

    public void updateAiSettings(Long userId, String apiKey, String persona, Integer proxyPort) {
        jdbcTemplate.update(
                "UPDATE user SET ai_api_key = ?, ai_persona = ?, ai_proxy_port = ?, updated_at = NOW() WHERE id = ?",
                apiKey, persona, proxyPort, userId);
    }
}
