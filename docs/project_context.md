# HireHub 项目上下文（会话压缩版）

> 用途：新会话喂这一个文件即可恢复上下文，替代长对话历史。
> 生成时点：二期建设中（一期端到端全绿；二期已完成投递最终一致性/通知/幂等/缓存三防/springdoc/tracing/sentinel，ES/MinIO/三层核验/Docker 化进行中）。

## ⚠️ 新会话必读（最高优先级）

1. **动手前必须先通读两份文档**（硬性要求，不要只靠本文件）：
   - `docs/关键决策记录.md`：28 条 ADR + 待定事项 Q-01~Q-09。**已定的照做；待定的不得擅自决定，必须先和用户讨论。**
   - `docs/架构与实施文档.md`：分期计划、链路设计、接口清单、版本约束。
2. **踩坑先查** `docs/踩坑记录.md`——里面是反复踩过的坑和最终解法，别重复踩。
3. **文档纪律（强制）**：
   - 产生**重大决策** → 必须新增 ADR 到 `docs/关键决策记录.md`；
   - 架构 / 实现有变化 → **实时同步** `docs/架构与实施文档.md`。

---

## 一、项目定位

招聘 / 求职平台，Java 微服务，**实习简历项目**。

- groupId `com.ll`，基础包名 `com.ll.hirehub`，版本 `1.0.0`
- 核心业务闭环：职位 → 投递 → 面试 → Offer
- 设计原则：**组件做减法、链路做深度**；不编造压测数据；只写做透的亮点

## 二、技术栈（已冻结）

| 组件 | 版本 |
|---|---|
| Spring Boot / Cloud / Cloud Alibaba | 3.5.0 / 2025.0.0 / 2025.0.0.0 |
| JDK | 17（本机 PATH=JDK17，JAVA_HOME=JDK21） |
| Nacos | 3.0.3（**已开启鉴权**） |
| MyBatis-Plus | 3.5.17 + 单独引 `mybatis-plus-jsqlparser`（3.5.9+ 拆包） |
| MySQL / Redis | 8.0 / 7 |
| 二期（已引入） | RabbitMQ、Elasticsearch 8.14.3、Sentinel 1.8.9、MinIO、springdoc **2.8.17**（Swagger UI 走 webjar 自托管，见踩坑记录）、Micrometer Tracing |

**求新路线踩坑（已处理）**：Gateway 坐标改名 → `spring-cloud-starter-gateway-server-webflux`；springdoc 必须用 2.x（3.x 给 Boot 4）；全线 `jakarta.*`。

## 三、28 条决策（一句话版）

| 编号 | 结论 |
|---|---|
| D-01 | 分布式事务：**移除 Seata**，用本地消息表 + MQ 最终一致 |
| D-02 | 认证：Spring Security 6 + JWT（落地为 gateway 统一鉴权，auth 只挂 `spring-security-crypto`） |
| D-03 | 版本路线：求新（Boot 3.5 / Cloud 2025 / SCA 2025） |
| D-04 | 身份模型：账号 + 全局角色(SEEKER/PLATFORM_ADMIN) + **企业维度角色(OWNER/HR)** |
| D-05 | 后台管理员：要（企业认证闭环必需）；PLATFORM_ADMIN **只能初始化脚本产生** |
| D-06 | 身份切换：**无状态**（`GET /me/identities` + `X-Company-Id`），不做服务端切换 |
| D-07 | 管理端接口：不拆服务，放所属服务 `/admin/` 子路径 |
| D-08 | 补组件：MinIO / springdoc / Micrometer Tracing / 延迟消息 |
| D-09 | MySQL→ES：MQ 异步 + 定时对账（不做双写；不用 Canal 因成本） |
| D-10 | 定时/延迟：RabbitMQ 延迟消息，不引 XXL-Job |
| D-11 | 实时通话：暂缓 WebRTC，先做 WebSocket 站内消息 |
| D-12 | 服务拆分：8 个服务，不单独拆 search / admin |
| D-13 | 数据库：每服务独立 schema，**禁止跨库 JOIN** |
| D-14 | 招聘者门槛：**只允许企业招聘者**，个人不能发职位 |
| D-15 | 发职位门槛：**分层**——草稿可建，企业认证通过才能上线 |
| D-16 | 实名在 auth（人维度），归属在 company（关系维度） |
| D-17 | 法定代表人：仅标记 + 兜底，**不给额外权限**；OWNER 缺失由管理员人工介入 |
| D-18 | 企业认证数据源：阿里云市场 API + Mock 兜底，**禁止爬取**（法律风险） |
| D-19 | 成员加入：**邀请码直接加入**，不做审批 |
| D-20 | **不预置企业数据**，企业随入驻产生 |
| D-21 | 企业内职位权限：常规操作企业级共享，**删除仅 OWNER** |
| D-22 | 统一搜索：`job_index` / `company_index` 双索引，**不混排** |
| D-23 | 求职者隐私：求职状态 + 匿名化 + **屏蔽公司**，默认最小可见 |
| D-24 | 招聘者**三层核验**：个人实名 / 企业归属(风险评分) / 法人强验证 |
| D-25 | 面试官不设独立角色，反馈 HR 代录 |
| D-26 | **投递存快照不存引用**（HR 看投递那一刻的版本） |
| D-27 | **投递状态机**：事件驱动 + 迁移表 + 乐观锁，前端只发事件 |
| D-28 | **投递与面试解耦**：两套独立状态机，面试取消不回退投递 |
| D-29 | **自然语言搜索**：大模型（**qwen3.7-plus**）把自然语言格式化成 ES 结构化查询（关键词 + 过滤） |

完整版见 `docs/关键决策记录.md`；架构细节见 `docs/架构与实施文档.md`。

## 四、代码结构

**139 文件 / 111 Java / 10 模块**

| 模块 | 端口 | 内容 |
|---|---|---|
| hirehub-common | — | Result / ResultCode(10xxx/20xxx/30xxx) / BusinessException / GlobalExceptionHandler / JwtUtil |
| hirehub-api | — | 5 Feign 契约（Company/Auth/Resume/Job/Delivery）+ 4 DTO |
| hirehub-gateway | 9000 | 路由 + `AuthGlobalFilter` |
| hirehub-auth | 9001 | 注册/登录/JWT/角色/实名 Mock + `AdminInitializer` |
| hirehub-company | 9002 | 企业/成员/认证/邀请码/管理端审核 + 信用代码真算法 |
| hirehub-job | 9003 | 职位状态机 + 三重校验（Feign） |
| hirehub-resume | 9004 | 简历 + 求职意向 |
| hirehub-delivery | 9005 | 投递 + 状态机 + 幂等 + 快照 |
| hirehub-interview | 9006 | 面试状态机 |
| hirehub-notification | 9007 | 通知列表/已读/未读数 |

SQL：`sql/init-databases.sql` + 7 个 `*-schema.sql`

## 五、关键实现约定

- 启动类：`@SpringBootApplication(scanBasePackages = "com.ll.hirehub")` + `@MapperScan`
- 用 Feign 的服务额外加 `@EnableFeignClients(basePackages = "com.ll.hirehub.api")`
- **内部接口统一 `/internal/**`**，网关只路由 `/api/**`，内部接口走服务间直连
- 网关注入 `X-User-Id` / `X-User-Roles`；`X-Company-Id` 由前端传（网关不注入）
- 逻辑删除用全局配置 `logic-delete-field: deleted`
- 投递状态机的 `DeliveryStatus` 8 态：PENDING/VIEWED/COMMUNICATING/INTERVIEW/OFFER/HIRED/REJECTED/CANCELLED

## 六、运行环境（本机实测）

| 项 | 值 |
|---|---|
| MySQL | docker `hirehub-mysql`，**宿主端口 3307**（不是 3306） |
| Redis | `hirehub-redis` 6379 |
| Nacos | `hirehub-nacos` v3.0.3，8848 / 9848 / 9849 / **8080 控制台**，**已开启鉴权** |
| 管理员账号 | `admin` / `admin123` |
| 测试身份证 | `110101199003078515`（校验位可过） |
| 测试信用代码 | `913100001234567896`（GB 32100 可过） |

## 七、本会话已修复的运行时问题

1. **Nacos 3.0 鉴权开启** → 8 个服务 yml 补 `username/password`（默认 `nacos/nacos`）
2. **MySQL 端口改 3307** → 6 个服务 datasource 从 3306 统一改到 3307 + 加 `allowPublicKeyRetrieval=true`
3. **`sys_user` 缺 `real_name_status`** → 全量重建 7 个库（drop + 用 schema 重灌）
4. **PowerShell 管道会破坏 UTF-8**：`Get-Content | docker exec -i mysql` 会吞掉含中文/emoji 注释附近的列；必须用**容器内 `sh -c "mysql < /tmp/sql/xxx.sql"`** 重定向

## 八、当前状态与下一步

**一期（已完成）**：端到端验证 49~50/50 全绿；文档 `docs/第一期运行指南.md`、`docs/端到端验证.md`、`docs/端到端验证报告.md`。

**二期（已完成）**：本地消息表 + MQ 投递最终一致性、通知打通、幂等（SETNX + MQ 消费去重）、Redis 缓存三防 + 布隆过滤器、springdoc（webjar 自托管 Swagger UI）、Micrometer Tracing、Sentinel（Feign 降级 + 网关限流）、docker-compose 补 ES/MinIO。

**二期（未完成）**：ES+IK 搜索、MinIO 简历上传、三层核验（D-24）、全部服务 Docker 化。

**一期已决策但代码未落地（待补）**：D-02 双 token、D-06 `/me/identities`、D-07 `@PreAuthorize` + 更多管理端接口 + `sys_operation_log`、D-23 求职者隐私模型。

**下一步**：ES+IK 搜索 → MinIO → 三层核验 → Docker 化（按关键决策记录 + 架构文档执行）。
