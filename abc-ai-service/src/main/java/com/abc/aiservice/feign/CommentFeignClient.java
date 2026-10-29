package com.abc.aiservice.feign;

import com.abc.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

/**
 * 调 comment-service 取用户评分（RAG 口味画像）。
 * 用户给某番打 9 分、给另一部打 3 分，是最直接的偏好信号。
 */
@FeignClient(name = "abc-comment-service")
public interface CommentFeignClient {

    @GetMapping("/api/rating/user")
    R<List<Map<String, Object>>> userRatings(@RequestHeader("X-User-Id") Long userId);
}
