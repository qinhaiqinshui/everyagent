---
title: 任务生命周期与 RPC
nav_order: 7
parent: backend
has_children: false
---

# 任务生命周期与 RPC

**一句话定位**：本篇讲挂在「任务域」上的 5 个注册点——任务创建准入预检（`TaskAdmissionPolicy`）、任务生命周期洋葱节点（`TaskLifecycleNode`）、自注册 RPC 方法（`registerRpcMethod`）、`/` 斜杠菜单两条扩展（`registerSlashProvider` / `registerSlashTokenResolver`）。核心交付物是 **洋葱 order 全表与数轴**（§2）——想往任务生命周期里插一个自己的节点，先看那张表再选位置；[420,850] 临界段是本域独有的注册期硬拒绝段（Advisor 链没有这回事，见 [advisors.md](advisors.md) §2.4）。

| 注册点 | 声明位置 | worker 侧注册表 / 分发器 | 内置使用者 |
|---|---|---|---|
| `registerTaskAdmissionPolicy` | `TaskPluginContext.java:17` | `TaskAdmissionPolicyRegistry`（至多一个，AtomicReference） | task-queue（唯一） |
| `registerTaskLifecycleNode` | `TaskPluginContext.java:24` | `TaskLifecycleRegistry` → `TaskLifecycleExecutor` | task-input-queue ×2、task-queue、task-edit-resend、subagent（4 插件 5 节点） |
| `registerRpcMethod` | `WorkerPluginContext.java:79` | `RpcDispatcher`（方法表 ConcurrentHashMap） | git ×13、task-input-queue ×3、subagent、task-queue、file-change（共 19 个方法） |
| `registerSlashProvider` | `WorkerPluginContext.java:87` | `SlashCommandRegistry`（→ `slash.list` RPC） | ai-review、git、sandbox-wsl-ubuntu、unattended（4 处） |
| `registerSlashTokenResolver` | `WorkerPluginContext.java:94` | `SlashTokenHandler`（同上 4 插件各自的 resolver） | 同上 4 插件 |

两个 task 域注册点声明在父接口 `TaskPluginContext`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/TaskPluginContext.java:17,24`），`WorkerPluginContext extends TaskPluginContext`——不需要 task 域能力的插件只依赖基础接口，不被迫传递性 import task 域类型（该接口类注释自证）。

## 1. TaskAdmissionPolicy —— 任务创建准入预检

### 1.1 接口定义（原码摘录）

`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/TaskAdmissionPolicy.java`：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/TaskAdmissionPolicy.java:9-19
public interface TaskAdmissionPolicy {
    /**
     * 检查是否准入该任务。
     * @param taskId 待创建的任务 ID
     * @param maxConcurrentTasks 配置的最大并发数
     * @return 准入结果
     */
    AdmissionResult check(String taskId, int maxConcurrentTasks);
}
```

返回值是 `AdmissionResult`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/AdmissionResult.java:8-17`）：

```java
public record AdmissionResult(boolean admitted, String rejectReason) {
    public static AdmissionResult admit() { return new AdmissionResult(true, null); }
    public static AdmissionResult reject(String reason) { return new AdmissionResult(false, reason); }
}
```

**语义只有两档**：`admit()` 放行任务进入洋葱；`reject(reason)` 在 RPC 边缘拒绝（reason 会原样进 `rpc.err`）。**没有第三档「排队」**——排队不是策略说了算，而是「策略放行 + 洋葱里的排队节点（如 `queue.admission`）在运行期 park 等待」两步合成的结果，见 §1.3。

### 1.2 何时被问询（worker 侧消费点）

注册表 `TaskAdmissionPolicyRegistry` 用 `AtomicReference` 持有**至多一个**策略（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/TaskAdmissionPolicyRegistry.java:9-25`），后注册者覆盖先注册者。唯一消费点是洋葱节点 `taskentry.create`（order=50）的**新建路径**（`every-agent-worker/src/main/java/dev/everyagent/worker/task/lifecycle/TaskEntryCreateNode.java:154-166`）：

- **无策略注册**：保持原有行为——`active >= maxConcurrentTasks` 时 `rc.err(Rpc.ERR_BUSY, "并发任务已达上限 N")` 硬拒绝；
- **有策略注册**：调 `policy.check(taskId, maxConcurrentTasks)`，`!admitted` 时 `rc.err(Rpc.ERR_BUSY, ar.rejectReason())` 拒绝。

即问询时机 = **`task.run` 不带 taskId 的新建任务**走到准入检查那一步；**再运行（rerun）路径不查并发上限**（该节点注释「准入检查（新建路径；rerun 不查上限）」自证，`TaskEntryCreateNode.java:154`；与 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §14.5「续跑放行不查并发上限」同口径）。

### 1.3 范例：task-queue（全仓唯一使用者）

```java
// every-agent-plugins/task-queue/src/main/java/dev/everyagent/plugin/taskqueue/TaskQueueAdmissionPolicy.java:8-16
/** 队列插件准入策略：always-admit。 */
public class TaskQueueAdmissionPolicy implements TaskAdmissionPolicy {
    @Override
    public AdmissionResult check(String taskId, int maxConcurrentTasks) {
        return AdmissionResult.admit();
    }
}

// 注册（TaskQueuePlugin.java:25）：ctx.registerTaskAdmissionPolicy(new TaskQueueAdmissionPolicy());
```

配套的洋葱节点（同一插件 `TaskQueuePlugin.java:24` 注册）：

```java
// every-agent-plugins/task-queue/src/main/java/dev/everyagent/plugin/taskqueue/QueueAdmissionNode.java:28-40
@Override public String id() { return "queue.admission"; }
@Override public float order() { return 40; }          // ⚠️ 代码是 40，见 §1.4
@Override public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
    taskQueue.acquire(ctx.taskId());                    // 下行：获取运行许可（可能阻塞，虚拟线程 park）
    try {
        return next.proceed(ctx);
    } finally {
        taskQueue.release(ctx.taskId());                // 上行：释放许可并唤醒下一个等待者（必达）
    }
}
```

两件套的分工：策略在 RPC 边缘 **always-admit**（不再 ERR_BUSY），节点在洋葱 order=40 用 Semaphore 排队——并发控制从「拒绝」换成「等待」。

### 1.4 ⚠️ 陈旧注释：250 ≠ 40（以代码为准，如实登记）

`QueueAdmissionNode` 的类 Javadoc 写 `order=250，落在洋葱下行空隙 100~400 之间`（`QueueAdmissionNode.java:12`），代码实际 `return 40`（`:31`）。同样写 250 的还有 `TaskAdmissionPolicy.java:7` 的接口 Javadoc、`TaskQueueAdmissionPolicy.java:10` 的注释，以及 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.14.4/§14.5。**以代码为准：order=40**，落在 RPC 线程段 `taskid.generate(30)` 与 `taskentry.create(50)` 之间（§2.3 全表）。ARCHITECTURE §7.14.4 还写该插件「以 `@Component` + 构造器注入注册」——实测 25 个内置插件源码零 Spring 注解、由 `URLClassLoader` 加载（[plugin-manifest 字段参考](../plugin-manifest.md) 与 [后端总览](overview.md) 已按代码事实写）。这些是文档欠账，不是代码问题，待架构文档侧统一更正。

## 2. TaskLifecycleNode —— 洋葱模型

### 2.1 接口定义（原码摘录）

`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/TaskLifecycleNode.java`：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/TaskLifecycleNode.java:7-27
/** 任务生命周期节点（Servlet Filter 风格）。 */
public interface TaskLifecycleNode {
    String id();
    /** 链上位置：升序 = 外→内（洋葱下行序）。float 允许任意插位；同 order 按注册顺序（稳定排序）。 */
    float order();
    /**
     * 契约：
     * - 下行段可否决：不调 next 直接 return Object（短路，内层不执行）或抛异常（执行器译为 FAILED）。
     * - 收口必达靠节点自己的 try/finally：推荐形态「下行动作在 try 外，next+收口包进 try/finally」——
     *   下行抛异常时本节点不收口（未进入不收口），next 之后无论成败收口必达。
     * - next() 拿到的一律是值（内核已把一切异常翻译成 Object），上行段通常无需 catch。
     * - 上行段可改写 result（如补 error 上下文）后返回，外层节点看到改写值。
     * - next 恰好调用一次：不调=否决；重复调=状态未定义（执行器打 ERROR 日志防御）。
     */
    Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception;
}
```

节点的读写面是 `TaskLifecycleContext extends ExecContext`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/TaskLifecycleContext.java:31-77`）：任务域成员 `taskId()/status()/title()/taskLock()/input()/rawContent()/runParams()/rpcContext()` + `subjectId()/emitter()/dataDir()/snapshot()/agentFactory()/interaction()/agents()` 等 ExecContext 槽位（均 default 桥接 `taskRuntime()`，早期 RPC 阶段任务未创建时返回 null/空——写节点时必须容忍）。槽位语义详见 [工具与沙箱扩展点](tools-and-sandbox.md) §5。

### 2.2 order 语义与方向（读执行器源码确定）

**升序 = 从外到内**。数值小者最外层（先见下行、后见上行），数值大者紧贴内核。依据（`every-agent-worker/src/main/java/dev/everyagent/worker/task/lifecycle/TaskLifecycleExecutor.java`）：

- `run()` 先按 `order` **升序稳定排序**（`Comparator.comparingDouble(TaskLifecycleNode::order)`，`:46-48`；同 order 按注册顺序，`TaskLifecycleRegistry.java:62-66` 同口径）；
- 再**从内到外**折叠为嵌套链（`while (i >= 0)` 从队尾往队头包，`:51-53`），链尾接内核 `kernel::run`；
- 执行时 `chain.proceed(ctx)` 从最外层进入——外层节点的 `invoke` 先执行（下行段），调 `next.proceed` 后进入内层，返回后执行外层的上行段。**上行执行序 = order 降序**（嵌套天然如此）。

与 Advisor 链（int、`HIGHEST_PRECEDENCE + N` 约 -21.47 亿，[advisors.md](advisors.md) §2.1）是**两套互不相干的坐标系**：这里是朴素正数 float，15/40/870/950 都是「裸值」。

### 2.3 完整节点 order 占用表（worker 内置 26 + 插件 5，共 31）

worker 内置节点分两处注册：RPC 线程段 6 个在 `TaskManager.registerLifecycleNodes`（`every-agent-worker/src/main/java/dev/everyagent/worker/task/TaskManager.java:174-187`，需要 TaskManager 内部 Map/Counter 所以不进 `BuiltInTaskLifecycleNodes`）；其余 20 个在 `BuiltInTaskLifecycleNodes.registerAll`（`every-agent-worker/src/main/java/dev/everyagent/worker/task/lifecycle/BuiltInTaskLifecycleNodes.java:68-96`），全部以 `pluginId="worker"` 注册（不受临界段限制）。「段型」栏：普通 = 走 `invoke` 语义；Section = `SectionNode`/`UpstreamNode` 子类（§2.5）。

| order | id | 段型 | 职责 | 来源 | 证据（文件:行号） |
|---|---|---|---|---|---|
| 10 | `idempotency.check` | 普通 | 幂等键去重（10 分钟窗口） | worker | `TaskManager.java:176`；`IdempotencyCheckNode.java:33` |
| **15** | `queue.dispatch` | 普通 | 运行中任务收新输入→入队短路 | **task-input-queue** | `QueueDispatchNode.java:46` |
| 20 | `workspace.resolve` | 普通 | 工作区参数校验/落定 | worker | `TaskManager.java:178`；`WorkspaceResolveNode.java:31` |
| 30 | `taskid.generate` | 普通 | 新建任务生成 taskId | worker | `TaskManager.java:180`；`TaskIdGenerateNode.java:40` |
| **40** | `queue.admission` | 普通 | Semaphore 排队（⚠️ 注释写 250，§1.4） | **task-queue** | `QueueAdmissionNode.java:31` |
| 50 | `taskentry.create` | 普通 | TaskEntry 构造 + **准入检查消费点**（§1.2） | worker | `TaskManager.java:182`；`TaskEntryCreateNode.java:73` |
| 55 | `rerun.restore` | 普通 | 再运行认领后的 meta 恢复 | worker | `RerunRestoreNode.java:22` |
| 70 | `response.ack` | 普通 | RPC 应答（RPC 线程段收尾） | worker | `TaskManager.java:185`；`ResponseAckNode.java:30` |
| 80 | `thread.submit` | 普通 | 切换到虚拟线程执行后续节点 | worker | `TaskManager.java:187`；`ThreadSubmitNode.java:67` |
| 100 | `persistence.track` | 普通 | 挂流源/登记落盘 | worker | `PersistenceTrackNode.java:34` |
| 150 | `ledger.track` | 普通 | agent 台账登记 | worker | `AgentLedgerTrackNode.java:31` |
| 200 | `task.wires` | 普通 | 接线（usage 广播钩子等） | worker | `TaskWiresNode.java:30` |
| 310 | `model.switch.trace` | 普通 | 模型切换 trace | worker | `ModelSwitchTraceNode.java:30` |
| 340 | `ledger.untrack` | Upstream | 台账注销 | worker | `AgentLedgerUntrackNode.java:29` |
| 350 | `workspace.activity` | Upstream | 工作区活跃度收口 | worker | `WorkspaceActivityNode.java:26` |
| 390 | `main.agent` | 普通 | 主 agent 装配/首轮输入 | worker | `MainAgentNode.java:19` |
| **420** | `registry.remove` | **临界段** | 任务移出内存注册表 | worker | `RegistryRemoveNode.java:20` |
| **450** | `gate.evict` | **临界段** | 授权门清理 | worker | `GateEvictNode.java:26` |
| **500** | `persistence.untrack` | **临界段** | 摘流源/注销落盘 | worker | `PersistenceUntrackNode.java:29` |
| **550** | `disk.index` | **临界段** | 磁盘索引更新 | worker | `DiskIndexNode.java:26` |
| **650** | `status.persist` | **临界段** | 终态落盘 meta | worker | `StatusPersistNode.java:26` |
| **750** | `log.flush` | **临界段** | 事件日志 flush/关 writer | worker | `LogFlushNode.java:26` |
| **800** | `concurrency.release` | **临界段** | 并发计数扣减 | worker | `ConcurrencyReleaseNode.java:19` |
| **840** | `status` | **临界段**（成对：down 在段外） | 终态事件广播 | worker | `StatusNode.java:36` |
| 860 | `ledger.persist` | Upstream | 台账落盘 agents.json | worker | `AgentLedgerPersistNode.java:28` |
| **870** | `queue.loop` | 普通 | 轮次循环：重复 next.proceed 续跑队列项 | **task-input-queue** | `QueueLoopNode.java:54` |
| 875 | `file.reference.process` | 普通 | `@` 文件引用预处理（每轮） | worker | `FileReferenceProcessNode.java:71-72` |
| **877** | `edit.resend` | 普通 | 编辑重发截断（每轮） | **task-edit-resend** | `EditResendNode.java:32` |
| 880 | `consume.input` | 普通 | 消费当前轮输入（每轮） | worker | `ConsumeInputNode.java:24` |
| 900 | `cascade.stop` | Upstream | 逐轮失败级联停 | worker | `CascadeStopNode.java:33` |
| **950** | `spawned.await` | 普通 | 逐轮等子 agent 全部终态（轮收口前） | **subagent** | `SubAgentSpawnedAwaitNode.java:29` |

⚠️ 与架构文档的差异：[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.14.1 写「内置节点（17 个）」且列出 `status.start(300)`/`queue.persist(700)` 等节点——代码中已不存在（现无 300/700 节点，台账类节点是后来收编的）；本表以源码 order() 实测为准。轮次循环段（870~950，临界段内侧）每轮重入、每轮把 `queue.loop` 的 next 链完整跑一遍；任务级收口节点全部在循环外侧只执行一次（`QueueLoopNode.java:10-19` 注释详述）。

### 2.4 洋葱 order 数轴（ASCII）

```
 外层（先见下行 / 后见上行）                                        内层（紧贴内核 runner.run）
 10 ─15─ 20 ─ 30 ─ 40 ─ 50 ─55─ 70 ─ 80 ── 100 ── 150 ── 200 ── 310 ─ 340 ─ 350 ─ 390 ┄┄ [420⋯840] ┄┄ 860 ─ 870 ─ 875 ─ 877 ─ 880 ─ 900 ─ 950 → 内核
  │   │       │    │    │        │                │                │   ╔═══════════╗    │    │         │         │    │         │
  │   │       │    │    │        │                │                │   ║ 临界段     ║    │  轮次循环段（每轮重入）   │
 幂等 队列   工作区  id   队列    准入           落盘track        接线            主agent  ║ 840→420   ║ 台账  队列   文件引用  编辑   消费   级联   等子agent
 检查  dispatch     生成  排队   entry创建      150/200/310/340/350            ║ 共享一把锁 ║ persist loop  875      877   880    停    950
      (插件)              (插件)  (rerun 55 在此)                              ╚═══════════╝   (插件)        (插件)     (插件)
      task-input-queue      task-queue                                            ↑插件禁区[420,850]↑
```

读法：括号内为插件节点；`[420⋯840]` 是临界段（§2.5），插件 order 落入 **闭区间 [420,850]** 会被 WARN 拒绝注册；其余空档可自由取 float 值（如 `45.5f`、`1000f`）。

### 2.5 临界段 [420,850] 专节

**定义位置**（两处同名常量，逐字一致）：

- `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/TaskLifecycleRegistry.java:31-32`：`CRITICAL_SECTION_MIN = 420f` / `CRITICAL_SECTION_MAX = 850f`，类注释「850..420 区间不允许插件节点插入（§7.3 锁策略），注册时检测并打 WARN 拒绝」；
- `every-agent-worker/src/main/java/dev/everyagent/worker/task/lifecycle/TaskLifecycleExecutor.java:35-36`：执行器侧同值常量，判定 `isInCriticalSection = node instanceof SectionNode && order ∈ [420,850]`（`:140-143`）。

**WARN 拒绝的原文文案**（`TaskLifecycleRegistry.java:46-48` 逐字抄，格式串）：

```java
log.warn("插件 {} 尝试注册生命周期节点 {} 的 order={} 落入临界段 [420,850]，已拒绝",
        pluginId, node.id(), node.order());
```

渲染形态：`插件 <pluginId> 尝试注册生命周期节点 <nodeId> 的 order=<order> 落入临界段 [420,850]，已拒绝`，随后 `return`（不 add，注册失败但不炸启动）。豁免条件：`pluginId` 为 null/空或 `"worker"`（内置节点不受限，判定条件 `TaskLifecycleRegistry.java:42-45`）。

**为什么有这段（临界区不容插队）**：执行器组装时把**连续且 order ∈ [420,850] 的 `SectionNode` 序列折叠成一个链位**——段内节点的下行段 `down(ctx)` 在段边界外按 order 升序**无锁**先执行，随后进入内层链；内层返回后**包一次** `synchronized(ctx.taskLock())`，段内上行段 `up(ctx, result)` 按 order 降序直接调用（`TaskLifecycleExecutor.java:67-99`；`SectionNode.java:9-15` 类注释）。任务收尾的全部一次性动作（移出注册表→摘流源→flush 日志→落终态→扣并发）必须在**同一把锁内连续完成**——若有外来节点插进这段，连续段会被切成两截、中间插入一次外来 `invoke`，锁内序列被打断（「未进入不收口」的收口动作半途插入外来副作用）。因此注册期直接拒绝插件节点落入该闭区间（含端点 420/850，判定用 `>=`/`<=`）。[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.14.1 写「order ∈ [420,850] 的连续 UpstreamNode 段共享一次 synchronized(taskLock)，对外表现为 order=850 的单一链位置」（850 为旧编号口径，现段内最高是 status=840，机制描述仍准确）。

**插件可选的安全区段**（闭区间 (420,850) 之外任意 float，含负数；现有 5 个插件节点分别占用 15/40/870/877/950）：

| 你想做什么 | 推荐 order | 依据/范例 |
|---|---|---|
| 拦截/改写任务创建输入（RPC 线程段） | `11~14`、`16~19`、`21~29`、`31~39`、`41~49`、`51~54`、`56~69`、`71~79` | `queue.dispatch(15)` 的教训：**必须在 taskid.generate(30) 之前**，否则新建任务被误判为「运行中收新输入」而入队短路、永远不运行（`QueueDispatchNode.java:13-17` 注释记录的事故） |
| 运行前排队/资源闸门 | `81~99`、`101~149`、`151~199`、`201~309`、`311~339`、`341~349`、`351~389`、`391~419` | `queue.admission(40)` 形态三 try/finally 成对节点 |
| 每轮预处理（轮次循环段） | `851~859`、`861~869`、`871~874`、`876`、`878~879`、`881~899` | **轮次循环节点必须 >850（临界段内侧）**：`queue.loop` 曾落在 395，每轮上行把一次性收口节点逐轮执行——任务中途被移出注册表、writer 提前关闭、并发计数重复扣减（`QueueLoopNode.java:11-19` 注释记录的事故根因） |
| 收口前等待/审计 | `901~949`、`951+` | `spawned.await(950)` |

### 2.6 非 register\* 通道：`services.addRoundClosedListener`

除了两个 task 域注册点，还有一条**不走 `ctx.register*` 的服务通道**：`WorkerServices.addRoundClosedListener(RoundClosedListener)`（声明于 `every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerServices.java:80`）。`RoundIndexStore` 持久化新闭合轮后回调全部监听器，传 `taskId + dataDir + List<RoundClosedInfo>`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/RoundClosedListener.java:9-25`）——解决「roundId 在轮闭合时才生成、晚于 advisor 的 doOnComplete」的时序问题，插件据此写按轮分片的数据文件。现役唯一使用者 file-change（`every-agent-plugins/file-change/src/main/java/dev/everyagent/plugin/filechange/FileChangePlugin.java:33`），配合其自注册 RPC `task.fileChanges` 读回（§3.6 同款「自持数据 + 自注册 RPC」姿势，详见 [持久化与状态](persistence-and-state.md)）。

## 3. registerRpcMethod —— 自注册 RPC

### 3.1 方法签名与 RpcContext

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerPluginContext.java:72-79
/**
 * 注册 RPC 方法（经 RpcDispatcher 分发）。
 * @param method 方法名（域.动作，如 "git.status"）
 * @param handler 处理器
 */
void registerRpcMethod(String method, RpcMethod handler);
```

`RpcMethod` 是 `@FunctionalInterface void handle(RpcContext ctx)`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/rpc/RpcMethod.java:10-19`）；`RpcContext`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/rpc/RpcContext.java:11-45`）提供：

| 方法 | 语义 |
|---|---|
| `String strParam(String name)` | 必填字符串，缺失/空抛 `BadParamsException`（分发器译为 `BAD_PARAMS`） |
| `String optStrParam(String name, String def)` / `long optLongParam(String name, long def)` | 可选参数带默认值 |
| `JsonNode params()` | 原始参数（复杂结构自行解析） |
| `void ok(JsonNode result)` | 成功应答（**同一 reqId 至多一次**） |
| `void err(String code, String message)` | 错误应答（同上） |

### 3.2 worker 侧分发链路

```
插件 activate(ctx)
  └─ ctx.registerRpcMethod("git.status", gitService::status)
     → WorkerPluginContextImpl.registerRpcMethod        WorkerPluginContextImpl.java:187-189
       → rpcDispatcher.register(method, ctx -> handler.handle(ctx))
          → methods.put(method, handler)                 RpcDispatcher.java:58-60（单张 ConcurrentHashMap，字段 :39）
请求到达（每条 hub 连接各订阅自己的 cmd 频道，连接/重连时重放）
  worker sub u.<K>.worker.<id>.cmd                       RpcDispatcher.java:49-56（init）；Channels.java:16-18
  onHubMessage：只认自己 cmd 频道上的 "rpc" 事件          RpcDispatcher.java:67-70（频道名不相等直接 return）
  → 查方法表：未命中 err UNKNOWN_METHOD                   RpcDispatcher.java:84-86
  → 命中：每请求一个虚拟线程执行 handler.handle(ctx)       RpcDispatcher.java:90-111（vt.submit）
    异常翻译：BadParams→BAD_PARAMS / NotFound→NOT_FOUND /
    AuthRequired→AUTH_REQUIRED / 其他→INTERNAL（记 error 日志）
  → rpc.cancel{reqId}：Future.cancel(true) 打断长请求      RpcDispatcher.java:116-130
应答回请求来源连接的 evt 频道 u.<K>.worker.<id>.evt，reqId 供请求方匹配   RpcDispatcher.java:132-136；Channels.java:20-22
```

**方法表是插件与 worker 内置共用的一张表**（`RpcDispatcher.java:39`）：`tasks.list`、`task.run`、`task.poll`（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.13 的统一读取 RPC）等内置方法与插件的 `git.status` 完全平权；`put` 语义意味着**同名方法后注册者覆盖先注册者**——worker 内置注册与插件激活的先后由 Spring 启动序决定（未实测具体先后），插件应避开内置方法名（§3.3）。

### 3.3 方法名命名空间现状（19 个插件方法归纳）

内置插件注册的全部 RPC 方法（rg 复核，共 19 个）：

| 前缀 | 方法 | 注册方 |
|---|---|---|
| `git.` | `git.status` `git.log` `git.diff` `git.show` `git.commit` `git.pull` `git.push` `git.discard` `git.init` `git.clone` `git.remote.add` `git.remote.list` `git.credential.save`（13 个） | git（`GitRpcMethods.java:4-16`，常量类；注册 `GitPlugin.java:42-54`） |
| `task.` | `task.queueList` | task-queue（`TaskQueuePlugin.java:29`） |
| `task.` | `task.queueRemove` `task.queueMove` `task.queueSnapshot` | task-input-queue（`TaskInputQueuePlugin.java:33-35`） |
| `task.` | `task.agents` | subagent（`SubAgentPlugin.java:35`） |
| `task.` | `task.fileChanges` | file-change（`FileChangePlugin.java:35`） |

规律：全部是 `域.动作` 或 `域.对象.动作` 形态（worker 内置同款：`tasks.list`、`fs.read`、`slash.list`），与 §5.5 扩展规则一致——**协议固定交互形状，`method` 只是字符串命名空间；新功能 = 注册新 method，零改协议、零改 hub；method 只加不改，breaking 用新名，老客户端靠 `sys.methods` 发现能力**（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §5.5）。⚠️ `task.*` 前缀已被 worker 内置（`task.run`/`task.poll`/`task.rounds`…）和多个插件共用，没有按插件 id 隔离的强制规则——新插件建议用自己独有的域前缀（如 `myplugin.action`）避免撞名；方法名常量住插件侧、task 核心不感知（§14.11 对 `task.agents`/`task.fileChanges` 的既有约定）。

### 3.4 ACL 与频道边界（插件 RPC 的暴露面，结论）

- **走什么频道**：插件 RPC 与内置 RPC 同路——前端把 `rpc` 事件发到目标 worker 的 cmd 频道 `u.<ownerKey>.worker.<workerId>.cmd`（`Channels.workerCmd`，`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/event/Channels.java:16-18`；worker 每条 hub 连接常订阅，`RpcDispatcher.java:49-56`）；应答回 evt 频道。
- **hub 侧 ACL 怎么作用**：频道名本身即 ACL——频道必须落在连接自己的 `u.<ownerKey>.` 前缀内（字符集 `[a-z0-9._-]`、长度 ≤160），hub 对每个 sub/pub 强制校验，越命名空间返回 `ACL_DENIED`；前缀校验在连接级完成，A 的连接物理上无法订阅/发布 `u.B.**`（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §4.3、§14.2）。
- **插件 RPC 受不受 ACL 保护**：受上述**命名空间级**保护（跨 owner 不可达），但**同命名空间内互信**——持有同一 apiKey 的任何连接可调该命名空间内任意频道，因此插件 RPC 对同 owner 的全部前端连接可调；worker 侧 `RpcDispatcher` 只按「频道是否等于自己的 cmd 频道」过滤（`RpcDispatcher.java:67-70`），**没有额外的统一鉴权层**。
- **越界防护是方法自己的责任**：worker 约定 `fs.*`/`git.*`/`task.run`（新建）**必带 `workspace` 参数、jailed 到工作区根**（先 realpath 再校验前缀；[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §5.5 与根 AGENTS.md「频道即鉴权边界」条目同口径）。插件方法要自己校验：git 插件每个方法第一行 `workspaces.sandboxFor(ctx.strParam("workspace"))`（`GitService.java:649-651`），路径再经沙箱 `resolveLoose`/`resolveExisting` 防穿越（§3.6 范例）；`fs.*` 系另叠加 §7.8 `PermissionGate` 授权链（工作区外访问弹 `kind=authorization` ask）。**自注册的插件 RPC 若不校验参数，就是同 owner 全前端裸暴露**——写 RPC 时把「必带哪个 scope 参数、路径怎么 jail」当作第一设计项。
- **前端怎么定向到某台 worker**：`ctx.sdk.rpc(workerId, method, params)`，workerId 来自工作区归属反查（§14.12 worker 归属是 wire 事实，禁止按 ownerKey 猜测）。

### 3.5 前端调用方式

插件 web 侧经 `ctx.sdk.rpc(workerId, method, params?)` 调用（`every-agent-plugin-api/js/index.ts:130-132`，返回 `Promise<unknown>`；签名与 Disposable 语义详见 [前端上下文 API](../web/context-api.md)）。宿主前端自身也用同一张方法表（`sys.methods` 能力发现，`RpcDispatcher.java:55`）。

### 3.6 范例：git.status 三点（注册 + 实现 + 前端调用）

```java
// ① 注册：every-agent-plugins/git/src/main/java/dev/everyagent/plugin/git/GitPlugin.java:42
ctx.registerRpcMethod(GitRpcMethods.GIT_STATUS, gitService::status);   // 常量 "git.status"

// ② 实现：every-agent-plugins/git/src/main/java/dev/everyagent/plugin/git/GitService.java:47-62
void status(RpcContext ctx) throws IOException {
    WorkspaceSandbox sb = sandbox(ctx);            // :649-651 → workspaces.sandboxFor(ctx.strParam("workspace"))
    StatusData s = statusData(sb);                 // 必带 workspace 参数，jailed 到工作区根（§3.4）
    ObjectNode o = Json.obj();
    o.put("branch", branch(sb));
    o.set("added", arr(s.added()));
    // … changed/modified/removed/missing/untracked/conflicting/ahead/behind 同构组装
    ctx.ok(o);
}

// ③ 前端调用：every-agent-plugins/git/web/gitGateway.ts:87-91,113
function rpcForWorkspace(workspace: string, method: string, params: Record<string, unknown>): Promise<unknown> {
    // …由工作区注册表反查 workerId；归属缺失（未注册/离线）直接抛错
    return sdk.rpc(workerId, method, params)
}
// …
return (await rpcForWorkspace(workspace, 'git.status', { workspace })) as never
```

最小范例（java-only 全功能样本）：task-queue 整插件只有一个 `task.queueList` RPC（`TaskQueuePlugin.java:29`），与 task 域注册点共用一个 `activate()`，可直接当模板抄。

## 4. registerSlashProvider + registerSlashTokenResolver —— `/` 菜单扩展

### 4.1 接口签名

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/slash/SlashProvider.java:11-17
@FunctionalInterface
public interface SlashProvider {
    /** 加载该来源的全部条目。 */
    List<SlashCommandItem> load();
}

// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/slash/SlashTokenResolver.java:15-27
public interface SlashTokenResolver {
    /** 固定 kind（与构造 opaque token 时的 kind 一致）。 */
    String kind();
    /** 把 payload 解析为提交给 AI 的替换文本。返回空串表示「清空该 token」（从模型上下文剥离）。 */
    String resolveSubmissionText(JsonNode payload);
}
```

`SlashCommandItem` 是 record：`id`（全局唯一，React key 与跨 provider 去重）/`title`（必填）/`subtitle`/`icon`（内联 SVG）/`group`（分组标题）/`insertText`（opaque token 串或纯文本）/`selectHandler`/`cancelHandler`（可空，空则用默认实现：insertText 原地 INLINE 插入）/`defaultSelected`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/slash/SlashCommandItem.java:33-42,102-109`）。

### 4.2 `/` 菜单怎么进来

```
插件 activate: ctx.registerSlashProvider("unattended", provider)
  → WorkerPluginContextImpl.registerSlashProvider        WorkerPluginContextImpl.java:193-203
    → SlashCommandRegistry.registerProvider(id, loader)   SlashCommandRegistry.java:31-33（Map<String,SlashProvider>）
worker 内置同路注册：SkillSlashProvider 构造器里 registry.registerProvider("skill", …)   SkillSlashProvider.java:43-48
前端打开 `/` 菜单 → slash.list RPC → 聚合 SlashCommandRegistry 全部 provider 的 load()   SlashMethods.java:85-（slash.list）
用户选中 → slash.select {id} → 反查条目触发 selectHandler → 返回 inline/bottom 结果      SlashMethods.java:109-112
取消胶囊 → slash.cancel；任务级 bottom token → slash.taskTokens.apply                    SlashMethods.java:159-162
提交后 opaque 串原样到达后端 → SlashTokenResolveAdvisor(HP+150) 按 kind 找 resolver
  把 token 替换为 resolveSubmissionText 的文本再进模型                                    advisors.md §2.2 #7
```

**SkillSlashProvider 与 ExternalSkillScanner 的关系（三路合并）**：`/` 菜单的 skill 候选来自「内置 `BuiltInSkills.getAllSkills()` → 插件 `SkillContributorRegistry` → `ExternalSkillScanner.scan()`」三路合并（`SkillSlashProvider.java`），同 id 去重、优先级依次降低；插件 SPI 条目副标题带「插件 · 」来源前缀，选中执行路径（`system.skill` opaque token）与内置完全一致。`registerSkillContributor` 通道现在**同时**进 system prompt（`SkillAdvisor.mergedSkills`，[advisors.md](advisors.md) §5）与 `/` 菜单——两条通道已等价（known-issues #11 修复前 SPI 只进 prompt 不进菜单）；物化文件到 skillsDir 仍是第三条合法通道（优先级最低，subagent 先例）。

### 4.3 范例（index 表 4 处注册）

| 插件 | registerSlashProvider | 条目 | resolver |
|---|---|---|---|
| unattended | `("unattended", () -> UnattendedSlashProvider.items(ctx.services()))`（`UnattendedPlugin.java:26-27`） | id=`unattended:on`「无人值守」，分组「授权」；selectHandler 写 `metadata["unattended"]=true` 并 `publishUpdated`，返回 bottom 胶囊（`UnattendedSlashProvider.java:44-69`） | `UnattendedSlashResolver`：kind=`unattended.mode`，`resolveSubmissionText` 返回**空串**——纯触发标记不注入模型（`UnattendedSlashResolver.java:20-24`） |
| ai-review | `("ai-review", …)`（`AiReviewPlugin.java:27-28`） | id=`ai-review:on`「AI 审议」 | `AiReviewSlashResolver`（`:31`） |
| git | `("git-auto-sync", GitAutoSyncSlashProvider::items)`（`GitPlugin.java:36`） | id=`git-auto-sync:on`「自动同步」 | `GitAutoSyncSlashResolver`（`:39`） |
| sandbox-wsl-ubuntu | `("network", …)`（`WslUbuntuSandboxPlugin.java:44`） | 「禁用网络」 | `NetworkSlashResolver`（`:45`） |

## 5. 事件发射红线（§14.0）

任务域插件最容易踩的线：**插件只发语义 `EmitEvent`，不感知 wire 事件名/seq**。[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §14.0（事件管道分层）原文：

> **插件永远不感知 task 层语义(wire 事件名、traceId、seq 等)。**

分层职责（§14.0）：插件/底层只发 `EventEmitter.emit(EmitEvent.…)`（只知道事件名 + payload + persist 标志）；agent 层填 agentId；task 层做语义→wire 映射（分配 seq、按 persist 落盘）；推送层背压定向；前端按 wire 事件名渲染。

`services()` 里可用的发射方法（签名照抄，`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerServices.java`）：

```java
EventEmitter emitterOf(String subjectId);   // :72 运行中任务返回有效 emitter；终态返回 null（调用方应跳过 emit）
StreamEmitter stream();                     // :50 事件扇出口（向 hub 连接广播，如 task.updated 状态广播）
```

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/model/EventEmitter.java:12-19
public interface EventEmitter {
    long emit(EmitEvent event);   // 返回 seq（EventLog 分配的 Snowflake ID），供产生方关联后续事件
}
```

`EmitEvent`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/model/EmitEvent.java:21-31`）是 record：`id`（产生方管理，新 id=新内容、同 id=原地更新）/`kind`/`agentId`（低层留 null，task 层兜底 mainAgentId）/`title`/`summary`/`content`/`status`/`data`/`persist`（落盘 vs 瞬态）/`mode`（APPEND/REPLACE），便捷工厂 `EmitEvent.of(...)`（持久）与 `EmitEvent.transientOf(...)`（瞬态）。现役范例：model-rate-limit 排队时发瞬态通知 `a.emitter().emit(EmitEvent.transientOf(noticeId[0], "system.notice", null, null, null, "模型「…」正在排队(在飞 N / 排队 M)", "waiting", null, EmitEvent.Mode.REPLACE))`（`every-agent-plugins/model-rate-limit/src/main/java/dev/everyagent/plugin/modelratelimit/RateLimitAdvisor.java:82-86`）。洋葱节点内则直接用 ExecContext 槽位 `ctx.emitter()`（早期 RPC 阶段为 null，先判空，§2.1）。

另注意 §14.11：**`agent.started`/`agent.status`/`agent.done`/agent 级 `error` 四个事件由 `AgentEntity` 单点发射，插件不得手搓**（advisors 层面已展开，[advisors.md](advisors.md) §8）；插件唯一允许的调用是兜底 `Agent.claimTerminal(...)`。

## 下一步读

- 插件私有数据落盘与「自持数据 + 自注册 RPC」完整姿势（file-change 的 `RoundClosedListener` + `task.fileChanges` 就是本篇两条通道的合体）：[persistence-and-state.md](persistence-and-state.md)
- 前端侧 `ctx.sdk.rpc` / `ctx.events` / Disposable 语义：[前端上下文 API](../web/context-api.md)
- 插件单测怎么只依赖 plugin-api 写桩（不引 worker 依赖，§14.9）：[调试与测试](../guides/debugging-and-testing.md)
