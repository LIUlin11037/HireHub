package com.ll.hirehub.resume.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 客户端（resume 服务，见 D-23 / Q-03）。
 * <p>
 * 独立配置类而不是复用 job 的：每个服务只连自己关心的组件，
 * 跨服务共享 Spring Bean 会把部署边界搞乱。
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${hirehub.es.host:localhost}")
    private String host;

    @Value("${hirehub.es.port:9200}")
    private int port;

    @Bean(destroyMethod = "close")
    public RestClient resumeRestClient() {
        return RestClient.builder(new HttpHost(host, port, "http")).build();
    }

    @Bean
    public ElasticsearchClient resumeElasticsearchClient(RestClient resumeRestClient,
                                                        ObjectMapper objectMapper) {
        ElasticsearchTransport transport =
                new RestClientTransport(resumeRestClient, new JacksonJsonpMapper(objectMapper));
        return new ElasticsearchClient(transport);
    }
}
