package com.abc.sourceservice.controller;

import com.abc.common.result.R;
import com.abc.sourceservice.entity.SourceRule;
import com.abc.sourceservice.service.PlaybackService;
import com.abc.sourceservice.service.RuleImporter;
import com.abc.sourceservice.service.RuleTestService;
import com.abc.sourceservice.service.SourceAggregator;
import com.abc.sourceservice.service.SourceRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 采集源管理 + 聚合搜索 + 播放解析 + 规则导入/自测/批量测试。
 */
@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
public class SourceController {

    private final SourceRuleService ruleService;
    private final SourceAggregator aggregator;
    private final PlaybackService playbackService;
    private final RuleImporter ruleImporter;
    private final RuleTestService ruleTestService;

    // ─── 规则 CRUD（ADMIN）─────────────────────────────────────

    @GetMapping("/rules")
    public R<List<SourceRule>> listRules() {
        return R.ok(ruleService.listAll());
    }

    @GetMapping("/rule/{id}")
    public R<SourceRule> getRule(@PathVariable Long id) {
        return R.ok(ruleService.getById(id));
    }

    @PostMapping("/rule")
    public R<SourceRule> createRule(@RequestBody SourceRule rule) {
        return R.ok(ruleService.create(rule));
    }

    @PutMapping("/rule/{id}")
    public R<SourceRule> updateRule(@PathVariable Long id, @RequestBody SourceRule rule) {
        rule.setId(id);
        return R.ok(ruleService.update(rule));
    }

    @DeleteMapping("/rule/{id}")
    public R<Void> deleteRule(@PathVariable Long id) {
        ruleService.delete(id);
        return R.ok();
    }

    // ─── 聚合搜索 ─────────────────────────────────────────────

    @GetMapping("/search")
    public R<List<SourceAggregator.SourceResult>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20000") int timeoutMs) {
        return R.ok(aggregator.searchAll(keyword, timeoutMs));
    }

    /** 选出与标准标题最匹配的候选。 */
    @GetMapping("/match")
    public R<Map<String, Object>> match(
            @RequestParam String title,
            @RequestParam(defaultValue = "1") int ep,
            @RequestParam(defaultValue = "20000") int timeoutMs,
            @RequestParam(defaultValue = "0.45") double threshold) {
        SourceAggregator.Candidate c = aggregator.findBest(title, ep, timeoutMs, threshold);
        if (c == null) return R.ok(Map.of("matched", false));
        return R.ok(Map.of(
                "matched", true,
                "ruleId", c.ruleId(),
                "sourceName", c.sourceName(),
                "matchedTitle", c.matchedTitle(),
                "score", c.score(),
                "direct", c.isDirect(),
                "directUrl", c.directUrl() == null ? "" : c.directUrl(),
                "episodePageUrl", c.episodePageUrl() == null ? "" : c.episodePageUrl()));
    }

    // ─── 播放解析 ─────────────────────────────────────────────

    /** 列出可用资源源（供前端"切换线路"下拉）。 */
    @GetMapping("/sources")
    public R<List<Map<String, Object>>> sources() {
        return R.ok(aggregator.listSources());
    }

    /**
     * 源健康检查（深度）：对每个源「搜索→分集解析」→ 前端红/橙/绿点。
     * status: ok=绿(能解析到分集) / search_only=橙(搜得到但解析失败) / dead=红(搜不到)
     */
    @GetMapping("/health")
    public R<List<Map<String, Object>>> health(@RequestParam String title,
                                               @RequestParam(defaultValue = "30000") int timeoutMs) {
        return R.ok(aggregator.healthCheck(title, timeoutMs));
    }

    /**
     * 解析播放信息：{ url, proxyUrl, headers, format, sourceName, ruleId }。
     * @param cleanTitle 归一化后的番剧标题（可选，用于更准的匹配，如 "火影忍者"）
     * @param ruleId     指定源（可选；不传则多源择优）。用于"切换线路"。
     */
    @GetMapping("/play")
    public R<Map<String, Object>> play(@RequestParam String animeKey,
                                       @RequestParam(defaultValue = "1") int ep,
                                       @RequestParam(required = false) String title,
                                       @RequestParam(required = false) String cleanTitle,
                                       @RequestParam(required = false) Long ruleId) {
        return R.ok(playbackService.play(animeKey, ep, title, cleanTitle, ruleId));
    }

    /**
     * 一键自测：完整跑一遍「搜索→匹配→解析直链」，用于验证闭环是否可用。
     * GET /api/source/test?title=火影忍者&ep=1
     */
    @GetMapping("/test")
    public R<Map<String, Object>> test(@RequestParam String title,
                                       @RequestParam(defaultValue = "1") int ep) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("query", title);
        out.put("ep", ep);
        // 直接以 keyword=title 走择优 + 解析
        java.util.Map<String, Object> play = playbackService.play("test:" + title, ep, title, title);
        out.put("result", play);
        boolean ok = play.get("url") != null;
        out.put("ok", ok);
        return R.ok(out);
    }

    // ─── 规则导入（Animeko / Kazumi 格式）──────────────────────

    /** 导入 Animeko css1.json（body 为原始 JSON 文本）。 */
    @PostMapping("/import/animeko")
    public R<Map<String, Object>> importAnimeko(@RequestBody String body) {
        List<SourceRule> rules = ruleImporter.fromAnimeko(body);
        int inserted = 0, skipped = 0;
        for (SourceRule r : rules) {
            SourceRule exist = ruleService.getByName(r.getName());
            if (exist != null) { skipped++; continue; }
            ruleService.create(r);
            inserted++;
        }
        return R.ok(Map.of("parsed", rules.size(), "inserted", inserted, "skipped", skipped));
    }

    /** 导入 Kazumi 插件 JSON（单个或数组）。 */
    @PostMapping("/import/kazumi")
    public R<Map<String, Object>> importKazumi(@RequestBody String body) {
        List<SourceRule> rules = ruleImporter.fromKazumi(body);
        int inserted = 0, skipped = 0;
        for (SourceRule r : rules) {
            SourceRule exist = ruleService.getByName(r.getName());
            if (exist != null) { skipped++; continue; }
            ruleService.create(r);
            inserted++;
        }
        return R.ok(Map.of("parsed", rules.size(), "inserted", inserted, "skipped", skipped));
    }

    /** 预览解析结果（不落库）：看看 Animeko/Kazumi 规则被转成了什么。 */
    @GetMapping("/import/preview")
    public R<Map<String, Object>> previewImport(@RequestParam String source,
                                                @RequestBody String body) {
        List<SourceRule> rules = "kazumi".equalsIgnoreCase(source)
                ? ruleImporter.fromKazumi(body) : ruleImporter.fromAnimeko(body);
        return R.ok(Map.of("count", rules.size(), "rules", rules));
    }

    // ─── 规则自测（单个 / 批量）────────────────────────────────

    /** 测试单条规则。withBrowser=true 会跑浏览器取直链（慢）。 */
    @GetMapping("/test/rule/{id}")
    public R<RuleTestService.TestResult> testRule(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "火影忍者") String keyword,
                                                  @RequestParam(defaultValue = "false") boolean withBrowser) {
        SourceRule rule = ruleService.getById(id);
        if (rule == null) return R.fail(404, "规则不存在");
        return R.ok(ruleTestService.test(rule, keyword, withBrowser));
    }

    /** 批量测试全部规则，产出带状态的报告（不行的会被标注）。 */
    @GetMapping("/test/all")
    public R<Map<String, Object>> testAll(@RequestParam(defaultValue = "火影忍者") String keyword,
                                          @RequestParam(defaultValue = "false") boolean withBrowser) {
        List<RuleTestService.TestResult> results = ruleTestService.testAll(keyword, withBrowser);
        long pass = results.stream().filter(r -> r.status().startsWith("PASS")).count();
        long ok = results.stream().filter(r -> r.status().startsWith("OK")).count();
        long fail = results.stream().filter(r -> r.status().startsWith("FAIL")).count();
        java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("total", results.size());
        summary.put("pass", pass);
        summary.put("okNoDirect", ok);
        summary.put("fail", fail);
        summary.put("keyword", keyword);
        summary.put("withBrowser", withBrowser);
        summary.put("results", results);
        return R.ok(summary);
    }
}
