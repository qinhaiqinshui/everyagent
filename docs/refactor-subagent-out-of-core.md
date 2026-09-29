# 重构方案：将子 Agent 概念从核心移出至插件

## 目标

> subagent 是插件带来的功能，不能放在核心中。核心应开放可以定制 agent 的方法。
> 删除子 agent 插件后，核心完全没有子 agent 概念。

> 分层：task/workflow(以后) -> agent -> 模型层（网络调用等）。
> AgentBuilder 属于 agent 层，不能知道 task 概念。

## 一、分层与核心设计

### 1.1 分层关系

```
┌──────────────────────────────────────────────────────────┐
│ task 层 (task 包)                                         │
│  TaskEntry / TaskManager / TaskStore /                    │
│  TaskLifecycle / RoundIndexStore / TaskEvents / ...      │
│  知道 AgentContext 接口，创建 agent 层组件并传入          │
│  创建 EventEmitter → 传入 agent 层                         │
├──────────────────────────────────────────────────────────┤
│ agent 层 (agent 包 — 从 task 包迁出)                      │
│  AgentBuilder / AgentEntity / AgentRunner /               │
│  接收上层传入的 EventEmitter，包装后向下传播              │
│  只依赖 AgentContext 接口，不知道 TaskEntry               │
├──────────────────────────────────────────────────────────┤
│ 模型层 / 工具层                                           │
│  ChatModel / OpenAiChatOptions / Spring AI / ToolCallback │
│  通过 agent 层传入的 EventEmitter 发射事件                │
└──────────────────────────────────────────────────────────┘
```

### 1.2 EventEmitter 逐层传播（像 OSI 7 层架构）

EventEmitter 是各层向外发射数据的统一通道，**从上层创建，逐层向下传播**。
每层在 emit() 时自动添加本层信息（如 agent 层添加 agentId），然后委托给上层传入的 emitter。

```
task 层 TaskEvents (implements EventEmitter)
  │  构造时注入 mainAgentId
  │  emit() 时: agentId 为 null → 兜底填 mainAgentId → 写入 EventLog
  │
  ▼ 传入
agent 层 AgentEntity (包装 emitter)
  │  构造时接收上层 emitter + 自己的 agentId
  │  emit() 时: agentId 为 null → 填入自己的 agentId → 委托上层 emitter
  │
  ▼ 传入（经 AgentEntity.emitter 暴露）
模型层 / 工具层 / advisor
  │  调用 entity.emitter().emit(EmitEvent)
  │  不需要填 agentId — agent 层自动填入
```

当前代码已有雏形——`AgentEntity` 构造时包装 `task.events`：
```java
// 当前（AgentEntity 构造器内自动包装）
this.agentEmitter = e -> {
    EmitEvent filled = (e.agentId() == null || e.agentId().isEmpty())
            ? e.withAgentId(this.agentId) : e;
    return task.events.emit(filled);  // ← 直接引用 task 层
};
```

**改造：emitter 从上层显式传入，AgentEntity 不再引用 TaskEntry。**

```java
// AgentBuilder.create() 接收 emitter 参数
public static AgentBuilder create(AgentContext ctx, String agentId,
        ChatModel chatModel, OpenAiChatOptions options,
        EventEmitter emitter) {        // ← 从上层传入
    ...
}

// AgentEntity 构造器接收 emitter
public AgentEntity(AgentContext context, String agentId, ...,
        EventEmitter upstreamEmitter) {  // ← 从上层传入
    ...
    // 包装：添加本层信息（agentId），委托上层
    this.emitter = e -> {
        EmitEvent filled = (e.agentId() == null || e.agentId().isEmpty())
                ? e.withAgentId(this.agentId) : e;
        return upstreamEmitter.emit(filled);
    };
}
```

**task 层创建 emitter 并传入：**
```java
// TaskEntry / TaskManager（task 层）
TaskEvents events = new TaskEvents(log, mainAgentId);  // task 层 emitter
// ... 传给 AgentBuilder.create(ctx, agentId, model, options, events)
```

**模型层 / advisor 调用时不需要填 agentId：**
```java
// WorkerToolEventAdvisor
a.emitter().emit(EmitEvent.of(id, "message", null, ...));  // agentId=null
// → agent 层包装自动填入 agentId → task 层兜底/落盘
```

### 1.3 当前问题

- `AgentEntity` 在 `task` 包中，直接持有 `TaskEntry task` 字段
- agent 层代码（WorkerToolEventAdvisor、RoundIndexAdvisor、AgentRunner）通过 `a.task.xxx`
  直接访问 TaskEntry 内部字段——**agent 层向上依赖了 task 层**
- `AgentEntity` 在构造器内自己创建 emitter（包装 `task.events`），而非从上层接收
- `AgentContext` 接口与 `AgentEntity` 功能重叠——两者都持有 agent 运行时上下文数据，
  且 AgentContext 中有 task 概念（taskId/taskTerminal/recordMainUsage），但 task 对 agent 层
  应是黑盒。agent 层核心（AgentRunner）几乎不用 AgentContext 的任何方法
- `AgentEventChannel` 是空接口（`extends EventEmitter` 无任何新增方法），冗余
- `AgentEntity.Kind { MAIN, SUB }` 是核心硬编码的子 agent 概念
- `AgentClientFactory.forMain()/forSub()` 按 Kind 分派

### 1.4 核心改造：AgentBuilder（agent 层）

AgentBuilder 是 agent 层的 Builder 模式装配入口。**不接收 TaskEntry，
也不接收 AgentContext——只接收 EventEmitter + `Map<String, Object> properties`**（从上层传入）。

**properties 是黑盒**：上层往里面放什么完全自主，agent 层核心不读这个 map。
task 层 advisor 自己取出来用（向下转型或按 key 取）。

**核心设计：`create()` 时已从注册表聚合全部工具和 advisor 到内部列表。
之后可用 `.tools(list, mode)` 按模式增删改。**

```java
// agent 层 — 不 import TaskEntry，不 import AgentContext
public class AgentBuilder {

    /** 修改模式：REPLACE=替换全部，ADD=追加，REMOVE=移除匹配项。 */
    public enum ModifyMode { REPLACE, ADD, REMOVE }

    // 必填
    private final String agentId;
    private final ChatModel chatModel;      // 模型层 — 由上层解析后传入
    private final OpenAiChatOptions options;
    private final EventEmitter emitter;     // 从上层传入的事件发射器（逐层传播）
    private final Map<String, Object> properties;  // 上层黑盒数据，agent 层核心不读

    // create() 时已从注册表聚合填充，后续可按模式增删改
    private List<ToolCallback> tools;       // 已填充默认值
    private List<Advisor> advisors;         // 已填充默认值

    // 可选 — 会话
    private final List<Message> conversation = new ArrayList<>();
    private String title = "";

    // 注入的依赖（用于 create() 时默认聚合）
    private final ToolProviderRegistry toolRegistry;
    private final AdvisorProviderRegistry advisorRegistry;
    private final ToolCallingManager defaultTcm;
    private final int maxRepeatedToolRounds;

    // ── 工厂方法 ──
    public static AgentBuilder create(String agentId,
            ChatModel chatModel, OpenAiChatOptions options,
            EventEmitter emitter,                        // 从上层传入
            Map<String, Object> properties) {             // 上层黑盒数据
        // 1. 聚合全部工具: 遍历 toolRegistry.getProviders() → appliesTo → createTools
        // 2. 聚合全部 advisor: 遍历 advisorRegistry.getProviders() → appliesTo → create → 按 order 排序
        // 3. 填充到内部 tools / advisors 列表
    }

    // ── fluent: 按模式增删改 ──
    public AgentBuilder tools(List<ToolCallback> t, ModifyMode mode) { ... }
    public AgentBuilder advisors(List<Advisor> a, ModifyMode mode) { ... }

    // ── fluent: 会话 ──
    public AgentBuilder title(String t) { ... }
    public AgentBuilder conversation(List<Message> c) { ... }
    public AgentBuilder systemPrompt(String p) { ... }
    public AgentBuilder userInput(String i) { ... }

    // ── build ──
    public AgentEntity build() {
        // 1. tcm: defaultTcm + wrapWithGuardIfNeeded
        // 2. 创建 AgentEntity(agentId, chatModel, options, tools, conversation, emitter, properties)
        //    → AgentEntity 构造时包装 emitter：添加 agentId → 委托上游 emitter
        // 3. 装配 ChatClient(chatModel, advisors) → entity.chatClient
        // 4. 返回 entity
    }
}
```

**用法语义：**

- **默认（不调 `.tools()`）**：用 `create()` 聚合的全部工具
- **`.tools(subTools, REPLACE)`**：完全替换为指定列表
- **`.tools(extraTools, ADD)`**：在默认列表后追加
- **`.tools(toRemove, REMOVE)`**：从默认列表中移除匹配项（按工具名匹配）

`advisors()` 同理。

### 1.5 AgentEntity 解耦

```java
// 现在: public final TaskEntry task;          ← agent 层直接依赖 task 层
//       public final EventEmitter agentEmitter; ← 构造器内自动包装 task.events
// 改后: public final EventEmitter emitter;     ← 从上层传入后包装
//       public final Map<String, Object> properties;  ← 上层黑盒数据
```

AgentEntity 构造器：
- 接收 `EventEmitter upstreamEmitter`（从上层传入）
- 接收 `Map<String, Object> properties`（上层黑盒数据，agent 层核心不读）
- 构造时包装 emitter：`this.emitter = e -> { 填 agentId; 委托 upstreamEmitter; }`

**agent 层核心（AgentRunner）完全不碰 properties**——它只需要 agentId、chatClient、emitter。
**task 层 advisor**（WorkerToolEventAdvisor、RoundIndexAdvisor）从 properties 取出 TaskEntry：

```java
// WorkerToolEventAdvisor (task 包内)
TaskEntry t = (TaskEntry) a.properties.get("taskEntry");
if (t.status.terminal()) { ... }
t.recordUsage(...);
```

**TaskEntry 不再实现任何 agent 层接口**——它是 task 层内部类，task 层 advisor 直接引用它。

### 1.6 删除 Kind 和 Scope

| 删除项 | 替代方式 |
|--------|---------|
| `AgentEntity.Kind { MAIN, SUB }` | AgentBuilder + null/非null 控制 |
| `AdvisorProvider.Scope` | AgentBuilder 调用方控制 advisor 列表 |
| `ToolProvider.Scope` | 同上 |
| `AgentClientFactory.forMain/forSub` | `AgentBuilder.build()` |
| `AgentFactory.buildMainAgent/buildAgent` | `AgentBuilder.build()` |
| `AdvisorProviderRegistry.getForMain/getForSub` | `getProviders()` 全量 |
| `ToolProviderRegistry.getForMain/getForSub` | `getProviders()` 全量 |

### 1.7 Usage 统计

所有 agent 的 usage 都记录到 task（经 `AgentContext.recordUsage()`），
任务 usage = 所有 agent usage 总和。AgentEntity 构造时自动注册到 `task.agents`。

---

## 二、包结构迁移

当前 agent 层组件散落在 `task` 包中，需要迁到 `agent` 包：

| 类 | 从 | 到 | 说明 |
|---|---|---|---|
| `AgentEntity` | `task/` | `agent/` | 持有 `AgentContext` 而非 `TaskEntry` |
| `AgentRunner` | `task/` | `agent/` | 用 `entity.chatClient` 执行 |
| `AgentBuilder` | — | `agent/` (新建) | Builder 模式装配入口 |
| `WorkerToolEventAdvisor` | `task/` | `task/` (留) | task 层 advisor，向下转型取 TaskEntry |
| `RoundIndexAdvisor` | `task/` | `task/` (留) | task 层 advisor |
| `InterceptingToolCallingManager` | `task/` | `agent/` | agent 层基础设施 |
| `LoopRepeatGuardToolManager` | `task/` | `agent/` | agent 层守卫 |
| `SchemaStrippedToolCallback` | `task/` | `agent/` | agent 层工具 |
| `ContextOverflow` | `task/` | `agent/` | agent 层诊断 |
| `ModelCallException` | `task/` | `agent/` | agent 层异常 |
| `AgentCancelledException` | `task/` | `agent/` | agent 层异常 |
| `AgentActivity` | `task/` | `agent/` | agent 层状态 |
| `RootCause` | `task/` | `agent/` | agent 层工具 |

---

## 三、变更清单

### Phase 1: 删除 AgentContext + AgentEventChannel

**AgentContext 与 AgentEntity 功能重叠**：两者都持有 agent 运行时上下文数据。AgentContext 的方法只被 task 层 advisor 调用（WorkerToolEventAdvisor），agent 层核心（AgentRunner）几乎不用。AgentContext 中有 task 概念（taskId/taskTerminal/recordMainUsage），但 task 对 agent 层应是黑盒。

**AgentEventChannel 是空接口**（`extends EventEmitter` 无任何新增方法），emitter 改为直接传入后完全冗余。

**方案：删除两个接口，AgentEntity 持有 `Map<String, Object> properties` 替代。**

| # | 文件 | 变更 |
|---|------|------|
| 1 | `plugin-api/agent/AgentEventChannel.java` | **删除**。空接口，EventEmitter 直接使用。 |
| 2 | `plugin-api/agent/AgentContext.java` | **删除**。agent 层不需要 task 层的接口。 |
| 3 | `plugin-api/spi/ToolExecutionContext.java` | `AgentContext agentContext()` → `Map<String, Object> properties()`（黑盒属性，插件自行取）。 |
| 4 | `task/InterceptingToolCallingManager.java` | ThreadLocal 类型从 `AgentContext` → `Map<String, Object>`。`setCurrentAgentContext` → `setCurrentProperties`。`ToolExecutionContextImpl` 的 `agentContext` 字段改 `properties`。 |
| 5 | `task/TaskEntry.java` | 删除 `implements AgentContext`。删除 `events()`、`taskTerminal()`、`recordMainUsage()`、`workspaceRoot()` 方法（这些不再是接口实现，而是 TaskEntry 自身方法，调用方直接调）。 |

**TaskEntry 不再实现任何 agent 层接口**——它是 task 层的内部类，task 层 advisor 直接引用它。

### Phase 2: 新建 AgentBuilder + AgentEntity 重构

| # | 文件 | 变更 |
|---|------|------|
| 6 | `agent/AgentBuilder.java` **(新建)** | Builder 模式装配入口。必填参数：`agentId` + `ChatModel` + `OpenAiChatOptions` + **`EventEmitter emitter`**（从上层传入）+ **`Map<String, Object> properties`**（上层黑盒数据）。fluent setter：tools/advisors（按 ModifyMode 增删改）/ conversation / title / systemPrompt / userInput。`build()` 聚合注册表默认值 + 装配 ChatClient + 创建 AgentEntity（传入 emitter + properties）。 |
| 7 | `agent/AgentEntity.java` **(从 task/ 迁入)** | 删除 `TaskEntry task`、`Kind` 枚举、`AgentContext context`。新增：`ChatClient chatClient`（AgentBuilder 装配注入）、`EventEmitter emitter`（从上层传入，构造时包装：填 agentId → 委托上游）、`Map<String, Object> properties`（上层黑盒数据，agent 层核心不读）。 |
| 8 | `agent/AgentRunner.java` **(从 task/ 迁入)** | 不再依赖 `AgentClientFactory`。`run()` 直接用 `a.chatClient`。`a.task` 引用全部删除。`setCurrentAgentContext(a.task)` → `setCurrentProperties(a.properties)`。 |

### Phase 3: 删除旧装配入口

| # | 文件 | 变更 |
|---|------|------|
| 5 | `AgentClientFactory.java` | **删除**。职责由 AgentBuilder 取代。`toolCallingManager()` @Bean 和 `skillAdvisor()` @Bean 移到新的 @Configuration。`wrapWithGuardIfNeeded()` 移到 AgentBuilder。 |
| 6 | `agent/AgentFactory.java` | **删除**。`buildMainAgent()` → AgentBuilder 默认 `build()`。`buildAgent()` + `SUB_SYSTEM_PROMPT` → subagent 插件。`resolveAgentConfig()` → task 层调 AgentBuilder 前解析。 |

### Phase 4: 注册表 + SPI 清理

| # | 文件 | 变更 |
|---|------|------|
| 7 | `plugin/registry/AdvisorProviderRegistry.java` | 删 `getForMain()/getForSub()`，只留 `getProviders()`。 |
| 8 | `plugin/registry/ToolProviderRegistry.java` | 同上。 |
| 9 | `plugin-api/spi/AdvisorProvider.java` | 删 `Scope` 枚举和 `scope()`。保留 `appliesTo()` 默认 `true`。 |
| 10 | `plugin-api/spi/ToolProvider.java` | 同上。 |

### Phase 5: 内置 Provider 适配器

| # | 文件 | 变更 |
|---|------|------|
| 11-18 | RoundIndexAdvisorProvider / SkillAdvisorProvider / SlashTokenResolveAdvisorProvider / WorkerToolEventAdvisorProvider / AskUserToolProvider / FileToolsProvider / BashToolProvider / PowerShellToolProvider | 删 `scope()`，删 `Scope` import |

### Phase 6: task 层 advisor 适配

| # | 文件 | 变更 |
|---|------|------|
| 19 | `task/WorkerToolEventAdvisor.java` | `a.task.xxx` → `((TaskEntry) a.properties.get("taskEntry")).xxx`。删 `if (a.kind == Kind.MAIN)` 判断——所有 agent 都记录 usage + 触发广播。 |
| 20 | `task/RoundIndexAdvisor.java` | `a.task.xxx` → `((TaskEntry) a.properties.get("taskEntry")).xxx`。删 `if (a.kind != Kind.MAIN)` 判断。 |
| 21 | `task/RoundIndexAdvisorProvider.java` | `create()` 时从 `AgentEntity.properties` 取 TaskEntry（或直接从 AdvisorContextImpl 获取）。 |

### Phase 7: TaskEntry 重构

| # | 文件 | 变更 |
|---|------|------|
| 22 | `task/TaskEntry.java` | 删除 `implements AgentContext`。删除 `subs`/`subFutures`/`stopRequested`。`subs` → `agents`（Map<String, AgentEntity>）。`totalUsage(main)` → `totalUsage()`。`subList()` → `agentList()`。`recordUsage()` / `recordMainUsage()` 保留为自身方法（不再实现接口）。不再实现 `events()` / `taskTerminal()` / `workspaceRoot()` / `taskId()` 作为接口方法（它们是自身字段直接暴露）。 |

### Phase 8: 子 Agent 管理移出

| # | 文件 | 变更 |
|---|------|------|
| 23 | `task/SubAgentManager.java` | **移至 subagent 插件**。 |
| 24 | `agent/AgentService.java` | **删除**（其方法参数用 `AgentContext`，接口已删）。 |
| 25 | `agent/AgentServiceImpl.java` | **删除**。 |

### Phase 9: 其他引用 AgentContext 的代码适配

| # | 文件 | 变更 |
|---|------|------|
| 26 | `plugins/unattended/UnattendedToolInterceptor.java` | `AgentContext agentCtx = ctx.agentContext()` → `Map props = ctx.properties()`。`agentCtx instanceof TaskInfo` → `props.get("taskEntry") instanceof TaskInfo`。 |
| 27 | `plugins/subagent/SubAgentTools.java` | `AgentContext ctx` → 不再依赖接口。改为持有 `Map<String, Object> properties` 或直接持有插件自己的 manager。 |
| 28 | `plugins/subagent/SubAgentToolsProvider.java` | 创建 SubAgentTools 时传入 properties 或直接传入 manager。 |
| 29 | `plugin-api/spi/ToolExecutionContext.java` | `AgentContext agentContext()` → `Map<String, Object> properties()`（已在 Phase 1 变更）。 |
| 30 | `task/InterceptingToolCallingManager.java` | ThreadLocal 类型改 `Map<String, Object>`（已在 Phase 1 变更）。 |

### Phase 10: 生命周期节点清理

| # | 文件 | 变更 |
|---|------|------|
| 31 | `task/lifecycle/SpawnedAwaitNode.java` | **移至 subagent 插件**。 |
| 32 | `task/lifecycle/CascadeStopNode.java` | 删 `SubAgentManager` 依赖。保留 `asks.cancelTask` + error 事件。 |
| 33 | `task/lifecycle/BuiltInTaskLifecycleNodes.java` | 删 `SubAgentManager` 注入和 `SpawnedAwaitNode` 注册。 |

### Phase 11: 配置与工具清理

| # | 文件 | 变更 |
|---|------|------|
| 34 | `config/WorkerProperties.java` | 删 `maxConcurrentSubs`、`subWaitTimeoutMs`。 |
| 35 | `proto/ShortIds.java` | 删 `subAgentId()`。 |
| 36 | `proto/ConfigDtos.java` | 删 `maxConcurrentSubs`、`subWaitTimeoutMs`。 |

### Phase 12: TaskManager 清理

| # | 文件 | 变更 |
|---|------|------|
| 37 | `task/TaskManager.java` | 删 `SubAgentManager` 注入。主 agent 创建改用 `AgentBuilder.create(agentId, model, options, emitter, properties)`。创建 properties map 并填入 taskEntry 等。 |

### Phase 13: WorkerPluginContext 清理

| # | 文件 | 变更 |
|---|------|------|
| 38 | `plugin-api/WorkerPluginContext.java` | Javadoc 移除 `AgentService` 引用。 |

### Phase 14: RoundIndex 泛化

| # | 文件 | 变更 |
|---|------|------|
| 39 | `task/RoundIndex.java` | `SubRange` → `AgentRange`。`Round.subs` → `Round.agentRanges`。 |
| 40 | `task/RoundIndexStore.java` | `SubBuilder` → `AgentRangeBuilder`。 |
| 41 | `task/TaskStore.java` | JSON 字段 `subs` → `agentRanges`（兼容旧名）。 |

### Phase 15: subagent 插件改造

| # | 文件 | 变更 |
|---|------|------|
| 42 | `SubAgentPlugin.java` | 直接构造 SubAgentManager。注册生命周期节点。 |
| 43 | `SubAgentManager.java`（移入） | 用 `AgentBuilder.create(agentId, model, options, emitter, props).tools(toRemove, REMOVE).advisors(toRemoveAdv, REMOVE).systemPrompt(...).userInput(input).build()` 装配。agent 台账由插件管理。 |
| 44 | `SubAgentTools.java` | 不再依赖 `AgentContext`。直接持有插件 SubAgentManager 或 properties。 |
| 45 | `SubAgentToolsProvider.java` | 删 `scope()`。 |
| 46 | 新增 `SubAgentSpawnedAwaitNode.java` | 从核心移入。 |
| 47 | 新增 `SubAgentCascadeStopNode.java` | 插件级联停止。 |

### Phase 16: 其他插件 Scope 适配

| # | 文件 | 变更 |
|---|------|------|
| 48-59 | system-info / agents-md / empty-response-retry / transient-error-retry / context-compression / model-length-guard / adaptive-max-tokens / model-rate-limit (2) / task-input-queue / file-change / git / unattended | 删 `scope()`，删 `Scope` import。unattended 的 `ToolExecutionContext.agentContext()` → `properties()` 适配。 |

### Phase 17: 插件中 Kind 引用清理

| # | 文件 | 变更 |
|---|------|------|
| 60 | `file-change/FileChangeAdvisor.java` | `a.kind == Kind.MAIN` → 删除判断。 |
| 61 | `ai-review/AiAuthReviewer.java` | `new AgentEntity(..., Kind.SUB, ...)` → `AgentBuilder.create(...).build()`。 |

### Phase 18: 测试适配

| # | 文件 | 变更 |
|---|------|------|
| 62-64 | model-length-guard / adaptive-max-tokens / ai-review 测试 | `Kind.MAIN/SUB` → AgentBuilder。 |

---

## 四、用法示例

### task 层创建主 agent（默认组装）
```java
// task 层解析模型配置
ResolvedConfig cfg = configs.resolve(t.snapshot.configId());
ChatModelFactory.AgentModel am = modelFactory.buildAgentModel(cfg, t.mainAgentId, t.events, null);

// task 层创建 emitter + properties（黑盒数据）
EventEmitter emitter = t.events;
Map<String, Object> props = new HashMap<>();
props.put("taskEntry", t);          // task 层 advisor 用
props.put("taskId", t.taskId);      // 日志用
props.put("workspaceRoot", t.workspaceRoot);  // 工具装配用

// create() 已聚合全部工具 + 全部 advisor，传入 emitter + properties，直接 build()
AgentEntity main = agentBuilder.create(t.mainAgentId, am.chatModel(), am.options(), emitter, props)
    .title("主 agent")
    .conversation(priorConversation)
    .build();
runner.run(main);
```

### subagent 插件创建子 agent（REMOVE 模式裁剪）
```java
// 插件从上层获取 emitter + properties
EventEmitter emitter = ...;  // 从 task 层传入
Map<String, Object> props = ...;  // 从 task 层传入

// 从 builder 取出默认聚合的工具，按工具名移除子 agent 工具和 ask_user
List<ToolCallback> toRemove = findToolsByName(builder, "run_agent", "list_agents",
        "wait_agents", "stop_agent", "ask_user");

AgentEntity sub = agentBuilder.create(agentId, model, options, emitter, props)
    .title(title)
    .tools(toRemove, AgentBuilder.ModifyMode.REMOVE)
    .advisors(roundIndexAndSkillAdvisors, AgentBuilder.ModifyMode.REMOVE)
    .systemPrompt(SUB_SYSTEM_PROMPT)
    .userInput(input)
    .build();
runner.run(sub);
```

### ai-review 插件创建审议 agent（REPLACE 模式完全自定义）
```java
EventEmitter emitter = ...;
Map<String, Object> props = ...;

AgentEntity review = agentBuilder.create(reviewAgentId, reviewModel, reviewOptions, emitter, props)
    .title("AI 安全审议")
    .tools(List.of(), AgentBuilder.ModifyMode.REPLACE)
    .advisors(reviewAdvisors, AgentBuilder.ModifyMode.REPLACE)
    .systemPrompt(reviewSystemPrompt)
    .userInput(userPrompt)
    .build();
runner.run(review);
```

---

## 五、删除 subagent 插件后的效果

核心代码搜索 `sub` / `Sub` / `子 agent` / `SubAgent` / `Kind` → **零命中**。
核心代码搜索 `AgentContext` / `AgentEventChannel` → **零命中**。

分层干净：
- **task 层** 创建 EventEmitter + properties map，传入 AgentBuilder，不关心 agent 内部组装
- **agent 层** 只依赖 EventEmitter + Map<String, Object> + 模型层，不知道 TaskEntry、不知道 AgentContext
- **properties 是黑盒**：agent 层核心（AgentRunner）完全不读；task 层 advisor 从中取出 TaskEntry 使用
- **EventEmitter 逐层传播**：上层创建 → 传入 agent 层包装（填 agentId） → 暴露给 advisor/工具调用
- **插件** 用 AgentBuilder 自定义 tools/advisors，创建完全定制的 agent
