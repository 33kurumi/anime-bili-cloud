package com.abc.animeservice.feign;

import com.abc.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 调用 user-service 记录 / 读取搜索历史。
 *
 * <p>搜索联想与个性化推荐需要「用户搜过什么」，此前这类动作只落在 Redis 热搜 ZSet 里，
 * 与具体用户无关。这里由网关透传 X-User-Id，服务直连不经过网关。</p>
 */
@FeignClient(name = "abc-user-service")
public interface UserFeignClient {

    @PostMapping("/api/user/search-history")
    R<Void> addSearchHistory(@RequestHeader("X-User-Id") Long userId,
                             @RequestBody Map<String, Object> body);

    @GetMapping("/api/user/search-history")
    R<List<Map<String, Object>>> listSearchHistory(@RequestHeader("X-User-Id") Long userId,
                                                   @RequestParam(value = "limit", defaultValue = "10") int limit);
}
