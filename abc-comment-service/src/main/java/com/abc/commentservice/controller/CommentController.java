package com.abc.commentservice.controller;

import com.abc.commentservice.entity.Comment;
import com.abc.commentservice.entity.Rating;
import com.abc.commentservice.service.CommentService;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @PostMapping("/api/comment")
    public R<Comment> postComment(@RequestBody Comment comment) {
        String rawId = comment.getAnimeId();
        // 清除所有bgm前缀
        while(rawId.startsWith("bgm:")) rawId = rawId.substring(4);
        Long realId = Long.parseLong(rawId);
        comment.setAnimeId(String.valueOf(realId));
        return R.ok(commentService.postComment(comment));
    }

    // 修改1：查询番剧评论，参数改为String，兼容bgm:前缀
    @GetMapping("/api/comment/anime/{animeId}")
    public R<List<Comment>> getComments(
            @PathVariable String animeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 循环清除所有bgm:前缀
        String cleanId = animeId;
        while (cleanId.startsWith("bgm:")) {
            cleanId = cleanId.substring(4);
        }
        Long id;
        try {
            id = Long.parseLong(cleanId);
        } catch (NumberFormatException e) {
            // 非数字，没有本地库ID，返回空评论
            return R.ok(List.of());
        }
        return R.ok(commentService.getCommentsByAnime(id, page, size));
    }

    @GetMapping("/api/comment/{commentId}/replies")
    public R<List<Comment>> getReplies(@PathVariable Long commentId) {
        return R.ok(commentService.getReplies(commentId));
    }

    @PostMapping("/api/comment/{commentId}/like")
    public R<Void> likeComment(@PathVariable Long commentId) {
        commentService.likeComment(commentId);
        return R.ok();
    }

    @DeleteMapping("/api/comment/{commentId}")
    public R<Void> deleteComment(@PathVariable Long commentId) {
        commentService.deleteComment(commentId);
        return R.ok();
    }

    @PostMapping("/api/rating")
    public R<Rating> rate(@RequestBody Rating rating) {
        return R.ok(commentService.rateAnime(rating));
    }

    // 修改2：查询番剧评分，同样兼容bgm:前缀
    @GetMapping("/api/rating/anime/{animeId}")
    public R<Map<String, Object>> getRatings(@PathVariable String animeId) {
        String cleanId = animeId;
        while (cleanId.startsWith("bgm:")) {
            cleanId = cleanId.substring(4);
        }
        Long id;
        try {
            id = Long.parseLong(cleanId);
        } catch (NumberFormatException e) {
            // 无本地数据，返回空评分
            return R.ok(Map.of("avg", 0, "count", 0));
        }
        return R.ok(commentService.getRatingStats(id));
    }
}