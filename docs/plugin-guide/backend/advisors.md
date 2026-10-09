---
title: Advisor 与模型增强扩展点
nav_order: 6
parent: backend
has_children: false
---

# Advisor 与模型增强扩展点

**一句话定位**：本篇讲挂在「模型调用链」与「模型构建」上的 6 个后端扩展点——向 agent 链注入 Advisor（`AdvisorProvider`）、在模型工厂构建期介入（`ChatModelEnhancer`）、替换 token 估算器（`TokenEstimator`）、贡献 skill（`SkillContributor`）、提供搜索后端（`SearchProvider`）、参与授权决议链（`AuthorizationHandler`）。核心交付物是 **Advisor order 坐标图**（§2）——想给 agent 链插一个自己的 Advisor，先看那张表再选位置。

注册方法全集见 [plugin.json 字段参考](../plugin-manifest.md) 与后端总览；本篇 6 个 SPI 的注册入口都是 `activate(ctx)` 里的 `ctx.register*` 一行。

| SPI | 包 | 注册方法（`WorkerPluginContext.java` 行号） | worker 侧注册表 | 消费点（详见各节） | 内置范例插件 |
|---|---|---|---|---|---|
| `AdvisorProvider` | `spi` | `registerAdvisorProvider`（:45） | `AdvisorProviderRegistry` | `AgentBuilder` 每轮装配聚合 | 11 个插件（12 个 Advisor，§2 全表） |
| `ChatModelEnhancer` | `model` | `registerChatModelEnhancer`（:66） | `ChatModelEnhancerRegistry` | `ChatModelFactory` 构建期委托 | model-pool（唯一） |
| `TokenEstimator` | `spi` | `registerTokenEstimator`（:63） | 无（直接替换 `WorkerServicesImpl` 持有实例） | `WorkerServices.tokenEstimator()` 的全部调用方 | model-rate-limit |
| `SkillContributor` | `skill` | `registerSkillContributor`（:60） | `SkillContributorRegistry` | `SkillAdvisor` 合流进 system prompt + `SkillSlashProvider` 并入 `/` 菜单 | subagent（唯一） |
| `SearchProvider` | `spi` | `registerSearchProvider`（:51） | `SearchProviderRegistry` | 统一 `search` 聚合（内置 `file-content`/`file-name`/`task` 三 provider 与插件 provider 同权，按 `order` 升序聚合）；能力接口 `SuggestionProvider`（mention.query，`SearchProvider` 子接口）（§6） | **无** |
| `AuthorizationHandler` | `permission` | `registerAuthorizationHandler`（:54） | `AuthorizationHandlerRegistry` | `GrantRegistry` 授权决议链 | ai-review、unattended |

所有注册方法的 worker 实现（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/WorkerPluginContextImpl.java:131,141,146,161,171,177`）都只是往注册表 `add` 一行（TokenEstimator 例外：调 `WorkerServicesImpl.replaceTokenEstimator` 原位换实例）。

## 1. AdvisorProvider —— 向 agent 链注入 Advisor

### 1.1 接口定义（原码摘录）

`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/AdvisorProvider.java`：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/AdvisorProvider.java:11-33
public interface AdvisorProvider {

    /** 插件 id。 */
    String pluginId();

    /** Advisor 顺序（数字，Spring @Order 语义）。 */
    int order();

    /** 为指定任务创建 Advisor 实例。 */
    Advisor create(AdvisorContext ctx);

    /** 可选：Advisor 是否适用于此任务。 */
    default boolean appliesTo(AdvisorContext ctx) { return true; }
}
```

`AdvisorContext extends ExecContext`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/AdvisorContext.java:17-42`），ExecContext 槽位表见 [工具与沙箱扩展点](tools-and-sandbox.md) §5.2，advisor 专属槽位：

| 槽位 | 含义 | 典型用途 |
|---|---|---|
| `agentId()` | 本 agent ID（主 agent = mainAgentId，子 agent = 其 agentId） | `appliesTo` 按主/子 agent 分流 |
| `configId()` | 本 agent 实际解析所用的模型配置 ID（per-agent 语义；审议 agent 覆盖模型时与 `snapshot().configId()` 不同） | TokenCalibrationAdvisor 按它隔离校准系数 |
| `toolCallingManager()` | 共享的 `ToolCallingManager` 单例 | 自定义 ToolCallingAdvisor 子类的构造参数 |
| `agentEntity()` | 当前 agent 上下文（plugin-api 契约接口，读会话内存等） | AgentsMdAdvisor 取 `execution().workspaceRoot()` |

### 1.2 与 Spring AI Advisor 的关系（复用框架，不自搓）

一句话：`Advisor`（`org.springframework.ai.chat.client.advisor.api.Advisor`）是 **Spring AI 的调用拦截原语**（`extends org.springframework.core.Ordered`，唯一抽象方法 `getName()`；`CallAdvisor`/`StreamAdvisor` 分别拦 call/stream 两条路径），而 `ToolCallingAdvisor` 是框架自带的**工具循环 advisor**（同时实现 `CallAdvisor`+`StreamAdvisor`+`ToolAdvisor`，持有 `ToolCallingManager` 驱动「模型 ↔ 工具」递归循环，`DEFAULT_ORDER` = `HIGHEST_PRECEDENCE + 300`）。worker 不手搓任何循环——`AgentRunner` 只把 `Prompt` 交给 `ChatClient`，工具循环整体复用 `ToolCallingAdvisor`，事件发射类增强靠**继承它并重写受保护 hook**（`doBeforeStream` / `doAfterStream` / `doGetNextInstructionsForToolCallStream` 等，范例 `every-agent-worker/src/main/java/dev/everyagent/worker/agent/WorkerToolEventAdvisor.java:66`（继承声明）与 `:92-95`（`adviseStream` 覆写搭桥））。这是 §14.8 红线的硬要求（原文见本篇 §8）。

### 1.3 消费链路：Advisor 怎么挂上链

```
插件 activate(ctx)
  └─ ctx.registerAdvisorProvider(p) → AdvisorProviderRegistry.register
     （WorkerPluginContextImpl.java:131；CopyOnWriteArrayList，AdvisorProviderRegistry.java:14-24，
       注册零校验——order 冲突/越界都不会被拒绝，全靠插件自己选位）
每轮 agent 装配（AgentBuilder.build()，per-run）
  ├─ 创建 AdvisorContextImpl（entity + tcm + configId）            AgentBuilder.java:301
  ├─ registry.getProviders() 按 order() 升序排序 → appliesTo 过滤 → create()
  │    非空产物依次并入 advisors 列表                              AgentBuilder.java:303-312
  ├─ 可选 .advisors(list, ModifyMode) 增删改（调用方按需覆盖）      AgentBuilder.java:314-316
  └─ ChatClient.builder(chatModel).defaultAdvisors(advisors).build()  AgentBuilder.java:319-321
运行（AgentRunner.run）
  └─ chatClient.prompt(prompt).stream() —— Spring AI 把 advisors 按 getOrder()
     升序折叠成链：数值最小者最外层（先见请求、后见响应）
```

worker 内置的 6 个 Advisor 同样经 `BuiltInAdvisorProviders`（`@PostConstruct`）注册进**同一个注册表**（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/adapters/BuiltInAdvisorProviders.java:44-67`）——插件 Advisor 与 worker 内置 Advisor 完全平权。主 agent 与子 agent 共用同一条装配链，仅 `agentId` 不同（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.3；分流靠 `appliesTo`，见 §1.4）。

### 1.4 范例：最小 AdvisorProvider（agents-md）

```java
// every-agent-plugins/agents-md/src/main/java/dev/everyagent/plugin/agentsmd/AgentsMdAdvisorProvider.java:15-33
public class AgentsMdAdvisorProvider implements AdvisorProvider {

    @Override public String pluginId() { return "builtin.agents-md"; }

    @Override public int order() { return Ordered.HIGHEST_PRECEDENCE + 60; }  // 选位见 §2 全表

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        ExecContext exec = a.execution();
        return new AgentsMdAdvisor(exec.workspaceRoot());   // 每 run 新建实例：per-run 状态放 advisor 体内
    }
}

// 注册（AgentsMdPlugin.activate）：ctx.registerAdvisorProvider(new AgentsMdAdvisorProvider());
```

`appliesTo` 分流范例（只挂主 agent）：`DialogInsertAdvisorProvider.appliesTo` 比较 `ctx.agentId()` 与 `TaskRuntime.mainAgentId()`，非主 agent 直接不挂（`every-agent-plugins/task-input-queue/src/main/java/dev/everyagent/plugin/inputqueue/DialogInsertAdvisorProvider.java:37-39`——子 agent 对话是一次性嵌套，不接收任务队列输入）。

### 1.5 常见坑

| 坑 | 说明 | 证据 |
|---|---|---|
| order 写成绝对正数/0 | 内置链全部是 `HIGHEST_PRECEDENCE + N`（约 -21.47 亿），绝对 0 会排到整条链**最内端**（数值最大）；TokenCalibrationAdvisor 就是现成案例（§2.5） | §2.5；`TokenCalibrationAdvisor.java:56-59` |
| provider 与 advisor 的 order 不一致 | `AgentBuilder` 按 provider 的 `order()` 排序聚合，Spring AI 链又按 advisor 自己的 `getOrder()` 重排——两个值**必须写同一个数**（仓库内所有实现均成对一致，如 `RateLimitAdvisorProvider.java:31-33` 与 `RateLimitAdvisor.java:63`） | `AgentBuilder.java:303-312`；`DefaultAroundAdvisorChain`（spring-ai-client-chat 2.0.1）`reOrder()` 用 `OrderComparator` 重排 |
| 在 provider 里存 per-run 状态 | `create()` 每次 agent 装配都会调用，provider 是长生命周期对象；per-run 状态放 `create` 里 new 出的 advisor 实例字段（git/file-change 均如此，`GitAutoSyncAdvisorProvider.java:12` 注释） | `AgentBuilder.java:303-312` |
| 一个 advisor 塞多个职责 | §14.8 红线：**一个 Advisor 只负责一个功能**；要挂钩工具循环的增强必须继承 `ToolCallingAdvisor` 重写受保护 hook，**不得另起一层包裹**或重复实现递归循环 | [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §14.8（本篇 §8 原文） |
| advisor 里 import 任务域类型 | 横切层只准取 ExecContext 槽位（`subjectId()/snapshot()/emitter()/terminal()`），禁止 import `TaskEntry/TaskRuntime/TaskInfo` | §14.11；[工具与沙箱扩展点](tools-and-sandbox.md) §5.1 |

## 2. Advisor order 坐标图（核心交付物）

### 2.1 数值语义（两个锚点，全部实测）

- **`Ordered.HIGHEST_PRECEDENCE`（下记 `HP`）= `Integer.MIN_VALUE` = -2147483648**。仓库内 order 一律写成 `HP + N` 形态，`N` 是相对偏移。
- **`ToolCallingAdvisor.DEFAULT_ORDER`（下记 `TCA`）= HP + 300 = -2147483348**。来源：spring-ai-client-chat **2.0.1** jar（`javap -constants org.springframework.ai.chat.client.advisor.ToolCallingAdvisor` 实测）；仓库源码多处注释与之互证（`SkillAdvisor.java:100`）。
- **方向**：升序 = 从外到内。数值小者最外层（先见请求、后见响应），数值大者紧贴模型。依据：Spring AI `DefaultAroundAdvisorChain.Builder.reOrder()` 用 `OrderComparator.sort` 升序排布，`nextCall/nextStream` 从队头 `pop()`（spring-ai-client-chat 2.0.1 sources jar，`DefaultAroundAdvisorChain.java`）；仓库源码注释同口径（`AgentStatusAdvisor.java:36`「+5 全链最外层」）。

### 2.2 内置 Advisor 链完整 order 表

「来源」栏：worker = `BuiltInAdvisorProviders` 注册的适配器；其余为插件 `activate()` 自行注册。所有条目的 provider `order()` 与 advisor `getOrder()` 数值一致。

| # | order 表达式 | 绝对值 | Advisor | 来源 | 职责 | 证据（文件:行号） |
|---|---|---|---|---|---|---|
| 1 | `HP + 5` | -2147483643 | AgentStatusAdvisor | worker | agent.* 生命周期事件（全链最外层，per-run 一次） | `every-agent-worker/src/main/java/dev/everyagent/worker/agent/AgentStatusAdvisor.java:66-69` |
| 2 | `HP + 10` | -2147483638 | RoundIndexAdvisor | worker | 轮次索引开/闭轮落盘 + 耗时 | `every-agent-worker/src/main/java/dev/everyagent/worker/task/RoundIndexAdvisor.java:59-62` |
| 3 | `HP + 50` | -2147483598 | SystemInfoAdvisor | system-info | 系统信息注入 prompt | `every-agent-plugins/system-info/src/main/java/dev/everyagent/plugin/sysinfo/SystemInfoAdvisorProvider.java:38-40` |
| 4 | `HP + 60` | -2147483588 | AgentsMdAdvisor | agents-md | AGENTS.md 约束注入 | `every-agent-plugins/agents-md/src/main/java/dev/everyagent/plugin/agentsmd/AgentsMdAdvisorProvider.java:23-25` |
| 5 | `HP + 100` | -2147483548 | SkillAdvisor | worker | skill 渐进式披露索引（含插件贡献，§5） | `every-agent-worker/src/main/java/dev/everyagent/worker/skill/SkillAdvisor.java:99-102` |
| 6 | `HP + 140` | -2147483508 | GitAutoSyncAdvisor | git | 本轮开始前 git 自动同步 | `every-agent-plugins/git/src/main/java/dev/everyagent/plugin/git/GitAutoSyncAdvisorProvider.java:28-30` |
| 7 | `HP + 150` | -2147483498 | SlashTokenResolveAdvisor | worker | composer opaque token 解析改写 | `every-agent-worker/src/main/java/dev/everyagent/worker/skill/SlashTokenResolveAdvisor.java:57-59` |
| 8 | `HP + 160` | -2147483488 | FileAttachmentAdvisor | worker | metadata.attachments → 末位多模态消息 | `every-agent-worker/src/main/java/dev/everyagent/worker/attachment/FileAttachmentAdvisor.java:54-56` |
| 9 | `TCA`（= `HP + 300`） | -2147483348 | WorkerToolEventAdvisor | worker | **工具循环本体** + delta/thinking/message/usage 事件 | `every-agent-worker/src/main/java/dev/everyagent/worker/agent/WorkerToolEventAdvisor.java:80` |
| 10 | `HP + 301` | -2147483347 | FileChangeAdvisor | file-change | 从模型流识别文件变更（工具 advisor 内层） | `every-agent-plugins/file-change/src/main/java/dev/everyagent/plugin/filechange/FileChangeAdvisorProvider.java:45-47` |
| 11 | `TCA + 30`（= `HP + 330`） | -2147483318 | DialogInsertAdvisor | task-input-queue | 主 agent「插入对话」队列 drain | `every-agent-plugins/task-input-queue/src/main/java/dev/everyagent/plugin/inputqueue/DialogInsertAdvisorProvider.java:34` |
| 12 | `TCA + 100`（= `HP + 400`） | -2147483248 | EmptyResponseRetryAdvisor | empty-response-retry | 空响应重调 | `every-agent-plugins/empty-response-retry/src/main/java/dev/everyagent/plugin/emptyretry/EmptyResponseRetryAdvisorProvider.java:30-32` |
| 13 | `TCA + 200`（= `HP + 500`） | -2147483148 | TransientErrorRetryAdvisor | transient-error-retry | 瞬时错误退避重试 | `every-agent-plugins/transient-error-retry/src/main/java/dev/everyagent/plugin/transientretry/TransientErrorRetryAdvisorProvider.java:30-32` |
| 14 | `TCA + 250`（= `HP + 550`） | -2147483098 | AdaptiveMaxTokensAdvisor | adaptive-max-tokens | 自适应输出预算 | `every-agent-plugins/adaptive-max-tokens/src/main/java/dev/everyagent/plugin/adaptivemaxtokens/AdaptiveMaxTokensAdvisorProvider.java:37-39` |
| 15 | `TCA + 300`（= `HP + 600`） | -2147483048 | ModelLengthGuardAdvisor | model-length-guard | 输出预算耗尽护栏（finish_reason=length） | `every-agent-plugins/model-length-guard/src/main/java/dev/everyagent/plugin/modellengthguard/ModelLengthGuardAdvisorProvider.java:38-40` |
| 16 | `TCA + 400`（= `HP + 700`） | -2147482948 | ContextCompressionAdvisor | context-compression | 上下文压缩（只改发给模型的视图） | `every-agent-plugins/context-compression/src/main/java/dev/everyagent/plugin/contextcompression/ContextCompressionAdvisorProvider.java:30-32` |
| 17 | `TCA + 500`（= `HP + 800`） | -2147482848 | RateLimitAdvisor | model-rate-limit | 模型请求限流（rpm/并发/tpm） | `every-agent-plugins/model-rate-limit/src/main/java/dev/everyagent/plugin/modelratelimit/RateLimitAdvisorProvider.java:31-33` |
| 18 | `0`（**绝对值**，非 HP 系） | 0 | TokenCalibrationAdvisor | model-rate-limit | token 估算 EMA 校准（⚠️ 见 §2.5） | `every-agent-plugins/model-rate-limit/src/main/java/dev/everyagent/plugin/modelratelimit/TokenCalibrationAdvisor.java:56-59,180-182` |

worker 适配器的注册顺序（先/后）不影响链位置——`AdvisorProviderRegistry` 输出后统一按 `order()` 排序（`BuiltInAdvisorProviders.java:22-24` 注释明示）。

### 2.3 数轴（HP 相对偏移坐标系）

```
 外层（先见请求 / 后见响应）                                    内层（紧贴模型 HTTP 调用）
 HP+0 ─5─ 10 ────── 50 ─ 60 ────── 100 ─ 140 ─ 150 ─ 160 ─ 300 ─ 301 ─ 330 ─ 400 ─ 500 ─ 550 ─ 600 ─ 700 ─ 800 ─────── 0(绝对)
  │   │   │          │   │          │    │    │    │    ▲TCA▲   │   │    │    │    │    │    │    │           ▲
  │   │   │          │   │          │    │    │    │  工具循环 │   │    │    │    │    │    │    │      TokenCalibration
  │   │   │          │   │          │    │    │    │  (事件发射)│   │    │    │    │    │    │    │      order=0 绝对值，
  │   │   │          │   │          │    │    │    │           │   │    │    │    │    │    │    │      数值最大 ⇒ 实际最内
  空档 状态 轮次      系统 AGENTS     skill git  token 附件  FileChg 插入  空响  瞬时  自适  长度  压缩  限流
       事件 索引      信息 .md        索引  同步  解析  注入  变更跟踪 对话  重试  重试  预算  护栏        （HP 系最内）
```

读法：同一数值锚点上的 advisor 按注册顺序并列；插件想插空档就取两锚点之间的任意偏移（int 精度足够，如 `HP + 120`）。

### 2.4 `[420, 850]` 的源码验证结论（与计划口径不符，如实更正）

**Advisor 链不存在 `[420, 850]` 禁插段。** 该区间在源码里的真实归属是**任务生命周期洋葱**（`TaskLifecycleNode`，float order）：

- `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/TaskLifecycleRegistry.java:30-47`：`CRITICAL_SECTION_MIN = 420f` / `CRITICAL_SECTION_MAX = 850f`——「临界段 order 范围：插件节点不允许落入此区间（内置节点的锁内连续段）」，外部插件节点 order ∈ [420, 850] 时**打 WARN 并拒绝注册**。
- 该段语义（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.14.1）：order ∈ [420, 850] 的连续上行段共享一次 `synchronized(taskLock)`，对外表现为 order=850 的单一链位置。
- Advisor 侧对比：`AdvisorProviderRegistry.register` 只 `add`，**零校验、零拒绝**（`AdvisorProviderRegistry.java:19-22`）；Advisor order 是 int（Spring `@Order` 语义），与生命周期的 float order 是两套互不相干的坐标系。

⇒ 插件选 Advisor 位置时**无需**避让任何区间，只需避开 §2.2 表中已占用的锚点；`[420, 850]` 禁插段是 `TaskLifecycleNode` 的约束，详见 [任务生命周期与 RPC](task-and-rpc.md)。

### 2.5 ⚠️ TokenCalibrationAdvisor 的 order=0 是已知不一致

`TokenCalibrationAdvisor` 自带注释称「order = 0（核心基础设施层），在 advisor 链最外层」（`TokenCalibrationAdvisor.java:29-31,56-59`），Provider 也是 `return 0`（`:180-182`）。但按 §2.1 的排序语义，绝对值 0 **大于**全部 `HP + N` 值 ⇒ 它实际排在整条链**最内端**（比 RateLimitAdvisor `HP + 800` 更内）。成因推测：`BuiltInAdvisorProviders` 的分区注释以 HP 相对偏移描述「核心基础设施（0─99）」（`BuiltInAdvisorProviders.java:52`），该类从 worker 迁入插件时把 0 写成了绝对值而非 `HP + 0`（推测，未实测运行时表现）。功能上不受影响——最内层同样能看到每轮模型调用的原始 chunk，校准目的仍达成；但**插件不要模仿这种写法**，一律用 `HP + N` / `TCA + N` 对齐坐标系。

### 2.6 可用空档推荐

分区约定（`BuiltInAdvisorProviders.java:51-66` 注释，均为 HP 相对偏移）：`0─99` 核心基础设施 / `100─199` 功能 / `200─399` 事件发射与文件跟踪 / `400─599` 重试与护栏 / `800─999` 终层。按功能就近选空档：

| 你想做什么 | 推荐区间（HP 偏移） | 理由 |
|---|---|---|
| 注入 system 级上下文（prompt 前置信息） | `+15~+45`、`+65~+95` | 生命周期/轮次信号之后、skill 索引前后；对外层可见性无破坏 |
| 改写用户输入 / 注入会话上下文 | `+165~+299` | 附件注入（+160）之后、工具循环（+300）之前——进入模型前的最后一站外层 |
| 挂进工具循环内侧（每轮工具迭代都过） | `+302~+329`、`+340~+399`、`+410~+490`、`+510~+540`、`+560~+590`、`+610~+690`、`+710~+790` | 与重试/护栏/压缩带交错；注意与既有锚点间隔 ≥10，留后续内置位 |
| 比限流更内（最先见响应原文） | `+810~+999` | HP 系最内空档；再往内只有绝对值 > 0 一途（不建议，见 §2.5） |
| 继承 ToolCallingAdvisor 做事件/工具循环挂钩 | 直接覆写 `TCA` 同值或紧邻内侧 | 参照 `WorkerToolEventAdvisor`；红线要求继承重写 hook，不得另起包裹层 |

## 3. ChatModelEnhancer —— 模型构建期介入

**增强什么**：不碰 Advisor 链，而是在 `ChatModelFactory` 构建 `ChatModel` 阶段整体接管——当模型配置的 `provider` 被某个 enhancer 认领（`supports` 返回 true）时，工厂委托该插件构建**组合 ChatModel**（如模型池容灾：多个成员模型按序切换），并采用主成员的完整 options 快照（上下文压缩等 advisor 读 `prompt.options` 仍拿到主模型参数）。

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/model/ChatModelEnhancer.java:12-22
public interface ChatModelEnhancer {
    String id();                                  // 增强器 id（日志/调试）
    boolean supports(String provider);            // 是否认领该 provider
    EnhancedChatModel enhance(EnhancerContext ctx); // ctx 提供 poolConfig() + resolveMember(configId) 复用工厂构建成员
}
```

**消费链路**：`ChatModelFactory.buildAgentModel` 先 `enhancerRegistry.find(provider)`——无人认领则照常构建 `OpenAiChatModel`；有人认领则委托 `enhance()`，插件未注册而配置又指向该 provider 时启动期直接抛 `IllegalStateException`（`every-agent-worker/src/main/java/dev/everyagent/worker/config/ChatModelFactory.java:79-99`）。注册表 `find` 按 `supports` 顺序取第一个命中（`ChatModelEnhancerRegistry.java:28-44`）。

**范例（唯一实现者）**：model-pool——`ModelPoolEnhancer.supports` 只认 `"model-pool"`（`every-agent-plugins/model-pool/src/main/java/dev/everyagent/plugin/modelpool/ModelPoolEnhancer.java:37-39`），注册即一行 `ctx.registerChatModelEnhancer(new ModelPoolEnhancer())`（`ModelPoolPlugin.java:26`）。用户把某模型配置的 `provider` 写成 `model-pool` 即激活组合模型容灾（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.4）。

## 4. TokenEstimator —— 替换 token 估算器

**估计什么**：无精确 tokenizer 场景下的文本 token 数 + 按模型配置（configId）隔离的在线校准。两个消费方：`ModelLengthGuardAdvisor`（输出预算耗尽判定）与模型限流器 tpm 记账（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.4.1）。

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/TokenEstimator.java:14-52（签名节选）
public interface TokenEstimator {
    long estimate(String text, String configId);   // 估算 token 数（用 configId 的校准系数，无记录则 factor=1.0）
    void calibrate(String configId, long estimatedTokens, long actualTokens); // 真实 usage EMA 校准
    double factorOf(String configId);             // 当前校准系数（诊断用）
    long sampleCountOf(String configId);          // 校准样本数（诊断用）
}
```

**默认实现与覆盖关系**：worker 内置 fallback `SimpleTokenEstimator`（`@Component`，CJK≈1 token、其余≈4 字符 1 token，factor 恒 1.0、不校准；`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/adapters/SimpleTokenEstimator.java:16-58`）。`ctx.registerTokenEstimator(estimator)` **原位替换** `WorkerServicesImpl` 持有的实例（`WorkerPluginContextImpl.java:171-174` → `WorkerServicesImpl.replaceTokenEstimator`，`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/WorkerServicesImpl.java:84-86`）；消费方经 `ctx.services().tokenEstimator()` 取**当前生效**实例（plugin-api `WorkerServices.java:38`）——model-length-guard 插件持 `AtomicReference` 包一层，后注册的估算器自动跟随（`ModelLengthGuardAdvisorProvider.java:19-20,46`）。

**范例（rg `registerTokenEstimator` 唯一命中）**：model-rate-limit 的 `BuiltinTokenEstimator`——EMA 校准系数按 configId 隔离、收敛（默认 2%）后停采、漂移（默认 5%）自动恢复，持久化 `<homeDir>/model-rate-state.json` 重启接续（`BuiltinTokenEstimator.java:34-45`；阈值配置 `WorkerProperties.java:517-537`）。注册三件套共享同一实例：估算器 + TokenCalibrationAdvisor（§2.5）+ RateLimitAdvisorProvider（`ModelRateLimitPlugin.java:33-46`）。

## 5. SkillContributor —— 贡献 skill

**机制**：接口只声明 skill 元数据，知识注入交给 `SkillAdvisor`（红线：skill 的知识注入与工具授权一律交给 advisor/tool 原语，`SkillContributor.java:14-20`）。

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/skill/SkillContributor.java:14-25
public interface SkillContributor {
    String pluginId();                 // 与 plugin.json 一致
    List<PluginSkill> skills();        // id/title/description/knowledgePath/toolIds（PluginSkill.java:19-31）
}
```

**与 worker skill 扫描如何合流**：`SkillAdvisor.mergedSkills()`（HP+100）每轮 `before()` 合并两条源——`BuiltInSkills.getActiveSkills()`（worker 内置主动 skill）优先，`SkillContributorRegistry.getSkills()`（全部插件贡献，按注册序拼接）随后，按 skill id 去重（内置赢）（`every-agent-worker/src/main/java/dev/everyagent/worker/skill/SkillAdvisor.java:67-90`）。合并结果以「标题 + 一句话描述 + 知识包绝对路径」的索引 `SystemMessage` 注入 system 区，正文由 AI 按需 `read_file`（渐进式披露）。知识包目录的沙箱可见性由 worker 侧统一登记：`WorkerBeanConfiguration.skillAdvisor` 把 `skillsDir` 以 READ_WRITE 登记挂载意图（`every-agent-worker/src/main/java/dev/everyagent/worker/config/WorkerBeanConfiguration.java:63-70`），注入路径按生效沙箱后端翻译（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.12「skill 知识包路径的沙箱注入」）。

**`/` 菜单侧同源并入**：`SkillSlashProvider.load()` 按「内置全部（主动+被动）→ 插件 SPI → 外部扫描」三路合并（见下方 ⚠️ 第 1 条），两条通道自此等价——SPI 贡献的 skill 同时对模型（system prompt 索引）与用户（`/` 菜单条目，经 `slash.list` 出网）可见。

**范例（rg 唯一命中）**：subagent 的 `SubAgentSkillContributor`——把 `classpath:skill/agent-dispatch.md` 物化到 `<skillsDir>/agent-dispatch/skill.md`（幂等：已存在且大小一致跳过；路径越界跳过），再返回 `PluginSkill("agent-dispatch", "子 Agent", …, toolIds=[run_agent/list_agents/wait_agents/stop_agent])`（`every-agent-plugins/subagent/src/main/java/dev/everyagent/plugin/subagent/SubAgentSkillContributor.java:38-83`）；`init()` 物化在注册后显式调用（`SubAgentPlugin.java:43-45`）。

⚠️ **一处与直觉不符的现状**（如实登记）：
1. ~~`/` 菜单数据源不含 `SkillContributorRegistry`~~ **（已并入）**：`SkillSlashProvider` 现按「内置（`BuiltInSkills.getAllSkills()`）→ 插件 SPI（`SkillContributorRegistry.getSkills()`）→ 外部（`ExternalSkillScanner.scan()`）」三路合并进 `/` 菜单（`every-agent-worker/src/main/java/dev/everyagent/worker/slash/SkillSlashProvider.java` 的 `load()`），同 id 去重、优先级 **内置 > 插件 SPI > 外部扫描**——SPI 是插件自己的声明（title/description 完整），外部扫描捞到同 id 只是知识包物化的副产品，不重复出菜单。插件条目与内置同 group（Skills）/icon/opaque token（`system.skill`，选中执行路径与内置完全一致），仅副标题带「插件 · 」前缀区分来源；无插件贡献时菜单与两路合并时代完全一致（零回归）。subagent 的 `agent-dispatch` 现经 SPI 以完整标题（「子 Agent」）进菜单（此前靠 `ExternalSkillScanner` 以目录名形态捞进菜单）；其知识包物化到 skillsDir 的行为**保留**——那是 AI 能 `read_file` 知识包的必要条件，与进菜单与否无关。
2. `skill.md` 的 id 规则与菜单副标题提取等扫描约定见 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.12「skill 目录结构与外部 skill」；本篇不展开。

## 6. SearchProvider —— 搜索后端（统一 search 聚合 + mention.query 能力接口）

**机制**：worker 只提供**一个**统一 `search` RPC（取代旧 `fs.search` / `fs.find` / `task.search`，[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §5.5 / §8.5 为契约口径），核心**零类型感知**——只「遍历 `SearchProviderRegistry`（可选按 `kinds` 过滤）→ 触发 provider → 聚合」。插件经 `ctx.registerSearchProvider` 注册的搜索后端（ElasticSearch/向量检索等）与**内置三引擎**（`file-content` / `file-name` / `task`，均实现 `SearchProvider`、启动装配期注册）**同权**：各 provider 按 `order()` 升序（同 order 保持注册先后）参与聚合，按位置键去重（`file-content` 沿用 `path+lineNumber+matchIndex` / `task` 沿用 `taskId+roundIndex+field+matchIndex` / `file-name` 按 `path`）。**核心不解释** `pattern` 与不透明 `filters` 袋，也不含 `maxResults`/`isRegex`/`include`/`exclude`/`scope` 等具名字段——它们是各 provider 自己声明的过滤字段（§6.1）。实现入口在 worker 侧的 `SearchProviderRegistry.getProviders()` + `SearchProviderInvoker`（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/SearchProviderRegistry.java`）。

**无害性保证**（写插件时可以依赖的行为契约）：

- 注册表为空 → 统一 `search` 行为与无插件时**完全一致**（聚合入口直接原样返回内置结果）；
- provider 返回 `null`/空列表 → 不加任何项、不报错；
- 单个 provider 抛异常 → 仅 WARN 跳过，其余 provider 与整体应答不受影响；
- 内置 rg 不可用但注册了 provider → 跳过内置 rg、仅聚合 provider 结果（无 provider 时保持原「rg 不可用」可读报错）。

**实现要点**（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/SearchProvider.java:22-65`）：

- `id()`（providerId）+ `order()`（`default 0f`，可选覆写）+ `kinds()`（`default` 空集合 = 不参与统一 `search`，供核心按请求 `kinds` 数据过滤）+ `search(SearchRequest)`（**唯一统一入口**，`default` 空实现）；`SearchRequest(workspaceId, workspaceRoot, pattern, filters, kinds)` 带工作区 id（工作区未注册进注册表时可能为 null）+ 工作区根 + 搜索词 + 不透明 `filters` 袋 + 请求 kinds；**过滤字段（`isRegex`/`caseSensitive`/`wholeWord`/`include`/`exclude`/`scope`…）由各 provider 自持、经 `filters` 袋按 `${kind}.${field}` 命名空间透传**，**核心 RPC 契约无 `maxResults`**（结果上限由 provider 自持）。
- `order()`（`default 0f`，可选覆写）：聚合顺序权重——**值小者先执行、结果先并入聚合**（升序 = 执行/返回序，与 `AuthorizationHandler#order()` 坐标约定一致，float 允许任意插位）。见下「顺序与生命周期」。
- 结果形状：`search` 返回 `SearchResult(items, truncated)`，项为通用 `SearchResultItem(kind, providerId, score, positionKey, fields)`；`fields` 为展示字段袋（`file-content` 带 `path/lineNumber/line/matchIndex/matchText`，`task` 带 `taskId/title/status/roundIndex/field/line/matchIndex/matchText`，`file-name` 只带 `path`），核心原样透传（`task` 的 `line` 为命中字段的**干净文本**，行内定位用 `matchIndex`）；`positionKey` 为**该 kind 内的位置键、由 provider 自持**（`file-content` = `path+lineNumber+matchIndex`、`file-name` = `path`、`task` = `taskId+roundIndex+field+matchIndex`），核心按 `kind`+`positionKey` 去重；`truncated` = 本 provider 自身触顶（结果上限）标志，聚合方取各 provider 的「或」。结果项可选增补字段 `providerId`/`score` 语义见 §8.5（自带 `providerId` 则尊重不覆盖，否则由聚合方填 `id()`）。
- 典型场景：search-es（ElasticSearch）、search-vector（向量检索）等在工作区外维护索引的引擎，把索引命中补充进前端搜索结果。

**顺序与生命周期**（`order()` + 卸载自动反注册，`SearchProviderRegistry` 为实现侧）：

- **排序**：注册表在注册时按 `order()` 升序**有序插入**，同 order 保持注册先后（稳定排序）；统一 `search` 直接按该序列聚合，消费代码零排序逻辑。全部插件都不覆写 `order()` 时保持原有注册序，行为不变。插位建议：低权重（如 `-10f`）给「快而粗」的先返回引擎，高权重给「慢而全」的兜底引擎。
- **生命周期（卸载自动反注册）**：`WorkerPluginContextImpl` 按插件维护「已注册 providers」登记清单（每个上下文绑定唯一插件 id，即 pluginId → providers 映射）；worker 优雅关闭时 `PluginLoader` 在 `@PreDestroy` 逐插件调用 `deactivate()` 之后**兜底反注册**其注册的全部 SearchProvider（`SearchProviderRegistry.unregisterAll(Collection)` 批量移除，返回实际移除数），provider 不残留、重复调用幂等。注意：运行期 `plugin.disable` / `plugin.uninstall` 只改禁用名单/删除目录，**不触发运行时反注册**——已激活插件的贡献留在注册表直到 worker 重启（与 `PluginStateStore`「重启 worker 后生效」口径一致，契约见 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §8.5「registry 顺序与生命周期」）。排序与批量反注册行为由 `SearchProviderRegistryTest` / `WorkerPluginContextSearchUnregisterTest` 钉住。

**范例**：暂无插件注册（内置插件零使用）；聚合/去重/触顶/异常跳过行为由搜索相关单测（`SearchProviderRegistryTest` 等）的 StubProvider 用例钉住。**注意**：内置 `file-content` / `file-name` / `task` 三引擎本身就是按 `SearchProvider` 落地的内置 provider，插件 provider 与它们同权聚合——**不替换后端**。

### 6.1 能力接口扩展：SuggestionProvider（mention.query）

**机制**（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §8.5「能力接口扩展」）：`SuggestionProvider`（`suggest(SuggestRequest)`，供 `mention.query` 的 @ 建议增补）是 `SearchProvider` 的**子接口**，与基接口同住 spi 包、**同样经 `ctx.registerSearchProvider` 注册**——注册/反注册生命周期零改动（`WorkerPluginContextImpl` 的登记清单与 `unregisterAll` 兜底原样覆盖）。registry 按**接口分派**能力：`SearchProviderRegistry.getSuggestionProviders()` 从**同一份** order 有序存储按 `instanceof` 过滤（不另建第二份存储，order/快照语义与 `getProviders()` 一致）。**注意**：旧的 `FileNameSearchProvider`（`findFiles`）能力接口与 `SearchProviderRegistry.getFileNameProviders()` **已删除**——「文件名搜索」现只是内置 provider 的一个 kind（`file-name`），不再是独立能力接口。

- `mention.query` 增补语义：**仅搜索模式**（非空 query）在内置四档打分排序结果之后按 `order()` 升序追加，按 `kind`+`path` 去重（kind 缺省归一 `file`），总条数仍截断 10 条（mention 应答无 `truncated` 标志，与内置静默截断一致）；浏览模式（空 query 列目录顶层）不经 provider。实现：`SlashMethods.appendProviderSuggestions`。
- 护栏与统一 `search` 完全同款：单个 provider 抛异常/超出超时预算（`worker.search.provider-timeout-ms`，默认 0 不限时）仅 WARN 跳过（共用 `SearchProviderInvoker`）；未注册任何实现该能力接口的对象时零额外行为；只实现基接口的 provider 对该链路零影响（instanceof 过滤不命中）。

**实现要点**（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/SuggestionProvider.java`）：

- `SuggestionProvider extends SearchProvider`：只新增 `suggest(SuggestRequest)`；基接口的 `search` / `kinds` 用**缺省空实现**（不覆写即不参与统一 `search`，`kinds` 缺省空集合）；`id()` 仍为必实现（结果项聚合时作 `providerId` 兜底）。
- `SuggestRequest(workspaceId, workspaceRoot, query, path)`：`query` 为用户已输入的 @ 搜索词（非空，已 trim）、`path` 为浏览基准目录的工作区相对 posix 路径（缺省 `.` = 根）、`workspaceId` 在工作区未注册进注册表时可能为 null。
- `Suggestion(path, kind, providerId, score)`：`path` 为工作区相对 posix 路径（应答派生 `name`，不要求文件当前在磁盘上存在）；`kind` 缺省归一 `file`（去重键 `kind`+`path`）；可选增补字段 `providerId`（聚合时由 worker 填 `provider.id()`，自带则尊重不覆盖）与 `score`（仅排序提示）可空、null 时序列化省略；有 `Suggestion(path, kind)` / `Suggestion(path)` 两个便捷构造；建议项排序由 provider 自理——调用方按返回序并入聚合。

**示例**（只实现 `SuggestionProvider`，注册一次即可）：

```java
public final class RecentFilesPlugin implements SuggestionProvider {

    @Override
    public String id() { return "recent-files"; }

    @Override
    public float order() { return -10f; } // 「快而粗」的索引命中先返回

    // ---- mention.query 能力接口（search / kinds 用基接口缺省空实现，不参与统一 search） ----

    @Override
    public List<Suggestion> suggest(SuggestRequest req) {
        // basename 命中最近打开索引（含被 .gitignore 剪掉、rg 枚举不到的文件）
        return index.fuzzy(req.query(), 10)
                .stream().map(p -> new Suggestion(p)).toList(); // kind 缺省 file
    }
}

// activate() 里注册一次，mention.query 增补生效（反注册生命周期与基接口一致）：
ctx.registerSearchProvider(new RecentFilesPlugin());
```

聚合/去重/截断/异常跳过行为由 `SearchServiceTest`（统一 search 聚合用例）/ `SearchProviderRegistryTest`（聚合 + capabilityDispatch 用例）/ `SlashMethodsMentionProviderTest`（StubSuggester 用例）钉住。

## 7. AuthorizationHandler —— 授权决议链节点

**链位置与 PermissionGate（§7.8）的关系**：PermissionGate 责任链（路径检查 → 危险动词 → `AuthorizeCheck`）判定「这次操作需要授权」且 grant 未命中时，进入 `GrantRegistry.authorize`——它沿**授权决议链**遍历全部 `AuthorizationHandler`（按 order 升序折叠为嵌套链）：任一节点 ALLOW → 自动授权（RUN 档）；DENY → 抛 `PermissionDeniedException` 回灌模型；全部 PASS 或空链 → 兜底放行（`every-agent-worker/src/main/java/dev/everyagent/worker/tools/permission/GrantRegistry.java:159-227`）。同 grantKey 并发授权共享同一 future，不弹第二张卡（`:165-215`）。

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/permission/AuthorizationHandler.java:13-43（签名节选）
public interface AuthorizationHandler {
    String id();
    float order();   // 链上位置：升序 = 执行序。float 允许任意插位
    AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception;
    // 不处理 = next.proceed(req)；决议短路 = return ALLOW/DENY；上行段 = 审计/升级/宽限期
    record AuthorizationRequest(ExecContext context, String agentId, String grantKey, String prompt) {}
    record AuthorizationDecision(Type type, String reason) { public enum Type { ALLOW, DENY, PASS } }
}
```

**现役链（3 个节点，order 实测）**：

| order | id | 来源 | 行为 | 证据 |
|---|---|---|---|---|
| `100f` | `ai-review-auth` | ai-review 插件 | `metadata.ai-review=true` 时交 AI 审议员决议；ESCALATE/超时 → PASS 下放 | `every-agent-plugins/ai-review/src/main/java/dev/everyagent/plugin/aireview/AiReviewAuthHandler.java:6-38`；注册 `AiReviewPlugin.java:24` |
| `200f` | `unattended-auth` | unattended 插件 | `metadata.unattended=true`（无人值守）时直接 DENY | `every-agent-plugins/unattended/src/main/java/dev/everyagent/plugin/unattended/UnattendedAuthHandler.java:6-19`；注册 `UnattendedPlugin.java:23` |
| `300f` | `human-authorization` | worker 内置（`@PostConstruct` 自注册） | 弹三档授权卡（本轮运行/本任务/拒绝），**链终结节点**（从不 PASS） | `every-agent-worker/src/main/java/dev/everyagent/worker/tools/permission/HumanAuthorizationHandler.java:31,58-76` |

排序与折叠：`AuthorizationHandlerRegistry.sorted()` 按 `order` 升序（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/AuthorizationHandlerRegistry.java:31-34`）→ `AuthorizationChainExecutor.run`（`every-agent-worker/src/main/java/dev/everyagent/worker/tools/permission/AuthorizationChainExecutor.java:24-40`）。插件插位建议：< 100（比 AI 审议更外，先做策略性拒绝/放行）或 100~300 之间（float 任意值，如 `150f`：审议之后、人工弹窗之前，适合「自动批准白名单」类增强；插在 300 之后无意义——human 是终结节点）。

## 8. 红线原文引用（§14.8）

[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §14「实现约束(开发者红线)」第 8 条：

> **复用 Spring AI,禁止重复造轮子**:agent 执行必须走 ChatClient + Advisor 生态,不得手搓 agent 循环、工具循环、响应聚合、system 拼接;执行链只能是很薄一层;新增 agent 能力优先做成 Advisor;**一个 Advisor 只负责一个功能**;事件发射等需挂钩工具循环的增强**通过继承 ToolCallingAdvisor 并重写受保护 hook 实现**;主/子 agent 共用同一运行入口与 Advisor 链,仅 agentId 不同。

落到本篇的三个可检验判据：① 新增强优先做成 `AdvisorProvider`（薄适配器）而不是改执行核心；② 单一职责——每个 Advisor 一个功能，不要把「注入 skill + 发事件 + 护栏」塞进同一个类（对照 `TokenCalibrationAdvisor.java:22` 类注释自证纪律）；③ 挂钩工具循环 = 继承 `ToolCallingAdvisor` 重写 `doAfterStream`/`doGetNextInstructionsForToolCallStream` 等受保护 hook，不得另起包裹层或重复实现递归循环（范例 `WorkerToolEventAdvisor.java:66,92-95`）。另注意 §14.9：插件 pom 任何 scope 不得依赖 `every-agent-worker`——本篇 6 个 SPI 的类型全部住在 plugin-api。

## 下一步读

- 任务生命周期节点（真正的 `[420,850]` 禁插段所在）与自注册 RPC：[task-and-rpc.md](task-and-rpc.md)
- 工具与沙箱扩展点（ExecContext 槽位表、工具循环的另一半）：[tools-and-sandbox.md](tools-and-sandbox.md)
- 26 个内置插件全景（谁注册了哪些 Advisor、order 多少）：[内置插件索引](../reference/builtin-plugins.md)
