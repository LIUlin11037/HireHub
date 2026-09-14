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
