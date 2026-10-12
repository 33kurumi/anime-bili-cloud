package com.abc.searchservice.config;

import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * Runs AFTER the Spring context is fully initialized.
 * Creates the ES index if missing — avoids the circular
 * dependency that @PostConstruct would trigger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexInitializer implements ApplicationRunner {

    private final RestHighLevelClient esClient;

    @Override
    public void run(ApplicationArguments args) {
        try {
            GetIndexRequest getReq = new GetIndexRequest("abc_anime");
            if (!esClient.indices().exists(getReq, org.elasticsearch.client.RequestOptions.DEFAULT)) {
                CreateIndexRequest createReq = new CreateIndexRequest("abc_anime");
                createReq.mapping(
                    "{\n" +
                    "  \"properties\": {\n" +
                    "    \"title\":       { \"type\": \"text\", \"analyzer\": \"standard\" },\n" +
                    "    \"description\": { \"type\": \"text\", \"analyzer\": \"standard\" },\n" +
                    "    \"tags\":        { \"type\": \"keyword\" },\n" +
                    "    \"year\":        { \"type\": \"integer\" },\n" +
                    "    \"season\":      { \"type\": \"keyword\" },\n" +
                    "    \"type\":        { \"type\": \"keyword\" },\n" +
                    "    \"rating_avg\":  { \"type\": \"float\" }\n" +
                    "  }\n" +
                    "}",
                    XContentType.JSON
                );
                esClient.indices().create(createReq, org.elasticsearch.client.RequestOptions.DEFAULT);
                log.info("Elasticsearch index 'abc_anime' created successfully");
            } else {
                log.info("Elasticsearch index 'abc_anime' already exists");
            }
        } catch (IOException e) {
            log.error("Failed to create Elasticsearch index 'abc_anime': {}", e.getMessage());
        }
    }
}
