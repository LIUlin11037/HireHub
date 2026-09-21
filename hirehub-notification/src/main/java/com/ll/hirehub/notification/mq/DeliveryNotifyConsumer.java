package com.ll.hirehub.notification.mq;

import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 站内通知消费者（手动 ACK + 幂等去重，见架构文档 §6.2 步骤③ / §6.4）。
 * <p>
 * 一期这个链路是断的——{@code /internal/notify} 全项目无人调用，通知列表恒为空。
 * 二期由本地消息表 + MQ 打通：业务事件落库即产生通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryNotifyConsumer {

    private final DeliveryNotifyService deliveryNotifyService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = MqConst.QUEUE_NOTIFICATION)
    public void onDeliveryMessage(MqMessage message, Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            boolean processed = deliveryNotifyService.handle(message);
            if (!processed) {
                log.info("通知消息重复，跳过: messageId={}", message.getMessageId());
            } else {
                publishConsumeAck(message.getMessageId());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理通知消息失败，重新入队: messageId={}, err={}", message.getMessageId(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void publishConsumeAck(String messageId) {
        MqPayload.ConsumeAck ack = new MqPayload.ConsumeAck();
        ack.setMessageId(messageId);
        ack.setConsumer(MqConst.Consumer.NOTIFICATION);
        ack.setSuccess(true);
        rabbitTemplate.convertAndSend(MqConst.EXCHANGE, MqConst.RK_CONSUME_ACK, ack);
    }
}
