# 设计方案：洋葱链贯穿 RPC 线程 + 统一入口 + 任务内队列插件化 v3

> 状态：**草稿，待评审** · v3
> 前置：onion 模型已落地（§11 收敛后基线）。v1/v2 已执行部分核心改造（内核改单轮、删除 InputQueue/DialogInsertAdvisor 等），本文为其修订方向。
> 一句话：**链从 RPC 线程启动，task.run 唯一入口，链返回值改 Object 支持短路，插件标记经 metadata 透传。**

---

## 1. 核心设计

### 1.1 链从 RPC 线程启动

现状洋葱链从虚拟线程启动（`runTask` → `lifecycleExecutor.run`），RPC 线程只做前置准备后 `vt.submit`。

新方案：**洋葱链从 `rpcTaskRun` 就启动**，覆盖 RPC 线程 + 虚拟线程全过程。RPC 线程阶段的前置动作（幂等检查、workspace 解析、taskId 生成、TaskEntry 构造、广播、ctx.ok）全部是链节点；虚拟线程阶段跑下行（track/wires/main.agent/status）→ 内核 → 上行收口。

### 1.2 task.run 唯一入口

废弃 `task.input`（fire-and-forget pub）和 `task.dialogInsert`（pub）。用户消息统一走 `task.run` RPC：
- **无 taskId** → 新建任务 → 走完整链
- **有 taskId 且运行中** → 链节点入队/插入 → 短路返回（不启动虚拟线程）→ `ctx.ok`
- **有 taskId 且终态** → 再运行认领 → 走完整链

### 1.3 链返回值改 Object

链返回值从 `TaskOutcome` 改为 `Object`，支持链节点短路返回中间状态：

```java
Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception;
```

- 正常流程：`next.proceed(ctx)` 返回 `TaskOutcome`（内核结局）
- 短路：不调 `next`，返回 `null` → 后续节点不执行、不启动虚拟线程

RPC 线程拿到链结果后只判断"有没有异常"，没异常就 `ctx.ok`。前端不关心返回值类型——任务状态变化全靠广播。

### 1.4 通用插件参数

task.run 参数新增 `metadata`（通用 `Map<String, Object>`）。核心不感知其语义，链节点（插件贡献）自行读取和消费：

```
task.run params: {
  input, rawContent, taskId, title, workspace, configId,
  idempotencyKey, taskTokens, editSeq,
  metadata: {                    // ← 新增通用容器
    insert: true,              // 插入对话标记（插件约定，核心不解释）
  }
}
```

- `editSeq`：编辑重发标记（核心已有，保留）
- `metadata.insert`：插入到当前对话标记（task-input-queue 插件消费）
- 队列入队不需要标记——有 taskId 且运行中即入队
- 核心只透传 `metadata`，不解释

---

## 2. 链节点基线

```
RPC 线程阶段（同步）:
  order 10  idempotency.check        幂等键去重
  order 20  workspace.resolve         workspace 解析 + 校验
  order 30  taskid.generate            taskId 生成（查重）
  order 40  admission.check           任务间队列（task-queue 插件：并发准入，超限→ERR_BUSY 短路）
  order 50  taskentry.create           TaskEntry 构造 + tasks.put + slashTaskTokens 写入
                                        新建分支：new TaskEntry + tasks.put
                                        再运行分支：diskTasks.remove 原子认领 + 构造新 TaskEntry + tasks.putIfAbsent
  order 55  rerun.restore             再运行 meta→TaskEntry 恢复（新建空转）
  order 60  slash.notify              slash 建后回调
  order 65  queue.dispatch            任务内队列（task-input-queue 插件）：
                                        ├── 有 taskId 且运行中 + metadata.insert → 移出队列→插入队列→ctx.ok→短路(null)
                                        ├── 有 taskId 且运行中 → 入队→ctx.ok→短路(null)
                                        └── 无 taskId 或终态 → 继续往下
  order 70  response.ack              TASK_CREATED 广播 + ctx.ok（同步返回前端）
  order 80  thread.submit              vt.submit（切换到虚拟线程，链后续部分在虚拟线程执行）

虚拟线程阶段（异步）:
  order 100 persistence.track         建目录 + 首写 meta + 挂 EventLog 监听
  order 200 task.wires                注入 onUsageBroadcast 钩子
  order 310 model.switch.trace        模型切换标注
  order 390 main.agent                buildMainAgent + consumeInput(首条输入)
  order 395 queue.loop               任务内队列（插件）：恢复悬空队列 + 注册 per-task 队列
                                        + 包裹内核循环（poll→consumeInput→再跑内核） + 落盘
  order 840 status                    下行 RUNNING / 上行终态 CAS
  --- 内核 ---
  order ∞   kernel                    agentService.run(main) 一次 → done
  --- 上行 ---
  order 950 spawned.await             等全部子 agent
  order 900 cascade.stop              级联停
  [临界段 420~850: status → release → flush → persist → disk.index → untrack → gate.evict → registry.remove]
  order 350 workspace.activity        工作区活动时间
```

### 2.1 链跨线程（thread.submit 节点）

order 80 `thread.submit` 节点是 RPC 线程→虚拟线程的分界：

```java
// ThreadSubmitNode (order=80)
public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
    // 此时在 RPC 线程
    // 提交虚拟线程执行链的剩余部分（100~上行~350 全部节点）
    Future<?> f = vt.submit(() -> {
        try {
            next.proceed(ctx);  // 在虚拟线程继续执行剩余链
        } catch (Throwable t) {
            // 执行器兜底已处理
        }
    });
    ((TaskLifecycleContextImpl) ctx).taskEntry().runFuture = f;
    return null;  // RPC 线程链到此结束，后续节点不执行
}
```

`thread.submit` 节点不调 `next.proceed`（那是虚拟线程的事），它把 `next`（后续链）打包提交到虚拟线程，自己返回 null。RPC 线程链到此结束。

### 2.2 短路返回（queue.dispatch 节点）

order 65 `queue.dispatch`（task-input-queue 插件贡献）短路：

```java
// QueueDispatchNode (order=65, RPC 线程阶段)
public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
    String taskId = ctx.taskId();
    if (taskId == null || taskId.isEmpty()) {
        return next.proceed(ctx);  // 新建任务，继续往下
    }
    TaskEntry t = taskManager.get(taskId);
    if (t != null && !t.status.terminal()) {
        // 运行中
        Map<String, Object> metadata = ctx.metadata();
        if (metadata != null && Boolean.TRUE.equals(metadata.get("insert"))) {
            // 插入到当前对话：从队列移出 → 加入插入队列
            // ...（插件内部逻辑）
        } else {
            // 入队
            queue.offer(ctx.input(), ctx.rawContent());
        }
        // 短路：自己调 ctx.ok，返回 null，后续节点不执行
        ctx.rpcContext().ok(Json.obj().put("taskId", taskId));
        return null;
    }
    return next.proceed(ctx);  // 终态或不存在，继续往下（再运行路径）
}
```

返回 null → 后续节点（order 70 response.ack / order 80 thread.submit / 100+ 虚拟线程阶段）不执行。

### 2.3 队列循环（queue.loop 节点，虚拟线程阶段）

order 395 `queue.loop`（task-input-queue 插件贡献），紧贴内核，循环调 `next.proceed`：

```java
// QueueLoopNode (order=395, 虚拟线程阶段)
public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
    String taskId = ctx.taskId();
    InputQueue queue = new InputQueue();

    // 下行：恢复悬空队列
    for (UserInput q : store.readQueue(store.dirOf(taskId))) {
        queue.offer(q.text(), q.rawContent());
    }
    // 注册 per-task 队列（queue.dispatch 节点在 RPC 线程入队用）
    registry.register(taskId, queue);

    try {
        // 内核循环：跑一轮 → poll 队列 → 有就再跑
        Object result = next.proceed(ctx);  // = 内核（agent.run 一次）
        while (result instanceof TaskOutcome to && to.status() == TaskEndStatus.DONE) {
            UserInput nextInput = queue.poll();
            if (nextInput == null) break;
            var impl = (TaskLifecycleContextImpl) ctx;
            impl.consumeInput(impl.taskEntry().main, nextInput);
            result = next.proceed(ctx);  // 再跑一轮内核
        }

        // 落盘悬空队列
        // ...

        return result;
    } finally {
        registry.unregister(taskId);
    }
}
```

**为什么不会重跑下行/上行**：`next.proceed` 从 QueueLoopNode 往内只有内核（TaskKernel），没有别的节点。重调 `next.proceed` 只重调内核。上行段在 QueueLoopNode 的外层，只有最终 `return result` 后才执行一次。

---

## 3. 契约变更

### 3.1 返回值 Object

```java
// TaskLifecycleNode
Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception;

// TaskChain
@FunctionalInterface
public interface TaskChain {
    Object proceed(TaskLifecycleContext ctx) throws Exception;
}

// TaskKernel
@FunctionalInterface
public interface TaskKernel {
    Object run(TaskLifecycleContext ctx);
}
```

`TaskOutcome` 仍是 Object 的一种具体类型，上行节点继续消费它。`TaskLifecycleExecutor.run` 返回值从 `TaskOutcome` 改为 `Object`。

### 3.2 TaskLifecycleContext 新增

```java
/** task.run 的用户输入文本（首条输入，main.agent 节点消费）。 */
String input();
/** task.run 的原始内容（含 opaque token，供前端回放还原）。 */
String rawContent();
/** task.run 的通用插件参数容器（核心不解释，插件自行消费）。 */
Map<String, Object> metadata();
/** RPC 应答器（链节点直接调 ctx.ok 返回前端；仅 RPC 线程阶段有效，虚拟线程阶段为 null）。 */
Object rpcContext();  // 返回 RpcContext 或 null
```

### 3.3 删除

| 删除项 | 说明 |
|---|---|
| `task.input`（Events.TASK_INPUT） | 废弃，统一走 task.run |
| `task.dialogInsert`（Events.TASK_DIALOG_INSERT） | 废弃，标记在 metadata 里 |
| `TaskInputHandler.onTaskInput` | 废弃 |
| `TaskInputHandler.onDialogInsert` | 废弃 |
| `TaskInputInterceptor` SPI | 不需要——队列插件直接注册链节点 |
| `TaskInputInterceptorRegistry` | 不需要 |
| `task.queueRemove` / `task.queueMove` RPC | 保留（队列插件自行注册，操作内存队列） |

---

## 4. 核心 vs 插件职责切分

### 4.1 核心（worker）节点

| order | 节点 | 职责 | 阶段 |
|---|---|---|---|
| 10 | `idempotency.check` | 幂等键去重 | RPC |
| 20 | `workspace.resolve` | workspace 解析 + 校验 | RPC |
| 30 | `taskid.generate` | taskId 生成 | RPC |
| 50 | `taskentry.create` | TaskEntry 构造 + tasks.put（新建）/ 认领（终态再运行） | RPC |
| 55 | `rerun.restore` | 再运行 meta→TaskEntry 恢复 | RPC |
| 60 | `slash.notify` | slash 建后回调 | RPC |
| 70 | `response.ack` | TASK_CREATED 广播 + ctx.ok | RPC |
| 80 | `thread.submit` | vt.submit 切换虚拟线程 | RPC→VT |
| 100 | `persistence.track` | 建目录 + 首写 meta + 挂 EventLog | VT |
| 200 | `task.wires` | 注入 onUsageBroadcast | VT |
| 310 | `model.switch.trace` | 模型切换标注 | VT |
| 390 | `main.agent` | buildMainAgent + consumeInput | VT |
| 840 | `status` | 下行 RUNNING / 上行终态 CAS | VT |
| 内核 | kernel | agentService.run 一次 → done | VT |
| 950~350 | （现状上行节点不变） | 收口 | VT |

### 4.2 task-queue 插件（任务间队列，不变）

| order | 节点 | 职责 |
|---|---|---|
| 40 | `admission.check` | 并发准入（Semaphore），超限→ERR_BUSY 短路 |

### 4.3 task-input-queue 插件（任务内队列）

| order | 节点 / 组件 | 职责 | 阶段 |
|---|---|---|---|
| 65 | `queue.dispatch` | 运行中→入队/插入→ctx.ok→短路(null) | RPC |
| 395 | `queue.loop` | 恢复悬空队列 + 注册队列 + 包裹内核循环 + 落盘 | VT |
| — | `QueueRpcHandler` | task.queueRemove / queueMove RPC | — |
| — | `DialogInsertAdvisor` | 工具循环下行 drain 插入队列 | VT |
| — | 前端面板 | ui.composer_above_panel 注册 | — |

**vs v2 方案的关键变化**：
- 不再需要 `TaskInputInterceptor` SPI / `QueueInputInterceptor` / `TaskInputInterceptorRegistry`——队列插件直接注册链节点(order=65)，节点在 RPC 线程执行时直接拿到 ctx（含 metadata/input/rawContent），自行判断入队/插入/短路。
- `queue.dispatch`(65) 在 RPC 线程，`queue.loop`(395) 在虚拟线程——两个节点分工明确：dispatch 管"入队/短路"，loop 管"恢复/循环/落盘"。

---

## 5. 前端改造

### 5.1 统一入口

```ts
// TaskChat.tsx 发送消息
if (isTaskRunning) {
  // 运行中：task.run{taskId} → 入队
  await taskQueryService.runTask(aiText, { taskId, rawContent })
} else if (isInsertMode) {
  // 插入到当前对话：task.run{taskId, metadata: { insert: true }}
  await taskQueryService.runTask(aiText, { taskId, rawContent, metadata: { insert: true } })
} else {
  // 新建 or 终态续跑
  await taskQueryService.runTask(aiText, { taskId, configId, rawContent })
}
```

- `sendInput`（fire-and-forget pub）废弃，统一走 `taskQueryService.runTask`（RPC 同步应答）。
- 前端不关心 `ctx.ok` 返回什么（`{taskId}` 还是 `{queued: true}`），只关心没报错。任务状态靠广播。

### 5.2 taskQueryService.runTask 新增 metadata 参数

```ts
async runTask(input: string, opts?: {
  taskId?: string
  workerId?: string
  title?: string
  // ... 其他参数不变
  metadata?: Record<string, unknown>  // ← 新增
}): Promise<string> {
  // RPC params 增加 metadata
}
```

### 5.3 删除

- `task-packet-view.ts` 的 `sendInput` 方法
- `taskQueryService.ts` 的 `removeQueuedInput` / `moveQueuedInput` / `insertQueuedInput`（迁插件前端）
- `taskStore.ts` 的 `pendingInputs` 字段保留（插件广播 task.updated 带该字段时进 store）

---

## 6. 待确认决策点

| # | 决策点 | 选项 | 倾向 |
|---|---|---|---|
| 1 | 短路时 ctx.ok 由谁调 | A) 入队节点自己调 B) 短路标记经 response.ack 调 | A：短路节点自治 |
| 2 | 再运行认领逻辑位置 | A) order 50 taskentry.create 节点内分支 B) 独立节点 | A：新建和认领都是"TaskEntry 入表" |
| 3 | QueueLoopNode 循环调 next.proceed 只重调内核 | 确认：下行/上行不重跑，每轮共用 AgentEntity/EventLog | 与现状一致 |
| 4 | thread.submit 跨线程 | 节点把 next 打包提交虚拟线程，自己返回 null | 确认可行 |
| 5 | editSeq（编辑重发）位置 | A) 核心节点（order ~45，在 queue.dispatch 之前） B) 插件 | A：编辑重发是核心任务语义，不是队列概念 |

---

## 7. 实施计划

| 步 | 内容 | 交付 |
|---|---|---|
| 1 | 契约变更：invoke/chain/kernel 返回值改 Object；TaskLifecycleContext 新增 input/rawContent/metadata/rpcContext | 接口就位 |
| 2 | TaskLifecycleExecutor：返回值改 Object；折叠规则适配 | 编译通过 |
| 3 | 核心节点拆为 RPC 线程阶段（order 10~80）+ 虚拟线程阶段（100+）；rpcTaskRun 改为启动链 | 核心链从 RPC 线程启动 |
| 4 | 删除 task.input / task.dialogInsert 通道 + TaskInputHandler.onTaskInput/onDialogInsert + TaskInputInterceptor SPI + Registry | 核心无队列概念 |
| 5 | task-input-queue 插件：queue.dispatch(65) + queue.loop(395) + RpcHandler + DialogInsertAdvisor + 前端面板 | 插件功能等价 |
| 6 | 前端：统一 task.run 入口 + metadata 参数 + 删 sendInput | 前端行为等价 |
| 7 | app pom 挂载 + CI 检查 + 冒烟 | 全链路通过 |

---

## 8. 风险

| 风险 | 对策 |
|---|---|
| 链返回值改 Object 破坏现有上行节点类型假设 | 上行节点入参从 TaskOutcome 改 Object，内部 instanceof 判断；或约定"正常运行路径返回值一定是 TaskOutcome" |
| thread.submit 跨线程后链的异常处理 | 虚拟线程内链异常由执行器兜底（catch Throwable）；RPC 线程已返回 |
| 短路后 response.ack 不执行 | 短路节点自己调 ctx.ok（§6.1 A） |
| QueueLoopNode 循环调 next.proceed 时中断/取消语义 | 每轮 next.proceed 返回 Object（内核翻译异常为 TaskOutcome）；非 DONE 直接 break |
| task.input 废弃后前端重连兼容 | 前端不再有 sendInput 路径；重连走 task.poll 恢复 |
| 再运行认领逻辑拆成链节点后的竞态 | diskTasks.remove 原子认领保留在节点内（§6.2 A） |
