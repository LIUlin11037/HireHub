-- HireHub delivery 库建表（一期：投递 + 快照；local_message/mq_consume_log 二期随 RabbitMQ 引入）
-- 执行：mysql -uroot -proot hirehub_delivery < sql/delivery-schema.sql

CREATE TABLE IF NOT EXISTS delivery (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id      BIGINT      NOT NULL,
    resume_id   BIGINT      NOT NULL,
    seeker_id   BIGINT      NOT NULL,
    company_id  BIGINT      NOT NULL,
    status      VARCHAR(32) NOT NULL DEFAULT 'PENDING',  -- 见 D-27：PENDING/VIEWED/COMMUNICATING/INTERVIEW/OFFER/HIRED/REJECTED/CANCELLED
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_job_resume (job_id, resume_id),        -- 数据库兜底防重复投递
    KEY idx_seeker (seeker_id, create_time),
    KEY idx_company (company_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS delivery_resume (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    delivery_id     BIGINT   NOT NULL,
    resume_id       BIGINT   NOT NULL,
    resume_snapshot TEXT,                                 -- 投递那一刻的简历快照（JSON，见 D-26）
    snapshot_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_delivery (delivery_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 本地消息表（二期，见架构文档 §6.2）：与业务同库同事务写入，保证「业务成功 ⟺ 消息一定被记录」
-- 状态机：0 待发送 → 1 已投递到 MQ(publisher confirm) → 2 消费端已确认；3 = 重试超限需人工介入
CREATE TABLE IF NOT EXISTS local_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,               -- 消费端幂等键
    biz_type        VARCHAR(64)  NOT NULL,               -- DELIVERY_CREATED / DELIVERY_EVENT
    biz_id          BIGINT,                              -- 业务主键（delivery_id）
    routing_key     VARCHAR(64)  NOT NULL,               -- MQ 路由键
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
