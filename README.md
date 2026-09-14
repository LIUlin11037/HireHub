# HireHub

招聘 / 求职平台 Java 微服务项目。

## 文档

- [架构与实施文档](docs/架构与实施文档.md) —— 服务边界、数据库设计、核心链路、分期计划
- [关键决策记录（ADR）](docs/关键决策记录.md) —— 28 条设计决策的背景 / 备选 / 理由

## 技术栈

Spring Boot 3.5 · Spring Cloud 2025 · Spring Cloud Alibaba 2025 · Nacos · Gateway · OpenFeign · MyBatis-Plus · MySQL · Redis

（RabbitMQ / Elasticsearch / Sentinel / MinIO 为二期引入，见架构文档 §10）

## 启动

1. 起中间件：`cd docker && docker compose up -d`
2. 按架构文档 §10 第一期逐个启动服务（IDE 运行）

## 模块

| 模块 | 端口 | 说明 |
|---|---|---|
| hirehub-common | — | 公共模块（响应体 / 异常 / 工具） |
| hirehub-api | — | 服务间契约（Feign + DTO） |
| hirehub-gateway | 9000 | 路由 + 统一鉴权 |
| hirehub-auth | 9001 | 注册登录 / JWT / 角色 |
| hirehub-company | 9002 | 企业 / 成员 / 认证 |
| hirehub-job | 9003 | 职位 / 搜索 |
| hirehub-resume | 9004 | 简历 / 求职意向 |
| hirehub-delivery | 9005 | 投递 |
| hirehub-interview | 9006 | 面试 |
| hirehub-notification | 9007 | 通知 |
