package com.ll.hirehub.company.mq;

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
 * company 侧 MQ 拓扑（企业变更 → company_index 同步）。
 */
@Configuration
public class CompanyMqConfig {

    @Bean
    public TopicExchange hirehubExchange() {
        return new TopicExchange(MqConst.EXCHANGE, true, false);
    }

    @Bean
    public Queue companySearchQueue() {
        return QueueBuilder.durable(MqConst.QUEUE_COMPANY_SEARCH).build();
    }

    @Bean
    public Binding companySearchBinding(Queue companySearchQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(companySearchQueue).to(hirehubExchange).with(MqConst.RK_COMPANY_UPSERT);
    }

    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
