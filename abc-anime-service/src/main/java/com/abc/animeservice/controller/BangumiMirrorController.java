package com.abc.animeservice.controller;

import com.abc.animeservice.datasource.BangumiDataSource;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bangumi 镜像管理：测试各镜像/官方的可用性 + 魔法开关（走本地代理直连官方）。
 * 前端"个人中心 / 设置"里可做测试按钮 + 魔法开关。
 */
@RestController
@RequestMapping("/api/datasource/mirror")
@RequiredArgsConstructor
public class BangumiMirrorController {

    private final BangumiDataSource bangumiDataSource;

    /** 状态：魔法开关、代理配置、当前镜像列表。 */
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("proxyEnabled", bangumiDataSource.isProxyEnabled());
        m.put("proxyConfigured", bangumiDataSource.proxyConfigured());
        m.put("proxyHost", bangumiDataSource.getProxyHost());
        m.put("proxyPort", bangumiDataSource.getProxyPort());
        m.put("mirrors", bangumiDataSource.getMirrorList());
        return R.ok(m);
    }

    /**
     * 逐个测试镜像 + 官方（官方经代理）。
     * @return [{url, type, ok, ms}]
     */
    @GetMapping("/test")
    public R<List<Map<String, Object>>> test() {
        List<Map<String, Object>> out = new ArrayList<>();
        // 各镜像（直连）
        for (String base : bangumiDataSource.getMirrorList()) {
            long ms = bangumiDataSource.ping(base, false, 8000);
            out.add(entry(base, "mirror", ms));
        }
        // 官方（经代理）—— 仅当配置了代理
        if (bangumiDataSource.proxyConfigured()) {
            String official = bangumiDataSource.getOfficialUrl();
            long ms = bangumiDataSource.ping(official, true, 8000);
            out.add(entry(official + "(proxy)", "official", ms));
        }
        return R.ok(out);
    }

    /** 魔法开关：enabled=true 走本地代理直连官方。 */
    @PostMapping("/magic")
    public R<Map<String, Object>> magic(@RequestParam boolean enabled) {
        bangumiDataSource.setProxyEnabled(enabled);
        return R.ok(Map.of(
                "proxyEnabled", bangumiDataSource.isProxyEnabled(),
                "proxyConfigured", bangumiDataSource.proxyConfigured()));
    }

    private Map<String, Object> entry(String url, String type, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("type", type);
        m.put("ok", ms >= 0);
        m.put("ms", ms);
        return m;
    }
}
