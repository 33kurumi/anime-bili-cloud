package com.abc.animeservice.controller;

import com.abc.animeservice.entity.Anime;
import com.abc.animeservice.mapper.AnimeMapper;
import com.abc.common.result.R;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified search controller — searches BOTH local MySQL AND Bangumi API live,
 * merges results with deduplication (animeko-style multi-source search).
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final AnimeMapper animeMapper;
    private final com.abc.animeservice.datasource.BangumiDataSource bangumiDataSource;
    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping("/anime")
    public R<List<Map<String, Object>>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (keyword == null || keyword.isBlank()) {
            return R.ok(Collections.emptyList());
        }

        List<Map<String, Object>> results = new ArrayList<>();

        // 1. Search local MySQL (title LIKE %keyword%)
        List<Anime> local = animeMapper.selectList(
                new LambdaQueryWrapper<Anime>()
                        .like(Anime::getTitle, keyword)
                        .orderByDesc(Anime::getRatingAvg)
                        .last("LIMIT " + size));
        for (Anime a : local) {
            results.add(animeToResultMap(a, 1.0)); // local results boost
        }

        // 2. Search Bangumi API live (animeko-style multi-source)
        try {
            List<Map<String, Object>> remote = bangumiDataSource.search(keyword, 1, size / 2);
            for (Map<String, Object> r : remote) {
                // dedup: skip if same bangumiId already in local results
                String bid = (String) r.get("id");
                boolean dup = local.stream().anyMatch(a -> bid.equals(a.getBangumiId()));
                if (!dup) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("_id", "bgm:" + bid);
                    entry.put("title", r.get("title"));
                    entry.put("coverUrl", r.get("coverUrl"));
                    entry.put("type", r.get("type"));
                    entry.put("year", r.get("year"));
                    entry.put("_score", 0.5); // remote results
                    entry.put("_source", "bangumi");
                    results.add(entry);
                }
            }
        } catch (Exception e) {
            log.warn("Bangumi live search failed for keyword '{}': {}", keyword, e.getMessage());
        }

        // Record hot keyword
        try {
            stringRedisTemplate.opsForZSet().incrementScore("search:hot", keyword, 1);
        } catch (Exception ignored) {}

        return R.ok(results);
    }

    @GetMapping("/suggest")
    public R<List<String>> suggest(@RequestParam String keyword) {
        if (keyword == null || keyword.isBlank()) return R.ok(List.of());
        List<Anime> local = animeMapper.selectList(
                new LambdaQueryWrapper<Anime>()
                        .likeRight(Anime::getTitle, keyword)
                        .last("LIMIT 5"));
        List<String> titles = local.stream()
                .map(Anime::getTitle)
                .collect(Collectors.toList());
        return R.ok(titles);
    }

    @GetMapping("/hot")
    public R<List<Map<String, Object>>> hotSearch() {
        var top = stringRedisTemplate.opsForZSet()
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

    private Map<String, Object> animeToResultMap(Anime a, double score) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("_id", String.valueOf(a.getId()));
        map.put("title", a.getTitle());
        map.put("coverUrl", a.getCoverUrl());
        map.put("description", a.getDescription());
        map.put("type", a.getType());
        map.put("year", a.getYear());
        map.put("rating_avg", a.getRatingAvg());
        map.put("_score", score);
        map.put("_source", "local");
        return map;
    }
}
