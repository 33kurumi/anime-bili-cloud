package com.abc.animeservice.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bangumi 评论（吐槽）拉取。
 *
 * <p>评论接口在 {@code next.*} 镜像上：{@code GET /p1/subjects/{id}/comments?limit=&offset=}
 * （普通 v0 镜像没有此接口，官方 next.bgm.tv 被墙，故走 next.bangumi.vip 镜像）。
 */
@Slf4j
@Service
public class BangumiCommentService {

    @Value("${abc.bangumi.comments-mirror:https://next.bangumi.vip}")
    private String commentsMirror;

    private static final String UA = "AnimeBilibiliCloud/1.0 (https://github.com/abc; contact@abc.com)";

    /**
     * 拉取某番剧的评论（吐槽）。
     * @param subjectId Bangumi 数字 id（"bgm:123" 会自动提取 123）
     * @return [{id, user, avatar, rate, comment, updatedAt}]
     */
    public List<Map<String, Object>> comments(String subjectId, int limit, int offset) {
        List<Map<String, Object>> out = new ArrayList<>();
        String id = cleanId(subjectId);
        if (id == null) return out;
        String url = commentsMirror + "/p1/subjects/" + id + "/comments?limit=" + limit + "&offset=" + offset;
        try {
            HttpResponse resp = HttpRequest.get(url)
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .timeout(12000)
                    .execute();
            if (resp.getStatus() != 200) {
                log.warn("Bangumi 评论拉取失败 HTTP {}: {}", resp.getStatus(), url);
                return out;
            }
            JSONObject root = JSONUtil.parseObj(resp.body());
            JSONArray data = root.getJSONArray("data");
            if (data != null) {
                for (Object o : data) {
                    JSONObject c = (JSONObject) o;
                    JSONObject user = c.getJSONObject("user");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getLong("id"));
                    m.put("username", user != null ? user.getStr("nickname", user.getStr("username", "匿名")) : "匿名");
                    JSONObject avatar = user != null ? user.getJSONObject("avatar") : null;
                    m.put("avatar", avatar != null ? fixImg(avatar.getStr("small", "")) : "");
                    m.put("rate", c.getInt("rate", 0));
                    m.put("comment", c.getStr("comment", ""));
                    m.put("updatedAt", c.getLong("updatedAt", 0L));
                    out.add(m);
                }
            }
            log.info("Bangumi 评论拉取成功: subject={}, {} 条", id, out.size());
        } catch (Exception e) {
            log.error("Bangumi 评论拉取异常 subject={}", id, e);
        }
        return out;
    }

    /** "bgm:123" / "123" → "123" */
    private String cleanId(String id) {
        if (id == null) return null;
        String k = id;
        while (k.startsWith("bgm:")) k = k.substring(4);
        return k.matches("\\d+") ? k : null;
    }

    /** 头像镜像替换（lain.bgm.tv 被墙 → 走可用镜像）；lain.bangumi.vip 本就可访问，原样保留。 */
    private String fixImg(String url) {
        if (url == null) return "";
        return url.replace("lain.bgm.tv", "lain.bangumi.vip");
    }
}
