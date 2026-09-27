# 删除 ModelRequestNode 体系，三个功能回归 Advisor 插件

## 目标
删除 Model 层洋葱链（ModelRequestNode 体系），将重试（2个）、压缩（1个）迁入独立插件模块，限流从 ModelRequestNode 改为 Advisor，ai-review 走正常 agent 创建路径。

## 步骤
- [x] 步骤 1：创建三个新插件模块骨架（empty-response-retry / transient-error-retry / context-compression）
    - 状态：已完成
    - agent：`sub_hkl4j`
    - 依赖：无
    - 验收标准：三个插件目录含 pom.xml + plugin.json + Plugin 入口类，`every-agent-plugins/pom.xml` 增加 3 个 `<module>`，`mvn compile` 通过 ✅
- [~] 步骤 2：迁移三个重试/压缩 Advisor 到插件模块
    - 状态：进行中（已派发）
    - agent：`sub_hkl4m`
    - 依赖：依赖步骤 1
    - 验收标准：6 个类（3 Advisor + ContextCompressor + ContextSummarizer + LlmContextSummarizer）从 worker/task/ 迁入对应插件模块（改 package + import），3 个 Provider 从 worker/plugin/adapters/ 迁入，3 个 Plugin 入口类创建，`BuiltInAdvisorProviders` 删除 3 行注册，原文件删除，`mvn compile` 通过
- [x] 步骤 3：限流从 ModelRequestNode 改为 Advisor
    - 状态：已完成
    - agent：`sub_hkl4k`
    - 依赖：无（与步骤 1/2 可并行）
    - 验收标准：`RateLimitAdvisor`（implements CallAdvisor, StreamAdvisor）+ `RateLimitAdvisorProvider` 创建，`ModelRateLimitPlugin.activate()` 改为 `registerAdvisorProvider`，`RateLimitNode.java` 删除，`mvn compile` 通过 ✅
- [ ] 步骤 4：删除 ModelRequestNode 体系（plugin-api + worker）
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 3（限流不再依赖 ModelRequestNode）
    - 验收标准：plugin-api 的 3 个接口文件删除，worker 的 `ModelRequestChainChatModel` + `ModelRequestContextImpl` + `ModelRequestNodeRegistry` 删除，`WorkerPluginContext`/`WorkerPluginContextImpl`/`PluginLoader`/`ChatModelFactory` 清理完毕，9 个测试文件 `fakeModelFactory` 签名修复，`mvn clean package` 全量通过
- [ ] 步骤 5：改造 ai-review 走 AgentClientFactory
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 2（重试 Advisor 已在插件中，ai-review 不再 import 它们）
    - 验收标准：`AiAuthReviewer` 注入 `AgentClientFactory`，删除手动构造 Advisor 代码，改用 `agentClientFactory.forAgent(reviewEntity, tcm)`，`buildReviewEntity` 改 `Kind.MAIN` → `Kind.SUB`，`mvn compile` + ai-review 测试通过
- [ ] 步骤 6：更新文档
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 4、5
    - 验收标准：ARCHITECTURE.md §7.19 重写（删除 ModelRequestNode 章节），§7.3 Advisor 链更新，§8.5 插件列表增加三个新插件，`docs/design-model-rate-limit.md` 更新限流实现方式

## 备注
- 步骤 1、3 无依赖可并行；步骤 2 依赖步骤 1；步骤 4 依赖步骤 3；步骤 5 依赖步骤 2；步骤 6 依赖步骤 4+5。
- 步骤 2 和步骤 3 可并行（无共享文件）。
- 测试文件共 9 个引用 `ModelRequestNodeRegistry`：WorkerIntegrationTest / WorkerHubE2eTest / WorkerTaskPollTest / WorkerDataPusherTest / TaskRoundsRpcTest / TaskAgentsRpcTest / MultiHubE2eTest / EditResendRoundsTest / FsGitModuleTest。
- 迁移时保持运行时行为不变：重试退避算法、压缩三阶段逻辑、限流排队/记账/校准、事件发射全部保持一致。
