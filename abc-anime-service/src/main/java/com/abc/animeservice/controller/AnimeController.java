package com.abc.animeservice.controller;

import ch.qos.logback.classic.Logger;
import com.abc.animeservice.entity.Anime;
import com.abc.animeservice.entity.Episode;
import com.abc.animeservice.entity.Tag;
import com.abc.animeservice.mapper.AnimeMapper;
import com.abc.animeservice.service.AnimeService;
import com.abc.animeservice.datasource.BangumiDataSource;
import com.abc.animeservice.datasource.Css1DataSource;
import com.abc.common.result.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
@Slf4j
@RestController
@RequestMapping("/api/anime")
@RequiredArgsConstructor
public class AnimeController {

    private final AnimeService animeService;
    private final AnimeMapper animeMapper;
    private final BangumiDataSource bangumiDataSource;
    private final Css1DataSource css1DataSource;

    @GetMapping("/list")
    public R<Page<Anime>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String season,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String orderBy) {
        return R.ok(animeService.getAnimeList(page, size, type, year, season, tag, keyword, orderBy));
    }

    /** Get detail for an anime. Supports both local DB ids and Bangumi remote ids. */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        // 1) 先尝试查本地数据库（纯数字 ID）
        try {
            Long dbId = Long.parseLong(id);
            Anime anime = animeService.getAnimeDetail(dbId);
            if (anime != null) {
                return R.ok(animeToMap(anime));
            }
        } catch (NumberFormatException ignored) {
            // 不是纯数字 ID，走 Bangumi 远程查询
        }

        // 2) 规范化 Bangumi ID：提取出纯数字的 bgmId
        String bgmId = id;
        // 循环去掉所有的 bgm: 前缀（兼容重复拼接的情况）
        while (bgmId.startsWith("bgm:")) {
            bgmId = bgmId.substring(4);
        }
        // 校验最终结果是不是纯数字
        if (!bgmId.matches("\\d+")) {
            return R.fail(404, "番剧不存在");
        }

        // 3) 先查本地数据库有没有同步过这条番剧
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Anime>()
                .eq(Anime::getBangumiId, bgmId);
        Anime local = animeMapper.selectOne(wrapper);
        if (local != null) {
            return R.ok(animeToMap(local));
        }

        // 4) 远程调用 Bangumi 接口查详情
        if (bangumiDataSource.isAvailable()) {
            Map<String, Object> detail = bangumiDataSource.getDetail(bgmId);
            if (detail.containsKey("anime")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> animeInfo = (Map<String, Object>) detail.get("anime");
                Map<String, Object> result = new LinkedHashMap<>(animeInfo);
                result.put("id", "bgm:" + bgmId);
                result.put("_source", "bangumi");

                // 填充远程剧集信息
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> eps = (List<Map<String, Object>>) detail.get("episodes");
                if (eps != null) {
                    result.put("episodesCount", eps.size());
                    result.put("remoteEpisodes", eps);
                }

                // ========== 新增：生成第三方播放源链接 ==========
                String title = (String) animeInfo.get("title");
                if (title != null && !title.isBlank()) {
                    List<Map<String, Object>> sources = new ArrayList<>();
                    try {
                        for (var s : css1DataSource.listSources()) {
                            String sourceName = (String) s.get("name");
                            String url = css1DataSource.buildSearchUrl(sourceName, title);
                            if (url != null) {
                                sources.add(Map.of("name", sourceName, "url", url));
                            }
                        }
                    } catch (Exception ignored) {}
                    result.put("videoSources", sources);
                }
                // ==================================================

                return R.ok(result);
            }
        }

        return R.fail(404, "番剧不存在");
    }

    @GetMapping("/{id}/episodes")
    public R<List<Episode>> episodes(@PathVariable String id) {
        String bgmId = id;
        // 循环移除所有 bgm: 前缀，兼容重复拼接的情况
        while (bgmId.startsWith("bgm:")) {
            bgmId = bgmId.substring(4);
        }

        // 1. 先尝试按本地数字ID查询
        try {
            Long dbId = Long.parseLong(bgmId);
            List<Episode> localEpisodes = animeService.getEpisodes(dbId);
            if (localEpisodes != null && !localEpisodes.isEmpty()) {
                return R.ok(localEpisodes);
            }
            // 本地查不到，继续走远程查询
        } catch (NumberFormatException ignored) {}

        // 2. 远程 Bangumi 查询剧集
        List<Episode> list = new ArrayList<>();
        if (bangumiDataSource.isAvailable()) {
            try {
                Map<String, Object> detail = bangumiDataSource.getDetail(bgmId);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> eps = (List<Map<String, Object>>) detail.get("episodes");
                if (eps != null) {
                    for (Map<String, Object> e : eps) {
                        Episode ep = new Episode();
                        ep.setEpisodeNum((Integer) e.getOrDefault("episodeNum", 0));
                        ep.setTitle((String) e.getOrDefault("title", "第" + e.get("episodeNum") + "集"));
                        ep.setDuration((Long) e.getOrDefault("duration", 0L));
                        list.add(ep);
                    }
                }
            } catch (Exception ignored) {}
        }
        return R.ok(list);
    }

    @GetMapping("/hot")
    public R<List<Map<String, Object>>> hot(@RequestParam(defaultValue = "12") int limit) {
        if (bangumiDataSource.isAvailable()) {
            try {
                // 调用你实现好的全年代热门排序接口
                List<Map<String, Object>> hotList = bangumiDataSource.getHot(1, limit);
                return R.ok(hotList);
            } catch (Exception e) {
                log.error("获取bgm热门番剧失败", e);
            }
        }
        // 兜底降级：本地数据库新番
        List<Anime> dbHot = animeService.getHotAnime(limit);
        List<Map<String, Object>> fallback = new ArrayList<>();
        for (Anime a : dbHot) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "bgm:" + a.getBangumiId());
            m.put("title", a.getTitle());
            m.put("coverUrl", a.getCoverUrl());
            m.put("type", a.getType());
            m.put("ratingAvg", a.getRatingAvg());
            m.put("episodesCount", a.getEpisodesCount());
            m.put("year", a.getYear());
            m.put("_source", "db");
            fallback.add(m);
        }
        return R.ok(fallback);
    }

    @GetMapping("/calendar")
    public R<List<Map<String, Object>>> calendar() {
        if (bangumiDataSource.isAvailable()) {
            try { return R.ok(bangumiDataSource.getCalendar()); }
            catch (Exception ignored) {}
        }
        return R.ok(animeService.getWeeklySchedule());
    }

    @GetMapping("/tags")
    public R<List<Tag>> tags() { return R.ok(animeService.getAllTags()); }

    @PostMapping
    public R<Anime> add(@RequestBody Anime anime) { return R.ok(animeService.addAnime(anime)); }

    @PutMapping("/{id}")
    public R<Anime> update(@PathVariable String id, @RequestBody Anime anime) {
        Long dbId;
        try {
            String cleanId = id;
            // 循环去掉所有 bgm: 前缀
            while (cleanId.startsWith("bgm:")) {
                cleanId = cleanId.substring(4);
            }
            dbId = Long.parseLong(cleanId);
        } catch (NumberFormatException e) {
            return R.fail(400, "无效的番剧ID格式");
        }

        anime.setId(dbId);
        return R.ok(animeService.updateAnime(anime));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        Long dbId;
        try {
            String cleanId = id;
            while (cleanId.startsWith("bgm:")) {
                cleanId = cleanId.substring(4);
            }
            dbId = Long.parseLong(cleanId);
        } catch (NumberFormatException e) {
            return R.fail(400, "无效的番剧ID格式");
        }

        animeService.deleteAnime(dbId);
        return R.ok();
    }

    private Map<String, Object> animeToMap(Anime a) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(a.getId()));
        result.put("title", a.getTitle());
        result.put("coverUrl", a.getCoverUrl());
        result.put("description", a.getDescription());
        result.put("type", a.getType());
        result.put("status", a.getStatus());
        result.put("year", a.getYear());
        result.put("season", a.getSeason());
        result.put("episodesCount", a.getEpisodesCount());
        result.put("ratingAvg", a.getRatingAvg());
        result.put("viewCount", a.getViewCount());
        result.put("studio", a.getStudio());
        if (a.getTitle() != null && !a.getTitle().isBlank()) {
            List<Map<String, Object>> sources = new ArrayList<>();
            try {
                for (var s : css1DataSource.listSources()) {
                    String name = (String) s.get("name");
                    String url = css1DataSource.buildSearchUrl(name, a.getTitle());
                    if (url != null) sources.add(Map.of("name", name, "url", url));
                }
            } catch (Exception ignored) {}
            result.put("videoSources", sources);
        }
        return result;
    }
}
