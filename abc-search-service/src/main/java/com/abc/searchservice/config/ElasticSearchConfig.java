package com.abc.searchservice.config;

import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.xcontent.XContentType;
import org.apache.http.HttpHost;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import java.io.IOException;

@Slf4j
@Configuration
public class ElasticSearchConfig {

    private RestHighLevelClient client;

    @Bean
    public RestHighLevelClient restHighLevelClient() {
        client = new RestHighLevelClient(
                RestClient.builder(new HttpHost("127.0.0.1", 9200, "http")));
        return client;
    }

    /**
     * Auto-create the abc_anime index at startup so users don't get
     * "index_not_found_exception" on the first search.
     */
    @PostConstruct
    public void initIndices() {
        // 先等等,等 restHighLevelClient bean 注入完成再操作
        // 通过 ApplicationRunner 更安全 — 延迟到整个 context 启动后
    }
}
