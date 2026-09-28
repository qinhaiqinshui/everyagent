# 长度护栏与自适应预算双插件化（model-length-guard / adaptive-max-tokens）

## 目标
把 worker 核心的 `ModelLengthGuardAdvisor` 抽成 `model-length-guard` 插件；新增 `adaptive-max-tokens` 插件（实现本会话讨论的 AdaptiveMaxTokensAdvisor：截断时自动放大 maxTokens 重试）。两插件**零互相依赖、零跨插件依赖**，缺任何一个（或都缺）系统照常运行。

## 设计决策（已定稿，实施时不再讨论）

### 交互模型：Adaptive 在 Guard 外侧（+250），消费 Guard 判定出的耗尽异常 + 协议信号，无编译依赖
```
链外→内: TransientErrorRetry(+200) → Adaptive(+250,新增) → ModelLengthGuard(+300) → ContextCompression(+400) → RateLimit(+500)
```
- Adaptive 放在 Guard **外侧**（+250，瞬时重试内侧）：先包装后放行，内层 Guard 的三条路径（①finish_reason 帧 / ②stall / ③断流，均收口为 `ModelLengthExhaustedException`）抛出的异常**穿透压缩/限流到达 Adaptive 的 onErrorResume**。
- 触发识别用**字符串特征匹配**（`instanceof` Guard 的异常类会造成插件间编译依赖，违反零依赖原则）：Guard 的错误文案前缀固定为「模型输出已达上限」三种成因表述（见 `lengthExhausted`），Adaptive 以此为信号；同时兜底协议级 `finish_reason=length` 帧（无 Guard 时自适应仍独立工作）。
- 命中 → 升级 `budget = min(base × multiplier^attempt, ceiling)` → 改写 prompt options（`mutate().maxTokens(budget)`）重发（重调 `chain.nextStream`，与瞬时重试同模式）；预算状态存 advisor 实例字段，**本次任务全程沿用**（per-run 创建，主/子 agent 各自隔离；升过的值持续生效，回落见下）。
- 放弃（预算已达 ceiling 或重试次数上限）→ 抛**自己的**非重试异常 `AdaptiveBudgetExhaustedException`（信息含已放大至 ceiling 多少、建议精简输入/降 reasoningEffort/任务拆分）——不引用 Guard 的任何类型。
- Guard 行为零变化（三条路径原样）；并存时 Guard 判定「耗尽」→ Adaptive 自动升预算重试 → 多次触顶才最终报错，形成「Guard 硬判定 + Adaptive 软恢复」接力。

### 缺谁都照样运行（四象限验收口径）
| Guard | Adaptive | 行为 |
|---|---|---|
| 有 | 有 | Guard 判定耗尽（length/stall/断流）→ Adaptive 升预算重试，任务内持续生效 → 触顶 ceiling 放弃才报错 |
| 有 | 无 | 现状行为，零变化 |
| 无 | 有 | 协议 length 帧兜底触发 Adaptive 升预算重试 → 放弃抛错；stall/断流退回瞬时重试语义（无 length 判定） |
| 无 | 无 | length 帧当正常完成（guard 出现前的旧行为），不崩 |

### Adaptive 预算策略
- 触发：①Guard 判定的耗尽异常（字符串特征「模型输出已达上限」）；②协议级 `finish_reason=length` 帧（无 Guard 插件时兜底，两者命中任一即触发）。
- 升级：`budget = min(base × multiplier^attempt, ceiling)`；重试零延迟重订阅（不加人工退避，避免无谓等待）。
- **任务级持续生效**：升级后的 budget 保存在 advisor 实例字段（per-run，主/子 agent 各自隔离），**本次任务后续所有轮次均用增大后的值**；回落：连续 N 轮实际输出（usage `completionTokens`）< 当前预算 × 低水位 → 衰减回 base（防预算单调膨胀）。
- **ceiling 硬上限 = 262144（256K tokens）**：当前主流模型最大输出上限——GPT-5.2 / o3 系列 256K、Claude 4.5 Sonnet 128K、Gemini 3 Pro 128K、GLM-5.3 128K、DeepSeek-V3.2 64K——256K 是 2025 年公开商用模型输出上限的包络值，只防失控不追求贴合各家；超出模型真实上限时厂商返回 400，由 Adaptive 捕获 400 类错误**一次性回退到触发升级前的上一个 budget**并停止继续上调（简单收敛，不反复试探）。
- 配置：`worker.limits.adaptive-max-tokens.{enabled, ceiling(默认 262144), multiplier(默认 2.0), max-retries(默认 2), fallback-ratio(默认 0.5), fallback-rounds(默认 3)}`；模型级可用 `params.maxTokensCeiling` 覆盖 ceiling（厂商真实上限，如 Claude 系填 131072）。**enabled=false 或模型未配 base maxTokens 时直通**（无基线无从升级）。

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
    - 验收标准：`AdaptiveMaxTokensAdvisor`（Call+Stream，order=+250，在 Guard +300 外侧）+ Provider + `AdaptiveBudgetExhaustedException` + WorkerProperties 新增 `worker.limits.adaptive-max-tokens.*`（ceiling 默认 262144）；单测覆盖：Guard 耗尽异常触发升级重试、协议 length 帧兜底触发、budget 任务内持续生效（第二轮直接用升值）、达 ceiling 放弃抛错、400 回退上一档、低水位回落、未配 base maxTokens/enabled=false 直通
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
- Adaptive(+250) 在 Guard(+300) 外侧：包装视角 Adaptive 先执行、内层 Guard 先收到请求；Guard 的耗尽异常在响应/错误回传路径上先到达 Adaptive 的 onErrorResume，被其消费升级重试——**位置即协议**，无需类型耦合（识别用 Guard 错误文案固定前缀「模型输出已达上限」）。
- 旧风险消除：原方案 Adaptive 在 Guard 内侧重发会与 stall 计时相互干扰；新位置 Adaptive 重发的是完整新请求（外层重新过 Guard），无静默期问题。
- 字符串匹配的脆弱性：Guard 错误文案前缀是该协议信号的契约，两插件任一方改动文案需同步——已在计划中标注为「跨插件协议信号」，Guard javadoc/文档需注明该文案前缀是对外契约不可随意改动。
- 配置新增走"模型级 params 优先、全局 limits 兜底"既有口径，与去池化方案（plan-core-depooling.md §5.1）及 Phase 6 热更新正交，互不牵扯。
- 红线自检：两 advisor 均薄实现（Call/StreamAdvisor，不自建工具循环/聚合）；一个插件一个功能；复用 ChatClient/Advisor 生态。
