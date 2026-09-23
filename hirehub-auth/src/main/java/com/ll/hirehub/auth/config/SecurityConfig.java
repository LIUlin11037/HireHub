package com.ll.hirehub.auth.config;

import com.ll.hirehub.common.security.GatewayTrustSecuritySupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 方法级鉴权（见 D-07）：{@code /api/auth/admin/**} 统一 {@code @PreAuthorize("hasRole('PLATFORM_ADMIN')")}。
 * 过滤器链全部 permitAll —— 鉴权边界在网关，详见 {@link GatewayTrustSecuritySupport}。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain hirehubSecurityFilterChain(HttpSecurity http) throws Exception {
        return GatewayTrustSecuritySupport.build(http);
    }
}
