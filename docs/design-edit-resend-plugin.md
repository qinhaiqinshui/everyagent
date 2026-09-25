# 设计方案：编辑重发插件化

> 状态：**草稿，待评审**
> 前置：洋葱链贯穿 RPC 线程 + 统一入口 + 任务内队列插件化 v3 已落地。
> 一句话：**编辑重发抽成独立插件，队列里存 ctx 引用，三个节点职责单一互不依赖。**

---

## 1. 核心设计

### 1.1 队列里存 ctx

队列项从 `UserInput(text, rawContent)` 改为直接存 `TaskLifecycleContext` 引用。ctx 是链节点间一直透传的任务上下文，携带 `input()`、`rawContent()`、`metadata()`。入队存引用，取出时全数据原样下传，后续节点从 metadata 中取自己在前端存进去的数据。

```
task.run#1 → ctx1{input:"改一下", rawContent, metadata:{editSeq:"123"}}
  → queue.dispatch(65): queue.offer(ctx1) → 短路

task.run#2 → ctx2{input:"继续", rawContent, metadata:null}
  → queue.dispatch(65): queue.offer(ctx2) → 短路
```

### 1.2 三个节点职责单一

| order | 节点 | 归属 | 职责 |
|-------|------|------|------|
| 395 | `queue.loop` | task-input-queue 插件 | poll ctx → 把 polledCtx 的 input/rawContent/metadata 设到当前 ctx → next.proceed |
| 395.5 | `edit.resend` | task-edit-resend 插件 | 从 ctx.metadata() 取 editSeq → 截断 → next.proceed |
| 396 | `consume.input` | 核心 | consumeInput(ctx.input, ctx.rawContent) → next.proceed |

三个节点互不依赖：
- 队列插件不知道 editSeq，只 poll + 透传
- 编辑重发插件不知道队列，只从 ctx.metadata() 取自己的标记
- consume.input 节点不知道上面两个，只做标准消费动作
- 删除 task-edit-resend 插件 → 395.5 不存在 → 395→396 直连，consumeInput 正常执行，只是不截断
- 删除 task-input-queue 插件 → 395 不存在 → 无队列循环，任务跑完即结束

### 1.3 数据流

```
RPC 线程:
  task.run{taskId, input, rawContent, metadata:{editSeq:"123"}}
    → rpcTaskRun 创建 ctx{input, rawContent, metadata}
    → queue.dispatch(65): 运行中 → queue.offer(ctx) → ctx.ok → 短路(null)

虚拟线程 (QueueLoopNode 循环):
  首轮:
    queue.loop(395): (无 poll) → next.proceed
      → edit.resend(395.5): ctx.metadata() == null → next.proceed
        → consume.input(396): consumeInput(ctx.input, ctx.rawContent) → next.proceed
          → kernel: agentService.run(main)

  后续轮 (poll 到 ctx1{editSeq:"123"}):
    queue.loop(395): poll → ctx1 → ctx.input/rawContent/metadata = ctx1 的值 → next.proceed
      → edit.resend(395.5): ctx.metadata().get("editSeq") == "123" → 截断 → next.proceed
        → consume.input(396): consumeInput(ctx.input, ctx.rawContent) → next.proceed
          → kernel: agentService.run(main)

  后续轮 (poll 到 ctx2{metadata:null}):
    queue.loop(395): poll → ctx2 → ctx.input/rawContent/metadata = ctx2 的值 → next.proceed
      → edit.resend(395.5): ctx.metadata() == null → next.proceed
        → consume.input(396): consumeInput(ctx.input, ctx.rawContent) → next.proceed
          → kernel: agentService.run(main)

  poll() == null → 循环结束
```

---

## 2. 链节点基线（改动部分）

```
虚拟线程阶段（异步）:
  order 100 persistence.track         (不变)
  order 200 task.wires                (不变)
  order 310 model.switch.trace        (不变)
  order 390 main.agent                (不变：buildMainAgent + consumeInput(首条))
  order 395 queue.loop               (改：poll ctx → 设 ctx → next.proceed，不做 consumeInput)
  order 395.5 edit.resend            (新增：从 ctx.metadata() 取 editSeq → 截断)
  order 396 consume.input            (新增核心：consumeInput → next.proceed)
  order 840 status                    (不变)
  --- 内核 ---
  order ∞   kernel
```

### 2.1 queue.loop(395) — 改造

```java
// QueueLoopNode (order=395, task-input-queue 插件)
public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
    String taskId = ctx.taskId();
    InputQueue queue = registry.getOrCreateInputQueue(taskId);

    // 下行：恢复悬空队列（新建任务返回空）
    for (TaskLifecycleContext qctx : store.readQueue(...)) {
        queue.offer(qctx);
    }

    try {
        Object result = next.proceed(ctx);  // 首轮 → edit.resend → consume.input → kernel
        while (result instanceof TaskOutcome to && to.status() == TaskOutcome.TaskEndStatus.DONE) {
            TaskLifecycleContext polledCtx = queue.poll();
            if (polledCtx == null) break;
            // 把 polledCtx 的数据设到当前 ctx，后续节点（edit.resend / consume.input）能读到
            var impl = (TaskLifecycleContextImpl) ctx;
            impl.input(polledCtx.input());
            impl.rawContent(polledCtx.rawContent());
            impl.metadata(polledCtx.metadata());
            result = next.proceed(ctx);  // → edit.resend → consume.input → kernel
        }

        // 上行：落盘悬空队列
        ...
        return result;
    } finally {
        registry.unregister(taskId);
    }
}
```

**变化**：
- `queue.offer(ctx)` / `queue.poll() → ctx`（队列存 ctx 引用）
- 不再调 `consumeInput`——交给 396 consume.input 节点
- poll 后把 polledCtx 的 input/rawContent/metadata 设到当前 ctx 上

### 2.2 edit.resend(395.5) — 新增

```java
// EditResendNode (order=395.5, task-edit-resend 插件)
public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
    Map<String, Object> metadata = ctx.metadata();
    if (metadata != null) {
        String editSeq = (String) metadata.get("editSeq");
        if (editSeq != null && !editSeq.isEmpty()) {
            // 截断：运行中热路径 / 终态冷路径
            truncateProcessor.truncate(ctx.taskId(), editSeq, ctx.input(), ctx.rawContent(), ctx);
        }
    }
    return next.proceed(ctx);  // → consume.input(396) → kernel
}
```

插件不存在时此节点不存在，395→396 直连，consumeInput 正常执行。

### 2.3 consume.input(396) — 新增核心节点

```java
// ConsumeInputNode (order=396, 核心)
public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
    var impl = (TaskLifecycleContextImpl) ctx;
    impl.consumeInput(impl.taskEntry().main,
            UserInput.of(ctx.input(), ctx.rawContent()));
    return next.proceed(ctx);  // → kernel
}
```

从 QueueLoopNode 迁出 consumeInput 调用，独立成核心节点。MainAgentNode(390) 的首条 consumeInput 不变（它消费 initialInput，不走队列循环）。

---

## 3. 队列改造

### 3.1 InputQueue 改存 ctx

```java
// InputQueue（task-input-queue 插件）
public final class InputQueue {
    private final ArrayList<TaskLifecycleContext> items = new ArrayList<>();

    public void offer(TaskLifecycleContext ctx) { ... }
    public TaskLifecycleContext poll() { ... }
    public TaskLifecycleContext removeAt(int index) { ... }
    // snapshot / move 等方法签名同步改
}
```

### 3.2 QueueDispatchNode 入队 ctx

```java
// QueueDispatchNode (order=65, task-input-queue 插件)
if (t != null && !t.status.terminal()) {
    // 入队整个 ctx（含 metadata，后续节点自行消费）
    registry.getOrCreateInputQueue(taskId).offer(ctx);
    ctx.ok(...);
    return null;
}
```

插入对话队列也改为存 ctx（DialogInsertAdvisor 从 ctx 取 input/rawContent drain）。

### 3.3 悬空队列落盘

队列项从 UserInput 改为 ctx，落盘时需要序列化 ctx 的 input/rawContent/metadata。恢复时反序列化重建 ctx（或部分数据）。具体格式由 task-input-queue 插件自行决定。

---

## 4. task-edit-resend 插件

### 4.1 后端

| 文件 | 职责 |
|------|------|
| `EditResendNode.java` (order=395.5) | 从 ctx.metadata() 取 editSeq → 截断 → next.proceed |
| `EditTruncateProcessor.java` | 截断逻辑（从 TaskManager.truncateForEdit / truncateForColdEdit 迁入） |
| `TaskEditResendRegistrar.java` | Spring 装配：注册 EditResendNode |

**EditTruncateProcessor** 包含两个方法（从 TaskManager 原样迁入）：

- `truncateForEdit(taskId, entry, editSeq, text, rawContent)` — 运行中热路径：截断内存 EventLog + 磁盘 + 重建会话内存 + 清理子 agent + 更新 meta + 广播
- `truncateForColdEdit(taskId, storedTask, editSeq, text, rawContent)` — 终态冷路径：截断磁盘 + 更新 meta + 广播

### 4.2 前端

| 文件 | 职责 |
|------|------|
| `web/index.ts` | 插件入口，注册编辑 UI |
| `web/EditMessageButton.tsx` | 用户消息上的编辑按钮（从 AgentMessageThread.tsx 迁入） |
| `web/useEditResend.ts` | 编辑模式状态 Hook（从 TaskChat.tsx 的 editTarget 逻辑迁入） |
| `web/userMessageEditContext.ts` | 编辑 Context（从核心迁入插件） |

前端通过 `taskQueryService.runTask(text, { taskId, rawContent, metadata: { editSeq } })` 发送编辑重发请求。editSeq 作为 metadata 字段透传到后端，核心不解释。

### 4.3 删除项

| 删除项 | 位置 | 说明 |
|--------|------|------|
| `truncateForEdit` | TaskManager | 迁入插件 EditTruncateProcessor |
| `truncateForColdEdit` | TaskManager | 迁入插件 EditTruncateProcessor |
| rpcTaskRun 中 editSeq 前置处理 | TaskManager | 清理（editSeq 由插件节点在 VT 阶段消费） |
| `editTarget` 状态 + `handleEditUserMessage` | TaskChat.tsx | 迁入插件 |
| `UserMessageEditContext` | 核心前端 | 迁入插件 |
| `AgentMessageThread` 中编辑按钮 | 核心前端 | 迁入插件 |

---

## 5. 核心改动

### 5.1 新增 ConsumeInputNode(396)

核心内置节点，从 QueueLoopNode 迁出 consumeInput 调用。

### 5.2 删除 TaskManager 中编辑重发逻辑

- 删除 `truncateForEdit` 方法
- 删除 `truncateForColdEdit` 方法
- 删除 `rpcTaskRun` 中 editSeq 前置处理（~30 行）

### 5.3 BuiltInTaskLifecycleNodes 注册

新增注册 `ConsumeInputNode(396)`。

---

## 6. 已确认决策

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | 队列存什么 | ctx 引用（链节点间透传的任务上下文） |
| 2 | 三个节点 order | queue.loop=395, edit.resend=395.5, consume.input=396 |
| 3 | 谁做 consumeInput | 396 consume.input 核心节点（从 QueueLoopNode 迁出） |
| 4 | 队列插件是否参与编辑重发 | 不参与，只 poll + 透传 ctx |
| 5 | 删除插件效果 | 删 task-edit-resend → 不截断；删 task-input-queue → 无队列循环 |
| 6 | editSeq 如何传递 | 前端存入 metadata，核心透传，插件消费 |

---

## 7. 风险

| 风险 | 对策 |
|------|------|
| 队列存 ctx 引用，ctx 可能被后续 RPC 修改 | 每次 task.run 创建新 ctx，入队后该 ctx 不再被修改 |
| 悬空队列落盘需要序列化 ctx | 落盘 input/rawContent/metadata 三字段，恢复时重建 |
| 395.5 与 396 之间无锁 | 两个节点都在 VT 单线程执行，无并发 |
| 截断失败时 consumeInput 仍执行 | EditResendNode 截断异常应 catch + 记日志，不阻塞后续 |

---

## 8. 实施计划

| 步 | 内容 | 交付 |
|---|------|------|
| 1 | InputQueue 改存 ctx + QueueDispatchNode 入队 ctx + QueueLoopNode poll 设 ctx（不调 consumeInput） | 队列存取 ctx 就位 |
| 2 | 新增 ConsumeInputNode(396) 核心 + 注册 | consume.input 就位 |
| 3 | 新建 task-edit-resend 插件：EditResendNode(395.5) + EditTruncateProcessor + Registrar | 截断就位 |
| 4 | 从 TaskManager 删除 truncateForEdit / truncateForColdEdit / editSeq 前置处理 | 核心无编辑重发 |
| 5 | 前端编辑 UI 抽成插件（EditMessageButton + useEditResend + userMessageEditContext） | 前端完整 |
| 6 | 编译验证 + 测试 | 全量通过 |
