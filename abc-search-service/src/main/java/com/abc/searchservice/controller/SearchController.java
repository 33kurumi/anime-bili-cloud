package com.abc.searchservice.controller;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final RestHighLevelClient esClient;
    private final RedisTemplate<String, String> redisTemplate;

    @GetMapping("/anime")
    public R<List<Map<String, Object>>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (keyword == null || keyword.isBlank()) {
            return R.ok(Collections.emptyList());
        }

        try {
            SearchRequest request = new SearchRequest("abc_anime");
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

            // Multi-field match: title^3 + tags^2 + description^1
            // operator=AND：要求关键词的词全部命中，避免 "火影" 命中一堆含"影"的无关番剧
            MultiMatchQueryBuilder query = QueryBuilders.multiMatchQuery(keyword)
                    .field("title", 3.0f)
                    .field("tags", 2.0f)
                    .field("description", 1.0f)
                    .operator(org.elasticsearch.index.query.Operator.AND);
            sourceBuilder.query(query);

            // Highlight title
            HighlightBuilder highlight = new HighlightBuilder()
                    .field("title")
                    .preTags("<em>").postTags("</em>");
            sourceBuilder.highlighter(highlight);

            // Pagination —— 不再强制按 rating 排序，默认按相关度(_score)降序
            sourceBuilder.from((page - 1) * size).size(size);

            request.source(sourceBuilder);
            SearchResponse response = esClient.search(request, RequestOptions.DEFAULT);

            List<Map<String, Object>> results = new ArrayList<>();
            for (SearchHit hit : response.getHits().getHits()) {
                Map<String, Object> item = new LinkedHashMap<>(hit.getSourceAsMap());
                item.put("_id", hit.getId());
                item.put("_score", hit.getScore());
                results.add(item);
            }

            // Record search keyword (for hot search)
            redisTemplate.opsForZSet().incrementScore("search:hot", keyword, 1);

            return R.ok(results);

        } catch (IOException e) {
            log.error("ES search error", e);
            return R.fail(1601, "搜索失败: " + e.getMessage());
        }
    }

    @GetMapping("/suggest")
    public R<List<String>> suggest(@RequestParam String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return R.ok(Collections.emptyList());
        }
        try {
            SearchRequest request = new SearchRequest("abc_anime");
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.query(QueryBuilders.matchPhrasePrefixQuery("title", keyword));
            sourceBuilder.size(5);
            sourceBuilder.fetchSource("title", null);
            request.source(sourceBuilder);

            SearchResponse response = esClient.search(request, RequestOptions.DEFAULT);
            List<String> suggestions = Arrays.stream(response.getHits().getHits())
                    .map(hit -> (String) (hit.getSourceAsMap().get("title")))
                    .collect(Collectors.toList());

            return R.ok(suggestions);
        } catch (IOException e) {
            log.error("ES suggest error", e);
            return R.ok(Collections.emptyList());
        }
    }

    @GetMapping("/hot")
    public R<List<Map<String, Object>>> hotSearch() {
        // Get top 10 hot search keywords from Redis
        var top = redisTemplate.opsForZSet()
                .reverseRangeWithScores("search:hot", 0, 9);
        List<Map<String, Object>> list = new ArrayList<>();
        if (top != null) {
            for (var item : top) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("keyword", item.getValue());
                entry.put("count", item.getScore());
                list.add(entry);
            }
        }
        return R.ok(list);
    }
}
