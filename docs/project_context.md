# HireHub 项目上下文（会话压缩版）

> 用途：新会话喂这一个文件即可恢复上下文，替代长对话历史。
> 生成时点：**三期已收尾（M0–M5 全部完成）**。一期端到端全绿；二期全部完成；三期 M0–M5 全绿，
> 四个验证脚本均已实测通过；中间件（含 RabbitMQ）全部容器化。

## ⚠️ 新会话必读（最高优先级）

1. **动手前必须先通读下面三份文档**（硬性要求，不要只靠本文件）：
   - `docs/关键决策记录.md`：**39 条 ADR** + 待定事项 Q-01~Q-09（**Q-03/Q-05/Q-07/Q-08 已决议落地**）。**已定的照做；待定的不得擅自决定，必须先和用户讨论。**
   - `docs/架构与实施文档.md`：分期计划、链路设计、接口清单、版本约束。
   - `docs/踩坑记录.md`——里面是反复踩过的坑和最终解法，别重复踩。
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

## 三、35 条决策（一句话版）

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
| D-30 | **三期不做前端**：交付接口 + 文档 + Apifox 集合，演示走 Swagger UI |
| D-31 | **简历解析管线**：上传确认 → MQ 异步 → PDFBox/POI → `resume_parse_detail` + `resume_index`；只回填空字段 |
| D-32 | **面试提醒延迟消息**：TTL + DLX + **定时兜底扫描** + `interview_reminder` 唯一键去重（不装延迟插件） |
| D-33 | **服务内方法级鉴权**：引 Security + Header 过滤器构 `SecurityContext` + 过滤器链**全 permitAll** + `@PreAuthorize` |
| D-34 | **WebSocket 站内消息**：并入 notification；一次性 `ws-ticket`；Redis Pub/Sub 多实例路由 |
| D-35 | **管理端审计日志**：集中存 auth `sys_operation_log`，内部接口上报，`trace_id` 与链路打通 |
| D-36 | **中间件全容器化**：RabbitMQ 纳入编排；用**专用非 guest 账号**（guest 只允许 loopback）；凭据在 broker/编排/yml 三处一致；切账号必须先删数据卷 |
| D-37 | **测试策略**：单测管"纯逻辑 + 边界"（状态机/JWT/校验算法/缓存回归），脚本管"跨服务链路"；**必须显式钉 surefire 3.x**；不做 `@SpringBootTest` 与 Testcontainers |
| D-38 | **延迟任务改用 Redis ZSet**（修订 D-32）：RabbitMQ 消息级 TTL 有**队头阻塞**（远期提醒堵死近期提醒，踩坑 #43），改为 ZSet + 1 秒轮询 + `ZREM` 认领；删掉两个延迟队列（MQ 队列 9→7）；兜底扫描保留为"主路径失败时的安全网" |
| D-39 | **安全加固**：密码强度（8~32 且含字母数字）、登录失败锁定（5 次锁 15 分钟，**锁定期内正确密码也拒**、用户名不存在也计数）、把"横向越权/日志脱敏"变成可执行判据（`phase3f`）；**明确不做** HTTPS/WAF/KMS/验证码（缺生产前提）。⚠️ **测试密码由 `123456` 改为 `123456ab`** |

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
| RabbitMQ | `hirehub-rabbitmq`（`rabbitmq:3.13.7-management`）5672 / 15672，账号 **`hirehub`**（默认密码见 compose，**不是 `guest`**） |
| ES / MinIO | `hirehub-es` 9200（green） / `hirehub-minio` 9010(S3)+9011(控制台)，`minioadmin/minioadmin`，桶 `resume` |
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

**二期（已完成，14/15 已验证）**：本地消息表 + MQ 投递最终一致性、通知打通、幂等（SETNX + MQ 消费去重）、Redis 缓存三防 + 布隆过滤器、springdoc（webjar 自托管 Swagger UI）、Micrometer Tracing、Sentinel（Feign fallbackFactory + 网关 gw-flow 限流）、**ES 双索引**（job_index / company_index：IK 分词、MQ 异步同步、对账、相关性、过滤、高亮、search_after）、**自然语言搜索**（D-29：Mock + qwen3.7-plus LLM）、**MinIO 简历预签名直传**、**三层核验**（D-24：实名 + 风险评分 + 法人授权令牌闭环）。

**二期（产物已完成，全栈启动待端口交接）**：全部服务 Docker 化——`docker/Dockerfile`（通用镜像，auth 镜像已验证可构建）、`docker/docker-compose-services.yml`（8 服务 + healthcheck + depends_on 启动顺序 + mem_limit + host.docker.internal 中间件访问）。本地 9000-9007 被 IDE 占用，跑全栈需先停 IDE 服务再 `docker compose -f docker/docker-compose-services.yml up -d --build`。

**一期已决策但代码未落地（三期补课，代码已完成待验证）**：D-02 双 token + refresh 轮换 + jti 黑名单、D-06 `/me/identities` + 成员缓存、D-07 `@PreAuthorize` + job 管理端 + `sys_operation_log`、D-23 求职者隐私模型。**均已写完代码并编译通过。**

**三期执行状态**（里程碑编号以 `docs/第三期计划.md` §1 为准，旧版编号已废弃）：

| 里程碑 | 状态 |
|---|---|
| M0 二期收尾 | ✅ **4/4 完成（全部实测）**：0.1 Docker 全栈（8 容器 healthy + 容器内 E2E **49/49**）、0.2 限流（容器内 150 并发 → **100×401+50×429**）、0.3 traceId（容器日志非空）、0.4 ES green |
| M1 认证可撤销（D-02） | ✅ **已实测通过**（`scripts/phase3-verify.ps1`） |
| M2 身份与审计（D-06 / D-07） | ✅ **已实测通过**（非管理员 3 个管理端接口全 403/20003；审计含 traceId） |
| M3 隐私与解析（D-23 / Q-03） | ✅ **已实测通过**（匿名 + 同意 + 屏蔽优先于同意 + 留痕；真实 PDF 解析 → skills/手机号回填，坏文件 `parseStatus=3` 带原因） |
| M4 实时（面试提醒 / WebSocket） | ✅ **已实测通过**（TTL+DLX 真延迟 60s 送达两档提醒；WS: ticket→握手→CONNECTED→PING/PONG→实时推送→ticket 一次性） |
| M5 收口（压测 / RabbitMQ 入 compose / Q-08 / 文档同步） | ✅ **4/4 完成**：压测 ✅ `docs/压测报告.md`（42320 请求 0 错误）；RabbitMQ 入 compose ✅ **已切换**（节点 `rabbit@hirehub-rabbitmq`、9 队列 / 11 绑定、6 条连接以 `hirehub` 账号接入）；Q-08 ✅ 复核 + 岗位联动下线（MQ 主路径 + 兜底对账，`scripts/phase3c-verify.ps1`）；文档 ✅ ADR D-36 + 踩坑 #32~#36 + 架构文档 §6.10 / §9 / §10 同步 |

**三期已完成的关键实测（都有硬证据）**：
- **限流**：`Nacos` 里 `hirehub-gateway-flow`（`rule-type: gw-flow`, `count: 100`）→ 150 并发得到 **100×401 + 50×429**。排查踩了三个连环坑，见 `踩坑记录.md` #20。
- **traceId**：**二期的"全链路 traceId"一直是断的** —— 8 个服务里只有 auth 引了 `spring-boot-starter-actuator`，缺它则 `Tracer` bean 不存在。补上后容器日志出现非空 `[hirehub-delivery,6ab5d88f…,121b6b3e…]`（踩坑记录 #19）。
- **Docker 全栈**：`mvn -DskipTests package` → `docker compose -f docker/docker-compose-services.yml up -d` → 8 服务 healthy → E2E **49/49**。
- **注意**：当前服务跑在**容器**里（`restart: unless-stopped`）。要回 IDEA 开发需先 `docker compose -f docker/docker-compose-services.yml down`。

**需要用户操作的事项（现已基本消解）**：
1. ~~在 IDEA 启动 8 个服务~~ → **不需要了**，8 个服务已全部容器化运行；要回 IDEA 才需 `docker compose -f docker/docker-compose-services.yml down`。
2. ~~增量 SQL~~ → 已并入 `sql/phase3-migration.sql`（文件内 `USE` 分段，一条命令）。
3. **`NACOS_PASSWORD` 必须注入**（本机 Nacos 客户端密码**不是默认的 `nacos`**，见踩坑记录 #26）——
   已固化在 `docker/.env`（gitignore），compose 自动读取。
4. **内存**：14 个容器约 5.9 GB（ES 1.2G + Nacos 1.0G 最大）。**关 IDEA / 动态壁纸 / 多余浏览器标签比停容器更划算**。

**验证脚本**（六个，都要跑绿）：
- `scripts/e2e-verify.ps1 -Fresh` —— 一期业务闭环，**49/49**
- `scripts/phase3-verify.ps1` —— 三期新增能力（D-02 / D-06 / D-07 / D-23），**54/54**
- `scripts/phase3b-verify.ps1` —— 需先跑 `e2e-verify.ps1 -Fresh` 造数据并 tee 到 `logs/e2e-setup.log`，
  它验证**简历解析真实文件链路 / 面试提醒真延迟（Redis ZSet，D-38）/ WebSocket**，**22/22**
- `scripts/phase3c-verify.ps1` —— **Q-08 企业认证复核 + 岗位联动下线**（正例 / MQ 生效证据 / 负例 / 幂等 / 鉴权 / 审计）；
  需要 `docker exec hirehub-mysql` 改夹具（企业名改成 `-REVOKED`），**34/34**
- `scripts/phase3d-verify.ps1` —— **面试状态机全量迁移**（确认 / 拒绝 / 取消（两角色）/ 改期 / 完成 + 终态阻断 +
  事件级角色规则 + 局外人 403 + **D-28 面试事件不回退投递**）；同样依赖 `logs/e2e-setup.log`，**36/36**
- `scripts/phase3f-verify.ps1` —— **安全（D-39）**：密码强度 / 登录失败锁定（含"锁定期内正确密码也拒"）/
  横向越权（B 读改不了 A 的简历）/ 敏感信息不落日志；需要 `docker` 清锁定 key 与读容器日志
- ⚠️ **测试密码约定已由 `123456` 改为 `123456ab`**（`@Size(8)+字母数字` 策略，见 D-39）；
  用旧密码建的既有账号（`alice`/`bob` 那批）在新脚本下登不进去，**请用 `-Fresh` 模式**。

**单元测试**（`mvn test`，**零容器依赖、秒级**，见 D-37）：

- 合计 **44 个用例 / 0 失败**：`JwtUtilTest` 7、`MockCompanyVerifierTest` 4、`CreditCodeUtilTest` 7、
  `JobCacheServiceTest` 7、`DeliveryStateMachineTest` 7、`InterviewStateMachineTest` 12。
- 覆盖：两个状态机的**全量迁移 + 全量终态 + 事件级角色规则 + 校验顺序**、JWT 三条安全约定、
  GB 32100 校验位真算法、Mock 核验的两条分支、以及**缓存锁 bug 的回归钉**。
- ⚠️ **必须显式钉 `maven-surefire-plugin` 3.x**：默认绑定的 surefire 2.x 不认识 JUnit 5，
  会「**一个用例都不跑却报 BUILD SUCCESS**」（`Tests run: 0`），见踩坑 #38。
- 明确**不做** `@SpringBootTest` 与 Testcontainers（与脚本覆盖面重叠，投入产出比低）。

**接口覆盖对照**（`.\scripts\api-coverage.ps1`，从源码枚举 + 对照脚本调用，有未覆盖则非零退出）：

> **实测：75 个接口 = COVERED 67 + `/internal/**` 8 + UNCOVERED 0。**

一度是 `UNCOVERED 10`（见踩坑 #40：当时我用"补了已知的两个缺口"代替了枚举核对）。这 10 个已由
**`scripts/phase3e-verify.ps1`（29/29）** 补齐：公司搜索、法人授权令牌（D-24 第三层）、邀请码→加入、
HR 自行下线、通知已读/全部已读、手工刷新简历索引、Swagger UI 重定向、自然语言搜索。

> **口径说明**：`GET /api/job/search/nl` 的 **LLM 分支已实测通过**（2026-09-25，容器内实跑，非推断）：
>
> ```
> [NL 搜索] LLM 解析成功: keyword=Java 后端 city=杭州 salary=30000-null education=本科 experience=null
> ```
>
> 输入 `杭州 30k 以上 本科 Java 后端` → 城市/薪资下限/学历均正确抽出，keyword 收敛为「Java 后端」。
> 这**排除了降级路径**（降级会把整句塞进 keyword、其余字段全 null），证明 `qwen3.7-plus` 模型名与 key 均有效。
>
> 仍需注意的只是**可观测性**：该解析器在「未配 key / 非 200 / 抛异常」时都只 `log.warn` 后降级，
> 所以仅看"接口返回 200"无法区分主路径与降级（见踩坑 #41）。成功路径已补 INFO 日志，可用
> `[NL 搜索] LLM 解析成功` 直接判定。**自动化脚本（phase3e）只覆盖无 key 的降级分支。**

> `/internal/**` 的 8 个不由网关路由，是在 E2E 流程中**经 Feign 间接走到**的
> （审计上报、站内信、实名状态、企业成员校验等都因此得到验证）。

**本会话最后修的运行时缺陷**：
- **`JobCacheService` 击穿锁 bug**（踩坑 #36）：锁只靠 TTL 释放 + 抢不到锁时返回 `null` →
  「读 → 写 evict → 立刻再读」会误报 `10004 资源不存在`。修法：`finally` 主动 compare-and-delete 释放 + 抢不到锁落 DB 兜底。
- **MinIO 健康检查**：镜像里没有 `curl`，健康检查恒 `unhealthy`（假红），改成 `/dev/tcp` 探测。

**下一步（可选）**：
- 若要把三期成果做成演示材料：`docs/端到端验证.md` ①–⑧ 手工路径 + 四个脚本即为验收清单。
- **遗留未做**：Q-04（SkyWalking，暂不引）、Q-01/Q-02（独立搜索服务 / Canal，均不做）、Q-06（订单体系，不做）、Q-09（求职者强制实名，不强制）。
