-- HireHub 一期：创建 7 个数据库（每服务独立 schema，见架构文档 §5.1）
-- 执行方式：mysql -uroot -proot < sql/init-databases.sql

CREATE DATABASE IF NOT EXISTS hirehub_auth         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hirehub_company      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hirehub_job          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hirehub_resume       DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hirehub_delivery     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hirehub_interview    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hirehub_notification DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
