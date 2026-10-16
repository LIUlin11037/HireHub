package com.ll.hirehub.notification.mq;

import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知消费者（手动 ACK + 消费回执）单元测试。
 * 消费回执是 delivery 端本地消息表「SENT → CONFIRMED」的唯一依据，
 * 漏发回执会被对账任务误判为投递失败（见审计 高#4）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryNotifyConsumerTest {

    @Mock
    private DeliveryNotifyService deliveryNotifyService;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private Channel channel;

    private DeliveryNotifyConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DeliveryNotifyConsumer(deliveryNotifyService, rabbitTemplate);
    }

    private MqMessage message() {
        MqMessage m = new MqMessage();
        m.setMessageId("msg-1");
        m.setBizType(MqConst.BizType.DELIVERY_CREATED);
        return m;
    }

    @Test
    @DisplayName("首消费成功 → basicAck + 发布消费回执（consume.ack）")
    void firstConsumeAcksAndPublishesReceipt() throws Exception {
        when(deliveryNotifyService.handle(any())).thenReturn(true);

        consumer.onDeliveryMessage(message(), channel, 1L, null);

        verify(rabbitTemplate).convertAndSend(eq(MqConst.EXCHANGE), eq(MqConst.RK_CONSUME_ACK),
                any(MqPayload.ConsumeAck.class));
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("重复消费 → 幂等跳过业务，仍 basicAck + 补发消费回执（回执幂等，防对账误杀）")
    void duplicateConsumeStillAcks() throws Exception {
        when(deliveryNotifyService.handle(any())).thenReturn(false);

        consumer.onDeliveryMessage(message(), channel, 1L, null);

        verify(channel).basicAck(1L, false);
        verify(rabbitTemplate).convertAndSend(eq(MqConst.EXCHANGE), eq(MqConst.RK_CONSUME_ACK),
                any(MqPayload.ConsumeAck.class));
    }

    @Test
    @DisplayName("处理异常 → basicNack 重新入队，不发回执")
    void failureNacksAndRequeues() throws Exception {
        doThrow(new RuntimeException("db down")).when(deliveryNotifyService).handle(any());

        consumer.onDeliveryMessage(message(), channel, 1L, null);

        verify(channel).basicNack(1L, false, true);
        verify(channel, never()).basicAck(1L, false);
        verify(rabbitTemplate, never()).convertAndSend(eq(MqConst.EXCHANGE), eq(MqConst.RK_CONSUME_ACK),
                any(MqPayload.ConsumeAck.class));
    }

    @Test
    @DisplayName("R-04：重试超限（x-death≥3）→ nack 不再 requeue，进死信队列")
    void failureBeyondLimitGoesToDlx() throws Exception {
        doThrow(new RuntimeException("poison message")).when(deliveryNotifyService).handle(any());
        java.util.List<java.util.Map<String, Object>> xDeath = java.util.List.of(
                java.util.Map.of("queue", MqConst.QUEUE_NOTIFICATION, "count", 3L));

        consumer.onDeliveryMessage(message(), channel, 1L, xDeath);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
    }
}
