# HireHub

招聘 / 求职平台的 Java 微服务项目 —— 8 个服务，覆盖「注册 → 实名 → 建企业 → 企业认证 → 发职位 → 投递 → 面试 → Offer」完整闭环。

**它不是 CRUD 堆砌**，重点在几条做深的链路：

- **投递最终一致性**：本地消息表 + RabbitMQ + 定时对账（不用 Seata，见 [D-01](docs/关键决策记录.md)）
- **统一搜索**：ES 双索引（职位 / 公司）+ IK 分词 + 高亮 + `search_after` 深分页 + 定时对账
- **缓存三防**：布隆过滤器 / 空值缓存 / 互斥锁重建（那把锁踩过一个真 bug，见[踩坑 #36](docs/踩坑记录.md)）
- **两套独立状态机**：投递（8 态）与面试（5 态），事件驱动 + 迁移表 + 事件级角色规则
- **面试提醒延迟任务**：Redis ZSet 延迟队列 + 秒级轮询 + 兜底扫描（[D-38](docs/关键决策记录.md)）
- **企业认证可撤销**：定期复核发现注销 → MQ 通知 → 该企业岗位批量下线，另有定时对账补偿（Q-08）
- **自然语言搜索**：qwen3.7-plus 把「杭州 30k 以上 本科 Java 后端」解析成结构化 ES 查询（[D-29](docs/关键决策记录.md)）

> 所有设计取舍都写成了 **41 条 ADR**，所有踩过的坑都记在 **50 条踩坑记录** 里 —— 这两份文档比代码更能说明"为什么这么做"。

---

## 快速开始

### 0. 前置

| 依赖 | 说明 |
|---|---|
| JDK 17 | 编译与运行都锁在 17（本机可用 JDK 21 编译，但走 `--release 17`） |
| Maven 3.9+ | 构建 |
| Docker Desktop | 中间件 + 8 个服务全部容器化 |
| PowerShell 5.1+ | 验证脚本（Windows 自带） |

### 1. 准备环境变量

```powershell
# 只差这两个文件里的值，仓库里不含任何真实密钥
# docker/.env（已 gitignore）：
#   NACOS_PASSWORD=<你的 Nacos 客户端密码>      # 必填
#   DASHSCOPE_API_KEY=<你的百炼 API Key>        # 可选：不配则自然语言搜索降级为关键词
```

### 2. 起中间件（MySQL / Redis / Nacos / RabbitMQ / ES / MinIO）

```powershell
docker compose -f docker/docker-compose.yml up -d
```

### 3. 构建并起 8 个服务

```powershell
$env:JAVA_HOME='<你的 JDK17/21 路径>'
mvn -B clean package -DskipTests                            # 产出各模块 fat jar
# ⚠️ 必须带 clean：删过源文件却不 clean 时，旧 .class 会留在 jar 里继续跑（见踩坑 #48）
docker compose -f docker/docker-compose-services.yml up -d --build
```

容器之间通过 **Docker 网络里的容器名**（`hirehub-mysql` / `hirehub-redis` / `hirehub-nacos` / `hirehub-rabbitmq` / `hirehub-es` / `hirehub-minio`）访问中间件
—— 不用 `host.docker.internal`，原因见[踩坑 #47](docs/踩坑记录.md)。

### 4. 访问

| 入口 | 地址 |
|---|---|
| 网关（唯一对外入口） | http://localhost:9000 |
| Swagger UI（每个业务服务自托管） | http://localhost:9001/swagger-ui.html … http://localhost:9007/swagger-ui.html |
| Nacos 控制台 | http://localhost:8080 （用户名 `nacos`） |
| RabbitMQ 管理台 | http://localhost:15672 （账号 `hirehub`，非 guest） |

**平台管理员**：`admin` / `admin123`（只能由初始化脚本产生，见 D-05）

**测试数据**（校验位是真的算法，编造的号会被拒）：
- 身份证 `110101199003078515`
- 统一社会信用代码 `913100001234567896`

---

## 怎么验证

### 端到端脚本（按顺序跑）

| 脚本 | 验证内容 | 通过 |
|---|---|---|
| `scripts/e2e-verify.ps1 -Fresh` | 一期闭环 ①–⑧（注册→实名→建企业→认证→发职位→投递→面试→Offer + 状态机拦截 + 网关鉴权） | **49/49** |
| `scripts/phase3-verify.ps1` | 双 token 轮换 / 身份查询 / 管理端鉴权与审计 / 求职者隐私 | **54/54** |
| `scripts/phase3b-verify.ps1` | 简历解析真实文件链路 / 面试提醒真实延迟 / WebSocket | **22/22** |
| `scripts/phase3c-verify.ps1` | 企业认证复核 + 岗位联动下线（Q-08），含"存续企业不受影响"负例 | **34/34** |
| `scripts/phase3d-verify.ps1` | 面试状态机全量迁移 + 事件级角色规则 + D-28 解耦 | **36/36** |
| `scripts/phase3e-verify.ps1` | 公司搜索 / 法人授权 / 邀请码加入 / HR 下线 / 通知已读 / 自然语言搜索 | **29/29** |
| `scripts/phase3f-verify.ps1` | **安全（D-39）**：密码强度 / 登录失败锁定（含"锁定期内正确密码也拒"）/ 横向越权 / 敏感信息不落日志 | **24/24** |
| `scripts/phase3g-verify.ps1` | **D-41 三个补口**：`/job/mine`（草稿/下线可见 + 分页 + 非成员 403）/ `/interview/company` / 投递列表带展示名 | **32/32** |

> `phase3b` / `phase3d` / `phase3e` 依赖 `e2e-verify.ps1 -Fresh` 造的数据，
> 所以**先跑 e2e 并 tee 一份日志**：
> ```powershell
> .\scripts\e2e-verify.ps1 -Fresh *>&1 | Tee-Object logs\e2e-setup.log
> ```

### 单元测试（零容器依赖，秒级）

```powershell
mvn test      # 44 个用例：状态机×2 / JwtUtil / 信用代码 / Mock 核验 / 缓存回归
```

### 接口覆盖（回答"有没有接口漏测"）

```powershell
.\scripts\api-coverage.ps1
```

从源码枚举全部接口，再对照脚本调用，输出 **COVERED / INTERNAL / UNCOVERED**，有未覆盖则非零退出。
**当前：79 个接口 = 69 已覆盖 + 10 个 `/internal/**`（经 Feign 间接走到）+ 0 未覆盖。**

---

## 模块

| 模块 | 端口 | 说明 |
|---|---|---|
| `hirehub-common` | — | 统一响应体 / 异常 / JWT / MQ 常量 / 安全过滤器 |
| `hirehub-api` | — | 服务间契约（Feign + DTO） |
| `hirehub-gateway` | 9000 | 路由 + 统一鉴权 + Sentinel 限流 |
| `hirehub-auth` | 9001 | 注册登录 / 双 token / 角色 / 实名（Mock，可切真实） |
| `hirehub-company` | 9002 | 企业 / 成员 / 邀请码 / 认证与定期复核 |
| `hirehub-job` | 9003 | 职位状态机 / 搜索（ES + 自然语言） |
| `hirehub-resume` | 9004 | 简历 / 附件解析 / 求职意向与隐私 |
| `hirehub-delivery` | 9005 | 投递状态机 / 快照 / 最终一致性（本地消息表） |
| `hirehub-interview` | 9006 | 面试状态机 / 提醒（Redis ZSet 延迟队列） |
| `hirehub-notification` | 9007 | 站内信 / WebSocket（一次性 ticket + Redis Pub/Sub） |

## 技术栈

Spring Boot **3.5.0** · Spring Cloud **2025.0.0** · Spring Cloud Alibaba **2025.0.0.0** · Nacos 3.0.3 ·
Gateway（WebFlux）· OpenFeign · MyBatis-Plus 3.5.17 · MySQL 8 · Redis 7 · RabbitMQ 3.13 ·
Elasticsearch 8.14（IK）· Sentinel 1.8.9 · MinIO · springdoc 2.8.17 · Micrometer Tracing · JUnit 5 + Mockito

---

## 文档导航

| 文档 | 内容 |
|---|---|
| [面试问答清单](docs/面试问答.md) | **24 个高频问题的答法**（含"你怎么知道它是对的"这类深挖题）★ 建议先看这份 |
| [前端开发指南](docs/前端开发指南.md) | 起步配置 / 开发顺序 / 页面清单 / 鉴权与身份切换 / 跨域全景（**开工包已交付到前端工程**） |
| [前端状态机映射表](docs/前端状态机映射表.md) | 投递 8 态 / 面试 5 态：状态文案、事件、**按角色分的按钮矩阵** |
| [架构与实施文档](docs/架构与实施文档.md) | 服务边界、数据库、核心链路、接口清单、MQ 拓扑、部署 |
| [关键决策记录（ADR）](docs/关键决策记录.md) | 41 条决策的**背景 / 备选 / 理由 / 代价** |
| [踩坑记录](docs/踩坑记录.md) | 50 条真实踩坑与最终解法（含 11 条"假绿/假红"与自伤类） |
| [端到端验证](docs/端到端验证.md) | ①–⑧ 手工冒烟路径（curl） |
| [端到端验证报告](docs/端到端验证报告.md) | 一期验收的逐条实测结果 |
| [压测报告](docs/压测报告.md) | 真实吞吐 / 延迟数据与"这不是生产容量"的说明 |
| [收尾计划](docs/收尾计划.md) | 当前进度与后续安排 |
| [项目上下文](docs/project_context.md) | 新会话快速恢复上下文用 |

---

## 已知边界（有意不做，不是没做完）

- **前端自研**（[D-40](docs/关键决策记录.md)）：栈 = Vue 3 + TS + Vite + Element Plus，
  见[前端开发指南](docs/前端开发指南.md)（原 D-30 的"不做前端"已被 D-40 修订）
- **不做**：SkyWalking（Q-04）、Canal（Q-02）、独立搜索服务（Q-01）、订单体系（Q-06）、
  WebRTC 实时通话（D-11）、求职者强制实名（Q-09）
- **外部依赖仍是打底实现**：企业工商核验为 Mock（可切阿里云市场 API，见 D-18）
- **未做的生产化**：CI/CD、监控告警、高可用、HTTPS、密钥管理 —— 属"上线才需要"的范围
