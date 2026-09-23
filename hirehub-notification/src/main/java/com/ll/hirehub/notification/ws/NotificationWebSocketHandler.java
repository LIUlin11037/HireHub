package com.ll.hirehub.notification.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 站内消息 WebSocket 处理器（见 D-34）。
 * <p>
 * 连接的<b>唯一鉴权入口是查询串里的一次性 ticket</b>：浏览器 WebSocket API 不能自定义请求头，
 * 所以网关拿不到 Authorization，只能把 {@code /ws/**} 放白名单，由本处理器用 ticket 换 userId。
 * <p>
 * 协议（JSON 文本帧）：
 * <pre>
 *   客户端 → 服务端   {"type":"READ","notificationId":123}   标记已读并回推未读数
 *   客户端 → 服务端   {"type":"PING"}
 *   服务端 → 客户端   {"type":"CONNECTED","payload":{"userId":1}}
 *                     {"type":"NOTIFICATION","payload":{...}}
 *                     {"type":"UNREAD","payload":{"count":3}}
 *                     {"type":"PONG"}
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    private static final String ATTR_USER_ID = "userId";

    private final WsTicketService ticketService;
    private final WsSessionRegistry registry;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = resolveUserId(session);
        if (userId == null) {
            // 票据无效/已用过：直接关掉，不给匿名连接任何能力
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("invalid or expired ticket"));
            return;
        }
        session.getAttributes().put(ATTR_USER_ID, userId);
        registry.register(userId, session);
        send(session, Map.of("type", "CONNECTED", "payload", Map.of("userId", userId)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText("");
            if ("READ".equalsIgnoreCase(type)) {
                long notificationId = node.path("notificationId").asLong();
                if (notificationId > 0) {
                    notificationService.read(userId, notificationId);
                    pushUnread(userId);
                }
            } else if ("PING".equalsIgnoreCase(type)) {
                send(session, Map.of("type", "PONG"));
            } else {
                log.debug("未知 WS 消息类型，忽略: {}", type);
            }
        } catch (Exception e) {
            log.warn("处理 WS 消息失败: userId={} err={}", userId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = currentUserId(session);
        if (userId != null) {
            registry.unregister(userId, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Long userId = currentUserId(session);
        log.warn("WebSocket 传输错误: userId={} sessionId={} err={}",
                userId, session.getId(), exception.getMessage());
        if (userId != null) {
            registry.unregister(userId, session);
        }
    }

    /**
     * 心跳保活：定时发 PING。
     * 作用是防止中间代理/NAT 掐掉空闲连接，并借发送失败清理已死连接
     * （真正的半开检测需要 PONG 超时跟踪，见 {@link WsSessionRegistry#broadcast}）。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void heartbeat() {
        if (registry.onlineUsers() == 0) {
            return;
        }
        // 往每个在线用户的所有连接上发 PING；send 失败会被 registry 顺手关掉
        registry.broadcast(buildPing());
    }

    private String buildPing() {
        return "{\"type\":\"PING\"}";
    }

    private Long currentUserId(WebSocketSession session) {
        Object userId = session.getAttributes().get(ATTR_USER_ID);
        return userId instanceof Long value ? value : null;
    }

    private Long resolveUserId(WebSocketSession session) {
        URI uri = session.getUri();
        String query = uri == null ? null : uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "ticket".equals(pair.substring(0, eq))) {
                String ticket = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                return ticketService.consume(ticket).orElse(null);
            }
        }
        return null;
    }

    private void pushUnread(Long userId) {
        try {
            Long count = notificationService.unreadCount(userId);
            // READ 是这条连接自己发起的动作，连接就在本实例，直接本地推即可
            registry.push(userId, objectMapper.writeValueAsString(
                    Map.of("type", "UNREAD", "payload", Map.of("count", count))));
        } catch (Exception e) {
            log.warn("回推未读数失败: userId={} err={}", userId, e.getMessage());
        }
    }

    private void send(WebSocketSession session, Object payload) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            log.warn("WS 发送失败: sessionId={} err={}", session.getId(), e.getMessage());
        }
    }
}
