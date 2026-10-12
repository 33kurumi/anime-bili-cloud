package com.abc.animeservice.datasource;

import java.util.List;
import java.util.Map;

/**
 * Common contract for all anime data sources.
 * Modeled after animeko's SPI-based data source architecture.
 *
 * Each implementation knows how to talk to one external API
 * (Bangumi, MyAnimeList, AniList, custom JSON feeds, etc.)
 * and returns normalized anime metadata.
 */
public interface AnimeDataSource {

    /** Display name for logging and admin UI */
    String getName();

    /** Is this data source available right now? */
    boolean isAvailable();

    /**
     * Search anime by keyword.
     * @return list of simplified anime info: {id, title, coverUrl, year, type}
     */
    List<Map<String, Object>> search(String keyword, int page, int size);

    /**
     * Get full detail for a single anime, including episodes.
     * @param externalId the ID used by this data source (e.g. Bangumi subject id)
     * @return {anime: {...}, episodes: [{title, episodeNum}, ...]}
     */
    Map<String, Object> getDetail(String externalId);

    /**
     * Get trending / popular / seasonal anime list.
     * @return list of {id, title, coverUrl}
     */
    List<Map<String, Object>> getHot(int page, int size);

    /**
     * Get weekly airing schedule. Each item: {dayOfWeek: 0-6, animeList: [...]}
     */
    List<Map<String, Object>> getCalendar();
}
