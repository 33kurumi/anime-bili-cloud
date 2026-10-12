package com.abc.commentservice.service;

import com.abc.commentservice.entity.Comment;
import com.abc.commentservice.entity.Rating;
import com.abc.commentservice.mapper.CommentMapper;
import com.abc.commentservice.mapper.RatingMapper;
import com.abc.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final RatingMapper ratingMapper;
    private final JdbcTemplate jdbcTemplate;

    public Comment postComment(Comment comment) {
        comment.setLikeCount(0);
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);
        return comment;
    }

    public List<Comment> getCommentsByAnime(Long animeId, int page, int size) {
        return commentMapper.selectList(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getAnimeId, animeId)
                        .isNull(Comment::getParentId)   // top-level only
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
        comment.setLikeCount(comment.getLikeCount() + 1);
        commentMapper.updateById(comment);
    }

    public void deleteComment(Long commentId) {
        commentMapper.deleteById(commentId);
        // Also delete replies
        commentMapper.delete(new LambdaQueryWrapper<Comment>().eq(Comment::getParentId, commentId));
    }

    @Transactional
    public Rating rateAnime(Rating rating) {
        if (rating.getScore() == null || rating.getScore() < 1 || rating.getScore() > 10) {
            throw new BusinessException(1302, "评分超出范围(1-10)");
        }

        // Upsert: one rating per user per anime
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

        // Update anime average rating
        Double avg = jdbcTemplate.queryForObject(
                "SELECT AVG(score) FROM rating WHERE anime_id = ?", Double.class, rating.getAnimeId());
        if (avg != null) {
            jdbcTemplate.update("UPDATE anime SET rating_avg = ? WHERE id = ?", avg, rating.getAnimeId());
        }

        return rating;
    }

    public Map<String, Object> getRatingStats(Long animeId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Double avg = jdbcTemplate.queryForObject(
                "SELECT AVG(score) FROM rating WHERE anime_id = ?", Double.class, animeId);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rating WHERE anime_id = ?", Long.class, animeId);

        stats.put("average", avg != null ? String.format("%.1f", avg) : "N/A");
        stats.put("count", count);

        // Rating distribution
        List<Map<String, Object>> distribution = jdbcTemplate.queryForList(
                "SELECT score, COUNT(*) as cnt FROM rating WHERE anime_id = ? GROUP BY score ORDER BY score DESC",
                animeId);
        stats.put("distribution", distribution);

        return stats;
    }
}
