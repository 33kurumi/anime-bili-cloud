package com.abc.sourceservice.engine;

import com.abc.sourceservice.engine.EngineModels.Road;
import com.abc.sourceservice.engine.EngineModels.SearchItem;
import com.abc.sourceservice.engine.strategy.ApiStrategy;
import com.abc.sourceservice.engine.strategy.CssStrategy;
import com.abc.sourceservice.engine.strategy.MaccmsStrategy;
import com.abc.sourceservice.engine.strategy.XpathStrategy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 两阶段规则引擎总控（对齐 Kazumi RuleEngine）。
 * 阶段一 search：关键词 → 详情页 URL 列表。
 * 阶段二 chapters：详情页 URL → 线路/分集列表。
 *
 * <p>另支持 maccms 模式：一次采集接口调用即拿到「条目 + 分集 + 明文直链」。
 */
public class RuleEngine {

    /** 阶段一：搜索。 */
    public List<SearchItem> search(RuleExecutionConfig cfg, String keyword) {
        if ("api".equals(cfg.getSearchMode())) {
            return ApiStrategy.search(cfg.getSearchApiCfg(), keyword, cfg.getBaseUrl(), cfg.getName());
        }
        if ("css".equals(cfg.getSearchMode())) {
            List<SearchItem> items = CssStrategy.search(cfg.getCssConfig(), keyword, cfg.getBaseUrl(), cfg.getName());
            if (items.isEmpty()) throw RuleException.noResult(cfg.getName());
            return items;
        }
        String html = fetchSearchPage(cfg, keyword);
        List<SearchItem> items = XpathStrategy.parseSearch(html,
                cfg.getSearchList(), cfg.getSearchName(), cfg.getSearchResult(),
                cfg.getBaseUrl(), cfg.getName());
        if (items.isEmpty()) throw RuleException.noResult(cfg.getName());
        return items;
    }

    /** maccms 模式：一次调用返回条目（含各线路直链）。 */
    public List<MaccmsStrategy.MaccmsItem> searchMaccms(RuleExecutionConfig cfg, String keyword) {
        List<MaccmsStrategy.MaccmsItem> items = MaccmsStrategy.fetch(cfg.getMacCmsApi(), keyword, cfg.getName());
        if (items.isEmpty()) throw RuleException.noResult(cfg.getName());
        return items;
    }

    /** 阶段二：分集。 */
    public List<Road> chapters(RuleExecutionConfig cfg, String detailUrl) {
        if ("api".equals(cfg.getChapterMode())) {
            return ApiStrategy.chapters(cfg.getChapterApiCfg(), detailUrl, cfg.getBaseUrl(), cfg.getName());
        }
        if ("css".equals(cfg.getSearchMode())) {
            List<Road> roads = CssStrategy.chapters(cfg.getCssConfig(), detailUrl, cfg.getBaseUrl(), cfg.getName());
            if (roads.isEmpty()) throw RuleException.chapterError(cfg.getName(), new RuntimeException("no roads"));
            return roads;
        }
        String html = HttpFetcher.get(detailUrl, cfg);
        List<Road> roads = XpathStrategy.parseChapters(html,
                cfg.getChapterRoads(), cfg.getChapterResult(), cfg.getBaseUrl(), cfg.getName());
        if (roads.isEmpty()) throw RuleException.chapterError(cfg.getName(), new RuntimeException("no roads"));
        return roads;
    }

    /** 解析某集最终直链（A 档静态站：从集页面直接正则抽 m3u8/mp4）。 */
    public String resolveDirectLink(RuleExecutionConfig cfg, String episodePageUrl) {
        String html = HttpFetcher.get(episodePageUrl, cfg);
        return XpathStrategy.extractDirectLink(html, cfg.getBaseUrl());
    }

    // ─── helpers ───────────────────────────────────────────────

    private String fetchSearchPage(RuleExecutionConfig cfg, String keyword) {
        String enc = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String path = cfg.getSearchUrl() == null ? "" : cfg.getSearchUrl().replace("@keyword", enc);
        String url = UrlUtil.normalize(cfg.getBaseUrl(), path);
        if (cfg.isUsePost()) {
            // POST：查询串作为表单体（对齐 Kazumi usePost 行为）
            int q = url.indexOf('?');
            if (q > 0) {
                String postUrl = url.substring(0, q);
                String formBody = url.substring(q + 1);
                return HttpFetcher.post(postUrl, cfg, formBody);
            }
            return HttpFetcher.post(url, cfg, "wd=" + enc);
        }
        return HttpFetcher.get(url, cfg);
    }
}
