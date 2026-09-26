package com.ll.hirehub.interview.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 Redis ZSet 的延迟队列（见 D-38）。
 * <p>
 * <b>为什么不用 RabbitMQ 的消息级 TTL</b>：消息级 TTL **只在消息到达队头时才被检查**。
 * 一条"1 天后提醒"排在队头，后面那条"30 分钟后提醒"就得干等一天才死信 ——
 * 这就是队头阻塞（head-of-line blocking），实测踩到过，见踩坑记录 #43。
 * 而 ZSet 用 score 排序，<b>谁到点谁先出</b>，不存在这个问题。
 *
 * <h3>数据结构</h3>
 * <ul>
 *   <li>{@code hirehub:delay:interview-remind}（ZSet）：member = {@code interviewId:tier}，score = 到点毫秒</li>
 *   <li>{@code hirehub:delay:interview-remind:payload}（Hash）：member → 载荷 JSON</li>
 * </ul>
 * member 用 {@code interviewId:tier} 而不是随机值，是为了让**改期天然成为"覆盖"**：
 * 同一个 member 再 {@code ZADD} 一次就把分数改了，不需要知道旧 token。
 *
 * <h3>并发与丢失</h3>
 * <ul>
 *   <li><b>多实例只出一个</b>：{@link #pollDue} 用 {@code ZREM} 的返回值当"认领"依据，
 *       删到 1 条才算抢到，天然避免两个实例同时发。</li>
 *   <li><b>认领后崩溃</b>：延迟项已从 ZSet 移除但通知没发出去 —— 那时
 *       {@code interview_reminder} 仍是 PENDING，由 {@code InterviewReminderScanner} 兜底补发。
 *       宁可让兜底扫，也不要"至少一次"变成"至少两次"。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDelayQueue {

    private static final String ZSET_KEY = "hirehub:delay:interview-remind";
    private static final String PAYLOAD_KEY = "hirehub:delay:interview-remind:payload";

    private final StringRedisTemplate redis;

    /** member 由「面试 id + 档位」决定：改期时同一个 member 覆盖即可 */
    public static String member(Long interviewId, String tier) {
        return interviewId + ":" + tier;
    }

    /** 放入/覆盖一个延迟项 */
    public void offer(Long interviewId, String tier, long atMillis, String payloadJson) {
        String m = member(interviewId, tier);
        redis.opsForHash().put(PAYLOAD_KEY, m, payloadJson);
        redis.opsForZSet().add(ZSET_KEY, m, atMillis);
    }

    /** 作废一个延迟项（面试被取消/拒绝/完成，或改期前先清掉旧档位） */
    public void cancel(Long interviewId, String tier) {
        String m = member(interviewId, tier);
        redis.opsForZSet().remove(ZSET_KEY, m);
        redis.opsForHash().delete(PAYLOAD_KEY, m);
    }

    /**
     * 取出所有已到点的延迟项，并**认领**它们。
     *
     * @return 被本实例认领成功的 member 列表（ZREM 返回 1 才算抢到）
     */
    public List<String> pollDue(long nowMillis, int limit) {
        List<String> claimed = new ArrayList<>();
        Set<String> due = redis.opsForZSet().rangeByScore(ZSET_KEY, 0, nowMillis, 0, limit);
        if (due == null || due.isEmpty()) {
            return claimed;
        }
        for (String m : due) {
            Long removed = redis.opsForZSet().remove(ZSET_KEY, m);
            if (removed != null && removed > 0) {
                claimed.add(m);
            }
        }
        return claimed;
    }

    /** 取走并删除载荷（取出后该延迟项就完整消费掉了） */
    public String takePayload(String member) {
        Object raw = redis.opsForHash().get(PAYLOAD_KEY, member);
        redis.opsForHash().delete(PAYLOAD_KEY, member);
        return raw == null ? null : raw.toString();
    }

    /** 当前待触发的延迟项数量（排障用） */
    public long pendingCount() {
        Long n = redis.opsForZSet().zCard(ZSET_KEY);
        return n == null ? 0L : n;
    }
}
