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
 * <p>
 * <b>为什么需要两个客户端</b>：预签名 URL 里的主机名会参与签名，
 * 而"服务怎么连 MinIO"和"客户端怎么连 MinIO"<b>未必是同一个地址</b>：
 * <ul>
 *   <li>容器里服务访问 MinIO 是 {@code host.docker.internal:9010}；</li>
 *   <li>但拿到预签名 URL 的是<b>浏览器/宿主机</b>，它解析不了
 *       {@code host.docker.internal} —— 实测报
 *       {@code The remote name could not be resolved: 'host.docker.internal'}。</li>
 * </ul>
 * 签名包含 Host，所以<b>签发后换主机名会让签名失效</b>，只能在签发时就用对地址。
 * 因此拆成两个客户端：{@code minioClient} 走内部地址做读写，
 * {@link PresignClient} 走对外地址只做签发。
 * 单体部署（两边都是 localhost）时两者等价，不需要额外配置。
 */
@Configuration
public class MinioConfig {

    @Value("${hirehub.minio.endpoint:http://localhost:9010}")
    private String endpoint;

    /**
     * 预签名 URL 里要给客户端使用的主机名。留空则与 {@link #endpoint} 相同。
     * 容器化时必须显式设置（如 {@code http://localhost:9010}）。
     */
    @Value("${hirehub.minio.public-endpoint:}")
    private String publicEndpoint;

    @Value("${hirehub.minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${hirehub.minio.secret-key:minioadmin}")
    private String secretKey;

    /**
     * 签名所用 region。<b>必须显式钉住</b>：
     * 不设时 SDK 可能去问服务端要 region（发一次网络请求），
     * 而"对外地址"从服务内部未必连得通 —— 实测报
     * {@code Failed to connect to localhost/127.0.0.1:9010: Connection refused}，
     * 表现为接口返回「生成上传链接失败」。钉住后签发是纯本地运算。
     * MinIO 未配置 {@code MINIO_REGION} 时默认接受 {@code us-east-1}。
     */
    @Value("${hirehub.minio.region:us-east-1}")
    private String region;

    /** 服务端自身读写用（建桶、下载附件解析） */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint).region(region)
                .credentials(accessKey, secretKey).build();
    }

    /**
     * 只用于 {@code getPresignedObjectUrl} 的客户端。
     * <p>
     * 用独立包装类型而不是再暴露一个 {@code MinioClient} bean：
     * 同类型两个 bean 会让按类型注入变成歧义（需要 {@code @Primary}/{@code @Qualifier}，
     * 而 Lombok 的构造器注入不会自动复制字段上的 {@code @Qualifier}）。
     */
    @Bean
    public PresignClient minioPresignClient() {
        String target = (publicEndpoint == null || publicEndpoint.isBlank()) ? endpoint : publicEndpoint;
        return new PresignClient(MinioClient.builder()
                .endpoint(target).region(region)
                .credentials(accessKey, secretKey).build());
    }

    /** 预签名专用客户端包装 */
    public record PresignClient(MinioClient client) {
    }
}
