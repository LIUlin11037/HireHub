-- HireHub company 库建表
-- 执行：mysql -uroot -proot hirehub_company < sql/company-schema.sql

CREATE TABLE IF NOT EXISTS company (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                    VARCHAR(128) NOT NULL,
    credit_code             VARCHAR(32)  NOT NULL,          -- 统一社会信用代码（企业唯一标识，见 D-18）
    legal_person_name       VARCHAR(64),
    business_status         VARCHAR(32),
    logo_url                VARCHAR(255),
    industry                VARCHAR(64),
    scale                   VARCHAR(32),
    city                    VARCHAR(64),
    address                 VARCHAR(255),
    description             TEXT,
    verify_status           TINYINT      NOT NULL DEFAULT 0, -- 0 待审核 / 1 通过 / 2 驳回 / 3 已撤销
    verify_time             DATETIME,
    verify_remark           VARCHAR(255),
    invite_code             VARCHAR(64),
    invite_code_expire_time DATETIME,
    create_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted                 TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_credit_code (credit_code),
    KEY idx_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS company_member (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id   BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(16) NOT NULL DEFAULT 'HR',         -- OWNER / HR（见 D-21）
    is_legal_rep TINYINT     NOT NULL DEFAULT 0,
    status       TINYINT     NOT NULL DEFAULT 1,            -- 1 正常 / 0 离职（不用逻辑删除）
    join_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_company_user (company_id, user_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS company_verify_record (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id        BIGINT NOT NULL,
    submitter_id      BIGINT,
    credit_code       VARCHAR(32),
    company_name      VARCHAR(128),
    legal_person_name VARCHAR(64),
    license_url       VARCHAR(255),
    verify_channel    VARCHAR(16),                            -- MOCK / ALIYUN
    request_snapshot  TEXT,                                   -- 提交材料快照（JSON 字符串）
    response_snapshot TEXT,                                   -- 第三方返回原文（JSON 字符串）
    result            VARCHAR(16),                            -- 通过 / 驳回
    remark            VARCHAR(255),
    operator_id       BIGINT,
    create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_company (company_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
