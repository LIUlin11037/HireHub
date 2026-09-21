-- HireHub 二期迁移：MQ 相关新表（可重复执行，见架构文档 §6.2 / §8.2）
--
-- 为什么不直接重跑 *-schema.sql：
--   job-schema.sql 末尾有 job_category 种子 INSERT，没有唯一键保护，
--   对已有库重跑会重复插入分类。所以二期变更走独立迁移文件。
--   各服务的 *-schema.sql 已同步更新，供全新装库使用。
--
-- 执行方式（容器内重定向，避免 PowerShell 管道破坏 UTF-8）：
--   docker cp sql/phase2-migration.sql hirehub-mysql:/tmp/p2.sql
--   docker exec hirehub-mysql sh -c "mysql -uroot -proot < /tmp/p2.sql"

-- ---------- delivery：本地消息表 ----------
USE hirehub_delivery;

CREATE TABLE IF NOT EXISTS local_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,
    biz_type        VARCHAR(64)  NOT NULL,
    biz_id          BIGINT,
    routing_key     VARCHAR(64)  NOT NULL,
    payload         TEXT,
    status          TINYINT      NOT NULL DEFAULT 0,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    send_time       DATETIME,
    ack_time        DATETIME,
    error_msg       VARCHAR(512),
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_scan (status, next_retry_time),
    KEY idx_biz (biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------- job：消费幂等日志 ----------
USE hirehub_job;

CREATE TABLE IF NOT EXISTS mq_consume_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id  VARCHAR(64) NOT NULL,
    consumer    VARCHAR(64) NOT NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_consumer (message_id, consumer)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------- notification：消费幂等日志 ----------
USE hirehub_notification;

CREATE TABLE IF NOT EXISTS mq_consume_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id  VARCHAR(64) NOT NULL,
    consumer    VARCHAR(64) NOT NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_consumer (message_id, consumer)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
