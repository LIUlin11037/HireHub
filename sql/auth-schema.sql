-- HireHub auth 库建表 + 种子数据
-- 执行：mysql -uroot -proot hirehub_auth < sql/auth-schema.sql

CREATE TABLE IF NOT EXISTS sys_user (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    username         VARCHAR(64)  NOT NULL,
    password         VARCHAR(128) NOT NULL,
    phone            VARCHAR(20),
    email            VARCHAR(128),
    nickname         VARCHAR(64),
    avatar           VARCHAR(255),
    status           TINYINT      NOT NULL DEFAULT 1,  -- 1 正常 / 0 禁用
    last_login_time  DATETIME,
    real_name        VARCHAR(64),
    id_card_no       VARCHAR(64),                       -- ⚠️ TODO 生产需加密存储（见 D-16/D-18）
    real_name_status TINYINT      NOT NULL DEFAULT 0,  -- 0 未认证 / 1 已认证 / 2 驳回
    real_name_time   DATETIME,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255),
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_user_role (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT   NOT NULL,
    role_id     BIGINT   NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 种子角色：注册接口硬编码只绑定 SEEKER；PLATFORM_ADMIN 只能由初始化脚本产生（见 D-05）
INSERT INTO sys_role (code, name, description) VALUES ('SEEKER', '求职者', '平台求职者')
    ON DUPLICATE KEY UPDATE name = VALUES(name);
INSERT INTO sys_role (code, name, description) VALUES ('PLATFORM_ADMIN', '平台管理员', '平台运营人员')
    ON DUPLICATE KEY UPDATE name = VALUES(name);
