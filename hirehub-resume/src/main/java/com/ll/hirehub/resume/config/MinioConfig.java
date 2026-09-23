package com.ll.hirehub.resume.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端（resume 服务，简历附件，见架构文档 §6.6 / D-08）。
 * <p>
 * 业务服务只负责「鉴权 + 发预签名 URL + 存元数据」，不承载文件流——
 * 大文件由前端直传 MinIO。
 */
@Configuration
public class MinioConfig {

    @Value("${hirehub.minio.endpoint:http://localhost:9010}")
    private String endpoint;

    @Value("${hirehub.minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${hirehub.minio.secret-key:minioadmin}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
