package com.ll.hirehub.notification.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本实例持有的长连接注册表（见 D-34）。
 * <p>
 * 一个用户可能在多个标签页 / 多个设备上打开连接，所以是 {@code userId → Set<session>}。
 * 用 {@link ConcurrentHashMap} + {@code ConcurrentHashMap.newKeySet()}：WebSocket 回调与
 * 定时心跳、Redis 订阅回调在不同线程上，普通 HashMap/ArrayList 会并发损坏。
 * <p>
 * <b>为什么必须有这个东西</b>：多实例部署下，用户 A 的连接可能在实例 1，
 * 而消息由实例 2 处理。实例 2 查本地注册表必然找不到 A —— 这正是要走 Redis Pub/Sub
 * 广播（各实例收到后各自检查本地有没有该用户连接）的原因。
 */
@Slf4j
@Component
public class WsSessionRegistry {

    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("WebSocket 连接建立: userId={} sessionId={} 该用户连接数={}",
                userId, session.getId(), sessions.get(userId).size());
    }

    public void unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions == null) {
            return;
        }
        userSessions.remove(session);
        if (userSessions.isEmpty()) {
            sessions.remove(userId);   // 避免 Map 无限增长
        }
        log.info("WebSocket 连接关闭: userId={} sessionId={}", userId, session.getId());
    }

    /** 推给本实例上该用户的全部连接；不在本实例则什么都不做（别的实例会处理） */
    public int push(Long userId, String json) {
        Set<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions == null || userSessions.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (WebSocketSession session : userSessions) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                // WebSocketSession 不是线程安全的，发送必须串行化
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
                delivered++;
            } catch (IOException e) {
                log.warn("推送失败，关闭该连接: userId={} sessionId={} err={}",
                        userId, session.getId(), e.getMessage());
                closeQuietly(session);
            }
        }
        return delivered;
    }

    public int onlineUsers() {
        return sessions.size();
    }

    /**
     * 往本实例全部连接广播（心跳用）。
     * <p>
     * 心跳的作用有两个：让中间代理/NAT 不把空闲连接掐掉；以及借发送失败清理已死的连接。
     * <b>它并不能真正检测半开连接</b>——TCP 写成功可能只是进了内核缓冲。要严格检测需要
     * 记录 PONG 超时，那要额外维护「上次收到 PONG 的时间」，本项目没做到那一步。
     */
    public void broadcast(String json) {
        sessions.values().forEach(userSessions -> {
            for (WebSocketSession session : userSessions) {
                if (!session.isOpen()) {
                    continue;
                }
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(json));
                    }
                } catch (IOException e) {
                    closeQuietly(session);
                }
            }
        });
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // 已经断了就算了
        }
    }
}
