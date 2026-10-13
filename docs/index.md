---
layout: default
title: 文档首页
nav_order: 1
has_children: false
---

> **哪里都可以使用的 Agent**：AI 任务跑在你自己电脑的 worker 上，你可以从任何浏览器查看进度、继续对话、回答它的提问。不需要公网 IP、不需要端口映射，也不把代码和数据上传到第三方。

本目录是 Every Agent 的公开文档入口，发布在 GitHub Pages：<https://qinhaiqinshui.github.io/everyagent/>

## 1. 从这里开始

| 想了解 | 看哪一篇 | 一句话 |
|---|---|---|
| 这个项目是干什么的 | [项目介绍](项目介绍.md) | 背景、典型场景与能力全景（另有 [PDF 版](项目介绍.pdf)） |
| **为什么这么设计** | [架构说明（ARCHITECTURE.md）](ARCHITECTURE.md) | 项目的**唯一架构事实源**：模块职责、协议、事件流、红线清单（§14）；实现与文档冲突时以它为准 |
| **全链路长什么样** | [架构全景图](架构.md) | 一页图看懂：前端订阅 → hub 中转 → worker 执行 → 数据落盘 |
| **怎么动手写插件** | [插件开发指南](plugin-guide/index.md) | 19 篇动手手册：5 分钟跑通 / `plugin.json` 字段 / 后端与前端扩展点 / 构建分发 / 排查 |

## 2. 插件开发指南的四条阅读路径

| 你想做的事 | 按顺序读 |
|---|---|
| 先把工程跑起来 | [快速上手](plugin-guide/getting-started.md) → [plugin.json 字段参考](plugin-guide/plugin-manifest.md) |
| 写后端能力（工具 / 沙箱 / Advisor / 任务生命周期 / RPC） | [后端插件总览](plugin-guide/backend/overview.md) → [工具与沙箱扩展点](plugin-guide/backend/tools-and-sandbox.md) → [Advisor 与模型增强扩展点](plugin-guide/backend/advisors.md) → [任务生命周期与 RPC](plugin-guide/backend/task-and-rpc.md) → [持久化与状态](plugin-guide/backend/persistence-and-state.md) |
| 写前端界面（侧边栏 / 编辑器 / 工具调用视图 / 事件） | [前端插件总览与加载链路](plugin-guide/web/overview-and-loading.md) → [前端 ctx API](plugin-guide/web/context-api.md) → [前端 UI 扩展点](plugin-guide/web/ui-extensions.md) → [前端事件](plugin-guide/web/events.md) |
| 打包分发安装自己的插件 | [构建与运行](plugin-guide/guides/build-and-run.md) → [打包与安装](plugin-guide/guides/packaging-and-install.md) → [调试与测试](plugin-guide/guides/debugging-and-testing.md) → [故障排查](plugin-guide/guides/troubleshooting.md) |

想对照现成实现，看[内置插件范例索引](plugin-guide/reference/builtin-plugins.md)；想查一个方法/字段的名字，看 [API 索引](plugin-guide/reference/api-index.md)；现象对不上文档，先查[已知问题与开放项](plugin-guide/reference/known-issues.md)。

## 3. 维护须知（改本站前必读）

- **发布源**：仓库 `Settings → Pages → Build and deployment → Source: Deploy from a branch → main / /docs`；站点配置是[`docs/_config.yml`](_config.yml)（主题 just-the-docs，remote_theme 方式）。
- **根 URL 必须由本文件提供**：Jekyll 把 `docs/index.md` 渲染成站点根的 `index.html`。少了它，访问 `https://<user>.github.io/<repo>/` 就会命中 GitHub Pages 的 404（提示「For root URLs … you must provide an index.html file」）。新增/删除文档时不要动本文件的位置。
- **导航约定**（just-the-docs）：`nav_order` 全局唯一、同级递增；`parent` 必须**精确等于**父页面的 `title`（本站用四个子目录的 `index.md` 作侧栏分组页：后端插件开发 / 前端插件开发 / 构建 · 调试 · 分发 / 参考），父页面要有 `has_children: true`。
- **不写 front-matter 的页面**不进侧栏（也不占 `nav_order`），只能直链或由别的页面给出入口——如 `项目介绍.md` 就由本页第 1 节提供入口；想让它出现在侧栏，补上 `title` + `nav_order` 并顺移同级编号。
- **链接**：正文一律用 `.md` 相对链接；GitHub Pages 默认启用 `jekyll-relative-links`，发布时自动改写成 `.html`，GitHub 网页上也能直接点。