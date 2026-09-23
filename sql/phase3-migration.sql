-- HireHub 第三期增量建表（幂等，可重复执行）
--
-- 执行（一条命令，文件内自带 USE 按库分段）：
--   docker cp sql/phase3-migration.sql hirehub-mysql:/tmp/phase3-migration.sql
--   docker exec hirehub-mysql sh -c "mysql -uroot -proot < /tmp/phase3-migration.sql"
--
-- ⚠️ 为什么用文件内 `USE`：本文件含多个库的表。不带 USE 直接跑会让所有表建在同一个库
--    （污染 schema，违反 D-13 每服务独立 schema）；拆成每库一个文件又要你执行多次。
--    文件内 USE 两个问题都避免。必须走 docker cp + 容器内重定向，不能用 PowerShell 管道
--    （会破坏 UTF-8，见 docs/踩坑记录.md #9）。
--
-- 注：全部 CREATE TABLE IF NOT EXISTS，可重复执行；已有表不会被改动。

-- =====================================================================
-- hirehub_auth —— 管理端操作审计（D-07 / D-35）
-- =====================================================================
USE hirehub_auth;

CREATE TABLE IF NOT EXISTS sys_operation_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id   BIGINT,                                  -- 操作人（管理员）用户 ID
    operator_name VARCHAR(64),                             -- 落库时由 auth 本地补全，不信任调用方
    module        VARCHAR(32),                             -- COMPANY / JOB / AUTH
    action        VARCHAR(64),                             -- VERIFY_APPROVE / VERIFY_REJECT / JOB_OFFLINE ...
    target_type   VARCHAR(32),                             -- COMPANY / JOB / USER
    target_id     BIGINT,
    detail        TEXT,                                    -- 改成了什么（人可读）
    ip            VARCHAR(64),
    trace_id      VARCHAR(64),                             -- 与链路追踪打通：哪一次请求干的
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_operator_time (operator_id, create_time),
    KEY idx_create_time (create_time),
    KEY idx_module (module)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- =====================================================================
-- hirehub_resume —— 简历解析（D-31）+ 求职者隐私（D-23）
-- =====================================================================
USE hirehub_resume;

-- MQ 消费幂等日志（解析消费者用，结构与其它服务的同名表一致，见 §8.2）
CREATE TABLE IF NOT EXISTS mq_consume_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id  VARCHAR(64) NOT NULL,
    consumer    VARCHAR(64) NOT NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_consumer (message_id, consumer)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 解析结果与 resume 分表：解析是机器推断，用户手填的字段才是权威（见 D-31）
CREATE TABLE IF NOT EXISTS resume_parse_detail (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id       BIGINT NOT NULL,
    user_id         BIGINT,
    source_type     VARCHAR(16),                           -- PDF / DOCX / XLSX / TXT / UNKNOWN
    skills          TEXT,                                  -- JSON 数组字符串，写 resume_index 用
    education       VARCHAR(32),
    work_years      INT,
    work_experience TEXT,
    raw_text        TEXT,                                  -- 抽取全文，供人工核对；不索引
    parse_error     VARCHAR(255),                          -- 失败原因（扫描件无文本层 / 加密 / 读取失败）
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_resume (resume_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 查看留痕：回答"谁看过我"（见 D-23 可见范围 + 留痕）
CREATE TABLE IF NOT EXISTS resume_view_log (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id      BIGINT NOT NULL,
    viewer_user_id BIGINT,
    company_id     BIGINT,
    scene          VARCHAR(32),                            -- DETAIL / CONTACT
    create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_resume_time (resume_id, create_time),
    KEY idx_company (company_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 联系方式查看同意：一次同意只对一家企业生效（见 D-23「联系方式需同意」）
CREATE TABLE IF NOT EXISTS resume_contact_consent (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id      BIGINT NOT NULL,
    seeker_user_id BIGINT NOT NULL,
    company_id     BIGINT NOT NULL,
    hr_user_id     BIGINT,
    status         TINYINT NOT NULL DEFAULT 0,              -- 0 待同意 / 1 已同意 / 2 已拒绝
    request_time   DATETIME,
    consent_time   DATETIME,
    UNIQUE KEY uk_resume_company (resume_id, company_id),
    KEY idx_seeker (seeker_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- =====================================================================
-- hirehub_interview —— 面试提醒排布（D-32）
-- =====================================================================
USE hirehub_interview;

-- 面试提醒记录：延迟消息只带 (interview_id, tier, token)，到期必须回查本表才算数。
--   status  0 待发 / 1 已发 / 2 已作废
--   token   改期后重生；旧延迟消息到期时令牌不匹配即被丢弃
--   uk      (interview_id, tier) 保证同档位只有一行，改期是"更新取代"而不是新增
CREATE TABLE IF NOT EXISTS interview_reminder (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    interview_id BIGINT      NOT NULL,
    tier         VARCHAR(16) NOT NULL,          -- 1D（面试前 1 天）/ 30M（面试前 30 分钟）
    token        VARCHAR(32) NOT NULL,
    remind_time  DATETIME    NOT NULL,          -- 面试时间 − 提前量
    status       TINYINT     NOT NULL DEFAULT 0,
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_interview_tier (interview_id, tier),
    KEY idx_status_time (status, remind_time)   -- 兜底扫描按「待发 + 已到点」取数
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

