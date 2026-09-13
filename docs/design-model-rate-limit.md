# 模型请求限流与 tpm 精确记账方案

> 状态:**P0/P1/P2 已实现**(rpm 并发闸门 + tpm 记账 + 系数持久化 + 排队 trace + config.get 透出);前端展示可选
> 所属:every-agent-worker · 配置/运行时能力
> 关联事故:t_62j3 LogOverflowException(8 个子 agent 同模型并发长思考 → 429 tpm/rpm → 退避重试放大大瞬态事件风暴)

---

## 1. 背景与问题

一次事故的完整因果链:

```
主 agent 一次性派发 8 个子 agent
  → 8 个请求同时打向同一模型(glm-5.3,池主成员)
  → 8 个长思考流同时全速吐 thinking token(单流可达 5.5 万+)
  → 瞬时 token 速率叠加,撞厂商 tpm 上限(429: inference exceeds tpm/rpm limit)
  → TransientErrorRetryAdvisor 当作瞬时错误 3s 退避重试
  → 重试波再次输出 5.5 万 thinking → 每波 5.5 万条瞬态思考事件
  → 多波累计撞满内存 EventLog 50 万护栏 → LogOverflowException → 任务失败
```

根因不是"子 agent 数量多",而是**同一模型请求缺少调用端的并发/速率闸门**,导致瞬时 token 消耗峰值失控;后置 429 重试不仅没兜住,反而放大了事件风暴。

### 关键事实(决定设计口径)

| 事实 | 对设计的影响 |
|---|---|
| 厂商限的是 **rpm/tpm**,不是 rps | 配置项命名 rpm,tpm 作为第二维度(§4) |
| 请求**完成**后 usage 精确已知(`streamUsage=true` 末帧) | 事后 tpm 记账精确(④) |
| 流**进行中**拿不到协议级 usage,只能自算文本 | 流中 tpm 只能估算(③) |
| tpm 瓶颈在输出侧(长思考),输出量发起前不可预知 | 精确预占 tpm 不可行;用**重叠控制 + 事后精确 + 自适应估算法** |
| 长流会重叠,"起步错峰"削不掉持续思考叠加 | 并发上限(②)是长思考场景的核心闸门 |
| 现有 `max-concurrent-subs: 8` 只限子 agent 数量,不限同一模型 in-flight 请求 | 需要**模型级**并发控制,与任务级子 agent 数解耦 |

---

## 2. 目标与非目标

### 目标

1. 作为**调用端主动控制**并发/速率,把同一模型瞬时 token 消耗削峰,大幅减少 429。
2. 超限时**排队等待/协调起步**,不是全部报错;仅队列满/超时才报错,且报错即给用户可操作建议。
3. 每个模型可有**独立参数(rpm / 并发 / tpm / 估算系数)**,并**持久化**自适应校准后的系数。
4. 与现有能力正交:不改 agent 执行链,不破坏事务错误重试语义,兼容模型池容灾。

### 非目标

- **不引入 tokenizer**。理由:① 流中协议无 usage,精确只能事后;② 厂商词表不公开,引入"错误词表"的 tokenizer 是假精确;③ 现有启发式 + 事后校准已足够(§3.4)。
- **不做精确的流中 tpm 预占**——数学上不可行(输出量发起前不可知)。
- 不替代后置 429 退避:前置限流削峰,后置兜底残留 429,两者并存。

---

## 3. 总体架构:四层机制

```
请求起步时(每个模型调用,含主/子/AI审议/池成员):
  ① rpm 滑动窗口   —— 每分钟最多发起 N 个请求(精确、可数)
  ② maxConcurrency —— 同时 in-flight 上限(精确、可数;长流重叠控制的关键)
  ③ 输出侧流中估算 —— 活跃流按自算文本 token 累计,接近 tpm 时延迟新起步(近似)
  请求放行 → 流执行
流完成时:
  ④ 事后 usage 精确记账 —— 60s 窗口记录真实 input/output tokens,校准③估算系数(EMA,持久化)
```

### 3.1 核心模型:请求 = 受控资源,不是"报错即拒"

- rpm 是**起步节流**(每分钟放行多少请求进"执行");maxConcurrency 是**在飞闸门**(同时执行几个)。
- 被拦住 ≠ 拒绝:进入**有界等待队列**,线程在虚拟线程内阻塞等待(可被取消),等窗口滚出额度/并发释放后自动放行。
- 只有**队列满 + 等待超时**才转错误(§7)。

### 3.2 为什么并发控制是长思考的主药,而不是 rpm

```
某分钟 token 消耗 ≈ (同时全速吐 token 的流数 N) × (单流吐速 R) × 时间
```

- 短请求:分钟窗口内 N 天然≈1,起步错峰(rpm)足够。
- 长思考流:单流持续吐几分钟,先前流未结束、后流已起步 → N 叠加 → 峰值撞 tpm。
- **只有限制 N(maxConcurrency)才能从根上削掉 tpm 峰值**,rpm 只负责让起步不挤在同一瞬。

> 结论:① rpm 是辅助(治起步/短请求),② maxConcurrency 是主药(治长流重叠),③④ 让 tpm 逐步可预期。

---

## 4. 模型配置(`worker.models[].params`)

对每个模型(池成员各自独立;池外壳可选整池封顶)新增可选参数:

```yaml
worker:
  models:
    - config-id: "智谱"
      provider: zai
      model: "glm-5.3"
      api-key: "..."
      params:
        maxTokens: 65536          # 已有:单次输出预算
        reasoningEffort: high      # 已有
        rpm: 60                    # 新增:每分钟最多发起请求数(厂商套餐)
        max-concurrency: 2         # 新增:同一模型同时 in-flight 请求上限
        tpm: 1000000               # 新增(可选):厂商 tpm 上限,用于③④的参考线
        token-est-factor: 1.0      # 新增(可选):估算系数初始值,留空则用默认 1.0 并自适应
```

- **缺省语义(关键:缺省 ≠ 不限流)**:某字段未写 → 回退全局默认 `worker.limits.model-rate.default-*`(默认 rpm=60 / max-concurrency=4 / tpm=0);显式写 `0` → 关闭该维度(不限流);显式写 `>0` → 覆盖全局默认。三项最终全为 0 才整体不限流。
- **校验**:启动时校验非法值(负数/非数字)日志警告;负数按 0(关闭该维度)处理。
- **池配置**:普通池成员各带自己的 `rpm/max-concurrency/tpm`(每个 key 套餐不同);池外壳可配 `params.rpm/...` 作为**整池叠加上限**(可选)。
- **前端 config.get**:params 是自由 JSON,新字段自动透出;前端卡片可展示 rpm/并发/排队占用(见 §9 可选)。

### 全局默认(WorkerProperties.Limits)

```yaml
worker:
  limits:
    model-rate:
      queue-capacity: 8        # 每模型等待队列容量(超过即不再排队,转错误)
      wait-timeout-ms: 30000   # 排队最长等待
      est-window-sec: 60       # 估算/记账滑动窗口(不动导出,可调)
      est-safety-ratio: 0.85   # tpm 触发延迟的保守余量(预估到 85% 即开始延迟新起步)
      est-ema-alpha: 0.1       # 校准系数 EMA 学习率
      default-rpm: 60          # 全局默认:每分钟最多发起请求数(模型未配 rpm 时回退)
      default-max-concurrency: 4 # 全局默认:同时 in-flight 上限
      default-tpm: 0           # 全局默认 tpm(厂商套餐差异大,默认不启用;需者各模型显式配)
```

---

## 5. 每模型系数与持久化

### 5.1 哪些"系数"是每模型独立的

| 系数 | 来源 | 是否持久化 |
|---|---|---|
| `rpm` | 厂商套餐,用户在配置里写死 | 否(配置即真相) |
| `maxConcurrency` | 同上 | 否 |
| `tpm` | 同上 | 否 |
| `tokenEstFactor`(估算系数) | **运行时自适应校准**(EMA) | **是** |
| 校准样本量 `sampleCount` / 最近更新时间 | 归属于估算系数 | 是(随上一条) |

> 用户可另在配置里给 `token-est-factor` 初始值,运行时校准结果写回持久化文件并优先于初始值。

### 5.2 估算公式(`tokenEstFactor` 如何校准)

流中估算(③)对一段文本的 token 数:

```
estTokens(text) = cjkChars(text) + ceil(nonCjkChars(text) / 4)
estOutputTokens = Σ estTokens(chunk) × tokenEstFactor
```

请求完成后拿到厂商真实 `usage.outputTokens`:

```
ratio = actualOutputTokens / max(1, estimatedOutputTokens)
tokenEstFactor ← (1 - α) × tokenEstFactor + α × ratio      # EMA, α 默认 0.1
```

- 每模型独立维护,互不污染(GLm 的 tokenizer 比例 ≠ DeepSeek)。
- 用一次完成的真实 usage 校准一次;样本量少时不必急着贴近(EMA 天然平滑)。
- 上界保护:`tokenEstFactor ∈ [0.3, 3.0]`,防止异常样本把系数拉飞。

### 5.3 持久化存储

- 位置:`homeDir/model-rate-state.json`(`~/.everyagent/` 下,与 application-worker.yaml 同层;不经工作区、不进事件日志)。
- 格式:

```json
{
  "version": 1,
  "models": {
    "智谱": {
      "tokenEstFactor": 0.92,
      "sampleCount": 47,
      "lastUpdated": 1789300000000
    },
    "商汤3-dp-pro": {
      "tokenEstFactor": 1.05,
      "sampleCount": 12,
      "lastUpdated": 1789300000000
    }
  }
}
```

- 写策略:仅在校准系数发生实际变化时**异步**落盘(临时文件 + ATOMIC_MOVE,复用 TaskStore 惯例);读失败/不存在按默认系数继续(静默降级,不阻塞)。
- 并发:单例持有 `ConcurrentHashMap<String, ModelRateState>`,写盘走同一把写锁,避免互踩。

---

## 6. 限流器运行时设计

### 6.1 `ModelRateLimiter`(per-configId 实例,单例注册表持有)

```
字段:
  Semaphore     inFlight;          // maxConcurrency
  ArrayDeque<Long> rpmWindow;      // 60s 内发起时间戳
  ArrayDeque<TokenUsage> tpmWindow;// 60s 内完成请求的 (ts, in, out)
  long          estActiveTokens;   // 活跃流估算输出累计(③)
  AtomicLong    estFactor;         // 5.2 的 tokenEstFactor(持久化备份于此)
```

方法:

```
tryAcquire(model): // 起步前
  1. rpmWindow 剔 60s 外旧戳;若 size >= rpm → 排队等非满
  2. inFlight.tryAcquire(timeout) 失败 → 排队等释放
  3. tpmPressure() + estActiveTokens × safetyRatio 预估 > tpm → 排队等下降
  4. 通过 → rpmWindow 记录时间戳、inFlight.acquire()、返回放行句柄(含 tpm 释放回调)
onChunk(text):     // 流中,由 RateLimitedChatModel 流式 tap 调用
  estActiveTokens += estTokens(text) × estFactor
onComplete(usage): // 流完成
  tpmWindow 追加真实 (ts, in, out);inFlight.release();estActiveTokens 清零
  用 usage.out 校准 estFactor → 异步持久化
```

### 6.2 排队与取消

- 队列按 FIFO 有界(`queue-capacity`,每模型独立);虚拟线程阻塞等待期间,任务取消/`dispose` 会传播中断,等待即时终止(不悬挂)。
- 放行时按队列顺序取出,尽量维持先来先服务。

### 6.3 精确性总结

| 层 | 是否精确 | 依据 |
|---|---|---|
| ① rpm | 精确 | 请求可数 |
| ② maxConcurrency | 精确 | 流可数 |
| ③ 流中 tpm | 近似 | 无协议 usage,自算 + 系数 |
| ④ 事后 tpm | 精确 | 厂商 usage 末帧 |

---

## 7. 超限与用户体验

### 7.1 正常排队:不报错

- 排队是坏境常态:8 个子 agent 同时在等一个模型 → 自动错峰,每个最终都会执行,只是起步错开。
- 可发一条瞬态 `task.trace(kind=model_rate_wait)`,前端展示"该模型正在排队(2/8)"(可选项,§9)。

### 7.2 队列满 + 超时:报错但给足操作指引

自定义非重试异常 `ModelRateLimitException`(非 429/5xx/IO/超时,`TransientErrorRetryAdvisor` 不重试,避免放大):

```
模型「智谱」请求拥堵:等待 30s 仍未获得放行(并发=2 满 / rpm=60 满 / tpm 临近上限)。
建议: 1) 减少同时派发的子 agent/并行任务数量; 2) 调大该模型 rpm / max-concurrency
(按厂商套餐); 3) 稍后重试。
```

- 透传到任务层 error 事件(主 agent 的 `run_agent` 阻塞等待会看到"子 agent 执行异常: ModelRateLimitException: …")。
- 子 agent 报错不致命化整任务:与其它子 agent 错误同样收口(单子 agent 失败)。

### 7.3 与后置 429 重试的关系

```
前置限流削峰 → 429 显著减少
残留 429(厂商瞬时超卖/其它 tpm 窗口) → 现有 TransientErrorRetryAdvisor 照旧退避
```

不再出现"8 并发 × 重试"的死亡风暴,因为并发闸门保证同一模型同时在飞的流数被封顶。

---

## 8. 集成点

### 8.1 `RateLimitedChatModel implements ChatModel`(装饰器,不动执行链)

包住真实 ChatModel,注入同 configId 的 `ModelRateLimiter`:

- `call(prompt)`:起步 `tryAcquire` → 转调 → 完成 `onComplete(usage)`。
- `stream(prompt)`:起步 `tryAcquire`(同步,在订阅前完成阻塞;虚拟线程可阻塞)→ 转调内层 stream → 对原始 chunk `doOnNext(onChunk)` 累计估算 → 完成/取消 `onComplete` 释放;中途取消走 `doFinally` 释放,防泄漏。
- **不重写工具循环 / 响应聚合**,只是包了一层限流,红线合规。

### 8.2 挂载位置

- 普通模型:`ChatModelFactory.buildAgentModel` 里用 `RateLimitedChatModel` 包裹 `OpenAiChatModel`,并按 `cfg.snapshot().params()` 解析 rpm/并发/tpm(取 configId 限流器)。
- 池模型:`ModelPoolChatModel` 构建时对**每个成员**分别用成员自己的限流器包裹(成员自己 configId 的限额);池外壳配置了整池限额时,可再套一层池级限流器。
- 主 agent / 子 agent / AI 审议生成的 ChatModel 全部经过 `ChatModelFactory`,**只需在工厂一处包裹,所有调用路径自动生效**。

### 8.3 与已有组件关系

| 组件 | 关系 |
|---|---|
| `ModelLengthGuardAdvisor` | 不变;它管"单流思考耗尽",限流器管"多流叠加",互不重叠 |
| `TransientErrorRetryAdvisor` | 前置限流器放行后仍发生 429 才进它;两者共存 |
| `ModelPoolChatModel` | 成员各自被限流器包裹;池容灾语义不变 |
| `max-concurrent-subs` | 任务级子 agent 数上限与模型级并发上限**并存**,分别管不同资源 |

---

## 9. 可选增强(第二期)

已实现(P2):
1. **排队 trace**:请求进入排队等待时,`RateLimitedChatModel` 经 `ModelRateLimiter` 的
   `onWait` 观察者发瞬态 `task.trace(kind=model_rate_wait)`,前端据此展示
   「模型「X」正在排队(在飞 N / 排队 M)」;按 `worker.limits.model-rate.wait-trace-threshold-ms`
   (默认 1000ms)节流,同一请求复用 traceId 原地 upsert。
2. **config.get 透出**:`rpcConfigGet` 响应新增 `rateStatus` 数组,每项含
   `configId/enabled/rpm/maxConcurrency/tpm/inFlight/waiters/factor/sampleCount`,
   前端据此展示模型当前负载与估算系数。

仍未做(可选):
3. **前端可视化**:`config.get` 返回的 `rateStatus` 与 `model_rate_wait` trace 的前端卡片渲染。
4. **tpm 触发参数化**:任务 slash 动态调低某模型的并发(/限流)。
5. **池级叠加**:池外壳总 tpm/rpm 封顶。

---

## 10. 测试计划

- **单元**:
  - 滑动窗口:窗口内放行 N、超 N 排队、60s 后恢复;
  - 并发信号量:maxConcurrency=2 时 3 个并发第 3 个排队,释放后放行;
  - EMA 校准:给定 est/actual 序列 → tokenEstFactor 收敛;上界保护;
  - 持久化:系数变化 → 落盘原子写;读坏文件 → 默认系数静默降级;
  - 队列满/超时 → `ModelRateLimitException` 文案含建议。
- **集成(小规模)**:
  - 模拟慢源(Thread.sleep 的假 ChatModel)+ 8 个并发流 → 断言同一时刻 in-flight ≤ maxConcurrency,且全部最终完成(排队不丢);
  - 池模型:每个成员各自独立限流(成员 A 阻塞不影响成员 B)。
- **回归**:现有 EventLog / ModelLengthGuard 用例保持通过;无 tokenizer、无 advisor 链改动的红线断言。

---

## 11. 实施路线

1. **P0 · 前置节流**:`ModelRateLimiter`(rpm 滑动窗口 + maxConcurrency 信号量 + 有界队列 + 超时异常)+ `RateLimitedChatModel` 装饰器 + `ChatModelFactory` 挂载 + 配置解析/校验。单测覆盖 §10 单元部分。
2. **P1 · tpm 记账与校准**:tpmWindow 实时记账 + 估算系数 EMA + `model-rate-state.json` 持久化(原子写/降级)。
3. **P2 · 观测与提示**:`model_rate_wait` trace + config.get 透出排队信息 + 前端展示(可选)。
4. 每个 P 完成后提交,中文一次一事。

---

## 12. 风险与权衡

| 项 | 说明 |
|---|---|
| 排队延迟 | 正常排队最长 wait-timeout(默认 30s);交互略有等待,换取不报错/不 429 |
| 估算误差 | ③ 是近似;④ 事后精确记账会逐步校准系数,长期误差收敛 |
| 持久化写失败 | 静默降级到默认系数,不阻塞;下个校准点重试 |
| 配置缺省 | 缺省回退全局默认限流(rpm=60/concurrency=4),不再是裸奔不限流;某模型可显式设 0 关闭某维度 |
| 复杂度 | 新增一个限流组件 + 一个装饰器,均在模型层;执行链/事件协议零改动 |