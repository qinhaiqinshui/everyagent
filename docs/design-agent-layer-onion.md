# 设计方案：任务生命周期洋葱模型 + Agent 独立成层

> 状态：**待评审**（用户确认后才实施）· v3（按评审结论定稿节点模型：无层粒度、一节点一事、float 排序；AgentDispatcher 废止；消息流 agent 事件渲染保留核心）
> 范围声明：本文覆盖整体架构（洋葱底座、agent 层独立、subagent 插件三件套、任务队列插件预留）；**本次只实施 Phase 1（洋葱底座）**，其余分期列出。

---

## 1. 背景与动机

### 1.1 现状问题（调研结论，路径均相对 `every-agent-worker/src/main/java/dev/everyagent/worker/`）

| 问题 | 现状证据 |
|---|---|
| **结束收口是大杂烩** | `task/TaskManager.finish()`（L1863，253 行 synchronized 方法）一个方法干 10+ 件事：终态赋值、agentStatus 终态事件、task.updated 广播、并发计数、`store.flush/writeQueue/updateMeta/writeAgents/untrack`、`gate.untrack`、`tasks.remove`、工作区活动时间。DONE/CANCELLED/FAILED/停机四路全部汇入。 |
| **开始收口分散** | 一半在 RPC 线程（`rpcTaskRun`：track、wireUsageBroadcast/wireAgentPersist、active 计数、TASK_CREATED 广播），一半在任务线程（`runTask` 头部 L1635-1638：startedAt、setStatus(RUNNING)、agentStatus("running")、buildMainAgent）。 |
| **无任务生命周期钩子** | plugin-api 无任何 start/end 扩展点；git auto-sync 用 order=+140 的外层 Advisor `after()` **冒充**「轮次收口」（时序耦合、非任务级）。 |
| **任务与子 agent 不同构** | 任务主循环在 `TaskManager.runTask`（while + inputQueue），子 agent 在 `SubAgentManager.runSub`（一次性 FutureTask）——两者内核都是 `runner.run(agent)`，但外壳（生命周期管理）完全两套。 |
| **agent 执行核心与任务层纠缠** | `AgentEntity.task` 强引用 `TaskEntry`；事件出口全走 `a.task.events`（TaskEvents 是 worker 内部类，不在任何 SPI 上）；`SubAgentManager` 深度依赖 TaskEntry/TaskEvents/AgentRunner/ChatModelFactory/ToolProviderRegistry。 |
| **队列无法插件化** | 准入 = `AtomicInteger active` + 上限即拒（ERR_BUSY），无排队语义，无扩展点；未来「任务队列插件」没有挂载位。 |

### 1.2 目标（用户需求原文拆解）

1. 任务开始/结束收口改成**洋葱模型**：下行=开始（外→内），上行=结束（内→外）。
2. 洋葱的**终态（内核）是调用起一个 Agent**——就像 runagent 工具调用起子 agent 一样。
3. **runagent 和 task 层都是 agent 的上层**（平级调用者）。
4. 本次先实现**洋葱模型底座**；任务队列后续做成插件（底座需为其预留挂载位）。
5. **subagent 插件**职责（v2 调整）：提供 ① 现有四个子 agent 工具（run_agent/list_agents/wait_agents/stop_agent）② 一个 skill（「子 Agent」使用方法论）③ 前端子 agent 列表与状态 UI（胶囊列表/信息卡/用量线）。
6. **删除该插件** = 没有子 agent 工具和 skill（AI 不再会派子 agent），**但 agent 层还在**（AgentService/事件/`task.agents` RPC 均为核心，历史任务的子 agent 数据仍可渲染）。

---

## 2. 目标架构总览

```
                    ┌─────────────────────────────────────────────┐
  RPC 层            │ task.run / task.cancel / task.poll …        │  (不变)
                    └───────────────────┬─────────────────────────┘
                                        ▼
  ┌───────────────────────── Task 洋葱（TaskOnion 执行器） ─────────────────────────┐
  │  下行 onStart（外→内，可阻塞/可否决）          上行 onEnd（内→外，逆序、必达） │
  │                                                                                │
  │  …persistence.track(100) → task.wires(200) → status.start(300)                │
  │    → main.agent(400) →【内核 = 调用 AgentService（多轮 + 输入队列）】          │
  │    → spawned.await(950) → cascade.stop(900) → status.finalize(850)            │
  │    → …concurrency.release → log.flush → status.persist(650)… → 最外收口       │
  │                                                                                │
  │  节点 = 单一职责动作（一节点一事）；order 为 float，插件可任意插位。            │
  │  未来任务队列插件 = 在 100~350 空隙插入排队节点（onStart 阻塞排队）。          │
  └──────────────────────────────────┬─────────────────────────────────────────────┘
                                     ▼
  ┌──────────────────────────── Agent 层（AgentService，核心） ────────────────────┐
  │  run(agent)          同步运行单个 agent 至最终回答（现 AgentRunner，薄）        │
  │  spawn/waitFor/stop/list   子 agent 异步编排（现 SubAgentManager 核心逻辑）    │
  │  AgentEventChannel   事件出口接口（task 层实现 → 写任务流 EventLog）           │
  │  task.agents RPC / 台账 agents.json（agent 元数据，不随插件卸载）              │
  │  ChatClient + Advisor 生态（复用 Spring AI，红线不动）                          │
  └──────────────────────────────────┬─────────────────────────────────────────────┘
                                     ▲ 调用者（平级上层）
            ┌────────────────────────┼────────────────────────┐
            │                        │                        │
      Task 层（内核调用）      subagent 插件              其他插件
      （洋葱 + 轮次循环）      （工具 + skill + 前端 UI   （如 auth-review 的
                                三件套，薄壳）            AiAuthReviewer 正规化）
```

**核心原则**：
- **洋葱内核不直接 `runner.run`，而是调 `AgentService`**——从第一天起「任务 = 调用一个 agent（对话式）」的语义就成立；subagent 插件 = 调用一个 agent（一次性）+ 交互面（skill/UI）。两种调用模式平级。
- **agent 层是核心，不随 subagent 插件卸载**：删除插件只失去「AI 使用子 agent 的入口与方法论」和「列表 UI」，agent 层能力（spawn/事件/台账）保留——历史任务子 agent 数据仍可渲染，其他上层仍可编程调用。
- 洋葱是**任务生命周期层**的编排，**不是** agent 执行循环；agent 执行循环仍由 Spring AI `ToolCallingAdvisor` 递归驱动（红线：不手搓）。

---

## 3. 洋葱模型设计（Phase 1 核心）

### 3.1 契约（放 `every-agent-plugin-api`，让插件能贡献节点）

**没有预设「层」粒度**——洋葱 = **单一职责节点的有序列表**。每个节点只干一件事（如「状态收口写磁盘」= `status.persist` 节点只调 `store.updateMeta`）；一个动作一个节点，track 与 untrack 也是两个独立节点。排序用 **float**（任意两节点间可插 100.5、100.25…，插件无需协调改号）。

```java
/** 任务生命周期节点（洋葱的一环）。一节点一事；下行=开始，上行=结束。 */
public interface TaskLifecycleNode {
    String id();
    /** 洋葱位置：升序 = 外→内。float 允许任意插位；同 order 按注册顺序（稳定排序）。 */
    float order();
    /** 下行（外→内）：任务开始。可阻塞（虚拟线程廉价，如未来排队节点）；抛异常=否决进入，任务 FAILED。 */
    default void onStart(TaskLifecycleContext ctx) throws Exception {}
    /** 上行（内→外）：任务结束（DONE/FAILED/CANCELLED 均必达）。不得抛异常（执行器兜底 WARN）。 */
    default void onEnd(TaskLifecycleContext ctx, TaskOutcome outcome) {}
    /** 上行时该节点是否在任务锁内执行（复现现状 finish 的 synchronized(t) 粒度，见 §3.4）。 */
    default boolean holdsTaskLock() { return true; }
}

/** 洋葱上下文：节点的读写面（窄接口，非 TaskEntry 本体，见 §3.4）。 */
public interface TaskLifecycleContext { String taskId(); /* … */ }

/** 任务结局。 */
public record TaskOutcome(TaskEndStatus status, String error, long startedAt, long endedAt) {}
```

执行器（两入口）：

```java
public final class TaskOnion {
    /** 常规：下行 → 内核 → 上行 unwind（逆序，只收口已进入的节点）。 */
    public TaskOutcome execute(List<TaskLifecycleNode> nodes, TaskKernel kernel, TaskLifecycleContext ctx) {
        int entered = 0;
        TaskOutcome outcome;
        try {
            for (TaskLifecycleNode n : nodes) { n.onStart(ctx); entered++; }   // 下行
            outcome = kernel.run(ctx);                                        // 内核 = 调用 agent
        } catch (InterruptedException e) { outcome = TaskOutcome.cancelled(e); Thread.currentThread().interrupt(); }
        catch (Throwable e)             { outcome = TaskOutcome.failed(e); }
        unwind(nodes.subList(0, entered), ctx, outcome);
        return outcome;
    }

    /** 外部注入结局的强制收口（停机旁路）：直接 unwind——status.finalize 的 CAS 保证幂等。 */
    public void unwind(List<TaskLifecycleNode> nodes, TaskLifecycleContext ctx, TaskOutcome injected) {
        for (int i = nodes.size() - 1; i >= 0; i--) {                         // 上行：内→外
            TaskLifecycleNode n = nodes.get(i);
            try {
                if (n.holdsTaskLock()) synchronized (ctx.taskLock()) { n.onEnd(ctx, injected); }
                else n.onEnd(ctx, injected);
            } catch (Throwable t) { log.warn("[onion] 节点 {} 收口失败（继续外层）", n.id(), t); }
        }
    }
}
```

### 3.2 错误语义（对齐中间件/unwind 惯例）

| 场景 | 行为 |
|---|---|
| 下行第 k 节点 onStart 抛异常 | 任务 FAILED；**只**逆序收口 0..k-1 节点（已进入的才收口）；第 k 节点自身不收口 |
| 内核异常/中断 | 全部已进入节点逆序 onEnd（outcome=FAILED/CANCELLED；中断位由执行器恢复） |
| 上行某节点 onEnd 抛异常 | 捕获记 WARN，**继续外层**（收口永不因单节点失败中断） |
| 上行必达且仅达一次 | 现 `finish()` 的 `terminal()` 幂等守卫语义收进 `status.finalize` 节点（CAS），执行器不重复 |
| 停机强制收口 | `TaskOnion.unwind(nodes, ctx, FAILED("worker 停机"))`（替代现状 shutdown 直接调 finish 的旁路） |
| 取消 | `rpcTaskCancel` 仍走 `future.cancel(true)` → 内核中断 → 上行 CANCELLED（级联路径不变） |

### 3.3 内置节点基线表（现状动作逐字映射，行为零变化）

**下行节点（onStart，order 升序 = 执行序）**：

| order | 节点 id | onStart 职责（一事） | 现状出处 |
|---|---|---|---|
| 100 | `persistence.track` | `store.track`：建目录 + 首写 meta.json + 挂 EventLog 监听 | rpcTaskRun L1078 |
| 200 | `task.wires` | 注入 `onUsageBroadcast` / `persistHook`（TaskEntry 钩子字段） | wireUsageBroadcast L1938 / wireAgentPersist L1957 |
| 300 | `status.start` | `startedAt` + `setStatus(RUNNING)` + task.updated 广播 + `agentStatus("running")` | runTask L1635-1637 |
| 400 | `main.agent` | `buildMainAgent` + `consumeInput(首条输入)` | runTask L1638-1639 |

**内核**：轮次循环 `while(true){ agentService.run(main); inputQueue.poll… }`（对话式调用 agent）。

**上行节点（onEnd，按执行先后排列 = order 降序）**：

| 执行序 | order | 节点 id | onEnd 职责（一事） | 锁 | 现状出处 |
|---|---|---|---|---|---|
| 1 | 950 | `spawned.await` | `awaitAllBeforeFinish`（等全部子 agent，超时级联停） | 锁外 | runTask L1672 |
| 2 | 900 | `cascade.stop` | outcome≠DONE：`stopRequested` + `stopAll` + `asks.cancelTask` + `cancelled/error` 事件 | 锁外 | runTask catch L1680-1705 |
| 3 | 850 | `status.finalize` | 终态 CAS（幂等门）+ `endedAt/error/status` + agentStatus 终态事件 + 终态广播 | 锁内 | finish L1866-1875 |
| 4 | 800 | `concurrency.release` | `active.decrementAndGet()` | 锁内 | finish L1876 |
| 5 | 750 | `log.flush` | `store.flush`（等落盘追平，30s 超时放行） | 锁内 | finish L1877 |
| 6 | 700 | `queue.persist` | `store.writeQueue`（悬空输入队列落盘） | 锁内 | finish L1888 |
| 7 | 650 | `status.persist` | `store.updateMeta`（**状态收口写磁盘**） | 锁内 | finish L1889 |
| 8 | 600 | `ledger.persist` | `store.writeAgents`（agent 台账终态快照） | 锁内 | finish L1891 |
| 9 | 550 | `disk.index` | `diskTasks.put`（终态任务转磁盘索引） | 锁内 | finish L1892 |
| 10 | 500 | `persistence.untrack` | `store.untrack`（关闭全部 jsonl writer） | 锁内 | finish L1893 |
| 11 | 450 | `gate.evict` | `gate.untrack`（授权内存驱逐） | 锁内 | finish L1894 |
| 12 | 400 | `registry.remove` | `tasks.remove`（两参原子，再运行认据此判输赢） | 锁内 | finish L1895 |
| 13 | 350 | `workspace.activity` | `activityTracker.onTaskFinished` | 锁外 | finish L1915 |

**顺序不变量**（重构正确性的判据）：
- 上行 1-13 的总顺序 = 现状 `runTask 尾部 → finish 内部` 的执行顺序**逐项对应**（await/cascade 在 finish 之前=锁外；3-12 对应 finish 持锁段；13 锁外）。
- 下行 100→400 = 现状 `rpcTaskRun（track/wire）→ runTask 头部（RUNNING/build/consume）`。
- **留在洋葱外（RPC 边缘）**：幂等键去重、并发上限检查 + `active.incrementAndGet()`（需同步应答 ERR_BUSY，TOCTOU 语义要求在 RPC 线程）、`TASK_CREATED` 广播、`ctx.ok` 应答——属「创建」而非「开始」。未来队列插件见 §3.5。
- **锁策略**：现状 finish 全程持 `synchronized(t)`；新模型由 `holdsTaskLock()` 分段复现——锁外段（await/cascade/activity）+ 锁内段（3-12 顺序连续），与现状等价。

### 3.4 TaskLifecycleContext（节点的读写面）

不从 plugin-api 暴露 `TaskEntry`（避免外部插件强耦合核心，重复 git/auth-review 反向依赖的旧路）。窄接口起步：

- 只读：`taskId / title / workspaceRoot / workspaceId / status / mainAgentId`
- 同步原语：`taskLock()`（执行器按 `holdsTaskLock()` 获取）
- 可写（受控）：`taskFlags` / usage 广播钩子（`onUsageBroadcast` 由 `task.wires` 注入）
- 事件最小面：`agentStatus(agentId, status)`（`status.start`/`status.finalize` 用）

内置节点在 worker 侧拿完整 `TaskEntry`（内部通道，同 `AdvisorContextImpl.agentEntity()` 惯例）；**外部插件只见窄接口**。

### 3.5 节点注册与插件贡献

- worker 内置节点经 `TaskLifecycleRegistry`（新，`plugin/registry/` 第 8 个注册表，CopyOnWriteArrayList + PluginStateStore 过滤 + **float order 稳定排序**——与 AdvisorProviderRegistry 同模式）注册。
- `WorkerPluginContext` 新增 `registerTaskLifecycleNode(TaskLifecycleNode)`。
- **任务队列插件（Phase 4 预留，本次不实施）**：在下行空隙（100~400 之间任意 float 位，如 250）插入排队节点——`onStart` 阻塞排队（虚拟线程下阻塞即挂起，零线程开销）、`onEnd` 出队广播；若需接管「并发上限即拒」语义，则替换 RPC 边缘预检（Phase 4 设计边缘扩展点）。底座只保证节点接口表达力足够。

---

## 4. Agent 层设计（Phase 2）

### 4.1 模块归属

**本 Phase 在 worker 内建包 `dev.everyagent.worker.agent`**（物理分包、清晰边界），Maven 独立模块化（`every-agent-agent`）作为后续可选步骤——先解逻辑依赖，再解物理依赖，避免一次迁移面过大。

迁入 agent 包：`AgentRunner`、`AgentClientFactory`、`AgentEntity`、`ChatModelFactory`、`WorkerToolEventAdvisor`、`LoopRepeatGuard*`、`AgentCancelledException`、`AgentActivity`。

**不迁**（任务流职责）：`TaskEvents`、`EventLog`、`TaskStore`、`RoundIndex*`、`FileChange*`、`InputQueue`——它们留在 task 层，经接口面向 agent 层。

### 4.2 解耦关键：AgentEntity 去 TaskEntry 化

```java
// 现在：class AgentEntity { TaskEntry task; … a.task.events.delta(…); }
// 之后：
public interface AgentContext {                  // agent 层需要的任务面（窄接口）
    String taskId();
    String workspaceRoot();
    AgentEventChannel events();                  // 见下
    boolean taskTerminal();                      // status.terminal()（事件泄漏防御用）
    void recordMainUsage(Usage u, …);            // 主 agent 电池（原 task.recordUsage + onUsageBroadcast）
    void updateSpawnedLedger(String agentId, ObjectNode summary);  // 子 agent 台账（原 agentLedger.put + persist）
}
public interface AgentEventChannel {             // 事件出口（TaskEvents 实现/适配）
    void delta(String agentId, String piece);
    void thinking(String agentId, String piece);
    void message(String agentId, String thinking, String text, Object parts);
    void usage(String agentId, String model, int ctxWindow, Object round, Object total);
    void toolResult(String agentId, Object calls);
    void agentStarted(String agentId, String title, String input);
    void agentDone(String agentId, String result, Object usage);
    void agentStatus(String agentId, String status);
    void error(String agentId, String message);
}
```

`TaskEntry implements AgentContext`（或内部适配器），`WorkerToolEventAdvisor` 只依赖两接口——agent 层与任务流的耦合收敛为**两个接口**。

### 4.3 AgentService（agent 层公共 API）

```java
public interface AgentService {
    /** 同步运行单个 agent 至最终回答（现 AgentRunner.run，主/子共用同一入口不变）。 */
    void run(AgentEntity a) throws InterruptedException;
    /** 异步派生子 agent（现 SubAgentManager.run 核心逻辑下沉）。 */
    String spawn(TaskEntry task, String input, String title, String reuseAgentId);  // Phase 2 后签名见 §5
    AgentWaitResult waitFor(TaskEntry task, String agentId, long timeoutMs);
    void stop(TaskEntry task, String agentId);
    void stopAll(TaskEntry task);
    List<AgentSummary> list(TaskEntry task);
}
```

处置既有预留：**`AgentDispatcher` SPI + `AgentDispatcherRegistry`（现零实现零消费）废止删除**，其语义被 `AgentService` 取代（dispatch→spawn、waitFor/stop/list 同名）。避免两套等价 SPI 并存。

### 4.4 调用者平级化

| 调用者 | 用法 |
|---|---|
| **Task 层**（洋葱内核） | `agentService.run(main)` × 轮次循环（对话式：多轮 + 输入队列 + waiting-user） |
| **subagent 插件** | `spawn/waitFor/stop/list`（一次性：单任务跑完即止）+ skill + 前端列表 UI（§5） |
| **其他插件** | 如 auth-review 的 `AiAuthReviewer` 从自建 AgentEntity 旁路改为正规调 agent 层（收编现存的「agent 层被旁路使用」先例） |

---

## 5. subagent 插件（Phase 3）

### 5.1 职责边界（v2 调整核心）

「子 agent」拆成**能力（核心）**与**入口/交互（插件）**两部分：

| 归属 | 内容 | 删除插件后 |
|---|---|---|
| **agent 层（核心）** | `AgentService.spawn/waitFor/stop/list`、`run`、agent 生命周期事件（agent.started/done/status）、台账 `agents.json`、`task.agents` RPC、消息流中的 agent 事件渲染（历史数据） | **保留**——其他上层仍可编程调用；历史任务子 agent 数据仍可渲染 |
| **subagent 插件** | ① 4 个工具（run_agent/list_agents/wait_agents/stop_agent）② 1 个 skill（「子 Agent」方法论）③ 前端列表 UI（胶囊列表/信息卡/用量线） | 全部消失——AI 不再有派子 agent 的入口与方法论，前端无列表 |

插件是**纯薄壳**：工具方法体 = `agentService.spawn(...)` 等一行委托；skill 文案 = 使用方法论；前端组件 = 订阅 agent 层数据渲染。**不含任何执行逻辑。**

### 5.2 插件结构（worker 端 + web 端）

```
every-agent-plugins/subagent/
  pom.xml                                  # Maven：依赖 every-agent-plugin-api（+ agent 层 API，见 §4）
  src/main/java/dev/everyagent/plugin/subagent/
    SubAgentTools.java                      # 4 个 @Tool（从 worker tools/ 迁出，改调 AgentService）
    SubAgentToolsProvider.java              # ToolProvider：scope=MAIN（从 worker plugin/adapters/ 迁出）
    SubAgentSkillContributor.java           # SkillContributor：贡献「子 Agent」skill（见 §5.3）
  web/
    index.ts                                # 前端插件入口（builtInPlugins.ts 自动发现）
    SubAgentListPanel.tsx                   # 从 web src/components/task/AgentListPanel.tsx 迁出
    subagent.css                            # 胶囊列表/信息卡/用量线样式（从 AgentListPanel.css 抽取）
```

worker 核心删除：`tools/SubAgentTools.java`、`plugin/adapters/SubAgentToolsProvider.java`、`BuiltInToolProviders` 注册行、`BuiltInSkills` 中的 `agent-dispatch` 条目（迁插件）。web 核心删除：`AgentListPanel.tsx/.css` 及 `TaskChat` 中的直接引用（改为扩展点渲染）。

### 5.3 新增扩展点：skill 贡献（worker 端）

现状：`skill/BuiltInSkills` 构造器**硬编码** `activeSkills = List.of(new Skill("agent-dispatch", "子 Agent", ...))`，plugin-api 无 skill SPI。新增：

```java
// every-agent-plugin-api
public interface SkillContributor {
    String pluginId();
    /** 贡献的 skill 描述（主动披露：进 system prompt + / 菜单）。 */
    List<PluginSkill> skills();
}
public record PluginSkill(String id, String name, String description, List<String> tools) {}
```

- `WorkerPluginContext.registerSkillContributor(SkillContributor)`；`SkillContributorRegistry`（第 9 个注册表，同模式）。
- `BuiltInSkills` 改为「核心内置 skill + 注册表插件贡献」合并供给 `SkillAdvisor`（渐进式披露索引）与 slash `/` 菜单。
- 插件禁用（`plugin.enable/disable`）→ SkillContributorRegistry 按 PluginStateStore 过滤 → skill 从 system prompt/菜单即时消失（与工具消失同拍）。

### 5.4 前端：复用 `ui.composer_above_panel` 扩展点

现状：`AgentListPanel` 挂在 `TaskComposerSurface.abovePanel`（`TaskChat.tsx` L809-814），数据 = `task.agents` RPC + 流事件（usage/agent.started/agent.done/agentStatus）实时合并进 `agentMeta`。web 已有 `ui.composer_above_panel` 扩展点（`ComposerPanelCtx{taskId, draft, isRunning}`）正是该槽位。

- 插件 `web/index.ts` 经 `ctx.ui.registerComposerAbovePanel({ id, Component: SubAgentListPanel })` 注册。
- 组件**自管数据**：订阅 `task.agents` RPC + 流事件（与现 TaskChat 相同逻辑迁入）；无子 agent（agents 空）时渲染 null（不占位）。
- 「选中子 agent → 过滤主线程显示」（现 `filterAgentId`/`handleSelectAgent` 是 TaskChat 内部状态）→ 扩展 `ComposerPanelCtx` 增加 `selectAgent(agentId | null)` 回调，核心实现联动；插件只调回调不持状态。
- **消息流中的 agent 生命周期事件渲染（agent.started/done 卡片）保留 web 核心**：它渲染的是事件流本身（agent 层契约），非交互入口；历史任务在插件删除后仍需正确展示。

### 5.5 删除插件的降级语义

| 面 | 表现 |
|---|---|
| 主 agent 工具集 | 无 run_agent 族（ToolProviderRegistry 已按 PluginStateStore 过滤，天然支持） |
| system prompt / `/` 菜单 | 无「子 Agent」skill 条目（SkillContributorRegistry 过滤） |
| 前端 | composer 上方无胶囊列表；消息流中历史 agent.started/done 事件仍渲染（核心）；`task.agents` RPC 仍可用 |
| worker 运行中任务的子 agent | 不受影响（已在跑的由 agent 层管理至终态） |

打包：`every-agent-app` pom 增加模块依赖；**吸取 git 插件漏挂教训**——迁移必须同步 app pom，加 CI 一致性检查（插件模块 ↔ app pom）。

---

## 6. 实施计划

| Phase | 内容 | 交付 |
|---|---|---|
| **1（本次）洋葱底座** | plugin-api 节点契约（`TaskLifecycleNode`/`TaskLifecycleContext`/`TaskOutcome`/`TaskKernel`）+ `registerTaskLifecycleNode`；`TaskLifecycleRegistry`（float 排序）+ `TaskOnion`；17 个内置节点拆分（下行 4 + 上行 13，finish/runTask/rpcTaskRun 逻辑**逐字映射**，不改行为）；`runTask` 重写为洋葱执行；单测（下行顺序/否决/unwind 逆序/幂等/停机注入/取消/锁分段）；ARCHITECTURE.md 新 §7.x | worker 行为零变化（事件顺序、seq、落盘字节级兼容），可回归验证 |
| **2 agent 层** | `dev.everyagent.worker.agent` 包收敛 + `AgentContext`/`AgentEventChannel` 解耦 + `AgentService` + `AgentDispatcher` 废止 + `SubAgentManager` 编排下沉 | agent 层边界成立（能力全部留在核心），其他插件可依赖 |
| **3 subagent 插件** | 三件套迁移（工具 + SkillContributor + 前端面板）；plugin-api 新增 `SkillContributor` SPI + 注册表；BuiltInSkills 合并改造；web `ComposerPanelCtx` 扩展 `selectAgent`；app pom 挂载 + CI 一致性检查 | 删除插件 = 无子 agent 工具/skill/前端列表，agent 层完好 |
| **4（未来）队列插件** | 排队节点（onStart 阻塞排队 / onEnd 出队广播）+ RPC 边缘预检扩展点 | 底座已就绪，不在本设计实施范围 |

---

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| finish 拆节点后收口顺序/竞态回归 | 顺序不变量逐条对照（§3.3 表）；锁分段复现现状 synchronized(t)（§3.3 锁策略）；补 onion 单测 + 全量 worker 测试回归 |
| 再运行（startRerun）路径绕过洋葱 | 再运行的 track/seed/wire 逻辑同样走 `persistence.track`/`task.wires` onStart（复用同一节点实现，不另写一份） |
| DataPusher 换日志时机变化 | track 仍由 `persistence.track`.onStart 调用；执行线程从 RPC 线程移到任务线程开头（外部可见行为不变：meta 最终一致、ctx.ok 应答仍即时） |
| AgentEntity 解耦破坏事件语义（roundSeqs 同轮共享 seq） | AgentEventChannel 由 TaskEvents 直接 implements，seq 逻辑不动，只换接口面 |
| 洋葱≠手搓 agent 循环的红线 | 内核调 AgentService→AgentRunner→ChatClient（Spring AI 工具循环）；洋葱只编排任务生命周期层 |
| 并发会话冲突（git 插件迁移进行中） | Phase 1 不碰 plugin 模块与 app pom；`SubAgentManager` 仅改调用点不动文件位置 |
| AgentListPanel 前端迁移破坏选中联动/数据流 | 组件数据订阅逻辑原样迁（task.agents + 流事件）；选中联动经 `selectAgent` 回调由核心实现，插件不持跨插件状态；迁移期与核心实现并跑对照 |
| skill 双源合并的顺序/去重（内置 vs 插件贡献） | SkillContributorRegistry 输出按 pluginId+skillId 去重，插件 id 冲突沿用 BuiltInPlugins 冲突检查惯例 |

---

## 8. 已确认决策（v3 评审结论）

1. **节点模型**：无层粒度；一节点一事；order 用 float（已落实到 §3）。
2. **`AgentDispatcher` 废止**：plugin-api 预留 SPI（dispatch/waitFor/stop/list 的「子 agent 调度策略」扩展点），全仓零实现零消费——确认无用，Phase 2 删除，语义并入 `AgentService`。
3. **消息流中 agent 生命周期事件渲染**：保留 web 核心（渲染事件流=agent 层契约，历史任务在插件删除后仍正确展示）。
4. **原 L4（等子 agent/级联停）**：拆为 `spawned.await`（950）+ `cascade.stop`（900）两个上行节点，Phase 1 直接包含（收口动作仅为方法搬移，触碰 `SubAgentManager` 面极小——`awaitAllBeforeFinish`/`stopAll` 两个既有方法调用）。

## 9. 遗留待确认

1. **skill 只随 subagent 插件走是否够用**：若未来别的插件也要贡献 skill，`SkillContributor` SPI 做成通用扩展点（设计已按通用写，subagent 是第一个使用者）。
2. Phase 2 的 Maven 物理分模块（`every-agent-agent`）时机：与包内收敛同步做，还是包收敛稳定后再拆（我建议后者）。
