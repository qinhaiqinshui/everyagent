# model-length-guard 实现细节

> 本文内容提取自 [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) §7.4.2(模型 HTTP 超时语义,涉及 `StreamTimeoutReleaseInterceptor` 与 `FirstChunkWatchdogAdvisor`,与本插件的 `ModelLengthGuardAdvisor` 协同工作)。文中 §x.x 引用均指向 ARCHITECTURE.md 对应章节。

## 模型 HTTP 超时语义(callTimeout 解除,流式长思考不限总时长)

**机制**(`ChatModelFactory.build` 经 `httpClientBuilderCustomizer` 挂 `StreamTimeoutReleaseInterceptor`,主/子/池成员/AI 审议全部生效):

- spring-ai 的 `OpenAiChatOptions.timeout(t)` 单值在 openai-java 展开为 `Timeout.request(t)`,最终映射 okhttp **`callTimeout`(整个调用的总时长上限,含流式全程)**;且 `AbstractOpenAiOptions.getTimeout()` 永远非 null(未设时默认 60s),per-request 四分量**每次覆盖** client 级配置,`httpClientBuilderCustomizer.timeout(...)` 无法纠正。reasoning 模型(reasoningEffort=high)单轮长思考可达数十分钟,callTimeout 到点 okhttp 强制断流(IOException)——表象与 provider 粗暴断流一致,且断流时输出量=思考速度×上限时长,常低于 maxTokens 的 80%,`ModelLengthGuardAdvisor` 比例判定不命中,落入瞬时重试死循环(每次重试重放整段长思考,再次到点断流)。
- 根治:应用拦截器内对每个 call 执行 `chain.call().timeout().clearTimeout()`——okhttp 原生支持运行期解除 call 级总时长,流式响应只要持续有 chunk 即不限总时长;同时剥除 `X-Stainless-Timeout` 请求头(该头携带 callTimeout 秒数,防 provider 按头掐流)。
- 兜底仍在:静默挂起由 okhttp readTimeout(读间隔上限,openai-java 默认 10 分钟)与 `ModelLengthGuardAdvisor` 的 stall(120s)先后兜住;真网络断连照常抛 IOException 交瞬时重试。`worker.model-timeout-ms` 语义因此调整为「读间隔上限的期望值」(仍写入 options.timeout,构成 per-request connect/read/write 默认分量的参考基准)。
- **首帧看门狗**(`FirstChunkWatchdogAdvisor`,advisor 链最内层、RateLimit(+500) 之内,只包住真实模型 HTTP 调用):流订阅成功后 `worker.model-first-chunk-timeout-ms`(默认 120s,≤0 关闭)内未收到任何 chunk,即以 `TimeoutException` 中断当前订阅——该异常属瞬时错误分类,交 `TransientErrorRetryAdvisor` 既有退避重试,耗尽后任务显式报错。封堵 readTimeout/stall 均不触发的两类静默挂起(2026-10 t_6342 实锤):①网关周期性下发 SSE 保活字节(注释行不产 chunk),okhttp 字节级 readTimeout 被无限续命,流永不完成也永不报错;②openai-java 异步桥在响应头到达前不随 dispose 取消底层 call,泄漏 call 占满 dispatcher per-host 槽位后新请求无限排队、零超时。首帧到达后 chunk 间隔上限沿用 `model-timeout-ms`(reactor 层 `Flux.timeout(first, next)`,与 okhttp readTimeout 同值双保险)。位置在限流之内:排队等 permit 的时间不消耗看门狗预算。
