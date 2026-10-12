package com.abc.sourceservice.engine.strategy;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.abc.sourceservice.engine.EngineModels.Road;
import com.abc.sourceservice.engine.EngineModels.SearchItem;
import com.abc.sourceservice.engine.HttpFetcher;
import com.abc.sourceservice.engine.RuleException;
import com.abc.sourceservice.engine.UrlUtil;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 解析策略（对齐 Kazumi api_rule_strategy）。
 * 配置化 HTTP 请求 + JSONPath 提取，处理「播放页再请求一个播放接口返回 JSON」的主流站点。
 *
 * <p>searchApiCfg 结构：
 * <pre>{ "request":{"method":"GET","url":"/api/search?wd=@{keyword}","headers":{}},
 *         "listPath":"$.data[*]","namePath":"$.name","sourcePath":"$.url" }</pre>
 * <p>chapterApiCfg 结构：
 * <pre>{ "request":{"method":"GET","url":"@{source}","headers":{}},
 *         "roadsPath":"$.data.roads[*]","episodesPath":"$.episodes[*]",
 *         "episodeNamePath":"$.name","episodeUrlPath":"$.url" }</pre>
 */
@Slf4j
public class ApiStrategy {

    /** 阶段一：搜索。 */
    public static List<SearchItem> search(String cfgJson, String keyword,
                                          String baseUrl, String ruleName) {
        List<SearchItem> items = new ArrayList<>();
        try {
            JSONObject cfg = JSONUtil.parseObj(cfgJson);
            JSONObject req = cfg.getJSONObject("request");
            String url = render(UrlUtil.normalize(baseUrl, req.getStr("url", "")),
                    Map.of("keyword", keyword));
            Map<String, String> headers = toHeaderMap(req.getJSONObject("headers"));
            String body = HttpFetcher.getWithHeaders(url, headers, 15000);

            String listPath = cfg.getStr("listPath", "$.data[*]");
            String namePath = cfg.getStr("namePath", "$.name");
            String sourcePath = cfg.getStr("sourcePath", "$.url");
            List<Object> list = readList(body, listPath);
            for (Object o : list) {
                String name = readString(o, namePath);
                String src = readString(o, sourcePath);
                if (name == null || src == null) continue;
                items.add(new SearchItem(name.trim(), UrlUtil.normalize(baseUrl, src)));
            }
        } catch (RuleException e) {
            throw e;
        } catch (Exception e) {
            throw RuleException.searchError(ruleName, e);
        }
        return items;
    }

    /** 阶段二：分集。 */
    public static List<Road> chapters(String cfgJson, String sourceUrl,
                                      String baseUrl, String ruleName) {
        List<Road> roads = new ArrayList<>();
        try {
            JSONObject cfg = JSONUtil.parseObj(cfgJson);
            JSONObject req = cfg.getJSONObject("request");
            String url = render(UrlUtil.normalize(baseUrl, req.getStr("url", "")),
                    Map.of("source", sourceUrl));
            Map<String, String> headers = toHeaderMap(req.getJSONObject("headers"));
            String body = HttpFetcher.getWithHeaders(url, headers, 15000);

            String roadsPath = cfg.getStr("roadsPath", "$.data.roads[*]");
            String episodesPath = cfg.getStr("episodesPath", "$.episodes[*]");
            String namePath = cfg.getStr("episodeNamePath", "$.name");
            String urlPath = cfg.getStr("episodeUrlPath", "$.url");

            List<Object> roadList = readList(body, roadsPath);
            int idx = 1;
            for (Object roadObj : roadList) {
                List<String> urls = new ArrayList<>();
                List<String> names = new ArrayList<>();
                List<Object> eps = readListFrom(roadObj, episodesPath);
                for (Object ep : eps) {
                    String u = readString(ep, urlPath);
                    String n = readString(ep, namePath);
                    if (u == null) continue;
                    urls.add(UrlUtil.normalize(baseUrl, u));
                    names.add(n != null ? n : ("第" + urls.size() + "集"));
                }
                if (!urls.isEmpty()) roads.add(new Road("播放线路" + idx++, urls, names));
            }
        } catch (RuleException e) {
            throw e;
        } catch (Exception e) {
            throw RuleException.chapterError(ruleName, e);
        }
        return roads;
    }

    // ─── helpers ───────────────────────────────────────────────

    /** 把 @{var} / @var 模板变量替换（URL 编码）。 */
    static String render(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String enc = URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8);
            out = out.replace("@{" + e.getKey() + "}", enc).replace("@" + e.getKey(), enc);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readList(String json, String path) {
        try {
            Object r = JsonPath.read(json, path);
            if (r instanceof List<?> l) return (List<Object>) l;
            return new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readListFrom(Object node, String path) {
        try {
            // 在子对象上执行 JSONPath
            String sub = JSONUtil.toJsonStr(node);
            Object r = JsonPath.read(sub, path);
            if (r instanceof List<?> l) return (List<Object>) l;
            if (r != null) return List.of(r);
            return new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String readString(Object node, String path) {
        try {
            String sub = JSONUtil.toJsonStr(node);
            Object r = JsonPath.read(sub, path);
            return r == null ? null : String.valueOf(r);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> toHeaderMap(JSONObject obj) {
        Map<String, String> m = new LinkedHashMap<>();
        if (obj != null) {
            for (String k : obj.keySet()) m.put(k, obj.getStr(k));
        }
        return m;
    }
}
