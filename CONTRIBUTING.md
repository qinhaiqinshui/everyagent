# 贡献指南

首先，感谢你愿意为 Every Agent 贡献时间与代码！🎉

Every Agent 是一套「公网可及、本机执行」的个人 AI Agent 系统。无论你是修复一个错别字、报告一个 bug，还是实现一个新能力，你的贡献都会让这个项目变得更好。

本指南会帮你快速了解如何参与贡献。过程中遇到任何问题，欢迎在 Issue 中提问。

## 目录

- [行为准则](#行为准则)
- [参与方式](#参与方式)
- [报告 Bug](#报告-bug)
- [提出功能建议](#提出功能建议)
- [开发环境搭建](#开发环境搭建)
- [提交 Pull Request](#提交-pull-request)
- [代码与提交规范](#代码与提交规范)

## 行为准则

所有参与者都应遵守 [行为准则](CODE_OF_CONDUCT.md)。我们希望这个社区对每个人都是友善、包容、无骚扰的。

## 参与方式

除了写代码，你还可以通过以下方式参与：

- **报告 Bug**：在 Issue 中描述问题（见下）；
- **提出功能建议**：分享你的使用场景与想法；
- **改进文档**：README、架构文档、本指南的错漏都欢迎修正；
- **回答问题**：在 Issue / Discussion 中帮助其他使用者；
- **分享使用经验**：写博客、录视频，让更多人知道 Every Agent。

## 报告 Bug

一个好的 Bug 报告能极大加速修复。请尽量包含：

1. **环境信息**：操作系统、部署方式（桌面版 / Docker / 源码）、版本或分支；
2. **复现步骤**：清晰、可复现的操作序列；
3. **期望行为 vs 实际行为**：你期望发生什么，实际发生了什么；
4. **日志 / 截图**：worker / hub 的控制台输出、前端报错截图。

> ⚠️ 请务必**脱敏**：不要贴出 API key、hub key、git 凭证等敏感信息。
>
> 安全问题请**不要**公开在 Issue 中，改按 [SECURITY.md](SECURITY.md) 的渠道私密报告。

## 提出功能建议

描述你的**使用场景**和**想要解决的问题**，比单纯描述功能更有效。请说明：

- 你正在做什么、卡在哪一步；
- 你希望 Every Agent 怎样帮你；
- 是否有可接受的替代方案。

## 开发环境搭建

```bash
# 前置要求
# - JDK 25（如 Corretto 25），JAVA_HOME 指向它
# - Maven（PATH 中可用）
# - Node.js 18+（仅前端 / 桌面版）

# Java 模块
mvn -pl every-agent-hub spring-boot:run        # hub @ 9100
mvn -pl every-agent-worker spring-boot:run     # worker，出站连 hub

# 前端
cd every-agent-web && npm install && npm run dev  # http://localhost:5174

# 测试
mvn test                                          # contract + hub + worker 全链路 E2E
cd every-agent-web && npm run typecheck
```

更多细节见 [README「快速开始」](README.md#-快速开始)。

## 提交 Pull Request

1. **Fork** 本仓库并克隆到本地；
2. 从 `main` 切出分支：`git checkout -b feat/你的特性`；
3. 修改代码，保持**一次 PR 只做一件事**；
4. 本地跑通测试（`mvn test`、前端 `npm run typecheck`）；
5. 提交时遵循[提交信息规范](#代码与提交规范)；
6. 推送到你的 fork 并发起 PR；
7. 在 PR 描述中说明「改了什么、为什么、怎么验证」。

小贴士：

- 动手前先搜索已有 Issue，避免重复劳动；
- 较大的改动建议先开 Issue 讨论方案，再动手实现；
- 保持 diff 聚焦，避免混入无关的格式化改动。

## 代码与提交规范

Every Agent 有几条**不可妥协的架构红线**，提交代码前务必了解（详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §14）：

- **复用 Spring AI 框架，禁止重复造轮子**：agent 执行必须走 `ChatClient` + `Advisor` 生态，不得手搓 agent 循环、工具调用循环、响应聚合等；
- **一个 Advisor 只负责一个功能**：新增能力优先做成 `Advisor`，不要把多个不相关职责塞进同一个 Advisor；
- **三层解耦**：hub / worker / client 只依赖 `every-agent-contract`，互相零依赖；
- **hub 零状态、零缓冲、零业务逻辑**；
- **worker 本地事件日志先行**，磁盘是唯一事实源；
- **实现与文档冲突时，先改文档再改代码**。

提交信息规范：

- **用中文**；
- **一次一事**：一个 commit 只解决一个问题；
- 建议格式：`类型: 简述`（类型如 `feat` / `fix` / `docs` / `refactor` / `test` / `chore`）。

例如：

```
feat: worker 支持模型池容灾切换
fix: 修复重连后事件 seq 补洞去重
docs: 补充多端访问部署步骤
```

---

再次感谢你的贡献！Every Agent 因你而更好。🚀
