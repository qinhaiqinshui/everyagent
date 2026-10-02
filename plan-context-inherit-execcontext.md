# Context 对象收编 ExecContext 重构(完成过渡期)

## 目标
让 plugin-api 中带重复字段的 Context 接口直接 `extends ExecContext`,删除 `taskId()`/`workspaceRoot()`/`execution()` 等过渡/重复槽位,消费者统一走 ExecContext 槽位(`subjectId()` 等),完成 S 系列过渡期的收尾。

## 现状盘点
| Context | 位置 | 与 ExecContext 的重复 | 处置 |
|---|---|---|---|
| `ToolContext` | spi | `taskId()`、`workspaceRoot()`(Path,**类型冲突**)、`interaction()`、`execution()`(default null,过渡) | 继承 ExecContext,删重复槽位 |
| `AdvisorContext` | spi | `taskId()`、`workspaceRoot()`(Path,冲突)、`execution()`(default);`configId()` 是 per-agent 语义(审议 agent 覆盖模型时≠snapshot().configId()),**保留** | 继承,删 taskId/workspaceRoot/execution |
| `FileReferenceContext` | spi | `taskId()`、`workspaceId()`、`workspaceRoot()`(Path,冲突)、`execution()`(default null,早期节点可为 null) | 继承,删重复槽位,保留早期 null 语义 |
| `ToolExecutionContext` | spi | `execution()`(唯一取数口) | 继承,删 execution(),拦截器直读槽位 |
| `TaskLifecycleContext` | task | `taskId()`、`workspaceRoot()`、`workspaceId()` | 继承,仿 `TaskRuntime` default 桥接(保留 taskId() 任务域成员,委托 taskRuntime(),早期节点 null 语义) |
| `AgentContext` | agent | 已有 `execution()` 槽位,agent 域接口,无重复字段 | 不动 |
| `WorkerPluginContext` / `EnhancerContext` / `RpcContext`(api+worker) / `PermissionContext` / `RestoredQueueContext` | — | 与 ExecContext 无重复字段(激活期/模型构建/RPC 应答/授权判定/恢复项) | 不动(RestoredQueueContext 随 TaskLifecycleContext 接口被动适配) |

**已确立的先例**:`TaskRuntime extends ExecContext`(default 桥接 subjectId→taskId、emitter→events、dataDir→taskDir),本次全部沿用该范式。
**类型冲突处理**:`ToolContext.workspaceRoot():Path` / `AdvisorContext.workspaceRoot():Path` / `FileReferenceContext.workspaceRoot():Path` 与 `ExecContext.workspaceRoot():String` 返回类型冲突,继承后必须删除 Path 变体,消费侧改 `Path.of(ctx.workspaceRoot())`。

## 步骤
- [x] 步骤 1:更新架构文档(docs/ARCHITECTURE.md §7.20 及相关条目)
    - 状态:已完成
    - agent:`sub_o3y4s`
    - 依赖:无
    - 验收:§7.20 新增 7.20.5 收编表 + 11 处定稿改动;「过渡」表述零残留;未动 Java 代码
    - 验收标准:§7.20 写明五个 Context 继承 ExecContext 的桥接/委托范式、删除的过渡槽位清单、`taskId`→`subjectId` 消费侧命名迁移;删除"过渡期返回 null"表述;文档先行(agents.md 红线)
- [x] 步骤 2:`ToolContext extends ExecContext` 重构
    - 状态:进行中(已派发)
    - agent:`sub_o3y4t`
    - 依赖:依赖步骤 1
    - 验收标准:接口删 `taskId()`/`workspaceRoot():Path`/`interaction()`/`execution()`;`ToolContextImpl` 持有 ExecContext 并委托全部槽位;`AgentBuilder.createToolContext` 简化;消费侧迁移:worker adapters(FileTools/DirectShell/AskUser 已用 execution() 的改直读槽位)、sandbox-windows-codex、sandbox-wsl-ubuntu(`ctx.taskId()`→`ctx.subjectId()`)、sandbox-windows-mic、subagent SubAgentToolsProvider、MissingToolCallbackResolver、TestFixtures.FakeToolContext;相关模块编译通过
- [x] 步骤 3:`AdvisorContext extends ExecContext` 重构
    - 状态:已完成
    - agent:-
    - 依赖:依赖步骤 2(同改 AgentBuilder,串行防冲突)
    - 验收标准:接口删 `taskId()`/`workspaceRoot():Path`/`execution()`,**保留 `configId()`**(per-agent 语义,审议覆盖场景);`AdvisorContextImpl` 槽位委托 `agentEntity.execution()`;消费侧迁移:DialogInsertAdvisorProvider(`ctx.taskId()`→`ctx.subjectId()`)等 13 处 provider 排查;相关测试(AdaptiveMaxTokens/ModelLengthGuard 等)适配;编译通过
- [x] 步骤 4:`FileReferenceContext extends ExecContext` 重构
    - 状态:已完成
    - agent:-
    - 依赖:依赖步骤 2
    - 验收标准:接口删 `taskId()`/`workspaceId()`/`workspaceRoot():Path`/`execution()`;`FileReferenceProcessNode.ContextView` 改为委托 TaskRuntime(早期 null 语义保留);ImageReferenceHandler 消费侧迁移(`ctx.workspaceRoot()`→`Path.of(...)` 含 null 判定);编译通过
- [x] 步骤 5:`ToolExecutionContext extends ExecContext` 重构
    - 状态:已完成
    - agent:-
    - 依赖:依赖步骤 2
    - 验收标准:接口删 `execution()`,继承 ExecContext;ToolExecutionChainExecutor 实现委托 per-run ExecContext;unattended UnattendedToolInterceptor `ctx.execution().metadata()`→`ctx.metadata()`;编译通过
- [x] 步骤 6:`TaskLifecycleContext extends ExecContext` 重构
    - 状态:已完成
    - agent:-
    - 依赖:依赖步骤 2
    - 验收标准:接口仿 TaskRuntime 加 default 桥接(`subjectId()`→`taskId()`),其余槽位 default 委托 `taskRuntime()`(早期节点 null 时返回 null/空);`taskId()`/`workspaceRoot()`/`workspaceId()` 作为任务域成员保留(与 TaskRuntime 同范式,消费侧零改动);TaskLifecycleContextImpl/RestoredQueueContext 适配;编译通过
- [x] 步骤 7:全量构建 + 测试回归
    - 状态:已完成
    - agent:-
    - 依赖:步骤 2-6
    - 验收:本次重构触及的 10 个插件模块 mvn test BUILD SUCCESS(零失败);worker 模块失败项(RoundIndexStore/ConversationLoader/FsGit/MultiHubE2e/TaskRoundsRpc)经 git stash 在干净 HEAD 上同样失败,确认为预存问题(git RPC 未知方法、轮次索引、会话加载等),与 Context 重构无关,非本次回归
- [x] 步骤 8:提交
    - 状态:已完成
    - agent:-
    - 依赖:步骤 7
    - 验收:单提交 4076085,中文信息,other: 前缀,23 文件改动(+496/-204);已排除无关 plan 文件

## 备注
- 全部步骤串行:步骤 2-6 均改 plugin-api 接口且步骤 2/3 同改 `AgentBuilder`,并行会冲突;每步完成后该步编译验证,步骤 7 做全量回归。
- 风险点 1:`workspaceRoot()` Path→String 类型变化是破坏性 API 变更,需全仓搜 `ctx.workspaceRoot()` 调用点逐一适配(步骤内完成)。
- 风险点 2:`AdvisorContext.configId()` 不能委托 `snapshot().configId()`(AgentBuilder 注释:审议 agent 覆盖模型时二者不同),保留为 AdvisorContext 自有槽位。
- 风险点 3:早期节点(RPC 阶段)任务未创建时 execution/runtime 为 null 的语义必须保留,不能改成强制非 null。
- 回退方案:每步独立提交,任一步回归失败可单独 revert。
