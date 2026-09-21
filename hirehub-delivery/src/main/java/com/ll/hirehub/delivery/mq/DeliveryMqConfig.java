package com.ll.hirehub.delivery.mq;

import com.ll.hirehub.common.mq.MqConst;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * delivery 侧的 MQ 拓扑（生产者）。
 * <p>
 * 生产者只声明 exchange + 自己的回执队列；投递/通知的队列由各自消费者声明，
 * 这样新增消费者不用改生产者的代码（见 {@link MqConst}）。
 */
@Configuration
public class DeliveryMqConfig {

    @Bean
    public TopicExchange hirehubExchange() {
        return new TopicExchange(MqConst.EXCHANGE, true, false);
    }

    /** 消费回执队列：各消费者处理成功后回执，delivery 据此把本地消息置为「已确认」 */
    @Bean
    public Queue deliveryAckQueue() {
        return QueueBuilder.durable(MqConst.QUEUE_DELIVERY_ACK).build();
    }

    @Bean
    public Binding deliveryAckBinding(Queue deliveryAckQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(deliveryAckQueue).to(hirehubExchange).with(MqConst.RK_CONSUME_ACK);
    }

    /** 用 JSON 而不是 JDK 序列化：跨服务可读、便于排查（消息体里保留类型头，消费端直接反序列化成 MqMessage） */
    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /** 给 Boot 自动装配的 RabbitTemplate 挂上 publisher confirm 回调（步骤④状态回写的一半） */
    @Bean
    public RabbitTemplateCustomizer confirmCallbackCustomizer(LocalMessageConfirmCallback callback) {
        return template -> template.setConfirmCallback(callback);
    }
}
