# 通用事件发射器 + 模型限流器抽成独立插件

## 目标

**核心目标**：设计并实现 `EventEmitter` — 通用事件发射器（plugin-api 接口），作为插件/底层组件发射事件的统一出口。配套 `WebSocketEmitter`（被动回调式推送管道，接管 DataPusher 的前端推送职责）。限流插件是第一个使用者，后续 `AgentEventChannel` 的 30 个方法逐步迁移到 `emit`。

**次级目标**：将 worker 核心中模型请求限流逻辑抽成 `every-agent-plugins/model-rate-limit` 独立插件。插件经模型请求洋葱链（`ModelRequestNode`，仿任务洋葱链 `TaskLifecycleNode`）插入限流逻辑，不耦合 `ChatModel`。

## 设计：EventEmitter 通用事件发射器

### 接口定义（plugin-api）

```java
public interface EventEmitter {
    void emit(String eventName, JsonNode payload, boolean persist);
}
```

### 分层管道

```
插件（限流器）
  │  emitter.emit("model_rate_wait", {configId, waiters, inFlight, tpmPressure}, false)
  │  ↑ 语义事件名，不是 wire 格式；不知道 task.trace / wf.trace
  ▼
agent 层（ChatModelFactory.build() 内部 lambda）
  │  填上 agentId，转发给 task 层
  ▼
task 层（TaskEvents.emit）
  │  把语义事件包装成 wire 格式：
  │  "model_rate_wait" → task.trace（payload 加 traceId/kind/title/summary/status/createdAt，原始 payload 作为 metadata）
  │  "delta" → delta（直接透传）
  │  "error" → error（直接透传）
  │  然后 log.append(wireEventName, wirePayload, agentId, ext, !persist)
  │  EventLog.onAppend → DataPusher.readFrom → wsEmitter.push → conn.pub → 前端
  ▼
前端
  收到 task.trace 帧按 kind 映射显示
```

> **语义→wire 映射在 task 层**。插件只管发语义事件名 + payload，不知道 wire 格式（task.trace / wf.trace）。以后工作流层实现自己的映射：`model_rate_wait` → `wf.trace`（工作流自己的包装格式）。前端按各自层的 wire 格式解析显示。

> **TaskEvents.emit() 只写 EventLog**（包装 + log.append），不直接调 WebSocketEmitter。推送仍由 DataPusher 的 onAppend → readFrom → push 链处理（push 委托给 wsEmitter）。与当前行为完全一致，只是 push() 内部从 acquireCredit + conn.pub 改为 wsEmitter.push()。前端零改动。

## 设计：WebSocketEmitter 被动推送管道

只做：**背压控制 + 定向推送到前端 websocket**。不读 EventLog、不回扫、不对账。

```java
// worker 内部
public class WebSocketEmitter {
    private final String sessionId;
    private final String streamKey;
    private final HubLink conn;
    // 背压（从 DataPusher 原样搬来）
    private static final int CREDIT_WINDOW = 128;
    private long nextPushIndex = 0;
    private volatile long ackedIndex = -1;

    /** 被动回调：接收组装好的帧，背压 + conn.pub */
    public void push(String channel, String event, Long seq, long ts,
                     JsonNode payload, ObjectNode ext) {
        long creditIndex = acquireCredit();
        ext.put("credit", true);
        ext.put("creditIndex", creditIndex);
        conn.pub(channel, event, seq, ts, payload, ext);
    }

    private long acquireCredit() { /* 从 DataPusher 原样搬来 */ }
    public void onAck(long creditIndex) { /* 从 DataPusher 原样搬来 */ }
}
```

### DataPusher 改动（最小化）

DataPusher 的所有逻辑不变（reconcile/readFrom/回扫/initial/ext 组装/payload 格式化），只把 `push()` 里的 `acquireCredit() + conn.pub()` 替换为调 `wsEmitter.push()`：

```java
// DataPusher.push() — 改后
private void push(EventRecord r, boolean replay) {
    // ext 组装（不变）
    // payload 格式化（不变）
    // 委托 WebSocketEmitter 推送（替代 acquireCredit + conn.pub）
    wsEmitter.push(channel, r.event(), r.seq(), r.ts(), payload, ext);
}
```

DataPusher 删除 `acquireCredit`/`onAck`/背压字段（CREDIT_WINDOW/nextPushIndex/ackedIndex），背压职责移到 WebSocketEmitter。`DataPusherManager.onAck` 路由到 WebSocketEmitter。

**前端零改动**：收到的帧格式完全不变。

## 设计：ModelRequestNode 模型请求洋葱链

### ModelConfig 搬到 plugin-api

`ModelSnapshot` 当前是 `TaskDtos` 中的 record（`configId / provider / baseUrl / model / params`），是纯数据，只依赖 `JsonNode`（Jackson，plugin-api 已有）。搬到 plugin-api 后**改名为 `ModelConfig`**，插件可直接获取完整模型配置信息。worker 现有引用（`TaskEntry` / `TaskEvents` / `ModelPoolChatModel` / `ConfigStore` / `ChatModelFactory` / `ContextOverflow`）统一改 import 路径 + 类名。

### 接口定义（plugin-api）

```java
public interface ModelRequestNode {
    String id();
    float order();
    Object invoke(ModelRequestContext ctx, ModelRequestChain next) throws Exception;
}

@FunctionalInterface
public interface ModelRequestChain {
    Object proceed(ModelRequestContext ctx) throws Exception;
}

public interface ModelRequestContext {
    /**
     * 模型配置快照（只读参考）。
     * 含 configId / provider / baseUrl / model / params。
     * 插件可据此做限流参数解析、日志、自适应决策等。
     * 注意：下游内核使用缓存的 ChatModel（按 configId 缓存 OpenAiChatModel），
     * 不消费此配置。修改此配置不影响实际请求。
     * 未来支持动态配置时，内核改为从 context 取模型（版本化缓存），接口不变。
     */
    ModelConfig config();
    EventEmitter events();
    /** 注册 chunk 回调（内核 doOnNext 时调用）*/
    void onChunk(Consumer<String> callback);
    /** 注册完成回调（内核检测到 usage 帧或 call 返回时调用）*/
    void onComplete(LongConsumer callback);
    /** 注册错误/取消回调（内核 doOnError/doOnCancel 时调用）*/
    void onError(Consumer<Throwable> callback);
}
```

> **回调注册模式**：节点在 `next.proceed()` 之前注册回调，proceed 对于 stream 路径返回 Flux（deferred，尚未订阅）。节点的阻塞等待（如 limiter.acquire）发生在 Flux.defer 内（订阅时执行），注册回调在 acquire 返回后、proceed 之前——顺序正确。

### worker 侧组装洋葱链

`ChatModelFactory.build()` 构建薄壳 `ModelRequestChainChatModel`：

```java
class ModelRequestChainChatModel implements ChatModel {
    private final ChatModel delegate;       // 缓存的 OpenAiChatModel（按 configId 复用，不消费 context）
    private final List<ModelRequestNode> nodes;
    private final EventEmitter emitter;
    private final ModelConfig config;       // 只读参考，传给插件节点

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            // 订阅时执行（虚拟线程上，可安全阻塞）
            ModelRequestContextImpl ctx = new ModelRequestContextImpl(config, emitter);
            return (Flux<ChatResponse>) executeChain(nodes, 0, ctx, prompt);
        });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // 同步执行（无 defer）
        ModelRequestContextImpl ctx = new ModelRequestContextImpl(config, emitter);
        Object result = executeChain(nodes, 0, ctx, prompt);
        // call 完成后触发 onComplete
        ChatResponse resp = (ChatResponse) result;
        ctx.invokeOnComplete(outputTokensOf(resp));
        return resp;
    }

    private Object executeChain(List<ModelRequestNode> nodes, int index,
            ModelRequestContextImpl ctx, Prompt prompt) {
        if (index >= nodes.size()) {
            // 内核：真实模型调用（使用缓存的 delegate，不消费 context）
            Flux<ChatResponse> flux = delegate.stream(prompt)
                .doOnNext(chunk -> {
                    String text = chunk.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) ctx.invokeOnChunk(text);
                    Usage usage = chunk.getMetadata() == null ? null : chunk.getMetadata().getUsage();
                    if (usage != null && usage.getCompletionTokens() != null
                            && usage.getCompletionTokens() > 0) {
                        ctx.invokeOnComplete(usage.getCompletionTokens());
                    }
                })
                .doOnError(ctx::invokeOnError)
                .doOnCancel(() -> ctx.invokeOnError(new CancellationException()))
                .doOnComplete(() -> ctx.invokeOnComplete(0));  // 无 usage 帧兜底
            return flux;
        }
        return nodes.get(index).invoke(ctx,
            c -> executeChain(nodes, index + 1, (ModelRequestContextImpl) c, prompt));
    }
}
```

`ModelRequestContextImpl` 收集所有注册的回调，内核通过 `invokeOnChunk` / `invokeOnComplete` / `invokeOnError` 逐个调用：

```java
class ModelRequestContextImpl implements ModelRequestContext {
    private final ModelConfig config;
    private final EventEmitter emitter;
    private final List<Consumer<String>> chunkCallbacks = new CopyOnWriteArrayList<>();
    private final List<LongConsumer> completeCallbacks = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> errorCallbacks = new CopyOnWriteArrayList<>();

    @Override public ModelConfig config() { return config; }
    @Override public EventEmitter events() { return emitter; }
    public void onChunk(Consumer<String> cb) { chunkCallbacks.add(cb); }
    public void onComplete(LongConsumer cb) { completeCallbacks.add(cb); }
    public void onError(Consumer<Throwable> cb) { errorCallbacks.add(cb); }

    void invokeOnChunk(String text) { chunkCallbacks.forEach(cb -> cb.accept(text)); }
    void invokeOnComplete(long tokens) { completeCallbacks.forEach(cb -> cb.accept(tokens)); }
    void invokeOnError(Throwable e) { errorCallbacks.forEach(cb -> cb.accept(e)); }
}
```

### 限流插件实现 ModelRequestNode

```java
public class RateLimitNode implements ModelRequestNode {
    private final ModelRateLimiterRegistry registry;

    @Override
    public Object invoke(ModelRequestContext ctx, ModelRequestChain next) throws Exception {
        Optional<ModelRateLimiter> limiter = registry.of(ctx.config().configId(), ctx.config().params());
        if (limiter.isEmpty()) return next.proceed(ctx);  // 无限流配置 → 直通

        // 下行：限流排队（阻塞等待放行，虚拟线程 park）
        ModelRateLimiter.Permit permit = limiter.get().acquire(waitInfo -> {
            // 经 EventEmitter 发语义事件（不知道 wire 格式/task.trace/wf.trace）
            ObjectNode payload = Json.obj()
                .put("configId", waitInfo.configId())
                .put("waiters", waitInfo.waiters())
                .put("inFlight", waitInfo.inFlight())
                .put("tpmPressure", waitInfo.tpmPressure());
            ctx.events().emit("model_rate_wait", payload, false);  // false = 瞬态不落盘
        });

        // 注册回调：绑定 Permit 生命周期到 Flux 实际执行
        ctx.onChunk(permit::onChunk);       // 流中逐 chunk 累计估算
        ctx.onComplete(permit::complete);  // usage 帧到达时记账
        ctx.onError(e -> permit.cancel()); // 异常/取消时释放

        // 继续（stream 路径返回 Flux，尚未订阅；call 路径同步返回）
        return next.proceed(ctx);
    }
}
```

> **时序**：
> 1. 前端订阅 → `Flux.defer` 触发执行
> 2. 洋葱链从外到内执行 → `RateLimitNode.invoke()` 调 `limiter.acquire()` → **阻塞挂起**（虚拟线程 park）
> 3. 限流器放行 → `acquire()` 返回 Permit
> 4. 注册回调：`ctx.onChunk(permit::onChunk)` / `ctx.onComplete(permit::complete)` / `ctx.onError(permit::cancel)`
> 5. `next.proceed(ctx)` → 到达内核 → `delegate.stream(prompt)` → 返回 Flux
> 6. 外层返回 Flux → Reactor 订阅 → chunk 到达 → `doOnNext` → `ctx.invokeOnChunk()` → `permit.onChunk(text)` 累计估算
> 7. usage 帧到达 → `ctx.invokeOnComplete(tokens)` → `permit.complete(tokens)` 记账
> 8. 异常/取消 → `ctx.invokeOnError(e)` → `permit.cancel()` 释放

**插件只知道 `ModelRequestContext`（ModelConfig + EventEmitter），不耦合 `ChatModel` / `Prompt` / `ChatResponse` / `Flux` / `TaskEvents` / `agentId` / `taskId` / `DataPusher`。**

### 关于内核不消费 ModelConfig（本次不做）

当前内核使用缓存的 `OpenAiChatModel`（按 configId 缓存，所有 agent 共用同一 OkHttp 客户端和线程资源）。context 里的 `ModelConfig` 是只读参考，不影响实际 HTTP 请求。

**原因**：openai-java SDK 每次 `OpenAiChatModel.builder().build()` 会创建新的 OkHttp ConnectionPool + Dispatcher 线程池 + DefaultSleeper Timer + StreamHandler 线程，这些资源不被 GC 回收，不缓存会导致线程泄漏。

**未来支持动态配置**：内核改为从 context 取模型（版本化缓存：configId + configVersion → OpenAiChatModel，配置没变就复用，变了才新建 + 旧实例显式 close）。接口不变，只改内核实现。本次不做。

## 步骤

- [x] 步骤 1：plugin-api 新增 `EventEmitter` + `ModelRequestNode` + `ModelRequestContext` + `ModelRequestChain` + `ModelConfig` 搬迁改名
    - 状态：已完成（commit 550062d）
    - agent：`sub_hkl3f`
    - 依赖：无
    - 验收标准：
      - `every-agent-plugin-api` 新增 `EventEmitter`（`emit(String eventName, JsonNode payload, boolean persist)`）
      - 新增 `ModelRequestNode`（洋葱链节点，仿 `TaskLifecycleNode`）
      - 新增 `ModelRequestContext`（`config()` 返回 `ModelConfig` / `events()` 返回 `EventEmitter` / `onChunk` / `onComplete` / `onError` 回调注册）
      - 新增 `ModelRequestChain`（`proceed(ctx)`）
      - `ModelSnapshot` 从 `TaskDtos` 搬到 `plugin-api`，**改名为 `ModelConfig`**（纯 record，依赖只有 `JsonNode`）；worker 现有引用统一改 import 路径 + 类名（`ModelSnapshot` → `ModelConfig`）
      - `WorkerPluginContext` 新增 `registerModelRequestNode()` 方法
      - worker 新增 `ModelRequestNodeRegistry`（@Component，同 ToolProviderRegistry 模式）

- [x] 步骤 2：worker 核心实现事件管道 + WebSocketEmitter + ChatModelFactory 改用洋葱链
    - 状态：已完成（commit 178f829）
    - agent：`sub_hkl3g`
    - 依赖：步骤 1
    - 验收标准：
      - `TaskEvents.emit()` 新增（语义→wire 映射 + log.append 写 EventLog，推送仍由 DataPusher onAppend 链处理）
      - `WebSocketEmitter` 新增（背压 + conn.pub，从 DataPusher 搬来背压逻辑）
      - `DataPusher.push()` 改为调 `wsEmitter.push()`（其余全不变；删 acquireCredit/onAck/背压字段）
      - `DataPusherManager.onAck` 路由到 WebSocketEmitter
      - `ChatModelFactory.build()` 构造 EventEmitter lambda + ModelRequestChainChatModel 薄壳（传入 ModelConfig）
      - `ModelRequestContextImpl` 实现回调注册 + invoke 方法
      - `AdvisorContext` 新增 `configId()` 方法（`AdvisorContextImpl` 从 `agentEntity.task.snapshot.configId()` 填充）
      - `ModelRateLimiterRegistry` 从 worker 删除
      - `build()` / `buildAgentModel()` / `buildPool()` 签名不变
      - worker 保留 fallback `SimpleTokenEstimator`
      - **前端零改动**

- [x] 步骤 3：创建 `every-agent-plugins/model-rate-limit` 插件模块
    - 状态：已完成（commit 8da92d0）
    - agent：`sub_hkl3h`
    - 依赖：步骤 1、2
    - 验收标准：目录/pom/plugin.json/Plugin 入口类建立；`every-agent-plugins/pom.xml` 加入 module

- [~] 步骤 4：搬迁限流逻辑到插件
    - 状态：进行中（已派发）
    - agent：`sub_hkl3i`
    - 依赖：步骤 3
    - 验收标准：6 个文件迁到插件包（ModelRateLimiter / RateLimitedChatModel→RateLimitNode / ModelRateLimitConfig / ModelRateLimiterRegistry / BuiltinTokenEstimator / TokenCalibrationAdvisor + Provider）；测试一并搬迁；`WorkerProperties.ModelRate` 留 worker；插件发语义事件名（如 `model_rate_wait`），task 层映射为 wire 格式（如 `task.trace` + kind）；`RateLimitNode` 从 `ctx.config().configId()` 和 `ctx.config().params()` 获取限流参数

- [ ] 步骤 5：worker 核心清理残留
    - 状态：待执行
    - agent：-
    - 依赖：步骤 4
    - 验收标准：worker 无限流器引用；`ConfigRpcHandler` 不再调 `rateLimitSnapshots()`；fallback `SimpleTokenEstimator` 就位；编译通过

- [ ] 步骤 6：ARCHITECTURE.md 文档更新
    - 状态：待执行
    - agent：-
    - 依赖：步骤 5

- [ ] 步骤 7：验证编译与测试
    - 状态：待执行
    - agent：-
    - 依赖：步骤 6

## 备注

### 不搬的东西
- `WorkerProperties.ModelRate` 配置类留 worker（全局配置项）
- `TokenEstimator` SPI 接口留 plugin-api（`ModelLengthGuardAdvisor` 也依赖）
- `WorkerPluginContext.registerTokenEstimator()` 不动（已存在）
- `AgentEventChannel` 不动（30 方法胖接口是独立架构债，后续逐步迁移到 `emit`）

### 回退方案
- 插件未加载时：fallback `SimpleTokenEstimator`，无 `ModelRequestNode` → 直通 raw model → 不限流
- WebSocketEmitter 未建立时（无前端订阅）：`emit` 仍写 EventLog，不推前端

### 风险点
1. **`ModelRequestChainChatModel` 的 `call()` 路径**：同步执行，call 完成后触发 onComplete；非流式路径无需 Flux.defer
2. **`AgentEntity` 类型不暴露**：`TokenCalibrationAdvisor` 搬到插件后经 `AdvisorContext.configId()` 获取 configId（本次新增此方法）
3. **`ConfigRpcHandler.rateStatus`**：插件自己注册 RPC，worker 核心不再调
4. **`ModelPoolChatModel` 继续收 `TaskEvents`**：worker 核心类不走洋葱链
5. **流式 `onChunk` 回调线程安全**：`CopyOnWriteArrayList` 保护回调列表；`permit.onChunk` 内部 `synchronized(monitor)` 保护
6. **WebSocketEmitter 背压共享**：DataPusher 回扫路径和 EventEmitter 实时路径共用同一背压窗口，并发调 `push()` 需线程安全（`acquireCredit` 已有 `synchronized` 保护）
7. **语义→wire 映射在 task 层**：插件发语义事件名（如 `model_rate_wait`），task 层包装成 wire 格式（如 `task.trace`）。需在 `TaskEvents.emit()` 中做映射，确保与现有 `appendTrace()` 格式一致。以后工作流层做自己的映射。
8. **ModelConfig 搬迁兼容**：`ModelSnapshot`（→ 改名 `ModelConfig`）被 `TaskEntry` / `TaskEvents` / `ModelPoolChatModel` / `ConfigStore` / `ChatModelFactory` / `ContextOverflow` 等多处引用，搬到 plugin-api 后这些引用改为新包路径 + 新类名。
9. **内核不消费 ModelConfig**：本次内核仍用缓存的 `OpenAiChatModel`，context 里的 `ModelConfig` 是只读参考。未来动态配置需版本化缓存（configId + version → 实例），旧实例显式 close。

### 后续迁移路径
- Phase 1（本次）：EventEmitter 接口 + WebSocketEmitter + 限流插件第一个使用
- Phase 2：ModelPoolChatModel.modelFailoverSwitch → emit
- Phase 3：Advisor 层事件（delta/thinking/usage/retry）→ emit
- Phase 4：生命周期事件（agent_started/done/status/error）→ emit
- Phase 5：AgentEventChannel 瘦身为仅含 emit 的接口
- Phase 6：内核消费 ModelConfig（版本化缓存，支持动态配置热更新）
