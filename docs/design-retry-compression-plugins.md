# 删除 Model 层洋葱链，三个功能回归 Advisor 插件

> 状态：**方案设计**（待实现）
> 关联：docs/ARCHITECTURE.md §7.3、§7.19、§8.5
> 前置调研：dev 分支（重构前）所有功能都是 Advisor，无 ModelRequestNode 链

---

## 1. 问题回顾

### 1.1 用户的三个问题

1. **为什么要多出 Model 层链？** —— 重构前（dev 分支）没有这条链，系统正常工作。
2. **能否删除 Model 层链？** —— 能。dev 分支证明不需要。
3. **ai-review 特殊怎么办？** —— 让 ai-review 走正常的 agent 创建路径（空工具），不再特殊处理。

### 1.2 dev 分支的事实

dev 分支中：
- **重试**：`EmptyResponseRetryAdvisor` + `TransientErrorRetryAdvisor`，Advisor，硬编码在 `AgentClientFactory`
- **压缩**：`ContextCompressionAdvisor`，Advisor，硬编码在 `AgentClientFactory`
- **限流**：`RateLimitedChatModel`（装饰器，`implements ChatModel`），硬编码在 `ChatModelFactory.build()`
- **ai-review**：直接 `new EmptyResponseRetryAdvisor(reviewEntity, reviewRetry)` + `new TransientErrorRetryAdvisor(...)`，手动挂到 `ChatClient`
- **没有** `ModelRequestNode`、`ModelRequestChain`、`ModelRequestContext`、`ModelRequestChainChatModel`、`ModelRequestNodeRegistry`

### 1.3 ModelRequestNode 链是怎么诞生的

commit `550062d`（插件化重构）创建了 `ModelRequestNode` SPI，commit `7675152` 把 `RateLimitedChatModel` 改造为 `RateLimitNode implements ModelRequestNode`。动机是让限流能作为插件注入。但这个中间层引入了架构复杂度——多了一条链、多了一套 SPI、多了一层抽象，插件开发者需要理解"什么时候用 Advisor，什么时候用 ModelRequestNode"。

### 1.4 核心洞察

ModelRequestNode 链的唯一用户是 `RateLimitNode`（model-rate-limit 插件）。删除这条链，限流可以回归为 ChatModel 装饰器（dev 模式）或 Advisor。三个功能（重试、压缩、限流）都可以是 Advisor 插件，统一在 Agent 层。ai-review 走 `AgentClientFactory.forAgent()` 创建 ChatClient，自动获得全套 Advisor，不再跨插件 import。

---

## 2. 目标

1. **删除 ModelRequestNode 体系**：`ModelRequestNode`、`ModelRequestChain`、`ModelRequestContext`、`ModelRequestChainChatModel`、`ModelRequestContextImpl`、`ModelRequestNodeRegistry`、`WorkerPluginContext.registerModelRequestNode()`
2. **三个功能做成 AdvisorProvider 插件**：重试（2 个）+ 压缩（1 个），通过 `registerAdvisorProvider()` 注册
3. **限流回归 Advisor**：`model-rate-limit` 插件的 `RateLimitNode`（ModelRequestNode）改造为 `RateLimitAdvisorProvider`（AdvisorProvider），创建 `RateLimitAdvisor`（`implements CallAdvisor, StreamAdvisor`）
4. **ai-review 走正常 agent 创建**：不再手动 `new` 重试 Advisor，改为调 `AgentClientFactory.forAgent()` 获得全套 Advisor 链
5. **零跨插件依赖**：插件之间不互相依赖
6. **不改运行时行为**：重试退避算法、压缩三阶段逻辑、限流排队/记账/校准、事件发射全部保持一致

---

## 3. 改后架构

### 3.1 分层（删掉 Model 层链后）

```
Task 层   → TaskLifecycleNode（洋葱链，任务生命周期）
              └─ [内核: agent 对话]
Agent 层  → Advisor 链（Spring AI Advisor 生态）
              ← RoundIndex / SystemInfo / AgentsMd / Skill / Git
              ← WorkerToolEventAdvisor（工具循环）
              ← EmptyResponseRetry     ← ★ Advisor 插件
              ← TransientErrorRetry    ← ★ Advisor 插件
              ← ModelLengthGuard
              ← ContextCompression     ← ★ Advisor 插件
              ← RateLimit              ← ★ 从 ModelRequestNode 改为 Advisor
              └─ [内核: ChatModel 调用]
Model 层  → ChatModel（OpenAiChatModel / ModelPoolChatModel）
              ← ChatModelEnhancer（仅 model-pool 容灾池）
HTTP 层   → OkHttp Interceptor（日志 / 超时释放）
              └─ [实际 HTTP 请求]
```

**删掉的**：Model 层的洋葱链（`ModelRequestChainChatModel`）。ChatModel 直接是 `OpenAiChatModel`（或经 `ChatModelEnhancer` 包装的 `ModelPoolChatModel`），不再被洋葱链包裹。

### 3.2 插件开发者看到的注册方法（删掉 registerModelRequestNode 后）

```
── Advisor 链 ──
registerAdvisorProvider(provider)        // Agent 层：对话增强（重试/压缩/限流/...）

── 工厂提供者 ──
registerToolProvider(provider)             // Agent 层：AI 工具
registerChatModelEnhancer(enhancer)        // Model 层：模型构建增强（容灾池）

── 洋葱链节点 ──
registerTaskLifecycleNode(node)            // Task 层：任务生命周期
registerAuthorizationHandler(handler)     // 授权决议链
registerToolExecutionInterceptor(i)        // 工具执行拦截链

── 其他 ──
registerSandboxProvider / registerSearchProvider / ...
```

**对比改前**：少了 `registerModelRequestNode()`。插件开发者不用学"什么时候用 Advisor，什么时候用 ModelRequestNode"。

---

## 4. 四个插件模块设计

### 4.1 目录结构

```
every-agent-plugins/
├── pom.xml                          # 增加 3 个 <module>
│
├── empty-response-retry/            ★ 新增
│   ├── pom.xml
│   ├── plugin.json
│   └── src/main/java/dev/everyagent/plugin/emptyretry/
│       ├── EmptyResponseRetryPlugin.java          # EveryAgentPlugin 入口
│       ├── EmptyResponseRetryAdvisor.java          # 从 worker/task/ 迁入
│       └── EmptyResponseRetryAdvisorProvider.java  # AdvisorProvider 实现
│
├── transient-error-retry/           ★ 新增
│   ├── pom.xml
│   ├── plugin.json
│   └── src/main/java/dev/everyagent/plugin/transientretry/
│       ├── TransientErrorRetryPlugin.java
│       ├── TransientErrorRetryAdvisor.java          # 从 worker/task/ 迁入
│       └── TransientErrorRetryAdvisorProvider.java
│
├── context-compression/             ★ 新增
│   ├── pom.xml
│   ├── plugin.json
│   └── src/main/java/dev/everyagent/plugin/contextcompression/
│       ├── ContextCompressionPlugin.java
│       ├── ContextCompressionAdvisor.java            # 从 worker/task/ 迁入
│       ├── ContextCompressionAdvisorProvider.java
│       ├── ContextCompressor.java                    # 从 worker/task/ 迁入
│       ├── ContextSummarizer.java                    # 从 worker/task/ 迁入
│       └── LlmContextSummarizer.java                 # 从 worker/task/ 迁入
│
├── model-rate-limit/                ★ 修改现有
│   ├── pom.xml
│   ├── plugin.json
│   └── src/main/java/dev/everyagent/plugin/modelratelimit/
│       ├── ModelRateLimitPlugin.java                # 修改：删 registerModelRequestNode
│       ├── RateLimitAdvisor.java                    # 新增（替代 RateLimitNode）
│       ├── RateLimitAdvisorProvider.java             # 新增（替代 RateLimitNode 注册）
│       ├── ModelRateLimiter.java                    # 不变
│       ├── ModelRateLimiterRegistry.java            # 不变
│       ├── ModelRateLimitConfig.java                # 不变
│       ├── ModelRateLimitException.java             # 不变
│       ├── BuiltinTokenEstimator.java               # 不变
│       └── TokenCalibrationAdvisor.java              # 不变
```

### 4.2 plugin.json

**empty-response-retry**:
```json
{
  "id": "empty-response-retry",
  "name": "空响应重试",
  "version": "0.1.0",
  "description": "模型返回空响应时自动重试",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.emptyretry.EmptyResponseRetryPlugin"
}
```

**transient-error-retry**:
```json
{
  "id": "transient-error-retry",
  "name": "瞬时错误退避重试",
  "version": "0.1.0",
  "description": "模型请求遇 429/5xx/网络抖动时退避重试",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.transientretry.TransientErrorRetryPlugin"
}
```

**context-compression**:
```json
{
  "id": "context-compression",
  "name": "上下文压缩",
  "version": "0.1.0",
  "description": "上下文超阈值时三阶段压缩",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.contextcompression.ContextCompressionPlugin"
}
```

### 4.3 Plugin 入口（三个重试/压缩插件相同模式）

```java
public class EmptyResponseRetryPlugin implements EveryAgentPlugin {
    @Override
    public String id() { return "empty-response-retry"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        ctx.registerAdvisorProvider(new EmptyResponseRetryAdvisorProvider(props));
    }
}
```

### 4.4 AdvisorProvider 适配器（以 EmptyResponseRetry 为例）

```java
public class EmptyResponseRetryAdvisorProvider implements AdvisorProvider {
    private final WorkerProperties props;

    public EmptyResponseRetryAdvisorProvider(WorkerProperties props) {
        this.props = props;
    }

    @Override public String pluginId() { return "builtin.empty-response-retry"; }
    @Override public Scope scope() { return Scope.BOTH; }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 100;  // 与 dev 分支一致
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new EmptyResponseRetryAdvisor(a, props.getRetry());
    }
}
```

### 4.5 限流改造：RateLimitNode → RateLimitAdvisor

当前 `RateLimitNode` 经 `ModelRequestContext.onChunk/onComplete/onError` 回调绑定 `Permit` 生命周期。改为 Advisor 后，通过 `adviseCall`/`adviseStream` 的 Reactor 操作符实现等价逻辑：

```java
public class RateLimitAdvisor implements CallAdvisor, StreamAdvisor {

    private final AgentEntity a;
    private final ModelRateLimiterRegistry registry;

    public RateLimitAdvisor(AgentEntity a, ModelRateLimiterRegistry registry) {
        this.a = a;
        this.registry = registry;
    }

    @Override
    public int getOrder() {
        // 最内层（在 ContextCompression +400 之后，紧贴模型调用）
        return ToolCallingAdvisor.DEFAULT_ORDER + 500;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Optional<ModelRateLimiter> limiter = registry.of(
                a.task.snapshot.configId(), a.task.snapshot.params());
        if (limiter.isEmpty()) {
            return chain.nextCall(request);  // 无限流配置 → 直通
        }
        ModelRateLimiter.Permit permit;
        try {
            permit = limiter.get().acquire(this::onWait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("interrupted");
        }
        try {
            ChatClientResponse resp = chain.nextCall(request);
            permit.complete(outputTokensOf(resp));
            return resp;
        } catch (RuntimeException e) {
            permit.cancel();
            throw e;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Optional<ModelRateLimiter> limiter = registry.of(
                a.task.snapshot.configId(), a.task.snapshot.params());
        if (limiter.isEmpty()) {
            return chain.nextStream(request);  // 无限流配置 → 直通
        }
        return Flux.defer(() -> {
            ModelRateLimiter.Permit permit;
            try {
                permit = limiter.get().acquire(this::onWait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Flux.error(new AgentCancelledException("interrupted"));
            }
            return chain.nextStream(request)
                    .doOnNext(chunk -> {
                        if (hasText(chunk)) permit.onChunk(textOf(chunk));
                        Integer tokens = completionTokensOf(chunk);
                        if (tokens != null && tokens > 0) permit.complete(tokens);
                    })
                    .doOnCancel(permit::cancel)
                    .doOnError(e -> permit.cancel())
                    .doOnComplete(() -> permit.complete(0));
        });
    }

    private void onWait(ModelRateLimiter.WaitInfo info, Long waitMs) {
        a.task.events.modelRateWait(/* trace params */);
    }
}
```

**与 dev 分支 `RateLimitedChatModel` 的等价性**：

| dev 装饰器 | Advisor 版本 | 说明 |
|---|---|---|
| `delegate.call(prompt)` | `chain.nextCall(request)` | 委托下一层 |
| `delegate.stream(prompt)` | `chain.nextStream(request)` | 同上 |
| `limiter.acquire()` | `limiter.acquire()` | 相同 |
| `permit.complete(tokens)` | `permit.complete(tokens)` | 相同 |
| `permit.cancel()` | `permit.cancel()` | 相同 |
| `permit.onChunk(text)` | `permit.onChunk(text)` | 在 `doOnNext` 中调 |
| `events.modelRateWait(...)` | `a.task.events.modelRateWait(...)` | 相同 |
| 按 configId 查 limiter | 按 configId 查 limiter | 相同（`a.task.snapshot.configId()`） |

**关键差异**：装饰器包住 `ChatModel`（HTTP 调用），Advisor 包住 `ChatClient` 调用链（含工具循环）。但限流的 order=+500 是最内层，位于所有其他 Advisor（含 ContextCompression +400）之后，等价于紧贴 ChatModel 调用。工具循环的每一轮都会穿过限流 Advisor，与装饰器包住单次 HTTP 调用语义一致。

### 4.6 RateLimitAdvisorProvider

```java
public class RateLimitAdvisorProvider implements AdvisorProvider {
    private final ModelRateLimiterRegistry registry;

    public RateLimitAdvisorProvider(ModelRateLimiterRegistry registry) {
        this.registry = registry;
    }

    @Override public String pluginId() { return "builtin.rate-limit"; }
    @Override public Scope scope() { return Scope.BOTH; }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 500;  // 最内层
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new RateLimitAdvisor(a, registry);
    }
}
```

### 4.7 model-rate-limit 插件入口修改

```java
public class ModelRateLimitPlugin implements EveryAgentPlugin {
    @Override
    public String id() { return "model-rate-limit"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerProperties props = ctx.getService(WorkerProperties.class);

        // 1. TokenEstimator（不变）
        BuiltinTokenEstimator estimator = new BuiltinTokenEstimator(props);
        ctx.registerTokenEstimator(estimator);

        // 2. TokenCalibrationAdvisor（不变）
        ctx.registerAdvisorProvider(new TokenCalibrationAdvisor.Provider(estimator));

        // 3. RateLimitAdvisorProvider（★ 替换原 registerModelRequestNode）
        ModelRateLimiterRegistry registry = new ModelRateLimiterRegistry(props, estimator);
        ctx.registerAdvisorProvider(new RateLimitAdvisorProvider(registry));
    }
}
```

### 4.8 Advisor order 分段表（改后）

```
  0 ─  99 │ 核心基础设施（RoundIndex / SystemInfo / AgentsMd / TokenCalibration）
100 ─ 199 │ 功能 Advisor（Skill / Git / Slash）
200 ─ 299 │ 守卫 / 文件跟踪（LoopRepeatGuard / FileChange）
300 ─ 399 │ 对话增强（DialogInsert / SlashTokenResolve）
400 ─ 499 │ 重试 / 护栏（EmptyRetry +100 / TransientRetry +200 / LengthGuard +300）
500 ─ 599 │ 终层（ContextCompression +400 / RateLimit +500）  ← ★ RateLimit 从 Model 层迁入
600 ─ 799 │ 用户扩展区
```

---

## 5. ai-review 改造

### 5.1 当前问题

`AiAuthReviewer.doReview()` 手动构造 `AgentEntity` + 手动挂两个重试 Advisor：

```java
// 当前（问题代码）
AgentEntity reviewEntity = buildReviewEntity(t, reviewAgentId, reviewOptions, chatModel, grantKey, prompt);
WorkerProperties.Retry reviewRetry = new WorkerProperties.Retry();
reviewRetry.setStrategy(...);  // 收敛参数
ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(
                new EmptyResponseRetryAdvisor(reviewEntity, reviewRetry),   // ← 跨插件依赖
                new TransientErrorRetryAdvisor(reviewEntity, reviewRetry)) // ← 跨插件依赖
        .build();
```

### 5.2 改后：走 AgentClientFactory

```java
// 改后
AgentEntity reviewEntity = buildReviewEntity(t, reviewAgentId, reviewOptions, chatModel, grantKey, prompt);
// 走正常 agent 创建路径，自动获得全套 Advisor 链（重试/压缩/限流等）
ToolCallingManager tcm = /* 从 Spring 容器获取共享单例 */;
ChatClient client = agentClientFactory.forAgent(reviewEntity, tcm);
```

`AgentClientFactory.forAgent()` 根据 `AgentEntity.kind` 分派 `forMain()` 或 `forSub()`，从 `AdvisorProviderRegistry` 聚合所有 `AdvisorProvider`（按 order 排序、按 scope 筛选），自动创建 ChatClient。ai-review 不再 import 任何重试 Advisor。

### 5.3 ai-review 的 AgentEntity 配置

```java
AgentEntity buildReviewEntity(TaskEntry t, String reviewAgentId, OpenAiChatOptions reviewOptions,
        ChatModel chatModel, String grantKey, String prompt) {
    // Kind.SUB：不挂 skill / git / dispatch 工具（与子 agent 同档）
    AgentEntity reviewEntity = new AgentEntity(t, reviewAgentId, AgentEntity.Kind.SUB,
            "AI 安全审议", chatModel, reviewOptions, List.of());  // 空工具
    reviewEntity.conversation.add(new SystemMessage(reviewSystemPrompt(t)));
    reviewEntity.conversation.add(new UserMessage(userPrompt(grantKey, prompt)));
    return reviewEntity;
}
```

用 `Kind.SUB` 的理由：
- 子 agent 不挂 skill、不挂 git、不挂派发工具——与审议需求一致
- 仍挂空响应重试 + 瞬时错误重试 + 上下文压缩 + 限流（scope=BOTH 的 Advisor）
- 工具列表 `List.of()` → 工具循环一轮即结束（模型无工具可调）

### 5.4 重试参数差异

ai-review 原来用收敛的重试参数（1 次重试、1s 退避），主 agent 用全局参数（30 次、3s 退避）。改为 `Kind.SUB` 后走 `forSub()`，获得的全是 scope=BOTH 的 Advisor，重试参数来自 `WorkerProperties.Retry`（全局默认）。

**方案**：审议模型配置在 `params` JSON 中携带 `retry` 覆盖段。`EmptyResponseRetryAdvisorProvider` / `TransientErrorRetryAdvisorProvider` 的 `create()` 方法额外检查 `ctx` 的 `configId` 对应的 params：

```java
@Override
public Advisor create(AdvisorContext ctx) {
    AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
    WorkerProperties.Retry retry = resolveRetry(a, props.getRetry());
    return new EmptyResponseRetryAdvisor(a, retry);
}

private WorkerProperties.Retry resolveRetry(AgentEntity a, WorkerProperties.Retry defaults) {
    JsonNode params = a.task.snapshot == null ? null : a.task.snapshot.params();
    if (params == null || !params.has("retry")) return defaults;
    // 从 params.retry 读取覆盖值，未覆盖的字段用全局默认
    ...
}
```

ai-review 审议模型配置示例：
```json
{
  "configId": "review-model",
  "params": {
    "retry": {
      "maxEmptyResponseRetries": 1,
      "maxRequestRetries": 1,
      "backoffBaseMs": 1000,
      "backoffFactor": 2,
      "strategy": "exponential"
    }
  }
}
```

若审议模型未配置 `params.retry`，则使用全局默认参数——由 `future.get(reviewTimeoutMs)` 硬闸保证总预算不超。

### 5.5 事件发射的影响

当前 ai-review 不挂 `WorkerToolEventAdvisor`，审议过程中不发 delta/thinking/message 事件。改为走 `forAgent()` 后，`WorkerToolEventAdvisor`（scope=BOTH）会被挂上——但工具列表为空，工具循环一轮即结束，`WorkerToolEventAdvisor` 仍会发射该轮的 delta/thinking/message 事件。

**影响**：审议过程中的模型输出（JSON 判定 + reasoning）会以 `reviewAgentId` 出现在任务事件流中。这些事件落原任务 jsonl，不新建 TaskEntry/EventLog——与当前审计 trace 的落盘行为一致。

**是否可接受**：审议是一次性调用（非长对话），模型输出是简短 JSON，事件量很小。且 `reviewAgentId` 与主 agent 的 `agentId` 不同，前端可按 agentId 分流展示。这是为统一架构做的合理取舍。

### 5.6 删除的依赖

ai-review 的 pom.xml **不增加**对 `empty-response-retry` 或 `transient-error-retry` 的依赖。ai-review 只依赖 `every-agent-worker`（获取 `AgentClientFactory`、`AgentEntity`、`WorkerProperties` 等），重试经 Advisor 链自动注入。

---

## 6. 删除 ModelRequestNode 体系

### 6.1 删除的文件

| 文件 | 位置 | 说明 |
|---|---|---|
| `ModelRequestNode.java` | plugin-api/.../model/ | 接口 |
| `ModelRequestChain.java` | plugin-api/.../model/ | 链接口 |
| `ModelRequestContext.java` | plugin-api/.../model/ | 上下文接口 |
| `ModelRequestChainChatModel.java` | worker/.../task/ | 薄壳组装器 |
| `ModelRequestContextImpl.java` | worker/.../task/ | 上下文实现 |
| `ModelRequestNodeRegistry.java` | worker/.../plugin/registry/ | 注册表 |
| `RateLimitNode.java` | model-rate-limit 插件 | 替换为 RateLimitAdvisor + Provider |

### 6.2 修改的文件

| 文件 | 修改 |
|---|---|
| `WorkerPluginContext.java` | 删除 `registerModelRequestNode()` 方法 |
| `WorkerPluginContextImpl.java` | 删除 `registerModelRequestNode()` 实现 + `ModelRequestNodeRegistry` 字段 |
| `PluginLoader.java` | 删除 `ModelRequestNodeRegistry` 注入 |
| `ChatModelFactory.java` | 删除 `ModelRequestNodeRegistry` 依赖；`build()` 回退为直接返回 `OpenAiChatModel`（或经 `ChatModelEnhancer` 包装的池模型） |
| `ModelRateLimitPlugin.java` | `registerModelRequestNode` → `registerAdvisorProvider` |
| `BuiltInAdvisorProviders.java` | 删除三个重试/压缩 Provider 注册行（迁入插件） |
| 8 个测试文件 | `fakeModelFactory` 签名移除 `ModelRequestNodeRegistry` 参数 |

### 6.3 ChatModelFactory.build() 回退

```java
// 改后（与 dev 分支一致，去掉洋葱链包裹）
public ChatModel build(ResolvedConfig cfg, OpenAiChatOptions options, String agentId,
        TaskEvents events) {
    String cacheKey = cfg.snapshot().configId();
    OpenAiChatModel raw = modelCache.computeIfAbsent(cacheKey, k ->
            OpenAiChatModel.builder()
                    .options(options)
                    .httpClientBuilderCustomizer(b -> b
                            .interceptor(HttpRequestLoggingInterceptor.SHARED)
                            .interceptor(StreamTimeoutReleaseInterceptor.INSTANCE))
                    .build());
    return raw;  // 直接返回，不再包裹 ModelRequestChainChatModel
    // 限流改由 Advisor 层处理，不在 ChatModel 层包裹
}
```

`ChatModelFactory` 构造函数移除 `ModelRequestNodeRegistry` 参数：

```java
public ChatModelFactory(WorkerProperties props,
        ChatModelEnhancerRegistry enhancerRegistry) {
    this.props = props;
    this.enhancerRegistry = enhancerRegistry;
    // 不再注入 ModelRequestNodeRegistry
}
```

### 6.4 保留的

| 保留 | 理由 |
|---|---|
| `ChatModelEnhancer` SPI | model-pool 插件容灾池构建，与洋葱链无关 |
| `EventEmitter` | `ChatModelEnhancer`（容灾切换 trace）和 `AgentEventChannel` 仍需要 |
| `ModelConfig` | `ChatModelEnhancer` 的 `EnhancerContext.poolConfig()` 返回类型 |
| `WorkerPluginContext.registerChatModelEnhancer()` | 不受影响 |

---

## 7. 包名映射

| 旧全限定名 | 新全限定名 |
|---|---|
| `dev.everyagent.worker.task.EmptyResponseRetryAdvisor` | `dev.everyagent.plugin.emptyretry.EmptyResponseRetryAdvisor` |
| `dev.everyagent.worker.task.TransientErrorRetryAdvisor` | `dev.everyagent.plugin.transientretry.TransientErrorRetryAdvisor` |
| `dev.everyagent.worker.task.ContextCompressionAdvisor` | `dev.everyagent.plugin.contextcompression.ContextCompressionAdvisor` |
| `dev.everyagent.worker.task.ContextCompressor` | `dev.everyagent.plugin.contextcompression.ContextCompressor` |
| `dev.everyagent.worker.task.ContextSummarizer` | `dev.everyagent.plugin.contextcompression.ContextSummarizer` |
| `dev.everyagent.worker.task.LlmContextSummarizer` | `dev.everyagent.plugin.contextcompression.LlmContextSummarizer` |
| `dev.everyagent.worker.plugin.adapters.EmptyResponseRetryAdvisorProvider` | `dev.everyagent.plugin.emptyretry.EmptyResponseRetryAdvisorProvider` |
| `dev.everyagent.worker.plugin.adapters.TransientErrorRetryAdvisorProvider` | `dev.everyagent.plugin.transientretry.TransientErrorRetryAdvisorProvider` |
| `dev.everyagent.worker.plugin.adapters.ContextCompressionAdvisorProvider` | `dev.everyagent.plugin.contextcompression.ContextCompressionAdvisorProvider` |
| `dev.everyagent.plugin.modelratelimit.RateLimitNode` | `dev.everyagent.plugin.modelratelimit.RateLimitAdvisor` + `RateLimitAdvisorProvider` |
| `dev.everyagent.plugin.api.model.ModelRequestNode` | **删除** |
| `dev.everyagent.plugin.api.model.ModelRequestChain` | **删除** |
| `dev.everyagent.plugin.api.model.ModelRequestContext` | **删除** |
| `dev.everyagent.worker.task.ModelRequestChainChatModel` | **删除** |
| `dev.everyagent.worker.task.ModelRequestContextImpl` | **删除** |
| `dev.everyagent.worker.plugin.registry.ModelRequestNodeRegistry` | **删除** |

---

## 8. 引用清单

### 8.1 代码引用（需修改 import 或删除）

| 文件 | 引用的类 | 修改方式 |
|---|---|---|
| `ai-review/.../AiAuthReviewer.java` | `EmptyResponseRetryAdvisor`、`TransientErrorRetryAdvisor` | 删除 import + 删除手动构造代码，改为调 `AgentClientFactory.forAgent()` |
| `worker/.../plugin/adapters/BuiltInAdvisorProviders.java` | 三个 Provider 注册 | 删除三行 `registry.register(...)` |
| `worker/.../task/ChatModelFactory.java` | `ModelRequestNodeRegistry`、`ModelRequestChainChatModel` | 删除依赖，`build()` 回退直返 |
| `worker/.../plugin/WorkerPluginContextImpl.java` | `registerModelRequestNode` + `ModelRequestNodeRegistry` | 删除 |
| `worker/.../plugin/loader/PluginLoader.java` | `ModelRequestNodeRegistry` | 删除注入 |
| `model-rate-limit/.../ModelRateLimitPlugin.java` | `registerModelRequestNode` | 改为 `registerAdvisorProvider` |
| `model-rate-limit/.../RateLimitNode.java` | `implements ModelRequestNode` | 替换为 `RateLimitAdvisor` + `RateLimitAdvisorProvider` |

### 8.2 注释引用（仅需修改文字）

| 文件 | 引用内容 |
|---|---|
| `worker/.../task/WorkerToolEventAdvisor.java` | 注释提及 ContextCompressionAdvisor |
| `worker/.../task/ModelLengthGuardAdvisor.java` | javadoc 提及与重试 Advisor 的协作 |
| `worker/.../task/ChatModelFactory.java` | javadoc 提及容灾与重试的关系、`maxRetries=0` 注释 |
| `worker/.../task/HttpRequestLoggingInterceptor.java` | 注释提及与重试的协作 |
| `model-pool/.../ModelPoolChatModel.java` | 注释提及与 TransientErrorRetryAdvisor 的关系 |
| `model-rate-limit/.../ModelRateLimitException.java` | 注释提及与 TransientErrorRetryAdvisor 的关系 |
| `worker/.../config/WorkerProperties.java` | javadoc 提及 ContextCompression / EmptyResponse |

### 8.3 删除文件

| 文件 | 操作 |
|---|---|
| `worker/.../task/EmptyResponseRetryAdvisor.java` | 删除（迁入插件） |
| `worker/.../task/TransientErrorRetryAdvisor.java` | 删除（迁入插件） |
| `worker/.../task/ContextCompressionAdvisor.java` | 删除（迁入插件） |
| `worker/.../task/ContextCompressor.java` | 删除（迁入插件） |
| `worker/.../task/ContextSummarizer.java` | 删除（迁入插件） |
| `worker/.../task/LlmContextSummarizer.java` | 删除（迁入插件） |
| `worker/.../plugin/adapters/EmptyResponseRetryAdvisorProvider.java` | 删除（迁入插件） |
| `worker/.../plugin/adapters/TransientErrorRetryAdvisorProvider.java` | 删除（迁入插件） |
| `worker/.../plugin/adapters/ContextCompressionAdvisorProvider.java` | 删除（迁入插件） |
| `plugin-api/.../model/ModelRequestNode.java` | 删除 |
| `plugin-api/.../model/ModelRequestChain.java` | 删除 |
| `plugin-api/.../model/ModelRequestContext.java` | 删除 |
| `worker/.../task/ModelRequestChainChatModel.java` | 删除 |
| `worker/.../task/ModelRequestContextImpl.java` | 删除 |
| `worker/.../plugin/registry/ModelRequestNodeRegistry.java` | 删除 |
| `model-rate-limit/.../RateLimitNode.java` | 删除（替换为 RateLimitAdvisor） |

---

## 9. 迁移步骤

### 步骤 1：创建三个新插件模块

1. 创建 `empty-response-retry/` + `transient-error-retry/` + `context-compression/` 三个目录
2. 各自 pom.xml + plugin.json
3. `every-agent-plugins/pom.xml` 增加 `<module>` 行
4. `mvn compile` 验证骨架

### 步骤 2：迁移三个重试/压缩插件

1. 将 6 个类（3 Advisor + ContextCompressor + ContextSummarizer + LlmContextSummarizer）从 `worker/task/` 迁入对应插件模块（改 package + import）
2. 将 3 个 Provider 从 `worker/plugin/adapters/` 迁入
3. 创建 3 个 Plugin 入口类
4. 从 `BuiltInAdvisorProviders` 删除 3 行注册
5. 从 `worker/task/` 和 `worker/plugin/adapters/` 删除原文件
6. `mvn compile` 验证

### 步骤 3：限流从 ModelRequestNode 改为 Advisor

1. 创建 `RateLimitAdvisor.java`（`implements CallAdvisor, StreamAdvisor`，逻辑等价 `RateLimitedChatModel`）
2. 创建 `RateLimitAdvisorProvider.java`（`implements AdvisorProvider`）
3. 修改 `ModelRateLimitPlugin.activate()`：`registerModelRequestNode` → `registerAdvisorProvider`
4. 删除 `RateLimitNode.java`
5. `mvn compile` 验证

### 步骤 4：删除 ModelRequestNode 体系

1. 删除 plugin-api 的 3 个接口文件
2. 删除 worker 的 `ModelRequestChainChatModel` + `ModelRequestContextImpl` + `ModelRequestNodeRegistry`
3. 修改 `WorkerPluginContext` 删除 `registerModelRequestNode()`
4. 修改 `WorkerPluginContextImpl` 删除实现
5. 修改 `PluginLoader` 删除注入
6. 修改 `ChatModelFactory`：移除 `ModelRequestNodeRegistry` 依赖，`build()` 回退直返
7. 修复 8 个测试文件的 `fakeModelFactory` 签名
8. `mvn clean package` 全量验证

### 步骤 5：改造 ai-review

1. `AiAuthReviewer` 注入 `AgentClientFactory`（替代手动构造 ChatClient）
2. 删除 `EmptyResponseRetryAdvisor` / `TransientErrorRetryAdvisor` import
3. 删除手动构造 Advisor 代码，改为 `agentClientFactory.forAgent(reviewEntity, tcm)`
4. `buildReviewEntity` 改 `Kind.MAIN` → `Kind.SUB`
5. `mvn compile` + 运行 ai-review 测试

### 步骤 6：更新文档

1. ARCHITECTURE.md §7.19 重写（删除 ModelRequestNode 章节，限流改记为 Advisor）
2. §7.3 Advisor 链更新（增加 RateLimitAdvisor，标注三个已插件化）
3. §8.5 插件列表增加三个新插件
4. `docs/design-model-rate-limit.md` 更新限流实现方式

---

## 10. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 限流 Advisor 的 `doOnNext` 拿到的是 `ChatClientResponse` 而非 `ChatResponse`，token 提取方式不同 | 低 | 中 | `ChatClientResponse.chatResponse()` 取出 `ChatResponse`，与装饰器拿到的对象一致 |
| 限流 Advisor order=+500 是否正确——在工具循环内侧还是外侧 | 中 | 中 | +500 在 ContextCompression +400 之后，位于工具循环内侧最深处。工具循环每一轮都穿过限流，与装饰器包住单次 HTTP 调用语义一致。需集成测试验证 |
| ai-review 走 `forAgent()` 后 `WorkerToolEventAdvisor` 发射 delta 事件 | 中 | 低 | 审议是一次性调用，事件量小；`reviewAgentId` 独立，前端可分流；事件落原任务 jsonl 不新建 TaskEntry |
| ai-review 用全局重试参数（30 次/3s）而非收敛参数（1 次/1s） | 中 | 低 | `future.get(reviewTimeoutMs)` 硬闸保证总预算不超；建议审议模型配置 `params.retry` 覆盖 |
| `ChatModelFactory.build()` 回退直返后，`ModelPoolChatModel`（池模型）的行为 | 低 | 低 | 池模型由 `ChatModelEnhancer` 构建，不经 `build()` 的直返路径；不受影响 |
| 测试文件 `fakeModelFactory` 签名改 | 低 | 低 | 纯参数删除，编译器检查 |

---

## 11. 验收标准

1. `mvn clean package` 全量构建通过
2. 运行已有测试通过（ContextCompressor 单测、LoopRepeatGuard 测试、限流测试等）
3. 启动 worker，日志中可见 6 个插件（3 新 + model-rate-limit 等）被扫描到并 `activate()` 成功
4. 执行 agent 任务，确认：
   - 重试事件（`request_retry` trace）按预期出现
   - 上下文压缩事件（`context_compression` trace）按预期工作
   - 限流排队事件（`model_rate_wait` trace）按预期工作
5. `ai-review` 插件的 AI 授权审议功能正常（审议会话自动获得重试，无需 import 重试 Advisor）
6. ai-review 的 pom.xml 不依赖 `empty-response-retry` 或 `transient-error-retry`（零跨插件依赖）
7. `grep -r "ModelRequestNode" --include="*.java"` 在 plugin-api 和 worker 中无结果（彻底删除）
8. ARCHITECTURE.md §7.19 已重写，§7.3 / §8.5 已更新
