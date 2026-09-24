# 设计方案：任务生命周期洋葱模型 + Agent 独立成层

> 状态：**已评审认可，待实施** · v11（全量自查修订：架构图修正洋葱归属 Task 编排层；锁策略补 runExistingTask 再运行认领反例→850..420 连续段临界区组合器为默认设计；§1.2 目标对齐 v6/v7；EventLog 归属修正；order 撞号错开 390/420；执行器中断译 CANCELLED；LOG_OVERFLOW 不发 error 逐字保留；前端插件取数通道补齐）
> 范围声明：本文覆盖整体架构（生命周期链底座、agent 层独立、subagent 插件三件套、任务队列插件预留）；**本次只实施 Phase 1（生命周期链底座）**，其余分期列出。命名约定：洋葱模型只是设计隐喻（下行/上行的同心结构），**不进任何 Java 类名/包名**——代码一律用职责命名（TaskLifecycleExecutor/TaskLifecycleNode/`task.lifecycle` 包）。

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
5. **subagent 插件**职责（v2 提出，v6/v7 扩充；权威表述见 §5.1）：提供 ① 四个子 agent 工具 ② 一个 skill（「子 Agent」方法论）③ `task.agents` RPC ④ 前端列表 UI 及取数链 ⑤ 子 agent 台账 agents.json（事件投影）。
6. **删除该插件** = 子 agent 域一切概念消失（无工具/skill/RPC/列表/台账读写），**但 agent 层还在**（spawn/事件/执行态）；历史任务子 agent 的消息流靠事件 payload 自足渲染（不依赖台账/RPC）。

---

## 2. 目标架构总览

```
外部帧(hub cmd：task.run / git.status / flow.start …)
   │ 方法注册于基础设施 RpcDispatcher；task.* 由 Task 编排层注册、git.* 由插件注册，平权
   ▼
┌─ 调用者层（agent 层的编排/入口形态，互相平级） ──────────────────────────────────┐
│                                                                                │
│  ┌─ Task 编排层（现状核心：task.* RPC + 轮次循环 + 前端展示；未来可能插件化） ──┐ │
│  │                                                                           │ │
│  │   ┌─ Task 生命周期链（TaskLifecycleExecutor，Task 编排层内部机制） ─────┐  │ │
│  │   │ 下行 = next(ctx) 前（外→内）      上行 = next 返回后（内→外）      │  │ │
│  │   │ track(100)→wires(200)→status.start(300)→main.agent(390)            │  │ │
│  │   │   →【内核 = 调用 AgentService（多轮+输入队列）】                    │  │ │
│  │   │ ← spawned.await(950)←cascade.stop(900)←status.finalize(850)        │  │ │
│  │   │   ← …log.flush←status.persist(650)←registry.remove(420)←activity  │  │ │
│  │   │ 节点=单一职责（一节点一事）；order float 可任意插位；               │  │ │
│  │   │ 未来队列插件 = 在 100~390 空隙插成对节点（enqueue 阻塞/出队）。     │  │ │
│  │   └────────────────────────────────────────────────────────────────────┘  │ │
│  └───────────────────────────┬───────────────────────────────────────────────┘ │
│                              │ 都调用（同一执行底座）                          │
│  subagent 插件（工具+skill+task.agents RPC+前端列表+台账投影；不经洋葱）        │
│  workflow 层（未来：另一种编排形态，与 task 平级，复用同一底座）                │
│  其他插件（如 auth-review 的 AiAuthReviewer 正规化）                           │
└──────────────────────────────┬─────────────────────────────────────────────────┘
                               ▼
  ┌──────────────────────── Agent 层（纯内存执行引擎，零持久化） ───────────────────┐
  │  run(agent)          同步运行单个 agent 至最终回答（现 AgentRunner，薄）        │
  │  spawn/waitFor/stop/list   子 agent 异步编排（现 SubAgentManager 核心逻辑）    │
  │  AgentEventChannel   事件发射端口（内存 append；落盘由调用者层承接）           │
  │  运行期 spawn 注册表（纯内存：实体/futures/终态——waitFor/stop/list 执行态）  │
  │  ChatClient + Advisor 生态（复用 Spring AI，红线不动）                          │
  └──────────────────────────────────┬─────────────────────────────────────────────┘
                                     ▼ 依赖
  ┌────────────── 基础设施层（与调用者无关的底层功能，三个子域） ───────────────────┐
  │  ① 模型与配置：ModelSnapshot · ConfigStore（解析+密钥）· ChatModelFactory       │
  │     · 模型池容灾 + 限流——上层要跑 agent 从这里取模型与密钥                      │
  │  ② RPC 通信：hub 连接（HubPool/conn）· RpcDispatcher（方法注册表+分发）         │
  │     ——task 层注册 task.*、git 插件注册 git.*、workflow 注册 flow.*，同位平权    │
  │  ③ 流式推送：EventLog（通用内存事件日志：seq 分配/append/listener）             │
  │     · DataPusher/Manager（stream 频道订阅定向推送、credit 背压）                │
  │     · StreamSourceRegistry（流源注册：编排层把「流键→EventLog」挂进推送器）     │
  └───────────────────────────────────────────────────────────────────────────────┘
```

**核心原则**：
- **洋葱内核不直接 `runner.run`，而是调 `AgentService`**——从第一天起「任务 = 调用一个 agent（对话式）」的语义就成立；subagent 插件 = 调用一个 agent（一次性）+ 子 agent 域交互面（工具/skill/RPC/列表/台账）。两种调用模式平级；洋葱只属于 Task 编排层，其他调用者不经过它。
- **agent 层是核心，不随 subagent 插件卸载**：删除插件失去「AI 使用子 agent 的入口与方法论」「列表 UI」「task.agents RPC」与**整个子 agent 台账概念（agents.json 的读写全部停止——它是插件维护的事件投影，磁盘数据原地保留不删）**；agent 层能力（spawn/事件/运行期 spawn 注册表）保留——历史任务子 agent 的消息流靠事件 payload 自足渲染（title/input/usage 都在事件里），其他上层仍可编程调用。
- 洋葱是**任务生命周期层**的编排，**不是** agent 执行循环；agent 执行循环仍由 Spring AI `ToolCallingAdvisor` 递归驱动（红线：不手搓）。
- **task 层 = agent 的一种编排**（洋葱 + 轮次循环 + RPC + 前端展示），不是 agent 层的宿主；未来可能插件化，届时 workflow 等平级编排形态共用 agent 层与模型与配置域。
- **基础设施层与调用者无关**（三个子域）：① 模型与配置（类型/解析/密钥/实例化/容灾限流）——task 层只在**任务创建时**冻结快照并持有引用（任务运行期模型不变的任务语义）；② RPC 通信（hub 连接+RpcDispatcher 方法注册分发）——task 层注册 task.*、git 插件注册 git.*、未来 workflow 注册 flow.*，**同位平权**；③ 流式推送（通用 EventLog seq 事件日志 + DataPusher 订阅推送 + StreamSourceRegistry 流源注册）——task 层在 track/再运行时把「taskId→EventLog」挂进流源注册表，推送器不再反向感知 TaskManager（现状 `DataPusherManager` 经 `TaskResumeListener` 够进 TaskManager 的耦合反转）；workflow 的流同样经此推送。

---

## 3. 洋葱模型设计（Phase 1 核心）

### 3.1 契约（放 `every-agent-plugin-api`，让插件能贡献节点）——Servlet Filter 风格参与式链

**没有预设「层」粒度**——生命周期链（洋葱模型）= **单一职责节点的有序列表**，但节点不是「两个回调」（onStart/onEnd），而是像 **Java Servlet Filter** 一样**参与执行**：节点拿到运行上下文，调用 `result = next(context)` 得到**后面全部节点 + 内核**的执行结果。`next()` 之前 = 下行（开始）阶段，之后 = 上行（结束）阶段。

```java
/**
 * 任务生命周期节点（Servlet Filter 风格）。
 * invoke() 内调用 next.proceed(ctx) 之前的代码 = 下行；之后的代码 = 上行。
 */
public interface TaskLifecycleNode {
    String id();
    /** 链上位置：升序 = 外→内（洋葱下行序）。float 允许任意插位；同 order 按注册顺序（稳定排序）。 */
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
/** 任务生命周期执行器：按 order 升序把节点组装为嵌套链，链尾接内核（洋葱模型实现）。 */
public final class TaskLifecycleExecutor {
    public TaskOutcome run(List<TaskLifecycleNode> nodes, TaskKernel kernel, TaskLifecycleContext ctx) {
        TaskChain chain = kernel::run;                    // 链尾 = 内核（异常已翻译）
        for (int i = nodes.size() - 1; i >= 0; i--) {     // 由内向外包裹
            TaskLifecycleNode node = nodes.get(i);
            TaskChain inner = chain;
            chain = c -> node.invoke(c, inner);
        }
        try {
            return chain.proceed(ctx);
        } catch (InterruptedException e) {                // 节点下行段被取消中断 → CANCELLED（非 FAILED）
            Thread.currentThread().interrupt();
            return TaskOutcome.cancelled();
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

filter 模型下收口序恒等于进入序的逆序（同一 order 决定两端位置）。**关键推论**：v3 的「一节点一事」划分（track/untrack 分离、纯收口动作独立成节点）恰好让逆序收口序与现状执行顺序**逐项一致**——不需要任何顺序变化。17 节点 = 4 个形态一（纯下行）+ 13 个形态二（空下行段纯收口）；形态三（成对 try/finally）是留给未来插件的。order 值各不相同（390/420 错开，避免同 order 靠注册序消歧的脆弱性）。

**下行节点（invoke 的 next 之前，order 升序 = 执行序）**：

| order | 节点 id | 下行段职责（一事） | 现状出处 |
|---|---|---|---|
| 100 | `persistence.track` | `store.track`：建目录 + 首写 meta.json + 挂 EventLog 监听 | rpcTaskRun L1078 |
| 200 | `task.wires` | 注入 `onUsageBroadcast` / `persistHook`（TaskEntry 钩子字段；persistHook 属台账，Phase 4 随插件迁出） | wireUsageBroadcast L1938 / wireAgentPersist L1957 |
| 300 | `status.start` | `startedAt` + `setStatus(RUNNING)` + task.updated 广播 + `agentStatus("running")` | runTask L1635-1637 |
| 390 | `main.agent` | `buildMainAgent` + `consumeInput(首条输入)` | runTask L1638-1639 |

**内核**：轮次循环 `while(true){ agentService.run(main); inputQueue.poll… }`（对话式调用 agent；Phase 1 内核直接调 `AgentRunner.run`——`AgentService` 是 Phase 2 产物，届时换调用点不动洋葱结构，见 §7.1 红线 2）。

**上行节点（invoke 的 next 之后，按执行先后排列 = order 降序；全部为形态二——下行段为空透传）**：

| 执行序 | order | 节点 id | 上行段职责（一事） | 锁 | 现状出处 |
|---|---|---|---|---|---|
| 1 | 950 | `spawned.await` | `awaitAllBeforeFinish`（等全部子 agent，超时级联停） | 锁外 | runTask L1672 |
| 2 | 900 | `cascade.stop` | result≠DONE 时按 status 分支：`stopRequested` + `stopAll` + `asks.cancelTask`；CANCELLED 发 `cancelled` 事件 / FAILED 发 `error` 事件（现状两个 catch 分支的差异收敛为看 result）。**例外逐字保留**：LOG_OVERFLOW 的 FAILED 不发 error 事件（现状该分支即无），节点按 error 类型判别 | 锁外 | runTask catch L1680-1705 |
| 3 | 850 | `status.finalize` | 终态 CAS（幂等门）+ `endedAt/error/status` + agentStatus 终态事件 + 终态广播 | 锁内 | finish L1866-1875 |
| 4 | 800 | `concurrency.release` | `active.decrementAndGet()` | 锁内 | finish L1876 |
| 5 | 750 | `log.flush` | `store.flush`（等落盘追平，30s 超时放行） | 锁内 | finish L1877 |
| 6 | 700 | `queue.persist` | `store.writeQueue`（悬空输入队列落盘） | 锁内 | finish L1888 |
| 7 | 650 | `status.persist` | `store.updateMeta`（**状态收口写磁盘**） | 锁内 | finish L1889 |
| 8 | 600 | `ledger.persist` | `store.writeAgents`（agent 台账终态快照；Phase 4 改由 subagent 插件经 registerTaskLifecycleNode 贡献，删插件即无此节点） | 锁内 | finish L1891 |
| 9 | 550 | `disk.index` | `diskTasks.put`（终态任务转磁盘索引） | 锁内 | finish L1892 |
| 10 | 500 | `persistence.untrack` | `store.untrack`（关闭全部 jsonl writer） | 锁内 | finish L1893 |
| 11 | 450 | `gate.evict` | `gate.untrack`（授权内存驱逐） | 锁内 | finish L1894 |
| 12 | 420 | `registry.remove` | `tasks.remove`（两参原子，再运行认据此判输赢；与 main.agent(390) 错开 order 避免同值歧义） | 锁内 | finish L1895 |
| 13 | 350 | `workspace.activity` | `activityTracker.onTaskFinished` | 锁外 | finish L1915 |

**顺序不变量**（重构正确性的判据）：
- 上行 1-13 的总顺序 = 现状 `runTask 尾部 → finish 内部` 的执行顺序**逐项对应**（await/cascade 在 finish 之前=锁外；3-12 对应 finish 持锁段；13 锁外）。
- 下行 100→390 = 现状 `rpcTaskRun（track/wire）→ runTask 头部（RUNNING/build/consume）`。
- **留在洋葱外（RPC 边缘）**：幂等键去重、并发上限检查 + `active.incrementAndGet()`（需同步应答 ERR_BUSY，TOCTOU 语义要求在 RPC 线程）、`TASK_CREATED` 广播、`ctx.ok` 应答——属「创建」而非「开始」。未来队列插件见 §3.5。
- **锁策略（filter 模型下为节点实现细节）**：现状 finish 全程一个 `synchronized(t)` 大临界区；新模型由各锁内节点上行段**各自** `synchronized(ctx.taskLock())`。安全性论证（区分两类并发方）：
  - **cancel（幂等，间隙无害）**：终态 CAS 已在第一个临界区（status.finalize）完成，rpcTaskCancel 插进间隙只置 stopRequested/stopAll——对已终态任务无害；finish 重入被 CAS 拦截。
  - **再运行认领（非幂等，间隙有害）**：`runExistingTask` 的认领条件含 `diskTasks`——若 `disk.index`(550) 完成后、`persistence.untrack`(500)/`registry.remove`(420) 前的间隙被认领，`startRerun` 会重新 `store.track`，与 finish 线程的 `untrack` 直接竞态（刚挂的 writer 被关）。**对策（默认设计而非备选）**：`status.finalize..registry.remove`（850..420）连续锁内段保持**同一临界区**——实现为该段节点共享一次 `synchronized(ctx.taskLock())` 进入（TaskManager 侧组装时对该连续段包一层临界区组合器；对外仍是独立节点，PluginStateStore/排序无感知），认领方只能在 `tasks.remove` 之后获得认领资格，与现状大临界区语义等价。`spawned.await`/`cascade.stop`（950/900，锁外）与 `workspace.activity`（350，锁外）不受影响。

### 3.4 TaskLifecycleContext（节点的读写面）

不从 plugin-api 暴露 `TaskEntry`（避免外部插件强耦合核心，重复 git/auth-review 反向依赖的旧路）。窄接口起步：

- 只读：`taskId / title / workspaceRoot / workspaceId / status / mainAgentId`
- 同步原语：`taskLock()`（锁内节点上行段自行 `synchronized`；850..420 连续段经临界区组合器共享一次进入，见 §3.3 锁策略）
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

迁入 agent 包：`AgentRunner`、`AgentClientFactory`、`AgentEntity`、`WorkerToolEventAdvisor`、`LoopRepeatGuard*`、`AgentCancelledException`、`AgentActivity`。

**归基础设施层（三个子域，不进 agent 包——agent 层与编排层都依赖它、不拥有它）**：

| 子域 | 内容 | 现状耦合需反转/收敛点 |
|---|---|---|
| 模型与配置 | `ChatModelFactory`（快照→ChatModel）、`ModelPoolChatModel`/`RateLimitedChatModel`（池容灾/限流）、`ConfigStore` 解析与密钥、`ModelSnapshot` 类型 | `SubAgentManager`/`TaskManager` 改经基础层接口调用 |
| RPC 通信 | `RpcDispatcher`（注册表+分发）、`HubPool`/hub 连接管理、`RpcContext` | task.* 方法注册已是 dispatcher 模式，无需反转——只需**定位声明**：dispatcher 属基础设施，task 层/git 插件/workflow 是平权注册方 |
| 流式推送 | `EventLog`（通用内存事件日志：seq 分配/append/listener/readFrom）、`DataPusher`/`DataPusherManager`（stream 频道订阅推送、credit 背压）、新增 `StreamSourceRegistry` | **反转**：现状 `DataPusherManager` 经 `TaskManager.TaskResumeListener` 反向取 task 的 EventLog——改为编排层调 `streamSources.attach(streamKey, log)/detach(...)`（task 层挂接点=洋葱 `persistence.track` 与 startRerun），推送器零 task 感知 |

依赖方向：调用者层（task/workflow/插件）与 agent 层 → 基础设施层；基础设施层不知道任何调用者。`task.poll` 的历史读取（TaskStore 磁盘窗口归并）仍属 task 域——它读的是任务数据格式；推送器只管内存日志增量。

**不进 agent 包**：`TaskEvents`、`TaskStore`、`RoundIndex*`、`FileChange*`、`InputQueue` 留 task 层（经接口面向 agent 层）；`EventLog` 归基础设施层流式推送子域（见上表，v10——task 层使用它作为每任务的内存事件日志实例）。

**持久化边界（零持久化原则）**：agent 层不管任何落盘，只管内存态——

| 持久化物 | 归属（都不在 agent 层） |
|---|---|
| 事件流 jsonl（含子 agent 消息按 agentId 落盘） | task 层 TaskStore（订阅基础设施 EventLog→sink 虚拟线程；EventLog 本身是基础设施内存态，不落盘） |
| 会话历史磁盘重建（ConversationLoader，再运行） | task 层（load 后把 List\<Message\> 传给 buildMainAgent；agent 层无 from-disk 恢复 API） |
| 轮次索引 rounds.jsonl / 悬空队列 queue.jsonl / meta.json | task 层（RoundIndexStore / TaskStore） |
| 子 agent 台账 agents.json | subagent 插件域（事件投影，v7） |
| 模型配置快照/密钥 | **模型与配置域（基础层）**：类型/解析/密钥/实例化/容灾都在此；task 层仅创建时冻结快照并持有引用（TaskEntry 字段，内存+meta 落盘），workflow 等未来上层同样从基础层获取 |

agent 层的内存态清单：AgentEntity（conversation/options/tools/usage 累计/终态）、spawn 注册表（实体表/futures）、AgentActivity 活跃快照——任务终态即随洋葱收口释放，不提供恢复。子 agent 会话"一次性不重建"为既有语义（事件流已完整落盘，重建属读路径=插件/前端的事）。

### 4.2 解耦关键：AgentEntity 去 TaskEntry 化

```java
// 现在：class AgentEntity { TaskEntry task; … a.task.events.delta(…); }
// 之后：
public interface AgentContext {                  // agent 层需要的任务面（窄接口）
    String taskId();
    String workspaceRoot();
    AgentEventChannel events();                  // 发射端口：内存 EventLog append；落盘由 task 层 TaskStore 承接
    boolean taskTerminal();                      // status.terminal()（事件泄漏防御用）
    void recordMainUsage(Usage u, …);            // 主 agent 电池（原 task.recordUsage + onUsageBroadcast；内存记录，meta 落盘在 task 层）
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
    /** 异步派生子 agent（现 SubAgentManager.run 核心逻辑下沉）。入参为 AgentContext/装配参数，不触 TaskEntry。 */
    String spawn(AgentContext ctx, String input, String title, String reuseAgentId);
    AgentWaitResult waitFor(AgentContext ctx, String agentId, long timeoutMs);
    void stop(AgentContext ctx, String agentId);
    void stopAll(AgentContext ctx);
    List<AgentSummary> list(AgentContext ctx);   // 返回内存执行态摘要（AgentActivity/usage/终态）
}
```

处置既有预留：**`AgentDispatcher` SPI + `AgentDispatcherRegistry`（现零实现零消费）废止删除**，其语义被 `AgentService` 取代（dispatch→spawn、waitFor/stop/list 同名）。避免两套等价 SPI 并存。

### 4.4 调用者平级化

| 调用者 | 用法 |
|---|---|
| **Task 层**（洋葱内核） | `agentService.run(main)` × 轮次循环（对话式：多轮 + 输入队列 + waiting-user） |
| **subagent 插件** | `spawn/waitFor/stop/list`（一次性：单任务跑完即止）+ skill + 前端列表 UI（§5） |
| **其他插件** | 如 auth-review 的 `AiAuthReviewer` 从自建 AgentEntity 旁路改为正规调 agent 层（收编现存的「agent 层被旁路使用」先例） |
| **workflow 层（未来）** | 插件接入的另一种编排形态：从模型与配置域取配置，按自己的编排语义（DAG/步骤/审批流…）调 `AgentService`，与 task 层平级共用底座——本设计通过「agent 层不含编排、模型与配置域不含调用者语义」为它留位 |

---

## 5. subagent 插件（Phase 4）

### 5.1 职责边界（v2 调整核心）

「子 agent」拆成**能力（核心）**与**入口/交互（插件）**两部分：

| 归属 | 内容 | 删除插件后 |
|---|---|---|
| **agent 层（核心）** | `AgentService.spawn/waitFor/stop/list`、`run`、agent 生命周期事件（agent.started/done/status）、**运行期 spawn 注册表**（AgentEntity 表/futures/终态——waitFor/stop/list 的执行态，实现这三方法本身所需）、消息流中的 agent 事件渲染（历史数据，事件 payload 自足：title/input/usage 都在事件里） | **保留**——spawn 执行能力与事件流渲染；其他上层仍可编程调用 |
| **subagent 插件** | ① 4 个工具（run_agent/list_agents/wait_agents/stop_agent）② 1 个 skill（「子 Agent」方法论）③ **`task.agents` RPC**（唯一取数口，经 registerRpcMethod 注册——git 插件先例；方法名保留）④ 前端列表 UI 及其取数链（fetchTaskAgents/seedAgents 基线/agentMeta 列表消费 + AgentListPanel）⑤ **子 agent 台账 `agents.json`**：插件维护的事件投影（订阅 agent.*/usage 事件更新内存台账，洋葱节点 `ledger.persist` + 定时快照落盘；读侧 disk 优先、读时把陈旧 running 归一为 stopped——现 restoreAgentLedger 语义前移到读路径） | 全部消失——AI 无派子 agent 入口与方法论、前端无列表、task.agents 无提供者无使用者、**台账无读无写（磁盘文件原地保留，重装插件即可继续读）** |

插件是**纯薄壳**：工具方法体 = `agentService.spawn(...)` 等一行委托；skill 文案 = 使用方法论；前端组件 = 订阅 agent 层数据渲染。**不含任何执行逻辑。**

### 5.2 插件结构（worker 端 + web 端）

```
every-agent-plugins/subagent/
  pom.xml                                  # Maven：依赖 every-agent-plugin-api（+ agent 层 API，见 §4）
  src/main/java/dev/everyagent/plugin/subagent/
    SubAgentTools.java                      # 4 个 @Tool（从 worker tools/ 迁出，改调 AgentService）
    SubAgentToolsProvider.java              # ToolProvider：scope=MAIN（从 worker plugin/adapters/ 迁出）
    SubAgentSkillContributor.java           # SkillContributor：贡献「子 Agent」skill（见 §5.3）
    SubAgentLedger.java                     # 台账事件投影（订阅 agent.*/usage 维护内存台账 + 快照落盘 agents.json，v7/§5.1）
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
- 组件**自管数据**：经 ctx 拉取/订阅——`ComposerPanelCtx` 需扩展取数通道（Phase 4）：`rpc(workerId, method, params)`（调 task.agents，复用前端 SDK 管道）+ `subscribeTaskEvents(taskId, handler)`（订阅流事件 usage/agent.*，由 TaskPacketView 转发）；无子 agent（agents 空）时渲染 null（不占位）。
- 「选中子 agent → 过滤主线程显示」（现 `filterAgentId`/`handleSelectAgent` 是 TaskChat 内部状态）→ 扩展 `ComposerPanelCtx` 增加 `selectAgent(agentId | null)` 回调，核心实现联动；插件只调回调不持状态。
- **消息流中的 agent 生命周期事件渲染（agent.started/done 卡片）保留 web 核心**：它渲染的是事件流本身（agent 层契约），非交互入口；历史任务在插件删除后仍需正确展示。

### 5.5 删除插件的降级语义

| 面 | 表现 |
|---|---|
| 主 agent 工具集 | 无 run_agent 族（ToolProviderRegistry 已按 PluginStateStore 过滤，天然支持） |
| system prompt / `/` 菜单 | 无「子 Agent」skill 条目（SkillContributorRegistry 过滤） |
| 前端 | composer 上方无胶囊列表（agentMeta 列表消费随之无意义，保持空置无害）；消息流中历史 agent.started/done 事件仍渲染（核心，事件 payload 自足）；`task.agents` RPC 无提供者（打开任务的拉取调用点已条件化跳过，失败仅 warn 不阻断） |
| 台账 | 无读无写：agents.json 不再更新（磁盘文件保留）；重装插件后恢复读写（disk 优先读旧数据） |
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
| `InterceptingToolCallingManager` for 循环 | 链组装器（同 TaskLifecycleExecutor 折叠方式，链尾 = 真实 manager 执行） |
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
| **1（本次）生命周期链底座** | plugin-api 节点契约（`TaskLifecycleNode`/`TaskLifecycleContext`/`TaskOutcome`/`TaskKernel`）+ `registerTaskLifecycleNode`；`TaskLifecycleRegistry`（float 排序）+ `TaskLifecycleExecutor`；17 个内置节点拆分（下行 4 + 上行 13，finish/runTask/rpcTaskRun 逻辑**逐字映射**，不改行为）；`runTask` 重写为生命周期链执行；单测（下行顺序/否决短路/逆序收口/try-finally 必达/幂等/取消/停机中断收口/锁分段）；ARCHITECTURE.md 新 §7.x | worker 行为零变化（事件顺序、seq、落盘字节级兼容），可回归验证 |
| **2 agent 层** | `dev.everyagent.worker.agent` 包收敛 + `AgentContext`/`AgentEventChannel` 解耦 + `AgentService` + `AgentDispatcher` 废止 + `SubAgentManager` 编排下沉 + **基础设施层确立**（模型与配置域；RPC/EventLog/DataPusher 定位声明 + `StreamSourceRegistry` 解耦 DataPusherManager→TaskManager 反向依赖） | agent 层边界成立（纯内存执行引擎）+ 基础设施三子域可被平级上层复用 |
| **2.5 TaskManager 拆分与通信解耦** | ① AgentFactory（agent 装配：buildMainAgent/buildAgent/resolveAgentConfig 从 TaskManager/SubAgentManager 迁出）② ConfigRpcHandler（config.get/reload + skill.reload RPC 迁出，自注册）③ SlashTaskCallbacks（slash 建后回调迁出）④ EventSink 接口（fanout(channelNamer,...)，无调用者语义；HubPool implements，pubAllTasks/pubTaskStream/broadcastEvt 委托 fanout）⑤ TaskMessageRouter + TaskInputHandler（worker 输入频道订阅与 TASK_INPUT/DIALOG_INSERT/ASK_REPLY 路由，从 TaskManager.onHubMessage 迁出；下层定义接口、上层实现）⑥ 洋葱节点自注入（PersistenceTrack/UntrackNode 直持 StreamSourceRegistry；Status 节点持 EventSink，不经 TaskManager 传回调）⑦ TaskManager 瘦身：23→15 参数（SubAgentManager 11→4），onHubMessage→TaskInputHandler 三方法，广播改 fanout | TaskManager 不再 import HubPool；基础设施层新增无调用者语义的 EventSink/TaskInputHandler；Phase 4 后 task 层可与其他编排层平级复用通信/装配底座 |
| **3 拦截链范式统一** | 工具执行链 + 授权决议链（含 PermissionGate 内部三链）迁移为 filter 形态（§6）；`ToolExecutionContext` 显式化替代 ThreadLocal；两个 SPI 使用者同步迁移 | 三链一种范式；工具链获得上行钩子、授权链获得决议后包裹 |
| **4 subagent 插件** | 三件套迁移（工具 + SkillContributor + 前端面板**及取数链**：task.agents RPC 迁插件注册、taskStream 的 fetchTaskAgents 调用点条件化、agentMeta 基线灌入随面板走）+ **台账迁移**（agentLedger/writeAgents/restoreAgentLedger/30s 定时快照/persistHook→插件的事件投影：worker 侧新增任务事件观测 SPI 供插件订阅 agent.*/usage；`ledger.persist` 洋葱节点改插件贡献）；plugin-api 新增 `SkillContributor` SPI + 注册表；BuiltInSkills 合并改造；web `ComposerPanelCtx` 扩展 `selectAgent`；app pom 挂载 + CI 一致性检查 | 删除插件 = 无子 agent 工具/skill/前端列表/task.agents RPC/**台账读写**，agent 层（spawn 执行态/事件）完好 |
| **5（未来）队列插件** | 形态三成对节点（下行段 enqueue 阻塞排队 / finally 出队广播）+ RPC 边缘预检扩展点 | 底座已就绪，不在本设计实施范围 |

### 7.1 实施红线（每个 Phase 的执行 agent 必读）

1. **Phase 1 行为零变化**：事件发射顺序、seq 语义、落盘内容、任务可见状态流转与现状**逐项一致**（对照 §3.3 基线表逐字映射）；唯一声明的变化是 track/wire 的执行线程从 RPC 线程移到任务线程开头（§8 风险表第 3 行）。
2. **洋葱只编排任务生命周期**：内核经 `AgentService`（Phase 1 先保持 `runner.run` 现状调用——AgentService 是 Phase 2 产物，Phase 1 内核直接调 AgentRunner，Phase 2 换接口不动结构）→ ChatClient → Spring AI `ToolCallingAdvisor` 工具循环。**禁止在洋葱/节点内出现任何 agent 执行逻辑**。
3. **不发明机制**：执行器/节点基类/临界段组合按 §7.3 规格；节点划分按 §3.3 基线表 id 与 order；不新增/合并/拆分节点（那是设计变更，需改本文档再改代码）。
4. **边界纪律**：只动当前 Phase 清单内的文件（§7.2/§7.4）；跨 Phase 的内容（哪怕是顺手）不做。
5. 继承 agents.md 全部红线（Spring AI 复用、一个 Advisor 一件事、提交信息中文一次一事等）。

### 7.2 Phase 1 文件级实施清单

**新建（every-agent-plugin-api，新包 `dev.everyagent.plugin.api.task`）**：

| 文件 | 内容 |
|---|---|
| `TaskLifecycleNode.java` | §3.1 契约原样（id/order/invoke） |
| `TaskChain.java` | §3.1 契约原样 |
| `TaskLifecycleContext.java` | §3.4 窄接口（taskId/title/workspaceRoot/workspaceId/status/mainAgentId/taskLock/taskFlags/usage 广播钩子/agentStatus） |
| `TaskOutcome.java` | record + TaskEndStatus 枚举 + failed/cancelled 工厂（error 文案沿用 RootCause.summary 格式） |
| `TaskKernel.java` | §3.1 契约原样 |
| `WorkerPluginContext.java`（修改） | 新增 `registerTaskLifecycleNode(TaskLifecycleNode)` |

**新建（worker，新包 `dev.everyagent.worker.task.lifecycle`）**：

| 文件 | 内容 |
|---|---|
| `TaskLifecycleExecutor.java` | §3.1 执行器 + §7.3 临界段组装 |
| `TaskLifecycleRegistry.java` | `plugin/registry/` 模式：CopyOnWriteArrayList + PluginStateStore 过滤 + float 稳定排序（同 order 注册序） || `UpstreamNode.java` | §7.3 基类 |
| `nodes/` 下 17 个节点类 | 类名=节点 id 驼峰（如 `PersistenceTrackNode`/`StatusFinalizeNode`/`SpawnedAwaitNode`…），职责=§3.3 表逐字映射；构造注入所需协作者（store/props/asks/subs/pool/gate/activityTracker…），由 `BuiltInTaskLifecycleNodes`（同包配置类）装配注册 |

**修改（TaskManager.java）**：
- `runTask` 重写为：组装洋葱（registry + 内置节点 + §7.3 临界段）→ 内核=现 while 轮次循环**整体搬入** `TaskKernel` lambda（异常翻译按 §3.1 内核适配）→ `onion.run(...)`。
- **删除 `finish()`**：全部动作分散进 13 个上行节点；`finalize 标记`随 CAS 语义进 `StatusFinalizeNode`。
- `rpcTaskRun`：保留幂等键去重/并发上限/`active.incrementAndGet()`/`TASK_CREATED` 广播/`ctx.ok`；删除 track/wire 两行（移入洋葱下行）。
- `startRerun`：不另写收口路径——seq seed/台账恢复/悬空队列恢复等保持在内核前（rpcTaskRun 提交前的准备段），track/wire 复用洋葱下行节点。
- `rpcTaskCancel` / `shutdown`：cancel 不变；shutdown 改为对每任务 `cancel(true)` + 有限等待（§3.2 停机行），不再直接调 finish。
- catch 块中的 `subs.awaitAllBeforeFinish`/`stopAll`/`asks.cancelTask`/事件发射全部移入 `spawned.await`/`cascade.stop` 节点（LOG_OVERFLOW 不发 error 的例外按 §3.3 表保留）。

**Phase 1 不许碰**：plugin-api 现有 SPI（含 AgentDispatcher）、两条拦截链（§6）、`SubAgentManager.java` 文件位置与内部实现（只允许 TaskManager 侧调用点变化）、every-agent-plugins/ 全部、every-agent-app pom、every-agent-web、every-agent-hub。

**Phase 1 验收（DoD）**：
1. `mvn test` 全绿（全仓 Java 测试无回归）。
2. 新增单测（`task/lifecycle/TaskLifecycleExecutorTest`）：下行顺序=order 升序；否决短路（下行抛异常→内层不执行、已进入层收口）；必达（内核抛错/中断→全部上行段执行且仅一次）；幂等（outcome 重复消费 CAS 拦截）；取消（中断→CANCELLED 非 FAILED）；停机注入；临界段（850..420 单次持锁——用可重入锁计数断言）；LOG_OVERFLOW 不发 error。
3. 手动冒烟：新建任务/取消/再运行/停机四路径事件顺序与磁盘产物对照现状。
4. ARCHITECTURE.md 新增 §7.x（洋葱小节，引用本文档）。

### 7.3 机制规格：UpstreamNode 与临界段组合（防发明的关键设计）

13 个上行节点是「下行段为空」的形态二。为让 §3.3 锁策略的 850..420 连续段**单次持锁**且节点保持独立，规格如下：

```java
/** 形态二基类：下行空，final invoke = 透传 + 上行段。 */
public abstract class UpstreamNode implements TaskLifecycleNode {
    @Override
    public final TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        return up(ctx, next.proceed(ctx));
    }
    /** 上行段（收口动作）。不得抛异常（自吞记 WARN），可改写 result。 */
    protected abstract TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result);
}
```

`TaskLifecycleExecutor.assemble(nodes, ctx)` 折叠规则：
1. 按 order 升序折叠为嵌套链（§3.1）。
2. **临界段识别**：连续且 order ∈ [850,420] 的 `UpstreamNode` 连续序列 → 段边界包一次 `synchronized(ctx.taskLock())`，段内节点**直接调 `up(ctx, result)`**（绕过 invoke 的透传样板，否则每节点各自拿锁退化回分段）；段对外表现为 order=850 的单一链位置。
3. 段内上行执行序 = order 降序（850 最先）——与现状 finish 持锁段逐项一致。
4. 段外节点（spawned.await 950/cascade.stop 900 在段内边界之外先执行；workspace.activity 350 在段外最后）各自按 §3.1 invoke 语义。

实现提示：组装器返回 `TaskChain`，不引入新公开类型；`UpstreamNode.up` 为 protected，仅组装器（同包）直接调用——包内约定即机制边界，插件节点（非 UpstreamNode）永远走 invoke，自行持锁需遵 §3.3 锁策略（850..420 区间不允许插件节点插入，注册表按 order 区间拒绝并在注册时打 WARN）。

### 7.4 后续 Phase 边界与验收要点（简）

| Phase | 边界（不许碰） | 验收要点 |
|---|---|---|
| 2 agent 层 | 不动 web/hub/app pom；AgentDispatcher 删除与 AgentService 引入同一提交 | 主/子 agent 行为不变（同一入口同一 advisor 链）；StreamSourceRegistry 反转后推送时序对照现状；agent 包无 TaskStore/TaskEvents import（架构测试可加 import 规则检查） |
| 3 拦截链 | 不动洋葱节点；LoopRepeatGuardToolManager 保持装饰器 | 两个 SPI 使用者（auth-review×3）同 PR 迁移；拦截语义回归（无人值守短路/AI 审议链序） |
| 4 subagent 插件 | 不动 agent 层 API；task.agents 方法名与 wire 格式不变 | 卸载冒烟：无工具/skill/RPC/列表/台账写；历史任务消息流 agent 卡片仍渲染；app pom 挂载 + CI 检查生效 |
| 5 队列插件 | 只新增，不改既有节点 order | 排队/出队/上限语义端到端 |

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| finish 拆节点后收口顺序/竞态回归 | 逆序不变量逐条对照（§3.3：收口序=进入序逆序=现状顺序）；锁分段等价论证（§3.3 锁策略）；补 lifecycle 单测 + 全量 worker 测试回归 |
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
7. **task.agents RPC 归 subagent 插件**（v6）：其唯一消费闭环是子 agent 列表（TaskPacketView 拉取 → seedAgents 灌 agentMeta 基线 → AgentListPanel）；消息流 agent 卡片靠事件 payload 自足不依赖它。
8. **子 agent 台账整体归插件域**（v7）：agents.json 是插件维护的事件投影而非 agent 层真相源——删除插件 = 台账概念消失（无读无写），磁盘数据不删。`ledger.persist` 洋葱节点 Phase 4 改由插件贡献；插件经新增的任务事件观测 SPI 订阅 agent.*/usage 维护投影（具体 SPI 形态 Phase 4 定）。
9. **agent 层零持久化、只管内存态**（v8）：执行态（AgentEntity/spawn 注册表/usage 累计）+ 事件发射端口 + 会话内存对象；事件 jsonl/轮次索引/meta/台账/会话重建全在 task 层或插件域（§4.1 持久化边界表）。AgentContext 去掉 updateSpawnedLedger（advisor 的 SUB 台账刷新随之取消，台账由插件事件订阅承接）。
10. **模型配置/密钥归基础层**（v9）：与调用者无关；task 层只在创建时冻结快照并持有引用。**task 层定性**：agent 的一种编排，未来可能插件化，与 workflow 平级共用底座（本次仅留位）。
11. **RPC 与 DataPusher 归基础设施层**（v10）：RPC 通信（hub 连接+RpcDispatcher）与流式推送（EventLog+DataPusher）是通用通信能力——task 层经注册使用（与 git 插件同位平权），workflow 同样可用。`DataPusherManager` 对 `TaskManager` 的反向依赖（TaskResumeListener 取 EventLog）反转为 `StreamSourceRegistry` 流源注册（task 挂接点=洋葱 persistence.track 与 startRerun）。`task.poll` 历史读取（磁盘窗口）仍属 task 域（任务数据格式）。
12. **v11 全量自查修订**（评审后自查，方案内部一致性）：① §2 架构图重画——洋葱嵌于 Task 编排层内部，其余调用者不经洋葱；② 锁策略论证修正——runExistingTask 再运行认领**非幂等**（认领条件含 diskTasks），`disk.index→untrack→tasks.remove` 间隙可被认领与 untrack 竞态；对策升级为默认设计：850..420 连续锁内段经临界区组合器共享一次 `synchronized`（对外仍独立节点）；③ §1.2 目标清单对齐 v6/v7（task.agents RPC/台账归插件）；④ §4.1 EventLog 归基础设施（不迁清单修正）；⑤ main.agent 390 / registry.remove 420 错开 order；⑥ 执行器兜底 InterruptedException→CANCELLED；⑦ cascade.stop 对 LOG_OVERFLOW 不发 error 逐字保留；⑧ Phase 4 前端取数通道：ComposerPanelCtx 扩展 rpc + subscribeTaskEvents。
13. **Phase 2.5 通信解耦与职责拆分**（评审确认）：① PendingAsks 归基础设施层（agent 层经 AskUserTool 发起、task 层经 TaskInputHandler.onAskReply 路由、洋葱 CascadeStopNode 直持）；② StreamSourceRegistry/EventSink/TaskMessageRouter 均为基础设施层通用件——streamKey/频道名是调用者数据，基础设施不知道 task/flow；③ workspaces/gate 留在 TaskManager（任务创建注册工作区、每轮授权失效是 task 编排活依赖）；④ 分层定位：工作区→task/工作流（平级编排）→agent→基础设施，核心边界为下层不知道上层。

## 10. 遗留待确认

1. **skill 只随 subagent 插件走是否够用**：若未来别的插件也要贡献 skill，`SkillContributor` SPI 做成通用扩展点（设计已按通用写，subagent 是第一个使用者）。
2. Phase 2 的 Maven 物理分模块（`every-agent-agent`）时机：与包内收敛同步做，还是包收敛稳定后再拆（我建议后者）。
