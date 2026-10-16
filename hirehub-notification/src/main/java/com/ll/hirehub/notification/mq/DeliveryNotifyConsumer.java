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
import java.util.List;
import java.util.Map;

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

    /** 审计 R-04：最大重新入队次数，超限进死信（永久性错误不再毫秒级死循环） */
    private static final long MAX_REQUEUE = 3;

    private final DeliveryNotifyService deliveryNotifyService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = MqConst.QUEUE_NOTIFICATION)
    public void onDeliveryMessage(MqMessage message, Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                  @Header(name = "x-death", required = false)
                                  List<Map<String, Object>> xDeath) throws IOException {
        try {
            boolean processed = deliveryNotifyService.handle(message);
            if (!processed) {
                log.info("通知消息重复，跳过: messageId={}", message.getMessageId());
            }
            // 审计 R-01：重复消费也必须补发回执——回执按 messageId 幂等无副作用；
            // 漏发会让 delivery 端本地消息永远停在 SENT，被对账重投 5 轮后误杀为 FAILED。
            publishConsumeAck(message.getMessageId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 审计 R-04：按 x-death 计数有限重试，超限 nack(requeue=false) 进死信队列
            long retries = deathCount(xDeath);
            if (retries >= MAX_REQUEUE) {
                log.error("通知消息重试超限，进入死信队列: messageId={}, retries={}, err={}",
                        message.getMessageId(), retries, e.getMessage(), e);
                channel.basicNack(deliveryTag, false, false);
            } else {
                log.error("处理通知消息失败，重新入队({}/{}): messageId={}, err={}",
                        retries + 1, MAX_REQUEUE, message.getMessageId(), e.getMessage());
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }

    /** 从 x-death 头取本队列的累计死亡次数（broker 每次 requeue 死信回流都会 +1） */
    private long deathCount(List<Map<String, Object>> xDeath) {
        if (xDeath == null) {
            return 0;
        }
        return xDeath.stream()
                .filter(d -> MqConst.QUEUE_NOTIFICATION.equals(String.valueOf(d.get("queue"))))
                .mapToLong(d -> d.get("count") instanceof Number n ? n.longValue() : 0)
                .findFirst()
                .orElse(0);
    }

    private void publishConsumeAck(String messageId) {
        MqPayload.ConsumeAck ack = new MqPayload.ConsumeAck();
        ack.setMessageId(messageId);
        ack.setConsumer(MqConst.Consumer.NOTIFICATION);
        ack.setSuccess(true);
        rabbitTemplate.convertAndSend(MqConst.EXCHANGE, MqConst.RK_CONSUME_ACK, ack);
    }
}
