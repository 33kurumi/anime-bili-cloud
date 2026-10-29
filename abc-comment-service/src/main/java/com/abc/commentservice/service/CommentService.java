package com.abc.commentservice.service;

import com.abc.commentservice.entity.Comment;
import com.abc.commentservice.entity.Rating;
import com.abc.commentservice.mapper.CommentMapper;
import com.abc.commentservice.mapper.RatingMapper;
import com.abc.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论 / 评分服务。
 *
 * <p>修复的审计问题（S0-2 身份伪造）：</p>
 * <ul>
 *   <li>评论人 userId / username 不再信任请求体，由 Controller 从网关注入的
 *       X-User-Id / X-Username 覆盖；</li>
 *   <li>删除评论校验所有者，越权删除返回 403；</li>
 *   <li>新增按用户查询评分（GET /api/rating/user），供 AI 服务做个性化 RAG；</li>
 *   <li>bgm: 前缀的远程番剧通过 bangumi_id 反查本地 anime.id，保证评论/评分落在同一张表。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final RatingMapper ratingMapper;
    private final JdbcTemplate jdbcTemplate;

    public Comment postComment(Comment comment) {
        if (comment.getUserId() == null) {
            // 双保险：正常请求在网关被拦截，这里兜底防止绕过网关直连服务
            throw new BusinessException(401, "请先登录");
        }
        comment.setLikeCount(0);
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);
        return comment;
    }

    public List<Comment> getCommentsByAnime(Long animeId, int page, int size) {
        return commentMapper.selectList(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getAnimeId, animeId)
                        .isNull(Comment::getParentId)   // 只取主楼，楼中楼走 /{commentId}/replies
                        .orderByDesc(Comment::getCreatedAt)
                        .last("LIMIT " + ((page - 1) * size) + ", " + size));
    }

    public List<Comment> getReplies(Long commentId) {
        return commentMapper.selectList(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getParentId, commentId)
                        .orderByAsc(Comment::getCreatedAt));
    }

    public void likeComment(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(1301, "评论不存在");
        }
        // 注意：当前 rating/comment 表没有点赞明细表，这里只能做计数 +1（无法防重复点赞），
        // 已在审计报告登记为已知限制，后续可加 comment_like(user_id, comment_id) 唯一索引表。
        comment.setLikeCount((comment.getLikeCount() == null ? 0 : comment.getLikeCount()) + 1);
        commentMapper.updateById(comment);
    }

    /**
     * 删除评论：只有作者本人可以删除；同时级联删除其回复。
     */
    public void deleteComment(Long commentId, Long userId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(1301, "评论不存在");
        }
        if (comment.getUserId() != null && !comment.getUserId().equals(userId)) {
            throw new BusinessException(403, "只能删除自己的评论");
        }
        commentMapper.deleteById(commentId);
        commentMapper.delete(new LambdaQueryWrapper<Comment>().eq(Comment::getParentId, commentId));
    }

    /**
     * 评分：每个用户对同一番剧只保留一条（先查后改/插），并把平均分回写 anime.rating_avg。
     */
    @Transactional
    public Rating rateAnime(Rating rating) {
        if (rating.getUserId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (rating.getScore() == null || rating.getScore() < 1 || rating.getScore() > 10) {
            throw new BusinessException(1302, "评分超出范围(1-10)");
        }

        Rating existing = ratingMapper.selectOne(
                new LambdaQueryWrapper<Rating>()
                        .eq(Rating::getUserId, rating.getUserId())
                        .eq(Rating::getAnimeId, rating.getAnimeId()));

        if (existing != null) {
            existing.setScore(rating.getScore());
            ratingMapper.updateById(existing);
        } else {
            rating.setCreatedAt(LocalDateTime.now());
            ratingMapper.insert(rating);
        }

        // 真实重算平均分并回写番剧表（此前审计确认这条链路是通的，保留）
        Double avg = jdbcTemplate.queryForObject(
                "SELECT AVG(score) FROM rating WHERE anime_id = ?", Double.class, rating.getAnimeId());
        if (avg != null) {
            jdbcTemplate.update("UPDATE anime SET rating_avg = ? WHERE id = ?", avg, rating.getAnimeId());
        }
        return existing != null ? existing : rating;
    }

    public Map<String, Object> getRatingStats(Long animeId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Double avg = jdbcTemplate.queryForObject(
                "SELECT AVG(score) FROM rating WHERE anime_id = ?", Double.class, animeId);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rating WHERE anime_id = ?", Long.class, animeId);

        stats.put("average", avg != null ? String.format("%.1f", avg) : "N/A");
        stats.put("count", count);

        List<Map<String, Object>> distribution = jdbcTemplate.queryForList(
                "SELECT score, COUNT(*) as cnt FROM rating WHERE anime_id = ? GROUP BY score ORDER BY score DESC",
                animeId);
        stats.put("distribution", distribution);
        return stats;
    }

    /**
     * 查询某个用户的全部评分（供 AI RAG 个性化推荐 / 前端「我的评分」）。
     */
    public List<Rating> getUserRatings(Long userId) {
        return ratingMapper.selectList(new LambdaQueryWrapper<Rating>()
                .eq(Rating::getUserId, userId)
                .orderByDesc(Rating::getCreatedAt)
                .last("LIMIT 100"));
    }

    /**
     * 把前端的番剧标识解析成 comment/rating 表里的本地 anime.id：
     * 纯数字直接用；"bgm:400602" 通过 bangumi_id 反查本地 anime 行；查不到返回 null。
     */
    public Long resolveLocalAnimeId(String animeKey) {
        if (animeKey == null || animeKey.isBlank()) {
            return null;
        }
        String key = animeKey.trim();
        boolean remote = false;
        while (key.startsWith("bgm:")) {
            key = key.substring(4);
            remote = true;
        }
        if (key.isEmpty()) {
            return null;
        }
        if (remote) {
            try {
                return jdbcTemplate.queryForObject(
                        "SELECT id FROM anime WHERE bangumi_id = ? LIMIT 1", Long.class, key);
            } catch (EmptyResultDataAccessException e) {
                return null;
            }
        }
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
