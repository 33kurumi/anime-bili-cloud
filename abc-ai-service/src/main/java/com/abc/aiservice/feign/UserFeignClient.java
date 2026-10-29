package com.abc.aiservice.feign;

import com.abc.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 调 user-service 取用户数据（RAG 用户画像）。
 *
 * <p>收藏 / 搜索历史 / 原始 key 都是本次新增：原先 RAG 只有观看历史一个维度，
 * 「推荐我可能喜欢的」实际上只能靠问题关键词搜番剧，谈不上画像。</p>
 */
@FeignClient(name = "abc-user-service")
public interface UserFeignClient {

    @GetMapping("/api/user/history")
    R<List<Map<String, Object>>> history(@RequestHeader("X-User-Id") Long userId,
                                         @RequestParam(defaultValue = "20") int limit);

    /** 用户收藏/追番（RAG 兴趣来源）。 */
    @GetMapping("/api/user/favorite")
    R<List<Map<String, Object>>> favorites(@RequestHeader("X-User-Id") Long userId);

    /** 用户最近搜索词（RAG 短期兴趣）。 */
    @GetMapping("/api/user/search-history")
    R<List<Map<String, Object>>> searchHistory(@RequestHeader("X-User-Id") Long userId,
                                               @RequestParam(defaultValue = "10") int limit);

    /**
     * 取用户原始 API Key。
     * 必须带服务间令牌，user-service 会校验 X-Internal-Token（网关已剥离外部传入的同名头）。
     */
    @GetMapping("/api/user/ai-key/raw")
    R<String> aiKeyRaw(@RequestHeader("X-User-Id") Long userId,
                       @RequestHeader("X-Internal-Token") String internalToken);

    /** 取用户 AI 设置（persona 等）。 */
    @GetMapping("/api/user/ai-settings")
    R<Map<String, Object>> aiSettings(@RequestHeader("X-User-Id") Long userId);
}
