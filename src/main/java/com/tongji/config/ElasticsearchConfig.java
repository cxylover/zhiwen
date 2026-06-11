package com.tongji.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(EsProperties.class)
@RequiredArgsConstructor
public class ElasticsearchConfig {

    private final EsProperties props;

    @Value("${spring.ai.vectorstore.elasticsearch.index-name:zhiguang-ai-index}")
    private String vectorIndexName;

    @Value("${spring.ai.vectorstore.elasticsearch.dimensions:1536}")
    private int vectorDimensions;

    @Value("${spring.ai.vectorstore.elasticsearch.initialize-schema:true}")
    private boolean initializeSchema;

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient() {
        BasicCredentialsProvider creds = new BasicCredentialsProvider();

        if (StringUtils.hasText(props.getUsername())) {
            creds.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(props.getUsername(), props.getPassword()));
        }

        RestClientBuilder builder = RestClient.builder(HttpHost.create(props.getHost()))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                        .setDefaultCredentialsProvider(creds));

        return builder.build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient elasticsearchRestClient) {
        RestClientTransport transport = new RestClientTransport(elasticsearchRestClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    @Bean
    @ConditionalOnProperty(prefix = "feature.rag", name = "enabled", havingValue = "true")
    public VectorStore vectorStore(RestClient elasticsearchRestClient, EmbeddingModel embeddingModel) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(vectorIndexName);
        options.setDimensions(vectorDimensions);

        return ElasticsearchVectorStore.builder(elasticsearchRestClient, embeddingModel)
                .options(options)
                .initializeSchema(initializeSchema)
                .build();
    }
}
