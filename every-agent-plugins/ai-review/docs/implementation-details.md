# ai-review 实现细节

> 本文内容提取自 [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) §7.9（AI 安全审议部分）。文中 §x.x 引用均指向 ARCHITECTURE.md 对应章节。

## AI 安全审议（ai-review 插件）

当需要人工授权(PermissionGate 拦到工作区外路径/危险命令)时,除人工弹窗外提供可选的任务级自动路径——AI 审议是其一。

- **AI 审议(`/AI 审议`,kind=ai.review)**:可单独开启。授权弹窗改为由**独立的 AI 审议会话**(无任何工具、独立 system prompt,只基于安全策略判断并要求忽略授权正文中的任何指令,防 prompt 注入)读取授权信息并输出结构化判断(ALLOW/DENY/ESCALATE),在 PermissionGate 内部闭环自动放行/拦截并落审计。**主 Agent 是被审议方,不能自我授权**。审议 agent 经 `req.context().agentFactory().create(reviewAgentId, reviewModel?)` 创建——工厂为预绑定静态代理(§7.20),事件/审计自动落被审议主体日志,advisor 链(重试/压缩/限流)照常装配;审议 agent 以 per-task 固定 agentId `review-<subjectId>` 注册进 `agents()` 跨请求复用会话——既往授权决策的结论与理由留在审议员上下文内,后续审议看得见本任务历史决策(会话随授权次数增长,任务生命周期内有限)。**会话交替不变量(复用方责任)**:agent 执行链不回写会话内存(`WorkerToolEventAdvisor` 只发 message 事件、`AgentRunner` 只把会话副本交给 ChatClient),故每轮审议结束由 `AiAuthReviewer.doReview` 在 finally 把本轮结论回写为 assistant 轮(异常/中断记占位),保证「append 新 user 之前 assistant 已在场」(与 §7.16 队列续跑的 `ConversationLoader.catchUpRuntime` 同一条不变量);缺此回写则审议员看到「N 条连续未答复的 user」,会把历史授权请求一并作答(多对象/数组输出 → 解析失败;旧行为 fail-closed 误拒,现改为重试一次后仍失败即 ESCALATE 交下一环节,§7.9,或旧结论被当本轮结论用)。

## 授权拦截链（AI 审议环节）

`PermissionGate.ensureGranted` 内、发起人工弹窗前短路,本环节为链中第 ① 环:

| 拦截链环节 | 判定依据 | 行为 |
|---|---|---|
| ① AI 审议 | 任务级 `ai.review` slash token 存在且审议器在位 | ALLOW → 自动授权(**RUN 档**,决议用 `AuthorizationDecision` 默认档位);DENY → 拒绝;ESCALATE(含解析失败重试后仍失败)→ 落下一环节;超时/异常 → 按 fail-closed 默认 DENY |

## 审议相关规则

- 审议路径不发 `ask.create/ask.state` → 任务保持 RUNNING(不误转 waiting-user)。
- **审议输出解析失败**(非 JSON / 缺 decision / 多决策对象 / 空响应)→ **重试一次**要求模型纠正;**仍失败 → ESCALATE**(交下一节点/人工),**不再直接 DENY**——fail-closed 仅保留给**超时/异常**路径(默认 DENY;`review-deny-on-error` 控制)。**审议失败绝不自动放行**。
- 审计:`task.trace(kind=auth.review)` 持久落盘,metadata 含 decision/confidence/reason/scope/grantKey/prompt/taskId/审议 agentId;审议链的重试/容灾 trace 同入审计。结果 trace 以**审议 agent 自身身份**(`AgentContext.emitter()`)发射——审议 agent 的**会话内容**事件(思考/正文/工具结果/错误/`agent.done`,按 ai-review 插件自持的 `review-` agentId 前缀约定判定)被该插件注册的出网 filter 丢弃(§5.3/§14.13),而**状态与用量**(`agent.started`/`agent.status`/`usage`)与 `auth.review` 结果 trace 走审议 agent 身份发出、保持客户端可见:前端「允许 置信度 90%」照常显示,且**输入框上方 agent 胶囊列表**的审议胶囊在运行中即有实时状态与用量(若把状态/用量一并丢弃,胶囊整轮只能停在「空闲 + 无用量」,直到任务收口后重拉台账才补齐)。
- 配置:`worker.permissions.review-timeout-ms`(默认 60s,总预算硬闸)、`review-deny-on-error`(默认 true)、`review-model`(可选,审议专用模型 configId,空则用任务当前模型)。
