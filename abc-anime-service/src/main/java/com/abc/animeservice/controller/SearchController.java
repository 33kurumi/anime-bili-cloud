package com.abc.animeservice.controller;

import com.abc.animeservice.datasource.BangumiDataSource;
import com.abc.animeservice.entity.Anime;
import com.abc.animeservice.feign.UserFeignClient;
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
 * 统一搜索（本地 MySQL + Bangumi 实时），animeko 风格多源合并去重。
 *
 * <p>补充：搜索成功后把关键词写入 user-service 的 search_history（需登录），
 * 联想接口再把这些历史混进本地标题前缀匹配结果里。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private static final int SUGGEST_LIMIT = 10;

    private final AnimeMapper animeMapper;
    private final BangumiDataSource bangumiDataSource;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserFeignClient userFeignClient;

    @GetMapping("/anime")
    public R<List<Map<String, Object>>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (keyword == null || keyword.isBlank()) {
            return R.ok(Collections.emptyList());
        }

        List<Map<String, Object>> results = new ArrayList<>();

        // 1. 本地 MySQL 标题模糊匹配
        List<Anime> local = animeMapper.selectList(
                new LambdaQueryWrapper<Anime>()
                        .like(Anime::getTitle, keyword)
                        .orderByDesc(Anime::getRatingAvg)
                        .last("LIMIT " + size));
        for (Anime a : local) {
            results.add(animeToResultMap(a, 1.0));
        }

        // 2. Bangumi 实时搜索（多源合并）
        try {
            List<Map<String, Object>> remote = bangumiDataSource.search(keyword, page, Math.max(1, size / 2));
            for (Map<String, Object> r : remote) {
                Object rawId = r.get("id");
                if (rawId == null) {
                    continue;
                }
                String remoteId = String.valueOf(rawId);   // 形如 bgm:123
                String bangumiId = remoteId.startsWith("bgm:") ? remoteId.substring(4) : remoteId;
                boolean dup = local.stream()
                        .anyMatch(a -> a.getBangumiId() != null && a.getBangumiId().equals(bangumiId));
                if (!dup) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("_id", "bgm:" + bangumiId);
                    entry.put("bangumiId", bangumiId);
                    entry.put("title", r.get("title"));
                    entry.put("coverUrl", r.get("coverUrl"));
                    entry.put("description", r.get("summary"));
                    entry.put("type", r.get("type"));
                    entry.put("year", r.get("year"));
                    entry.put("_score", 0.5);
                    entry.put("_source", "bangumi");
                    // 前端统一字段：详情页/卡片都用同名字段渲染
                    entry.put("totalEpisodes", r.get("totalEpisodes") != null ? r.get("totalEpisodes") : r.get("episodesCount"));
                    entry.put("rating", r.get("ratingAvg"));
                    entry.put("tags", r.get("tags"));
                    results.add(entry);
                }
            }
        } catch (Exception e) {
            log.warn("Bangumi live search failed for keyword '{}': {}", keyword, e.getMessage());
        }

        // 3. 热搜 ZSet（无用户维度，仅统计）
        try {
            stringRedisTemplate.opsForZSet().incrementScore("search:hot", keyword, 1);
        } catch (Exception ignored) {
        }

        // 4. 用户搜索历史（失败不影响搜索）
        recordSearchHistory(userId, keyword.trim(), results.size());

        return R.ok(results);
    }

    /**
     * 联想：本地标题前缀匹配 + 用户自己的搜索历史（前缀命中），最多 10 条。
     */
    @GetMapping("/suggest")
    public R<List<String>> suggest(@RequestParam String keyword,
                                   @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (keyword == null || keyword.isBlank()) {
            return R.ok(List.of());
        }
        LinkedHashSet<String> titles = new LinkedHashSet<>();

        List<Anime> local = animeMapper.selectList(
                new LambdaQueryWrapper<Anime>()
                        .likeRight(Anime::getTitle, keyword)
                        .last("LIMIT 5"));
        for (Anime a : local) {
            if (a.getTitle() != null) {
                titles.add(a.getTitle());
            }
        }

        if (userId != null) {
            try {
                R<List<Map<String, Object>>> resp = userFeignClient.listSearchHistory(userId, 20);
                if (resp != null && resp.getData() != null) {
                    String lower = keyword.toLowerCase();
                    for (Map<String, Object> item : resp.getData()) {
                        String kw = item.get("keyword") == null ? null : String.valueOf(item.get("keyword"));
                        if (kw != null && kw.toLowerCase().startsWith(lower)) {
                            titles.add(kw);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("读取用户搜索历史失败（联想降级为本地匹配）: {}", e.getMessage());
            }
        }

        List<String> list = titles.stream().limit(SUGGEST_LIMIT).collect(Collectors.toList());
        return R.ok(list);
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

    private void recordSearchHistory(Long userId, String keyword, int resultCount) {
        if (userId == null || keyword.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("keyword", keyword);
            body.put("resultCount", resultCount);
            body.put("source", "WEB");
            userFeignClient.addSearchHistory(userId, body);
        } catch (Exception e) {
            log.warn("记录搜索历史失败（不影响搜索主流程）: {}", e.getMessage());
        }
    }

    private Map<String, Object> animeToResultMap(Anime a, double score) {
        Map<String, Object> map = new LinkedHashMap<>();
        // 本地 ID 与 Bangumi ID 用同一套「bgm:」前缀约定，前端只认 _id
        map.put("_id", a.getBangumiId() != null && !a.getBangumiId().isBlank()
                ? "bgm:" + a.getBangumiId() : String.valueOf(a.getId()));
        map.put("bangumiId", a.getBangumiId());
        map.put("title", a.getTitle());
        map.put("coverUrl", a.getCoverUrl());
        map.put("description", a.getDescription());
        map.put("type", a.getType());
        map.put("year", a.getYear());
        map.put("rating_avg", a.getRatingAvg());
        map.put("rating", a.getRatingAvg());
        map.put("totalEpisodes", a.getEpisodesCount());
        map.put("_score", score);
        map.put("_source", "local");
        return map;
    }
}
