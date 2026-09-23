package com.ll.hirehub.resume.mq;

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
 * resume 侧 MQ 拓扑（见 Q-03）。
 * <p>
 * 复用二期的 {@code hirehub.topic}，不新增交换机——新增一个中间件组件的成本远高于多一个绑定。
 */
@Configuration
public class ResumeMqConfig {

    @Bean
    public TopicExchange hirehubExchange() {
        return new TopicExchange(MqConst.EXCHANGE, true, false);
    }

    /** 简历解析队列：resume 服务自产自销（上传确认 → 解析 → 写 resume_index） */
    @Bean
    public Queue resumeParseQueue() {
        return QueueBuilder.durable(MqConst.QUEUE_RESUME_PARSE).build();
    }

    @Bean
    public Binding resumeParseBinding(Queue resumeParseQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(resumeParseQueue).to(hirehubExchange).with(MqConst.RK_RESUME_PARSE);
    }

    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
