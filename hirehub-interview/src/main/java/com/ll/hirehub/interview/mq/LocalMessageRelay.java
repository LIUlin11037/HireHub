package com.ll.hirehub.interview.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.interview.entity.LocalMessage;
import com.ll.hirehub.interview.mapper.LocalMessageMapper;
import com.ll.hirehub.common.constant.RedisKeys;
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
 * 本地消息表的后台任务（审计 R-02，复用 D-01 步骤②⑤）：
 * relay 每 5s 投递待发送消息；reconcile 每小时重投「已发送但无回执」；cleanup 每天清理已确认。
 * 三个任务都用 Redis 锁防多实例并发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageRelay {

    private static final String LOCK_KEY = RedisKeys.LOCK_INTERVIEW_LOCAL_MESSAGE;
    /** 单次扫描上限，避免一次拉太多把内存和 broker 打满 */
    private static final int BATCH_SIZE = 100;
    /** 超过这个时间还没收到消费回执，认为可能丢了，重新投递 */
    private static final Duration RECONCILE_DELAY = Duration.ofMinutes(5);
    /** 已确认消息保留天数（便于排查，之后物理删除） */
    private static final int RETAIN_DAYS = 7;
    /** relay 锁 TTL（审计 R-07，与 delivery 侧一致）：覆盖批内 100 条同步 dispatch 的慢 broker 场景 */
    private static final Duration RELAY_LOCK_TTL = Duration.ofSeconds(120);

    private final LocalMessageMapper localMessageMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RedisLock redisLock;
    private final LocalMessageConfirmCallback confirmCallback;

    /** 定时把待发送的消息投出去 */
    @Scheduled(fixedDelayString = "${hirehub.mq.relay-interval-ms:5000}")
    public void relayPending() {
        if (!redisLock.tryLock(LOCK_KEY + ":relay", RELAY_LOCK_TTL)) {
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

    /** 对账补偿——发送成功但消费端没回执的，重新投递 */
    @Scheduled(cron = "0 5 * * * ?")
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
                        .eq(LocalMessage::getStatus, LocalMessage.STATUS_SENT)
                        .set(LocalMessage::getStatus, LocalMessage.STATUS_FAILED)
                        .set(LocalMessage::getRetryCount, retry)
                        .set(LocalMessage::getErrorMsg, "对账重投超限：消费端始终未回执"));
                log.error("本地消息置为失败待人工排查: messageId={}", message.getMessageId());
                continue;
            }
            // 打回待发送（带状态条件，晚到的回执不会被翻回），由 relayPending 下一轮重新投递
            localMessageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                    .eq(LocalMessage::getId, message.getId())
                    .eq(LocalMessage::getStatus, LocalMessage.STATUS_SENT)
                    .set(LocalMessage::getStatus, LocalMessage.STATUS_PENDING)
                    .set(LocalMessage::getRetryCount, retry)
                    .set(LocalMessage::getNextRetryTime, LocalDateTime.now())
                    .set(LocalMessage::getErrorMsg, "对账重投"));
        }
    }

    /** 清理已确认的历史消息 */
    @Scheduled(cron = "0 40 3 * * ?")
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
