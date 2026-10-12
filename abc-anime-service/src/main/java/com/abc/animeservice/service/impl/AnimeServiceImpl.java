package com.abc.animeservice.service.impl;

import cn.hutool.core.util.StrUtil;
import com.abc.animeservice.entity.Anime;
import com.abc.animeservice.entity.Episode;
import com.abc.animeservice.entity.Tag;
import com.abc.animeservice.mapper.AnimeMapper;
import com.abc.animeservice.mapper.EpisodeMapper;
import com.abc.animeservice.mapper.TagMapper;
import com.abc.animeservice.service.AnimeService;
import com.abc.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnimeServiceImpl implements AnimeService {

    private final AnimeMapper animeMapper;
    private final EpisodeMapper episodeMapper;
    private final TagMapper tagMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public Page<Anime> getAnimeList(int page, int size, String type, Integer year, String season,
                                     String tag, String keyword, String orderBy) {
        LambdaQueryWrapper<Anime> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(type)) {
            wrapper.eq(Anime::getType, type);
        }
        if (year != null) {
            wrapper.eq(Anime::getYear, year);
        }
        if (StrUtil.isNotBlank(season)) {
            wrapper.eq(Anime::getSeason, season);
        }
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(Anime::getTitle, keyword);
        }
        // Ordering
        if ("rating".equals(orderBy)) {
            wrapper.orderByDesc(Anime::getRatingAvg);
        } else if ("views".equals(orderBy)) {
            wrapper.orderByDesc(Anime::getViewCount);
        } else {
            wrapper.orderByDesc(Anime::getCreatedAt);
        }

        return animeMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    public Anime getAnimeDetail(Long id) {
        Anime anime = animeMapper.selectById(id);
        if (anime == null) {
            throw new BusinessException(1201, "番剧不存在");
        }
        // Increment view count (async via Redis)
        redisTemplate.opsForZSet().incrementScore("anime:views", String.valueOf(id), 1);
        return anime;
    }

    @Override
    public List<Episode> getEpisodes(Long animeId) {
        return episodeMapper.selectList(
                new LambdaQueryWrapper<Episode>()
                        .eq(Episode::getAnimeId, animeId)
                        .orderByAsc(Episode::getEpisodeNum));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Anime> getHotAnime(int limit) {
        // Top rated anime with a minimum score threshold
        return animeMapper.selectList(
                new LambdaQueryWrapper<Anime>()
                        .gt(Anime::getRatingAvg, 5.0)
                        .orderByDesc(Anime::getRatingAvg)
                        .last("LIMIT " + limit));
    }

    @Override
    public List<Map<String, Object>> getWeeklySchedule() {
        // Return current season anime group by day of week
        List<Map<String, Object>> schedule = new ArrayList<>();
        String[] days = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

        for (int i = 0; i < 7; i++) {
            Map<String, Object> daySchedule = new LinkedHashMap<>();
            daySchedule.put("day", days[i]);
            // In production, fetch from DB where weekday = i
            daySchedule.put("animeList", Collections.emptyList());
            schedule.add(daySchedule);
        }

        return schedule;
    }

    @Override
    public List<Tag> getAllTags() {
        return tagMapper.selectList(null);
    }

    @Override
    public Anime addAnime(Anime anime) {
        animeMapper.insert(anime);
        // Clear hot anime cache
        redisTemplate.delete("anime:hot:10");
        return anime;
    }

    @Override
    public Anime updateAnime(Anime anime) {
        animeMapper.updateById(anime);
        // Clear hot anime cache
        redisTemplate.delete("anime:hot:10");
        return animeMapper.selectById(anime.getId());
    }

    @Override
    public void deleteAnime(Long id) {
        animeMapper.deleteById(id);
        redisTemplate.delete("anime:hot:10");
    }
}
