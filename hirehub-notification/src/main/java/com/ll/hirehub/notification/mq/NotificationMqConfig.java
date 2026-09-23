package com.ll.hirehub.notification.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * notification 侧 MQ 拓扑（消费者）。
 * <p>
 * 一个队列同时绑定 {@code delivery.created} 与 {@code delivery.event}：
 * 站内通知是「投递域的所有事件都要处理」，用一条队列省去重复的连接与幂等逻辑。
 */
@Configuration
public class NotificationMqConfig {

    @Bean
    public TopicExchange hirehubExchange() {
        return new TopicExchange(MqConst.EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(MqConst.QUEUE_NOTIFICATION).build();
    }

    @Bean
    public Binding notificationCreatedBinding(Queue notificationQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(notificationQueue).to(hirehubExchange).with(MqConst.RK_DELIVERY_CREATED);
    }

    @Bean
    public Binding notificationEventBinding(Queue notificationQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(notificationQueue).to(hirehubExchange).with(MqConst.RK_DELIVERY_EVENT);
    }

    /**
     * 三期：面试提醒 → 站内通知（见 D-32）。
     * <p>
     * 注意绑定的是 {@code interview.remind.notify} 而<b>不是</b> {@code interview.remind}：
     * 后者是延迟队列的死信路由键，只有 interview 服务的消费队列该听——
     * 若这里也绑上，一条延迟消息会被两个队列同时消费，面试提醒就会重复落两次。
     */
    @Bean
    public Binding notificationInterviewRemindBinding(Queue notificationQueue,
                                                     TopicExchange hirehubExchange) {
        return BindingBuilder.bind(notificationQueue).to(hirehubExchange)
                .with(MqConst.RK_INTERVIEW_REMIND_NOTIFY);
    }

    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
