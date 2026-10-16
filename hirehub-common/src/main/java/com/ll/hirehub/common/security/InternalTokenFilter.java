package com.ll.hirehub.common.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口令牌校验（审计 S-11）。
 * <p>
 * {@code /internal/**} 是服务间 Feign 专用端点，网关不路由它们——但服务端口可直连，
 * 此前任何能到达服务端口的调用方都能无凭证调用（伪造通知 / 拖取投递数据）。
 * 现在要求调用方携带 {@code X-Internal-Token}，由 Feign 拦截器统一注入
 * （{@code hirehub-api} 模块的 {@code InternalTokenInterceptor}）。
 * <p>
 * 令牌由 {@code HIREHUB_INTERNAL_TOKEN} 注入；默认值仅用于本地开发。
 * 这只是网络隔离（D-33）之外的第二层防线，不替代安全组/网络策略。
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    /** 本地开发默认令牌（生产必须用 HIREHUB_INTERNAL_TOKEN 覆盖） */
    public static final String DEV_TOKEN = "hirehub-dev-internal-token";

    @Value("${hirehub.security.internal-token:${HIREHUB_INTERNAL_TOKEN:" + DEV_TOKEN + "}}")
    private String internalToken;

    @PostConstruct
    void warnIfDevToken() {
        if (DEV_TOKEN.equals(internalToken)) {
            log.warn("/internal 接口正在使用开发默认令牌，仅限本地开发，生产请注入 HIREHUB_INTERNAL_TOKEN");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String presented = request.getHeader(HEADER);
        // 常量时间比较，防时序探测
        if (presented == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8))) {
            log.warn("/internal 调用缺少或携带错误的内部令牌: path={} from={}", path, request.getRemoteAddr());
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":403,\"message\":\"forbidden\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
