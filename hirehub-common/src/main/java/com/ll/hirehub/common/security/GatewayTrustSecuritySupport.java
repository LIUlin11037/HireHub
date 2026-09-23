package com.ll.hirehub.common.security;

import com.ll.hirehub.common.result.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 下游服务的 Security 过滤器链模板（见 D-02 / D-07）。
 * <p>
 * 每个开启方法级鉴权的服务只要：
 * <pre>
 * &#64;Configuration
 * &#64;EnableMethodSecurity
 * public class SecurityConfig {
 *     &#64;Bean
 *     public SecurityFilterChain chain(HttpSecurity http) throws Exception {
 *         return GatewayTrustSecuritySupport.build(http);
 *     }
 * }
 * </pre>
 * <p>
 * 三个关键点（每一条都是踩过的坑）：
 * <ol>
 *   <li><b>全部 permitAll</b>：鉴权边界在网关，服务侧只做"基于已注入身份的方法级校验"。
 *       如果在这里收权，会把 /internal/** 与网关转发路径一起拦死（引入 Security 后全接口 401 的根因）。</li>
 *   <li><b>STATELESS + 关闭 csrf/formLogin/httpBasic/logout</b>：纯 token 体系，不建会话、不出登录页。</li>
 *   <li><b>异常也要走统一 Result 契约</b>：默认会返回 HTML 错误页，与项目 10xxx/20xxx 口径不一致。</li>
 * </ol>
 */
public final class GatewayTrustSecuritySupport {

    private GatewayTrustSecuritySupport() {
    }

    public static SecurityFilterChain build(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                writeJson(response, HttpStatus.UNAUTHORIZED, ResultCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, e) ->
                                writeJson(response, HttpStatus.FORBIDDEN, ResultCode.FORBIDDEN)))
                .addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 手写 JSON，避免为 common 引入 Jackson —— 它同时被 WebFlux 网关复用。 */
    public static void writeJson(HttpServletResponse response, HttpStatus status, ResultCode code)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + code.getCode()
                + ",\"message\":\"" + code.getMessage() + "\",\"data\":null}");
    }
}
