package com.abc.animeservice.service;

import com.abc.animeservice.entity.Anime;
import com.abc.animeservice.entity.Episode;
import com.abc.animeservice.entity.Tag;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.Map;

public interface AnimeService {
    Page<Anime> getAnimeList(int page, int size, String type, Integer year, String season,
                             String tag, String keyword, String orderBy);
    Anime getAnimeDetail(Long id);
    List<Episode> getEpisodes(Long animeId);
    List<Anime> getHotAnime(int limit);
    List<Map<String, Object>> getWeeklySchedule();
    List<Tag> getAllTags();
    Anime addAnime(Anime anime);
    Anime updateAnime(Anime anime);
    void deleteAnime(Long id);
}
