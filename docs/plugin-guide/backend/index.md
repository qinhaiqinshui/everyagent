---
title: 后端插件开发
nav_order: 7
has_children: true
---

worker 侧（Java）插件能力手册，共 5 篇，建议按顺序读：先看运行模型（插件怎么被发现、加载、激活），再按你要注册的扩展点跳进对应篇章。

前置：[快速上手](../getting-started.md) 与 [plugin.json 字段参考](../plugin-manifest.md)。红线约束见 [架构说明](../../ARCHITECTURE.md)（§14 是红线清单）。

| 文档 | 讲什么 |
|---|---|
| [后端插件总览](overview.md) | `EveryAgentPlugin` 生命周期、每插件一个 `URLClassLoader` 隔离、扫描 → 激活全链路、16 个 `ctx.register*` 注册点 |
| [工具与沙箱扩展点](tools-and-sandbox.md) | ToolProvider / ToolExecutionInterceptor / SandboxProvider / FileReferenceHandler，以及与 PermissionGate、jail 的关系 |
| [Advisor 与模型增强扩展点](advisors.md) | AdvisorProvider / ChatModelEnhancer / TokenEstimator / SearchProvider / AuthorizationHandler + Advisor 链 order 坐标 + 技能包物化（SkillMaterializer） |
| [任务生命周期与 RPC](task-and-rpc.md) | TaskAdmissionPolicy / TaskLifecycleNode、`registerRpcMethod` 命名与 ACL、Slash 候选与 token 解析 |
| [持久化与状态](persistence-and-state.md) | 插件私有数据的合法存放（`pluginDir()` + 自注册 RPC）、两种禁用机制 |