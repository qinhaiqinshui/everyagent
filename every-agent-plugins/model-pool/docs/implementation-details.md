# model-pool 实现细节

> 本文内容提取自 [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) §7.4 与 §7.19.5。文中 §x.x 引用均指向 ARCHITECTURE.md 对应章节。

## 组合模型容灾（model-pool 插件）

**模型池 = 一个模型 provider**(`provider: model-pool`,经 `ChatModelEnhancer` SPI 委托给 `model-pool` 插件产出 `ModelPoolChatModel`),主/子 agent 与 AI 审议共用同一入口:

- `worker.models` 里新增一种特殊配置项:`model` 字段用逗号分隔的成员 configId 列表(`model: "deepseek,qwen"`,首个 = 主模型),configId 指向它即「任务默认带容灾」。该 `model` 字段格式（逗号分隔的成员 configId 列表）是 **model-pool 插件的私有配置格式**,`ConfigStore` 原样存储 provider/model 字符串,不解析成员格式。
- 成员格式解析、校验（逗号分隔解析/trim/去空/去重/禁池套池/成员不存在跳过+告警/全空抛异常）均为 **model-pool 插件私有逻辑**,worker 核心 `ConfigStore` 不解析成员格式。成员笔误/漏配的暴露时点从「worker 启动期拒绝」后移到「首次构建期抛出」。
- `ChatModelFactory.buildAgentModel` 遇到该 provider 时经 `ChatModelEnhancerRegistry` 查找匹配的 enhancer（`model-pool` 插件注册的 `ModelPoolEnhancer`），委托其 `enhance(ctx)` 构建组合 `ModelPoolChatModel`(组合各成员的 OpenAiChatModel,按序逐个尝试):请求异常(非网络、非终态)时切下一个成员重试——每个成员用**自己的完整 options 快照**(baseUrl/apiKey/model 在构建时固定),成功即返回该成员真实响应;网络异常、空响应耗尽、取消类原样上抛;流式带防重护栏(已下发 chunk 后流中断不切换)。容灾切换经 `EventEmitter.emit(EmitEvent.TraceData.of("model_failover", ...))` 发 `task.trace(kind=model_failover)`。
- 待池耗尽不做包络,最后异常原样上抛,交给外层瞬时错误重试 advisor 退避重跑。

## ChatModelEnhancer SPI 与 model-pool 插件实现

`ChatModelEnhancer` 用于**模型构建层替换**——插件在 `ChatModelFactory` 构建阶段介入,组合多个成员模型。

```java
// plugin-api: dev.everyagent.plugin.api.model
public interface ChatModelEnhancer {
    String id();
    boolean supports(String provider);
    EnhancedChatModel enhance(EnhancerContext ctx);
}
```

- `ChatModelFactory` 通过 `ChatModelEnhancerRegistry` 查找匹配的 enhancer(`find(provider)`),委托构建。
- `EnhancerContext` 提供 `resolveMember(String configId)` → `MemberSpec | null`（不存在返回 null,供插件跳过+告警）和 `buildMember(MemberSpec)` 回调,让插件复用 `ChatModelFactory.build()` 构建单个成员模型。`poolConfig()` 返回原始配置（`model` 字段即逗号串等插件私有格式,未解析）,成员格式解析/校验/组合策略全归插件。
- `EnhancedChatModel` 返回组合 `ChatModel` + 主成员 `ChatOptions`。

**`model-pool` 插件**(`every-agent-plugins/model-pool`):第一个 `ChatModelEnhancer` 使用者。检测 `provider=model-pool` → 解析 `poolConfig().model()` 逗号串（trim/去空/去重/顺序保持）→ 逐个 `resolveMember`：null 跳过 + error 日志;成员 provider 是本插件 id（组合型）→ 抛异常禁套池;全空 → 抛异常。通过的成员经 `ctx.buildMember()` 逐个构建 `ChatModel`,组装为 `ModelPoolChatModel`(按序容灾切换),返回 `EnhancedChatModel(pool, primaryOptions)`。容灾切换经 `EventEmitter.emit(EmitEvent.TraceData.of("model_failover", ...))` 发 trace,链状态 per-request 管理(`Flux.defer` 闭包内 `AtomicReference`)。
