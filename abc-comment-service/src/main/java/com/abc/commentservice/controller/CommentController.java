package com.abc.commentservice.controller;

import com.abc.commentservice.entity.Comment;
import com.abc.commentservice.entity.Rating;
import com.abc.commentservice.service.CommentService;
import com.abc.common.exception.BusinessException;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评论 / 评分接口。
 *
 * <p>身份来源：网关校验 JWT 后注入 X-User-Id / X-Username，业务侧不再信任请求体里的
 * userId（修复 S0-2：此前任何人可在 body 里写 userId=1 冒充他人发评论/评分）。
 * 写接口要求登录，读接口允许匿名（网关对 GET 放行）。</p>
 */
@RestController
@RequiredArgsConstructor
public class CommentController {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";

    private final CommentService commentService;

    @PostMapping("/api/comment")
    public R<Comment> postComment(@RequestHeader(value = HEADER_USER_ID, required = false) Long userId,
                                  @RequestHeader(value = HEADER_USERNAME, required = false) String username,
                                  @RequestBody Comment comment) {
        requireLogin(userId);
        Long localAnimeId = commentService.resolveLocalAnimeId(comment.getAnimeId());
        if (localAnimeId == null) {
            throw new BusinessException(400, "该番剧暂无本地数据，暂不支持评论");
        }
        // 以网关注入的身份为准，覆盖 body 里可能伪造的 userId/username
        comment.setUserId(userId);
        comment.setUsername(StringUtils.hasText(username) ? username : ("用户" + userId));
        comment.setAnimeId(String.valueOf(localAnimeId));
        return R.ok(commentService.postComment(comment));
    }

    @GetMapping("/api/comment/anime/{animeId}")
    public R<List<Comment>> getComments(
            @PathVariable String animeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long id = commentService.resolveLocalAnimeId(animeId);
        if (id == null) {
            return R.ok(List.of());
        }
        return R.ok(commentService.getCommentsByAnime(id, page, size));
    }

    @GetMapping("/api/comment/{commentId}/replies")
    public R<List<Comment>> getReplies(@PathVariable Long commentId) {
        return R.ok(commentService.getReplies(commentId));
    }

    @PostMapping("/api/comment/{commentId}/like")
    public R<Void> likeComment(@RequestHeader(value = HEADER_USER_ID, required = false) Long userId,
                               @PathVariable Long commentId) {
        requireLogin(userId);
        commentService.likeComment(commentId);
        return R.ok();
    }

    @DeleteMapping("/api/comment/{commentId}")
    public R<Void> deleteComment(@RequestHeader(value = HEADER_USER_ID, required = false) Long userId,
                                 @PathVariable Long commentId) {
        requireLogin(userId);
        commentService.deleteComment(commentId, userId);
        return R.ok();
    }

    /**
     * 评分。请求体统一收成 Map 以兼容 {@code animeId: "bgm:400602"}（String）和数字两种写法，
     * 避免 Jackson 反序列化直接把远程番剧的 key 判成 400。
     */
    @PostMapping("/api/rating")
    public R<Rating> rate(@RequestHeader(value = HEADER_USER_ID, required = false) Long userId,
                          @RequestBody Map<String, Object> body) {
        requireLogin(userId);
        Object rawAnimeId = body.get("animeId");
        Object rawScore = body.get("score");
        if (rawAnimeId == null || rawScore == null) {
            throw new BusinessException(400, "缺少 animeId 或 score");
        }
        Long localAnimeId = commentService.resolveLocalAnimeId(String.valueOf(rawAnimeId));
        if (localAnimeId == null) {
            throw new BusinessException(400, "该番剧暂无本地数据，暂不支持评分");
        }
        int score;
        try {
            score = Integer.parseInt(String.valueOf(rawScore).trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "score 必须是 1-10 的整数");
        }
        Rating rating = Rating.builder()
                .userId(userId)
                .animeId(localAnimeId)
                .score(score)
                .build();
        return R.ok(commentService.rateAnime(rating));
    }

    @GetMapping("/api/rating/anime/{animeId}")
    public R<Map<String, Object>> getRatings(@PathVariable String animeId) {
        Long id = commentService.resolveLocalAnimeId(animeId);
        if (id == null) {
            // 远程番剧还没同步到本地库：返回空统计而不是 500
            return R.ok(Map.of("avg", 0, "count", 0));
        }
        return R.ok(commentService.getRatingStats(id));
    }

    /**
     * 当前用户的评分列表（AI 服务 RAG 取数：收藏 + 评分 + 搜索记录构造用户画像）。
     */
    @GetMapping("/api/rating/user")
    public R<List<Rating>> myRatings(@RequestHeader(value = HEADER_USER_ID, required = false) Long userId) {
        requireLogin(userId);
        return R.ok(commentService.getUserRatings(userId));
    }

    private void requireLogin(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
    }
}
