package com.abc.sourceservice.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.abc.sourceservice.entity.SourceRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部规则导入：把 Animeko（web-selector / css1.json）与 Kazumi（XPath 插件）格式
 * 统一转换成本项目的 {@link SourceRule}。
 */
@Slf4j
@Service
public class RuleImporter {

    /** 解析 Animeko css1.json（exportedMediaSourceDataList.mediaSources）。 */
    public List<SourceRule> fromAnimeko(String css1Json) {
        List<SourceRule> out = new ArrayList<>();
        JSONObject root = JSONUtil.parseObj(css1Json);
        JSONObject outer = root.getJSONObject("exportedMediaSourceDataList");
        if (outer == null) return out;
        JSONArray list = outer.getJSONArray("mediaSources");
        if (list == null) return out;

        for (Object o : list) {
            JSONObject src = (JSONObject) o;
            String factoryId = src.getStr("factoryId");
            if (!"web-selector".equals(factoryId)) continue; // 跳过 rss/bt 等
            JSONObject args = src.getJSONObject("arguments");
            if (args == null) continue;
            JSONObject sc = args.getJSONObject("searchConfig");
            if (sc == null) continue;

            String name = args.getStr("name", "animeko");
            String searchUrl = sc.getStr("searchUrl", "");
            String host = hostOf(searchUrl);

            SourceRule r = SourceRule.builder()
                    .name("[Animeko] " + name)
                    .version("1.0")
                    .baseUrl(host)
                    .searchUrl(searchUrl)
                    .usePost(0)
                    .searchMode("css")
                    .chapterMode("css")
                    .cssConfig(sc.toString())   // 整个 searchConfig 交给 CssStrategy
                    .browserResolve(1)          // Animeko 源返回播放页，需浏览器取直链
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                    .referer(host)
                    .weight(5)
                    .enabled(0)                 // 默认关闭，自测通过后再启用
                    .healthStatus("UNKNOWN")
                    .successRate(0.0)
                    .build();
            out.add(r);
        }
        return out;
    }

    /** 解析 Kazumi 插件 JSON（单个或数组）。 */
    public List<SourceRule> fromKazumi(String kazumiJson) {
        List<SourceRule> out = new ArrayList<>();
        Object parsed;
        try {
            parsed = JSONUtil.parse(kazumiJson.trim());
        } catch (Exception e) {
            return out;
        }
        List<JSONObject> objs = new ArrayList<>();
        if (parsed instanceof JSONArray arr) {
            for (Object o : arr) objs.add((JSONObject) o);
        } else if (parsed instanceof JSONObject obj) {
            objs.add(obj);
        }
        for (JSONObject p : objs) {
            String name = p.getStr("name", "kazumi");
            String baseURL = p.getStr("baseURL", "");
            SourceRule r = SourceRule.builder()
                    .name("[Kazumi] " + name)
                    .version(p.getStr("version", "1.0"))
                    .baseUrl(baseURL)
                    .searchUrl(p.getStr("searchURL", ""))
                    .usePost(Boolean.TRUE.equals(p.getBool("usePost")) ? 1 : 0)
                    .searchMode("xpath")
                    .chapterMode("xpath")
                    .searchList(p.getStr("searchList"))
                    .searchName(p.getStr("searchName"))
                    .searchResult(p.getStr("searchResult"))
                    .chapterRoads(p.getStr("chapterRoads"))
                    .chapterResult(p.getStr("chapterResult"))
                    // Kazumi 靠 WebView 取直链，这里用我们的浏览器解析
                    .browserResolve(1)
                    .userAgent(p.getStr("userAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0"))
                    .referer(p.getStr("referer", baseURL))
                    .weight(5)
                    .enabled(0)
                    .healthStatus("UNKNOWN")
                    .successRate(0.0)
                    .build();
            out.add(r);
        }
        return out;
    }

    private static String hostOf(String url) {
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme() == null ? "https" : u.getScheme();
            if (u.getHost() != null) return scheme + "://" + u.getHost();
        } catch (Exception ignored) { }
        return url;
    }
}
