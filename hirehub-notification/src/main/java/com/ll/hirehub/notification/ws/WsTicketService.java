package com.ll.hirehub.notification.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * WebSocket 一次性票据（见 D-34）。
 * <p>
 * <b>为什么不能把 accessToken 直接放 URL</b>：浏览器 WebSocket API <b>不能自定义请求头</b>，
 * 凭证只能挂在查询串上；而 URL 会被网关日志、Nginx access log、浏览器历史记录留下来。
 * 换成「短时 + 一次性」的票据后，即使被记录，泄漏窗口也只有几十秒且已被消费。
 * <p>
 * 流程：
 * <pre>
 *   POST /api/notification/ws-ticket   （走网关，身份由 X-User-Id 提供）
 *        → 生成随机 ticket，Redis 存 ticket → userId，TTL 60s
 *   客户端连 ws://.../ws/notification?ticket=xxx
 *        → 服务端 GETDEL 消费（一次即废），拿到 userId
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WsTicketService {

    private static final String KEY_PREFIX = "ws:ticket:";

    private final StringRedisTemplate redis;

    @Value("${hirehub.ws.ticket-ttl-seconds:60}")
    private long ticketTtlSeconds;

    /** 签发票据（调用方已通过网关鉴权，userId 可信） */
    public String issue(Long userId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(KEY_PREFIX + ticket, String.valueOf(userId),
                Duration.ofSeconds(ticketTtlSeconds));
        return ticket;
    }

    /**
     * 消费票据：拿到 userId 的同时删除（一次性）。
     * <p>
     * 用 GETDEL 而不是 GET + DEL，是为了避免并发下同一个票据被两次读取都成功——
     * 竞态修复成本远低于多写一行。
     */
    public Optional<Long> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String userId = redis.opsForValue().getAndDelete(KEY_PREFIX + ticket);
        if (userId == null) {
            log.info("ws-ticket 无效或已被使用");
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(userId));
        } catch (NumberFormatException e) {
            log.warn("ws-ticket 内容异常: {}", userId);
            return Optional.empty();
        }
    }
}
