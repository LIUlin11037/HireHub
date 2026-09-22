package com.ll.hirehub.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

import java.util.Collections;
import java.util.List;

/**
 * Spring Cloud Gateway 路由级 Sentinel 限流（见架构文档 §8.3）。
 * <p>
 * 为什么必须手写这个配置类：SCA 的 {@code spring-cloud-starter-alibaba-sentinel} 在网关应用里
 * <b>不会</b> 引入路由级适配器（依赖树里只有 webflux-adapter），只加 starter 的话规则形同虚设。
 * 需要显式声明 {@code sentinel-spring-cloud-gateway-adapter} 并注册下面的 Filter / 异常处理器。
 * <p>
 * 规则来源：Nacos dataId {@code hirehub-gateway-flow}，类型 {@code gw-flow}
 * （网关用 GatewayFlowRule，不是普通 FlowRule——后者对路由不生效）。
 */
@Configuration
public class SentinelGatewayConfig {

    private static final String BLOCK_BODY =
            "{\"code\":30001,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null,\"traceId\":null}";

    /** 被限流时的响应统一成 Result 契约 + 429，而不是默认那段纯文本 */
    @PostConstruct
    public void initBlockHandler() {
        GatewayCallbackManager.setBlockHandler((exchange, t) ->
                ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(BLOCK_BODY));
    }

    /** 路由级限流过滤器：对所有路由生效，优先级最高，尽早拦截 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public GlobalFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    /** 把 Sentinel 的 BlockException 转成 HTTP 响应 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler(
            ObjectProvider<List<ViewResolver>> viewResolversProvider,
            ServerCodecConfigurer serverCodecConfigurer) {
        return new SentinelGatewayBlockExceptionHandler(
                viewResolversProvider.getIfAvailable(Collections::emptyList), serverCodecConfigurer);
    }
}
