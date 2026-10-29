package com.abc.sourceservice.service;

import com.abc.sourceservice.engine.EngineModels.Road;
import com.abc.sourceservice.engine.RuleEngine;
import com.abc.sourceservice.engine.RuleExecutionConfig;
import com.abc.sourceservice.engine.browser.BrowserResolver;
import com.abc.sourceservice.entity.SourceRule;
import com.abc.sourceservice.security.ProxySigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 播放编排：番剧标题 → 多源择优（含依次回退）或指定源 → 解析直链 → 短缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackService {

    private final SourceAggregator aggregator;
    private final SourceRuleService ruleService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final BrowserResolver browserResolver;
    private final ProxySigner proxySigner;

    private static final long CACHE_TTL_SECONDS = 3600;
    private static final int MAX_TRY = 8;   // 自动择优最多尝试几个候选

    public Map<String, Object> play(String animeKey, int episodeNum, String title, String cleanTitle) {
        return play(animeKey, episodeNum, title, cleanTitle, null);
    }

    public Map<String, Object> play(String animeKey, int episodeNum, String title,
                                    String cleanTitle, Long ruleId) {
        String cacheKey = cacheKey(animeKey, episodeNum, ruleId);
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) return cached;

        Map<String, Object> result = new LinkedHashMap<>();
        String query = (cleanTitle != null && !cleanTitle.isBlank()) ? cleanTitle : title;
        if (query == null || query.isBlank()) {
            result.put("message", "缺少番剧标题，无法匹配资源源");
            return result;
        }

        // 指定源：只试该源
        if (ruleId != null) {
            SourceAggregator.Candidate c = aggregator.resolveWithRule(ruleId, query, episodeNum, 30000);
            if (c == null) { result.put("message", "该源未匹配到该番剧"); return result; }
            Map<String, Object> r = resolveCandidate(c, episodeNum);
            return finish(cacheKey, c.isDirect(), r);
        }

        // 自动择优：按相似度排序候选，依次尝试直到有一个出真直链
        List<SourceAggregator.Candidate> candidates = aggregator.findCandidates(query, episodeNum, 20000);
        if (candidates.isEmpty()) { result.put("message", "所有资源源均未匹配到该番剧"); return result; }
        int tried = 0;
        String lastMsg = "未能解析到播放地址";
        for (SourceAggregator.Candidate c : candidates) {
            if (tried++ >= MAX_TRY) break;
            Map<String, Object> r = resolveCandidate(c, episodeNum);
            if (r.get("url") != null) {
                return finish(cacheKey, c.isDirect(), r);
            }
            lastMsg = String.valueOf(r.getOrDefault("message", lastMsg));
        }
        result.put("message", "尝试多个源均失败：" + lastMsg);
        log.info("自动择优 {} 集全部失败，候选 {} 个", episodeNum, candidates.size());
        return result;
    }

    // ─── 缓存读写（选源编排 SourceSelectService 复用同一套 key/容错）─────

    /** 播放结果缓存 key：番剧 + 集 + （可选）指定源。 */
    public String cacheKey(String animeKey, int episodeNum, Long ruleId) {
        return "source:play:" + animeKey + ":" + episodeNum + (ruleId == null ? "" : ":" + ruleId);
    }

    /**
     * 读缓存。Redis 挂了不能影响播放：异常一律当未命中返回 null。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCached(String cacheKey) {
        try {
            return (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("读取播放缓存失败（降级为未命中）: {}", e.getMessage());
            return null;
        }
    }

    /** 真直链才缓存（page 结果不算，避免把"需要浏览器再解析"的中间态缓存 1 小时）。 */
    public void putCached(String cacheKey, Map<String, Object> r) {
        if (r == null || r.get("url") == null || "page".equals(r.get("resolvedBy"))) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(cacheKey, r, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入播放缓存失败（不影响本次播放）: {}", e.getMessage());
        }
    }

    /** 真直链才缓存；返回结果。 */
    private Map<String, Object> finish(String cacheKey, boolean isDirect, Map<String, Object> r) {
        putCached(cacheKey, r);
        return r;
    }

    /**
     * 把一个候选解析成可播信息。失败时返回带 message 的 map（url 为空）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveCandidate(SourceAggregator.Candidate c, int episodeNum) {
        Map<String, Object> result = new LinkedHashMap<>();
        SourceRule rule = ruleService.getById(c.ruleId());
        if (rule == null) { result.put("message", "规则不存在"); return result; }
        RuleExecutionConfig cfg = ruleService.toConfig(rule);
        RuleEngine engine = new RuleEngine();

        String playUrl;
        String episodePageUrl = null;
        Map<String, String> browserHeaders = null;
        String resolvedBy = "direct";
        if (c.isDirect()) {
            playUrl = c.directUrl();
        } else {
            episodePageUrl = c.episodePageUrl();
            List<Road> roads;
            try {
                roads = engine.chapters(cfg, episodePageUrl);
            } catch (Exception e) {
                result.put("message", "分集解析失败: " + e.getMessage());
                result.put("sourceName", rule.getName());
                return result;
            }
            if (roads.isEmpty() || roads.get(0).getUrls().isEmpty()) {
                result.put("message", "该源未解析到剧集");
                result.put("sourceName", rule.getName());
                return result;
            }
            List<String> urls = roads.get(0).getUrls();
            int idx = Math.min(Math.max(episodeNum - 1, 0), urls.size() - 1);
            String epPageUrl = urls.get(idx);

            String direct = null;
            if (!cfg.isBrowserResolve()) {
                try { direct = engine.resolveDirectLink(cfg, epPageUrl); } catch (Exception ignored) { }
            }
            if (direct != null) {
                playUrl = direct; resolvedBy = "static";
            } else {
                BrowserResolver.ResolvedVideo rv = browserResolver.resolve(epPageUrl, cfg);
                if (rv != null) { playUrl = rv.url(); browserHeaders = rv.headers(); resolvedBy = "browser"; }
                else { playUrl = epPageUrl; resolvedBy = "page"; }
            }
        }
        if (playUrl == null || playUrl.isBlank()) {
            result.put("message", "未能解析到播放地址");
            result.put("sourceName", rule.getName());
            return result;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String referer = (rule.getReferer() != null && !rule.getReferer().isBlank())
                ? rule.getReferer() : rule.getBaseUrl();
        if (referer != null) headers.put("Referer", referer);
        if (rule.getUserAgent() != null && !rule.getUserAgent().isBlank()) headers.put("User-Agent", rule.getUserAgent());
        if (browserHeaders != null) {
            if (browserHeaders.containsKey("Cookie")) headers.put("Cookie", browserHeaders.get("Cookie"));
            if (!headers.containsKey("User-Agent") && browserHeaders.containsKey("User-Agent")) headers.put("User-Agent", browserHeaders.get("User-Agent"));
        }

        result.put("url", playUrl);
        result.put("format", playUrl.contains(".m3u8") ? "m3u8" : "mp4");
        result.put("sourceName", rule.getName());
        result.put("ruleId", rule.getId());
        result.put("matchedTitle", c.matchedTitle());
        result.put("matchScore", c.score());
        result.put("direct", c.isDirect());
        result.put("resolvedBy", resolvedBy);
        if (episodePageUrl != null) result.put("episodePageUrl", episodePageUrl);
        result.put("headers", headers);
        // 带签名：前端/播放器直接拿它播，代理端验签防篡改与白嫖
        result.put("proxyUrl", proxySigner.signedProxyUrl(rule.getId(), playUrl));
        return result;
    }
}
