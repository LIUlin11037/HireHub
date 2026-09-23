package com.ll.hirehub.notification.config;

import com.ll.hirehub.notification.ws.NotificationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册（见 D-34）。
 * <p>
 * 路径放在 {@code /ws/**} 而不是 {@code /api/**}：网关只对 {@code /api/**} 做统一鉴权，
 * 而 WebSocket 握手拿不到 Authorization 头（浏览器限制），必须走 {@code /ws/**} 白名单 +
 * 一次性 ticket 自鉴权。这条边界要在网关和本服务的路由配置里保持一致。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final NotificationWebSocketHandler notificationWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notificationWebSocketHandler, "/ws/notification")
                // 用 allowedOriginPatterns 而不是 allowedOrigins("*")：
                // 后者与携带凭证的请求不兼容，浏览器会直接拒握手
                .setAllowedOriginPatterns("*");
    }
}
