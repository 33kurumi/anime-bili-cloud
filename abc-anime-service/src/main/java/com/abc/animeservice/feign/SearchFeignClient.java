package com.abc.animeservice.feign;

import com.abc.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Map;

/**
 * 调用 search-service 维护 ES 索引（服务间协作，补齐原项目缺失的 Feign 调用）。
 */
@FeignClient(name = "abc-search-service")
public interface SearchFeignClient {

    @PostMapping("/api/search/admin/reindex")
    R<Map<String, Object>> reindex();

    @PostMapping("/api/search/admin/index/{id}")
    R<Map<String, Object>> indexOne(@PathVariable("id") long id);
}
