package com.abc.animeservice.controller;

import com.abc.animeservice.service.BangumiCommentService;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Bangumi 评论（吐槽）接口。
 * 前端详情页/播放页可调用来展示来自 Bangumi 的真实评论。
 */
@RestController
@RequestMapping("/api/anime")
@RequiredArgsConstructor
public class CommentController {

    private final BangumiCommentService bangumiCommentService;

    /**
     * 某番剧的 Bangumi 评论。
     * GET /api/anime/bgm-comments?subjectId=bgm:10380&limit=20&offset=0
     */
    @GetMapping("/bgm-comments")
    public R<List<Map<String, Object>>> bgmComments(
            @RequestParam String subjectId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return R.ok(bangumiCommentService.comments(subjectId, limit, offset));
    }
}
