package com.ll.hirehub.job.mq;

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
 * 「企业认证撤销」消费者：批量下线该企业岗位（见 Q-08 状态联动）。
 * <p>
 * 手动 ACK + mq_consume_log 幂等，与 job 侧其它消费者保持一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanyRevokeConsumer {

    private final CompanyRevokeService companyRevokeService;

    @RabbitListener(queues = MqConst.QUEUE_JOB_COMPANY_REVOKE)
    public void onCompanyRevoked(MqMessage message, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            boolean processed = companyRevokeService.handle(message);
            if (!processed) {
                log.info("企业认证撤销消息重复，跳过: messageId={}", message.getMessageId());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理企业认证撤销消息失败，重新入队: messageId={}, err={}",
                    message.getMessageId(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
