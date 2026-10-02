# 统一执行上下文（ExecContext）落地

## 目标
按 `docs/design-exec-context.md` 定稿方案，将 worker 执行管道的黑盒 properties map
升级为显式类型化的 `ExecContext` 管道，横切层（advisor/工具/授权链/子 agent/审议
agent）全部域中性化，共 6 个阶段（S1–S6）各自独立编译、独立提交。

## 步骤

- [x] 步骤 1（S1）：plugin-api 新增 ExecContext 接口层
    - 状态：已完成（提交 8dff2fd）
    - agent：`S1-exec-context-api`
    - 产物：`every-agent-plugin-api` 6 文件（新建 execution/ExecContext.java；
      TaskRuntime/AgentFactory/AgentContext/ToolContext/AdvisorContext 修改）
    - 依赖：无
    - 内容：
        1. 新增 `dev.everyagent.plugin.api.execution.ExecContext`（§4.1 全槽位：
           subjectId/workspaceRoot/workspaceId/snapshot/emitter/agentFactory/metadata/
           dataDir/terminal/interaction/agents；不含 fileChanges）。
           其中 `agentFactory()`/`interaction()` 暂以 default 返回 null（javadoc
           注明 S2 起由 worker TaskEntry 覆盖实现），保证本阶段零 worker 改动可编译。
        2. `TaskRuntime extends ExecContext`（不再 extends TaskInfo，显式吸收其成员
           status()/taskDir() 声明）；提供 default 映射：`subjectId()→taskId()`、
           `emitter()→events()`、`dataDir()→taskDir()`；旧成员（含 fileChanges 六方法）
           全部保留。
        3. `AgentFactory` 重签：新增两参签名 `create(agentId)` / `create(agentId, configId)`
           （default 抛 UnsupportedOperationException，javadoc 注明 S2 由绑定工厂实现、
           S5 转抽象）；旧四参签名原样保留（S5 删除）——否则 SubAgentManager/AiAuthReviewer
           提前编译失败，违背「每阶段独立编译」。
        4. `AgentContext` 新增 `default ExecContext execution() { return null; }`
           （@Deprecated properties() 并存，S4 删）。
        5. `ToolContext`/`AdvisorContext` 各增 `default ExecContext execution()`（§7.3）。
    - 验收标准：`mvn -q -pl every-agent-plugin-api -am compile` 通过；全仓
      `mvn -q compile`（含 worker/plugins）零改动通过；提交
      `feat: 新增 ExecContext 统一执行上下文接口`。

- [x] 步骤 2（S2）：worker 实现 ExecContext 与三预绑定端口
    - 状态：已完成（提交 ad2ff9e）
    - agent：`S2-worker-exec-impl`
    - 产物：worker 10 文件（新增 TaskBoundAgentFactory/SubjectBoundInteractionService
      + 单测；改 AgentFactoryImpl/AgentBuilder/AgentEntity/TaskEntry/TaskManager/
      TaskEntryCreateNode/ThreadSubmitNode）
    - 备注：修复 S1 遗留编译破损（TaskEntry 过渡性 implements TaskInfo，S3 删）；
      过渡 map 在 AgentBuilder.transitionProps 重建（含 configId 覆盖值细节）；
      worker 69 个测试失败经方法级基线对比确认为 HEAD 既有（环境相关），零新增
    - 依赖：步骤 1
    - 内容：
        1. `AgentFactoryImpl` 内部化：新增包内全参方法 `create(agentId, configId,
           ExecContext exec)`（绑定默认 configId = exec.snapshot().configId()）；
           旧四参接口方法保留为过渡（map 中取 taskEntry 充当 exec 的桥接实现）。
        2. 新增 `TaskBoundAgentFactory implements AgentFactory`（静态代理：闭包
           ExecContext，两参签名委托全参；四参过渡委托 delegate）。
        3. 新增 `SubjectBoundInteractionService`（绑 subjectId 的 ask 代理：
           context map 自动填 "taskId"=subjectId，键名保留前端兼容）。
        4. `TaskEntry` 实现 ExecContext 全槽位：显式覆盖 subjectId()/emitter()/
           dataDir()/agentFactory()/interaction()（懒加载字段，覆盖 default null）。
        5. worker 内部 `AgentBuilder.create(agentId, chatModel, options, ExecContext exec)`
           重签（emitter 取自 exec.emitter()，createToolContext 直接用 exec）。
        6. `ThreadSubmitNode` 主 agent 构造改传 exec（props 四件套组装删除）。
        7. `AgentEntity` 持有 ExecContext 并实现 `AgentContext.execution()`
           （properties 字段暂保留至 S4）。
    - 验收标准：`mvn -q compile` 全仓通过；worker 相关测试通过
      （`mvn -q -pl every-agent-worker test`）；提交
      `feat: worker 实现 ExecContext 与三预绑定端口`。

- [x] 步骤 3（S3）：授权链收编 ExecContext + AskQuestion fields
    - 状态：已完成（提交 2f7641d）
    - agent：`S3-auth-chain`
    - 产物：43 文件跨 plugin-api/worker/plugins/web（TaskInfo 退役删除；
      PermissionContext 域中性；GrantRegistry 槽位化；AskQuestion fields 含前端渲染）
    - 备注：顺带修复 S1 遗留的 4 插件 TaskInfo 消费点编译断裂；worker 69 失败
      基线（方法级对比）零新增；插件测试全绿（ai-review 17、unattended 4 等）
    - 依赖：步骤 2
    - 内容：
        1. `AuthorizationRequest` 重签 `(ExecContext context, agentId, grantKey, prompt)`。
        2. 三 handler 迁移（§6.2）：Unattended→`req.context().metadata()`；
           Human→`req.context().interaction().ask(...)`；AiReview→metadata 判定 +
           ctx 取 emitter/subjectId/workspaceRoot（审议 agent 创建本阶段仍走旧
           WorkerServices.agentFactory() 四参，S5 收编）。
        3. `GrantRegistry` 域中性化（§6.3）：authorize(req,...)、beginRun/untrack/
            extraRoots/execRoots 参数语义改 subjectId、execRootsSandboxed
           (workspaceRoot, subjectId)、persistGrants(dataDir,...)。
        4. `PermissionContext.task` 字段 → `workspaceRoot` + `AuthorizationRequest`；
           `FsToolSupport`/`CommandExecutor` 签名 TaskEntry→ExecContext（§6.4、§8.4）。
        5. `TaskInfo` 退役：permission 包删除，11 个 import 文件改 TaskRuntime/ExecContext。
        6. AskQuestion 结构化信息槽（§12）：record 加 `fields`（兼容旧构造）、
           `EventPayloads.questionsToJson` 补 fields（空省略）、前端
           `UserInteractionQuestion.fields` + `UserInteractionHost` 渲染、
           HumanAuthorizationHandler/ImageReferenceHandler 授权 ask 改
           「短问句 + fields」。
    - 验收标准：`mvn -q compile` 全仓通过；授权链/插件相关测试通过；前端
      `npm run build`（或 tsc 类型检查）通过；提交
      `feat: 授权链收编 ExecContext 实现域中性`。

- [x] 步骤 4（S4）：advisor 链全面迁移 ExecContext
    - 状态：已完成（提交 ef97a2f）
    - agent：`S4-advisor-migration`
    - 产物：28 文件（AgentContext/ToolExecutionContext 删 properties 槽位；18 消费者
      迁 execution()；FileChangeAdvisor 走 TaskService.get(subjectId())）
    - 备注：get("taskEntry") 全仓仅剩 AgentFactoryImpl 四参桥接 1 处（S5 删）；
      worker 69 失败基线零变化；插件测试全绿；ToolExecutionContext 黑盒暴露口一并退役
    - 依赖：步骤 2（与步骤 3 在依赖上可并行；因共享工作区的 git/mvn 提交冲突
      风险，编排为串行：步骤 3 完成提交后再派发本步骤）
    - 内容：
        1. §8.1 全表迁移：18 个文件的 `properties().get("taskEntry")` 强转 →
           `execution()` 槽位取数（RateLimit/ModelLengthGuard/ContextCompression/
           AdaptiveMaxTokens/EmptyResponseRetry/TransientErrorRetry/AgentsMd/
           SystemInfo/GitAutoSync/UnattendedToolInterceptor/WorkerToolEventAdvisor/
           RoundIndex/ContextOverflow/SlashTokenResolve/FileAttachment…）。
        2. `FileChangeAdvisor` 改走 `TaskService.get(subjectId())` 取 TaskRuntime
           读写 fileChanges（任务域插件合法路径）。
        3. `AgentContext` 删 `properties()`；`AgentEntity` properties 字段替换为
           execution；`AgentFactoryImpl` 四参过渡实现内的 map 桥接同步清理。
        4. `ToolContextImpl.taskEntry()` 删除（3 个内置 provider 改用 execution()，
           若步骤 3 已改则本步骤仅核验）。
    - 验收标准：`mvn -q compile` 全仓通过；全仓 `rg 'get\("taskEntry"\)'` 归零；
      advisor 相关测试通过；提交 `feat: advisor 链全面迁移 ExecContext`。

- [x] 步骤 5（S5）：subagent 全面中性化 + 审议 agent 收编 + 收尾清理
    - 状态：已完成（提交 0f8911e）
    - agent：`S5-subagent-neutralize`
    - 产物：38 文件（SubAgentManager 零服务依赖+TaskSubState；Ledger IO 自持；
      概念清退；AiAuthReviewer 固定 agentId 复用会话；四参签名/default 过渡全删）
    - 备注：残留扫描 4 项归零；ai-review 14/14+4/4（含新增复用会话用例）；
      worker 基线 68 失败方法零变化（1 个 flake 复跑 3/3 绿排除）
    - 依赖：步骤 3、步骤 4
    - 内容：
        1. `SubAgentManager` 零服务依赖（§8.2）：构造参数归零、方法签名改收
           ExecContext、取数全走槽位、监视器内部化（synchronized(taskState)）。
        2. `SubAgentTools` 改绑 ToolContext.execution()；`SubAgentLedger` IO 自持
           （AtomicFiles 读写 ctx.dataDir()/agents.json）；`TaskStoreService`
           readAgents/writeAgents 段退役。
        3. 生命周期节点壳/核分离（track/untrack/persist/spawned.await 取
           taskRuntime() 槽位）；`SubAgentRpcHandler` 壳/核分层；`SubAgentPlugin.activate()`
           services().task() 删除。
        4. task 域 subagent 概念清退（§8.5）：`TaskStore.truncateAfterSeq` 不再删
           agents.json/file-changes/（注释更新）；`RpcMethods.TASK_AGENTS` 常量移
           subagent 插件。
        5. `AiAuthReviewer`（§8.3）：构造只剩 WorkerConfig；`req.context().agentFactory()
           .create(...)`；固定 per-task agentId `review-<subjectId>` 复用会话
           （agents().get 命中 → resetForRerun + UserMessage 续跑；未命中 → 创建注册）。
        6. `ImageReferenceHandler`/`AskUserTool` 改经 ctx.interaction()；
           `WorkerServices.agentFactory()` 删除；`AgentFactory` 四参签名删除、
           两参转抽象；`AgentFactoryImpl` 不再 implements 公共接口（内部化）；
           `ExecContext.agentFactory()/interaction()` 的 default null 过渡实现
           转抽象（唯一实现者 TaskEntry 已就位）。
    - 验收标准：`mvn -q compile` 全仓通过；subagent/ai-review 测试通过；
      全仓无 `WorkerServices.agentFactory()`/`taskEntry()`（ToolContextImpl）引用；
      提交 `feat: subagent 插件全面中性化与审议 agent 收编 ExecContext`。

- [x] 步骤 6（S6）：ARCHITECTURE.md 同步
    - 状态：已完成（提交 50dbadc）
    - agent：`S6-arch-doc-sync`
    - 产物：docs/ARCHITECTURE.md 13 处修改（新增 §7.20 统一执行上下文管道 6 小节、
      D29 决策记录、§14.11 红线扩展、旧概念清理）
    - 备注：签名对照 7 处与代码一致；发现一处超范围文档漂移（§7.19.1/§7.4.1
      EmitEvent 事件管道演进滞后，与 ExecContext 无关，未处理留作后续）
    - 依赖：步骤 5
    - 内容：按终态同步架构文档：执行上下文管道分层（与 §14.0 事件管道同构）、
      ExecContext 槽位表、授权链新契约、AgentFactory 绑定工厂、subagent 中性化、
      AskQuestion fields；核对 §14 红线清单表述。
    - 验收标准：文档与代码实现一致（抽检接口签名/提交记录）；提交
      `other: 架构文档同步执行上下文管道`。

## 备注
- 依赖链：S1 → S2 → S3 → S4 → S5 → S6（S3/S4 依赖上可并行，编排为串行以避免
  共享工作区 git/mvn 冲突；S3 触碰 unattended 的 handler、S4 触碰其 interceptor，
  文件不交集但提交必须串行）。
- 每步验收由主 Agent 执行：编译 + 定向测试 + `rg` 残留扫描 + git 提交；验收不过
  则退回对应子 agent 修改直至通过。
- 工作区已有未提交变更（若干 plan-*.md 删除）与本次无关，提交时只 add 本次
  改动文件，不夹带。
- 回退方案：每阶段独立提交，任一阶段失败可 `git revert` 该阶段提交回滚，
  不影响前序阶段。
- 构建环境已验证：JDK 25（Corretto，JAVA_HOME=/opt/tools/jdk25）+ Maven 3 可用。
