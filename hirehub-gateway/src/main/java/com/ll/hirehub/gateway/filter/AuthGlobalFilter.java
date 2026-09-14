package com.ll.hirehub.gateway.filter;

import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 网关统一鉴权（见架构文档 §6.1）：
 *   白名单放行 → 校验 JWT → 注入 X-User-Id / X-User-Roles → 放行
 * 下游服务信任内网请求头，不再重复解析 JWT。
 * X-Company-Id 由前端传入并原样转发（网关不注入），见 D-06。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Set<String> WHITE_LIST = Set.of(
            "/api/auth/register",
            "/api/auth/login"
    );

    private final JwtUtil jwtUtil;

    public AuthGlobalFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (WHITE_LIST.contains(path)) {
            return chain.filter(exchange);
        }

        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(exchange, ResultCode.UNAUTHORIZED);
        }

        String token = auth.substring(7);
        try {
            Claims claims = jwtUtil.parseToken(token);
            String userId = claims.getSubject();
            List<String> roles = readRoles(claims);

            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Id", userId)
                    .header("X-User-Roles", String.join(",", roles))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            return unauthorized(exchange, ResultCode.TOKEN_INVALID);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readRoles(Claims claims) {
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, ResultCode resultCode) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + resultCode.getCode()
                + ",\"message\":\"" + resultCode.getMessage() + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
