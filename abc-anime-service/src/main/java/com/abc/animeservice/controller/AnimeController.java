package com.abc.animeservice.controller;

import ch.qos.logback.classic.Logger;
import com.abc.animeservice.entity.Anime;
import com.abc.animeservice.entity.AnimeTag;
import com.abc.animeservice.entity.Episode;
import com.abc.animeservice.entity.Tag;
import com.abc.animeservice.mapper.AnimeMapper;
import com.abc.animeservice.mapper.AnimeTagMapper;
import com.abc.animeservice.mapper.EpisodeMapper;
import com.abc.animeservice.mapper.TagMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
    private final EpisodeMapper episodeMapper;
    private final AnimeTagMapper animeTagMapper;
    private final TagMapper tagMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

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

    /**
     * 分集列表。
     *
     * <p>原实现把「bgm:400602」里的数字当本地 anime.id 去查（400602 会被误当成 dbId），
     * 且每次刷新都同步打 2 个远程接口（无限流/无缓存），详情页换集即触发。
     * 现在：bgm 前缀先按 bangumi_id 找本地 anime.id → 查 episode 表；
     * 命中不了再走 Redis 缓存（6h）→ Bangumi，成功后顺手落库 episode。</p>
     */
    @GetMapping("/{id}/episodes")
    public R<List<Episode>> episodes(@PathVariable String id) {
        String bgmId = id;
        while (bgmId.startsWith("bgm:")) {
            bgmId = bgmId.substring(4);
        }

        Anime local = null;
        // 1. bgm: 前缀：按 bangumi_id 反查本地 id（不再把 bgmId 当 dbId）
        if (id.startsWith("bgm:")) {
            local = animeMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Anime>()
                    .eq(Anime::getBangumiId, bgmId).last("LIMIT 1"));
            if (local != null) {
                List<Episode> localEpisodes = animeService.getEpisodes(local.getId());
                if (localEpisodes != null && !localEpisodes.isEmpty()) {
                    return R.ok(localEpisodes);
                }
            }
        } else {
            // 2. 纯数字：直接按本地 anime.id 查
            try {
                Long dbId = Long.parseLong(bgmId);
                local = animeMapper.selectById(dbId);
                List<Episode> localEpisodes = animeService.getEpisodes(dbId);
                if (localEpisodes != null && !localEpisodes.isEmpty()) {
                    return R.ok(localEpisodes);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (!bgmId.matches("\\d+")) {
            return R.ok(new ArrayList<>());
        }

        String cacheKey = "anime:episodes:" + bgmId;
        // 3. Redis 缓存（6 小时）——避免每次进详情页都打 Bangumi
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                return R.ok(objectMapper.readValue(cached, new TypeReference<List<Episode>>() {
                }));
            }
        } catch (Exception e) {
            log.warn("读取分集缓存失败，回源 Bangumi: {}", e.getMessage());
        }

        // 4. 远程 Bangumi
        List<Episode> list = new ArrayList<>();
        if (bangumiDataSource.isAvailable()) {
            try {
                Map<String, Object> detail = bangumiDataSource.getDetail(bgmId);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> eps = (List<Map<String, Object>>) detail.get("episodes");
                if (eps != null) {
                    for (Map<String, Object> e : eps) {
                        Episode ep = new Episode();
                        ep.setEpisodeNum(e.get("episodeNum") == null ? 0 : ((Number) e.get("episodeNum")).intValue());
                        ep.setTitle(e.get("title") == null ? "第" + ep.getEpisodeNum() + "集" : String.valueOf(e.get("title")));
                        ep.setDuration(e.get("duration") == null ? 0L : ((Number) e.get("duration")).longValue());
                        list.add(ep);
                    }
                }
            } catch (Exception e) {
                log.warn("Bangumi 分集获取失败: {}", e.getMessage());
            }
        }

        if (!list.isEmpty()) {
            // 5. 写缓存（失败不影响返回）
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(list), 6, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("写分集缓存失败: {}", e.getMessage());
            }
            // 6. 本地有该番剧则落库 episode，后续走纯 DB 查询
            if (local != null) {
                try {
                    for (Episode ep : list) {
                        ep.setAnimeId(local.getId());
                        episodeMapper.insert(ep);
                    }
                } catch (Exception e) {
                    log.warn("分集落库失败（可忽略，下次仍走缓存）: {}", e.getMessage());
                }
            }
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

    /** 读取番剧标签名（anime_tag -> tag），失败降级为空列表，不阻塞详情接口。 */
    private List<String> loadTags(Long animeId) {
        try {
            List<AnimeTag> relations = animeTagMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AnimeTag>()
                            .eq(AnimeTag::getAnimeId, animeId));
            if (relations.isEmpty()) {
                return List.of();
            }
            List<Long> tagIds = relations.stream().map(AnimeTag::getTagId).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            if (tagIds.isEmpty()) {
                return List.of();
            }
            return tagMapper.selectBatchIds(tagIds).stream().map(Tag::getName)
                    .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("读取番剧标签失败 animeId={}: {}", animeId, e.getMessage());
            return List.of();
        }
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
        // 统一字段：前端详情页/卡片/收藏都按这套字段渲染（原先缺 bangumiId 导致收藏键错乱）
        result.put("bangumiId", a.getBangumiId());
        result.put("totalEpisodes", a.getEpisodesCount());
        result.put("rating", a.getRatingAvg());
        result.put("tags", loadTags(a.getId()));
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
