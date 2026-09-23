-- HireHub resume 库建表
-- 执行：mysql -uroot -proot hirehub_resume < sql/resume-schema.sql

CREATE TABLE IF NOT EXISTS resume (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    title             VARCHAR(64),
    name              VARCHAR(64),
    gender            VARCHAR(16),
    birth             VARCHAR(16),
    phone             VARCHAR(20),
    email             VARCHAR(128),
    expect_city       VARCHAR(64),
    expect_salary_min INT,
    expect_salary_max INT,
    expect_position   VARCHAR(64),
    status            TINYINT     NOT NULL DEFAULT 0,   -- 0 保密 / 1 公开（是否允许被 HR 搜到，见 D-23）
    parse_status      TINYINT     NOT NULL DEFAULT 0,   -- 0 待解析 / 1 解析中 / 2 成功 / 3 失败
    attachment_id     BIGINT,
    attachment_key    VARCHAR(255),                        -- MinIO 对象键（二期 §6.6）
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted           TINYINT     NOT NULL DEFAULT 0,
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS job_preference (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT      NOT NULL,
    job_status          VARCHAR(32) NOT NULL DEFAULT '在职-暂不考虑',  -- 离职-随时到岗 / 在职-考虑机会 / 在职-暂不考虑
    expect_category_ids TEXT,                                          -- JSON 数组
    expect_city         VARCHAR(64),
    expect_salary_min   INT,
    expect_salary_max   INT,
    blocked_company_ids TEXT,                                          -- JSON 数组（屏蔽公司，见 D-23）
    create_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
