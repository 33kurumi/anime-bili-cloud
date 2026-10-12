package com.abc.sourceservice.service;

import com.abc.sourceservice.engine.EngineModels.Road;
import com.abc.sourceservice.engine.EngineModels.SearchItem;
import com.abc.sourceservice.engine.RuleEngine;
import com.abc.sourceservice.engine.RuleExecutionConfig;
import com.abc.sourceservice.engine.browser.BrowserResolver;
import com.abc.sourceservice.engine.strategy.MaccmsStrategy;
import com.abc.sourceservice.entity.SourceRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则自测：对单条/全部规则跑「搜索 → 分集 → 浏览器取直链」，产出可读状态。
 * 用于批量验证导入的规则是否可用（不行的标注出来）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleTestService {

    private final SourceRuleService ruleService;
    private final BrowserResolver browserResolver;

    /** 单条规则的测试结果。 */
    public record TestResult(Long id, String name, String mode, String status,
                             boolean searchOk, int searchCount, String sampleTitle,
                             boolean chapterOk, int episodeCount,
                             boolean browserOk, String directUrl,
                             String error) { }

    /** 测试单条规则。withBrowser=true 时对第一集尝试浏览器取直链（较慢）。 */
    public TestResult test(SourceRule rule, String keyword, boolean withBrowser) {
        RuleExecutionConfig cfg = ruleService.toConfig(rule);
        String mode = cfg.getSearchMode();
        RuleEngine engine = new RuleEngine();
        int searchCount = 0;
        String sampleTitle = null;
        String firstDetail = null;
        int episodeCount = 0;
        boolean browserOk = false;
        String directUrl = null;
        String error = null;

        try {
            if (cfg.isMaccms()) {
                List<MaccmsStrategy.MaccmsItem> items = engine.searchMaccms(cfg, keyword);
                searchCount = items.size();
                if (!items.isEmpty()) {
                    MaccmsStrategy.MaccmsItem it = items.get(0);
                    sampleTitle = it.name();
                    if (!it.roads().isEmpty() && !it.roads().get(0).getUrls().isEmpty()) {
                        episodeCount = it.roads().get(0).getUrls().size();
                        directUrl = it.roads().get(0).getUrls().get(0); // maccms 直接给直链
                    }
                }
            } else {
                List<SearchItem> items = engine.search(cfg, keyword);
                searchCount = items.size();
                if (!items.isEmpty()) {
                    sampleTitle = items.get(0).getName();
                    firstDetail = items.get(0).getUrl();
                }
                if (firstDetail != null) {
                    try {
                        List<Road> roads = engine.chapters(cfg, firstDetail);
                        if (!roads.isEmpty()) {
                            episodeCount = roads.get(0).getUrls().size();
                            if (withBrowser && cfg.isBrowserResolve() && !roads.get(0).getUrls().isEmpty()) {
                                BrowserResolver.ResolvedVideo rv = browserResolver.resolve(roads.get(0).getUrls().get(0), cfg);
                                if (rv != null) { browserOk = true; directUrl = rv.url(); }
                            }
                        }
                    } catch (Exception e) {
                        error = "分集失败: " + e.getMessage();
                    }
                }
            }
        } catch (Exception e) {
            error = e.getMessage();
        }

        String status;
        if (error != null && searchCount == 0) status = "FAIL";
        else if (directUrl != null) status = "PASS";        // 拿到了可播直链
        else if (searchCount > 0 && episodeCount > 0) status = "OK(无直链)";
        else if (searchCount > 0) status = "PARTIAL(仅搜索)";
        else status = "FAIL";

        ruleService.markHealth(rule.getId(), status.startsWith("PASS") || status.startsWith("OK"));
        return new TestResult(rule.getId(), rule.getName(), mode, status,
                searchCount > 0, searchCount, sampleTitle,
                episodeCount > 0, episodeCount, browserOk, directUrl, error);
    }

    /** 批量测试全部规则。 */
    public List<TestResult> testAll(String keyword, boolean withBrowser) {
        List<TestResult> results = new ArrayList<>();
        for (SourceRule rule : ruleService.listAll()) {
            try {
                results.add(test(rule, keyword, withBrowser));
            } catch (Exception e) {
                results.add(new TestResult(rule.getId(), rule.getName(),
                        rule.getSearchMode(), "FAIL", false, 0, null,
                        false, 0, false, null, "异常: " + e.getMessage()));
            }
        }
        return results;
    }

    /** 导入前的 dry-run：不落库，直接测一批待导入规则。 */
    public List<Map<String, Object>> dryRun(List<SourceRule> rules, String keyword, boolean withBrowser) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SourceRule rule : rules) {
            rule.setId(-1L);
            TestResult r = test(rule, keyword, withBrowser);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("mode", r.mode());
            m.put("status", r.status());
            m.put("searchCount", r.searchCount());
            m.put("sampleTitle", r.sampleTitle());
            m.put("episodeCount", r.episodeCount());
            m.put("browserOk", r.browserOk());
            m.put("directUrl", r.directUrl());
            m.put("error", r.error());
            out.add(m);
        }
        return out;
    }
}
