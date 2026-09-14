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
    KEY idx_seeker (seeker_id)
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
