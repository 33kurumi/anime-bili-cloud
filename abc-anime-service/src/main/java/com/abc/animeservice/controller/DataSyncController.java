package com.abc.animeservice.controller;

import com.abc.animeservice.datasource.BangumiDataSource;
import com.abc.animeservice.datasource.Css1DataSource;
import com.abc.animeservice.datasource.DataSyncService;
import com.abc.animeservice.feign.SearchFeignClient;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
public class DataSyncController {

    private final DataSyncService dataSyncService;
    private final BangumiDataSource bangumiDataSource;
    private final Css1DataSource css1DataSource;
    private final SearchFeignClient searchFeignClient;

    /** 同步后触发 ES 增量重建（失败不影响同步结果）。 */
    private void syncToEs() {
        try {
            searchFeignClient.reindex();
            log.info("已触发 search-service 重建 ES 索引");
        } catch (Exception e) {
            log.warn("触发 ES 同步失败（search-service 未就绪？）: {}", e.getMessage());
        }
    }

    @PostMapping("/sync/trending")
    public R<Map<String, Object>> syncTrending(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int size) {
        long start = System.currentTimeMillis();
        int count = dataSyncService.syncTrendingAnime(page, size);
        syncToEs();
        long elapsed = System.currentTimeMillis() - start;
        return R.ok(Map.of(
                "source", bangumiDataSource.getName(),
                "imported", count,
                "elapsedMs", elapsed,
                "page", page,
                "size", size
        ));
    }

    @PostMapping("/sync/episodes")
    public R<Map<String, Object>> syncEpisodes() {
        long start = System.currentTimeMillis();
        int count = dataSyncService.syncEpisodesForExistingAnime();
        long elapsed = System.currentTimeMillis() - start;
        return R.ok(Map.of(
                "source", bangumiDataSource.getName(),
                "episodesImported", count,
                "elapsedMs", elapsed
        ));
    }

    @PostMapping("/sync/search")
    public R<Map<String, Object>> syncSearch(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "10") int count) {
        long start = System.currentTimeMillis();
        int imported = dataSyncService.syncBySearch(keyword, count);
        syncToEs();
        long elapsed = System.currentTimeMillis() - start;
        return R.ok(Map.of(
                "source", bangumiDataSource.getName(),
                "keyword", keyword,
                "imported", imported,
                "elapsedMs", elapsed
        ));
    }

    @PostMapping("/sync/full")
    public R<Map<String, Object>> syncFull() {
        long start = System.currentTimeMillis();
        log.info("Starting full data sync from Bangumi...");

        int animeCount = dataSyncService.syncTrendingAnime(1, 30);
        log.info("Step 1 done: {} anime imported", animeCount);

        int epCount = dataSyncService.syncEpisodesForExistingAnime();
        log.info("Step 2 done: {} episodes imported", epCount);

        long elapsed = System.currentTimeMillis() - start;
        return R.ok(Map.of(
                "source", bangumiDataSource.getName(),
                "animeImported", animeCount,
                "episodesImported", epCount,
                "elapsedMs", elapsed,
                "elapsedSec", elapsed / 1000
        ));
    }

    @GetMapping("/search")
    public R<List<Map<String, Object>>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (keyword == null || keyword.isBlank()) {
            return R.ok(List.of());
        }
        return R.ok(bangumiDataSource.search(keyword, page, size));
    }

    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        return R.ok(Map.of(
                "name", bangumiDataSource.getName(),
                "available", bangumiDataSource.isAvailable()
        ));
    }

    // ─── CSS1 video source endpoints ───────────────────────────

    /**
     * List all available CSS1 media sources (video providers).
     */
    @GetMapping("/video/sources")
    public R<List<Map<String, Object>>> listVideoSources() {
        return R.ok(css1DataSource.listSources());
    }

    /**
     * Get a video search URL for an anime title.
     * Frontend can open this URL in a new tab or embed it.
     *
     * GET /api/datasource/video/search?keyword=海豹宝宝
     */
    @GetMapping("/video/search")
    public R<Map<String, Object>> getVideoSearchUrl(@RequestParam String keyword) {
        String url = css1DataSource.buildDefaultSearchUrl(keyword);
        if (url == null) {
            return R.ok(Map.of("url", "", "message", "CSS1 data source not loaded yet"));
        }
        return R.ok(Map.of("url", url, "keyword", keyword));
    }

    /**
     * Force refresh the CSS1 data source.
     */
    @PostMapping("/video/refresh")
    public R<Map<String, Object>> refreshVideoSources() {
        css1DataSource.refresh();
        return R.ok(Map.of("sources", css1DataSource.listSources().size()));
    }
}
