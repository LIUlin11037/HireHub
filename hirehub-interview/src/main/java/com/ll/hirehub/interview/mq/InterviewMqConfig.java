package com.ll.hirehub.interview.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * interview 侧的 MQ 拓扑（见 D-32 修订 / D-38）。
 * <pre>
 *   Redis ZSet 延迟队列（到点由 InterviewReminderPoller 取出，见 D-38）
 *              │ 校验后转发 routing = interview.remind.notify
 *              ↓
 *   hirehub.notification.msg   ← notification 服务消费，落库 + 推送
 * </pre>
 * <b>为什么延迟不再走 RabbitMQ</b>：消息级 TTL **只在消息到达队头时才被检查** ——
 * 一条"1 天后提醒"排在队头，后面那条"30 分钟后提醒"就得干等一天才死信。
 * 这个队头阻塞实测踩到过（踩坑记录 #43），改期/取消也会留下失效消息。
 * 改用 **Redis ZSet**（按到点时间做 score）后谁到点谁先出，且 Redis 本就在运维清单里，
 * 不引入新组件（见 D-38）。
 * <p>
 * 这里只保留**发送通知**所需的最小拓扑：interview 是 producer，
 * 通知队列与绑定由 notification 服务自己声明（谁消费谁声明，避免两边参数漂移）。
 */
@Configuration
public class InterviewMqConfig {

    @Bean
    public TopicExchange hirehubExchange() {
        return new TopicExchange(MqConst.EXCHANGE, true, false);
    }

    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
