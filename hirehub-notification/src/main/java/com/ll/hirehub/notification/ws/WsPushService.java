package com.ll.hirehub.notification.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 多实例长连接消息路由（见 D-34）。
 * <p>
 * <b>问题</b>：用户 A 的 WebSocket 连在实例 1，而"给 A 发通知"这件事可能发生在实例 2
 * （MQ 消费者落在哪个实例是随机的）。实例 2 查本地会话表必然查不到 A。
 * <p>
 * <b>方案对比</b>：
 * <ul>
 *   <li><b>MQ 按实例维度路由</b>：每个实例一个专属队列，投递时要知道"A 在哪个实例"——
 *       需要维护全局在线表，且实例扩缩容时要处理路由表失效。</li>
 *   <li><b>Redis Pub/Sub 广播</b>（本实现）：所有实例订阅同一个 channel，
 *       收到后各自检查本地有没有该用户的连接，有就推。实例间不需要互相知道，
 *       代价是每个实例都会收到与自己无关的消息——在本项目规模下这个成本可以忽略。</li>
 * </ul>
 * <b>已知取舍</b>：Redis Pub/Sub 是<b>不持久</b>的（fire-and-forget）。这在本场景可以接受——
 * 通知已经先落库，推送只是"让在线用户立刻看到"；没推到的用户下次拉列表照样能看到。
 * 若要求"绝不丢推送"，就该换成 Redis Stream / 每实例一个 MQ 队列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WsPushService implements MessageListener {

    /** 广播频道 */
    public static final String CHANNEL = "hirehub:ws:push";

    private final StringRedisTemplate redis;
    private final WsSessionRegistry registry;
    private final ObjectMapper objectMapper;

    /** 把一条消息推给指定用户（先广播到所有实例，由持有连接的实例真正下发） */
    public void pushToUser(Long userId, Object payload) {
        if (userId == null) {
            return;
        }
        try {
            WsPushEnvelope envelope = new WsPushEnvelope(userId,
                    objectMapper.writeValueAsString(payload));
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            // 推送失败不影响主流程：通知已经落库，用户下次拉列表仍能看到
            log.warn("WebSocket 推送广播失败: userId={} err={}", userId, e.getMessage());
        }
    }

    /** 各实例订阅回调：只推给"连在本实例上"的连接 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            WsPushEnvelope envelope = objectMapper.readValue(body, WsPushEnvelope.class);
            int delivered = registry.push(envelope.getUserId(), envelope.getJson());
            if (delivered == 0) {
                log.debug("用户 {} 不在本实例，交由其它实例处理", envelope.getUserId());
            }
        } catch (Exception e) {
            log.warn("处理 WebSocket 广播消息失败: {}", e.getMessage());
        }
    }

    /** 广播信封：{@code userId} + 要下发的 JSON 文本 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WsPushEnvelope {
        private Long userId;
        private String json;
    }
}
