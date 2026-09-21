-- HireHub job 库建表
-- 执行：mysql -uroot -proot hirehub_job < sql/job-schema.sql

CREATE TABLE IF NOT EXISTS job (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id     BIGINT       NOT NULL,
    publisher_id   BIGINT       NOT NULL,                    -- 仅留痕，不作权限边界（D-21）
    title          VARCHAR(128) NOT NULL,
    category_id    BIGINT,
    skills         TEXT,                                     -- JSON 数组字符串，如 ["Java","Spring Boot"]
    city           VARCHAR(64),
    district       VARCHAR(64),
    address        VARCHAR(255),
    remote         TINYINT      NOT NULL DEFAULT 0,
    salary_min     INT,
    salary_max     INT,
    salary_type    VARCHAR(16),
    negotiable     TINYINT      NOT NULL DEFAULT 0,
    education      VARCHAR(32),
    experience     VARCHAR(32),
    headcount      INT,
    job_type       VARCHAR(16),
    description    TEXT,
    requirement    TEXT,
    status         TINYINT      NOT NULL DEFAULT 0,          -- 0 草稿 / 1 招聘中 / 2 已下线 / 3 已满
    offline_reason VARCHAR(32),                              -- 手动 / 企业认证失效 / 管理员下架 / 已招满 / 已过期
    publish_time   DATETIME,
    expire_time    DATETIME,
    delivery_count INT          NOT NULL DEFAULT 0,
    view_count     INT          NOT NULL DEFAULT 0,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    KEY idx_company (company_id),
    KEY idx_category (category_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS job_category (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id   BIGINT      NOT NULL DEFAULT 0,
    name        VARCHAR(64) NOT NULL,
    level       TINYINT     NOT NULL DEFAULT 1,             -- 1 一级 / 2 二级
    sort        INT         NOT NULL DEFAULT 0,
    status      TINYINT     NOT NULL DEFAULT 1,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    KEY idx_parent (parent_id, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 种子分类（两级，见 §5.2）
INSERT INTO job_category (parent_id, name, level, sort) VALUES
    (0, '技术', 1, 1), (0, '产品', 1, 2), (0, '设计', 1, 3), (0, '运营', 1, 4),
    (0, '市场', 1, 5), (0, '销售', 1, 6), (0, '职能', 1, 7);

INSERT INTO job_category (parent_id, name, level, sort) VALUES
    (1, '后端开发', 2, 1), (1, '前端开发', 2, 2), (1, '算法', 2, 3), (1, '测试', 2, 4), (1, '运维', 2, 5);

-- MQ 消费幂等日志（二期）：唯一索引兜底「至少一次投递」导致的重复消费
CREATE TABLE IF NOT EXISTS mq_consume_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id  VARCHAR(64) NOT NULL,
    consumer    VARCHAR(64) NOT NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_consumer (message_id, consumer)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
