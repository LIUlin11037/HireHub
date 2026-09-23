package com.ll.hirehub.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 用网关注入的请求头构建 {@code SecurityContext}（见 D-02 配套设计 / D-07）。
 * <p>
 * <b>为什么需要它</b>：鉴权边界在网关，下游服务不再解析 JWT。但 {@code @PreAuthorize}
 * 依赖 Security 上下文——不手工构建，方法级鉴权要么全部拒绝、要么全部放行（D-02 已预警这个坑）。
 * <p>
 * 安全前提：服务只能被网关访问（内网直连即信任 {@code X-User-Id}）。
 * 所以本过滤器<b>只</b>信任网关注入的头，任何外部请求头伪造都在网关被覆盖/拦截。
 * <p>
 * <b>不加 {@code @Component}</b>：hirehub-common 被 8 个服务（含 WebFlux 网关）复用，
 * 靠注解自动装配会把 Servlet 过滤器塞进网关。由真正开启方法级鉴权的服务在自己的
 * SecurityConfig 里显式 new 出来。
 */
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";

    /** @PreAuthorize("hasRole('X')") 匹配的是 ROLE_X，所以构建权限时要补前缀 */
    public static final String ROLE_PREFIX = "ROLE_";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String userId = request.getHeader(HEADER_USER_ID);
            if (userId != null && !userId.isBlank()
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null,
                                parseRoles(request.getHeader(HEADER_USER_ROLES)));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            filterChain.doFilter(request, response);
        } finally {
            // 线程池复用线程，必须清理，否则会串身份（典型越权来源）
            SecurityContextHolder.clearContext();
        }
    }

    /** {@code X-User-Roles: SEEKER,PLATFORM_ADMIN} → [ROLE_SEEKER, ROLE_PLATFORM_ADMIN] */
    private List<GrantedAuthority> parseRoles(String rolesHeader) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return authorities;
        }
        for (String role : rolesHeader.split(",")) {
            String trimmed = role.trim();
            if (!trimmed.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + trimmed));
            }
        }
        return authorities;
    }
}
