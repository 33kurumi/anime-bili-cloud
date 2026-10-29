package com.abc.sourceservice.service;

import com.abc.sourceservice.engine.EngineModels.Road;
import com.abc.sourceservice.engine.EngineModels.SearchItem;
import com.abc.sourceservice.engine.RuleEngine;
import com.abc.sourceservice.engine.RuleExecutionConfig;
import com.abc.sourceservice.engine.strategy.MaccmsStrategy;
import com.abc.sourceservice.entity.SourceRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规则探针：统一入口测试一条规则（或一份未落库的临时规则）。
 *
 * <p>为什么要有它：项目里有 4 种采集策略（xpath / api / css(Animeko) / maccms），
 * 以前每种策略只能从不同入口试（{@code /test/rule/{id}} 只测库里规则，
 * {@code /import/preview} 只做转换不执行），调试一条新规则得先入库、再跑、再删。
 * 现在一个 {@code POST /api/source/rule/test} 既能测已入库规则（传 ruleId），
 * 也能测「贴一段配置直接跑」（不传 ruleId，按 mode 从 body 里读字段），
 * 返回统一结构 {@code {matched, items[{title,link,episode}], costMs, errors}}，
 * 前端规则编辑器可以直接对着它做「保存前自测」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleProbeService {

    private final SourceRuleService ruleService;

    /**
     * 执行一次探针。
     *
     * <p>body 支持两种形态：
     * <ol>
     *   <li>{@code {"ruleId": 12, "keyword": "火影忍者"}} —— 测库里已有规则；</li>
     *   <li>{@code {"mode":"css","name":"xx","baseUrl":"https://a.com","cssConfig":"{...}",
     *       "searchUrl":"...","searchList":"...","keyword":"火影忍者"}} —— 测临时规则。</li>
     * </ol>
     */
    public Map<String, Object> probe(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        long started = System.currentTimeMillis();
        String keyword = firstText(b, "keyword", "title");
        boolean deep = parseBool(firstText(b, "deep", "withChapters"));

        List<Map<String, Object>> items = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        String mode;
        String ruleName = firstText(b, "name");

        try {
            SourceRule rule = loadRule(b);
            RuleExecutionConfig cfg = rule != null ? ruleService.toConfig(rule) : buildConfig(b);
            mode = cfg.getSearchMode();
            if (ruleName.isBlank() && cfg.getName() != null) {
                ruleName = cfg.getName();
            }

            if (keyword.isBlank()) {
                errors.add("缺少 keyword（或 title），无法执行搜索");
            } else {
                RuleEngine engine = new RuleEngine();
                if (cfg.isMaccms()) {
                    collectMaccms(engine, cfg, keyword, items);
                } else {
                    collectSearch(engine, cfg, keyword, deep, items, errors);
                }
            }
        } catch (Exception e) {
            mode = firstText(b, "mode").isEmpty() ? "unknown" : firstText(b, "mode").toLowerCase(Locale.ROOT);
            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.info("规则探针执行失败: {}", e.getMessage());
        }

        out.put("matched", !items.isEmpty());
        out.put("mode", mode);
        out.put("ruleName", ruleName);
        out.put("keyword", keyword);
        out.put("count", items.size());
        out.put("items", items);
        out.put("errors", errors);
        out.put("costMs", System.currentTimeMillis() - started);
        return out;
    }

    // ─── 采集 ────────────────────────────────────────────────────

    private void collectMaccms(RuleEngine engine, RuleExecutionConfig cfg, String keyword,
                               List<Map<String, Object>> items) {
        List<MaccmsStrategy.MaccmsItem> found = engine.searchMaccms(cfg, keyword);
        for (MaccmsStrategy.MaccmsItem it : found) {
            List<String> urls = it.roads().isEmpty() ? List.of() : it.roads().get(0).getUrls();
            Map<String, Object> m = item(it.name(), urls.isEmpty() ? "" : urls.get(0), urls.size());
            m.put("vodId", it.vodId());
            m.put("direct", true);
            m.put("roads", it.roads().size());
            items.add(m);
        }
    }

    private void collectSearch(RuleEngine engine, RuleExecutionConfig cfg, String keyword, boolean deep,
                               List<Map<String, Object>> items, List<String> errors) {
        List<SearchItem> found = engine.search(cfg, keyword);
        List<Road> firstRoads = null;
        for (int i = 0; i < found.size(); i++) {
            SearchItem it = found.get(i);
            Map<String, Object> m = item(it.getName(), it.getUrl(), null);
            m.put("direct", false);
            items.add(m);
        }
        if (!deep || found.isEmpty()) {
            return;
        }
        // 深度模式：对第一个结果解析分集，避免「搜得到但详情页解析失败」的虚假绿灯
        try {
            firstRoads = engine.chapters(cfg, found.get(0).getUrl());
            int episodes = firstRoads.isEmpty() ? 0 : firstRoads.get(0).getUrls().size();
            items.get(0).put("episode", episodes);
            items.get(0).put("roads", firstRoads.size());
            if (episodes == 0) {
                errors.add("分集解析为空：规则可能已过期或该详情页需登录");
            }
        } catch (Exception e) {
            errors.add("分集解析失败: " + e.getMessage());
        }
    }

    private Map<String, Object> item(String title, String link, Integer episode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("link", link == null ? "" : link);
        m.put("episode", episode);
        return m;
    }

    // ─── 临时规则构建 ────────────────────────────────────────────

    private SourceRule loadRule(Map<String, Object> b) {
        String rid = firstText(b, "ruleId");
        if (rid.isBlank()) {
            return null;
        }
        try {
            return ruleService.getById(Long.parseLong(rid.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 把 JSON body 拼成 RuleExecutionConfig（不落库，字段名与 SourceRule 对齐）。 */
    private RuleExecutionConfig buildConfig(Map<String, Object> b) {
        String mode = normalizeMode(firstText(b, "mode"));
        String chapterMode = firstText(b, "chapterMode").isBlank() ? "xpath" : firstText(b, "chapterMode");
        String cssConfig = firstText(b, "cssConfig", "css");
        String apiConfig = firstText(b, "searchApiCfg", "apiConfig");
        return RuleExecutionConfig.builder()
                .name(firstText(b, "name").isBlank() ? "临时规则" : firstText(b, "name"))
                .baseUrl(firstText(b, "baseUrl"))
                .searchMode(mode)
                .chapterMode(chapterMode)
                .searchUrl(firstText(b, "searchUrl"))
                .searchList(firstText(b, "searchList"))
                .searchName(firstText(b, "searchName"))
                .searchResult(firstText(b, "searchResult"))
                .chapterRoads(firstText(b, "chapterRoads"))
                .chapterResult(firstText(b, "chapterResult"))
                .searchApiCfg(apiConfig)
                .chapterApiCfg(firstText(b, "chapterApiCfg", "chapterApiConfig"))
                .cssConfig(cssConfig)
                .macCmsApi(firstText(b, "macCmsApi", "maccms", "maccmsApi"))
                .usePost(parseBool(firstText(b, "usePost")))
                .browserResolve(false)
                .userAgent(firstText(b, "userAgent"))
                .referer(firstText(b, "referer"))
                .build();
    }

    private static String normalizeMode(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "api" -> "api";
            case "maccms" -> "maccms";
            case "css" -> "css";
            default -> "xpath";
        };
    }

    // ─── 小工具 ──────────────────────────────────────────────────

    /** 依次取第一个非空字符串字段（支持别名）。 */
    static String firstText(Map<String, Object> b, String... keys) {
        for (String key : keys) {
            Object v = b.get(key);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    private static boolean parseBool(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }
}
