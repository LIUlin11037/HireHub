package com.ll.hirehub.common.mq;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * MQ 业务负载（{@link MqMessage#getPayload()} 的 JSON 结构）。
 * <p>
 * 放在 common 而不是各服务各写一份：这是 delivery(生产) ↔ job/notification(消费)
 * 之间的跨服务契约，和 hirehub-api 里的 Feign DTO 是同一类东西——
 * 写两份一定会漂移。
 */
public final class MqPayload {

    private MqPayload() {
    }

    /** 投递创建：job 数 +1 / 通知 HR */
    @Data
    public static class DeliveryCreated implements Serializable {
        private Long deliveryId;
        private Long jobId;
        private Long companyId;
        private Long seekerId;
        private String jobTitle;
        private String seekerName;
        /** 接收通知的 HR 用户 ID（生产端解析，避免消费端依赖 company 服务） */
        private List<Long> receiverIds;
    }

    /**
     * 投递状态迁移：通知
     * <p>
     * {@code receiverIds} 由生产端（delivery）解析后放进负载——
     * 因为「CANCEL 该通知哪几个 HR」需要查 company 库的成员表，
     * 让消费端去查会把 company 的依赖扩散到 notification 服务。
     */
    @Data
    public static class DeliveryEvent implements Serializable {
        private Long deliveryId;
        private Long jobId;
        private Long companyId;
        private Long seekerId;
        private String jobTitle;
        private String event;
        private String fromStatus;
        private String toStatus;
        /** SEEKER / HR，见架构文档 §6.2 通知映射 */
        private String notifyTo;
        private List<Long> receiverIds;
    }

    /** 消费回执：消费者处理成功后回写本地消息表状态=2 */
    @Data
    public static class ConsumeAck implements Serializable {
        private String messageId;
        private String consumer;
        private boolean success;
    }

    /** 职位 ES 同步：job.upsert / job.delete 的负载 */
    @Data
    public static class JobSync implements Serializable {
        private Long jobId;
    }
}
