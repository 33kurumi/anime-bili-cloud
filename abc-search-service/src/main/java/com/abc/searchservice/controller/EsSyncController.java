package com.abc.searchservice.controller;

import com.abc.common.result.R;
import com.abc.searchservice.service.EsSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ES 索引维护接口。
 * - 全量重建：POST /api/search/admin/reindex（把 MySQL 番剧全部灌进 ES）
 * - 单条增量：POST /api/search/admin/index/{id}
 * 建议限 ADMIN；此处为演示/运维方便暂放白名单。
 */
@RestController
@RequestMapping("/api/search/admin")
@RequiredArgsConstructor
public class EsSyncController {

    private final EsSyncService esSyncService;

    @PostMapping("/reindex")
    public R<Map<String, Object>> reindex() {
        long start = System.currentTimeMillis();
        int n = esSyncService.reindexAll();
        return R.ok(Map.of(
                "synced", n,
                "elapsedMs", System.currentTimeMillis() - start));
    }

    @PostMapping("/index/{id}")
    public R<Map<String, Object>> indexOne(@PathVariable long id) {
        boolean ok = esSyncService.indexOne(id);
        return R.ok(Map.of("animeId", id, "indexed", ok));
    }
}
