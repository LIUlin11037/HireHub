-- HireHub notification 库建表
-- 执行：mysql -uroot -proot hirehub_notification < sql/notification-schema.sql

CREATE TABLE IF NOT EXISTS notification (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    type        VARCHAR(32),
    title       VARCHAR(128),
    content     TEXT,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notification_receiver (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    notification_id BIGINT   NOT NULL,
    receiver_id     BIGINT   NOT NULL,
    read_status     TINYINT  NOT NULL DEFAULT 0,   -- 0 未读 / 1 已读
    read_time       DATETIME,
    KEY idx_receiver (receiver_id, read_status),
    KEY idx_notification (notification_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- MQ 消费幂等日志（二期）：唯一索引兜底「至少一次投递」导致的重复消费
CREATE TABLE IF NOT EXISTS mq_consume_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id  VARCHAR(64) NOT NULL,
    consumer    VARCHAR(64) NOT NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_consumer (message_id, consumer)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
