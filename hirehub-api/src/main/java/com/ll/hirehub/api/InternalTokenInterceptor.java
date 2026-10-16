package com.ll.hirehub.api;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feign 内部令牌注入（审计 S-11，与 hirehub-common 的 {@code InternalTokenFilter} 配套）。
 * <p>
 * 所有服务间调用（{@code /internal/**}）统一携带 {@code X-Internal-Token}，
 * 被调服务据此拒绝"绕过网关直连服务端口"的未授权调用。
 * 令牌与校验侧读同一组配置：{@code hirehub.security.internal-token} / {@code HIREHUB_INTERNAL_TOKEN}。
 */
@Component
public class InternalTokenInterceptor implements RequestInterceptor {

    @Value("${hirehub.security.internal-token:${HIREHUB_INTERNAL_TOKEN:hirehub-dev-internal-token}}")
    private String internalToken;

    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Internal-Token", internalToken);
    }
}
