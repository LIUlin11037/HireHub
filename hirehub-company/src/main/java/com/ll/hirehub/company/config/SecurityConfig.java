package com.ll.hirehub.company.config;

import com.ll.hirehub.common.security.GatewayTrustSecuritySupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 方法级鉴权（见 D-07）：管理端接口统一用 {@code @PreAuthorize("hasRole('PLATFORM_ADMIN')")}
 * 替代手写 {@code requireAdmin(roles)}。
 * <p>
 * 过滤器链全部 permitAll —— 鉴权边界在网关，这里只负责把网关注入的身份
 * 变成 Security 上下文，供 @PreAuthorize 使用。原因见 {@link GatewayTrustSecuritySupport}。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain hirehubSecurityFilterChain(HttpSecurity http) throws Exception {
        return GatewayTrustSecuritySupport.build(http);
    }
}
