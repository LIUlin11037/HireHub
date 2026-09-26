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
    /** 二期：职位变更 → ES 同步（见 §6.3） */
    public static final String RK_JOB_UPSERT = "job.upsert";
    public static final String RK_JOB_DELETE = "job.delete";
    /** 二期：企业变更 → ES 同步（见 §6.3） */
    public static final String RK_COMPANY_UPSERT = "company.upsert";
    /** 三期：简历附件已上传 → 异步解析（见 Q-03） */
    public static final String RK_RESUME_PARSE = "resume.parse";
    /** 三期：面试提醒 → 站内通知（interview 校验后转发，notification 服务消费） */
    public static final String RK_INTERVIEW_REMIND_NOTIFY = "interview.remind.notify";
    /** 三期：企业认证被撤销（定期复核发现注销）→ 该企业岗位批量下线（见 Q-08） */
    public static final String RK_COMPANY_REVOKED = "company.verify-revoked";

    // ---------- 队列 ----------
    public static final String QUEUE_JOB_DELIVERY = "hirehub.job.delivery";
    public static final String QUEUE_NOTIFICATION = "hirehub.notification.msg";
    public static final String QUEUE_DELIVERY_ACK = "hirehub.delivery.ack";
    /** 二期：职位 ES 同步队列（job 服务自消费，写 job_index） */
    public static final String QUEUE_JOB_SEARCH = "hirehub.job.search";
    /** 二期：企业 ES 同步队列（company 服务自消费，写 company_index） */
    public static final String QUEUE_COMPANY_SEARCH = "hirehub.company.search";
    /** 三期：简历解析队列（resume 服务自消费，PDFBox/POI → 结构化 + resume_index） */
    public static final String QUEUE_RESUME_PARSE = "hirehub.resume.parse";
    // 注：面试提醒的延迟队列已不再用 RabbitMQ（原来那两个队列 QUEUE_INTERVIEW_REMIND_DELAY /
    // QUEUE_INTERVIEW_REMIND 已删除）—— 消息级 TTL 有队头阻塞，改用 Redis ZSet，见 D-38 / 踩坑 #43
    /** 三期：企业认证撤销 → job 批量下线该企业岗位（见 Q-08 状态联动） */
    public static final String QUEUE_JOB_COMPANY_REVOKE = "hirehub.job.company-revoke";

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
        /** 职位新增/变更 → 同步 job_index */
        public static final String JOB_UPSERT = "JOB_UPSERT";
        /** 职位删除 → 从 job_index 移除 */
        public static final String JOB_DELETE = "JOB_DELETE";
        /** 企业新增/变更 → 同步 company_index */
        public static final String COMPANY_UPSERT = "COMPANY_UPSERT";
        /** 简历附件已上传 → 触发解析 */
        public static final String RESUME_PARSE = "RESUME_PARSE";
        /** 面试提醒（延迟消息到期 / 兜底扫描 → 站内通知） */
        public static final String INTERVIEW_REMIND = "INTERVIEW_REMIND";
        /** 企业认证被撤销 → job 批量下线该企业岗位（见 Q-08 / D-18） */
        public static final String COMPANY_VERIFY_REVOKED = "COMPANY_VERIFY_REVOKED";
    }

    /** 消费者标识，写进 mq_consume_log 便于排查 */
    public static final class Consumer {
        private Consumer() {
        }

        public static final String JOB_DELIVERY_COUNT = "job-delivery-count";
        public static final String NOTIFICATION = "notification";
        /** 职位 ES 同步消费者 */
        public static final String JOB_SEARCH = "job-search";
        /** 企业 ES 同步消费者 */
        public static final String COMPANY_SEARCH = "company-search";
        /** 简历解析消费者 */
        public static final String RESUME_PARSE = "resume-parse";
        // 注：「面试提醒消费者」已删除：延迟触发改由 Redis ZSet + 轮询完成，不再有 MQ 消费者（D-38）
        /** 企业认证撤销消费者（job 批量下线岗位） */
        public static final String JOB_COMPANY_REVOKE = "job-company-revoke";
    }
}
