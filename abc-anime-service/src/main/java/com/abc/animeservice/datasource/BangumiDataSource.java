package com.abc.animeservice.datasource;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bangumi.tv (bgm.tv) metadata data source.
 *
 * <p>上游采用多镜像 + 故障转移设计：官方 {@code api.bgm.tv} / {@code next.bgm.tv} 在部分网络下不可达，
 * 因此默认走可用的公开镜像，并支持通过配置指向自建反代网关（见 docker-compose 的 abc-bgm-gateway）。
 *
 * <p>配置项 {@code abc.bangumi.mirrors}（逗号分隔，按优先级排序），例如：
 * <pre>
 *   abc.bangumi.mirrors=https://bgmapi.anibt.net,https://api.bgmapi.com
 *   # 或指向自建反代：http://abc-bgm-gateway
 * </pre>
 *
 * <p>API 契约参考官方 https://bangumi.github.io/api/ 的 v0：
 * <ul>
 *   <li>POST /v0/search/subjects  —— 搜索（body: {keyword, sort, filter, limit, offset}）</li>
 *   <li>GET  /v0/subjects/{id}    —— 条目详情</li>
 *   <li>GET  /v0/episodes?subject_id=&type=0&limit= —— 剧集</li>
 *   <li>GET  /calendar            —— 每日放送</li>
 * </ul>
 * 注意：limit 官方约束为 1~50；相对路径拼接镜像根地址。
 */
@Slf4j
@Component
@Primary
public class BangumiDataSource implements AnimeDataSource {

    /** 多镜像根地址（按优先级），可由 application.yml 覆盖；默认 = 主镜像 + 备份镜像 */
    @Value("${abc.bangumi.mirrors:https://bgmapi.anibt.net,https://api.bgmapi.com}")
    private String mirrorsConfig;

    private static final String USER_AGENT = "AnimeBilibiliCloud/1.0 (https://github.com/abc; contact@abc.com)";
    private static final int MAX_LIMIT = 50;          // 官方约束
    private static final long DEFAULT_RETRY_AFTER_MS = 1000L;

    private volatile List<String> mirrors = List.of();
    /** 最近一次成功的镜像，命中后优先使用（粘住，减少探测开销） */
    private volatile String activeMirror = null;

    private final Map<String, Integer> failureCount = new ConcurrentHashMap<>();

    /** 官方地址（魔法开启时走它 + 本地代理） */
    @Value("${abc.bangumi.official-url:https://next.bgm.tv}")
    private String officialUrl;

    /** 本地 VPN/代理（如 Clash 127.0.0.1:7890）；魔法开关用它访问官方 */
    @Value("${abc.bangumi.proxy.host:}")
    private String proxyHost;

    @Value("${abc.bangumi.proxy.port:0}")
    private int proxyPort;

    /** 魔法开关：true=走本地代理访问官方 api.bgm.tv；false=走镜像。运行时可切换 */
    private volatile boolean proxyEnabled = false;

    public BangumiDataSource() {
        log.info("===== BangumiDataSource 实例化成功（多镜像 failover + 魔法代理模式） =====");
    }

    /** 魔法开关状态。 */
    public boolean isProxyEnabled() { return proxyEnabled; }
    public boolean proxyConfigured() {
        return proxyHost != null && !proxyHost.isBlank() && proxyPort > 0;
    }
    public void setProxyEnabled(boolean on) {
        this.proxyEnabled = on;
        log.info("魔法开关：{}（代理 {}:{}）", on ? "开" : "关", proxyHost, proxyPort);
    }
    public List<String> getMirrorList() { return new ArrayList<>(getMirrors()); }
    public String getProxyHost() { return proxyHost; }
    public int getProxyPort() { return proxyPort; }
    public String getOfficialUrl() { return officialUrl; }

    /** 测一个 base 地址是否可用，返回耗时 ms；失败 -1。 */
    public long ping(String base, boolean useProxy, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.get(base + "/v0/subjects?limit=1")
                    .header("User-Agent", USER_AGENT).timeout(timeoutMs);
            if (useProxy && proxyConfigured()) {
                req.setProxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new java.net.InetSocketAddress(proxyHost, proxyPort)));
            }
            HttpResponse resp = req.execute();
            return resp.getStatus() == 200 ? System.currentTimeMillis() - start : -1;
        } catch (Exception e) {
            return -1;
        }
    }


    /** 懒解析镜像列表，兼容 @Value 注入时机 */
    private List<String> getMirrors() {
        if (mirrors.isEmpty()) {
            List<String> parsed = new ArrayList<>();
            for (String s : mirrorsConfig.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) parsed.add(t.endsWith("/") ? t.substring(0, t.length() - 1) : t);
            }
            mirrors = parsed;
        }
        // 优先把最近成功的镜像排到最前（粘住）
        String active = activeMirror;
        if (active != null && mirrors.contains(active)) {
            List<String> ordered = new ArrayList<>(mirrors.size());
            ordered.add(active);
            for (String m : mirrors) if (!m.equals(active)) ordered.add(m);
            return ordered;
        }
        return mirrors;
    }

    @Override
    public String getName() {
        return "Bangumi (bgm.tv)";
    }

    @Override
    public boolean isAvailable() {
        HttpResponse resp = request("/v0/subjects?limit=1", false, null, 5000);
        return resp != null && resp.getStatus() == 200;
    }

    // ─── Data source API ───────────────────────────────────────

    @Override
    public List<Map<String, Object>> search(String keyword, int page, int size) {
        List<Map<String, Object>> results = new ArrayList<>();
        JSONObject body = buildSearchBody(keyword, "match", page, size);
        HttpResponse resp = request("/v0/search/subjects", true, body.toString(), 10000);
        if (resp == null || resp.getStatus() != 200) {
            log.error("Bangumi search failed on all mirrors: keyword={}", keyword);
            return results;
        }
        JSONObject data = JSONUtil.parseObj(resp.body());
        JSONArray list = data.getJSONArray("data");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                results.add(subjectToMap(list.getJSONObject(i)));
            }
        }
        return results;
    }

    @Override
    public Map<String, Object> getDetail(String externalId) {
        Map<String, Object> result = new LinkedHashMap<>();
        HttpResponse resp = request("/v0/subjects/" + externalId, false, null, 10000);
        if (resp == null || resp.getStatus() != 200) {
            log.error("Bangumi subject {} not found on any mirror", externalId);
            return result;
        }

        JSONObject subject = JSONUtil.parseObj(resp.body());
        Map<String, Object> anime = subjectToDetailMap(subject);
        anime.put("bangumiId", externalId);

        // Extract studio from infobox
        JSONArray infobox = subject.getJSONArray("infobox");
        if (infobox != null) {
            for (int i = 0; i < infobox.size(); i++) {
                JSONObject kv = infobox.getJSONObject(i);
                if (kv != null && "动画制作".equals(kv.getStr("key"))) {
                    anime.put("studio", kv.getStr("value", ""));
                    break;
                }
            }
        }
        result.put("anime", anime);

        // Fetch episodes
        List<Map<String, Object>> episodes = new ArrayList<>();
        try {
            Thread.sleep(350); // rate limit
            HttpResponse epResp = request(
                    "/v0/episodes?subject_id=" + externalId + "&type=0&limit=200", false, null, 10000);
            if (epResp != null && epResp.getStatus() == 200) {
                JSONObject epData = JSONUtil.parseObj(epResp.body());
                JSONArray epList = epData.getJSONArray("data");
                if (epList != null) {
                    for (int i = 0; i < epList.size(); i++) {
                        JSONObject ep = epList.getJSONObject(i);
                        Map<String, Object> epMap = new LinkedHashMap<>();
                        epMap.put("episodeNum", ep.getInt("sort", 0));
                        epMap.put("title", ep.getStr("name_cn", ep.getStr("name", "")));
                        epMap.put("duration", extractDuration(ep.getStr("duration", "")));
                        episodes.add(epMap);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch episodes for subject {}", externalId, e);
        }
        result.put("episodes", episodes);
        return result;
    }

    @Override
    public List<Map<String, Object>> getHot(int page, int size) {
        List<Map<String, Object>> results = new ArrayList<>();
        JSONObject body = buildSearchBody("", "heat", page, size);
        HttpResponse resp = request("/v0/search/subjects", true, body.toString(), 10000);
        if (resp == null || resp.getStatus() != 200) {
            log.error("Bangumi getHot failed on all mirrors");
            return results;
        }
        JSONObject data = JSONUtil.parseObj(resp.body());
        JSONArray list = data.getJSONArray("data");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                results.add(subjectToMap(list.getJSONObject(i)));
            }
        }
        log.info("getHot 返回番剧数量：{}（镜像 {}）", results.size(), activeMirror);
        return results;
    }

    @Override
    public List<Map<String, Object>> getCalendar() {
        List<Map<String, Object>> schedule = new ArrayList<>();
        String[] dayNames = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        for (String day : dayNames) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("day", day);
            d.put("animeList", new ArrayList<>());
            schedule.add(d);
        }

        HttpResponse resp = request("/calendar", false, null, 10000);
        if (resp == null || resp.getStatus() != 200) {
            log.error("Bangumi calendar failed on all mirrors");
            return schedule;
        }
        JSONArray data = JSONUtil.parseArray(resp.body());
        if (data == null) return schedule;

        for (int i = 0; i < data.size(); i++) {
            JSONObject dayObj = data.getJSONObject(i);
            JSONObject weekdayObj = dayObj.getJSONObject("weekday");
            int weekday = weekdayObj == null ? 0 : weekdayObj.getInt("id", 0);
            int idx = (weekday + 6) % 7;
            if (idx < 0 || idx >= 7) continue;

            JSONArray items = dayObj.getJSONArray("items");
            List<Map<String, Object>> animeList = new ArrayList<>();
            if (items != null) {
                for (int j = 0; j < items.size(); j++) {
                    JSONObject a = items.getJSONObject(j);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    String bgmRawId = String.valueOf(a.getInt("id"));
                    entry.put("id", "bgm:" + bgmRawId);

                    String cnTitle = a.getStr("name_cn");
                    String jpTitle = a.getStr("name");
                    String finalTitle;
                    if (cnTitle != null && !cnTitle.isBlank()) finalTitle = cnTitle;
                    else if (jpTitle != null && !jpTitle.isBlank()) finalTitle = jpTitle;
                    else finalTitle = "未知番剧";
                    entry.put("title", finalTitle);

                    JSONObject im = a.getJSONObject("images");
                    String rawImg = im != null ? im.getStr("large", "") : "";
                    entry.put("coverUrl", rawImg.replace("lain.bgm.tv", "bgmimg.anibt.net"));

                    entry.put("episodesCount", a.getInt("total_episodes", 0));
                    entry.put("ratingAvg", a.getDouble("score", 0.0));
                    entry.put("year", extractYear(a.getStr("air_date")));
                    entry.put("type", mapType(a.getStr("type")));
                    animeList.add(entry);
                }
            }
            schedule.get(idx).put("animeList", animeList);
        }
        return schedule;
    }

    // ─── Mirror failover core ──────────────────────────────────

    /**
     * 依次尝试各镜像执行请求，命中即返回（并标记 activeMirror）。
     * 单镜像遇到 429 时读 Retry-After 退避重试一次；其余非 200 / 异常则切换下一个镜像。
     *
     * @return 成功响应的 HttpResponse；全部失败返回 null
     */
    private HttpResponse request(String relativePath, boolean post, String jsonBody, int timeoutMs) {
        // 魔法开关：走本地代理直连官方 api.bgm.tv
        if (proxyEnabled && proxyConfigured()) {
            HttpResponse r = requestVia(officialUrl, relativePath, post, jsonBody, timeoutMs, true);
            if (r != null) return r;
            log.warn("魔法代理访问官方失败，回退镜像");
        }
        for (String base : getMirrors()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    HttpRequest req = post
                            ? HttpRequest.post(base + relativePath)
                            : HttpRequest.get(base + relativePath);
                    req.header("User-Agent", USER_AGENT).timeout(timeoutMs);
                    if (post) {
                        req.header("Content-Type", "application/json");
                        if (jsonBody != null) req.body(jsonBody);
                    }
                    HttpResponse resp = req.execute();
                    int st = resp.getStatus();
                    if (st == 200) {
                        activeMirror = base;
                        failureCount.remove(base);
                        return resp;
                    }
                    if (st == 429 && attempt == 0) {
                        long wait = parseRetryAfter(resp);
                        log.warn("Bangumi 镜像 {} 触发限流(429)，{}ms 后重试", base, wait);
                        Thread.sleep(wait);
                        continue;
                    }
                    log.warn("Bangumi 镜像 {} 返回 HTTP {}，切换下一镜像: {}", base, st, relativePath);
                    failureCount.merge(base, 1, Integer::sum);
                    break; // 换镜像
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    log.warn("Bangumi 镜像 {} 请求异常，切换下一镜像: {}", base, e.getMessage());
                    failureCount.merge(base, 1, Integer::sum);
                    break; // 换镜像
                }
            }
        }
        log.error("所有 Bangumi 镜像均失败: {}", relativePath);
        return null;
    }

    /** 用（可选）代理访问指定 base。 */
    private HttpResponse requestVia(String base, String path, boolean post, String jsonBody,
                                    int timeoutMs, boolean useProxy) {
        try {
            HttpRequest req = post ? HttpRequest.post(base + path) : HttpRequest.get(base + path);
            req.header("User-Agent", USER_AGENT).timeout(timeoutMs);
            if (useProxy && proxyConfigured()) {
                req.setProxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new java.net.InetSocketAddress(proxyHost, proxyPort)));
            }
            if (post) {
                req.header("Content-Type", "application/json");
                if (jsonBody != null) req.body(jsonBody);
            }
            HttpResponse resp = req.execute();
            return resp.getStatus() == 200 ? resp : null;
        } catch (Exception e) {
            log.warn("代理访问 {} 失败: {}", base, e.getMessage());
            return null;
        }
    }

    private long parseRetryAfter(HttpResponse resp) {
        try {
            String ra = resp.header("Retry-After");
            if (ra != null && !ra.isBlank()) {
                return Math.min(Long.parseLong(ra.trim()) * 1000L, 10000L);
            }
        } catch (Exception ignored) { }
        return DEFAULT_RETRY_AFTER_MS;
    }

    // ─── Builder / mapping helpers ─────────────────────────────

    private JSONObject buildSearchBody(String keyword, String sort, int page, int size) {
        // 仅允许 heat / rank / match，非法值归 match（关键词相关度）
        if (!"heat".equals(sort) && !"rank".equals(sort) && !"match".equals(sort)) {
            sort = "match";
        }
        int limit = Math.max(1, Math.min(MAX_LIMIT, size)); // 官方约束 1~50
        int offset = Math.max(0, (page - 1) * limit);

        JSONArray typeFilter = new JSONArray();
        typeFilter.add(2); // 2 = anime

        JSONObject filter = new JSONObject();
        filter.set("type", typeFilter);
        filter.set("nsfw", false); // 搜索默认过滤 NSFW（保留 #1 决策：不动）

        JSONObject body = new JSONObject();
        body.set("sort", sort);
        body.set("filter", filter);
        body.set("offset", offset);
        body.set("limit", limit);
        if (keyword != null && !keyword.isEmpty()) {
            body.set("keyword", keyword);
        }
        return body;
    }

    private Map<String, Object> subjectToMap(JSONObject obj) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", "bgm:" + obj.getInt("id"));

        String cn = obj.getStr("name_cn");
        String jp = obj.getStr("name");
        String realTitle = "未知番剧";
        if (cn != null && !cn.isBlank()) realTitle = cn;
        else if (jp != null && !jp.isBlank()) realTitle = jp;
        map.put("title", realTitle);

        JSONObject images = obj.getJSONObject("images");
        String rawImg = images != null ? images.getStr("large", "") : "";
        map.put("coverUrl", rawImg.replace("lain.bgm.tv", "bgmimg.anibt.net"));
        map.put("type", mapType(obj.getStr("type")));
        map.put("year", extractYear(obj.getStr("air_date")));
        map.put("summary", obj.getStr("summary", ""));

        JSONObject ratingObj = obj.getJSONObject("rating");
        double score = ratingObj != null ? ratingObj.getDouble("score", 0.0) : 0.0;
        map.put("score", score);
        map.put("ratingAvg", score);
        map.put("episodesCount", obj.getInt("total_episodes", 0));
        return map;
    }

    private Map<String, Object> subjectToDetailMap(JSONObject subject) {
        Map<String, Object> anime = new LinkedHashMap<>();
        anime.put("title", subject.getStr("name_cn", subject.getStr("name")));
        JSONObject imgObj = subject.getJSONObject("images");
        String rawImg = imgObj != null ? imgObj.getStr("large", "") : "";
        anime.put("coverUrl", rawImg.replace("lain.bgm.tv", "bgmimg.anibt.net"));
        anime.put("description", subject.getStr("summary", ""));
        anime.put("type", mapType(subject.getStr("type")));
        anime.put("year", extractYear(subject.getStr("air_date")));
        anime.put("season", mapSeason(subject.getStr("air_date")));
        anime.put("status", Boolean.TRUE.equals(subject.getBool("airing")) ? "连载中" : "已完结");
        anime.put("episodesCount", subject.getInt("total_episodes", 0));

        JSONObject ratingObj = subject.getJSONObject("rating");
        double score = ratingObj != null ? ratingObj.getDouble("score", 0.0) : 0.0;
        anime.put("ratingAvg", score);
        anime.put("studio", "");
        return anime;
    }

    private String mapType(String bgmType) {
        if (bgmType == null) return "TV";
        return switch (bgmType) {
            case "TV" -> "TV";
            case "Movie" -> "MOVIE";
            case "OVA" -> "OVA";
            case "Web" -> "ONA";
            default -> "TV";
        };
    }

    private String mapSeason(String airDate) {
        if (airDate == null || airDate.length() < 7) return null;
        String month = airDate.substring(5, 7);
        return switch (month) {
            case "01", "02", "03" -> "1月";
            case "04", "05", "06" -> "4月";
            case "07", "08", "09" -> "7月";
            case "10", "11", "12" -> "10月";
            default -> null;
        };
    }

    private Integer extractYear(String airDate) {
        if (airDate == null || airDate.length() < 4) return null;
        try {
            return Integer.parseInt(airDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long extractDuration(String duration) {
        if (duration == null || duration.isEmpty()) return 0L;
        try {
            String s = duration.trim();
            if (s.contains(":")) {
                String[] parts = s.split(":");
                return Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60;
            }
            if (s.contains("h") || s.contains("m")) {
                long total = 0;
                int hIdx = s.indexOf('h');
                if (hIdx > 0) total += Long.parseLong(s.substring(0, hIdx).trim()) * 3600;
                int mIdx = s.indexOf('m');
                if (mIdx > 0) {
                    String min = hIdx >= 0 ? s.substring(hIdx + 1, mIdx).trim() : s.substring(0, mIdx).trim();
                    if (!min.isEmpty()) total += Long.parseLong(min) * 60;
                }
                return total;
            }
            return 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}
