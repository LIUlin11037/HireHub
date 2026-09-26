package com.ll.hirehub.interview.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 延迟队列轮询器（见 D-38）：把 Redis ZSet 里到点的面试提醒取出来触发。
 * <p>
 * 轮询间隔默认 1 秒 —— 面试提醒按分钟算，1 秒的精度绰绰有余。
 * 之所以不用更"实时"的机制（如 RabbitMQ + 消费者、或 Redis keyspace notification）：
 * 这个场景对精度不敏感，而对**不丢、不重、不堵**敏感，简单轮询最符合这一点。
 * <p>
 * 与 {@code InterviewReminderScanner} 的分工：本类是**主路径**（到点即发，秒级）；
 * 扫描器是**兜底**（5 分钟一轮，补"延迟项丢了/认领后崩溃"的情况）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewReminderPoller {

    /** 一轮最多处理多少条，避免突发时长时间占用 */
    private static final int BATCH = 200;

    private final RedisDelayQueue delayQueue;
    private final InterviewReminderService reminderService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${hirehub.interview.delay.poll-interval-ms:1000}")
    public void poll() {
        try {
            List<String> due = delayQueue.pollDue(System.currentTimeMillis(), BATCH);
            for (String member : due) {
                String json = delayQueue.takePayload(member);
                if (json == null) {
                    log.warn("延迟项缺少载荷，忽略: member={}", member);
                    continue;
                }
                try {
                    reminderService.onReminderDue(
                            objectMapper.readValue(json, MqPayload.InterviewRemind.class));
                } catch (Exception e) {
                    // 单条失败不影响同批其它：提醒行仍是 PENDING，兜底扫描会补发
                    log.warn("触发面试提醒失败（兜底扫描会补发）: member={} err={}", member, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("轮询延迟队列失败（下轮重试）: {}", e.getMessage());
        }
    }
}
