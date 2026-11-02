# model-rate-limit 实现细节

> 本文内容提取自 [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) §7.4.1 与 §7.19.3。文中 §x.x 引用均指向 ARCHITECTURE.md 对应章节。

## 模型请求限流(调用端 rpm/并发/tpm 闸门)

**背景**:厂商限的是 rpm/tpm(不是 rps);一次事故即「主 agent 同时派发 8 个子 agent → 8 个长思考流同时全速吐 token → 瞬时叠加撞 429 → 退避重试放大瞬态事件风暴 → LogOverflow」。调用端需要**前置主动限流**,而不是只靠后置 429 重试。

**机制**(`model-rate-limit` 插件的 `RateLimitAdvisor` 实现 `CallAdvisor`/`StreamAdvisor`,经 Advisor 链注入(order=工具循环+500,最内层);主/子/AI 审议/池成员全部自动生效):

- 每模型独立配置(`worker.models[].params`):`rpm`(每分钟发起数,滑动窗口)、`max-concurrency`(同时 in-flight 上限,**长思考重叠的核心闸门**)、`tpm`(可选参考线)。(另有历史遗留字段 `token-est-factor`:仍被解析但已无消费方——校准系数由 `token-estimator` 插件的持久化状态提供,见下。) **缺省回退全局默认限流,不是裸奔不限流**(Java 默认 rpm=60 / max-concurrency=4 / tpm=0;jar 内 `application.yml` 出厂值为 rpm=120 / max-concurrency=6 / tpm=0);某模型要关闭某维度,在其 params 显式设 0。
- 请求起步经 `ModelRateLimiter.acquire` 排队等放行:rpm 窗口 / 并发信号量 / tpm 压力三关;超限进有界等待队列(默认队列 8、等 5 分钟),**正常排队不报错**,仅队列满 + 超时才抛 `ModelRateLimitException`(非重试,文案含「减少并发派发/调大配置」建议)。阻塞等待发生在虚拟线程上(park,零线程开销)。
- **tpm 记账**:流中无协议级 usage(OpenAI 兼容只在末帧带),故流中用自算文本 token 粗估(CJK≈1、其余≈4 字符 1 token)累计,估算经 `OutputTokenAccumulator` 统一口径(**正文增量 + 思考累积值差分 + 工具调用参数按 callId 累积**);**必须含工具调用参数**——厂商真实 `completionTokens` 也计入它,漏计会让校准比值 `actual/estimated` 系统性偏高、把系数抬到上界(只统计「无工具调用」轮次时 median 实测/估算 ≈1.05,证明估算公式本身误差仅 5~7%,坏的是统计口径)。请求完成后用厂商真实 usage 记账入 60s 窗口;校准由 **`token-estimator` 插件的 `TokenCalibrationAdvisor`** 完成(用真实 `completionTokens` 的 EMA 校准估算系数,每模型独立,持久化 `~/.everyagent/model-rate-state.json`,重启接续)——状态文件带**口径版本 `version:2`**,口径变更(如本次纳入工具调用参数、修正 ratio 语义)后旧系数不再可比,载入时版本不符即丢弃、从空重新标定。token 估算经 Advisor 的 `doOnNext`(chunk 估算)/`doOnComplete`(真实记账)回调驱动。
- 全局默认:`worker.limits.model-rate.{queue-capacity, wait-timeout-ms, est-window-sec, est-safety-ratio, est-ema-alpha, default-rpm, default-max-concurrency, default-tpm}`。
- **观测**:排队等待经 `EventEmitter.emit(EmitEvent.TraceData.transientOf("model_rate_wait", null, "模型排队中", null, "waiting"))` 发语义事件,task 层映射为瞬态 `task.trace(kind=model_rate_wait)`(前端展示「模型正在排队」,§7.19);插件未加载时无限流 Advisor → 直通 → 不限流;worker 保留 fallback `SimpleTokenEstimator`(只实现 `estimate`、无校准),即 token-estimator 插件被禁用时三处消费方(model-length-guard / context-compression / model-rate-limit)的兜底,退化为「无校准粗估」。
- **插件不耦合内核**:`RateLimitAdvisor` 只依赖 `ModelConfig` + `EventEmitter`,不耦合 `ChatModel`/`Prompt`/`ChatResponse`/`Flux`/`TaskEvents`/`agentId`/`taskId`/`DataPusher`。`ConfigRpcHandler.rateStatus` 返回空数组(插件后续自行注册 RPC 上报限流运行态)。

## 限流 Advisor 插件(every-agent-plugins/model-rate-limit)

限流插件以 Advisor 形式注入 Agent 执行链,不再经独立的模型请求中间层:

- **`RateLimitAdvisor` implements `CallAdvisor, StreamAdvisor`**:order=`ToolCallingAdvisor.DEFAULT_ORDER+500`(最内层,ContextCompression +400 之后),对主/子/AI 审议/池成员全部自动生效。
- `adviseCall`/`adviseStream` 中经 `a.snapshot()` 查 limiter:无限流配置 → 直通下一层;有配置 → `acquire()` 排队等待(虚拟线程 park,零线程开销),等待期间经 `a.emitter().emit(EmitEvent.transientOf(...))` 发语义事件(`AdvisorContext extends ExecContext`,直读槽位,§7.20)。
- 通过 Reactor 操作符(`doOnNext`/`doOnCancel`/`doOnError`/`doOnComplete`)绑定 Permit 生命周期:`doOnNext` 驱动流中 token 估算(`permit::onChunk`),`doOnComplete` 驱动事后精确记账与信号量释放(`permit::complete`),`doOnError`/`doOnCancel` 驱动取消与释放(`permit::cancel`)。不再经 `ModelRequestContext` 回调,直接在 Advisor 链操作。
- **限流类驻插件包**:`ModelRateLimiter` / `RateLimitAdvisor` / `RateLimitAdvisorProvider` / `ModelRateLimitConfig` / `ModelRateLimiterRegistry`;测试一并随插件。**token 估算与在线校准已拆到独立插件 `token-estimator`**(`BuiltinTokenEstimator` + `TokenCalibrationAdvisor`),`model-rate-limit` 只保留限流,经 `ctx.services().tokenEstimator()` **延迟解析**消费估算器(装载顺序:按目录名字典序,`model-rate-limit` 排在 `token-estimator` 之前激活,故经 `Supplier` 在按 configId 缓存的 `computeIfAbsent` 内取值,不固化 activate 期间的旧实例)。
- **不耦合内核**:插件只依赖 `ModelConfig` + `EventEmitter`,不耦合 `ChatModel`/`Prompt`/`ChatResponse`/`Flux`/`TaskEvents`/`agentId`/`taskId`/`DataPusher`。
- **worker 核心清理**:无限流器引用;`ConfigRpcHandler.rateStatus` 返回空数组(插件后续自行注册 RPC);fallback `SimpleTokenEstimator`(只实现 `estimate`、无校准)就位——token-estimator 插件被禁用时,供 model-length-guard / context-compression / model-rate-limit 三处消费方兜底。
- **回退**:插件未加载时无限流 Advisor → 直通 → 不限流;WebSocketEmitter 未建立时(无前端订阅)`emit` 仍写 EventLog,不推前端。
- `WorkerProperties.ModelRate` 配置类留 worker(全局配置项);`TokenEstimator` SPI 接口留 plugin-api(现只含 `estimate` 一个方法;`model-length-guard` 插件也依赖);插件经 `AdvisorProvider` 注册 Advisor。
