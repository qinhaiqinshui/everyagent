# 统一执行上下文（ExecContext）方案

项目处于开发状态，尚未上线，不用兼容旧数据，旧数据用户会自行删除。

## 0. 摘要

当前 worker 的执行管道里存在**六套上下文 + 一个无类型黑盒 map**。黑盒 map
（`properties: {taskEntry, taskId, workspaceRoot, configId}`）是事实上的执行主干道：
task 层填入 → agent 层透传 → 20+ 处 advisor 强转 `get("taskEntry")` 取用 → 工具链、
授权链、子 agent、审议 agent 各自再手工组装一遍。

本方案引入**统一执行上下文 `ExecContext`**（plugin-api），把黑盒四件套显式类型化，
沿 `task 层 → agent 层 → 工具执行链 → 授权链` 逐层往下传，形成与 §14.0 事件管道
分层同构的**执行上下文管道**。task 域类型（`TaskEntry`/`TaskRuntime`/`TaskInfo`）
从所有横切层（advisor、工具、授权、子 agent、审议）的可见面消失；未来工作流层
实现自己的 `ExecContext` 即可复用全部基础设施，横切层零改动。

## 1. 背景与问题

### 1.1 起点：授权链域耦合

授权决议链 `AuthorizationRequest(TaskInfo task, agentId, grantKey, prompt)` 把任务域
类型焊进 SPI 签名；`AiReviewAuthHandler` 强转 `TaskRuntime`、`HumanAuthorizationHandler`
强转 `TaskEntry`。工作流层要走同一条授权链就得 `implements TaskInfo` 伪造任务——
域反向依赖。

### 1.2 扩展发现：黑盒 map 才是总病根

深挖 AiAuthReviewer 与 SubAgentManager 的同构模式时发现：两者的 agent 装配都在
手工组装**同一个四件套 map**：

```java
// ThreadSubmitNode（task 层，主 agent）
props.put("taskEntry", t);  props.put("taskId", t.taskId);
props.put("workspaceRoot", t.workspaceRoot);  props.put("configId", t.snapshot.configId());

// SubAgentManager.buildSubAgent（插件，子 agent）          ← 一模一样
// AiAuthReviewer.buildReviewAgent（插件，审议 agent）      ← 一模一样
```

这个 map 穿透 agent 层（`AgentContext.properties()`），是 20+ 处 advisor 强转
`(TaskRuntime) a.properties().get("taskEntry")` 的数据源。**问题不在任何单一链路，
在于执行上下文以无类型黑盒形态流通，每层消费者各自强转取数。**

### 1.3 现状全景：六套上下文

| 上下文 | 位置 | 职责 | 与 task 域的耦合 |
|---|---|---|---|
| `TaskLifecycleContext` | plugin-api `task` | 任务生命周期链读写面 | `taskInfo()`/`taskRuntime()`（合法，本职） |
| **properties 黑盒 map** | 穿透 agent 层 | **事实上的执行主干道** | 强转 `TaskEntry`/`TaskRuntime` 20+ 处 |
| `AgentContext` | plugin-api `agent` | per-agent 数据面 | `properties()` 暴露黑盒 |
| `AdvisorContext` | plugin-api `spi` | advisor 创建参数 | `agentEntity()` 间接暴露 |
| `ToolContext` | plugin-api `spi` | 工具创建参数 | Impl 额外暴露 `taskEntry()` |
| `PermissionContext` + `AuthorizationRequest` | worker + plugin-api `permission` | 权限/授权链 | 直接持 `TaskEntry`/`TaskInfo` |

## 2. 现状扫描结论（消费者真实取数）

全部消费者从 task 域对象上实际取的东西，归纳后只有以下集合——**几乎全是域中性的
值或端口**：

| 取数 | 消费者 | 本质 |
|---|---|---|
| `snapshot()`（configId/params） | RateLimit、AdaptiveMaxTokens、ModelLengthGuard、ContextCompression | 模型配置 |
| `taskId()` | EmptyResponseRetry、TransientErrorRetry、AdaptiveMaxTokens、ModelLengthGuard、ContextCompression、GitAutoSync（发事件/审计） | **subjectId** |
| `workspaceRoot()` | AgentsMd、GitAutoSync、SystemInfo、（外层权限链全部节点） | 路径字符串 |
| `events()`（task 级 emitter） | 几乎全部 advisor（发 trace）、SubAgentManager（agent.started/done）、AiAuthReviewer（auth.review 审计） | **EventEmitter 端口** |
| `status/terminal` | WorkerToolEventAdvisor（leak-guard，worker 内置） | 终态布尔 |
| `fileChanges` 三槽位（读写） | FileChangeAdvisor（读写）、edit-resend（清空） | 本轮改动收集器 |
| `workspaceRoot` + `taskId` | FsToolSupport、CommandExecutor → PermissionGate | 授权链入口参数 |
| `events()` + `snapshot()` + props 四件套 | SubAgentManager、AiAuthReviewer（agent 装配） | **agent 创建能力** |
| `metadata()` | UnattendedAuthHandler、AiReviewAuthHandler、（slash provider 若干） | 主体策略标记 |
| `taskDir()` | GrantRegistry（grants.json 落盘） | 数据目录 |

工具链侧：`AskUserToolProvider`/`DirectShellToolProvider`/`FileToolsProvider` 经
`ToolContextImpl.taskEntry()` 拿 `TaskEntry`，只消费 workspaceRoot/taskId + 喂给 gate。

**没有任何一个横切消费者需要任务域的多态行为——它们只要数据。**
这是统一上下文可行性的根本依据。

## 3. 设计原则

1. **执行上下文显式类型化**：黑盒四件套升级为接口槽位，强转全部消失。
2. **上层预绑定、往下传**：task 层构造 `ExecContext`（实为 `TaskEntry`）往下传；
   agent 工厂以静态代理形态预绑定在 ctx 上（`ctx.agentFactory().create(agentId)`
   单参即可）——与 EventEmitter 的 agentId 包装（§14.0）同构。
3. **横切层域中性**：advisor / 工具 / 授权链 / 子 agent / 审议 agent 只见
   `ExecContext`，不见 `TaskEntry`/`TaskRuntime`/`TaskInfo`。工作流层未来实现
   `WorkflowRuntime implements ExecContext`，横切层零改动。
4. **task 层内部随意**：`TaskEntry` 实现全部接口；worker 内置组件（生命周期节点、
   TaskManager 等）继续用具体类——域边界不在 worker 内部，在横切层的可见面。

### 否决项（记录决策依据）

| 否决项 | 理由 |
|---|---|
| `ReviewExecutor` 专用端口 | task 层不该感知下游要做审议；agent 创建是通用能力，不为单一场景定制接口 |
| 公共 agent（不与 task 关联） | 审计无处落盘（§7.9 红线）；advisor 链依赖 taskEntry 会 NPE/静默失效；缺省模型选择断裂 |
| `BoundAgentFactory` 独立接口类型 | 静态代理 `implements AgentFactory` 即可，插件依赖类型不变，可链式套娃 |
| `WorkerServices.createBoundFactory()` | ctx.agentFactory() 已是唯一获取口，无需二次暴露 |
| ExecContext 携带 `status()` 完整状态 / `touch()` 等任务操作 | 任务域私有；横切层只需要 `terminal()`（收口防泄漏）。`TaskRuntime` 保留 |

## 4. ExecContext 设计

### 4.1 接口定义（plugin-api 新包 `execution`）

```java
package dev.everyagent.plugin.api.execution;

public interface ExecContext {

    /** 执行主体 ID（授权状态分区键/审计字段；今天=taskId，未来=workflowId）。 */
    String subjectId();

    /** 工作区根路径。 */
    String workspaceRoot();

    /** 工作区稳定 ID。 */
    String workspaceId();

    /** 完整模型配置快照（configId/params/模型参数；消费者经 snapshot().configId() 取 ID）。 */
    ModelConfig snapshot();

    /** 已绑定本主体的任务级事件口（trace/审计/agent.started 等落此处）。
     *  注意：与 AgentContext.emitter()（agent 层 agentId 包装）是两层，各自保留。 */
    EventEmitter emitter();

    /** 已绑定本主体的 Agent 工厂（静态代理）；create(agentId) 单参创建，
     *  create(agentId, configIdOverride) 覆盖模型。 */
    AgentFactory agentFactory();

    /** 主体策略标记（unattended / ai-review 等；随 meta.json 落盘的持久数据；授权链节点判定用）。 */
    Map<String, Object> metadata();

    /** 数据目录（grants.json 等落盘；今天=workspaces/<wsId>/tasks/<taskId>/）。 */
    Path dataDir();

    /** 主体是否已收口（leak-guard：终态后不再发射事件）。 */
    boolean terminal();

    /** 已绑定本主体的用户交互口（静态代理：ask 的 context map 自动填 subjectId
     *  ——替代 HumanAuthorizationHandler / ImageReferenceHandler / AskUserTool 手动
     *  组装 Map.of("taskId",...)；agentId 由调用方按需经 context 参数补充）。
     *  与 emitter()/agentFactory() 同为预绑定端口。 */
    InteractionService interaction();

    /** 本主体的活动 agent 注册表（可读写 Map：put/get/values/containsKey）。
     *  收纳主体上下文内创建的全部 agent：主 agent + 各插件派生的 agent
     *  （subagent 的子 agent、ai-review 的审议 agent 等，由创建方插件注册）。
     *  无子 agent 是正常形态（主 agent 独立完成全部工作）。 */
    Map<String, AgentContext> agents();
}
```

### 4.2 槽位出处对照（每个槽位从现状哪条取数路径收编）

| 槽位 | 现状取法 | 消费者 |
|---|---|---|
| `subjectId()` | `t.taskId()` | 授权链、重试/护栏 advisor、GitAutoSync || `workspaceRoot()` | `t.workspaceRoot()` / `ctx.workspaceRoot()` | AgentsMd/SystemInfo/Git/外层权限链/FsTool |
| `snapshot()` | `t.snapshot().configId()` / `t.snapshot().params()` | RateLimit/AdaptiveMaxTokens/Guard/Compression（configId 经 snapshot().configId() 取，不设独立槽） |
| `emitter()` | `t.events()` | 全部 advisor、SubAgentManager、AiAuthReviewer |
| `agentFactory()` | 手工组装四件套 → `agentFactory.create(...)` | SubAgentManager、AiAuthReviewer |
| `metadata()` | `t.metadata()` | Unattended/AiReview 授权节点、slash provider |
| `dataDir()` | `t.taskDir()` / `store.dirOf(taskId)` | GrantRegistry |
| `terminal()` | `t.status.terminal()` | WorkerToolEventAdvisor（leak-guard） |
| `interaction()` | `services.interaction()` + 手动填 `Map.of("taskId",...)` | HumanAuthorizationHandler、ImageReferenceHandler、AskUserTool（经 ToolContext）——三处手动绑定消失 |
| `agents()` | `t.agents()`（Map，7 处） | SubAgentManager（复用判定/台账合并/注册）；ai-review 审议 agent 注册与跨请求复用（§8.3）；edit-resend 清空走 TaskRuntime 不变 |

**不进 ExecContext 的槽位（判据 §3.3）**：fileChanges 三槽位（瞬态回合槽）留
`TaskRuntime` 任务域私有——file-change 插件（写）经 `TaskService.get(subjectId())`
访问（SubAgentManager 先例）、edit-resend（清空）已是 `ctx.taskRuntime()` 路径
零改动、RoundIndexAdvisor（落盘）是 worker 内置直读 TaskEntry 零改动。

### 4.3 TaskRuntime 与 ExecContext 的关系

```java
// plugin-api
public interface TaskRuntime extends ExecContext {   // 不再 extends TaskInfo
    // 任务域私有成员保留：mainAgentId() / agents() / main() / log() /
    // status()(完整状态字符串) / startedAt() / endedAt() / touch() /
    // summaryJson() / truncateLogAfter()
    // fileChanges 三槽位 override 为真实实现（default 在 ExecContext）
    // subjectId() 由实现类映射到 taskId
}
```

`TaskEntry`（worker）是唯一实现：`implements TaskRuntime`，`subjectId() { return taskId; }`。

### 4.4 TaskInfo 退役

`TaskInfo`（permission 包）的五个成员全部被吸收：`taskId→subjectId`、
`metadata→metadata`、`taskDir→dataDir`、`terminal→terminal`、`status→TaskRuntime`。
接口删除，消费者（11 个文件）改 import `TaskRuntime` 或 `ExecContext`
（它们实际拿到的本来都是 TaskEntry 实例）。

### 4.5 AgentFactory 重设计（绑定工厂，plugin-api）

```java
package dev.everyagent.plugin.api.agent;

public interface AgentFactory {
    /** 用绑定 configId 创建 agent。 */
    AgentBuilder create(String agentId);
    /** 覆盖模型配置创建 agent（null = 用绑定默认值）。 */
    AgentBuilder create(String agentId, String configId);
}
```

worker 内部实现（不暴露到 plugin-api）：

```java
// 全参实现（原 AgentFactoryImpl 的创建逻辑，接收 ExecContext）
class AgentFactoryImpl {
    AgentBuilder create(String agentId, String configId, ExecContext exec) {
        // 绑定默认 configId = exec.snapshot().configId()（内部闭包，不占 ExecContext 槽位）
    }
}

// 静态代理：闭包 ExecContext，实现 plugin-api 两参签名
class TaskBoundAgentFactory implements AgentFactory {
    private final AgentFactoryImpl delegate;
    private final ExecContext bound;
    public AgentBuilder create(String agentId) { return delegate.create(agentId, null, bound); }
    public AgentBuilder create(String agentId, String configId) { return delegate.create(agentId, configId, bound); }
}
```

- `ExecContext.agentFactory()` 是**唯一获取口**（TaskEntry 实现内 new 代理）。
- 未来 `WorkflowBoundAgentFactory` 同构：闭包 workflow 上下文，链式套娃（workflow
  绑定值兜底后透传 task 绑定值）。
- **`WorkerServices.agentFactory()` 删除**（仅 2 个消费者，均本次迁移）。
- 原四参签名（emitter/properties map）从公共 API 消失；map 组装退化为
  AgentFactoryImpl 内部过渡细节，advisor 全迁后彻底删除。

## 5. 分层传递路径（OSI 同构）

```
task 层（ThreadSubmitNode）
  agentBuilder.create(mainAgentId, chatModel, options, taskEntry)   ← ExecContext 替代 emitter+props
        │
agent 层（AgentEntity / AgentContext）
  execution() → ExecContext          ← properties() 黑盒退役
  emitter()（agent 级 agentId 包装）保留，包的是 execution().emitter()
        │
advisor 链（20+）
  a.execution().snapshot().configId() / .emitter() / .terminal() ...   ← 强转消失
        │
工具执行链（ToolContext）
  execution() → ExecContext          ← Impl.taskEntry() 退役
  FsToolSupport/CommandExecutor: gate.requirePath(exec, agentId, ...)
        │
授权链（AuthorizationRequest）
  record(ExecContext context, String agentId, String grantKey, String prompt)
  GrantRegistry 按 ctx.subjectId() 分区、ctx.dataDir() 落盘
        │
子 agent / 审议 agent（终端消费者）
  SubAgentManager: task.agentFactory().create(agentId)
  AiAuthReviewer:  req.context().agentFactory().create(agentId, reviewModel?)
```

每层只消费自己层级的槽位；下层不知道上层是谁（task 还是未来 workflow）。

## 6. 授权链收编（原域中性化方案）

### 6.1 契约

```java
// plugin-api permission 包
public interface AuthorizationHandler {
    String id();
    float order();
    AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception;

    record AuthorizationRequest(ExecContext context, String agentId,
                                String grantKey, String prompt) {}
    record AuthorizationDecision(Type type, String reason) {
        enum Type { ALLOW, DENY, PASS }
    }
}
```

`AuthorizationContext`（旧方案的 8 字段 record）不再需要——ExecContext 已携带
subjectId/metadata/dataDir/emitter/agentFactory，授权请求只剩两个授权专属参数。

### 6.2 三 handler 迁移

| handler | 现状 | 改后 |
|---|---|---|
| UnattendedAuthHandler | `req.task().metadata()` | `req.context().metadata()` |
| HumanAuthorizationHandler | `(TaskEntry) req.task()` 取 taskId + 手动填 ask context map | `req.context().interaction().ask(...)`（subjectId 已在代理内绑定；context map `"taskId"` 键值不变，前端兼容） |
| AiReviewAuthHandler | `(TaskRuntime) req.task()` 强转 | `req.context().metadata()` 判定 + `req.context().agentFactory().create(agentId, reviewModel)` 创建审议 agent；`emitter()/subjectId()/workspaceRoot()` 从 ctx 取 |

### 6.3 GrantRegistry 域中性化

| 方法 | 改后 |
|---|---|
| `authorize(TaskEntry t, ...)` | `authorize(AuthorizationRequest req, List<Path> roots, List<Path> execRoots)`：分区 `req.context().subjectId()`，落盘 `req.context().dataDir()` |
| `beginRun/untrack/extraRoots/execRoots(taskId)` | 参数语义改为 `subjectId`（改名，值不变） |
| `execRootsSandboxed(TaskEntry t)` | `execRootsSandboxed(String workspaceRoot, String subjectId)` |
| `persistTaskGrants(t, ...)` | `persistGrants(Path dataDir, ...)` |

### 6.4 外层权限链（PermissionContext）

`TaskEntry task` 字段替换为：`String workspaceRoot`（路径判定）+ `AuthorizationRequest`
（AuthorizeCheck/CommandCheck/PrivilegeCheck 透传给 GrantRegistry）。
调用方（`FsToolSupport`/`CommandExecutor`）从 `ToolContext.execution()` 取
ExecContext 构造，不再接触 TaskEntry。

## 7. agent/工具上下文改造

### 7.1 worker AgentBuilder（内部）

`create(agentId, chatModel, options, emitter, properties)` →
`create(agentId, chatModel, options, ExecContext exec)`：emitter 从
`exec.emitter()` 取；`createToolContext` 直接用 exec 构造（不再从 map 逐个 get）。

### 7.2 AgentContext（plugin-api agent）

```java
public interface AgentContext {
    // properties() 删除，替换为：
    /** 本 agent 所属的执行上下文（task 或未来 workflow）。 */
    ExecContext execution();
    // 其余不变：agentId/title/emitter(agent 级包装)/conversation/status/...
}
```

`AgentEntity.properties` 字段替换为 `ExecContext execution`；agent 级 emitter
包装上游即 `execution.emitter()`。

### 7.3 ToolContext / AdvisorContext（plugin-api spi）

两者各增 `default ExecContext execution() { return null; }`，Impl 实现注入。
`ToolContextImpl.taskEntry()` 退役（3 个内置 provider 改用 `execution()`）。

## 8. 消费者迁移清单

### 8.1 advisor（properties.get("taskEntry") → execution()）

| 文件 | 取数 |
|---|---|
| RateLimitAdvisor ×2 处 | snapshot |
| ModelLengthGuardAdvisor ×2 | snapshot / taskId |
| ContextCompressionAdvisor ×2 | snapshot / taskId |
| AdaptiveMaxTokensAdvisor ×2 + Provider | taskId / snapshot |
| EmptyResponseRetryAdvisor ×2 | taskId |
| TransientErrorRetryAdvisor ×2 | taskId |
| FileChangeAdvisor ×3 | **不走 ExecContext**：任务域插件，经 `TaskService.get(ctx.subjectId())` 取 TaskRuntime 读写 fileChanges 三槽位（SubAgentManager 先例） |
| AgentsMdAdvisorProvider | workspaceRoot |
| SystemInfoAdvisorProvider | workspaceRoot |
| GitAutoSyncAdvisor | taskId / workspaceRoot |
| UnattendedToolInterceptor | instanceof 判定 → `ExecContext` |
| worker: WorkerToolEventAdvisor ×4 / RoundIndexAdvisor / ContextOverflow | terminal / taskId / snapshot |
| worker: SlashTokenResolve / FileAttachment AdvisorProvider | → execution() |

### 8.2 SubAgentManager 全面中性化（subagent 插件域中性）

subagent 能力（spawn/wait/stop 子 agent）是**执行域能力**，不是任务域能力——没有 task
只有 workflow 时同样可用。全面改造：

**SubAgentManager 零服务依赖**：

- 构造参数归零（`InteractionService`、`TaskService` 全删；仅保留 `setLedger`）。
- 方法签名改收 ExecContext：`run(ctx, input, title, agentId)` / `list(ctx)` /
  `waitFor(ctx, agentId, timeout)` / `stop(ctx, agentId)` /
  `awaitAllBeforeFinish(ctx)` / `stopAll(ctx)`——工具入口不再
  `taskService.get(taskId)` 反查，`SubAgentTools` 改绑 `ToolContext.execution()`
  整个上下文句柄。
- 取数全走槽位：`task.agents()`→`ctx.agents()`（×7）、`task.events()`→`ctx.emitter()`、
  `task.taskId()`→`ctx.subjectId()`、`asks.hasPendingFor(taskId,...)`→
  `ctx.interaction().hasPendingFor(ctx.subjectId(),...)`、装配→`ctx.agentFactory()`。
- **监视器内部化**：`synchronized (task)`（拿任务对象当锁）→ `synchronized (taskState)`
  （Manager 自有 per-subject `TaskSubState`）——任务对象监视器依赖消失。

**SubAgentLedger IO 自持**：

- agents.json 本是**插件自有数据**（住在主体 dataDir 下），但 IO 挂在
  `TaskStoreService`（接口里"agents.json 读写（subagent 台账）"整段就是为此加的）。
- 台账改用 `AtomicFiles`（plugin-api util）自行读写 `ctx.dataDir().resolve("agents.json")`；
  `TaskStoreService` 的 readAgents/writeAgents 段退役（全仓仅 subagent 消费）。

**生命周期节点壳/核分离**：

- 四个 `TaskLifecycleNode`（track/untrack/persist/spawned.await）的逻辑全是主体无关的
  （track=订阅 emitter 投影、persist=台账落盘、await=收口等待）：节点体内改取
  `ctx.taskRuntime()`（TaskRuntime extends ExecContext，零强转）的
  subjectId/emitter/dataDir/agents 槽位。
- **壳留 task 面**：`TaskLifecycleNode` SPI 是任务生命周期的（工作流未来有自己的同构
  SPI）；中性核心（Manager/台账逻辑）+ task 注册壳（现有节点类）。工作流落地时同一
  核心以 `WorkflowLifecycleNode` 壳挂载，零新逻辑。

**SubAgentRpcHandler 壳/核分层**：

- `task.agents` RPC 冷路径 `readMeta(dir)` 取 mainAgentId / `dirOf(taskId)`——
  meta.json 是任务摘要，mainAgentId 是任务概念：**壳留 task 面**（继续用
  TaskStoreService），核心列表逻辑复用中性台账 reader。未来 `wf.agents` 同构包壳。

**SubAgentPlugin.activate()**：`services().task()` 删除；`services().store()` 仅供
RpcHandler（task 壳）；Ledger 不再需要 store。

**任务域插件分类明确**（file-change、edit-resend）：它们的本职就是任务域功能
（本任务本回合的改动收集/会话截断重发），经 `TaskService`/
`TaskLifecycleContext.taskRuntime()` 取 `TaskRuntime` 是合法依赖——本方案解耦目标是
横切基础设施与执行域能力插件（subagent），不是消灭任务域插件对任务域接口的正常使用。
**git 是工作区域能力插件**：RPC/面板/凭证/斜杠全部域中性；唯一耦合
GitAutoSyncAdvisor（“本轮执行完成后同步工作区”——执行域横切，非任务域）在 §8.1
S4 表内两行迁完，此后 git 插件整体域中性（无 task 只有 workflow 亦可用自动同步）。

### 8.3 AiAuthReviewer（审议 agent 跨请求复用上下文）

- 构造只剩 `WorkerConfig`（AgentFactory 依赖删除）
- `review(AuthorizationRequest req, ...)`：`req.context().agentFactory()
  .create(reviewAgentId, resolveReviewModel())`；审计 trace 用
  `req.context().emitter()`；workspaceRoot/subjectId 从 ctx 取
- `AiReviewPlugin.activate()` 不再传 agentFactory
- **固定 per-task agentId 复用会话**：现状每次审议 `ShortIds.next("review")`
  生成新 agentId、新会话——改为**同一任务固定 `review-<subjectId>`**，注册进
  `ctx.agents()`；后续审议请求走 SubAgentManager 复用范式：
  `agents().get(id)` 命中 → `resetForRerun()` + `conversation().add(new UserMessage(
  授权请求))` 续跑（历史审议 Q&A 留在会话内，审议员看得见本任务既往授权决策的
  结论与理由）；未命中 → `ctx.agentFactory()` 创建并注册。后果（接受）：
  ①审议 agent 出现在 list_agents / task.agents 台账合并视图（可见性增强）；
  ②会话随授权次数增长（任务生命周期内有限；后续可加条数上限裁剪，本次不做）。

### 8.4 工具 provider

AskUserToolProvider / DirectShellToolProvider / FileToolsProvider：
`impl.taskEntry()` → `ctx.execution()`，FsToolSupport/CommandExecutor 签名
`TaskEntry` → `ExecContext`。

### 8.5 task 域 subagent 概念清退（task 核心不依赖插件）

worker 核心零硬依赖（无 import、pom 零依赖），概念泄漏四处清退：

| # | 耦合点 | 处置 |
|---|---|---|
| ① | `TaskRuntime.agents()` javadoc "subagent 需要 put/get" | `agents()` 上移 ExecContext（§4.1），语义改述为主体活动 agent 注册表（主 + 各插件派生：子 agent、审议 agent）；TaskEntry 字段保留（totalUsage/summary 消费），TaskRuntime 不再声明 |
| ② | `TaskStoreService.readAgents/writeAgents`（"subagent 台账"段） | 已在 §8.2：台账 IO 自持，接口段退役 |
| ③ | `TaskStore.truncateAfterSeq` 特判清理 `file-changes/` 与 `agents.json` | **本次解除：截断只动 *.jsonl/rounds.jsonl，不删任何插件数据文件**。接受后果：编辑重发截断后 agents.json/file-changes 残留陈旧条目（台账/轮详情展示陈旧数据，无正确性影响）。**开放项**：后续增加截断事件通知（如 `task.truncated` 领域事件），插件自行决定清理——task 核心永不知晓插件文件名 |
| ④ | worker `RpcMethods.TASK_AGENTS` 常量 | 方法名字符串移到 subagent 插件（跟注册方走） |

**确认无耦合**（已核查）：`mainAgentId/main()`（会话文件名/冷启动，任务自有概念）、
`totalUsage()` 聚合（wire 契约，读字段）、`maxConcurrentSubs`（插件内常量）、
DataPusher/ConversationLoader/TaskEvents（仅注释提及）、rounds.jsonl `subs` 恒空
保留位（wire 兼容不动）。

## 9. 分阶段落地（每阶段独立编译/提交）

| 阶段 | 内容 | 提交 |
|---|---|---|
| S1 | plugin-api：新增 `execution.ExecContext`（含 `interaction()`/`agents()` 槽位，不含 fileChanges）；`TaskRuntime extends ExecContext`（保留全部旧成员含 fileChanges 六方法）；`AgentFactory` 重签（两参）；`AgentContext.execution()` 与 `properties()` 并存（@Deprecated） | feat: 新增 ExecContext 统一执行上下文接口 |
| S2 | worker：`AgentFactoryImpl` 内部化（全参收 ExecContext）+ `TaskBoundAgentFactory` + `SubjectBoundInteractionService`（绑 subjectId 的 ask 代理）+ `TaskEntry` 实现 ExecContext 全槽位（agentFactory()/interaction()/subjectId()...）；`ThreadSubmitNode` 主 agent 构造改传 exec | feat: worker 实现 ExecContext 与三预绑定端口 |
| S3 | 授权链：`AuthorizationRequest` 重签 + 三 handler + GrantRegistry + PermissionContext + FsToolSupport/CommandExecutor + TaskInfo 退役（permission 包删除，11 文件 import）；附带 AskQuestion.fields 结构化信息槽（§12，含前端渲染） | feat: 授权链收编 ExecContext 实现域中性 |
| S4 | advisor 迁移（§8.1 全表，FileChangeAdvisor 走 TaskService 路径）+ AgentContext 删 properties() + AgentEntity 字段替换 | feat: advisor 链全面迁移 ExecContext |
| S5 | subagent 全面中性化（Manager 零服务依赖+监视器内部化/Ledger IO 自持/生命周期节点壳核分离/RpcHandler 分层/TaskStoreService agents 段退役）+ task 域 subagent 概念清退（§8.5：截断不再删插件数据文件、TASK_AGENTS 常量移插件）+ AiAuthReviewer 固定 agentId 复用会话 + SubAgentManager/AiAuthReviewer/两 Plugin 入口 + ImageReferenceHandler/AskUserTool 改经 ctx.interaction() + WorkerServices.agentFactory() 删除 + ToolContextImpl.taskEntry() 删除 | feat: subagent 插件全面中性化与审议 agent 收编 ExecContext |
| S6 | ARCHITECTURE.md 同步 | other: 架构文档同步执行上下文管道 |

依赖顺序：S1→S2→（S3/S4 可并行）→S5→S6。S3 与 S4 均只依赖 S2 的 TaskEntry 实现。

## 10. 向后兼容与影响面

- **plugin-api breaking**：`AgentFactory` 签名、`AuthorizationRequest`、
  `AgentContext.properties()`、`TaskInfo` 删除、`WorkerServices.agentFactory()` 删除
  （`WorkerServices.interaction()` 保留——非主体绑定的场景仍可用裸服务）、
  `TaskStoreService` agents 段删除（仅 subagent 消费，台账 IO 自持）、
  `TaskRuntime.agents()` 上移 ExecContext。
  **行为变更**：编辑重发截断不再删除 agents.json/file-changes/（§8.5③，残留陈旧
  条目被接受，事件通知自清为后续开放项）；AI 审议 agent 改 per-task 固定
  agentId 复用会话（§8.3，出现在台账视图 + 会话随授权增长）。
  外部插件如有自定义 AuthorizationHandler / 直接读 properties 的 advisor 需适配；
  当前扩展点全部为内置插件，影响面 = 本仓库 18 个插件 + worker。
- **wire 零变化**：事件名/payload/RPC 全不动；grants.json 格式不变（落盘路径经
  dataDir 槽位仍指向任务目录）；`InteractionService` context map 的 `"taskId"` 键
  保留（值=subjectId，今天相等）。
- **行为零变化**：授权语义、审计落盘、advisor 链装配（重试/压缩/限流照常从
  ExecContext 取数）、leak-guard 全部保持。

## 11. 工作流复用终态

```java
// 未来工作流层
class WorkflowRuntime implements ExecContext {
    // subjectId=workflowId / dataDir=工作流数据目录 / emitter=工作流事件口 /
    // agentFactory=WorkflowBoundAgentFactory（闭包 workflow 绑定，可再套 task 层）
}

// 同一条授权链、同一批 advisor、同一工具链 —— 零改动
grantRegistry.authorize(new AuthorizationRequest(wfCtx, agentId, grantKey, prompt), ...);
```

## 12. 附带小任务：AskQuestion 结构化信息槽（授权弹窗信息增强）

### 12.1 现状与动机

`AskQuestion(id, prompt, options)` 三字段；wire 序列化（plugin-api
`EventPayloads.questionsToJson`）只带 id/prompt/options。前端 `UserInteractionQuestion`
已预留 `details?: string`（渲染为补充说明块）但后端从未填充。

授权弹窗现状是全部信息挤在 prompt 一段文本里（"AI 请求写入工作区外路径: /c/root\n
授权范围: ..."）——机器可读字段与人类可读文案混排。补一个默认为空的**结构化
键值对 map**，前端以"标签: 值"列表渲染：

```
┌ 是否授权? ────────────────┐
│ 目录: /c/root             │
│ 授权类型: 写               │
│ ◉ 授权                    │
│ ○ 否                      │
└───────────────────────────┘
```

### 12.2 改动

**plugin-api**（record 加字段，旧构造调用点经紧凑构造器兼容）：

```java
public record AskQuestion(String id, String prompt, List<AskOption> options,
                          Map<String, String> fields) {
    /** 兼容旧构造（fields 默认空）。 */
    public AskQuestion(String id, String prompt, List<AskOption> options) {
        this(id, prompt, options, Map.of());
    }
}
```

**wire 序列化**：`EventPayloads.questionsToJson` 补 `fields`（空 map 省略——
must-ignore 双向兼容：老前端忽略未知字段，老 worker 不带该字段，前端判空跳过）。

**前端**：`UserInteractionQuestion` 加 `fields?: Record<string, string>`；
`UserInteractionHost` 在 prompt 下、options 上渲染"标签: 值"信息块（复用
constraints/infoBlock 样式）；`details` 保留（自由文本，与 fields 并列）。

**消费者（授权链先行）**：`HumanAuthorizationHandler`/`ImageReferenceHandler` 的授权
ask 从"prompt 长文案"改为"prompt 短问句 + fields 结构化"：

```java
new AskQuestion("", "是否授权?", AUTHORIZE_OPTIONS, Map.of(
        "目录", real.toString(),
        "授权类型", PathSupport.opDesc(op)))
```

prompt 文案保留完整语义（磁盘回放/通知摘要仍可读），fields 只是增强展示层——
不改变 ask 协议语义、不影响回答解析。

### 12.3 阶段归属

随 S3（授权链迁移）一并落地：授权链是 fields 的第一个消费者；`AskUserTool`
（AI 提问）后续按需自行使用。



| 红线 | 本方案 |
|---|---|
| §14.0 事件管道分层 | 执行上下文管道与之同构推广：每层只消费自己槽位，上层预绑定往下传（agentFactory 与 emitter 同范式） |
| §14.9 插件零 worker 依赖 | 全部横切接口下沉 plugin-api（ExecContext/AgentFactory 短签名）；worker 具体类（AgentFactoryImpl/TaskBoundAgentFactory/TaskEntry）不外泄 |
| §14.8 复用 Spring AI | advisor 链结构不变，仅取数路径从 properties 强转改为 execution() 槽位 |
| 文档先行 | 本文档定稿后按 S1–S6 落地，ARCHITECTURE.md 在 S6 同步 |
