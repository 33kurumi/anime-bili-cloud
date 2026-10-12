package com.abc.aiservice.feign;

import com.abc.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** 调 anime-service 搜番剧（RAG 候选召回）。 */
@FeignClient(name = "abc-anime-service")
public interface AnimeFeignClient {

    @GetMapping("/api/datasource/search")
    R<List<Map<String, Object>>> search(@RequestParam String keyword,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "8") int size);
}
