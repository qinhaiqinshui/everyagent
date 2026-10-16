---
title: 前端插件开发
nav_order: 9
has_children: true
---

前端（TypeScript）插件能力手册，共 4 篇，建议按顺序读：先看加载链路与硬约束，再查 `PluginContext` API、UI 扩展点与事件。

前置：[快速上手](../getting-started.md) 与 [plugin.json 字段参考](../plugin-manifest.md)（纯 web 插件没有 `main`，只有 `webMain`）。

| 文档 | 讲什么 |
|---|---|
| [前端插件总览与加载链路](overview-and-loading.md) | 宿主如何发现与加载插件、bare import 白名单、为什么必须 `import type` |
| [前端 ctx API](context-api.md) | `ctx.ui / sdk / events / fs / storage / commands` 逐个方法与 Disposable 语义 |
| [前端 UI 扩展点](ui-extensions.md) | 15 个 UI 扩展点逐个成节：字段表 + 注册代码 + 排序规则 |
| [前端事件](events.md) | 宿主事件全集与 plugin-api 具名事件、`subscribe` / `getExtensionsVersion` 快照机制 |