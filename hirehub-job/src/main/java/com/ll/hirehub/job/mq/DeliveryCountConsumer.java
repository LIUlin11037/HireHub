package com.ll.hirehub.job.mq;

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
 * 「投递创建」消费者（手动 ACK + 幂等去重，见架构文档 §6.2 步骤③）。
 * <p>
 * ACK 顺序：业务事务提交成功 → 发消费回执 → basicAck。
 * 任一步失败都 nack 重入队；重复消费由 mq_consume_log 挡住。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryCountConsumer {

    private final DeliveryCountService deliveryCountService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = MqConst.QUEUE_JOB_DELIVERY)
    public void onDeliveryCreated(MqMessage message, Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            boolean processed = deliveryCountService.handle(message);
            if (!processed) {
                log.info("投递创建消息重复，跳过: messageId={}", message.getMessageId());
            } else {
                publishConsumeAck(message.getMessageId());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理投递创建消息失败，重新入队: messageId={}, err={}", message.getMessageId(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /** 回执让 delivery 把本地消息置为「已确认」，对账任务据此判断消息是否真的被消费 */
    private void publishConsumeAck(String messageId) {
        MqPayload.ConsumeAck ack = new MqPayload.ConsumeAck();
        ack.setMessageId(messageId);
        ack.setConsumer(MqConst.Consumer.JOB_DELIVERY_COUNT);
        ack.setSuccess(true);
        rabbitTemplate.convertAndSend(MqConst.EXCHANGE, MqConst.RK_CONSUME_ACK, ack);
    }
}
