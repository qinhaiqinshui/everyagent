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
- [开发一个插件](#开发一个插件)
- [发布（维护者）](#发布维护者)
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
mvn -pl every-agent-hub spring-boot:run        # hub @ 6101
mvn -pl every-agent-worker spring-boot:run     # worker，出站连 hub

# 前端
cd every-agent-web && npm install && npm run dev  # http://localhost:5174

# 测试
mvn test                                          # contract + hub + worker 全链路 E2E
cd every-agent-web && npm run typecheck
```

更多细节见 [README「快速开始」](README.md#-快速开始)。

## 开发一个插件

给 Every Agent 加能力（工具、沙箱、Advisor、任务生命周期、UI 扩展点、RPC）不需要动宿主代码，也不必从零搭目录——脚手架一条命令生成完整工程：

```bash
node create-everyagent-plugin --id <id>    # java / web / full 三种形态，交互式问答
```

从 [docs/plugin-guide/getting-started.md](docs/plugin-guide/getting-started.md) 开始（全站入口 [docs/plugin-guide/index.md](docs/plugin-guide/index.md)）。两条红线务必先读：插件**零 `every-agent-worker` 依赖**（[架构文档](docs/ARCHITECTURE.md) §14.9，唯一允许的实现依赖是 `every-agent-plugin-api`）；前端插件**禁止 `@/` 引用宿主模块**（§8.5，类型包只能 `import type`）。

## 发布（维护者）

对外发布两条线：**Maven Central**（插件 SDK 坐标，CI 自动）与 **npm**（脚手架 CLI + 前端类型包，手工）。

**首发状态（2026-10-21）**：Java 侧 SDK 4 坐标**已发布 Maven Central，首发版本 1.6.0**（https://repo1.maven.org/maven2/io/github/qinhaiqinshui/）；npm 侧双包待发（见下）。下方「一次性配置」已全部完成，留档备查（换密钥/换 token 时才需要）。

### Maven Central（插件 SDK，一次配置后全自动）

发布范围只有 SDK 链 4 坐标（`io.github.qinhaiqinshui:every-agent-parent` / `-contract` / `-plugin-parent` / `-plugin-api`）；hub / worker 不发布。坐标用 `io.github.qinhaiqinshui`（Central 命名空间经 GitHub 账号验证，无需域名；Java 包名保持 `dev.everyagent.*` 不受影响）。

一次性配置（✅ 已完成，留档）：

1. 注册 [central.sonatype.com](https://central.sonatype.com)（可用 GitHub 登录），在 Portal 里**验证命名空间** `io.github.qinhaiqinshui`（按 Portal 指引在 GitHub 账号下创建验证仓库）；
2. 本机生成 GPG 发布密钥并把公钥上传到 [keyserver.ubuntu.com](https://keyserver.ubuntu.com)：
   ```powershell
   gpg --full-generate-key          # RSA 4096，有效期自定
   gpg --keyserver keyserver.ubuntu.com --send-keys <密钥ID>
   gpg --armor --export-secret-keys <密钥ID> > gpg-private.asc   # 填入下方 secret
   ```
3. Portal → Account → **Generate User Token**（得到用户名 / token 一对）；
4. 仓库 Settings → Secrets and variables → Actions 配 4 个 secrets：`CENTRAL_USERNAME`、`CENTRAL_TOKEN`（上一步的 token 对）、`GPG_PRIVATE_KEY`（`gpg-private.asc` 全文）、`GPG_PASSPHRASE`（密钥口令）。

日常发版（发布链：根 pom 的 `central` profile + [.github/workflows/publish-maven-central.yml](.github/workflows/publish-maven-central.yml)）：

```powershell
# 确认根 pom <revision> 已是待发版本（版本变更走 scripts/bump-version.py），然后：
git tag v1.7.0
git push origin v1.7.0        # 触发 workflow：签名 → 上传 Central Portal → 自动放行
```

注意：**Central 版本一经发布不可覆盖**（1.6.0 已占用），同版本重跑会在 Portal 校验阶段失败（预期行为）；每轮发版必须先 bump `<revision>` 到从未发布过的新版本。发布链技术栈：Maven Wrapper 钉死 3.9.16（runner 自带版本会漂移，曾致 bundle 缺 pom）、central-publishing 插件 0.12.0（0.7.0 对 Portal 的 warnings 字段解析崩溃——0.12.0 起生效，首个用它的发版在下轮）。本地验证发布配置（不真发）：`./mvnw -Pcentral -pl every-agent-contract,every-agent-plugin-api -am package "-DskipTests" "-Dgpg.skip=true"`。

### npm（脚手架 CLI + 前端类型包，手工）

前提：npm 账号 + `npm login`；`everyagent` scope（org）在 npm 上可用（`@everyagent/plugin-api` 发布前先确认 org 归属）。

```powershell
# ① 前端类型包（纯类型零运行时，入口 index.d.ts 由 scripts/sync-plugin-api-copy.ps1 保持与源码一致）
cd every-agent-plugin-api/js
npm publish --access public

# ② 脚手架 CLI（版本须与 everyagent.sdkVersion/工程版本一致，由守卫测试钉住）
cd create-everyagent-plugin
npm publish --access public
```

发布后开发者即可 `npm create everyagent-plugin -- --id my-tool`（getting-started 的等价命令随之生效）。两侧版本都随根 pom `<revision>` 单点源（bump-version.py 联动），发什么版本查根 pom 即可。

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
