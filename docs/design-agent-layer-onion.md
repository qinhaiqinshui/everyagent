# 设计方案：任务生命周期洋葱模型 + Agent 独立成层

> 状态：**待评审**（用户确认后才实施）· v6（task.agents RPC 划归 subagent 插件：其唯一消费闭环是子 agent 列表（拉取→seedAgents→agentMeta→AgentListPanel），删除插件即无使用者；agent 层只保留台账写入/恢复作为 spawn 持久化真相源）
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
  │  下行 = next(ctx) 之前的代码（外→内）   上行 = next 返回后的代码（内→外）      │
  │                                                                                │
  │  …persistence.track(100) → task.wires(200) → status.start(300)                │
  │    → main.agent(400) →【内核 = 调用 AgentService（多轮 + 输入队列）】          │
  │    → spawned.await(950) → cascade.stop(900) → status.finalize(850)            │
  │    → …concurrency.release → log.flush → status.persist(650)… → 最外收口       │
  │                                                                                │
  │  节点 = 单一职责动作（一节点一事）；order 为 float，插件可任意插位。            │
  │  未来任务队列插件 = 在 100~400 空隙插成对节点（enqueue 阻塞/finally 出队）。  │
  └──────────────────────────────────┬─────────────────────────────────────────────┘
                                     ▼
  ┌──────────────────────────── Agent 层（AgentService，核心） ────────────────────┐
  │  run(agent)          同步运行单个 agent 至最终回答（现 AgentRunner，薄）        │
  │  spawn/waitFor/stop/list   子 agent 异步编排（现 SubAgentManager 核心逻辑）    │
  │  AgentEventChannel   事件出口接口（task 层实现 → 写任务流 EventLog）           │
  │  台账 agents.json 写入/恢复（spawn 持久化真相源，不随插件卸载）              │
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
- **agent 层是核心，不随 subagent 插件卸载**：删除插件失去「AI 使用子 agent 的入口与方法论」「列表 UI」与 **task.agents RPC（唯一消费闭环是列表，随之消失、无孤儿）**；agent 层能力（spawn/事件/**台账写入与恢复**）保留——历史任务子 agent 的消息流靠事件 payload 自足渲染（title/input/usage 都在事件里，不依赖台账 RPC），其他上层仍可编程调用。
- 洋葱是**任务生命周期层**的编排，**不是** agent 执行循环；agent 执行循环仍由 Spring AI `ToolCallingAdvisor` 递归驱动（红线：不手搓）。

---

## 3. 洋葱模型设计（Phase 1 核心）

### 3.1 契约（放 `every-agent-plugin-api`，让插件能贡献节点）——Servlet Filter 风格参与式链

**没有预设「层」粒度**——洋葱 = **单一职责节点的有序列表**，但节点不是「两个回调」（onStart/onEnd），而是像 **Java Servlet Filter** 一样**参与执行**：节点拿到运行上下文，调用 `result = next(context)` 得到**后面全部节点 + 内核**的执行结果。`next()` 之前 = 下行（开始）阶段，之后 = 上行（结束）阶段。

```java
/**
 * 任务生命周期节点（Servlet Filter 风格）。
 * invoke() 内调用 next.proceed(ctx) 之前的代码 = 下行；之后的代码 = 上行。
 */
public interface TaskLifecycleNode {
    String id();
    /** 洋葱位置：升序 = 外→内。float 允许任意插位；同 order 按注册顺序（稳定排序）。 */
    float order();
    /**
     * 契约：
     * - 下行段可否决：不调 next 直接 return TaskOutcome（短路，内层不执行）或抛异常（执行器译为 FAILED）。
     * - 收口必达靠节点自己的 try/finally：推荐形态「下行动作在 try 外，next+收口包进 try/finally」——
     *   下行抛异常时本节点不收口（未进入不收口），next 之后无论成败收口必达。
     * - next() 拿到的一律是值（内核已把一切异常翻译成 TaskOutcome），上行段通常无需 catch。
     * - 上行段可改写 result（如补 error 上下文）后返回，外层节点看到改写值。
     * - next 恰好调用一次：不调=否决；重复调=状态未定义（执行器打 ERROR 日志防御）。
     */
    TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception;
}

/** 链的下一环。 */
@FunctionalInterface
public interface TaskChain {
    TaskOutcome proceed(TaskLifecycleContext ctx) throws Exception;
}

/** 任务结局（值对象；异常只在内核翻译一次，链上只传值）。 */
public record TaskOutcome(TaskEndStatus status, String error, long startedAt, long endedAt) {
    public enum TaskEndStatus { DONE, FAILED, CANCELLED }
    public static TaskOutcome failed(Throwable t) { /* … */ }
    public static TaskOutcome cancelled() { /* … */ }
}

/** 任务内核：对话式调用 agent（轮次循环）。必须把一切异常翻译为 TaskOutcome（中断位恢复）。 */
@FunctionalInterface
public interface TaskKernel {
    TaskOutcome run(TaskLifecycleContext ctx);
}
```

执行器（组装即全部逻辑）：

```java
/** 洋葱执行器：按 order 升序把节点组装为嵌套链，链尾接内核。 */
public final class TaskOnion {
    public TaskOutcome run(List<TaskLifecycleNode> nodes, TaskKernel kernel, TaskLifecycleContext ctx) {
        TaskChain chain = kernel::run;                    // 链尾 = 内核（异常已翻译）
        for (int i = nodes.size() - 1; i >= 0; i--) {     // 由内向外包裹
            TaskLifecycleNode node = nodes.get(i);
            TaskChain inner = chain;
            chain = c -> node.invoke(c, inner);
        }
        try {
            return chain.proceed(ctx);
        } catch (Throwable t) {                           // 最外层之外的兜底（节点否决异常等）
            return TaskOutcome.failed(t);
        }
    }
}
```

**节点三种形态**（17 个内置节点全部落在这三种形态内）：

```java
/** 形态一：纯下行节点（persistence.track, order=100）——上行无动作，直接透传。 */
final class PersistenceTrackNode implements TaskLifecycleNode {
    public String id() { return "persistence.track"; }
    public float order() { return 100; }
    public TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        store.track(ctx.taskId(), /* … */);               // 下行
        return next.proceed(ctx);                         // 上行无（untrack 是独立节点 order=500）
    }
}

/** 形态二：纯收口节点（status.persist, order=650）——下行段为空，先透传拿结果再收口。 */
final class StatusPersistNode implements TaskLifecycleNode {
    public String id() { return "status.persist"; }
    public float order() { return 650; }
    public TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        TaskOutcome result = next.proceed(ctx);           // 下行段为空
        synchronized (ctx.taskLock()) {                   // 上行 = 状态收口写磁盘（一节点一事）
            store.updateMeta(ctx.taskId());
        }
        return result;
    }
}

/** 形态三：成对节点（未来队列插件形态，try/finally 状态局部——filter 模型的表达力所在）。 */
final class QueueAdmissionNode implements TaskLifecycleNode {
    public String id() { return "queue.admission"; }
    public float order() { return 250; }
    public TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        QueueTicket ticket = queue.enqueue(ctx.taskId()); // 下行：入队（阻塞=虚拟线程挂起）
        try {
            return next.proceed(ctx);                     // 内层执行
        } finally {
            queue.leave(ctx.taskId(), ticket);            // 上行：出队广播（必达）
        }
    }
}

/** 内核适配（TaskManager 侧）：现 runTask 的轮次循环整体搬入，异常一次性翻译。 */
TaskKernel kernel = ctx -> {
    try {
        // consumeInput + while (true) { agentService.run(main); inputQueue.poll … }
        return new TaskOutcome(TaskEndStatus.DONE, null, ctx.startedAt(), System.currentTimeMillis());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();               // 恢复中断位（后续节点仍可感知）
        return TaskOutcome.cancelled();
    } catch (Throwable t) {
        return TaskOutcome.failed(t);
    }
};
```

### 3.2 错误语义（filter 模型）

| 场景 | 行为 |
|---|---|
| 节点下行段抛异常 / 不调 next 直接 return | 否决：内层全部不执行；异常被外层节点 `next.proceed(ctx)` 之外的执行器兜底译为 FAILED（或 return 的短路值即结局）；外层节点照常走自己的上行段（它们已在 invoke 中） |
| 内核异常/中断 | **内核内**翻译为 TaskOutcome（FAILED/CANCELLED + 中断位恢复），链上只传值——节点上行段拿到的永远是正常 result |
| 某节点上行段抛异常 | 穿过更外层节点的 `next.proceed()` 调用点——**外层若未 try/finally 则收口中断**。约束：内置节点的上行段不允许抛（收口动作自带 try/catch 吞错记 WARN，如 flush 超时放行）；执行器对最外层兜底译 FAILED |
| 收口必达 | 由节点 **try/finally** 保证（推荐形态：下行动作在 try 外）。「下行抛异常 ⇒ 本节点不收口」自动成立 |
| 上行必达且仅达一次 | `status.finalize`（850）的终态 CAS 幂等门不变 |
| 停机强制收口 | filter 无外部重放入口（栈在各节点 invoke 内）。停机 = `cancel(true)` 中断全部任务线程 → 各节点 finally/上行段沿链自然收口 + 有限等待；替代现状 shutdown 直接调 finish 的旁路（status.finalize CAS 保证不双收）。destroy 超时未退出的任务记 ERROR 放弃（应急语义，与现状 flush 30s 超时放行同档） |
| 取消 | `rpcTaskCancel` 仍 `future.cancel(true)` → 内核中断 → result=CANCELLED 沿链上行（级联路径不变） |
| result 改写 | 上行段可改写（如 cascade.stop 补 error 上下文）；改写值向外层传播 |

### 3.3 内置节点基线表（现状动作逐字映射，行为零变化）

filter 模型下收口序恒等于进入序的逆序（同一 order 决定两端位置）。**关键推论**：v3 的「一节点一事」划分（track/untrack 分离、纯收口动作独立成节点）恰好让逆序收口序与现状执行顺序**逐项一致**——不需要任何顺序变化。17 节点 = 4 个形态一（纯下行）+ 13 个形态二（空下行段纯收口）；形态三（成对 try/finally）是留给未来插件的。

**下行节点（invoke 的 next 之前，order 升序 = 执行序）**：

| order | 节点 id | 下行段职责（一事） | 现状出处 |
|---|---|---|---|
| 100 | `persistence.track` | `store.track`：建目录 + 首写 meta.json + 挂 EventLog 监听 | rpcTaskRun L1078 |
| 200 | `task.wires` | 注入 `onUsageBroadcast` / `persistHook`（TaskEntry 钩子字段） | wireUsageBroadcast L1938 / wireAgentPersist L1957 |
| 300 | `status.start` | `startedAt` + `setStatus(RUNNING)` + task.updated 广播 + `agentStatus("running")` | runTask L1635-1637 |
| 400 | `main.agent` | `buildMainAgent` + `consumeInput(首条输入)` | runTask L1638-1639 |

**内核**：轮次循环 `while(true){ agentService.run(main); inputQueue.poll… }`（对话式调用 agent）。

**上行节点（invoke 的 next 之后，按执行先后排列 = order 降序；全部为形态二——下行段为空透传）**：

| 执行序 | order | 节点 id | 上行段职责（一事） | 锁 | 现状出处 |
|---|---|---|---|---|---|
| 1 | 950 | `spawned.await` | `awaitAllBeforeFinish`（等全部子 agent，超时级联停） | 锁外 | runTask L1672 |
| 2 | 900 | `cascade.stop` | result≠DONE 时按 status 分支：`stopRequested` + `stopAll` + `asks.cancelTask`；CANCELLED 发 `cancelled` 事件 / FAILED 发 `error` 事件（现状两个 catch 分支的差异收敛为看 result） | 锁外 | runTask catch L1680-1705 |
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
- **锁策略（filter 模型下为节点实现细节）**：现状 finish 全程一个 `synchronized(t)` 大临界区；新模型由各锁内节点上行段**各自** `synchronized(ctx.taskLock())`。差异论证：终态 CAS 已在第一个临界区（status.finalize）完成，此后 cancel/runExisting 等并发方插进小临界区间隙时看到的是已终态任务，只做幂等动作（rpcTaskCancel 置 stopRequested/stopAll 无害；finish 重入被 CAS 拦截）——与整段等价。若实施期回归发现反例，兜底方案：850..400 连续锁内段用组合节点包一层（放弃一节点一事于该段）。

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
- **任务队列插件（Phase 5 预留，本次不实施）**：在下行空隙（100~400 之间任意 float 位，如 250）插入**形态三成对节点**（§3.1 QueueAdmissionNode 示例）——下行段 `enqueue` 阻塞排队（虚拟线程下阻塞即挂起，零线程开销）、finally 出队广播；若需接管「并发上限即拒」语义，则替换 RPC 边缘预检（Phase 5 设计边缘扩展点）。filter 模型的 try/finally 局部状态正是为这类成对关注点准备。

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

## 5. subagent 插件（Phase 4）

### 5.1 职责边界（v2 调整核心）

「子 agent」拆成**能力（核心）**与**入口/交互（插件）**两部分：

| 归属 | 内容 | 删除插件后 |
|---|---|---|
| **agent 层（核心）** | `AgentService.spawn/waitFor/stop/list`、`run`、agent 生命周期事件（agent.started/done/status）、**台账 `agents.json` 的写入与恢复**（agentLedger/writeAgents/restoreAgentLedger）、消息流中的 agent 事件渲染（历史数据，事件 payload 自足：title/input/usage 都在事件里） | **保留**——spawn 的持久化真相源与事件流渲染；其他上层仍可编程调用 |
| **subagent 插件** | ① 4 个工具（run_agent/list_agents/wait_agents/stop_agent）② 1 个 skill（「子 Agent」方法论）③ **`task.agents` RPC**（唯一取数口，经 registerRpcMethod 注册——git 插件先例；方法名保留）④ 前端列表 UI 及其取数链（fetchTaskAgents/seedAgents 基线/agentMeta 列表消费 + AgentListPanel） | 全部消失——AI 无派子 agent 入口与方法论、前端无列表、**task.agents 无提供者也无使用者（无孤儿 RPC）** |

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
| 前端 | composer 上方无胶囊列表（agentMeta 列表消费随之无意义，保持空置无害）；消息流中历史 agent.started/done 事件仍渲染（核心，事件 payload 自足）；`task.agents` RPC 无提供者（打开任务的拉取调用点已条件化跳过，失败仅 warn 不阻断） |
| worker 运行中任务的子 agent | 不受影响（已在跑的由 agent 层管理至终态） |

打包：`every-agent-app` pom 增加模块依赖；**吸取 git 插件漏挂教训**——迁移必须同步 app pom，加 CI 一致性检查（插件模块 ↔ app pom）。

---

## 6. 统一拦截链范式（工具执行 / 审核授权，后续阶段实施）

worker 现有两条运行期责任链，语义与任务洋葱同构（有序节点 + 短路 + 链尾兜底），一并迁移为 §3.1 的 filter 形态（`result = next(ctx)`），**不在 Phase 1 实施**。

### 6.1 现状契约

**工具执行拦截链**（`plugin-api spi/ToolExecutionInterceptor` + `task/InterceptingToolCallingManager`）：

```java
public interface ToolExecutionInterceptor {
    int order();
    /** 下行拦截：非 null → 短路（合成结果）；null → 放行。只有下行，无上行钩子。 */
    ToolExecutionResult beforeToolExecution(Prompt prompt, ChatResponse chatResponse,
            List<AssistantMessage.ToolCall> toolCalls);
}
```

`InterceptingToolCallingManager`（装饰共享 `ToolCallingManager`）在 `executeToolCalls` 入口 for 循环遍历：首个非 null 短路，否则委托真实 manager。现有使用者：auth-review 的 `UnattendedToolInterceptor`（无人值守拦截 ask_user 轮，合成自动回答）。

**审核授权链**（两层）：

- **SPI 决议链**（`permission/AuthorizationHandler`）：`{ order, applies(req), decide(req) → ALLOW/DENY/PASS }`，核心遍历排序节点，任一 ALLOW/DENY 短路，PASS 下传，全 PASS 兜底。使用者：`AuthorizeCheck`（弹窗）、auth-review 的 `AiReviewAuthHandler`（AI 审议）、`UnattendedAuthHandler`（无人值守 DENY）。
- **PermissionGate 内部检查链**（worker 内部类，每条入口一条）：文件路径链 `WorkspaceAllowCheck → MissingPathCheck → SkillsReadAllowCheck → ExternalRootAllowCheck → OverBroadRootCheck → AuthorizeCheck`；命令链 `CommandCheck`；提权链 `PrivilegeCheck`。节点返回 `PermissionDecision`（ALLOW/DENY 短路，SKIP 继续），链尾兜底拒绝。

### 6.2 目标契约（filter 形态，与 §3.1 同一范式）

```java
/** 工具执行拦截节点：下行=执行前检查，上行=执行后处理（现状没有的能力）。 */
public interface ToolExecutionInterceptor {
    String id();
    float order();                       // float 与任务洋葱对齐
    /**
     * 下行段（next 前）：检查本轮工具调用；不调 next 直接 return = 短路（合成结果，
     * 等价现 beforeToolExecution 非 null）。
     * next() = 后续节点 + 真实工具执行（原 delegate.executeToolCalls）。
     * 上行段（next 后）：结果后处理/审计/计时/异常翻译——本范式新增的能力。
     */
    ToolExecutionResult invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception;
}

/** 授权决议节点：ALLOW/DENY = 短路 return；不处理 = return next.proceed(req)。 */
public interface AuthorizationHandler {
    String id();
    float order();
    /**
     * 下行段：前置判断（原 applies 的过滤语义并入「不处理即 next」）；
     * 决议短路：return ALLOW/DENY（原 decide 短路）；
     * 上行段：决议产生后的审计/升级/宽限期处理——本范式新增的能力。
     */
    AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception;
}
```

`ToolExecutionContext` = 现 beforeToolExecution 三参数 + 显式任务上下文（替代 `InterceptingToolCallingManager.currentTask()` ThreadLocal——插件不再依赖 worker 内部类取任务，依赖注入化）。

### 6.3 迁移映射（行为等价）

| 现状 | filter 形态 |
|---|---|
| `beforeToolExecution(...)` 返回非 null（短路） | 下行段不调 `next`，直接 return 合成结果 |
| `beforeToolExecution(...)` 返回 null（放行） | 下行段直接 `return next.proceed(ctx)` |
| `decide()` 返回 ALLOW/DENY（短路） | 下行段 return 决议 |
| `decide()` 返回 PASS / `applies()`=false | `return next.proceed(req)` |
| `InterceptingToolCallingManager` for 循环 | 链组装器（同 TaskOnion 折叠方式，链尾 = 真实 manager 执行） |
| `LoopRepeatGuardToolManager`（死循环守卫装饰器） | **保持装饰器形态不迁**：它是核心守卫不是插件扩展点，且需在 `executeToolCalls` 处合成工具结果回传模型（框架唯一允许点），与插件链职责不同 |
| 内部检查链（WorkspaceAllowCheck 族，SKIP 继续） | 同范式迁移：SKIP = `next.proceed`，ALLOW/DENY = 短路（保持三链分离：文件/命令/提权入口不同，链尾兜底拒绝不变） |

### 6.4 收益与代价

**收益**：① 工具链获得上行钩子（执行后审计/耗时统计/结果改写，现状缺失）；② 授权链获得决议后包裹（审计、审批升级、宽限期）；③ 三条链（任务洋葱/工具/授权）一种心智模型与一种调试方式；④ ThreadLocal 上下文传递被显式化（`ToolExecutionContext` 注入）。

**代价（破坏性）**：两个 SPI 签名变更，现有使用者须同步迁移——`UnattendedToolInterceptor`、`AiReviewAuthHandler`、`UnattendedAuthHandler`（auth-review 插件，**并发会话活跃区，迁移需协调**）；`PermissionGate` 内部三链约 9 个检查节点重构。兼容选项：过渡期提供旧接口适配器（`beforeToolExecution` 包成「下行段+透传」的 filter 节点）一次性弃用，或直接切换（repo 内使用者全量可枚举，建议直接切换少一层间接）。

### 6.5 实施位置

独立 **Phase 3「拦截链范式统一」**（在 agent 层收敛之后：工具链上下文显式化依赖 agent 层边界；subagent 插件顺延 Phase 4，队列插件 Phase 5）。Phase 1 明确不动这两条链。

---

## 7. 实施计划

| Phase | 内容 | 交付 |
|---|---|---|
| **1（本次）洋葱底座** | plugin-api 节点契约（`TaskLifecycleNode`/`TaskLifecycleContext`/`TaskOutcome`/`TaskKernel`）+ `registerTaskLifecycleNode`；`TaskLifecycleRegistry`（float 排序）+ `TaskOnion`；17 个内置节点拆分（下行 4 + 上行 13，finish/runTask/rpcTaskRun 逻辑**逐字映射**，不改行为）；`runTask` 重写为洋葱执行；单测（下行顺序/否决短路/逆序收口/try-finally 必达/幂等/取消/停机中断收口/锁分段）；ARCHITECTURE.md 新 §7.x | worker 行为零变化（事件顺序、seq、落盘字节级兼容），可回归验证 |
| **2 agent 层** | `dev.everyagent.worker.agent` 包收敛 + `AgentContext`/`AgentEventChannel` 解耦 + `AgentService` + `AgentDispatcher` 废止 + `SubAgentManager` 编排下沉 | agent 层边界成立（能力全部留在核心），其他插件可依赖 |
| **3 拦截链范式统一** | 工具执行链 + 授权决议链（含 PermissionGate 内部三链）迁移为 filter 形态（§6）；`ToolExecutionContext` 显式化替代 ThreadLocal；两个 SPI 使用者同步迁移 | 三链一种范式；工具链获得上行钩子、授权链获得决议后包裹 |
| **4 subagent 插件** | 三件套迁移（工具 + SkillContributor + 前端面板**及取数链**：task.agents RPC 迁插件注册、taskStream 的 fetchTaskAgents 调用点条件化、agentMeta 基线灌入随面板走）；plugin-api 新增 `SkillContributor` SPI + 注册表；BuiltInSkills 合并改造；web `ComposerPanelCtx` 扩展 `selectAgent`；app pom 挂载 + CI 一致性检查 | 删除插件 = 无子 agent 工具/skill/前端列表/**task.agents RPC**，agent 层（台账写入/恢复/事件）完好 |
| **5（未来）队列插件** | 形态三成对节点（下行段 enqueue 阻塞排队 / finally 出队广播）+ RPC 边缘预检扩展点 | 底座已就绪，不在本设计实施范围 |

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| finish 拆节点后收口顺序/竞态回归 | 逆序不变量逐条对照（§3.3：收口序=进入序逆序=现状顺序）；锁分段等价论证（§3.3 锁策略）；补 onion 单测 + 全量 worker 测试回归 |
| 再运行（startRerun）路径绕过洋葱 | 再运行的 track/seed/wire 逻辑同样走 `persistence.track`/`task.wires` 下行段（复用同一节点实现，不另写一份） |
| DataPusher 换日志时机变化 | track 仍在 `persistence.track` 下行段执行；执行线程从 RPC 线程移到任务线程开头（外部可见行为不变：meta 最终一致、ctx.ok 应答仍即时） |
| AgentEntity 解耦破坏事件语义（roundSeqs 同轮共享 seq） | AgentEventChannel 由 TaskEvents 直接 implements，seq 逻辑不动，只换接口面 |
| 洋葱≠手搓 agent 循环的红线 | 内核调 AgentService→AgentRunner→ChatClient（Spring AI 工具循环）；洋葱只编排任务生命周期层 |
| 并发会话冲突（git 插件迁移进行中） | Phase 1 不碰 plugin 模块与 app pom；`SubAgentManager` 仅改调用点不动文件位置 |
| AgentListPanel 前端迁移破坏选中联动/数据流 | 组件数据订阅逻辑原样迁（task.agents + 流事件）；选中联动经 `selectAgent` 回调由核心实现，插件不持跨插件状态；迁移期与核心实现并跑对照 |
| skill 双源合并的顺序/去重（内置 vs 插件贡献） | SkillContributorRegistry 输出按 pluginId+skillId 去重，插件 id 冲突沿用 BuiltInPlugins 冲突检查惯例 |

---

## 9. 已确认决策（历次评审结论）

1. **节点模型**：无层粒度；一节点一事；order 用 float（已落实到 §3）。
2. **`AgentDispatcher` 废止**：plugin-api 预留 SPI（dispatch/waitFor/stop/list 的「子 agent 调度策略」扩展点），全仓零实现零消费——确认无用，Phase 2 删除，语义并入 `AgentService`。
3. **消息流中 agent 生命周期事件渲染**：保留 web 核心（渲染事件流=agent 层契约，历史任务在插件删除后仍正确展示）。
4. **原 L4（等子 agent/级联停）**：拆为 `spawned.await`（950）+ `cascade.stop`（900）两个上行节点，Phase 1 直接包含（收口动作仅为方法搬移，触碰 `SubAgentManager` 面极小——`awaitAllBeforeFinish`/`stopAll` 两个既有方法调用）。
5. **节点为 Servlet Filter 参与式链**（v4）：`result = next(ctx)`；下行=next 前、上行=next 后；收口序=进入序逆序。
6. **工具执行链与审核授权链同样迁移为 filter 形态**（v5）：写入 §6，独立 Phase 3 实施，Phase 1 不动。
7. **task.agents RPC 归 subagent 插件**（v6）：其唯一消费闭环是子 agent 列表（TaskPacketView 拉取 → seedAgents 灌 agentMeta 基线 → AgentListPanel）；消息流 agent 卡片靠事件 payload 自足不依赖它。删除插件 = RPC 无提供者也无使用者。agent 层保留台账写入/恢复（spawn 持久化真相源）。

## 10. 遗留待确认

1. **skill 只随 subagent 插件走是否够用**：若未来别的插件也要贡献 skill，`SkillContributor` SPI 做成通用扩展点（设计已按通用写，subagent 是第一个使用者）。
2. Phase 2 的 Maven 物理分模块（`every-agent-agent`）时机：与包内收敛同步做，还是包收敛稳定后再拆（我建议后者）。
