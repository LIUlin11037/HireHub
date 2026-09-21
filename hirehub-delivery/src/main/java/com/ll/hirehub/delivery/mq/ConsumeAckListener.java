package com.ll.hirehub.delivery.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.delivery.entity.LocalMessage;
import com.ll.hirehub.delivery.mapper.LocalMessageMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 消费回执监听（见架构文档 §6.2 步骤④）。
 * <p>
 * 消费者处理成功后发一条 ACK 消息回来，delivery 据此把本地消息置为「已确认（status=2）」。
 * 没有这条回执通道，对账任务就无法区分「消息还在路上」和「消息其实没人消费」。
 * <p>
 * 注意：写库失败时直接丢弃该回执而不是重回队列——
 * 本地消息会停留在 status=1，由对账任务重新投递业务消息，
 * 消费端幂等会挡住重复业务，届时自然产生新的回执。这样避免无限重投同一条回执。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumeAckListener {

    private final LocalMessageMapper localMessageMapper;

    @RabbitListener(queues = MqConst.QUEUE_DELIVERY_ACK)
    public void onConsumeAck(MqPayload.ConsumeAck ack, Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (ack != null && ack.getMessageId() != null) {
                localMessageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                        .eq(LocalMessage::getMessageId, ack.getMessageId())
                        .set(LocalMessage::getStatus, LocalMessage.STATUS_CONFIRMED)
                        .set(LocalMessage::getAckTime, LocalDateTime.now()));
                log.debug("收到消费回执: messageId={}, consumer={}", ack.getMessageId(), ack.getConsumer());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理消费回执失败，丢弃该回执待对账补偿: {}", e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
