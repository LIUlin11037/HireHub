package com.ll.hirehub.interview.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ll.hirehub.interview.entity.LocalMessage;
import com.ll.hirehub.interview.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * publisher confirm 回调（复用 D-01 步骤②）。
 * 只靠 {@code convertAndSend} 不抛异常不足以确认到达 broker，必须开 confirm。
 * <p>
 * 注意：面试提醒（InterviewReminderService.fire）不带 CorrelationData，
 * 进来时 correlationData=null 直接忽略，不会误写本地消息表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageConfirmCallback implements RabbitTemplate.ConfirmCallback {

    /** 面试提醒消息的 CorrelationData 前缀（审计 R-03），与本地消息表的 messageId 区分 */
    public static final String REMINDER_PREFIX = "remind:";

    /** 指数退避基数：第 n 次重试等 2^n 秒 */
    private static final int BACKOFF_BASE_SECONDS = 2;

    private final LocalMessageMapper localMessageMapper;
    private final InterviewReminderService reminderService;

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null || correlationData.getId() == null) {
            return;
        }
        String messageId = correlationData.getId();
        // 审计 R-03：面试提醒的 confirm —— ack 置「已发」，nack 回退「待发」由兜底扫描重发
        if (messageId.startsWith(REMINDER_PREFIX)) {
            try {
                Long reminderId = Long.valueOf(messageId.substring(REMINDER_PREFIX.length()));
                reminderService.settleOnConfirm(reminderId, ack);
            } catch (NumberFormatException e) {
                log.warn("无法解析的提醒 confirm 回调: {}", messageId);
            }
            return;
        }
        if (ack) {
            // 带状态条件：只对「还没被标记过」的消息生效，迟到的重复 confirm 无副作用
            localMessageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                    .eq(LocalMessage::getMessageId, messageId)
                    .eq(LocalMessage::getStatus, LocalMessage.STATUS_PENDING)
                    .set(LocalMessage::getStatus, LocalMessage.STATUS_SENT)
                    .set(LocalMessage::getSendTime, LocalDateTime.now())
                    .set(LocalMessage::getErrorMsg, null));
            log.debug("本地消息已确认到达 broker: {}", messageId);
            return;
        }
        // nack：broker 明确拒收（如队列不存在、磁盘告警），走退避重试
        log.warn("本地消息投递被 broker 拒绝: messageId={}, cause={}", messageId, cause);
        backoff(messageId, cause);
    }

    /** 投递失败：重试次数 +1，指数退避；超过上限置为失败等待人工介入 */
    void backoff(String messageId, String cause) {
        LocalMessage message = localMessageMapper.selectOne(
                new LambdaQueryWrapper<LocalMessage>().eq(LocalMessage::getMessageId, messageId));
        if (message == null) {
            return;
        }
        int retry = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
        LambdaUpdateWrapper<LocalMessage> update = new LambdaUpdateWrapper<LocalMessage>()
                .eq(LocalMessage::getMessageId, messageId)
                .set(LocalMessage::getRetryCount, retry)
                .set(LocalMessage::getErrorMsg, cause == null ? "broker nack" : truncate(cause));
        if (retry > LocalMessage.MAX_RETRY) {
            update.set(LocalMessage::getStatus, LocalMessage.STATUS_FAILED);
            log.error("本地消息重试超限，置为失败待人工排查: messageId={}, retry={}", messageId, retry);
        } else {
            update.set(LocalMessage::getNextRetryTime,
                    LocalDateTime.now().plusSeconds((long) Math.pow(BACKOFF_BASE_SECONDS, retry)));
        }
        localMessageMapper.update(null, update);
    }

    private String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
