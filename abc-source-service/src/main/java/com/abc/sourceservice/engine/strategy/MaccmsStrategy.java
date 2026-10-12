package com.abc.sourceservice.engine.strategy;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.abc.sourceservice.engine.EngineModels.Road;
import com.abc.sourceservice.engine.HttpFetcher;
import com.abc.sourceservice.engine.RuleException;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 苹果CMS（maccms）采集接口策略 —— 免浏览器的直链来源。
 *
 * <p>maccms 站普遍开放 JSON 采集接口，一次请求即可拿到「番剧 + 分集 + 明文 m3u8 直链」：
 * <pre>
 *   GET {apiRoot}/api.php/provide/vod/?ac=detail&amp;wd={keyword}
 *   → { list: [ { vod_id, vod_name, vod_play_from, vod_play_url } ] }
 *   vod_play_from: 线路名，多线路用 "$$$" 分隔，如 "lzm3u8"
 *   vod_play_url : 分集，多线路用 "$$$" 分隔、集用 "#" 分隔、集名与地址用 "$" 分隔
 *                  如 "第01集$https://x/index.m3u8#第02集$https://y/index.m3u8"
 * </pre>
 * 关键在于：{@code vod_play_url} 里 {@code $} 之后的地址就是 m3u8 直链本身，
 * 无需再解析播放页（这也是它比 XPath 抓网页更可靠的原因）。
 */
@Slf4j
public final class MaccmsStrategy {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private MaccmsStrategy() { }

    /** 一条 maccms 番剧条目：名称 + 若干线路（每线路含集名与直链）。 */
    public record MaccmsItem(String name, String vodId, List<Road> roads) { }

    /** 按关键词搜索采集接口，返回带直链的条目列表。 */
    public static List<MaccmsItem> fetch(String apiRoot, String keyword, String ruleName) {
        List<MaccmsItem> items = new ArrayList<>();
        if (apiRoot == null || apiRoot.isBlank()) return items;
        String root = apiRoot.endsWith("/") ? apiRoot.substring(0, apiRoot.length() - 1) : apiRoot;
        String url = root + "/api.php/provide/vod/?ac=detail&wd="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        try {
            String body = HttpFetcher.getWithHeaders(url, Map.of("User-Agent", UA), 15000);
            JSONObject root0 = JSONUtil.parseObj(body);
            JSONArray list = root0.getJSONArray("list");
            if (list == null) return items;
            for (Object o : list) {
                JSONObject v = (JSONObject) o;
                String name = v.getStr("vod_name");
                String vodId = v.getStr("vod_id");
                List<Road> roads = parseRoads(v.getStr("vod_play_from", ""), v.getStr("vod_play_url", ""));
                if (name != null && !roads.isEmpty()) {
                    items.add(new MaccmsItem(name, vodId, roads));
                }
            }
        } catch (Exception e) {
            throw RuleException.searchError(ruleName, e);
        }
        return items;
    }

    /** 解析 vod_play_from / vod_play_url → 线路列表。 */
    static List<Road> parseRoads(String playFrom, String playUrl) {
        List<Road> roads = new ArrayList<>();
        if (playUrl == null || playUrl.isBlank()) return roads;

        String[] fromGroups = playFrom == null ? new String[0]
                : playFrom.split(Pattern.quote("$$$"));
        String[] urlGroups = playUrl.split(Pattern.quote("$$$"));

        for (int g = 0; g < urlGroups.length; g++) {
            String roadName = g < fromGroups.length && !fromGroups[g].isBlank()
                    ? fromGroups[g].trim() : ("线路" + (g + 1));
            List<String> urls = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String ep : urlGroups[g].split("#")) {
                if (ep.isBlank()) continue;
                String[] parts = ep.split(Pattern.quote("$"), 2);
                String epName;
                String epUrl;
                if (parts.length == 2) {
                    epName = parts[0].trim();
                    epUrl = parts[1].trim();
                } else {
                    // 无 "集名$" 前缀，整段即地址
                    epName = "";
                    epUrl = parts[0].trim();
                }
                if (epUrl.isBlank()) continue;
                urls.add(epUrl);
                names.add(epName.isEmpty() ? ("第" + urls.size() + "集") : epName);
            }
            if (!urls.isEmpty()) roads.add(new Road(roadName, urls, names));
        }
        return roads;
    }

    /** 该地址是否本身就是可直接播放的媒体直链。 */
    public static boolean isDirectMedia(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv");
    }
}
