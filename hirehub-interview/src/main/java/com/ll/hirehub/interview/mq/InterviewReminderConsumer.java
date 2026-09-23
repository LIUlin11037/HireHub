package com.ll.hirehub.interview.mq;

import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 面试提醒消费者（延迟消息经 DLX 到达，见 D-32）。
 * <p>
 * 手动 ACK：校验 + 发通知要么都成功、要么重入队。
 * <p>
 * <b>这里不需要 mq_consume_log 做幂等</b>——{@code interview_reminder.status} 的乐观更新
 * （0 → 1）本身就是幂等闸门，比通用消费日志更语义化：它表达的是"这条提醒已发出"，
 * 而不是"这条消息见过"。重复投递会被状态校验直接挡掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewReminderConsumer {

    private final InterviewReminderService reminderService;

    @RabbitListener(queues = MqConst.QUEUE_INTERVIEW_REMIND)
    public void onRemind(MqMessage message, Channel channel,
                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            reminderService.onDelayMessage(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理面试提醒消息失败，重新入队: messageId={}, err={}",
                    message.getMessageId(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
