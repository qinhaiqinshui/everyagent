# adaptive-max-tokens 实现细节

> 本文内容提取自 [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) §7.3.1。文中 §x.x 引用均指向 ARCHITECTURE.md 对应章节。

## 自适应输出预算（adaptive-max-tokens 插件）

**背景**:reasoning 模型在长思考任务中常把 maxTokens 输出预算全部耗在 thinking 上、finish_reason=length 截断。如果用户配置了较小的 maxTokens(如 8192),虽然 Guard 会检测到并报错,但任务直接失败——用户体验差。理想行为是:检测到 length 截断时自动放大 maxTokens 重试,直到产出有效结果或触及 ceiling。

**机制**(`adaptive-max-tokens` 插件的 `AdaptiveMaxTokensAdvisor` 实现 `StreamAdvisor`,order=工具循环+250,位于 Guard(+300)外侧;call 路径直通,仅 stream 路径生效):

- **触发信号**:per-subscription 在 `doOnNext` 检测 `finish_reason=length` 帧(真实 provider 帧或 Guard 合成帧,不区分来源)——记 flag 后 `filter` 吞掉该帧不上抛(防止帧泄漏到外层聚合器与前端)。流结束(doOnComplete 或 onErrorResume)时 flag 置位 → 升级预算重试。
- **交互模型**:Guard 补帧(数据面触发器)→ Adaptive 消费帧升预算重试;Guard 抛异常(控制面兜底)→ Adaptive 不在场时错误收口不丢。Adaptive 只认帧,不认异常类型/文案。异常不会使 Adaptive 失效:Adaptive(+250)在 Guard(+300)外层,Guard 异常必经 Adaptive 的 onErrorResume;Reactor 保证 onNext(帧)先于 onError(异常),flag 必已置位。
- **升级**:`budget = min(base × multiplier^attempt, ceiling)`;重试零延迟重订阅(不加人工退避)。升级后的 budget 保存在 advisor 实例字段(per-run,主/子 agent 各自隔离),本次任务后续所有轮次均用增大后的值。
- **回落**:连续 N 轮实际输出(usage completionTokens)< 当前预算 × 低水位 → 衰减回 base(防预算单调膨胀)。
- **ceiling 硬上限 = 262144(256K tokens)**:2025 年主流商用模型输出上限包络值(GPT-5.2/o3 256K、Claude 4.5 128K、Gemini 3 Pro 128K 等)。超出模型真实上限时厂商返回 400,Adaptive 捕获后一次性回退到触发升级前的上一个 budget 并停止继续上调。
- **放弃**:预算已达 ceiling 或重试次数上限 → 抛非重试异常 `AdaptiveBudgetExhaustedException`(信息含已放大至 ceiling 多少、建议精简输入/降 reasoningEffort/任务拆分)。
- **直通条件**:`enabled=false` 或模型未配 base maxTokens 时直通(无基线无从升级)。

**缺谁都照样运行(四象限)**:

| Guard | Adaptive | 行为 |
|---|---|---|
| 有 | 有 | Guard 补帧+抛异常 → Adaptive 消费帧升预算重试,任务内持续生效 → 触顶 ceiling 放弃才报错 |
| 有 | 无 | 异常直达任务层收口,现状不变 ✅ |
| 无 | 有 | 真实 length 帧 + 流正常 complete → Adaptive 在 doOnComplete 检测帧触发升级 |
| 无 | 无 | length 帧当正常完成(guard 出现前的旧行为),不崩 |

**配置**:`worker.limits.adaptive-max-tokens.{enabled, ceiling(默认 262144), multiplier(默认 2.0), max-retries(默认 2), fallback-ratio(默认 0.5), fallback-rounds(默认 3)}`;模型级可用 `params.maxTokensCeiling` 覆盖 ceiling(厂商真实上限,如 Claude 系填 131072)。

**跨插件协议契约**:「模型输出耗尽 = finish_reason=length 帧 + 非重试异常」组合信号。帧来自协议本身(比错误文案稳定),Guard 侧合成帧语义不可移除。
