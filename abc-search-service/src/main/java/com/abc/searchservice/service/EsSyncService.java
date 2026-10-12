package com.abc.searchservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL → ElasticSearch 同步。
 *
 * <p>补齐原项目缺失的索引写入管道：把 MySQL 的番剧（anime + anime_tag/tag）
 * 写入 ES 索引 {@code abc_anime}，让 {@code /api/search/*} 真正能搜到数据。
 *
 * <p>索引文档结构对齐 IndexInitializer 的 mapping：
 * title(text) / description(text) / tags(keyword[]) / year(int) / season(keyword) / type(keyword) / rating_avg(float)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsSyncService {

    private static final String INDEX = "abc_anime";

    private final RestHighLevelClient esClient;
    private final JdbcTemplate jdbc;

    /** 全量重建：把 MySQL 所有番剧灌进 ES。返回同步条数。 */
    public int reindexAll() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title, description, type, year, season, rating_avg FROM anime");
        if (rows.isEmpty()) {
            log.info("MySQL 无番剧数据，跳过 ES 重建");
            return 0;
        }
        try {
            BulkRequest bulk = new BulkRequest();
            for (Map<String, Object> r : rows) {
                long id = ((Number) r.get("id")).longValue();
                bulk.add(new IndexRequest(INDEX)
                        .id(String.valueOf(id))
                        .source(toDoc(r, id), XContentType.JSON));
            }
            esClient.bulk(bulk, RequestOptions.DEFAULT);
            log.info("ES 全量重建完成，共 {} 条", rows.size());
            return rows.size();
        } catch (Exception e) {
            log.error("ES 全量重建失败", e);
            throw new RuntimeException("ES 重建失败: " + e.getMessage(), e);
        }
    }

    /** 增量：同步单条番剧到 ES（插入/更新后调用）。 */
    public boolean indexOne(long animeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title, description, type, year, season, rating_avg FROM anime WHERE id = ?", animeId);
        if (rows.isEmpty()) {
            log.info("番剧 {} 不存在，跳过索引", animeId);
            return false;
        }
        try {
            Map<String, Object> r = rows.get(0);
            IndexRequest req = new IndexRequest(INDEX)
                    .id(String.valueOf(animeId))
                    .source(toDoc(r, animeId), XContentType.JSON);
            IndexResponse resp = esClient.index(req, RequestOptions.DEFAULT);
            log.info("ES 索引单条: id={}, result={}", animeId, resp.getResult());
            return true;
        } catch (Exception e) {
            log.error("ES 索引单条失败 id={}", animeId, e);
            return false;
        }
    }

    /** 组装 ES 文档，并带上标签（多对多）。 */
    private Map<String, Object> toDoc(Map<String, Object> a, long animeId) {
        List<String> tags = new ArrayList<>();
        try {
            tags = jdbc.queryForList(
                    "SELECT t.name FROM anime_tag at JOIN tag t ON at.tag_id = t.id WHERE at.anime_id = ?",
                    String.class, animeId);
        } catch (Exception e) {
            log.warn("查询番剧 {} 标签失败: {}", animeId, e.getMessage());
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", a.get("title"));
        doc.put("description", a.get("description"));
        doc.put("tags", tags);
        doc.put("year", a.get("year"));
        doc.put("season", a.get("season"));
        doc.put("type", a.get("type"));
        doc.put("rating_avg", a.get("rating_avg"));
        return doc;
    }
}
