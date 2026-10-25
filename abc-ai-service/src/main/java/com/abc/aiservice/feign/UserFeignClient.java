package com.abc.aiservice.feign;

import com.abc.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** 调 user-service 取用户数据（RAG 用户画像）。 */
@FeignClient(name = "abc-user-service")
public interface UserFeignClient {

    @GetMapping("/api/user/history")
    R<List<Map<String, Object>>> history(@RequestHeader("X-User-Id") Long userId,
                                         @RequestParam(defaultValue = "20") int limit);

    /** 取用户自己的大模型 API Key（可空）。 */
    @GetMapping("/api/user/ai-key")
    R<String> aiKey(@RequestHeader("X-User-Id") Long userId);

    /** 取用户 AI 设置（persona 等）。 */
    @GetMapping("/api/user/ai-settings")
    R<Map<String, Object>> aiSettings(@RequestHeader("X-User-Id") Long userId);
}
