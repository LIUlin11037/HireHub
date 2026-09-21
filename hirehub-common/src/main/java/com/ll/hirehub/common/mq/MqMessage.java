package com.ll.hirehub.common.mq;

import lombok.Data;

import java.io.Serializable;

/**
 * MQ 消息信封（见架构文档 §6.2 本地消息表）。
 * <p>
 * 刻意不放任何 amqp 相关类型——hirehub-common 被 WebFlux 网关复用，
 * 不能因为 MQ 契约把 spring-amqp 带进网关的 classpath。
 * <p>
 * {@code messageId} 是消费端幂等的唯一键：至少一次投递 + 消费去重 = 效果上恰好一次。
 */
@Data
public class MqMessage implements Serializable {

    /** 全局唯一，消费端用它写 mq_consume_log 做去重 */
    private String messageId;

    /** 业务类型，见 {@link MqConst.BizType}，同时决定路由键 */
    private String bizType;

    /** 业务主键（如 deliveryId） */
    private Long bizId;

    /** 业务负载 JSON，由各消费端按需反序列化 */
    private String payload;
}
