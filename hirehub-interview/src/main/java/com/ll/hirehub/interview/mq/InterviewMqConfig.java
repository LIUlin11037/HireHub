package com.ll.hirehub.interview.mq;

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
 * interview 侧 MQ 拓扑：面试提醒延迟消息（TTL + DLX，见 D-32）。
 * <pre>
 *   hirehub.interview.remind.delay   ← 生产者投递，带 per-message TTL，**没有消费者**
 *              │ TTL 到期
 *              ↓ DLX = hirehub.topic / routing = interview.remind
 *   hirehub.interview.remind         ← interview 服务消费，校验后转发站内通知
 *              │ routing = interview.remind.notify
 *              ↓
 *   hirehub.notification.msg         ← notification 服务消费，落库分发
 * </pre>
 * <b>为什么用 TTL + DLX 而不是延迟插件</b>：延迟插件要额外安装（运维成本），
 * 而本项目的两个提前量（1 天 / 30 分钟）用 TTL 完全够；代价是队头阻塞，
 * 由 {@code InterviewReminderScanner} 兜底扫描补偿——这个取舍见踩坑记录与 D-32。
 * <p>
 * <b>为什么延迟队列声明在 producer 侧</b>：消费方不在这个服务，
 * 队列参数（DLX 指向）属于"投递约定"，必须跟生产逻辑放在一起才不会漂移。
 */
@Configuration
public class InterviewMqConfig {

    @Bean
    public TopicExchange hirehubExchange() {
        return new TopicExchange(MqConst.EXCHANGE, true, false);
    }

    /** 延迟队列：无消费者，靠消息级 TTL 到期后死信 */
    @Bean
    public Queue interviewRemindDelayQueue() {
        return QueueBuilder.durable(MqConst.QUEUE_INTERVIEW_REMIND_DELAY)
                .withArgument("x-dead-letter-exchange", MqConst.EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConst.RK_INTERVIEW_REMIND)
                .build();
    }

    @Bean
    public Queue interviewRemindQueue() {
        return QueueBuilder.durable(MqConst.QUEUE_INTERVIEW_REMIND).build();
    }

    @Bean
    public Binding interviewRemindBinding(Queue interviewRemindQueue, TopicExchange hirehubExchange) {
        return BindingBuilder.bind(interviewRemindQueue).to(hirehubExchange)
                .with(MqConst.RK_INTERVIEW_REMIND);
    }

    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
