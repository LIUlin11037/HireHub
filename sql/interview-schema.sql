-- HireHub interview 库建表
-- 执行：mysql -uroot -proot hirehub_interview < sql/interview-schema.sql

CREATE TABLE IF NOT EXISTS interview (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    delivery_id      BIGINT      NOT NULL,
    job_id           BIGINT      NOT NULL,
    company_id       BIGINT      NOT NULL,
    seeker_id        BIGINT      NOT NULL,
    round            INT         NOT NULL DEFAULT 1,          -- 轮次（1 一面 / 2 二面，见 D-28）
    interview_time   DATETIME,
    interview_type   VARCHAR(16),
    address_or_link  VARCHAR(255),
    interviewer_name VARCHAR(64),                             -- 面试官姓名（HR 录入的文本）
    status           VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',-- SCHEDULED/CONFIRMED/COMPLETED/REJECTED/CANCELLED
    cancelled_by     VARCHAR(16),                             -- HR / SEEKER
    cancel_reason    VARCHAR(255),
    create_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_delivery (delivery_id),
    KEY idx_company (company_id, interview_time),
    KEY idx_seeker (seeker_id),
    -- 审计 F-17③：round 由 count+1 计算，并发创建会重号——uk 兜底，冲突时由代码重算重试
    UNIQUE KEY uk_delivery_round (delivery_id, round)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS interview_feedback (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    interview_id BIGINT      NOT NULL,
    recorder_id  BIGINT,
    result       VARCHAR(16),                                -- 通过 / 未通过 / 待定
    comment      TEXT,
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_interview (interview_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 本地消息表（审计 R-02 / §6.9，复用 D-01 机制）：与面试业务同库同事务写入
-- 状态机：0 待发送 → 1 已投递到 MQ(publisher confirm) → 2 消费端已确认；3 = 重试超限需人工介入
CREATE TABLE IF NOT EXISTS local_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,               -- 消费端幂等键
    biz_type        VARCHAR(64)  NOT NULL,               -- INTERVIEW_EVENT
    biz_id          BIGINT,                              -- 业务主键（interview_id）
    routing_key     VARCHAR(64)  NOT NULL,               -- MQ 路由键（interview.event）
    payload         TEXT,                                -- 业务负载 JSON
    status          TINYINT      NOT NULL DEFAULT 0,     -- 0 待发送 / 1 已发送 / 2 已确认 / 3 失败
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    send_time       DATETIME,
    ack_time        DATETIME,
    error_msg       VARCHAR(512),
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_scan (status, next_retry_time),              -- 定时投递扫描
    KEY idx_biz (biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
