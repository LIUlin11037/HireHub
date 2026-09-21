package com.ll.hirehub.delivery.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ll.hirehub.delivery.entity.LocalMessage;
import com.ll.hirehub.delivery.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * publisher confirm 回调（见架构文档 §6.2 步骤②的「成功：status=1」）。
 * <p>
 * 必须开 publisher confirm 才知道消息有没有真的到达 broker——
 * 只靠 {@code convertAndSend} 不抛异常是不够的，那只说明写进了客户端缓冲区。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageConfirmCallback implements RabbitTemplate.ConfirmCallback {

    /** 指数退避基数：第 n 次重试等 2^n 秒 */
    private static final int BACKOFF_BASE_SECONDS = 2;

    private final LocalMessageMapper localMessageMapper;

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null || correlationData.getId() == null) {
            return;
        }
        String messageId = correlationData.getId();
        if (ack) {
            localMessageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                    .eq(LocalMessage::getMessageId, messageId)
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
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LocalMessage>()
                        .eq(LocalMessage::getMessageId, messageId));
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
