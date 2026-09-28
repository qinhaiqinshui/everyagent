# 长度护栏与自适应预算双插件化（model-length-guard / adaptive-max-tokens）

## 目标
把 worker 核心的 `ModelLengthGuardAdvisor` 抽成 `model-length-guard` 插件；新增 `adaptive-max-tokens` 插件（实现本会话讨论的 AdaptiveMaxTokensAdvisor：截断时自动放大 maxTokens 重试）。两插件**零互相依赖、零跨插件依赖**，缺任何一个（或都缺）系统照常运行。

## 设计决策（已定稿，实施时不再讨论）

### 交互模型：纯协议信号 + order 位置，无编译依赖
```
链外→内: TransientErrorRetry(+200) → ModelLengthGuard(+300) → Adaptive(+350,新增) → ContextCompression(+400) → RateLimit(+500)
```
- Adaptive 在 Guard **内侧**（+350），先于 Guard 看到原始模型帧：`finish_reason=length` 帧到达时**拦截**（吞掉该帧），按升级预算改写 prompt options 后重发（重调 `chain.nextStream`，与瞬时重试同模式）。
- Adaptive 放弃（达重试次数上限或预算到 ceiling）→ 抛**自己的**非重试异常 `AdaptiveBudgetExhaustedException`（信息含已放大至多少、建议精简输入/降 reasoningEffort）——不引用 Guard 的异常类。
- Guard 保持三条路径不变；与 Adaptive 并存时 Guard 实际兜的是 ②stall/③断流 两条路径（①finish_reason 帧已被 Adaptive 消费），互补不重叠。

### 缺谁都照样运行（四象限验收口径）
| Guard | Adaptive | 行为 |
|---|---|---|
| 有 | 有 | length 帧 → Adaptive 升预算重试 N 次 → 放弃抛自己的错；stall/断流 → Guard 判定收口 |
| 有 | 无 | 现状行为，零变化 |
| 无 | 有 | length 帧 → Adaptive 升预算重试 → 放弃抛错；stall/断流退回瞬时重试语义（无 length 判定） |
| 无 | 无 | length 帧当正常完成（guard 出现前的旧行为），不崩 |

### Adaptive 预算策略
- 触发：确定性信号 only——`finish_reason=length` 帧 / usage 末帧 `completionTokens >= maxTokens`（不做"思考很长"的启发式，避免鼓励烧 token）。
- 升级：`budget = min(base × multiplier^attempt, ceiling)`；重试立即重订阅（**不加人工退避延迟**，防触发外层 Guard 的 stall 计时）。
- 回落：连续 N 轮实际输出 < 当前预算 × 低水位 → 衰减回 base（防预算单调膨胀）。
- 上限 ceiling 来源：`worker.models[].params.maxTokensCeiling`（模型级，任务快照 params 读）> `worker.limits.adaptive-max-tokens.default-ceiling`（全局默认）；参数（multiplier/maxRetries/回落阈值）走 `worker.limits.adaptive-max-tokens.*` 全局配置。**不配 ceiling（=0）= 插件直通不启用**（缺省安全）。
- 预算状态是 advisor 实例字段（per-run 经 AdvisorProvider 创建，主/子 agent 天然隔离）；改写只动 prompt options（`mutate().maxTokens(budget)`），不动 `a.options`、不动任务快照——与"请求级参数走请求通道"的定稿口径一致。

### Guard 插件化的依赖解法（迁移不改行为）
- `WorkerProperties`：activate() 时 `ctx.getService(WorkerProperties.class)`（ModelPoolPlugin 先例）。
- `TokenEstimator`：**延迟解析**——每次 `create(ctx)` 时从 worker 服务取"当前生效"估算器（`WorkerServicesImpl` 的 `AtomicReference`，model-rate-limit 插件 `registerTokenEstimator` 替换后自动跟随；不在场时 worker 内置 `SimpleTokenEstimator` 兜底）。不得在 activate() 时固化引用，否则拿不到替换。
- `AgentEntity`：`((AdvisorContextImpl) ctx).agentEntity()`（RateLimitAdvisorProvider 先例）。
- `ModelLengthExhaustedException` 随类迁入插件（public，任务层只按 RuntimeException error 收口，无需感知具体类型）。

## 步骤
- [ ] 步骤 1：更新 ARCHITECTURE.md（文档先行）
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：§7.4.1 及 §7.3 交叉引用改为 guard 插件归属；新增 AdaptiveMaxTokens 小节（order=+350、协议信号交互模型、四象限矩阵、params/limits 配置项）；演进记录（§7.19.4 表或 Phase 列表）补一行
- [ ] 步骤 2：创建 model-length-guard 插件模块骨架
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：`every-agent-plugins/model-length-guard/`（pom + plugin.json + `ModelLengthGuardPlugin` 入口空注册）+ `every-agent-plugins/pom.xml` 加 `<module>`，`mvn compile` 通过
- [ ] 步骤 3：创建 adaptive-max-tokens 插件模块骨架
    - 状态：待执行
    - agent：-
    - 依赖：无
    - 验收标准：`every-agent-plugins/adaptive-max-tokens/`（pom + plugin.json + `AdaptiveMaxTokensPlugin` 入口空注册）+ `<module>` 登记，`mvn compile` 通过
- [ ] 步骤 4：迁移 ModelLengthGuardAdvisor 到插件
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 2
    - 验收标准：Advisor + `ModelLengthExhaustedException` + 新 `ModelLengthGuardAdvisorProvider`（order=+300，scope=BOTH，TokenEstimator 延迟解析）迁入插件并在 activate() 注册；worker 侧 `task/ModelLengthGuardAdvisor.java`、`plugin/adapters/ModelLengthGuardAdvisorProvider.java` 删除、`BuiltInAdvisorProviders` 删注册行；`ModelLengthGuardAdvisorTest` 迁入插件；`mvn clean package` 通过
- [ ] 步骤 5：实现 AdaptiveMaxTokensAdvisor
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 3
    - 验收标准：`AdaptiveMaxTokensAdvisor`（Call+Stream，order=+350）+ Provider + `AdaptiveBudgetExhaustedException` + WorkerProperties 新增 `worker.limits.adaptive-max-tokens.*`；单测覆盖：length 帧拦截升级重试、达 ceiling 放弃抛错、回落衰减、无 ceiling 直通、finish 信号不误触发
- [ ] 步骤 6：核心清理与文档校准
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 4、5
    - 验收标准：`rg "ModelLengthGuardAdvisor" every-agent-worker/src` 零结果（注释指向插件）；`rg "AdaptiveMaxTokens" every-agent-worker/src` 零结果；SimpleTokenEstimator 保留为 worker 兜底 bean；ARCHITECTURE.md 与代码一致
- [ ] 步骤 7：全量回归 + 分步提交
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 6
    - 验收标准：`mvn clean package` 全量通过（含 WorkerIntegrationTest / WorkerHubE2eTest / MultiHubE2eTest）；两插件 pom 互相零依赖（`rg adaptive every-agent-plugins/model-length-guard` 零结果，反向同）；按步骤分别 git 提交（中文一次一事，feat: 前缀）

## 备注
- 步骤 2/3 与步骤 1 无依赖可并行；步骤 4/5 分别依赖 2/3，**彼此可并行**（互不读写对方模块）；6 依赖 4+5；7 收口。
- 已知风险：Adaptive 内部重试的静默期计入外层 Guard 的 stall 计时（+300 包 +350）——缓解：重试零延迟重订阅 + 验收用例覆盖；若实测误判，调大 `length-stall-ms` 或把 Adaptive 移到 Guard 外侧（放弃时改抛自身异常仍无耦合，备选方案不引入依赖）。
- 配置新增走"模型级 params 优先、全局 limits 兜底"既有口径，与去池化方案（plan-core-depooling.md §5.1）及 Phase 6 热更新正交，互不牵扯。
- 红线自检：两 advisor 均薄实现（Call/StreamAdvisor，不自建工具循环/聚合）；一个插件一个功能；复用 ChatClient/Advisor 生态。
