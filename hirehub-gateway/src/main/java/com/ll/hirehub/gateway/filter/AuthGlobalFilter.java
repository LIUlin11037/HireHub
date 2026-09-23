package com.ll.hirehub.gateway.filter;

import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
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
 *   白名单放行 → 校验 JWT → 查 jti 黑名单 → 注入 X-User-Id / X-User-Roles → 放行
 * 下游服务信任内网请求头，不再重复解析 JWT。
 * <p>
 * 三期新增（D-02）：
 * <ul>
 *   <li><b>黑名单校验</b>：登出/撤销后的 access token 必须立即失效——JWT 本身无状态，
 *       所以这里查一次 Redis。用<b>响应式</b> Redis（{@link ReactiveStringRedisTemplate}），
 *       绝不能用阻塞客户端：网关跑在 Netty 事件循环上，一个阻塞调用会拖住整条连接。</li>
 *   <li><b>凭证类型校验</b>：只接受 access token，refresh token 不能当访问凭证用。</li>
 *   <li><b>X-Trace-Id 回传</b>：把链路 ID 透出到响应头，排障时客户端可直接引用。</li>
 * </ul>
 * X-Company-Id 由前端传入并原样转发（网关不注入），见 D-06。
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String BLACKLIST_KEY = "auth:blacklist:%s";
    private static final String TRACE_HEADER = "X-Trace-Id";

    /**
     * WebSocket 握手前缀（见 D-34）：浏览器 WebSocket API <b>不能自定义请求头</b>，
     * 所以这里拿不到 Authorization，只能放行握手，改由 notification 服务用
     * 一次性 ticket（{@code ?ticket=xxx}）自鉴权。放行的只是握手，
     * 没有有效 ticket 的连接会被服务端立即关闭。
     */
    private static final String WS_PREFIX = "/ws/";

    private static final Set<String> WHITE_LIST = Set.of(
            "/api/auth/register",
            "/api/auth/login",
            // access token 已过期，无法要求 Authorization 有效（见 D-02）
            "/api/auth/refresh",
            // 法人授权：法人没有平台账号，凭证是 company 服务签发的一次性令牌（见 D-24 第三层）
            "/api/company/authorize"
    );

    private final JwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redis;

    /**
     * 用 ObjectProvider 而不是直接注入 Tracer：tracing 是<b>旁路能力</b>，
     * 缺了它不该让网关起不来（曾经因为没引 actuator 导致 Tracer bean 缺失、
     * 网关启动直接失败，见踩坑记录 #19）。
     */
    private final ObjectProvider<Tracer> tracerProvider;

    public AuthGlobalFilter(JwtUtil jwtUtil, ReactiveStringRedisTemplate redis,
                            ObjectProvider<Tracer> tracerProvider) {
        this.jwtUtil = jwtUtil;
        this.redis = redis;
        this.tracerProvider = tracerProvider;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exposeTraceId(exchange);

        String path = exchange.getRequest().getURI().getPath();
        if (WHITE_LIST.contains(path) || path.startsWith(WS_PREFIX)) {
            return chain.filter(exchange);
        }

        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, ResultCode.UNAUTHORIZED);
        }

        Claims claims;
        try {
            claims = jwtUtil.parseToken(auth.substring(7));
        } catch (Exception e) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, ResultCode.TOKEN_INVALID);
        }
        if (!jwtUtil.isAccessToken(claims)) {
            // refresh token 只能用于 /api/auth/refresh，不能直接访问业务接口
            return reject(exchange, HttpStatus.UNAUTHORIZED, ResultCode.TOKEN_INVALID);
        }

        String userId = claims.getSubject();
        List<String> roles = readRoles(claims);
        String jti = claims.getId();

        return isBlacklisted(jti).flatMap(blacklisted -> {
            if (blacklisted) {
                return reject(exchange, HttpStatus.UNAUTHORIZED, ResultCode.UNAUTHORIZED);
            }
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Id", userId)
                    .header("X-User-Roles", String.join(",", roles))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        });
    }

    /**
     * 查 jti 是否已被登出拉黑。
     * <p>
     * <b>Redis 不可用时 fail-closed（拒绝请求）</b>：撤销是安全承诺，宁可短时不可用，
     * 也不能让已登出的凭证继续通行。这条取舍是刻意的，不要"顺手优化"成放行。
     */
    private Mono<Boolean> isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return Mono.just(false);   // 历史 token 没有 jti，无从撤销
        }
        return redis.hasKey(String.format(BLACKLIST_KEY, jti))
                .onErrorResume(e -> {
                    log.error("黑名单校验失败，按 fail-closed 拒绝请求: {}", e.getMessage());
                    return Mono.just(true);
                });
    }

    /** 把当前链路的 traceId 写进响应头，便于排查问题（见架构文档 §8.4） */
    private void exposeTraceId(ServerWebExchange exchange) {
        exchange.getResponse().beforeCommit(() -> {
            Tracer tracer = tracerProvider.getIfAvailable();
            if (tracer != null) {
                Span span = tracer.currentSpan();
                if (span != null) {
                    exchange.getResponse().getHeaders().set(TRACE_HEADER, span.context().traceId());
                }
            }
            return Mono.empty();
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> readRoles(Claims claims) {
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, ResultCode resultCode) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + resultCode.getCode()
                + ",\"message\":\"" + resultCode.getMessage() + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 必须晚于 Sentinel 限流过滤器（后者是 HIGHEST_PRECEDENCE），
        // 否则未认证请求先被 401，限流永远测不到；见 SentinelGatewayConfig。
        return -100;
    }
}
