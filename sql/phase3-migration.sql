-- HireHub 第三期增量建表（幂等，可重复执行）
--
-- ⚠️ 本文件按库分段。执行时必须带库名，否则报 ERROR 1046 No database selected
--    （见 docs/踩坑记录.md #9：`mysql < file` 不带库名会失败）。
--
--    规范做法（走 docker cp，避免 PowerShell 管道破坏 UTF-8）：
--      docker cp sql/phase3-migration.sql hirehub-mysql:/tmp/phase3-migration.sql
--      docker exec hirehub-mysql sh -c "mysql -uroot -proot hirehub_auth     < /tmp/phase3-migration.sql"
--      docker exec hirehub-mysql sh -c "mysql -uroot -proot hirehub_resume   < /tmp/phase3-migration.sql"
--      docker exec hirehub-mysql sh -c "mysql -uroot -proot hirehub_interview < /tmp/phase3-migration.sql"
--
--    注：本文件里的 CREATE TABLE IF NOT EXISTS 在各库互不可见，按库执行时只有该库需要的表会被建出来。

-- =====================================================================
-- hirehub_auth —— 管理端操作审计（D-07）
-- =====================================================================
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
-- hirehub_resume —— 简历解析（Q-03）+ 求职者隐私（D-23）
-- =====================================================================

-- MQ 消费幂等日志（解析消费者用，与其它服务的同名表结构一致，见 §8.2）
CREATE TABLE IF NOT EXISTS mq_consume_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id  VARCHAR(64) NOT NULL,
    consumer    VARCHAR(64) NOT NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_consumer (message_id, consumer)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 解析结果与 resume 分表：解析是机器推断，用户手填的字段才是权威（见 Q-03）
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
