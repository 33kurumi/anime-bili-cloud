package com.abc.animeservice.datasource;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Video data source backed by sub.creamycake.org/v1/css1.json.
 *
 * This is NOT an AnimeDataSource — it provides video playlists, not anime metadata.
 * The CSS1 standard defines a list of "media sources", each is a scraper that
 * knows how to search a website and extract video URLs from its HTML.
 *
 * For our purposes, we parse the JSON at startup and expose:
 *   - a flat list of source names (for display)
 *   - a search/play endpoint that delegates to the source's search URL
 */
@Slf4j
@Component
public class Css1DataSource {

    private static final String CSS1_URL = "https://sub.creamycake.org/v1/css1.json";

    /** Cached media sources from css1.json */
    private final List<Map<String, Object>> mediaSources = new ArrayList<>();

    /** source name → search URL template */
    private final Map<String, String> searchTemplates = new ConcurrentHashMap<>();

    public Css1DataSource() {
        refresh();
    }

    /**
     * (Re)load css1.json from the network.
     */
    public void refresh() {
        try {
            HttpResponse resp = HttpRequest.get(CSS1_URL)
                    .header("User-Agent", "AnimeBilibiliCloud/1.0")
                    .timeout(15000)
                    .execute();
            if (resp.getStatus() == 200) {
                String body = resp.body();
                var root = JSONUtil.parseObj(body);
                var outer = root.getJSONObject("exportedMediaSourceDataList");
                var list = outer.getJSONArray("mediaSources");
                mediaSources.clear();
                searchTemplates.clear();
                if (list != null) {
                    for (Object item : list) {
                        var src = (cn.hutool.json.JSONObject) item;
                        Map<String, Object> simplified = new LinkedHashMap<>();
                        simplified.put("factoryId", src.getStr("factoryId"));
                        var args = src.getJSONObject("arguments");
                        if (args != null) {
                            String name = args.getStr("name", "unknown");
                            String desc = args.getStr("description", "");
                            String icon = args.getStr("iconUrl", "");
                            simplified.put("name", name);
                            simplified.put("description", desc);
                            simplified.put("iconUrl", icon);

                            var searchCfg = args.getJSONObject("searchConfig");
                            if (searchCfg != null) {
                                String searchUrl = searchCfg.getStr("searchUrl", "");
                                if (!searchUrl.isBlank()) {
                                    searchTemplates.put(name, searchUrl);
                                }
                            }
                        }
                        mediaSources.add(simplified);
                    }
                }
                log.info("CSS1 data source loaded: {} media sources", mediaSources.size());
            } else {
                log.error("CSS1 fetch failed: HTTP {}", resp.getStatus());
            }
        } catch (Exception e) {
            log.error("Failed to load CSS1 data source", e);
        }
    }

    /** List all available media source names (for display / admin UI). */
    public List<Map<String, Object>> listSources() {
        return Collections.unmodifiableList(mediaSources);
    }

    /**
     * Build a search URL by substituting {keyword} into the source's template.
     * @param sourceName  the "name" field from a CSS1 media source
     * @param keyword     the search term
     * @return a ready-to-share URL, or null if source not found
     */
    public String buildSearchUrl(String sourceName, String keyword) {
        String template = searchTemplates.get(sourceName);
        if (template == null) return null;
        return template.replace("{keyword}", keyword);
    }

    /**
     * Build a search URL from the highest-tier source (first in list).
     */
    public String buildDefaultSearchUrl(String keyword) {
        if (mediaSources.isEmpty() || searchTemplates.isEmpty()) return null;
        String firstName = (String) mediaSources.get(0).get("name");
        return buildSearchUrl(firstName, keyword);
    }
}
