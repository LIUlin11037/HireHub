package com.ll.hirehub.resume.mq;

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
 * 「简历解析」消费者（手动 ACK + 幂等去重）。
 * <p>
 * 手动 ACK 而不是自动：解析要下载文件 + 解析文本 + 写索引，中途失败必须重试，
 * 自动 ACK 会在处理完之前就把消息从队列摘掉，进程一挂消息就丢了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeParseConsumer {

    private final ResumeParseHandler parseHandler;

    @RabbitListener(queues = MqConst.QUEUE_RESUME_PARSE)
    public void onResumeParse(MqMessage message, Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            boolean processed = parseHandler.handle(message);
            if (!processed) {
                log.info("简历解析消息重复，跳过: messageId={}", message.getMessageId());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理简历解析消息失败，重新入队: messageId={}, err={}",
                    message.getMessageId(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
