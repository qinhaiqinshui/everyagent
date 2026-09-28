# 核心去池化：池概念移出 worker 核心（方案文档）

> 状态：**方案已定稿，待实施**
> 结论来源：2024 评审讨论（RateLimit / 模型池 / ChatModelEnhancer / buildMember / 快照语义 / 热更新边界）
> 原则：worker 核心 + plugin-api + wire 协议**零池概念**；池化是 model-pool 插件的私有概念。

---

## 1. 问题：池概念泄漏进核心（已核实全景）

「成员列表怎么解析」是 model-pool 插件的私有配置格式，却由 worker 核心解析：

| 位置 | 泄漏内容 |
|---|---|
| `ConfigStore` | `POOL_PROVIDER` 常量、`parsePoolMembers()`（逗号分隔解析/trim/去空/去重）、禁池套池校验、成员不存在跳过策略、**池外壳 params 继承首成员** |
| `ConfigStore.ResolvedConfig` | `poolMembers()` 字段 + `isPool()` 方法 |
| `ChatModelFactory` | `if (!cfg.isPool())` 分支 + `EnhancerContext.members()` 组装 |
| `ConfigDtos.ModelConfig`（wire） | `members` 字段（config.get 透出） |
| `ai-review` 插件 | `AiAuthReviewer.resolveConfig()` 引用 `ConfigStore.POOL_PROVIDER`（且该 if 两分支代码完全相同，冗余） |

关键错误在**路由判据**：`buildAgentModel` 该问的是「这个 provider 有没有 enhancer 认领」（`enhancerRegistry.find(provider) != null`），而不是「这是不是池」（`isPool()`）。后者把具体插件的概念烧进核心——按此判据，将来任何新的组合型 provider（负载均衡、缓存模型、mock）都得再改 ConfigStore 加一个 `isXxx()`，SPI 退化回 if/else 收集器。

## 2. 已核实的事实基础

- **前端零依赖 members**：`ModelConfigInfo.members?` 仅为可选字段声明，全仓**零消费点**，UI 只用 `configId`/`model`。删 wire 字段前端零改动（不声明也兼容，字段恒 undefined）。
- **请求级 params 与快照读数是两股道**：
  - 请求级（temperature/topP/maxTokens/reasoningEffort）：`buildMember` 时经工厂 `options()` 灌进 `OpenAiChatOptions` 随实例固化，**跟随成员**；池切换时 `withOptions` 用成员自己的 options 改写本次 prompt。池插件全程不碰成员 params（`ModelPoolEnhancer` 现状即如此，成员 options 从 `model.getOptions()` 回读保证同源）。
  - 快照读数（rpm/maxConcurrency/tpm、contextWindowTokens）：消费方读的是**任务快照 params**（池任务 = 现状继承自首成员的副本），成员实例无关。消费方三处：`RateLimitAdvisor`（限流参数）、`ContextCompressionAdvisor`（压缩触发窗口）、`WorkerToolEventAdvisor`（usage 上下文电池分母）。
- **限流器按 `task.snapshot.configId()` 取**：池任务 = 池外壳一个 configId，**整个池共享一个限流器**——advisor 层对池的态度本来就是「池就是一个模型」。
- **modelCache 按 configId 全局复用**：同一配置当任务模型用和当池成员用，拿到同一个 `OpenAiChatModel` 实例（同一 OpenAIClient/OkHttp 资源）。「配置→实例」流水线（options 灌装 + 缓存 + SDK 客户端固化）在工厂里且**不含池语义，本次不动**。
- `ConfigStore.resolve()` 对不存在 configId 抛 `NotFoundException`；插件侧跳过无效成员需要「不存在返回 null」的变体。

## 3. 目标形态

```
ConfigStore:   存原始配置（provider/model 字符串原样），零池语义，零成员解析
               ↓ resolve(configId)
ChatModelFactory.buildAgentModel:
  enhancer = registry.find(provider)
  enhancer == null → 普通路径（build() + options()，现状不动）
  enhancer != null → 委托 enhance(ctx)，EnhancerContext 提供:
      poolConfig()                    原始配置（model 字段就是逗号串，未解析）
      resolveMember(configId)         → MemberSpec|null（新增，替代 members()）
      buildMember(spec)               回调工厂 build()（不变，单一真相源）
      events()/agentId()              （不变）
               ↓
model-pool 插件: 自己 split 逗号串、自己校验禁套池/成员缺失跳过、自己组装
                ModelPoolChatModel（行为不变）
```

核心只知道「provider → 有无 enhancer 认领」；成员格式、解析、校验、组合策略全归插件。

## 4. 改动清单（按依赖序）

1. **文档先行**（红线：实现与文档冲突先改文档）
   `docs/ARCHITECTURE.md` §7.4（模型池容灾：解析归插件、非致命成员错误时点后移）、§7.17/§5.10 相关段（worker.models 配置描述去池语义）、§7.19.5（ChatModelEnhancer SPI：路由判据、`resolveMember`）；`EnhancerContext.buildMember` javadoc 的「含洋葱链包装」陈旧注释一并清理。
2. **plugin-api**
   `EnhancerContext`：删 `members()`；新增 `resolveMember(String configId)` → `MemberSpec | null`（不存在返回 null 供插件跳过+告警）；注释措辞从「池」改为「组合 provider」。`MemberSpec` 保留（通用「待构建成员」概念）。
3. **worker 核心**
   - `ConfigStore`：删 `POOL_PROVIDER`/`parsePoolMembers()`/`toConfig()` 池分支（含 params 继承）/`ResolvedConfig.poolMembers()`/`isPool()`；新增 `resolveOrNull(String)`。
   - `ChatModelFactory.buildAgentModel`：判据 `!cfg.isPool()` → `enhancerRegistry.find(provider) == null`；匿名 `EnhancerContext` 改用 `resolveMember`。
   - `ConfigDtos.ModelConfig`：删 `members` 字段；`ConfigRpcHandler.rpcConfigGet` 同步。`WorkerProperties.Model` javadoc 去池描述。
4. **model-pool 插件**
   `ModelPoolEnhancer.enhance()`：解析 `poolConfig().model()` 逗号串（trim/去空/去重/顺序保持）→ 逐个 `resolveMember`：null 跳过 + error 日志；成员 provider 是本插件 id（组合型）→ 抛异常禁套池；全空 → 抛异常。`ModelPoolChatModel` **零改动**。
5. **ai-review 插件**
   删 `AiAuthReviewer.resolveConfig()` 的 `POOL_PROVIDER` 冗余 if（两分支代码相同，直接合并）。
6. **测试**
   `ConfigStoreTest` 池用例迁入 model-pool 插件侧新增 `ModelPoolEnhancerTest`（逗号解析/去重/缺失跳过/禁套池/全空抛）；`ModelPoolChatModelTest` 不动；全量 `mvn clean package` + E2E。

## 5. 行为取舍（已定）

### 5.1 删除 params 继承 → 三消费方回退默认

池外壳不再继承首成员 params 后，池任务快照 params 为空，三消费方回退：

| 消费方 | 现状（继承首成员） | 去池化后 |
|---|---|---|
| RateLimitAdvisor | 首成员 rpm/maxConcurrency | 全局默认（rpm=60/并发=4） |
| ContextCompressionAdvisor | 首成员 contextWindowTokens | `DEFAULT_CONTEXT_WINDOW_TOKENS` |
| WorkerToolEventAdvisor（电池分母） | 同上 | 同上 |

**接受此回退**，理由：
- **显式配置即恢复精确控制**：池外壳显式写 `params`（contextWindowTokens/rpm/maxConcurrency…）即整池声明值——设计文档 §4 本有「外壳显式配置以其为准」语义，继承只是缺省便利。
- **继承口径本来就不准**：混合窗口池（智谱 200k + DeepSeek 128k）抄 200000，轮到小窗成员接管时压缩与电池偏乐观。按保守成员取值显式配置才是正确口径。
- 请求级参数（temperature/maxTokens 等）**不受影响**——本就跟随成员实例（§2 两股道）。
- 在插件侧保留继承效果需引入「配置视图钩子」等新机制，时序也对不上（快照冻结早于插件介入）——为去耦合再造耦合点，不做。

### 5.2 池配置错误暴露时点后移

成员笔误/漏配从「worker 启动期拒绝（无有效成员）」后移到「首次构建期抛出」。配置格式所有权归插件的必然代价，与「单成员不可用不影响整体」哲学一致。

### 5.3 明确不做的事（讨论结论，防止回头路）

- **不做「切换成员时用成员 params 覆盖任务快照」**：快照是任务级冻结状态（主/子 agent 共享、落 TaskSummary、审计依据）；切换是 per-request 瞬态（每轮从主模型重新起步，游标 per-subscription），覆盖会造成快照在成员间震荡污染 + 多 agent 互踩。成员身份只活在**本次请求的 prompt options**（`withOptions` 已闭环）。模型层不持有 task 引用（只有 EventEmitter + agentId），保持薄。
- **不做「读取时穿透 resolve 的动态快照」**：撕裂读（单轮内多 advisor 口径不一致）、半动态状态（params 活而 baseUrl/apiKey/模型实例死）、审计回放不确定性。动态配置是**独立账**（§6）。
- **不引入 tokenizer、不做池级叠加限流**：维持现状边界。

## 6. 与 Phase 6（配置热更新）的关系：正交，独立推进

快照冻结语义保留；热更新按既有 Phase 6 规划（ARCHITECTURE.md §7.19.4）单独立项，正确形状（本次讨论结论）：

- **round 边界刷新**：每轮开始重新 resolve 一次、round 内冻结——单轮一致、轮间演进；
- **版本化 ModelConfig + 版本化模型缓存**：配置带 epoch，round 边界发现版本变化 → 重建模型实例原子替换（`ModelConfig` javadoc 已预留：接口不变，内核改为从 context 取模型）；
- 换模型/baseUrl/apiKey 的热生效必须连带模型缓存失效，否则一半活一半死；
- 限流器参数（registry 首创建格）如需跟随热更新，属同一账内子项。

去池化改「配置怎么解析进成员」，Phase 6 改「配置何时生效」，互不牵扯。

## 7. 验收标准

- `rg "isPool|poolMembers|POOL_PROVIDER|parsePoolMembers"` 在 every-agent-worker 与 every-agent-plugin-api 主代码**零结果**（测试同理，池用例已迁插件侧）；
- worker 核心与 plugin-api 源码中不再出现「池」语义（`rg -i "pool"` 仅剩 HubPool 等无关同名）；
- 前端零改动（`every-agent-web` 无 diff）；
- `mvn clean package` 全量通过，含 WorkerIntegrationTest / WorkerHubE2eTest / MultiHubE2eTest / 新增 ModelPoolEnhancerTest；
- 池任务行为回归：正常轮询容灾、`model_failover` trace、池耗尽原样上抛均不变（`ModelPoolChatModel` 零改动保证）。

## 8. 风险

| 项 | 说明 | 缓解 |
|---|---|---|
| 已部署池配置未显式写 params | 限流/压缩窗口回退默认（§5.1） | README/文档一句话提醒「池外壳建议显式配 params（按最保守成员取值）」 |
| 池配置错误暴露后移（§5.2） | 笔误从启动报错变为首任务报错 | 错误信息含 configId 与缺失成员 id，可操作 |
| wire 删 members 字段 | 老前端读 members | 已核实零消费；可选字段缺省即 undefined |
