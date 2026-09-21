package com.ll.hirehub.delivery.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.delivery.entity.LocalMessage;
import com.ll.hirehub.delivery.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表的三个后台任务（见架构文档 §6.2 步骤②⑤）。
 * <ol>
 *   <li>{@link #relayPending()} 每 5s：把待发送消息投到 MQ</li>
 *   <li>{@link #reconcile()} 每小时：把「已发送但迟迟没有消费回执」的重新投递</li>
 *   <li>{@link #cleanup()} 每天凌晨：清理已确认的过期消息</li>
 * </ol>
 * 三个任务都用 Redis 锁防多实例并发——多副本部署时同一时刻只允许一个实例执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageRelay {

    private static final String LOCK_KEY = "lock:local-message";
    /** 单次扫描上限，避免一次拉太多把内存和 broker 打满 */
    private static final int BATCH_SIZE = 100;
    /** 超过这个时间还没收到消费回执，认为可能丢了，重新投递 */
    private static final Duration RECONCILE_DELAY = Duration.ofMinutes(5);
    /** 已确认消息保留天数（便于排查，之后物理删除） */
    private static final int RETAIN_DAYS = 7;

    private final LocalMessageMapper localMessageMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RedisLock redisLock;
    private final LocalMessageConfirmCallback confirmCallback;

    /** 步骤②：定时把待发送的消息投出去 */
    @Scheduled(fixedDelayString = "${hirehub.mq.relay-interval-ms:5000}")
    public void relayPending() {
        if (!redisLock.tryLock(LOCK_KEY + ":relay", Duration.ofSeconds(30))) {
            return;
        }
        List<LocalMessage> pending = localMessageMapper.selectList(new LambdaQueryWrapper<LocalMessage>()
                .eq(LocalMessage::getStatus, LocalMessage.STATUS_PENDING)
                .le(LocalMessage::getNextRetryTime, LocalDateTime.now())
                .orderByAsc(LocalMessage::getId)
                .last("LIMIT " + BATCH_SIZE));
        for (LocalMessage message : pending) {
            dispatch(message);
        }
    }

    /** 步骤⑤：对账补偿——发送成功但消费端没回执的，重新投递 */
    @Scheduled(cron = "0 0 * * * ?")
    public void reconcile() {
        if (!redisLock.tryLock(LOCK_KEY + ":reconcile", Duration.ofMinutes(5))) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.now().minus(RECONCILE_DELAY);
        List<LocalMessage> stuck = localMessageMapper.selectList(new LambdaQueryWrapper<LocalMessage>()
                .eq(LocalMessage::getStatus, LocalMessage.STATUS_SENT)
                .lt(LocalMessage::getSendTime, deadline)
                .orderByAsc(LocalMessage::getId)
                .last("LIMIT " + BATCH_SIZE));
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("对账发现 {} 条已发送但无消费回执的本地消息，准备重新投递", stuck.size());
        for (LocalMessage message : stuck) {
            int retry = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
            if (retry > LocalMessage.MAX_RETRY) {
                localMessageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                        .eq(LocalMessage::getId, message.getId())
                        .set(LocalMessage::getStatus, LocalMessage.STATUS_FAILED)
                        .set(LocalMessage::getRetryCount, retry)
                        .set(LocalMessage::getErrorMsg, "对账重投超限：消费端始终未回执"));
                log.error("本地消息置为失败待人工排查: messageId={}", message.getMessageId());
                continue;
            }
            // 打回待发送，由 relayPending 下一轮重新投递（保持「重新投递」逻辑只有一处）
            localMessageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                    .eq(LocalMessage::getId, message.getId())
                    .set(LocalMessage::getStatus, LocalMessage.STATUS_PENDING)
                    .set(LocalMessage::getRetryCount, retry)
                    .set(LocalMessage::getNextRetryTime, LocalDateTime.now())
                    .set(LocalMessage::getErrorMsg, "对账重投"));
        }
    }

    /** 步骤⑤：清理已确认的历史消息 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanup() {
        if (!redisLock.tryLock(LOCK_KEY + ":cleanup", Duration.ofMinutes(10))) {
            return;
        }
        int deleted = localMessageMapper.delete(new LambdaQueryWrapper<LocalMessage>()
                .eq(LocalMessage::getStatus, LocalMessage.STATUS_CONFIRMED)
                .lt(LocalMessage::getUpdateTime, LocalDateTime.now().minusDays(RETAIN_DAYS)));
        log.info("本地消息清理完成，删除 {} 条已确认记录", deleted);
    }

    /** 投递单条消息；同步异常（broker 不可达）也走退避重试 */
    public void dispatch(LocalMessage message) {
        MqMessage mqMessage = new MqMessage();
        mqMessage.setMessageId(message.getMessageId());
        mqMessage.setBizType(message.getBizType());
        mqMessage.setBizId(message.getBizId());
        mqMessage.setPayload(message.getPayload());
        try {
            rabbitTemplate.convertAndSend(MqConst.EXCHANGE, message.getRoutingKey(), mqMessage,
                    new CorrelationData(message.getMessageId()));
        } catch (Exception e) {
            log.warn("本地消息投递异常，进入退避重试: messageId={}, err={}", message.getMessageId(), e.getMessage());
            confirmCallback.backoff(message.getMessageId(), e.getMessage());
        }
    }
}
