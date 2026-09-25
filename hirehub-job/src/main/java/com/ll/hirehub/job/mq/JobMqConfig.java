package com.ll.hirehub.job.mq;

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
 * job 侧 MQ 拓扑（消费者）。
 * <p>
 * 队列由消费方声明并绑定自己关心的路由键——生产者只发「发生了什么」，
 * 不关心谁在听（见 {@link MqConst}）。
 */
@Configuration
public class JobMqConfig {

    @Bean
    public TopicExchange hirehubExchange() {
        return new TopicExchange(MqConst.EXCHANGE, true, false);
    }

    /** 只关心「投递创建」——职位投递数 +1 */
    @Bean
    public Queue jobDeliveryQueue() {
        return QueueBuilder.durable(MqConst.QUEUE_JOB_DELIVERY).build();
    }

    @Bean
    public Binding jobDeliveryBinding(Queue jobDeliveryQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(jobDeliveryQueue).to(hirehubExchange).with(MqConst.RK_DELIVERY_CREATED);
    }

    /** 职位变更 → 写 job_index（本服务自消费，见 §6.3） */
    @Bean
    public Queue jobSearchQueue() {
        return QueueBuilder.durable(MqConst.QUEUE_JOB_SEARCH).build();
    }

    @Bean
    public Binding jobSearchUpsertBinding(Queue jobSearchQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(jobSearchQueue).to(hirehubExchange).with(MqConst.RK_JOB_UPSERT);
    }

    @Bean
    public Binding jobSearchDeleteBinding(Queue jobSearchQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(jobSearchQueue).to(hirehubExchange).with(MqConst.RK_JOB_DELETE);
    }

    /** 三期：企业认证被撤销 → 批量下线该企业岗位（见 Q-08） */
    @Bean
    public Queue jobCompanyRevokeQueue() {
        return QueueBuilder.durable(MqConst.QUEUE_JOB_COMPANY_REVOKE).build();
    }

    @Bean
    public Binding jobCompanyRevokeBinding(Queue jobCompanyRevokeQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(jobCompanyRevokeQueue).to(hirehubExchange).with(MqConst.RK_COMPANY_REVOKED);
    }

    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
