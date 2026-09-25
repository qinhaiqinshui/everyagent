# 设计方案：任务内输入队列插件化（核心去队列概念）

> 状态：**草稿，待评审** · v2（用户澄清后重写：核心内核=单轮，队列插件用节点包裹内核实现循环）
> 前置：onion 模型已落地（§11 收敛后基线）。
> 一句话：**核心就是"拿到输入→running→agent.run 一次→done，运行中收到消息拒绝"；队列插件用一个紧贴内核的链节点拦截消息+实现 while 循环。**

---

## 1. 核心流程（无队列插件时）

```
task.input → [终态/新建] → 洋葱下行(track/wires/main.agent/status) → 内核: agentService.run(main) 一次 → 洋葱上行(终态收口) → done
task.input → [运行中] → 拒绝（ERR_BUSY 或静默忽略）
```

核心内核**没有 while 循环**：

```java
TaskKernel kernel = c -> {
    agentService.run(main);
    return TaskOutcome.done(startedAt, System.currentTimeMillis());
};
```

一轮 agent 跑完即终态。用户要继续对话 → 终态任务的 `task.input` → 再运行冷启动（核心已有，不依赖队列）。

---

## 2. 队列插件设计

### 2.1 紧贴内核的成对节点

队列插件贡献一个 `TaskLifecycleNode`，order 紧跟 `main.agent`(390) 之后、内核之前（如 order=395）。在 filter 模型中，该节点的 `next.proceed(ctx)` 就是内核（再往内没有别的节点了）。

```java
public final class QueueLoopNode implements TaskLifecycleNode {
    public float order() { return 395; }

    public TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        // 下行：注册消息拦截器（运行中 task.input → 入队而非拒绝）
        inputInterceptorRegistry.register(ctx.taskId(), (text, raw) -> queue.offer(text, raw));

        // 内核循环：跑一轮 → 队列取下一条 → 有就再跑
        TaskOutcome result = next.proceed(ctx);          // = 内核（agent.run 一次）
        while (result.status() == TaskOutcome.TaskEndStatus.DONE) {
            UserInput nextInput = queue.poll();           // 非阻塞，空返回 null
            if (nextInput == null) break;                 // 队列空 → 任务结束
            ctx.consumeInput(main, nextInput);
            result = next.proceed(ctx);                   // 再跑一轮内核
        }

        // 上行段（return 后洋葱按 order 降序收口）
        return result;
    }
}
```

**为什么不会重复上行段**：`next.proceed(ctx)` 从该节点往内只有内核（TaskKernel），内核不含任何上行节点。上行段（status/cascade/spawned 等）在该节点的外层，只有最终 `return result` 后才执行一次。

### 2.2 消息拦截：task.input 运行中分支

核心 `TaskInputHandler.onTaskInput` 运行中分支当前是"入队"，改为"拒绝"：

```java
// 核心行为
public void onTaskInput(HubLink conn, JsonNode payload) {
    TaskEntry t = tasks.get(taskId);
    if (t != null && !t.status.terminal()) {
        // 运行中：核心直接拒绝
        // 队列插件注册了拦截器时，拦截器先处理（offer 到队列），核心不拒绝
        for (TaskInputInterceptor interceptor : inputInterceptors) {
            if (interceptor.onRunningTaskInput(taskId, text, rawContent)) return;
        }
        // 无拦截器 → 拒绝
        return;  // 或 ctx.err(ERR_BUSY) — 待定，见 §5
    }
    // 终态 → 再运行认领（核心保留）
    rerunTask(conn, taskId, text, rawContent, editSeq);
}
```

新 SPI：

```java
// every-agent-plugin-api
public interface TaskInputInterceptor {
    /** 处理运行中任务收到的 task.input。true = 已处理（入队），核心不拒绝。 */
    boolean onRunningTaskInput(String taskId, String text, String rawContent);
}
```

队列插件注册实现：`onRunningTaskInput` → `queue.offer(text, rawContent)` → return true。

### 2.3 插入到当前对话（task.dialogInsert）

`task.dialogInsert` + `DialogInsertAdvisor` + `AgentEntity.pendingDialogInserts` 全部迁入插件：
- 插件注册 `TaskDialogInsertHandler`（或复用 `TaskInputInterceptor` 扩展），拦截 `task.dialogInsert` 消息。
- `DialogInsertAdvisor` 由插件贡献（AdvisorProvider，scope=MAIN）。
- `AgentEntity.pendingDialogInserts` 字段移除——插件经 AgentContext 扩展暴露插入队列给 advisor。

### 2.4 队列操作 RPC

`task.queueRemove` / `task.queueMove` 由插件经 `registerRpcMethod` 注册（git 插件先例），操作插件内部队列。

### 2.5 悬空队列持久化

插件贡献 `queue`(700) SectionNode（下行恢复 / 上行落盘），同现状设计不变。

---

## 3. 核心 vs 插件职责切分

### 3.1 核心（worker）删除

| 删除项 | 说明 |
|---|---|
| `InputQueue.java` | 迁入插件 |
| `TaskEntry.inputQueue` 字段 | 删除 |
| `TaskEntry.pendingInputs()` / `runtimeSummaryJson()` 中的 pendingInputs | 删除 |
| `publishQueue()` | 删除 |
| 内核 while 循环 | 改为单轮 `agentService.run(main)` |
| `onDialogInsert()` | 删除（迁插件） |
| `rpcTaskQueueRemove` / `rpcTaskQueueMove` / `mutateQueue` | 删除（迁插件） |
| `TaskStore.readQueue/writeQueue/deleteQueue` | 保留方法（插件调用），或迁插件 |
| `QueueNode`(700) | 从内置节点删除（插件贡献） |
| `AgentEntity.pendingDialogInserts` | 删除（迁插件） |
| `DialogInsertAdvisor` | 删除（迁插件） |
| 内核循环中的 `publishQueue(te)` | 删除 |

### 3.2 核心（worker）保留

| 保留项 | 说明 |
|---|---|
| `TaskKernel`（单轮） | `agentService.run(main)` 一次，return done |
| `onTaskInput` 终态分支 | 再运行认领（核心续命机制） |
| `TaskInputInterceptor` SPI | 插件注册后拦截运行中 task.input |
| `task.input` 运行中分支 | 无拦截器时拒绝；有拦截器时交给拦截器 |
| `UserInput` 值对象 | 插件依赖它 |

### 3.3 插件（task-input-queue）结构

```
every-agent-plugins/task-input-queue/
  pom.xml
  src/main/java/dev/everyagent/plugin/inputqueue/
    InputQueue.java                  # 从 worker 迁入
    QueueLoopNode.java               # 紧贴内核的循环节点(order=395)
    QueueInputInterceptor.java       # TaskInputInterceptor 实现(运行中入队)
    QueueDialogInsertHandler.java    # task.dialogInsert 处理
    DialogInsertAdvisor.java         # 从 worker 迁入(AdvisorProvider 贡献)
    QueueRpcHandler.java             # task.queueRemove/queueMove RPC
    QueuePersistNode.java            # queue(700) 持久化节点(SectionNode)
    TaskInputQueueRegistrar.java     # 装配入口
  web/
    index.ts                         # registerComposerAbovePanel
    TaskInputQueuePanel.tsx          # 从 web 核心迁入
    task-input-queue.css
```

---

## 4. 前端迁移

同 subagent 插件先例：面板经 `ui.composer_above_panel` 扩展点注册。

- `TaskQueuePanel.tsx/.css` 从 web 核心迁入插件 `web/`。
- `TaskChat.tsx` 删除硬编码 `<TaskQueuePanel>`，abovePanel 改为纯扩展点渲染。
- `taskStore.pendingInputs` 字段保留（插件广播 task.updated 带 pendingInputs 时进 store；无插件恒空）。
- `taskQueryService` 三方法迁插件前端（经 `ctx.rpc()` 调用）。

---

## 5. 待评审决策点

| # | 决策点 | 选项 | 倾向 |
|---|---|---|---|
| 1 | 无队列插件时运行中 task.input 回复 | A) 静默忽略 B) 返回 ERR_BUSY | A：单轮语义下运行中窗口极短，静默忽略更简洁；前端可做"任务运行中"禁用输入框兜底 |
| 2 | queue.jsonl 读写方法归属 | A) TaskStore 保留方法、插件调用 B) 插件自管文件 IO | A：TaskStore 已有方法且经目录体系 |
| 3 | AgentEntity.pendingDialogInserts 去留 | A) 移除，插件经 AgentContext 扩展暴露 B) 保留为通用字段 | A：彻底去核心概念 |
| 4 | queue(700) 进临界段 | A) "worker"标识注册(subagent 先例) B) 放宽信任 | A |

---

## 6. 实施计划

| 步 | 内容 | 交付 |
|---|---|---|
| 1 | plugin-api：新增 `TaskInputInterceptor` SPI；`WorkerPluginContext.registerTaskInputInterceptor()` | 接口就位 |
| 2 | worker 核心：内核改单轮；`onTaskInput` 运行中分支改拒绝+拦截器；删 InputQueue/inputQueue/publishQueue/onDialogInsert/mutateQueue/QueueNode/DialogInsertAdvisor/pendingDialogInserts | 核心无队列概念 |
| 3 | 新建插件：QueueLoopNode(395) + InputInterceptor + RpcHandler + QueuePersistNode(700) + DialogInsertAdvisor + InputQueue | worker 侧行为等价 |
| 4 | web 核心：删 TaskQueuePanel 硬编码引用；插件 web/ 迁入面板 | 前端行为等价 |
| 5 | app pom 挂载 + CI 检查 | 卸载冒烟通过 |

---

## 7. 风险

| 风险 | 对策 |
|---|---|
| QueueLoopNode 循环重调 next.proceed 的异常/中断语义 | 每轮 next.proceed 返回 TaskOutcome（值），非 DONE 直接 break 退出循环（失败/取消交给上行段收口） |
| 消息拦截时序：task.input 在洋葱节点下行段注册拦截器之前到达 | 注册在 ctx 装配时（节点下行段执行前），但 task.input 是异步消息——运行中窗口极短，且拦截器注册在 QueueLoopNode 下行段（order=395），main.agent(390) 刚建完 agent 就注册了 |
| 删插件后历史 queue.jsonl | 原地保留；重装恢复 |
