package com.abc.sourceservice.engine;

import com.abc.sourceservice.entity.SourceRule;
import lombok.Builder;
import lombok.Data;

/**
 * 规则执行的不可变快照（对齐 Kazumi 的 RuleExecutionConfig）。
 * 把 DB 实体拍平，执行期不依赖 MyBatis。
 */
@Data
@Builder
public class RuleExecutionConfig {
    private Long ruleId;
    private String name;
    private String baseUrl;
    private boolean usePost;
    private String searchMode;    // xpath / api / maccms
    private String chapterMode;   // xpath / api
    private String searchUrl;     // 含 @keyword
    private String searchList;
    private String searchName;
    private String searchResult;
    private String chapterRoads;
    private String chapterResult;
    private String searchApiCfg;
    private String chapterApiCfg;
    private String cssConfig;     // css 模式：Animeko web-selector 的 searchConfig
    private String macCmsApi;     // maccms 采集接口根地址
    private boolean browserResolve; // 是否用无头浏览器解析直链
    private String matchVideoUrl;   // 浏览器拦截识别视频的正则
    private String userAgent;
    private String referer;

    public boolean isMaccms() {
        return "maccms".equals(searchMode);
    }

    public boolean isCss() {
        return "css".equals(searchMode);
    }

    public static RuleExecutionConfig of(SourceRule r) {
        String mode = normalizeMode(r.getSearchMode());
        // css 模式：Animeko 的 matchVideo.matchVideoUrl 嵌在 cssConfig 里，抽出来供浏览器拦截用
        String matchVideoUrl = r.getMatchVideoUrl();
        if ("css".equals(mode) && (matchVideoUrl == null || matchVideoUrl.isBlank())
                && r.getCssConfig() != null) {
            try {
                cn.hutool.json.JSONObject mv = cn.hutool.json.JSONUtil.parseObj(r.getCssConfig())
                        .getJSONObject("matchVideo");
                if (mv != null) matchVideoUrl = mv.getStr("matchVideoUrl");
            } catch (Exception ignored) { }
        }
        return RuleExecutionConfig.builder()
                .ruleId(r.getId())
                .name(r.getName())
                .baseUrl(r.getBaseUrl())
                .usePost(r.getUsePost() != null && r.getUsePost() == 1)
                .searchMode(mode)
                .chapterMode(normalizeChapterMode(r.getChapterMode()))
                .searchUrl(r.getSearchUrl())
                .searchList(r.getSearchList())
                .searchName(r.getSearchName())
                .searchResult(r.getSearchResult())
                .chapterRoads(r.getChapterRoads())
                .chapterResult(r.getChapterResult())
                .searchApiCfg(r.getSearchApiCfg())
                .chapterApiCfg(r.getChapterApiCfg())
                .cssConfig(r.getCssConfig())
                .macCmsApi(r.getMacCmsApi())
                .browserResolve(r.getBrowserResolve() != null && r.getBrowserResolve() == 1)
                .matchVideoUrl(matchVideoUrl)
                .userAgent(r.getUserAgent())
                .referer(r.getReferer())
                .build();
    }

    private static String normalizeMode(String m) {
        if ("api".equalsIgnoreCase(m)) return "api";
        if ("maccms".equalsIgnoreCase(m)) return "maccms";
        if ("css".equalsIgnoreCase(m)) return "css";
        return "xpath";
    }

    private static String normalizeChapterMode(String m) {
        return "api".equalsIgnoreCase(m) ? "api" : "xpath";
    }
}
