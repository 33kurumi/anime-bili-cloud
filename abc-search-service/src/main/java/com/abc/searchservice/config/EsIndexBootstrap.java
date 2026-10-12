package com.abc.searchservice.config;

import com.abc.searchservice.service.EsSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时若 ES 索引为空而 MySQL 有数据，则自动全量重建，
 * 让搜索"开箱即用"，无需手动触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexBootstrap implements ApplicationRunner {

    private final EsSyncService esSyncService;
    private final JdbcTemplate jdbc;
    private final org.elasticsearch.client.RestHighLevelClient esClient;

    @Override
    public void run(ApplicationArguments args) {
        try {
            long esCount = esClient.count(
                    new CountRequest("abc_anime"),
                    RequestOptions.DEFAULT).getCount();
            Long dbCount = jdbc.queryForObject("SELECT COUNT(*) FROM anime", Long.class);
            if (dbCount == null) dbCount = 0L;
            log.info("ES 索引 abc_anime 现有 {} 条，MySQL 番剧 {} 条", esCount, dbCount);

            if (esCount == 0 && dbCount > 0) {
                log.info("ES 索引为空但 MySQL 有数据，自动全量重建...");
                int n = esSyncService.reindexAll();
                log.info("自动重建完成，灌入 {} 条", n);
            }
        } catch (Exception e) {
            log.warn("ES 启动自检/重建跳过: {}", e.getMessage());
        }
    }
}
