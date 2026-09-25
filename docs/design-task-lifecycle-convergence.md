# 设计方案：任务生命周期节点收敛（对称合并 + 再运行恢复入链）

> 状态：**草稿，待评审** · v1
> 前置文档：[design-agent-layer-onion.md](design-agent-layer-onion.md)（洋葱模型底座，已实施）。本文是其节点基线表（§3.3）与机制规格（§7.3）的后继修订，不改动底座契约（`TaskLifecycleNode`/`TaskChain`/`TaskOutcome`/`TaskKernel` 不变）。
> 动机：Phase 1 的 16 内置节点是「行为零变化」约束下对旧 `finish()`/`runTask`/`rpcTaskRun` 的逐字映射产物——为保上行顺序逐项一致而刻意拆散。底座稳定后，按「**一节点一职责，对称动作收一个节点**」原则收敛。

---

## 1. 收敛原则与合并判据

filter 模型（`result = next(ctx)`，next 前 = 下行，next 后 = 上行）天然支持成对节点（设计文档 §3.1 形态三）。但**一个节点的下行位与上行位被同一个 order 锁定**（上行执行序 = 进入序逆序），由此得出合并判据：

> **一对对称动作能收进一个节点，当且仅当存在单一 order，使下行动作与上行动作的时序约束同时成立。**

按此判据逐项检验现状全部对称对，结论分三类：可合并（§2）、不可合并（§3）、新增入链（§4）。

## 2. 可合并的对称对

### 2.1 `status`（合并 status.start(300) + status.finalize(850) → order=840）

| | 内容 |
|---|---|
| 下行段 | `startedAt` + `setStatus(RUNNING)` + task.updated 广播 + `agentStatus("running")`（现 StatusStartNode 方法体原样） |
| 上行段 | 终态 CAS（幂等门）+ `endedAt/error/status` + agentStatus 终态事件 + 终态广播（现 StatusFinalizeNode 方法体原样） |

- **位置推导**：上行段必须在临界段头部（finalize→remove 原子段的第一环，§3.3 锁策略）→ order 只能是 ~840；下行段随之落在 main.agent(390) 之后、内核之前。
- **行为差异（需评审接受）**：`agentStatus("running")` 事件与 RUNNING 广播从「user.message 之前」变为「user.message/round.opened 之后」。语义上更自洽（先收到用户输入，agent 才进入 running），但属事件顺序变化，需冒烟验证前端无依赖。
- 选 840 而非 850：上行序 = order 降序，840 仍是临界段第一段（在 800 之前）；同时给未来「需在 finalize 前执行的段内节点」留出 841..850 空隙。

### 2.2 `queue`（合并 新增下行「悬空队列恢复」+ queue.persist(700) → order=700）

| | 内容 |
|---|---|
| 下行段 | `store.readQueue(dir)` → 逐条 `inputQueue.offer`（现 startRerun 悬空队列恢复段原样搬入） |
| 上行段 | 悬空队列落盘 queue.jsonl / 空则删除（现 QueuePersistNode 方法体原样） |

- **新建任务空转天然成立**：track(100) 已建目录，`readQueue` 对新目录返回空列表——无需「是否再运行」分支，两条路径同一节点。
- **位置**：下行 700 在 main.agent(390)（首条输入 consumeInput）之后、内核首次 poll 之前，满足现状「offer 必须先于 runTask 线程 poll」的约束（同线程内顺序保证，比现状「提交前 offer」更宽松也更清晰）；上行原位不变。
- 这是教科书级成对节点：同一资源（inputQueue ↔ queue.jsonl）的载入/落盘收在一处。

## 3. 不可合并的对称对（判据检验不通过，保持现状）

| 对 | 下行约束 | 上行约束 | 结论 |
|---|---|---|---|
| `persistence`（track 100 / untrack 500） | 必须在一切事件落盘**之前**（否则 user.message 等早期事件无 jsonl sink 挂点） | 必须在临界段内、registry.remove(420) **之前**——否则再运行认领窗口内 startRerun 重新 track，与未关闭的旧 writer 竞态（onion 文档 §3.3 锁策略论证） | 合并放 100 → untrack 脱出临界段，重开竞态；放 500 → track 太晚，早期事件丢失。**保持两个节点** |
| `concurrency`（acquire / release 800） | acquire 与并发上限检查在 RPC 线程做 TOCTOU，需同步应答 ERR_BUSY | 临界段内 | 默认路径不可动。**队列插件接管准入后**（QueueAdmissionNode(250) 已是形态三成对节点）由插件语义自然覆盖，核心不动 |

临界段内其余纯上行节点（release 800 / flush 750 / persist 650 / disk.index 550 / untrack 500 / gate.evict 450 / registry.remove 420）**不再互合并**：它们的相对顺序是落盘正确性本身（如 status.persist 必须在 log.flush 之后，meta 水位才正确），一个节点的上行段是连续代码块，合并即吞并中间节点或改变顺序。一动作一节点是临界段单次持锁机制的自然粒度，不是过度拆分。

## 4. 新增入链节点（startRerun/rpcTaskRun 准备段收敛）

现状 `startRerun` 约 90 行准备逻辑手工堆叠在提交执行器之前，`rpcTaskRun` 尾部另有一份 slash 回调——这是 onion 文档 §8 风险表点名的「再运行路径绕过洋葱」残留。按「一节点一职责、不要太分散」收敛为 **3 个新节点**：

| order | 节点 id | 形态 | 职责 | 现状出处 |
|---|---|---|---|---|
| 50 | `rerun.restore` | 纯下行 | **再运行状态恢复（一事）**：meta→TaskEntry 全量恢复——createdAt / taskFlags（含 aiReview、unattended 旧字段兼容迁移）/ networkBlocked / powershellEnabled / seedUsageMeta / slashTaskTokens 回读 + `log.seed(seqLastOf)` 水位续号。新建任务无 rerunMeta，整体空转 | startRerun 中段 |
| 90 | `slash.notify` | 纯下行 | **slash 建后回调（一事）**：`slashCallbacks.notifySlashCallbacks(t, taskId)`，新建/再运行两条路径统一至此 | rpcTaskRun 尾部 + startRerun 尾部（两处重复，收敛为一份） |
| 310 | `model.switch.trace` | 纯下行 | **模型切换标注（一事）**：overrideConfigId 与 meta.configId 不一致时发射 `modelSwitch` 事件（仅再运行携带 override 时生效） | startRerun 尾部 |

位置约束论证：
- `rerun.restore` 在 100 之前：token/flags 必须在 track 首落盘 meta 前完成（现状注释明确要求首落盘含 slashTaskTokens）；log.seed 必须在事件发射前。
- `slash.notify` 在 90：现状两条路径的 notify 均发生在 track 之前（rpcTaskRun 在 submit 前、startRerun 在 submit 前，而 track 在任务线程内），节点化后时序不变；执行线程从 RPC 线程移到任务线程（与 Phase 1 track/wire 的迁移同档，回调内 `runningTask()` 取实体不受影响——TaskEntry 已入表）。
- `model.switch.trace` 在 310：事件发射在 track(100) 之后，保证落盘时序确定（现状在 track 前发射，依赖 sink 追平回放，属隐性假设；入链后变为显式保证）。

### 4.1 明确不入链的剩余准备动作

| 动作 | 去处 | 理由 |
|---|---|---|
| 会话磁盘重建（ConversationLoader.load → priorConversation） | 留在 ctx 装配（startRerun 传入，TaskLifecycleContextFactory 持有） | 它是**内核入参**（buildMainAgent 的构造参数），无对称上行；且 ConversationLoader 属 task 域读路径。链节点管生命周期动作，不管内核入参装配 |
| TaskEntry 构造 / workspace 解析 / config 解析 | 留在 TaskBootstrap / RPC 边缘 | 发生在 ctx 存在之前，物理上不可入链 |
| 幂等键去重 / 并发上限 / TASK_CREATED 广播 / ctx.ok | 留在 RPC 边缘 | 「创建」而非「开始」，需同步应答（onion 文档 §3.3 已定性） |
| `tasks.putIfAbsent` 热表注册 | 留在认领路径 | 与 diskTasks.remove 原子认领构成互斥纪律，抽离会把竞态窗口复杂化；无对应链位 |
| waiting-user ⇄ running 切换（askPendingChanged） | 留在 PendingAsks.StatusHook | 运行中途的事件驱动状态翻转，洋葱管「一次运行」的两端，管不到中途 |
| 编辑重发截断（truncateForEdit 热路径） | 留在 TaskInputHandler | 发生在运行中、不改生命周期 |
| 轮次级收口（git auto-sync 等） | 需要「轮次级链」扩展点，**不属于本设计** | 任务级洋葱不表达轮次语义，另起设计 |

## 5. 机制变更（§7.3 修订）

现状临界段识别 = `instanceof UpstreamNode && order ∈ [420,850]`（UpstreamNode 下行段为空）。status(840)、queue(700) 变为「下行非空 + 上行在段内」的成对节点后，识别规则失效。修订：

```java
/** 临界段节点基类：下行段在段边界外执行，上行段在段共享临界区内执行。 */
public abstract class SectionNode implements TaskLifecycleNode {
    @Override
    public final TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        down(ctx);                              // 下行段：段边界外、无锁
        return up(ctx, next.proceed(ctx));      // 上行段：执行器组装时收进共享临界区
    }
    /** 下行段（开始动作）。默认空。 */
    protected void down(TaskLifecycleContext ctx) throws Exception {}
    /** 上行段（收口动作）。不得抛异常（自吞记 WARN），可改写 result。 */
    protected abstract TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result);
}
```

- `UpstreamNode` 保留，改为 `SectionNode` 的特化（down 为空），存量 11 个纯上行节点零改动。
- 段识别改为「`instanceof SectionNode && order ∈ [420,850]`」。
- 执行器折叠规则修订：连续段节点的**下行段在段边界按 order 升序依次执行（无锁）**，随后进入内层链；内层返回后段共享一次 `synchronized(ctx.taskLock())`，上行段按 order 降序执行。对外语义与现状逐项一致（现状段内节点下行段本来就是空）。
- 注册表区间拒绝规则不变（插件节点禁入 [420,850]；subagent 的 ledger.persist(600) 沿用现状「worker」标识注册的既有特例，onion 文档 §7.3 已载）。

## 6. 收敛后基线表（内置 16 → 18 节点，净合并 2 对、新增 3 个、消解 2 处重复）

```
下行（order 升序）：
  rerun.restore(50) → slash.notify(90) → persistence.track(100)
  → task.wires(200) → model.switch.trace(310) → main.agent(390)
  → queue.down(700) → status.down(840)
内核：轮次循环（不变）
上行（order 降序）：
  spawned.await(950) → cascade.stop(900)
  → 【临界段，单次持锁】status.up(840) → concurrency.release(800)
    → log.flush(750) → queue.up(700) → status.persist(650)
    → disk.index(550) → persistence.untrack(500) → gate.evict(450)
    → registry.remove(420)
  → workspace.activity(350)
```

插件节点不受影响：task-queue 的 queue.admission(250)、subagent 的台账三节点（150/600/340）order 均不变。

**收敛后 startRerun/rpcTaskRun 形态**：认领/构造 TaskEntry → ctx 装配（含会话重建）→ 提交执行器。两路径同构，准备逻辑零手工堆叠。

## 7. 行为差异声明（本设计唯一允许的变化，需评审逐项确认）

| # | 变化 | 影响面 | 验证 |
|---|---|---|---|
| 1 | `agentStatus("running")` 事件与 RUNNING 广播移到 user.message/round.opened 之后（status 下行 300→840） | 事件流内相对顺序；tasks 频道广播晚数微秒 | 冒烟：新建任务事件序列；前端状态流转 |
| 2 | slash 建后回调执行线程：新建任务从 RPC 线程移到任务线程（track 前，时序不变） | 回调内逻辑均经 runningTask() 取实体，无影响 | slash token 建后回调回归 |
| 3 | 新建任务多一次 `readQueue` 空调用（目录已建、无文件，返回空） | 一次文件存在性检查 | 无 |
| 4 | model.switch.trace 事件发射点从 track 前移到 track 后（落盘时序由隐性变显式） | 仅再运行且切换模型时 | 再运行切模型冒烟 |

其余全部行为（事件内容、seq 语义、落盘字节、锁序、取消/停机路径）逐项不变。

## 8. 实施清单与验收

**修改**：
1. 本评审通过后，先修订 design-agent-layer-onion.md §3.3 基线表与 §7.3 机制规格（引用本文档），再改代码。
2. `task/lifecycle/`：新增 `SectionNode`；`UpstreamNode` 改继承 SectionNode；`TaskLifecycleExecutor` 段识别与折叠规则按 §5 修订；`StatusStartNode`+`StatusFinalizeNode` 合并为 `StatusNode`；`QueuePersistNode` 改 `QueueNode`（SectionNode，补下行恢复）；新增 `RerunRestoreNode`/`SlashNotifyNode`/`ModelSwitchTraceNode`；`BuiltInTaskLifecycleNodes` 重排。
3. `TaskLifecycleContextImpl`/Factory：新增 rerunMeta（再运行来源 meta，可空）与 slashCallbacks 注入。
4. `TaskManager`：startRerun 删除已入链动作（状态恢复/队列恢复/slash 回调/modelSwitch/active 计数保留）；rpcTaskRun 删除 slash 回调行。

**验收（DoD）**：
1. `mvn test` 全绿；TaskLifecycleExecutorTest 增补：SectionNode 下行在段边界外执行、段内单次持锁（可重入锁计数断言）、成对节点 try/finally 必达。
2. 冒烟四路径（新建/取消/再运行/停机）事件顺序对照现状——除 §7 声明的 4 项差异外逐项一致。
3. 再运行冒烟：悬空队列恢复、taskFlags/开关恢复、slash token 回调、模型切换 trace 各验证一次。

## 9. 风险

| 风险 | 对策 |
|---|---|
| status 下行后移引入前端时序依赖 | §7 差异表逐项冒烟；若前端确有依赖，回退方案 = status 拆回两节点（保留其余收敛） |
| SectionNode 段折叠改动引入锁序回归 | 段内上行序 = order 降序不变量断言进单测；850..420 区间插件禁入规则不变 |
| rerun.restore 遗漏 meta 字段 | 以 startRerun 现状代码为唯一事实源逐行映射，diff 审查 |
