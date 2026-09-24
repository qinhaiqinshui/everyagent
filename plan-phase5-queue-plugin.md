# Phase 5: 任务队列插件实施计划

## 目标
实现 `docs/design-agent-layer-onion.md` 中的 Phase 5——任务队列插件：在洋葱模型下行空隙（order=250）插入形态三成对节点（QueueAdmissionNode），将当前「并发上限即拒 ERR_BUSY」语义替换为「排队等待」语义；新增 RPC 边缘预检扩展点供队列插件接管准入逻辑。

## 步骤

- [x] 步骤 1：plugin-api 新增 `TaskAdmissionPolicy` SPI + 准入结果类型
    - 状态：已完成
    - agent：`sub_hklt`
    - 依赖：无
    - 验收标准：`every-agent-plugin-api` 的 `dev.everyagent.plugin.api.task` 包新增 `TaskAdmissionPolicy` 接口（`AdmissionResult check(AdmissionRequest req)`）和 `AdmissionRequest`/`AdmissionResult` record；`WorkerPluginContext` 新增 `registerTaskAdmissionPolicy(TaskAdmissionPolicy)` 方法

- [x] 步骤 2：worker 新增 `TaskAdmissionPolicyRegistry` + `WorkerPluginContextImpl` 实现注册
    - 状态：已完成
    - agent：`sub_hklt`
    - 依赖：步骤 1
    - 验收标准：`plugin/registry/` 新增 `TaskAdmissionPolicyRegistry`（AtomicReference，至多一个策略）；`WorkerPluginContextImpl` 实现 `registerTaskAdmissionPolicy` 委托注册表

- [x] 步骤 3：worker `TaskManager.rpcTaskRun` 集成准入策略 + 新增队列事件常量
    - 状态：已完成
    - agent：`sub_hklt`
    - 依赖：步骤 2
    - 验收标准：`rpcTaskRun` 中并发上限检查改为：若 `TaskAdmissionPolicyRegistry` 有注册策略则调用之（队列插件 always-admit），否则保持默认 `active >= max → ERR_BUSY`；`Events.java` 新增 `TASK_QUEUED = "task.queued"` 事件常量；`RpcMethods.java` 新增 `TASK_QUEUE_LIST = "task.queueList"` 方法名

- [x] 步骤 4：创建 `task-queue` 插件模块骨架（pom + 目录结构）
    - 状态：已完成
    - agent：`sub_hklu`
    - 依赖：无（可与步骤 1-3 并行）
    - 验收标准：`every-agent-plugins/task-queue/` 目录创建，`pom.xml` 依赖 `every-agent-worker`；`every-agent-plugins/pom.xml` 的 `<modules>` 加入 `task-queue`

- [x] 步骤 5：实现 `TaskQueue` 核心类（Semaphore + 队列状态管理 + 事件广播）
    - 状态：已完成
    - agent：`sub_hklw`
    - 依赖：步骤 3、步骤 4
    - 验收标准：`TaskQueue.java` 包含 `Semaphore`（permits=maxConcurrentTasks）、`ConcurrentLinkedQueue<String>` 跟踪排队 taskId、`acquire(taskId)` / `release(taskId)` 方法；acquire 时若需等待则广播 `task.queued` 事件（含队列位置），release 时广播更新后的队列状态

- [x] 步骤 6：实现 `QueueAdmissionNode`（TaskLifecycleNode, order=250, 形态三 try/finally）
    - 状态：已完成
    - agent：`sub_hklw`
    - 依赖：步骤 5
    - 验收标准：`QueueAdmissionNode` 实现 `TaskLifecycleNode`；order=250；invoke 中 try 之前调 `taskQueue.acquire(ctx.taskId())`（下行阻塞段），finally 调 `taskQueue.release(ctx.taskId())`（上行出队广播段）；下行抛异常时 release 不执行（未进入不收口语义）

- [x] 步骤 7：实现 `TaskQueueAdmissionPolicy` + `TaskQueueRegistrar` + `task.queueList` RPC
    - 状态：已完成
    - agent：`sub_hklw`
    - 依赖：步骤 5、步骤 6
    - 验收标准：`TaskQueueAdmissionPolicy` 实现 `TaskAdmissionPolicy`（always-admit，返回 `AdmissionResult.admit()`）；`TaskQueueRegistrar` 为 `@Component`，构造时注入 `TaskLifecycleRegistry` + `TaskAdmissionPolicyRegistry` + `EventSink`，注册 `QueueAdmissionNode` 和 `TaskQueueAdmissionPolicy`；`task.queueList` RPC 返回当前队列快照

- [x] 步骤 8：app pom 挂载 task-queue 依赖
    - 状态：已完成
    - agent：主 Agent
    - 依赖：步骤 4
    - 验收标准：`every-agent-app/pom.xml` 的 `<dependencies>` 加入 `task-queue` 模块依赖（吸取 git 插件漏挂教训）

- [x] 步骤 9：单元测试
    - 状态：已完成
    - agent：`sub_hklx`
    - 依赖：步骤 6、步骤 7
    - 验收标准：`QueueAdmissionNodeTest` 验证：① 无排队时直接通过；② 并发满时阻塞（虚拟线程 park）；③ 任务完成后释放并唤醒等待者；④ 下行异常时不 release（未进入不收口）；⑤ 队列事件广播正确

- [x] 步骤 10：集成测试 + `mvn test` 全量回归
    - 状态：已完成
    - agent：主 Agent
    - 依赖：步骤 9
    - 验收标准：task-queue 插件 9 个测试全绿（5+4）；worker 模块有 Phase 3 预存编译错误（ExternalRootAllowCheckTest，与 Phase 5 无关）；plugin-api 编译通过

- [x] 步骤 11：ARCHITECTURE.md 新增 §7.14.4 队列插件小节 + 更新 §14.5 红线
    - 状态：已完成
    - agent：`sub_hkly`
    - 依赖：步骤 10
    - 验收标准：`docs/ARCHITECTURE.md` 新增 `### 7.14.4 任务队列插件（Phase 5）` 小节，描述 QueueAdmissionNode/order=250/Semaphore 机制/准入策略扩展点/`task.queued` 事件/`task.queueList` RPC；§14.5 并发红线补充「队列插件启用时超限任务排队等待而非 BUSY 拒绝」

- [ ] 步骤 12：git 提交
    - 状态：待执行
    - agent：主 Agent
    - 依赖：步骤 11
    - 验收标准：提交信息 `feat: 实现任务队列插件（Phase 5）——洋葱模型形态三成对节点 + RPC 边缘准入扩展点`

## 备注
- **依赖关系**：步骤 1→2→3 串行（SPI→注册表→集成）；步骤 4 独立可并行；步骤 5 依赖 3+4；步骤 6 依赖 5；步骤 7 依赖 5+6；步骤 8 依赖 4；步骤 9 依赖 6+7；步骤 10 依赖 9；步骤 11 依赖 10；步骤 12 依赖 11。
- **并行机会**：步骤 1-3 与步骤 4 可并行。
- **设计约束（来自 design-agent-layer-onion.md §7.4）**：只新增，不改既有节点 order。QueueAdmissionNode order=250 落在 100~400 下行空隙内，不与既有节点冲突。
- **架构红线**：QueueAdmissionNode 是形态三（try/finally 成对），非 UpstreamNode 子类（不走临界段组合）；插件经 `WorkerPluginContext.registerTaskLifecycleNode` 或 `@Component`+直接注册表注入注册（同 git 插件先例）。
- **Semaphore 设计**：permits = `maxConcurrentTasks`；acquire 阻塞 = 虚拟线程 park（零线程开销）；release 唤醒下一个等待者。`active` 计数器保持不变（统计在途任务总数含排队），Semaphore 控制实际并发运行数。
- **事件设计**：`task.queued` 广播到 `tasks` 频道，payload 含 `{taskId, position, queueLength}`；任务获得 slot 后由 `status.start`(300) 正常广播 `task.updated` status=running。
