package com.abc.animeservice.datasource;

import com.abc.animeservice.entity.Anime;
import com.abc.animeservice.entity.Episode;
import com.abc.animeservice.mapper.AnimeMapper;
import com.abc.animeservice.mapper.EpisodeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates pulling anime data from external sources (Bangumi etc.)
 * and writing them into MySQL so they become available to end users.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncService {

    private final BangumiDataSource bangumiDataSource;
    private final AnimeMapper animeMapper;
    private final EpisodeMapper episodeMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Fetch trending anime from the data source and insert into DB.
     * Skips anime that already exist (by bangumiId).
     *
     * @return count of newly inserted anime
     */
    @Transactional
    public int syncTrendingAnime(int page, int size) {
        if (!bangumiDataSource.isAvailable()) {
            log.error("Bangumi API is not available, abort sync");
            return 0;
        }

        List<Map<String, Object>> hotList = bangumiDataSource.getHot(page, size);
        int inserted = 0;

        for (Map<String, Object> item : hotList) {
            String bangumiId = (String) item.get("id");
            // Check if already exists
            Long count = animeMapper.selectCount(
                    new LambdaQueryWrapper<Anime>().eq(Anime::getBangumiId, bangumiId));
            if (count > 0) {
                log.debug("Skipping existing anime: bangumiId={}", bangumiId);
                continue;
            }

            Anime anime = Anime.builder()
                    .title((String) item.getOrDefault("title", "Unknown"))
                    .coverUrl((String) item.getOrDefault("coverUrl", ""))
                    .type((String) item.getOrDefault("type", "TV"))
                    .year((Integer) item.getOrDefault("year", null))
                    .status("连载中")
                    .bangumiId(bangumiId)
                    .ratingAvg(parseDouble(item.get("score")))
                    .viewCount(0L)
                    .build();

            anime.setCreatedAt(LocalDateTime.now());
            anime.setUpdatedAt(LocalDateTime.now());
            animeMapper.insert(anime);
            inserted++;
            log.info("Synced anime: id={}, title={}, bangumiId={}", anime.getId(), anime.getTitle(), bangumiId);
        }

        // Clear caches
        redisTemplate.delete("anime:hot:10");
        redisTemplate.delete("anime:hot:12");

        return inserted;
    }

    /**
     * Sync episodes for all anime in DB that have a bangumiId but zero episodes.
     */
    @Transactional
    public int syncEpisodesForExistingAnime() {
        if (!bangumiDataSource.isAvailable()) {
            log.error("Bangumi API is not available, abort sync");
            return 0;
        }

        List<Anime> animeList = animeMapper.selectList(
                new LambdaQueryWrapper<Anime>().isNotNull(Anime::getBangumiId));
        AtomicInteger totalInserted = new AtomicInteger(0);

        for (Anime anime : animeList) {
            // Check if episodes already exist
            Long count = episodeMapper.selectCount(
                    new LambdaQueryWrapper<Episode>().eq(Episode::getAnimeId, anime.getId()));
            if (count > 0) {
                continue; // already has episodes
            }

            try {
                Thread.sleep(350); // rate limit for Bangumi API
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            Map<String, Object> detail = bangumiDataSource.getDetail(anime.getBangumiId());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> episodes = (List<Map<String, Object>>) detail.get("episodes");
            if (episodes == null || episodes.isEmpty()) {
                continue;
            }

            int inserted = 0;
            for (Map<String, Object> epMap : episodes) {
                Episode ep = Episode.builder()
                        .animeId(anime.getId())
                        .episodeNum((Integer) epMap.getOrDefault("episodeNum", 0))
                        .title((String) epMap.getOrDefault("title", "第" + epMap.get("episodeNum") + "集"))
                        .duration((Long) epMap.getOrDefault("duration", 0L))
                        .playCount(0L)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                episodeMapper.insert(ep);
                inserted++;
            }

            // Update episode count on anime
            anime.setEpisodesCount(episodes.size());

            // Sync description if still empty
            @SuppressWarnings("unchecked")
            Map<String, Object> animeInfo = (Map<String, Object>) detail.get("anime");
            if (animeInfo != null) {
                if (anime.getDescription() == null || anime.getDescription().isBlank()) {
                    anime.setDescription((String) animeInfo.getOrDefault("description", ""));
                }
                if (anime.getStatus() == null) {
                    anime.setStatus((String) animeInfo.getOrDefault("status", anime.getStatus()));
                }
                if (anime.getStudio() == null || anime.getStudio().isBlank()) {
                    anime.setStudio((String) animeInfo.getOrDefault("studio", ""));
                }
                if (anime.getSeason() == null) {
                    anime.setSeason((String) animeInfo.getOrDefault("season", null));
                }
                if (anime.getRatingAvg() == null || anime.getRatingAvg() == 0.0) {
                    anime.setRatingAvg(parseDouble(animeInfo.get("ratingAvg")));
                }
            }
            anime.setUpdatedAt(LocalDateTime.now());
            animeMapper.updateById(anime);

            totalInserted.addAndGet(inserted);
            log.info("Synced {} episodes for anime: id={}, title={}", inserted, anime.getId(), anime.getTitle());
        }

        redisTemplate.delete("anime:hot:10");
        return totalInserted.get();
    }

    /**
     * Search external source for a specific keyword and import the first N results.
     */
    @Transactional
    public int syncBySearch(String keyword, int importCount) {
        if (!bangumiDataSource.isAvailable()) {
            log.error("Bangumi API is not available, abort sync");
            return 0;
        }

        List<Map<String, Object>> results = bangumiDataSource.search(keyword, 1, importCount);
        int inserted = 0;

        for (Map<String, Object> item : results) {
            String bangumiId = (String) item.get("id");
            Long count = animeMapper.selectCount(
                    new LambdaQueryWrapper<Anime>().eq(Anime::getBangumiId, bangumiId));
            if (count > 0) continue;

            Anime anime = Anime.builder()
                    .title((String) item.getOrDefault("title", "Unknown"))
                    .coverUrl((String) item.getOrDefault("coverUrl", ""))
                    .description((String) item.getOrDefault("summary", ""))
                    .type((String) item.getOrDefault("type", "TV"))
                    .year((Integer) item.getOrDefault("year", null))
                    .status("连载中")
                    .bangumiId(bangumiId)
                    .ratingAvg(parseDouble(item.get("score")))
                    .viewCount(0L)
                    .build();

            anime.setCreatedAt(LocalDateTime.now());
            anime.setUpdatedAt(LocalDateTime.now());
            animeMapper.insert(anime);
            inserted++;
        }

        redisTemplate.delete("anime:hot:10");
        return inserted;
    }

    private Double parseDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Double d) return d;
        if (obj instanceof Integer i) return i.doubleValue();
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
