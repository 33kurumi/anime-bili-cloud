package com.abc.danmakuservice.source.dandanplay;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 弹弹play (dandanplay) API 客户端。
 *
 * <p>API 强制签名（对齐 Animeko {@code DandanplayClient}）：
 * <pre>
 *   X-AppId:     appId
 *   X-Timestamp: 当前秒级时间戳
 *   X-Signature: Base64( SHA256( appId + timestamp + urlPath + appSecret ) )
 * </pre>
 * 注意签名里的 path 是「不含域名的请求路径」，例如 {@code /api/v2/bangumi/bgmtv/1234}。
 *
 * <p>核心端点：
 * <ul>
 *   <li>GET /api/v2/bangumi/bgmtv/{bgmSubjectId} —— 用 Bangumi id 换 dandan 剧集表（最可靠）</li>
 *   <li>GET /api/v2/bangumi/{animeId}            —— 用 dandan animeId 取剧集表</li>
 *   <li>GET /api/v2/search/anime?keyword=        —— 按名字搜番（降级）</li>
 *   <li>GET /api/v2/search/episodes?anime=&episode= —— 按名+集搜（降级）</li>
 *   <li>GET /api/v2/comment/{episodeId}?chConvert=0&amp;withRelated=true —— 拉取弹幕</li>
 * </ul>
 */
@Slf4j
@Component
public class DandanplayClient {

    private static final String BASE = "https://api.dandanplay.net";

    @Value("${abc.dandanplay.app-id:}")
    private String appId;

    @Value("${abc.dandanplay.app-secret:}")
    private String appSecret;

    public boolean isConfigured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    // ─── 对外方法 ───────────────────────────────────────────────

    /**
     * 用 Bangumi subject id 获取 dandan 剧集表。
     * @return dandan 剧集列表（含 episodeId/episodeTitle/episodeNumber），失败返回空列表
     */
    public List<DandanEpisode> getEpisodesByBgmtvSubjectId(long bgmSubjectId) {
        JSONObject resp = get("/api/v2/bangumi/bgmtv/" + bgmSubjectId);
        return extractEpisodes(resp);
    }

    /** 用 dandan animeId 获取剧集表。 */
    public List<DandanEpisode> getEpisodesByAnimeId(long animeId) {
        JSONObject resp = get("/api/v2/bangumi/" + animeId);
        return extractEpisodes(resp);
    }

    /** 按名字模糊搜索番剧，返回 {animeId, animeTitle} 列表（降级用）。 */
    public List<Map<String, Object>> searchAnime(String keyword) {
        JSONObject resp = get("/api/v2/search/anime?keyword=" + urlEncode(keyword));
        List<Map<String, Object>> out = new ArrayList<>();
        if (resp == null) return out;
        JSONArray animes = resp.getJSONArray("animes");
        if (animes != null) {
            for (Object o : animes) {
                JSONObject a = (JSONObject) o;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("animeId", a.getLong("animeId"));
                m.put("animeTitle", a.getStr("animeTitle"));
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 拉取某集弹幕。
     * @return 原始弹幕列表（cid/p/m），失败返回空列表
     */
    public List<JSONObject> getDanmakuList(long episodeId) {
        JSONObject resp = get("/api/v2/comment/" + episodeId + "?chConvert=0&withRelated=true");
        List<JSONObject> out = new ArrayList<>();
        if (resp == null) return out;
        JSONArray comments = resp.getJSONArray("comments");
        if (comments != null) {
            for (Object o : comments) out.add((JSONObject) o);
        }
        return out;
    }

    // ─── 内部实现 ───────────────────────────────────────────────

    private List<DandanEpisode> extractEpisodes(JSONObject resp) {
        List<DandanEpisode> out = new ArrayList<>();
        if (resp == null) return out;
        Boolean success = resp.getBool("success", false);
        JSONObject bangumi = resp.getJSONObject("bangumi");
        if (!Boolean.TRUE.equals(success) || bangumi == null) {
            log.info("dandanplay 未匹配到番剧: errorCode={}, errorMessage={}",
                    resp.getInt("errorCode", -1), resp.getStr("errorMessage"));
            return out;
        }
        JSONArray eps = bangumi.getJSONArray("episodes");
        if (eps != null) {
            for (Object o : eps) {
                JSONObject e = (JSONObject) o;
                out.add(new DandanEpisode(
                        e.getLong("episodeId", 0L),
                        e.getStr("episodeTitle", ""),
                        e.getStr("episodeNumber", "")
                ));
            }
        }
        return out;
    }

    /** 带签名 GET，返回解析后的 JSONObject；失败返回 null。 */
    private JSONObject get(String path) {
        if (!isConfigured()) {
            log.warn("dandanplay 未配置 appId/appSecret，跳过拉取");
            return null;
        }
        try {
            long ts = System.currentTimeMillis() / 1000;
            String sign = sign(appId, ts, path, appSecret);
            HttpResponse resp = HttpRequest.get(BASE + path)
                    .header("X-AppId", appId)
                    .header("X-Timestamp", String.valueOf(ts))
                    .header("X-Signature", sign)
                    .header("Accept", "application/json")
                    .timeout(60_000)
                    .execute();
            if (resp.getStatus() != 200) {
                // dandanplay 用 X-Error-Message 头给出具体原因（Invalid AppId / Invalid Signature / Invalid Timestamp ...）
                String err = resp.header("X-Error-Message");
                log.warn("dandanplay 请求失败 HTTP {} : {}  <- {}", resp.getStatus(), path,
                        err != null ? err : "(no X-Error-Message)");
                return null;
            }
            return JSONUtil.parseObj(resp.body());
        } catch (Exception e) {
            log.error("dandanplay 请求异常: {}", path, e);
            return null;
        }
    }

    /** Base64( SHA256( appId + timestamp + path + appSecret ) ) */
    static String sign(String appId, long timestamp, String path, String appSecret) throws Exception {
        String data = appId + timestamp + path + appSecret;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** dandan 剧集（轻量模型） */
    public record DandanEpisode(long episodeId, String episodeTitle, String episodeNumber) { }
}
