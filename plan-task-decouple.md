# Task 域解耦方案

## 目标
消除 worker 内部和 plugin-api 门面层对 task 域类型的不当耦合，使横切层（advisor / 工具 / 授权 / 子 agent / slash）只见 `ExecContext`，不见 `TaskEntry` 等 task 域具体类型。

## 前提确认
- `ExecContext` 已有 `metadata()`、`workspaceRoot()`、`interaction()`、`emitter()` 方法。`TaskEntry implements TaskRuntime extends ExecContext` 已实现这些。无需扩展接口，直接改下游消费方。

## 步骤

- [ ] 步骤 1：拆分 `WorkerServices` 门面，将 task 域服务隔离到子接口
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：`WorkerServices` 不再直接暴露 `task()`/`store()` 方法；新增独立 `TaskServices` 子接口（`WorkerServices` 继承之），非 task 插件只看基础 `WorkerServices` 不被迫传递性 import task 类型。全量编译通过。
    - 说明：根因修复——当前所有插件通过 `ctx.services()` 就看见了 `TaskService`/`TaskStoreService`。拆分后，需要 task 服务的插件显式获取 `TaskServices`，其余插件零 task 耦合。

- [ ] 步骤 2：拆分 `WorkerPluginContext`，将 task SPI 注册隔离到子接口
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：`WorkerPluginContext` 不再直接暴露 `registerTaskAdmissionPolicy()`/`registerTaskLifecycleNode()`；新增 `TaskPluginContext` 子接口承载这两个方法。非 task 插件的 `activate(ctx)` 签名只见基础 `WorkerPluginContext`。全量编译通过。

- [ ] 步骤 3：消除 Advisor 层对 `TaskEntry` 的直接持有
    - 状态：待执行
    - agent：-
    - 依赖：无（`ExecContext` 已具备所需方法）
    - 验收标准：
      - `FileAttachmentAdvisor` 字段从 `TaskEntry` 改为 `ExecContext`，通过 `ExecContext.metadata()` 取附件数据
      - `SlashTokenResolveAdvisor` 字段从 `TaskEntry` 改为 `ExecContext`
      - `FileAttachmentAdvisorProvider` 和 `SlashTokenResolveAdvisorProvider` 不再强转 `ExecContext` → `TaskEntry`
      - 编译通过，现有测试通过

- [ ] 步骤 4：消除 slash 域接口签名中的 `TaskEntry` 参数
    - 状态：待执行
    - agent：-
    - 依赖：无（`ExecContext` 已具备所需方法）
    - 验收标准：
      - `SlashTokenHandler.resolve(String, TaskEntry)` → `resolve(String, ExecContext)`
      - `SlashTokenResolver.resolveSubmissionText(JsonNode, TaskEntry)` → `resolveSubmissionText(JsonNode, ExecContext)`
      - `SlashTaskCallbacks` 方法参数 `TaskEntry` → `ExecContext`
      - `ExternalFileTokenResolver` 实现签名同步更新，不再 import `TaskEntry`
      - `SlashTaskScopeStore` 改为通过 `ExecContext` 访问所需数据
      - 编译通过，现有测试通过

- [ ] 步骤 5：重构 ship 推送层，消除对 task 域五大类型的直接依赖
    - 状态：待执行
    - agent：-
    - 依赖：无（独立子系统）
    - 验收标准：
      - `DataPusher` 不再直接持有 `TaskManager`，改通过 `StreamSourceRegistry`（接口化）获取事件流源
      - `DataPusherManager` 不再实现 `TaskManager.TaskResumeListener` 内部接口；改为实现 plugin-api 上的通用 `StreamSourceListener` 接口
      - `StreamSourceRegistry` 方法签名从 `EventLog` 改为 `EventLogReader`（plugin-api 接口）
      - `TaskEvents.wireEvent()` 逻辑抽为 ship 层内的独立工具类 `EventWireFormatter`，ship 层不再直接调用 task 域静态方法
      - 编译通过，现有测试通过

- [ ] 步骤 6：将工具调用基础设施类从 task 包移至 agent 包
    - 状态：待执行
    - agent：-
    - 依赖：无（独立重构）
    - 验收标准：
      - `InterceptingToolCallingManager`、`LoopRepeatGuardToolManager`、`SchemaStrippedToolCallback`、`LoopRepeatException`、`LoopRepeatGuard`、`ContextOverflow` 从 `task` 包移至 `agent` 包
      - `AgentRunner`、`AgentBuilder` 的 import 路径更新
      - `ChatModelFactory` 从 `task` 包移至 `config` 包，`AgentFactoryImpl` import 更新
      - 编译通过，现有测试通过

- [ ] 步骤 7：将 `TaskSearchService` 移入 task 域
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：
      - `TaskSearchService` 从 `modules` 包移至 `task` 包（它只搜任务数据，本质是 task 域代码）
      - `modules` 层的 RPC dispatch 改为调用 task 域的 `TaskSearchService`，不再直接引用
      - 编译通过，现有测试通过
    - 说明：`TaskSearchService` 调用 `TaskStore.scanWorkspace()` 和 `TaskStore.parseRoundLine()`，整个服务只处理任务轮次数据，放在 modules 层是层归属错误。移入 task 包后，它对 `TaskStore`/`RoundIndex` 的引用变为包内引用，不再算跨域耦合。

- [ ] 步骤 8：消除 `WorkspaceManager` 对 `TaskManager` 的直接依赖
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：
      - 在 `modules` 层定义 `WorkspaceCascadePort` 接口，含 `int deleteByWorkspaceId(String workspaceId)` 和 `int redirectWorkspace(String oldRoot, String newRoot)` 两个方法，签名全用原语类型
      - `TaskManager` 实现 `WorkspaceCascadePort`
      - `WorkspaceManager` 字段从 `ObjectProvider<TaskManager>` 改为 `ObjectProvider<WorkspaceCascadePort>`
      - `WorkspaceManager` 不再 import `TaskManager`
      - 编译通过，现有测试通过

- [ ] 步骤 9：消除 `InteractionServiceImpl` 对 `TaskManager`/`TaskEntry`/`TaskEvents` 的直接依赖
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：
      - 在 `interaction` 层定义 `EmitterLookup` 接口，含 `EventEmitter emitterFor(String subjectId)` 方法，返回 plugin-api 的 `EventEmitter` 接口
      - `TaskManager` 实现 `EmitterLookup`，内部 `get(taskId).events` 返回为 `EventEmitter`
      - `InteractionServiceImpl` 字段从 `@Lazy TaskManager` 改为 `EmitterLookup`
      - 内部 `Ask` 类的 `TaskEvents events` 字段改为 `EventEmitter emitter`
      - `InteractionServiceImpl` 不再 import `TaskManager`/`TaskEntry`/`TaskEvents`
      - 编译通过，现有测试通过

- [ ] 步骤 10：消除 plugin/adapters 层对 task 域存储类型的直接持有
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：
      - `RoundIndexAdvisorProvider` 不再直接持有 `TaskStore`/`RoundIndexStore`；通过 `ExecContext` 取所需数据（`dataDir()` 定位 rounds.jsonl）
      - `WorkerToolEventAdvisorProvider` 不再直接 new task 包的 `WorkerToolEventAdvisor`；通过 SPI 工厂（`AdvisorFactory` 接口）创建
      - `BuiltInAdvisorProviders` 不再持有/传递 `TaskStore`/`RoundIndexStore`
      - 编译通过，现有测试通过

- [ ] 步骤 11：修复 plugins 层的残留耦合
    - 状态：待执行
    - agent：-
    - 依赖：步骤 1、2
    - 验收标准：
      - `sandbox-wsl-ubuntu/NetworkTaskFlag` 不再接收 `TaskService` 参数，改通过 `ExecContext.metadata()` 写入 + 通用上下文更新广播
      - 5 个插件的测试桩从 `StubTaskRuntime implements TaskRuntime` 改为 `StubExecContext implements ExecContext`
      - 全量编译通过，全量测试通过

- [ ] 步骤 12：收紧 hub `ChannelRegistry` 的 task 频道解析
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：
      - 将 `parseStreamChannel` / `StreamRef` / `taskId` 提取逻辑上提到 contract（作为协议级 helper 类 `StreamChannelParser`），hub 调用 contract 的 helper，不再在自身代码中硬编码 `task.` 频道段解析逻辑
      - hub `ChannelRegistry` 改为调用 `StreamChannelParser.parse(channel)` 获取 `StreamRef(ownerKey, taskId)`
      - 编译通过，hub 测试通过

- [ ] 步骤 13：收敛 web 前端 task 域代码归属
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：
      - `src/sdk/task-poll.ts`、`task-packet-buffer.ts`、`task-packet-view.ts` 迁至 `src/task/`
      - `src/sdk/index.ts` 不再 re-export 任何 task 业务方法
      - `src/hub/taskStore.ts`、`taskStream.ts` 迁至 `src/task/`
      - `src/hub/session.ts` 不再硬编码订阅 `tasks` 频道——由 task 域自行在 Provider 挂载时订阅
      - task DTO 从 `src/types/` 迁至 `src/task/types.ts`
      - `src/sdk/` 只保留 `frames.ts`、`channels.ts`、`hub-client.ts` 三个纯协议文件
      - 前端构建通过，功能不退化

## 备注

### 依赖关系
- 步骤 1、2 是步骤 11 的前置依赖（门面拆分后插件才能不再传递性耦合）
- 其余步骤互不依赖

### 可并行批次
- **批次 A**（无依赖）：步骤 1 + 步骤 2 + 步骤 3 + 步骤 4 + 步骤 5 + 步骤 6 + 步骤 7 + 步骤 8 + 步骤 9 + 步骤 10 + 步骤 12 + 步骤 13
- **批次 B**（依赖步骤 1、2）：步骤 11

### 已知约束
- 每步完成后需全量编译 + 测试，确保不引入回归
- `ExecContext` 已具备 `metadata()`/`workspaceRoot()`/`interaction()`/`emitter()`，无需扩展接口
- `WorkerServicesImpl` 作为 SPI 适配器桥接 worker 内部 task 域与 plugin-api 契约，属合理引用，不在改造范围
- `proto/RpcMethods.java` 的 wire 协议常量属合理，不在改造范围
- contract 层的 task 事件 schema 定义属合理协议契约，不在改造范围
- 接口命名不带 "Task" 前缀——`WorkspaceCascadePort`、`EmitterLookup` 均定义在消费方层，`TaskManager` 实现之，方法签名只用 plugin-api 类型与原语，不出现 task 域类型
