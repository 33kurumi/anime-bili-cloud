package com.abc.sourceservice.engine.strategy;

import com.abc.sourceservice.engine.EngineModels.Road;
import com.abc.sourceservice.engine.EngineModels.SearchItem;
import com.abc.sourceservice.engine.HttpFetcher;
import com.abc.sourceservice.engine.RuleException;
import com.abc.sourceservice.engine.UrlUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * CSS 选择器策略 —— 兼容 Animeko 的 {@code web-selector} 数据源格式。
 *
 * <p>Animeko 的源用 CSS 选择器（jsoup）+ {keyword} 占位符，配置形如：
 * <pre>
 * searchConfig: {
 *   searchUrl: "https://site/search.html?wd={keyword}",
 *   subjectFormatId: "indexed" | "a",
 *   selectorSubjectFormatIndexed: { selectNames, selectLinks },
 *   selectorSubjectFormatA: { selectLists },
 *   channelFormatId: "index-grouped" | "no-channel",
 *   selectorChannelFormatFlattened: { selectChannelNames, matchChannelName,
 *                                     selectEpisodeLists, selectEpisodesFromList,
 *                                     selectEpisodeLinksFromList, matchEpisodeSortFromName },
 *   selectorChannelFormatNoChannel: { selectEpisodes, selectEpisodeLinks, matchEpisodeSortFromName }
 * }
 * </pre>
 *
 * <p>返回的仍是「详情页 URL」（后续分集需用 selectEpisodeLists 再抓，或用浏览器解析直链）。
 */
@Slf4j
public final class CssStrategy {

    private CssStrategy() { }

    /** 阶段一：搜索。配置来自 Animeko searchConfig（JSON）。 */
    public static List<SearchItem> search(String cfgJson, String keyword,
                                          String baseUrl, String ruleName) {
        List<SearchItem> items = new ArrayList<>();
        try {
            JSONObject cfg = JSONUtil.parseObj(cfgJson);
            String searchUrl = cfg.getStr("searchUrl", "");
            String url = searchUrl.replace("{keyword}", keyword);
            String html = HttpFetcher.getWithHeaders(url, java.util.Map.of(
                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0"), 15000);
            Document doc = Jsoup.parse(html, url);

            String subjectFormat = cfg.getStr("subjectFormatId", "indexed");
            if ("a".equalsIgnoreCase(subjectFormat) && cfg.getJSONObject("selectorSubjectFormatA") != null) {
                JSONObject a = cfg.getJSONObject("selectorSubjectFormatA");
                items = parseSubjectA(doc, a, baseUrl);
            } else {
                JSONObject idx = cfg.getJSONObject("selectorSubjectFormatIndexed");
                if (idx != null) items = parseSubjectIndexed(doc, idx, baseUrl);
            }
        } catch (Exception e) {
            throw RuleException.searchError(ruleName, e);
        }
        return items;
    }

    private static List<SearchItem> parseSubjectA(Document doc, JSONObject cfg, String baseUrl) {
        List<SearchItem> out = new ArrayList<>();
        String sel = cfg.getStr("selectLists", "");
        if (sel.isBlank()) return out;
        for (Element el : doc.select(sel)) {
            // selectLists 指向 <a>：名字取 title 或文本，链接取 href
            String name = firstNonBlank(el.attr("title"), el.text());
            String href = el.attr("href");
            if (name == null || href == null || href.isBlank()) continue;
            out.add(new SearchItem(name.trim(), UrlUtil.normalize(baseUrl, href)));
        }
        return out;
    }

    private static List<SearchItem> parseSubjectIndexed(Document doc, JSONObject cfg, String baseUrl) {
        List<SearchItem> out = new ArrayList<>();
        String nameSel = cfg.getStr("selectNames", "");
        String linkSel = cfg.getStr("selectLinks", "");
        if (nameSel.isBlank() || linkSel.isBlank()) return out;
        Elements names = doc.select(nameSel);
        Elements links = doc.select(linkSel);
        int n = Math.min(names.size(), links.size());
        for (int i = 0; i < n; i++) {
            String name = names.get(i).text();
            String href = links.get(i).attr("href");
            if (name.isBlank() || href.isBlank()) continue;
            out.add(new SearchItem(name.trim(), UrlUtil.normalize(baseUrl, href)));
        }
        return out;
    }

    /** 阶段二：分集。detailUrl 是详情页，用 searchConfig 里的 channel 配置解析。 */
    public static List<Road> chapters(String cfgJson, String detailUrl,
                                      String baseUrl, String ruleName) {
        List<Road> roads = new ArrayList<>();
        try {
            JSONObject cfg = JSONUtil.parseObj(cfgJson);
            String html = HttpFetcher.getWithHeaders(detailUrl, java.util.Map.of(
                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0"), 15000);
            Document doc = Jsoup.parse(html, detailUrl);

            String channelFormat = cfg.getStr("channelFormatId", "index-grouped");
            if ("no-channel".equalsIgnoreCase(channelFormat) && cfg.getJSONObject("selectorChannelFormatNoChannel") != null) {
                JSONObject nc = cfg.getJSONObject("selectorChannelFormatNoChannel");
                Road road = parseNoChannel(doc, nc, baseUrl);
                if (road != null) roads.add(road);
            } else if (cfg.getJSONObject("selectorChannelFormatFlattened") != null) {
                roads = parseIndexGrouped(doc, cfg.getJSONObject("selectorChannelFormatFlattened"), baseUrl);
            }
        } catch (Exception e) {
            throw RuleException.chapterError(ruleName, e);
        }
        return roads;
    }

    private static List<Road> parseIndexGrouped(Document doc, JSONObject cfg, String baseUrl) {
        List<Road> roads = new ArrayList<>();
        String channelSel = cfg.getStr("selectChannelNames", "");
        String listSel = cfg.getStr("selectEpisodeLists", "");
        String epSel = cfg.getStr("selectEpisodesFromList", "");
        String epLinkSel = cfg.getStr("selectEpisodeLinksFromList", "");

        Elements lists = doc.select(listSel);
        if (lists.isEmpty()) return roads;

        Elements channelNames = channelSel.isBlank() ? new Elements() : doc.select(channelSel);
        int idx = 1;
        for (Element list : lists) {
            Elements epEls = epSel.isBlank() ? list.select("a") : list.select(epSel);
            List<String> urls = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Element ep : epEls) {
                Element linkEl = ep;
                if (!epLinkSel.isBlank()) {
                    Element l = ep.selectFirst(epLinkSel);
                    if (l != null) linkEl = l;
                }
                String href = linkEl.attr("href");
                if (href.isBlank()) href = ep.attr("href");
                if (href.isBlank()) continue;
                String name = firstNonBlank(ep.attr("title"), ep.text());
                urls.add(UrlUtil.normalize(baseUrl, href));
                names.add(name == null || name.isBlank() ? ("第" + urls.size() + "集") : name.trim());
            }
            if (urls.isEmpty()) continue;
            String roadName = (idx - 1) < channelNames.size() && !channelNames.get(idx - 1).text().isBlank()
                    ? channelNames.get(idx - 1).text().trim() : ("线路" + idx);
            roads.add(new Road(roadName, urls, names));
            idx++;
        }
        return roads;
    }

    private static Road parseNoChannel(Document doc, JSONObject cfg, String baseUrl) {
        String epSel = cfg.getStr("selectEpisodes", "");
        String epLinkSel = cfg.getStr("selectEpisodeLinks", "");
        if (epSel.isBlank()) return null;
        Elements epEls = doc.select(epSel);
        List<String> urls = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Element ep : epEls) {
            Element linkEl = ep;
            if (!epLinkSel.isBlank()) {
                Element l = ep.selectFirst(epLinkSel);
                if (l != null) linkEl = l;
            }
            String href = linkEl.attr("href");
            if (href.isBlank()) href = ep.attr("href");
            if (href.isBlank()) continue;
            String name = firstNonBlank(ep.attr("title"), ep.text());
            urls.add(UrlUtil.normalize(baseUrl, href));
            names.add(name == null || name.isBlank() ? ("第" + urls.size() + "集") : name.trim());
        }
        if (urls.isEmpty()) return null;
        return new Road("播放线路1", urls, names);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
