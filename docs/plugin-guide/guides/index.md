---
title: 构建 · 调试 · 分发
nav_order: 13
has_children: true
---

把插件从本地源码变成别人能装上的 `.eap` 包：构建矩阵、打包安装、调试测试、故障排查，共 4 篇。

| 文档 | 讲什么 |
|---|---|
| [构建与运行](build-and-run.md) | 三形态（java / web / full）构建矩阵、Maven 与 npm 两侧配方、worker 启动姿势与 cwd 边界 |
| [打包与安装](packaging-and-install.md) | `.eap` 包内部布局、6 个 `plugin.*` RPC、手工安装目录布局 |
| [调试与测试](debugging-and-testing.md) | 后端 JUnit5 单测范式（不依赖 worker）、前端 typecheck / build 自检、日志定位 |
| [故障排查](troubleshooting.md) | 以 `LoadedPlugin.status` 与 worker WARN 文案为行的「现象 → 根因 → 修复」表 |