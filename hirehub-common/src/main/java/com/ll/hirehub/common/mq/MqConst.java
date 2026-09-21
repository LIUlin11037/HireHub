package com.ll.hirehub.common.mq;

/**
 * MQ 拓扑与业务类型常量（见架构文档 §6.2 / §6.4 / §6.3）。
 * <p>
 * 拓扑设计：一个 topic exchange + 按业务域绑定的队列。
 * 生产者只关心「发生了什么」（路由键），消费者各自声明自己关心的绑定，
 * 加一个新消费者不需要改生产者——这是选 topic 而不是对每个消费者发一条消息的原因。
 *
 * <pre>
 *   hirehub.topic (topic)
 *     ├─ routing: delivery.created  →  hirehub.job.delivery        (职位投递数 +1)
 *     ├─ routing: delivery.created  →  hirehub.notification.msg     (通知 HR)
 *     ├─ routing: delivery.event    →  hirehub.notification.msg     (状态变更通知双方)
 *     └─ routing: consume.ack       →  hirehub.delivery.ack         (消费回执 → 本地消息表置为已确认)
 * </pre>
 */
public final class MqConst {

    private MqConst() {
    }

    /** 业务域 topic exchange */
    public static final String EXCHANGE = "hirehub.topic";

    // ---------- 路由键 ----------
    public static final String RK_DELIVERY_CREATED = "delivery.created";
    public static final String RK_DELIVERY_EVENT = "delivery.event";
    public static final String RK_CONSUME_ACK = "consume.ack";

    // ---------- 队列 ----------
    public static final String QUEUE_JOB_DELIVERY = "hirehub.job.delivery";
    public static final String QUEUE_NOTIFICATION = "hirehub.notification.msg";
    public static final String QUEUE_DELIVERY_ACK = "hirehub.delivery.ack";

    /** 业务类型 */
    public static final class BizType {
        private BizType() {
        }

        /** 投递创建 → job 投递数 +1、通知 HR */
        public static final String DELIVERY_CREATED = "DELIVERY_CREATED";
        /** 投递状态迁移 → 通知求职者 / HR */
        public static final String DELIVERY_EVENT = "DELIVERY_EVENT";
        /** 消费回执（消费者 → delivery 的状态回写） */
        public static final String CONSUME_ACK = "CONSUME_ACK";
    }

    /** 消费者标识，写进 mq_consume_log 便于排查 */
    public static final class Consumer {
        private Consumer() {
        }

        public static final String JOB_DELIVERY_COUNT = "job-delivery-count";
        public static final String NOTIFICATION = "notification";
    }
}
