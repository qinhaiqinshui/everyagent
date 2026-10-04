# Every Agent — 架构说明

> **哪里都可以使用的 Agent**:worker 执行器运行在本地局域网的个人电脑上,任务里的 agent 在其中运行,进度从任何地方查看。
> 本文档是项目的**唯一架构事实源**。实现与本文冲突时,以本文为准(或先修订本文)。
> 面向读者:项目贡献者、希望二次开发或自部署的开发者、以及想理解其设计思路的评估者。协议层是语言中立的,任何 WS 客户端都可按第 5 章自行实现。

---

## 1. 项目概览

Every Agent 是一套「**公网可及、本机执行**」的 AI Agent 系统:AI 任务(agent 循环)跑在你自己电脑上的执行器(worker)里,而你可以从任何地方的浏览器通过一个公网消息中心(hub)查看进度、发指令、处理需要确认的问题。不需要公网 IP、不需要端口映射、不需要把代码和数据上传到第三方服务器。

系统由四个可独立部署的模块 + 插件 API + 内置插件集 + 一个桌面打包组成:

| 模块 | 职责 | 端口 |
|---|---|---|
| `every-agent-hub` | 公网消息中心:纯中转 WebSocket (WebFlux/Reactor),零状态、零缓冲、零业务逻辑 | 6101 |
| `every-agent-worker` | 执行器:Spring Boot + Spring AI 2,托管任务运行时、模型调用、workspace、沙箱进程 | 6102(仅本地健康检查) |
| `every-agent-web` | 前端:React + TS,内置 TS 客户端 SDK,经 hub 遥控 worker | 5174(dev) |
| `every-agent-contract` | 纯协议契约:帧信封 / RPC 信封 / 通用错误码 / 身份哈希(Java DTO + TS 类型) | — |
| `every-agent-plugin-api` | 插件 API 契约:ExecContext(统一执行上下文) / EventEmitter / EmitEvent / ChatModelEnhancer / ModelConfig / TaskLifecycleNode 等接口(纯类型,插件与 worker 共用) | — |
| `every-agent-plugins` | 内置插件集:model-rate-limit(限流) / task-queue(队列) / subagent / git / ai-review / empty-response-retry(空响应重试) / transient-error-retry(瞬时错误重试) / context-compression(上下文压缩) 等 | — |
| `every-agent-desktop` | Electron 桌面版:web + hub + worker 一体打包(Windows x64 便携/安装包) | 本地 6101/6102 |

### 1.1 设计理念

1. **三层完全解耦**：hub / worker / 前端是独立程序、独立部署、独立演进,互相只认消息协议,不认实现;三者只依赖 `every-agent-contract`,互相零依赖。插件层依赖 `every-agent-plugin-api`(纯接口包),插件与 worker 经此解耦;插件对 `every-agent-worker` **任何 scope(含 test)零依赖**——测试需要 TaskRuntime/AgentContext/WorkerConfig 等桩时,在测试类内自建等价实现(实现 plugin-api 接口),不得借 worker 的具体实现类当测试脚手架。
2. **hub 不理解业务(红线)**：hub 是 RPC 转发中心——不校验事件语义、不认识"任务"、不维护业务规则。它唯一保留的校验是**连接级命名空间鉴权**(频道前缀是否匹配连接身份),这是身份边界,不是业务理解。
3. **磁盘是唯一事实源**：任务数据(事件日志 + 元数据)永久落盘;worker 内存只是运行期驻留,任务结束即销毁。重启、崩溃、换机后一切从磁盘重建。
4. **任务永久保留**：无自动清理;用户主动删除是唯一移除路径。
5. **运行即销毁、冷启动**：agent 运行结束即释放内存;再次运行是从磁盘载入历史后的一次**普通运行**,没有"续跑"特殊状态。
6. **worker 输出不受下游影响**：前端离线、hub 宕机(全部宕机也不例外)、落盘慢,都不阻塞任务主循环。
7. **阻塞等待零成本**：Java 25 虚拟线程——"必须等用户输入"的操作以挂起虚拟线程实现,不占资源。
8. **无账户模型**：持有密钥即身份;数据按命名空间隔离;无用户表、无注册。
9. **单隧道 + 三条动词**：公网到局域网 worker 只有 hub 这一条隧道,一切数据交换走它;隧道之上只有 RPC / Task / Event 三条交互动词,新增功能不改协议、不改 hub。
10. **统一执行上下文(ExecContext)**：执行数据沿 `task 层 → agent 层 → 工具执行链 → 授权链` 显式类型化逐层下传(§7.3/§7.20/§14.11);横切层(advisor/工具/授权/子 agent/审议)只见 `ExecContext`,不见任务域类型——未来工作流层实现自己的 ExecContext 即零改动复用全部横切基础设施。

### 1.2 硬性保证(验收底线)

| # | 保证 | 依托机制 |
|---|---|---|
| G1 | 前端断线/关浏览器不影响 worker 运行 | 发布与消费完全解耦(§7.1) |
| G2 | hub 宕机/重启(单个甚至全部)不影响 worker 运行 | 本地磁盘先行,hub 无状态 |
| G3 | 重连后能看到整个 task 从头到当前的完整数据,并继续流式输出 | 任务永久落盘 + `task.poll` 拉取 + stream 定向推送(§7.13) |
| G4 | 无前端在线时 askuser 挂起等待、零开销;前端任何时候上线都能看到并作答 | 虚拟线程挂起 + `ask.state` 周期重发(§7.8) |
| G5 | 任务只能被本人删除,且永不自动消失 | `task.delete` 唯一删除路径 |

---

## 2. 总体架构与部署拓扑

```
[家中/办公室 LAN]                         [公网服务器 ×N]
┌────────────────────┐   wss 出站长连接   ┌──────────────────┐        ┌─────────────────┐
│  worker(个人 PC)    │ ════════════════> │  hub(纯中转,不落盘) │ <═══════ │  前端静态站       │
│  跑任务 + 本地事件日志 │   每 hub 一条     │       ×N 个       │   wss   │  手机/任何地点浏览器 │
└────────────────────┘                  └──────────────────┘        └─────────────────┘
```

- worker 只发起**出站** WebSocket → 家庭路由器、NAT、无公网 IP 都无需配置。
- worker 可同时连接**多个 hub**(`worker.hubs` 配置列表):同 apiKey 配多个 hub = 可靠性冗余;不同 apiKey = 一台 worker 服务多个命名空间。
- **hub 是公网 → 局域网 worker 的唯一隧道**:模型配置、任务列表、文件树、git 管理等一切数据交换都走这条管道,但 hub 对内容零理解。模型 API 调用由 worker 本地直连 provider(出站 HTTPS),不经 hub。
- 前端发完运行命令可以直接关浏览器;worker 持续运行。重开浏览器后重订阅 + 拉取补齐 + 无缝续播(§10.2)。
- 公网传输强制 wss + TLS;hub 按公网服务加固(§6.3)。
- 一个命名空间可以有多台 worker(如家中 PC + 办公室 PC),前端按 presence 自选。

### 2.1 单隧道 + 三条动词

一切前后端数据交换都走 hub 管道,但"经过 hub"不等于"hub 承担功能"。管道之上定义三条交互动词,新增功能不改协议、不改 hub:

| 动词 | 形态 | 适用 | 判据 |
|---|---|---|---|
| **RPC** | `rpc{method,params,reqId}` → `rpc.ok/rpc.err`,大结果 `rpc.data` 分批 | 配置读写、任务列表、文件树、git 状态 | 秒级完成,有明确应答 |
| **Task** | 建为任务,复用全套机制(事件日志/seq/断线续播) | 跑 agent、git clone、批量文件操作 | 有进度、可能中断、需要历史回放 |
| **Event** | 频道广播,无应答 | presence、任务生命周期、配置/文件变更通知 | 只是状态通知 |

---

## 3. 技术栈与端口

| 层 | 技术 | 端口 |
|---|---|---|
| hub | Java 25,Spring Boot WebFlux(Reactor Netty WS) | 6101(/ws + /health) |
| worker | Java 25,Spring Boot + Spring AI 2,JDK 内置 HttpClient WebSocket(多连接 HubPool),虚拟线程 | 6102(仅 127.0.0.1 健康/管理) |
| web | React + TS,内置 TS 客户端 SDK | 5174(dev)/ 静态托管 |
| contract | 纯协议:帧/RPC 信封/错误码/身份哈希(Java DTO + TS 类型) | — |
| desktop | Electron(内置 Node 24),spawn 本地 hub/worker 子进程(jlink 精简 JRE 25) | 本地 6101/6102 |

版本统一由仓库根父 pom 锁定(Spring Boot 4.1.x / Spring AI 2.0.x),各模块不得各自升版本。

---

## 4. 身份、信任与安全模型

### 4.1 无账户:密钥即身份

- 无用户表、无注册。**apiKey 即身份**:`ownerKey = sha256(apiKey)`(64 位小写 hex,即频道名中的 `<K>`)。
- 持有密钥即该命名空间全权,密钥泄露 = 身份泄露;公网部署必须 wss。

### 4.2 双道鉴权:hub key 管"连上",worker apiKey 管"访问"

- **hub key(必填)**:hub 启动即强制校验 `hub.hub-key`(配置直接填原始密钥,程序启动自算 sha256,未配置拒绝启动)。所有 frontend/worker 连接必须在 hello 携带原始 `hubKey`,缺失/不符一律 `NOT_AUTHENTICATED`。
- **worker apiKey(按 worker 各自配置)**:连上 hub 后,要访问某台 worker 的任务、文件、git 数据,必须持该 worker 的 apiKey 建立对应命名空间的连接;worker 端 RPC 按连接身份处理。
- 前端因此有**两类连接**:一条"目录连接"(用 hubKey 连,订阅 `u.<sha256(hubKey)>.workers` 看在线 worker 目录)+ 每条 worker 一条"数据连接"(用该 worker 的 apiKey,订阅其 `u.<K>.tasks/evt`,任务与 RPC 走这条)。

### 4.3 频道即鉴权边界

- 频道名本身即 ACL:频道必须落在连接自己的 `u.<ownerKey>.` 前缀内(字符集 `[a-z0-9._-]`,长度 ≤160)。hub 对每个 sub/pub 强制校验,越命名空间返回 `ACL_DENIED`。
- 由于前缀校验由 hub 在连接级完成,A 的连接物理上无法订阅 `u.B.**`(B 为另一命名空间)→ 客户端伪造归属不可能。
- **同命名空间内互信(取舍)**:持有同一 apiKey 的任何角色可 pub/sub 该命名空间内任意频道;任务归属、越权等业务规则全部由 worker 处理(hub 不理解业务)。对单人/小团队部署可接受。

### 4.4 连接级规则

- **连接抢占**:同 ownerKey + role=worker + clientId 的新连接到来 → hub 关闭旧连接,presence 依次发 `worker.offline` → `worker.online`(防僵尸连接挡重连)。前端 clientId 自由,不抢占。
- **presence**:worker 连接建立/断开时,hub 向 `u.<K>.workers` 广播 `worker.online/worker.offline`(payload 含 workerId、ownerFingerprint = ownerKey 前 16 位、meta 如 hostname/version)。
- **错误分级**:`NOT_AUTHENTICATED`/`VERSION_MISMATCH` 断开连接;`ACL_DENIED`/`FRAME_TOO_LARGE`/`RATE_LIMITED` 拒绝单帧、连接保持。

---

## 5. 消息协议

所有帧均为 **UTF-8 JSON 文本帧**,`type` 区分控制帧,`event` 区分业务事件。**contract 只承载纯协议**(帧信封、握手字段、RPC 信封与通用错误码、身份哈希);事件名、任务 DTO、RPC 方法名、业务频道构造器全部住在 worker 侧 `proto`(及 TS SDK 对应模块)——业务演进零改 contract、零改 hub。

### 5.1 连接与握手

```
wss://hub:6101/ws
→ { "type":"hello", "ver":3, "role":"frontend"|"worker", "apiKey":"sk-...", "hubKey":"hub-secret", "clientId":"fe-1",
    "meta": { "hostname":"home-pc", "version":"0.1.0" } }        // hubKey 必填;meta 可选,worker 上报
← { "type":"welcome", "ver":3, "sessionId":"s-17", "serverTs":1755859200000 }
```

- `ver` 为协议版本(当前 **3**),握手协商一次;无共同版本 → `VERSION_MISMATCH` 断开。不逐帧携带版本。
- 未 hello 就 pub/sub → `NOT_AUTHENTICATED` 并断开。
- 控制帧全集:`hello` `welcome` `sub` `unsub` `pub` `msg` `error` `ping` `pong`。
- **ping/pong(应用层心跳帧)**:`{"type":"ping","ts":…}` / `{"type":"pong","ts":…}`。前端与 worker 的应用层心跳:间隔 5s,**仅当本周期内无任何帧到达时才发 ping**;判死唯一依据 = **发出 ping 后 15s 无任何帧到达**(不是「距上帧超时」——主动退订降载后连接合法空闲,按距上帧判死会误杀;探测有应答=活,探测超时=死);hub 收到 ping 即回 pong(不路由、不记录)。协议 v3 全链端统一升级,不兼容 v2。

```jsonc
// 订阅 / 退订(hub 无缓冲,sub 不带 since)
{ "type":"sub",   "channel":"u.K.worker.w7.evt" }
{ "type":"unsub", "channel":"u.K.worker.w7.evt" }

// 发布(ext 开放扩展;任务流实时增量由 worker 定向 pub 到 stream 频道,ext.target=前端 sessionId)
{ "type":"pub", "mid":"uuid", "channel":"u.K.tasks",
  "event":"task.updated", "ts":1755859200000, "payload": { "taskId":"t_k3f0", "status":"running" },
  "ext": { "traceparent":"00-…-01" } }

// hub → 订阅者(原样投递,附已认证 from,ext 原样转发)
{ "type":"msg", "channel":"u.K.tasks", "event":"task.updated",
  "ts":1755859200000, "from":{ "clientId":"worker-1", "role":"worker" },
  "payload": { … }, "ext": { … } }

// 错误
{ "type":"error", "code":"ACL_DENIED|NOT_AUTHENTICATED|FRAME_TOO_LARGE|RATE_LIMITED|VERSION_MISMATCH", "detail":"..." }
```

### 5.2 频道表(全部命名空间化)

| 频道 | 内容 | 谁可订阅/发布 |
|---|---|---|
| `u.<K>.workers` | `worker.online` / `worker.offline`(hub 自动 presence,**仅 hub 可发布**) | 命名空间内任意订阅 |
| `u.<K>.worker.<id>.cmd` | `rpc`(一切请求-命令的统一信封) | 命名空间内任意角色 |
| `u.<K>.worker.<id>.evt` | `rpc.ok/rpc.err/rpc.data/rpc.progress` + 通知事件(`config.changed`、`fs.changed`、`workspaces.changed`) | 命名空间内任意角色 |
| `u.<K>.worker.<id>.input` | **worker 级输入频道**:`task.input` / `ask.reply` / `stream.ack`(worker 每连接订阅一次,订阅数 O(worker×hub)) | 命名空间内任意角色 |
| `u.<K>.tasks` | 任务生命周期:`task.created` / `task.updated` / `task.deleted` | 命名空间内任意角色 |
| `u.<K>.task.<id>.stream` | **运行中任务实时增量**(worker 定向推送,`ext.target=sessionId` 只投该会话) | 命名空间内任意角色 |
| `u.<K>.term.<termId>.stream` | **内嵌终端实时输出**(worker 定向推送：event `term.output` payload `{data: base64}`；进程退出推 `term.exited`) | 命名空间内任意角色 |

> **stream 订阅通知**:前端 sub/unsub `u.<K>.task.<id>.stream` 时,hub 向该命名空间的在线 worker 连接定向发 `subscriber.join/subscriber.leave`(`payload={sessionId,taskId}`)——这是无状态 fire-and-forget 通知(hub 不存订阅簿),worker 据此按 (sessionId,taskId) 建/销 DataPusher(§7.13)。

hub 对频道名不解释业务语义:它只做"前缀必须匹配本连接命名空间 + 字符集/长度合法性"这一件事。业务规则(如任务归属)全部住在 worker。

### 5.3 任务流事件(持久 / 瞬态)

事件分**持久**(落盘 + 回放)与**瞬态**(只存内存 EventLog 尾部,消费 seq 但不落盘)。任务流事件双通道:实时增量由 worker 定向推送到 stream 频道,历史回放/上滚/区间/兜底统一经 RPC `task.poll` 拉取——两路读同一本日志、同一 seq 空间,前端按 seq 去重归并。

| 频道 | 事件 | 持久 | payload 要点 |
|---|---|---|---|
| cmd | `rpc` | — | `reqId, method, params` |
| evt | `rpc.ok` / `rpc.err` | — | `reqId, result` / `reqId, code, message` |
| evt | `rpc.data` | — | `reqId, batch, hasMore`(流式应答分批) |
| evt | `rpc.progress` | — | `reqId, message, pct?` |
| evt | `config.changed` / `fs.changed` / `workspaces.changed` | — | `{keys}` / `{workspace,path,kind}` / 注册表快照 |
| tasks | `task.created` / `task.updated` / `task.deleted` | — | 任务摘要(含 `pendingInputs` 待消费输入快照,运行时态不落盘);deleted:`{taskId}` |
| task.poll / stream | `user.message` | ✓ | 用户输入入日志:`{content, data:{rawContent?}}`(统一事件模型,content=AI 可见明文;rawContent=原始输入含 opaque token 串,仅用于前端回放还原胶囊,在 `data` 内、非顶层) |
| task.poll / stream | `delta` / `thinking` | ✗ 瞬态 | 逐 token / 推理增量(主/子同名,子带 agentId) |
| task.poll / stream | `message` | ✓ | **每轮权威完成记录**:`{thinking, text, toolCalls:[{id,name,arguments}], agentId?}` |
| task.poll / stream | `tool.result` | ✓ | `{callId, name, summary, truncated?}` |
| task.poll / stream | `usage` | ✓ | 每轮模型 token 实测用量 + 累计 |
| task.poll / stream | `ask.create` / `ask.state` / `ask.resolved` | ✓ | 见 §7.8 |
| task.poll / stream | `agent.started` / `agent.done` | ✓ | agent per-run 生命周期(主/子统一,§7.20.1);started 携带 title/creator/metadata,done 携带 usage+末轮正文 |
| task.poll / stream | `agent.status` | ✓ | 主/子统一状态事件:running / waiting-user / done / failed / stopped |
| task.poll / stream | `error` / `cancelled` | ✓ | `{message(带 agentId 即该子 agent 失败)}` / `{by}` |
| stream | `message.edited` | — | 消息编辑同步事件(非持久,worker 截断磁盘后广播到 stream 频道):`{seq, text, rawContent?}`;客户端据此移除 seq > 该消息的本地事件并更新消息内容 |
| task.poll / stream | `task.trace` | ✓/✗ 按 ext | **统一纯显示 trace**(重试生命周期、任务耗时、模型容灾、授权审计等):`{traceId, kind, title, summary?, content?, status?, createdAt, metadata?}`;`ext.persist=false` 标记瞬态实例 |
| task.poll / stream | `round.opened` / `round.closed` | ✗ 瞬态 | 轮次开/闭通知:`{startSeq,user}` / `{startSeq,endSeq,finalReply}` |
| input | `task.input` | — | `{taskId, text, rawContent?}`(worker 级频道;热非终态入队/终态触发一次普通运行) |
| input | `ask.reply` | — | `{askId, answer}` |
| input | `stream.ack` | — | `{taskId, creditIndex}` 流消费进度回报(§7.13 背压) |

> **线上 wire 形态**:事件名不分主/子 agent(delta/message/error 同名),归属由 `agentId` 决定——主 agent payload 不带 agentId,子 agent 必带;磁盘 jsonl 每行必记 agentId。`agent.started`/`agent.done`/`agent.status` 为 agent 生命周期专用名:**状态 = per-run() 生命周期**——每一轮 `Agent.run()` 是一个完整周期 `agent.started → agent.status{running} → [agent.status{waiting-user} ⇄ running]* → error? → agent.done → agent.status{done|failed|stopped}`,队列取下一条输入/复用续跑即开新一轮(again `started`)。四类事件由 advisor 链驱动、由 agent 实体一处发射(`AgentStatusAdvisor` 把流生命周期信号翻译成 `AgentEntity` 的 per-run 状态机方法,§7.20.1),**主 agent 与各插件派生 agent 一视同仁**;task 层与插件不再手搓 agent.* 事件(插件仅在「运行体从未进入流生命周期」的兜底路径调 `claimTerminal`,CAS 保证不重复)。task 级状态(`task.updated`,由 StatusNode/TaskManager.setStatus 驱动)与 agent 级状态分属两个维度,不互相替代。

单帧上限默认 16 MiB(可配 `hub.max-frame-bytes`);超大工具结果截断为 `summary + truncated:true`。

### 5.4 seq 规则(协议的秩序根基)

- `seq` 由 worker 按任务**从 1 单调递增分配**,写入事件日志的瞬间确定;**跨运行延续**(再运行从上次水位接续)。
- **瞬态事件也消耗 seq**(delta/thinking 等消费序号但不落盘)→ **磁盘回放的 seq 有洞是合法状态**;seq 仍严格递增、从不复用。
- **`seq` 属于任务流事件空间**(每任务一个),wire 上以**字符串**传输(`String.valueOf(seq)`):seq 是 64 位 Snowflake(≈10^17),远超 JS `Number.MAX_SAFE_INTEGER(2^53)`;按 number 输出会在浏览器 `JSON.parse` 丢精度。前端 `compareSeq` 按「位数优先 + 字典序」比较字符串,等价数值序且不丢精度。
- 实时与历史来自同一本日志、同一 seq 空间 → 前端按 seq 去重、排序;游标 lastSeq 取自事件项的 seq 字符串。
- cmd/evt 频道的 rpc 帧不携带 seq(seq 只属于任务流事件空间)。

### 5.5 三条动词与 RPC 方法注册表

worker 端 `RpcDispatcher` 注册方法;应答回**请求来源连接**的 `evt` 频道,reqId 供请求方匹配,同命名空间其余前端可作缓存刷新。

| method | 说明 |
|---|---|
| `tasks.list` | 任务列表快照(内存运行中 + 磁盘索引合并;可选 `workspace`/`limit`/`offset`/`taskIds`) |
| `task.run` / `task.cancel` / `task.delete` | 运行任务(**创建/续跑合一**):不传 taskId=新建(必带 workspace)并开跑;传 taskId=载入老任务历史续跑(运行中则入队)。`metadata` 为一次性插件参数(经 `runParams` 下传、不落盘),队列插件认 `{insert:true, index}`=队列项「插入到当前对话」(§7.16)。delete = 唯一删除路径(运行中拒绝) |
| `task.poll` | 任务流纯拉取:历史(磁盘)∪ 实时(内存尾部)按 seq 归并;支持 afterSeq/beforeSeq/区间/mode('events'/'rounds')/waitMs 长轮询 |
| `task.rounds` | 轮次索引拉取(rounds.jsonl 全部行 + 运行中未闭合轮 open;旧任务首次惰性全量生成落盘) |
| `task.roundTail` | 按轮起点(startSeq)取该轮末尾 limit 条事件,用于初始渲染 |
| `task.agents` | 子 agent 台账一次性拉取(前端打开任务详情、建子 agent 胶囊列表的唯一取数口;live 任务取内存台账,磁盘路径 agents.json 优先、旧任务回退 meta.json 的 agents 数组只读;按 createdAt 升序;**按顶级 `creator=subagent` 过滤(旧条目无顶级 creator 时回退 `metadata.creator`,两者皆空视为旧格式子 agent 保留)——审议 agent 等 creator≠subagent 的条目不出现在子 agent 列表;主 agent 条目恒保留(前端 `agentMeta['']` 的 title/usage/context 冷启动基线只认台账,§7.20.1**;应答 `{agents:[台账项], mainAgentId}`);方法由 subagent 插件注册,方法名常量跟注册方走(住插件侧,§14.11) |
| `task.fileChanges` | **file-change 插件注册的 RPC**(方法名常量与语义全住插件侧,task 核心不感知,§14.11/§7.15.2):带 `roundId` → 该轮变更全文 `file-changes/<roundId>.json` 的 `{changes:[...]}`(含 beforeContent/afterContent);**省略 `roundId`** → 全任务各轮轻量摘要 `{rounds:[{roundId, changes:[{filePath,fileName,changeType,saveCount}]}]}`(前端轮末面板一次拉全,不做逐轮 N 次 RPC) |
| `task.search` | 任务内容搜索(内置 rg + worker 后处理):`workspaceId` 必填且必须是稳定 id 形态(`defaultworkspace` / `w_xxxxx`,拒绝路径穿越),按 `workspaces/<workspaceId>/tasks/<taskId>/` 枚举任务目录,复用 rg 搜索 `rounds.jsonl`(每行一轮,含 user/finalReply 正文);入参 `pattern` / `isRegex` / `caseSensitive` / `wholeWord` / `maxResults`(默认 500),pattern 语义与 `fs.search` 共用 `buildMatchArgs`;rg 命中 JSON 原始行后由 worker `parseRoundLine` 解析、对 user/finalReply 干净文本二次匹配(消除字段名/转义噪音,同时得到准确 `matchIndex`/`matchText`);结果项 `{taskId, title, workspace, workspaceId, status, matches:[{roundIndex, field:'user'|'finalReply', line, matchIndex, matchText}]}`,按任务聚合;大结果复用 `rpc.data` 分批 + 末帧 `ok` 汇总(§5.4) |
| `task.queueRemove` / `task.queueMove` | 删除/重排某条队列输入(task-input-queue 插件注册;运行中热队列直改内存、终态改磁盘悬空队列 queue.jsonl) |
| `task.queueSnapshot` | 拉取队列快照 `{taskId, pendingInputs:[…), pendingInputsRaw:[…]}`(task-input-queue 插件注册;热任务取内存 InputQueue,终态任务读 queue.jsonl 悬空队列)——前端队列面板自持数据源(插件专有数据不进核心 ComposerPanelCtx/taskStore);`pendingInputsRaw` 与 `pendingInputs` 等长按位对齐(空串=无原始内容),供面板「编辑」回填还原胶囊;入队/消费/增删均广播 `task.updated`(携带 pendingInputs)驱动刷新 |
| `task.message.edit` | 编辑已发送的用户消息:截断 seq > 该消息的所有磁盘事件(仅 *.jsonl/rounds.jsonl,**不删插件数据文件**——agents.json/file-changes/ 残留陈旧条目被接受,task 核心不感知插件文件名;后续可增加截断事件通知由插件自清)、原地更新该消息内容、广播 `message.edited` 同步事件、冷启动重跑(不写新 user.message,对话历史已含编辑后的消息);任务运行中拒绝 |
| `config.get` | 模型配置只读(Spring 配置承载,见 §7.17) |
| `config.reload` | 重新读取模型配置(重新解析 worker.models,应用用户在外部 YAML 中的修改);广播 `config.changed{keys:["models"]}` |
| `skill.reload` | 重新扫描外部 skill 列表(用户在系统技能目录下增删 skill 目录后热加载);广播 `config.changed{keys:["skills"]}`,前端 `/` 菜单下次打开即拉取最新列表 |
| `workspaces.list` / `workspaces.add` / `workspaces.remove` | 工作区注册表 CRUD(多工作区并行) |
| `workspaces.resolveMissing` | 启动自检缺失工作区落定:action=delete(删除注册并级联任务数据)/redirect(纠正到新目录并迁移任务归属) |
| `fs.list` / `fs.reveal` / `fs.read` / `fs.write` / `fs.mkdir` / `fs.move` / `fs.delete` / `fs.browse` | 工作区文件操作,**必带 workspace 参数**,沙箱限定;文件树懒加载;沙箱附加根按操作语义分流(§7.17):**只读操作**(`fs.list`/`fs.reveal`/`fs.read`/`fs.revealInOs`)并入工作区外部授权根(externalRoots,完全读写已授权)+ 系统技能目录(skills 读写免授权,§13.8)——使前端「打开文件」标签页能读取 AI 已读的 skill/外部授权文件;**写操作**(`fs.write`/`fs.mkdir`/`fs.move`/`fs.delete`)仅并入 externalRoots(完全读写),技能根同样并入(读写均开放,§13.8);`fs.browse`(不经沙箱)列盘符/逐层浏览目录,可选 `includeFiles`(boolean,缺省 false 仅目录,完全兼容现有行为):true 时目录条目同时列出文件,每条目带 `kind:"file"\|"directory"`,响应带 `supportsFiles:true` 能力标记(前端能力探测;老前端不传/老 worker 不带按 must-ignore 双向兼容,§5.6) |
| `fs.revealInOs` | 在**运行 worker 的宿主机器**上打开系统文件管理器并选中目标(资源树右键「在系统文件管理器中显示」,对标 VSCode Reveal in File Explorer),**必带 workspace 参数**,路径经沙箱 `resolveExisting` 校验(防越界/符号链接逃逸);Windows `explorer.exe /select,<path>`(fire-and-forget,退出码不表征成败)、macOS `open -R`、Linux 优先 freedesktop FileManager1 `ShowItems` 选中目标、无 dbus/无注册实现退化 `xdg-open` 打开所在目录;argv 直传无 shell 解析;无桌面环境(无头 worker/无文件管理器)抛 IO 异常转 RPC 错误;远程访问场景窗口在 worker 所在电脑弹出;老前端不调用零影响 |
| `term.open` / `term.input` / `term.resize` / `term.close` | Web 内嵌终端会话(§7.18，真 PTY)：`term.open` 必带 `workspace`+`termId`(前端生成,先 sub 频道再 open 防丢首帧)+`path`(目录,沙箱 `resolveExisting` 校验,非目录拒收)+`cols`/`rows`+可选 `shell`，返回 `{termId, pid}`；`term.input` 入参 `{termId, data(base64)}`；`term.resize` 入参 `{termId, cols, rows}`；`term.close` 入参 `{termId}`。输出经 `u.<K>.term.<termId>.stream` 定向推送；老前端不调用零影响，老 worker 无此方法时前端按 must-ignore 降级提示 |
| `fs.search` | 工作区文本内容搜索(内置 rg,§5.10),**必带 workspace 参数**,沙箱 jailed 到工作区根;入参 `pattern` / `isRegex` / `caseSensitive` / `wholeWord` / `includeGlobs` / `excludeGlobs`(逗号分隔 glob,include 用 `-g '!*' -g glob` 放行、exclude 用 `-g !glob`) / `maxResults`(默认 1000,触顶 kill rg 置 `truncated`);rg 参数 `--hidden --json --crlf -e <pattern>`(固定串加 `--fixed-strings`),逐行解析 JSON lines(`type:match` 的 `submatches` → 命中片段);结果项 `{path, lineNumber, line, matchIndex, matchText}`,按文件聚合;大结果复用 `fs.read` 的 `rpc.data` 分批 + 末帧 `ok` 汇总(§5.4);老前端不调用零影响,老 worker 无此方法时前端按 must-ignore 降级纯前端搜索(§5.6) |
| `git.status` / `git.log` / `git.diff` / `git.commit` / `git.pull` / `git.push` / `git.discard` / `git.init` / `git.clone` / `git.remote.add` / `git.remote.list` | 工作区 git 快操作,必带 workspace;由 `NativeGit` 调宿主原生 git argv 直传执行(§7.12) |
| 大型迁移(批量 checkout / 大仓库迁移) | 建为 Task,进度走任务流 |
| `git.credential.save` | 保存 git 远端凭证(AES-GCM 加密落盘,§7.12;只写不读回) |
| `slash.list` / `slash.select` / `slash.cancel` / `slash.taskTokens.apply` | 斜杠命令清单与选中/取消/任务级 token 应用(§7.16) |
| `mention.query` | `@` 文件搜索(后端子序列模糊匹配 + 隐藏规则 + 截断 10 条) |
| `rpc.cancel` | 取消进行中的长 RPC(Future.cancel) |
| `sys.methods` / `sys.info` | 能力发现:本 worker 支持的方法清单与版本、workspace/模型/hub 元信息 |

**扩展规则**:协议固定的是交互形状(请求/应答/分批/进度/取消/通知),不是内容;`method` 只是字符串命名空间(`域.动作`),`params`/`result` 是自由 JSON。新功能 = 注册新 method,零改协议、零改 hub。兼容规则:method 只加不改;breaking change 用新名,老客户端靠 `sys.methods` 发现能力。

### 5.6 前向兼容与预留

- **must-ignore 总则**:接收方对信封中任何未知字段一律忽略、不得报错——这是真正的演进机制。
- **`ver`(握手级)**:协议版本在 hello/welcome 协商一次;方法级版本由 `sys.methods` 报告。
- **`ext`(帧级,开放映射)**:自由 JSON 键值;hub 零理解、逐帧原样转发。生态约定:`ext.traceparent`/`ext.baggage` 按 W3C Trace Context 携带链路追踪;worker 将其随持久事件入库,`task.poll` 重放保真。
- 明确不预留:逐帧版本号、重试计数、schema id。

---

## 6. 消息中心(every-agent-hub)

### 6.1 职责与非职责

| 职责 | 非职责 |
|---|---|
| 握手鉴权(apiKey→ownerKey、hubKey 校验)、频道前缀校验(字符集/长度/命名空间)、路由扇出 | ❌ 任何消息缓冲/补播/ack(sub 无 since) |
| presence(worker 会话开合 → `u.<K>.workers`)+ stream 频道订阅通知(join/leave,无状态 fire-and-forget) | ❌ 任何持久化 |
| 心跳、慢消费者保护、公网加固 | ❌ **任何业务理解/业务校验**(不知道"任务"是什么——红线) |

hub 只解析信封的 `type` / `channel`(及 hello 握手字段);`event` / `seq` / `payload` / `ext` 原样转发,一律不得读写。不存在角色×频道权限矩阵(同命名空间互信);不存在订阅簿(stream 订阅通知是无状态 fire-and-forget)。

### 6.2 内部组件

- **SessionRegistry** — sessionId → 连接、角色、ownerKey、订阅集。
- **ChannelRegistry** — channel → 订阅者集合;pub 到来即遍历投递(带 `ext.target` 时只定向投给该 sessionId);前端 sub/unsub stream 频道时向该命名空间在线 worker 发 join/leave 通知。
- **PresenceService** — worker 会话建立/断开时向 `u.<K>.workers` 发 worker.online/offline;订阅时补发全量快照。
- **慢消费者保护** — 每连接出口队列上限 1000 条,溢出断开;前端自动重连 + 重新拉取,不丢数据。
- **心跳** — WS protocol-level ping 每 15s,45s 无 pong 判死;同时响应应用层 ping 帧——收到即回 pong,不路由、不记录(§5.1)。

### 6.3 公网加固清单

wss 强制 + 证书;hello 失败限速(防 key 枚举);单 IP / 全局连接数上限;单帧上限;pub 令牌桶;hub-key 连接鉴权(必填,fail-fast 启动);apiKey 白名单不做(它定义命名空间边界)。

---

## 7. Worker 执行层(every-agent-worker)

### 7.1 出口路径:本地事件日志先行(解耦的根基)

```
任务虚拟线程 ──append──→ 内存事件日志(每任务,分配 seq)──异步──→ 磁盘 *.jsonl(按 agent 分文件)
                              │                                      ↑
                              │ DataPusher(按已订阅前端定向推送)        │
                              ↓                                      │
              stream 频道(ext.target=sessionId,实时增量)          task.poll 拉取
                                          (内存尾部 ∪ 磁盘反向窗口按 seq 归并,rpc.data 分批)
```

- 发布永不阻塞任务线程:无消费者、hub 全部宕机、落盘慢,任务完全无感。
- 持久化是"订阅本地日志的异步 sink"(fire-and-forget)。
- 任务终态时 flush 落盘 → 更新 meta → **销毁内存驻留**。

### 7.2 内部组件

| 组件 | 职责 |
|---|---|
| **HubPool** | 多 hub 出站连接池:每连接(HubLink)独立 WS 客户端 + 重连循环;HubLink 应用层心跳——5s 周期仅空闲时(本周期无任何帧到达)才发 ping,ping 后 15s 无帧判死,主动关闭触发重连(§5.1);路由 API 按命名空间扇出 / 回源 |
| **EventLog** | 每任务内存日志(运行中);append 即分配 seq;`seed(seqLastOf)` 供再运行接续;尾部只读供 task.poll 归并 |
| **TaskStore** | 持久层:`workspaces/<workspaceId>/tasks/<taskId>/` 按 agent 分文件 `*.jsonl`;启动扫描建索引;**随机访问分块反向读取原语**(ReverseLineReader 从文件尾 64KB 块向前扫,不整文件重扫) |
| **TaskManager** | 运行编排:创建/取消/再运行(冷启动)/删除;`finish()` 驱逐内存驻留 |
| **DataPusher / DataPusherManager** | 定向推送器(§7.13):每 (sessionId,taskId) 一个虚拟线程,把运行中任务 EventLog 增量(含瞬态)推到 stream 频道;`push()` 组装 ext/payload 后委托 `WebSocketEmitter.push()` 做背压 + conn.pub(§7.19) |
| **WebSocketEmitter** | 被动推送管道(§7.19):只做背压控制 + `conn.pub`,不读 EventLog、不回扫、不对账;DataPusher 回扫路径与 EventEmitter 实时路径共用同一背压窗口;`DataPusherManager.onAck` 路由到此 |
| **ConversationLoader** | 冷启动:从磁盘 jsonl 重建 conversation |
| **PendingAsks** | askId → CompletableFuture;ask 工具在此挂起(§7.8) |
| **TaskPoll** | 应答 `task.poll` RPC:磁盘反向窗口 ∪ 内存尾部按 seq 归并,rpc.data 分批 |
| **RpcDispatcher** | 方法注册表;每请求一个虚拟线程 |
| **功能模块** | ConfigStore(模型配置只读)、WorkspaceManager(工作区注册表)、FsService(沙箱内文件操作)、GitService(工作区 git 快操作 RPC)、NativeGit(宿主原生 git 执行器,§7.12)、GitCredentialStore(git 凭证加密存储) |
| **任务路由索引** | `taskId → {dir, summary}`(磁盘任务的元数据索引,boot 扫描构建) |

**无修剪、无 retention**:任务永久保留。内存 EventLog 受 `maxEventsPerTask`(默认 50 万)护栏(防 RAM 失控;磁盘 jsonl 全量不受影响)。计数口径:护栏只计**持久(落盘)事件**;流式瞬态事件(delta/thinking 及 `ext.persist=false` 的 trace)虽进内存缓冲供实时推送,但不占用计数——瞬态风暴由 `ModelLengthGuardAdvisor`(§7.3)治理。

### 7.3 Agent 执行链(Spring AI Advisor 生态)

worker 的 agent 执行**复用 Spring AI 2 框架**,不手搓 agent 循环/工具循环/响应聚合。(红线:`AgentRunner` 等执行链只是很薄一层——把 `Prompt` 交给 `ChatClient`,`ToolCallingAdvisor` 接管工具循环,自定义 `Advisor` 注入技能/记忆/护栏等增强;新增 agent 能力优先做成 Advisor。)

主 agent advisor 链(每 run 新建实例,状态随实例隔离):

```
AgentStatusAdvisor(agent 生命周期事件,最外层 +5) → RoundIndexAdvisor(轮次索引+耗时,+10) → SkillAdvisor(内置 skill 渐进式披露索引;外部 skill 不进提示词,仅经 `/` 菜单手动选用) → LoopRepeatGuardAdvisor(事件发射 + 工具循环 + 死循环检测)
→ DialogInsertAdvisor(队列项「插入到当前对话」,主 agent 专属) → EmptyResponseRetryAdvisor(空响应重调,独立插件)
→ TransientErrorRetryAdvisor(瞬时错误退避,独立插件) → AdaptiveMaxTokensAdvisor(自适应输出预算,独立插件,order=工具循环+250,Guard 外侧)
→ ModelLengthGuardAdvisor(输出预算耗尽护栏,独立插件 model-length-guard,finish_reason=length,order=工具循环+300)
→ ContextCompressionAdvisor(上下文压缩,独立插件,最内层重试/压缩侧) → RateLimitAdvisor(模型请求限流,独立插件,order=工具循环+500,最内层)
```

- agent 生命周期事件由 `AgentStatusAdvisor`（全链最外层，`StreamAdvisor`）驱动 `AgentEntity` 的 per-run 状态机发射：`adviseStream` 入口 → `beginRun()`（`agent.started` + `agent.status{running}`）；`doOnComplete`/`doOnError`/`doOnCancel` → `claimTerminal(completed|error|stopped, 附言)`，终态发射顺序固定 `error? → agent.done → agent.status{终态}`。它**不继承** `ToolCallingAdvisor`——只需「整轮一次」的流生命周期信号，而工具循环递归只重入比自己更内层的 advisor，故挂在外侧恰好每次 `AgentRunner.run()` 进一次；最外层还保证终态晚于 message/usage 与 rounds 闭合落盘（前端以主 agent 终态 `agent.status` 为拉 rounds 的触发点）。详见 §7.20.1。
- 核心事件发射由 `WorkerToolEventAdvisor` 完成(继承 Spring AI `ToolCallingAdvisor`,重写受保护 hook 发射 delta/message/usage/tool 等事件,**不另起一层重复实现递归循环**)。
- 重试退避算法由 `worker.retry.strategy` 选择,空响应重试与瞬时错误重试共享:`fixed`(默认,固定 `backoff-base-ms` 间隔、`max-request-retries` 默认 30 次,适合网络抖动场景的稳定节奏恢复)或 `exponential`(`base × factor^(n-1)` 递增退避);未知取值回落 `exponential`(旧行为)。
- `ModelLengthGuardAdvisor`(独立 `model-length-guard` 插件,order=工具循环+300,瞬时重试内侧、上下文压缩外侧):识别「输出预算耗尽」这一确定性失败,三条路径殊途同归报同一错误。**帧+异常双信号改造**:检测到耗尽时先向下游下发合成 `finish_reason=length` 帧,再抛非重试异常 `ModelLengthExhaustedException`。①真实 length 帧:不在 doOnNext 就地抛(会把元素转成 error,帧到不了外层)——改为透传帧 + 记 flag,流 complete 时若 flag 置位再抛;②stall/③断流:onErrorResume 里先 concatWith 下发合成帧(`ChatGenerationMetadata.builder().finishReason("length")`)再 Flux.error(原判定异常),Reactor 保证 onNext 先于 onError 到达外层。三条路径的「输出已达上限」判定(无 tokenizer,CJK≈1 token、其余≈4 字符 1 token):模型配置了 maxTokens 时用 20%~40% 容差的 ≈maxTokens 比例判定;**未配置 maxTokens 时**(provider 用服务端默认预算,客户端不可见)用绝对阈值兜底——自估输出 ≥ `worker.limits.length-disconnect-min-tokens`(默认 32768)即判定,「断流+已输出数万 token」是预算耗尽强信号,重试代价极高(每次重放整段长思考,长思考模型一轮可耗数万 token、循环几十分钟)。错误为自定义非重试异常(穿透瞬时重试,避免放大瞬态事件风暴),信息含「精简输入/拆分任务/降低 reasoningEffort/调大 maxTokens」建议,经任务层 error 收口呈现给用户。**合成帧语义不可移除**——这是跨插件协议契约:AdaptiveMaxTokensAdvisor(§7.3.1)只认帧,不认异常类型/文案。
- `LoopRepeatGuardAdvisor` 叠加**死循环检测**:比较本轮与上一轮工具调用签名(名称+参数集合,顺序无关),连续重复达 `worker.limits.max-repeated-tool-rounds`(默认 3)**不再直接中断**——而是把一条提醒文本作为该轮工具执行结果回传 AI,留一次纠正机会(本轮不真正执行工具,与 `MissingToolCallbackResolver` 同构:错误信息作为工具结果回传由 AI 自纠);若提醒后下一轮仍下发完全相同的工具调用,才中断任务(error 收口)。守卫逻辑不在 advisor 体内,而在装饰 `ToolCallingManager` 的 `LoopRepeatGuardToolManager` 中(框架唯一允许「既阻止真实工具执行、又能注入合成工具结果回传模型」的扩展点是 `executeToolCalls`),advisor 仅负责把守卫装饰器装配到工具循环入口,事件逻辑全部继承 `WorkerToolEventAdvisor`。
- `DialogInsertAdvisor`(普通 StreamAdvisor,在主 agent 的工具循环下行阶段)把任务队列「插入到当前对话」的用户消息 drain 并追加给 AI + 发射 `user.message` 事件 + 追加 agent conversation;仅主 agent 装配(`DialogInsertAdvisorProvider.appliesTo` 比 agentId==mainAgentId,子 agent 对话是一次性嵌套,不接收任务队列输入,也不与主 agent 抢同一个插入队列)。
- 主 Agent 与子 Agent **共用同一执行入口与 Advisor 链**,仅 agentId 不同;子 agent 不挂计时与 skill,但同挂上下文压缩。
- **advisor 取数统一走 `AgentContext.execution()`(ExecContext 槽位)**:taskId→`subjectId()`、模型配置→`snapshot()`(configId 经 `snapshot().configId()` 取)、事件→`emitter()`、终态判定→`terminal()`;agent 装配经 `ctx.agentFactory().create(agentId)`(预绑定工厂,静态代理)。worker 不再有 `properties` 黑盒 map 与 `get("taskEntry")` 强转(§7.20/§14.11)。**fileChanges 不进 ExecContext 也不进 TaskRuntime**(插件功能不占核心接口,§14.11 判据)——collector 是 `FileChangeAdvisor` 的 per-run 实例字段,按轮落盘走 `RoundClosedListener` 回调,读侧走插件自己的 `task.fileChanges` RPC(§7.15.2)。

### 7.3.1 自适应输出预算（adaptive-max-tokens 插件）

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

### 7.4 组合模型容灾（model-pool 插件）

**模型池 = 一个模型 provider**(`provider: model-pool`,经 `ChatModelEnhancer` SPI 委托给 `model-pool` 插件产出 `ModelPoolChatModel`),主/子 agent 与 AI 审议共用同一入口:

- `worker.models` 里新增一种特殊配置项:`model` 字段用逗号分隔的成员 configId 列表(`model: "deepseek,qwen"`,首个 = 主模型),configId 指向它即「任务默认带容灾」。该 `model` 字段格式（逗号分隔的成员 configId 列表）是 **model-pool 插件的私有配置格式**,`ConfigStore` 原样存储 provider/model 字符串,不解析成员格式。
- 成员格式解析、校验（逗号分隔解析/trim/去空/去重/禁池套池/成员不存在跳过+告警/全空抛异常）均为 **model-pool 插件私有逻辑**,worker 核心 `ConfigStore` 不解析成员格式。成员笔误/漏配的暴露时点从「worker 启动期拒绝」后移到「首次构建期抛出」。
- `ChatModelFactory.buildAgentModel` 遇到该 provider 时经 `ChatModelEnhancerRegistry` 查找匹配的 enhancer（`model-pool` 插件注册的 `ModelPoolEnhancer`），委托其 `enhance(ctx)` 构建组合 `ModelPoolChatModel`(组合各成员的 OpenAiChatModel,按序逐个尝试):请求异常(非网络、非终态)时切下一个成员重试——每个成员用**自己的完整 options 快照**(baseUrl/apiKey/model 在构建时固定),成功即返回该成员真实响应;网络异常、空响应耗尽、取消类原样上抛;流式带防重护栏(已下发 chunk 后流中断不切换)。容灾切换经 `EventEmitter.emit(EmitEvent.TraceData.of("model_failover", ...))` 发 `task.trace(kind=model_failover)`。
- 待池耗尽不做包络,最后异常原样上抛,交给外层瞬时错误重试 advisor 退避重跑。

### 7.4.1 模型请求限流(调用端 rpm/并发/tpm 闸门)

**背景**:厂商限的是 rpm/tpm(不是 rps);一次事故即「主 agent 同时派发 8 个子 agent → 8 个长思考流同时全速吐 token → 瞬时叠加撞 429 → 退避重试放大瞬态事件风暴 → LogOverflow」。调用端需要**前置主动限流**,而不是只靠后置 429 重试。

**机制**(`model-rate-limit` 插件的 `RateLimitAdvisor` 实现 `CallAdvisor`/`StreamAdvisor`,经 Advisor 链注入(order=工具循环+500,最内层);主/子/AI 审议/池成员全部自动生效):

- 每模型独立配置(`worker.models[].params`):`rpm`(每分钟发起数,滑动窗口)、`max-concurrency`(同时 in-flight 上限,**长思考重叠的核心闸门**)、`tpm`(可选参考线)、`token-est-factor`(估算系数初始值)。**缺省回退全局默认限流(rpm=60 / max-concurrency=4 / tpm=0),不是裸奔不限流**;某模型要关闭某维度,在其 params 显式设 0。
- 请求起步经 `ModelRateLimiter.acquire` 排队等放行:rpm 窗口 / 并发信号量 / tpm 压力三关;超限进有界等待队列(默认队列 8、等 5 分钟),**正常排队不报错**,仅队列满 + 超时才抛 `ModelRateLimitException`(非重试,文案含「减少并发派发/调大配置」建议)。阻塞等待发生在虚拟线程上(park,零线程开销)。
- **tpm 记账**:流中无协议级 usage(OpenAI 兼容只在末帧带),故流中用自算文本 token 粗估(CJK≈1、其余≈4 字符 1 token)累计;请求完成后用厂商真实 usage 记账入 60s 窗口,并 EMA 反向校准估算系数(`token-est-factor`,每模型独立,持久化 `~/.everyagent/model-rate-state.json`,重启接续)。token 估算经 Advisor 的 `doOnNext`/`doOnComplete` 回调驱动。
- 全局默认:`worker.limits.model-rate.{queue-capacity, wait-timeout-ms, est-window-sec, est-safety-ratio, est-ema-alpha, default-rpm, default-max-concurrency, default-tpm}`。
- **观测**:排队等待经 `EventEmitter.emit(EmitEvent.TraceData.transientOf("model_rate_wait", null, "模型排队中", null, "waiting"))` 发语义事件,task 层映射为瞬态 `task.trace(kind=model_rate_wait)`(前端展示「模型正在排队」,§7.19);插件未加载时无限流 Advisor → 直通 → 不限流,worker 保留 fallback `SimpleTokenEstimator` 供 `model-length-guard` 插件(§7.3)使用。
- **插件不耦合内核**:`RateLimitAdvisor` 只依赖 `ModelConfig` + `EventEmitter`,不耦合 `ChatModel`/`Prompt`/`ChatResponse`/`Flux`/`TaskEvents`/`agentId`/`taskId`/`DataPusher`。`ConfigRpcHandler.rateStatus` 返回空数组(插件后续自行注册 RPC 上报限流运行态)。

### 7.4.2 模型 HTTP 超时语义(callTimeout 解除,流式长思考不限总时长)

**机制**(`ChatModelFactory.build` 经 `httpClientBuilderCustomizer` 挂 `StreamTimeoutReleaseInterceptor`,主/子/池成员/AI 审议全部生效):

- spring-ai 的 `OpenAiChatOptions.timeout(t)` 单值在 openai-java 展开为 `Timeout.request(t)`,最终映射 okhttp **`callTimeout`(整个调用的总时长上限,含流式全程)**;且 `AbstractOpenAiOptions.getTimeout()` 永远非 null(未设时默认 60s),per-request 四分量**每次覆盖** client 级配置,`httpClientBuilderCustomizer.timeout(...)` 无法纠正。reasoning 模型(reasoningEffort=high)单轮长思考可达数十分钟,callTimeout 到点 okhttp 强制断流(IOException)——表象与 provider 粗暴断流一致,且断流时输出量=思考速度×上限时长,常低于 maxTokens 的 80%,`ModelLengthGuardAdvisor` 比例判定不命中,落入瞬时重试死循环(每次重试重放整段长思考,再次到点断流)。
- 根治:应用拦截器内对每个 call 执行 `chain.call().timeout().clearTimeout()`——okhttp 原生支持运行期解除 call 级总时长,流式响应只要持续有 chunk 即不限总时长;同时剥除 `X-Stainless-Timeout` 请求头(该头携带 callTimeout 秒数,防 provider 按头掐流)。
- 兜底仍在:静默挂起由 okhttp readTimeout(读间隔上限,openai-java 默认 10 分钟)与 `ModelLengthGuardAdvisor` 的 stall(120s)先后兜住;真网络断连照常抛 IOException 交瞬时重试。`worker.model-timeout-ms` 语义因此调整为「读间隔上限的期望值」(仍写入 options.timeout,构成 per-request connect/read/write 默认分量的参考基准)。

### 7.5 上下文管理

**双事实源分离**(红线):`Event`(不可变,磁盘 jsonl)是传输/回放/审计的事实源;`conversation: Message[]` 是 LLM 工作态,随运行销毁,再运行时由 ConversationLoader 重建。

**上下文压缩**(`ContextCompressionAdvisor`,主/子同挂、最内层):只改写**发送给模型的 instructions 视图**,内存 conversation 与磁盘事件日志始终全量。

- **offset 校准**:每轮现场重算常数修正项 = 上一轮 provider 实测 `inputTokens − 粗估算值`,抵消无 tokenizer 粗估漏掉工具定义/系统模板/分词造成的漏触发。
- **累积式裁剪**:per-run 状态化,首次压缩后持有「压缩基线 baseline + 已吸收源消息数 absorbed」,后续轮只把新增消息 delta 接到基线上做增量裁剪。
- **摘要压缩**:阶段 C 丢弃历史轮前,调用 `LlmContextSummarizer`(默认复用当前 agent 的 chatModel)生成要点摘要;失败退化为「保留该轮 user 截断简版」防永久失忆;单条超大工具结果做首尾保留的确定性截断。
- 配置:`worker.limits.context-compression-enabled`(关闭则整体关)、`context-offset-enabled`(默认 true)、`context-summary-enabled`(默认 true)、`context-summary-max-tokens`(默认 512)、`context-max-tool-result-chars`(默认 40000)、`context-trigger-ratio`(0.95)/`context-target-ratio`(0.50)。
- 压缩可见性:`task.trace(kind=context_compression)`,前端可展开查看「已自动压缩上下文(阶段, 消息 M→N, 约 X→Y token)」。

**用量**:每轮模型实测 token 用量由 `usage` 事件上报;`TaskSummary.usage` 持久化最近一轮主 agent 上下文占用快照(前端任务列表上下文电池数据源)。

### 7.6 多 hub 连接与输出路由

```
worker ── HubPool ──┬─ conn₁ (url₁, apiKey₁ → K₁)  订阅 u.K₁.worker.<id>.cmd + .input
                    ├─ conn₂ (url₂, apiKey₂ → K₂)  订阅 u.K₂.worker.<id>.cmd + .input
                    └─ …(N 条,同一 workerId 广播所有 hub)
输出路由:
  tasks 通知       → 扇出到全部匹配连接(同 apiKey 多 hub = 双收冗余)
  任务流事件       → 定向推送(join 通知来自哪条连接就经哪条推,ext.target=前端 sessionId)+ task.poll 兜底
  RPC 应答         → 请求来源连接(前端只听自己 hub 的 evt 频道)
  presence        → hub 连接级自动,worker 零代码
```

- 配置:`worker.hubs: [{url, api-key, hub-key}]`(三者必填)。同 apiKey 配多 hub = 可靠性冗余;不同 apiKey = 一台 worker 服务多个命名空间。
- **任务不做 owner 隔离**:任务数据按工作区归类 `workspaces/<workspaceId>/tasks/<taskId>/`,任务事件扇出到 worker 的全部连接;`tasks.list` 返回全部任务。数据隔离靠命名空间(不同 apiKey 连接到不同 hub/频道域)+ worker 侧文件沙箱。
- **故障语义**:单连接故障不影响其余连接;全部断开 → 内存任务照跑完落盘(输出无人消费,天然背压),重连后前端从磁盘 ∪ 内存尾部拉取补齐。
- worker 从不订阅任何 per-task 频道:输入统一走 worker 级 `u.K.worker.<id>.input`(每连接一条),订阅数 O(worker×hub)。

### 7.7 任务生命周期:永久保留、运行即销毁、冷启动

```
(无) ──task.run(无taskId)──→ running ⇄ waiting-user ──→ done | failed | cancelled
                          ↑                          │
                          └── task.run{taskId} ──────┘  终态收到运行 = 从磁盘载入历史,
                              (task.input 兼容触发)        作为一次普通运行(没有"续跑"概念)
```

- **创建**:`task.run`(不传 taskId;必带 workspace)→ 建目录 → 任务驻留内存。
- **运行中**:新到输入入 inputQueue 在本次运行内消费(轮次循环 queue.loop 在每轮内核返回后 poll,临界段内侧续跑),队列增减**与逐项消费**均广播 `task.updated`(pendingInputs 快照;运行时态不落盘);悬空队列(终态未消费项)随 queue.jsonl 持久化、再运行时恢复。
- **终态(finish)**:flush 落盘 → 更新 meta → **销毁内存驻留**。内存只剩索引条目(~150B)。
- **再运行**:从磁盘载入(ConversationLoader 重建 conversation),复用原 taskId/workspace,`log.seed(seqLastOf)` 接续序号,作为一次普通运行。模型取任务 meta 的 configId(配置已删则回退默认)。
- **删除**:运行中拒绝;否则删磁盘目录 + 索引,广播 `task.deleted`。**这是任务唯一消失路径**。
- **worker 重启**:boot 扫描全部用户目录重建索引;磁盘上非终态任务标 failed("worker 重启中断");终态任务原样可回放。家中 PC 关机不丢数据。

### 7.8 阻塞式人机交互:askuser 与授权

虚拟线程让"阻塞式等"零成本——这是选 Java 25 的最大红利。

```jsonc
// agent → 任务流(EventLog)
{ "event":"ask.create", "payload":{ "askId":"q_x9…", "kind":"question",
  "questions":[{ "id":"q_x9…_0", "prompt":"要继续吗?", "options":["是","否"] }], "timeoutMs":1800000 } }
{ "event":"ask.state",  "payload":{ "askId":"q_x9…", "status":"pending", ... } }   // 挂起期间每 30s 重发
// 前端 → worker 级 input 频道
{ "event":"ask.reply",  "payload":{ "askId":"q_x9…", "answer":"是" } }
// agent → 任务流
{ "event":"ask.resolved", "payload":{ "askId":"q_x9…", "by":"s-17" } }
```

- 工具实现:`askUser(...)` 发 `ask.create` 后 `pendingAsks.await(askId, timeout)` —— 虚拟线程挂起零开销。
- 多前端抢答:complete() 幂等,先到先得;ask.state 30s 重发 + 持久事件让重连/新上线前端必然看到挂起问题。
- 超时(默认 30 分钟):向模型返回"用户未响应",由模型自决;同时广播 `ask.state{status:"timeout"}`。
- 任务在等待期间转入 `waiting-user`;取消时 future 异常完成。

#### 危险操作授权(PermissionGate)

ask 管道承载第二类阻塞请求:**危险操作授权**。`PermissionGate` 在工具执行前拦截,拦截面:

1. 文件工具目标在**工作区外**(realpath 判定,符号链接逃逸同判;read/list/stat → READ,write/move/remove → WRITE);
2. 命令串中的**危险动词**(del/erase/rmdir/Remove-Item/rm/shred 等,`worker.permissions.dangerous-patterns` 可配)——**仅当命令引用可能落在工作区外的路径时**才需授权;工作区内增删改查直接放行(授权护的是「工作区外」,不是删除动作本身;cwd 锁定 + 沙箱可写性契约兜底);
3. 命令串中引用的已存在工作区外路径(EXEC 权限)。

**权限责任链**(worker `tools/permission/`):节点接口产出三态 `ALLOW|DENY|SKIP`,链上顺序执行,任一 ALLOW/DENY 即短路;全链 SKIP 按拒绝兜底。

| 入口 | 链(顺序) |
|---|---|
| 文件路径 | `WorkspaceAllowCheck`(工作区内放行)→ `MissingPathCheck`(读不存在 NotFound)→ `SkillsReadAllowCheck`(skills 目录读写放行,§7.17)→ `ExternalRootAllowCheck`(工作区外部授权根放行环:realpath 落在该工作区 externalRoots 内即 ALLOW,§7.17)→ `OverBroadRootCheck`(盘根/工作区祖先拒收)→ `AuthorizeCheck`(委托授权决议) |
| 命令 | `CommandCheck`(危险动词 + 越界已存在路径逐项授权,系统目录同权) |
| 提权 | `PrivilegeCheck`(提权动词 / seccomp setuid exec,§7.11) |

**用户显式选择=已授权(工作区外部授权根)**:经 `@` 弹窗 `+` 图标显式选择的工作区外路径由 worker 侧注册为**工作区外部授权根**(§7.17,语义 = 完全读写 READ+WRITE+EXEC)——该选择本身就是授权动作,后续工具访问经文件路径责任链的 `ExternalRootAllowCheck` 放行环直接放行,PermissionGate **不再弹 `kind=authorization` ask**。这是与人工弹窗/AI 审议并列的授权来源,不是绕过 gate:全链 SKIP 兜底、过宽根拒收、命令危险动词拦截面等语义不变。

**弹窗形态**:`ask.create{kind:"authorization"}` 三选项(拒绝 / 本轮运行内允许 / 本任务全程允许),答案回传稳定 token `deny`/`run`/`task`;未识别/超时/取消一律按拒绝(安全缺省)。

**AskQuestion 结构化信息槽(§7.20)**:`AskQuestion(id, prompt, options, fields)` 第四字段为「标签→值」键值对——授权弹窗的机器可读字段(目录、授权类型等)与人类可读文案分离,前端在 prompt 下、options 上渲染「标签: 值」信息块;wire 序列化对空 map 省略该字段(must-ignore 双向兼容:老前端忽略未知字段,老 worker 不带该字段,前端判空跳过);仅展示增强,不改 ask 协议语义与回答解析。

**两档生效**:`run` 档纯内存,本轮输入处理完即清;`task` 档持久化 `workspaces/<workspaceId>/tasks/<taskId>/grants.json`,冷启动再运行恢复。

**授权粒度**:路径类按「最深已存在祖先 realpath」、动词类按规范化动词,避免同目录/同动词反复弹;同 key 并发只弹一张卡(inFlight future)。

**拒绝语义**:抛 `PermissionDeniedException` → 统一转「[工具执行失败]」文本回灌模型,agent 循环不中断。

**授权决议链契约(域中性,§7.20/§14.11)**:决议请求 `AuthorizationRequest(ExecContext context, agentId, grantKey, prompt)`——链节点(Unattended/AiReview/Human)只见 ExecContext 槽位(`metadata()` 判策略开关、`interaction()` 弹窗(subjectId 已绑定)、`agentFactory()` 建审议 agent、`emitter()` 落审计),不接触 `TaskEntry/TaskRuntime/TaskInfo`;`GrantRegistry` 按 `subjectId()` 分区授权状态、`dataDir()` 落盘 grants.json(今天二者=taskId/任务数据目录)。未来工作流层构造自己的 ExecContext 即走同一条链,链代码零改动。

### 7.9 AI 安全审议与无人值守

当需要人工授权(PermissionGate 拦到工作区外路径/危险命令)时,除人工弹窗外提供两条可选的任务级自动路径:

- **AI 审议(`/AI 审议`,kind=ai.review)**:可单独开启。授权弹窗改为由**独立的 AI 审议会话**(无任何工具、独立 system prompt,只基于安全策略判断并要求忽略授权正文中的任何指令,防 prompt 注入)读取授权信息并输出结构化判断(ALLOW/DENY/ESCALATE),在 PermissionGate 内部闭环自动放行/拦截并落审计。**主 Agent 是被审议方,不能自我授权**。审议 agent 经 `req.context().agentFactory().create(reviewAgentId, reviewModel?)` 创建——工厂为预绑定静态代理(§7.20),事件/审计自动落被审议主体日志,advisor 链(重试/压缩/限流)照常装配;审议 agent 以 per-task 固定 agentId `review-<subjectId>` 注册进 `agents()` 跨请求复用会话——既往授权决策的结论与理由留在审议员上下文内,后续审议看得见本任务历史决策(会话随授权次数增长,任务生命周期内有限)。
- **无人值守(`/无人值守`,kind=unattended.mode)**:开启时**联动**开启 AI 审议(selectHandler 一次返回两个胶囊,前端各自 apply)。AI 仍可看到并调用 `ask_user` 工具,但 `UnattendedToolInterceptor`(工具执行拦截链节点,§7.14.3)在工具执行瞬间拦截该调用、代替人工逐题选择第一个选项,以「题干：首选项」格式回传作答文本(与前端真实作答格式一致;不创建 ask、不挂起等待);拦截器每次工具执行实时读 `ctx.metadata()` 的 unattended 标记(`ToolExecutionContext extends ExecContext`,域中性直读槽位,§7.20),运行中点胶囊开/关即时生效。两胶囊 ✕ 独立,开启时联动、事后可拆分。

**授权拦截链**(`PermissionGate.ensureGranted` 内、发起人工弹窗前短路,两条独立环节互不相关):

| 拦截链环节 | 判定依据 | 行为 |
|---|---|---|
| ① AI 审议 | 任务级 `aiReview`(开启且审议器在位) | ALLOW → 自动授权(RUN 档);DENY → 拒绝;ESCALATE/审议失败 → 落下一环节 |
| ② 无人值守 | 任务级 `unattended` | 开启 → 授权直接拒绝(无人工可弹);未开 → 正常人工弹窗 |

- 审议路径不发 `ask.create/ask.state` → 任务保持 RUNNING(不误转 waiting-user)。
- 审议异常/超时/输出非 JSON/缺字段 → 默认 DENY(fail-closed);**审议失败绝不自动放行**。
- 审计:`task.trace(kind=auth.review)` 持久落盘,metadata 含 decision/confidence/reason/scope/grantKey/prompt/taskId/审议 agentId;审议链的重试/容灾 trace 同入审计。
- 配置:`worker.permissions.review-timeout-ms`(默认 60s,总预算硬闸)、`review-deny-on-error`(默认 true)、`review-model`(可选,审议专用模型 configId,空则用任务当前模型)。

### 7.10 命令沙箱(插件化,多后端)

沙箱后端已从 worker 核心抽离为独立插件(`every-agent-plugins/sandbox-windows-mic/`、`every-agent-plugins/sandbox-wsl-ubuntu/`)。`SandboxBackend` SPI 极简化为**挂载 + 工作区生命周期 + 标识**三个方法,不执行命令、不翻译路径、不涉及工具注册、不涉及授权策略。

**SandboxBackend SPI(极简):**
- `id()` — 后端标识。
- `mount(List<MountRequest>)` — 批量挂载宿主路径到沙箱内,返回 {宿主路径 → 沙箱内路径} 映射表。DIRECT/windows-mic 返回原路径(不挂载);wsl-ubuntu 做 drvfs 挂载返回 `/c/...` 形态。幂等:重复调用相同路径不重复挂载。
- `onWorkspaceRemoved(Path)` — 工作区删除时 best-effort 清理挂载等。DIRECT/windows-mic no-op;wsl-ubuntu 做 umount。

| 后端 | id | priority | mount 行为 | 说明 |
|---|---|---|---|---|
| **WSL Ubuntu** | `wsl-ubuntu` | 10 | 批量 drvfs 挂载 → `/c/...` | 原 wsl-direct 改名,独立插件 |
| **Windows MIC** | `windows-mic` | 5 | 返回原路径 | 命令跑在宿主上,独立插件 |
| **DIRECT(默认沙箱)** | `direct` | — | 返回原路径 | OsSandbox 自身,无 Provider |

**后端选择时机(时序红线)**:`SandboxProvider` 全部由插件在 `PluginLoader` 的 `@PostConstruct` 里注册,而 `PluginLoader → WorkerServices → OsSandbox` 的构造依赖链决定了 **OsSandbox 一定先于插件激活完成初始化**。因此后端**不得在 `@PostConstruct` 一次性定论**:
- `OsSandbox` 的 delegate 按 **`SandboxProviderRegistry` 代次(generation,每次注册/注销自增)惰性解析**:首次访问(`id()`/`mount()`/`onWorkspaceRemoved()`)或代次变化时重新 `select()`,解析结果(含「无可用后端」的 null)按代次缓存,不产生每次调用的重复探测;
- 选择规则不变:`select()` 先按 `isAvailable()` 过滤候选,再对胜出者 `create()`——重副作用(如 codex 的 UAC setup)只可能发生在胜出时刻,不因探测而提前;
- 启动期日志只陈述「配置 + 候选清单(不探测、不 create)」,后端**定论推迟**到首次真正访问 delegate 时;生效后打一条 INFO(后端切换时带原 id),解析不到时打 WARN 并附候选 id/priority;
- `mount()`/`onWorkspaceRemoved()` 由 OsSandbox **必须转发给 delegate**(delegate 为 null 才走 SPI 默认的原路径/no-op)——门面自己吞掉挂载会让 wsl 系列的 drvfs 挂载整体失效。

**核心路径翻译中间人(SandboxPathRegistry):** worker 核心内部 `@Component`,管理 {宿主路径 → 沙箱内路径} 映射表。核心调 `sandbox.mount()` 拿到映射关系后自己查表翻译,不依赖沙箱。

**意图登记 / 映射物化两段式**(否则「注册即挂载」会把 `wsl.exe` 进程开销压进启动路径,且启动期后端尚未定论):
- `register(owner, path, access)` / `sync(owner, requests)` — **只登记挂载意图**(按 owner 分组的幂等集合,零 IO);`sync` 为**覆盖式对齐**(该 owner 的差集自动撤销),`unregister(owner, path)` 撤销单条。
- **物化惰性**于首次翻译查询:按当前生效后端把全部意图**一次批量** `mount()`,代次键 = (后端 id, 意图版本);任一变化即整表重建——后端切换(如 mic → wsl-ubuntu)不会残留旧形态映射,插件热插拔同理自愈。
- `toSandboxPath(Path)` — 宿主路径 → AI 可见路径:命中意图根取其视图,否则**最长前缀根**推导子路径;无映射原样返回(DIRECT 场景)。
- `toHostPath(String)` — AI 视角路径 → 宿主路径:同样最长前缀反推;**无映射返回 null**(注册表外路径,调用方原样交给 Java NIO 自然报错,AI 改用沙箱命令工具)。
- 前缀比较两侧统一 `'/'` 归一 + Windows 下大小写不敏感;返回的宿主路径一律由登记的原始形态拼接(不丢大小写)。
- `onWorkspaceRemoved(Path)` — 通知沙箱清理 + 从意图表与映射表移除该根。

**登记方(owner 分组,谁的路径谁登记):**
| owner | 登记方 | 内容 | 时机 |
|---|---|---|---|
| `workspaces` | `WorkspaceManager` | 全部在册工作区根 + 各工作区 `externalRoots`(READ_WRITE) | 注册表变更(`broadcastRegistry` 单一收口点:启动载入、新增/注册、外部授权根新增、移除、失效清理) |
| `skills` | `BuiltInSkills` | 系统技能目录根(READ_WRITE,§7.17 读写挂入) | `@PostConstruct` 物化知识包时 |

**任务级授权不进意图表**:单次 EXEC/READ 授权(grant)只在**授权决议链**(`PermissionGate`/`GrantRegistry`)层面放行文件工具,不经常规意图表扩挂进沙箱——命令侧挂载清单由各沙箱插件每次执行自行推导(wsl-ubuntu = `WorkspaceManager.pruneStaleAndListMountRoots()` + skills 目录),避免 run 档授权经持久 drvfs 挂载泄漏成跨任务可读根。

**核心宿主访问工具与沙箱命令工具共存:**
- **核心的命令工具**(windows-mic / DIRECT 后端):核心自带,Windows 上 PowerShellTool 以 `powershell` 工具名注册;Linux 上 BashTool 以 `bash` 工具名注册。直接 ProcessBuilder 执行,走自己的授权链。
- **沙箱插件的命令工具**:沙箱插件不只提供 `SandboxBackend`(挂载+清理),还提供 `ToolProvider`(命令工具)。沙箱完全自由:自己实现 CommandExecutor、自己扫描路径、自己决定授权策略。通过 `appliesTo(ToolContext)` 控制生效条件(如 `ctx.sandbox().id().equals("wsl-ubuntu")`)。
- 两者通过 `ToolProvider.appliesTo()` 各自控制生效条件,不冲突。核心 `DirectShellToolProvider` appliesTo = 生效后端 id == `direct`(即无任何 SPI 后端),Windows 提供 `powershell`、其余提供 `bash`;windows-mic / wsl-ubuntu / codex 各自提供自己方言的命令工具。
- **只有某后端才做得到的隔离能力,其命令与状态一律归该插件**:wsl-ubuntu 的 `/禁用网络` 由 `sandbox-wsl-ubuntu` 自己经 `registerSlashProvider`/`registerSlashTokenResolver` 注册、状态写任务 `metadata`,并在自己的 CommandExecutor 里读取生效;worker 核心不持有该开关,也不为做不到断网的后端预留同名命令。

**路径翻译流程:**
1. 路径提供方注册 → `SandboxPathRegistry.register()` → 核心调 `sandbox.mount()` → 沙箱返回沙箱内路径 → 核心保存映射表。
2. 工具参数翻译:`FsToolSupport` 收到 AI 传的路径后调 `pathRegistry.toHostPath()` 翻译为宿主路径;无映射原样保留让 Java NIO 自然报错。
3. 非工具路径翻译:`SkillAdvisor`(知识包路径)和 `ExternalFileTokenResolver`(@ 引用)直接调 `pathRegistry.toSandboxPath()`。

- `worker.sandbox.type`: `auto`(默认)| `wsl-ubuntu` | `windows-mic` | `none`。旧值 `wsl-direct` → 归一为 `wsl-ubuntu`(静默兼容);旧值 `wsl-bwrap`/`bwrap`/`wsl` → 归一为 `auto` 并 WARN。WSL 专属配置(distro/tarball 等)由插件通过 `plugin.json contributes.config` 自管。
- **Windows Medium IL 契约**(对 windows-mic 后端):沙箱进程运行在 Medium IL(Restricted Token 去特权但不降级),天然可写工作区与已授权目录,不对文件系统做任何标注或 ACL 修改——零副作用、零残留。越界写拦截由 PermissionGate 责任链承担。
- **网络策略**:两级开关,默认放行——① 全局 `worker.sandbox.allow-network=false`(经 `SandboxConfig.networkDenied` 交给后端:wsl-ubuntu 真断网、codex 选 Offline 账户;mic/direct 只剥代理 env 拦不住直连);② 任务级 `/禁用网络`(**只有 wsl-ubuntu 后端做得到**,故整个能力归 `sandbox-wsl-ubuntu` 插件自带:插件自己注册 slash 命令提供者 + token 提交解析器,状态写任务 `metadata["networkBlocked"]`(核心不感知 key),随 meta.json 持久化、再运行保持;`/` 菜单条目按当前生效后端 `sandbox().id()` 决定是否出现,其他后端不提供该命令)。落地由沙箱插件自己的 CommandExecutor 负责:wsl-ubuntu = 发行版内 `unshare -n` 新建无 eth0 的 netns(DNS/回环全断)。worker 核心不持有任何任务级禁网状态,通用 `CommandExecutor`/`OsSandbox` 也不再判定网络。
- **命令 stdin 契约**:AI 命令的 stdin 一律接 null 设备(`/dev/null`/`NUL`)。

### 7.11 提权拦截

wsl-bwrap 后端的 seccomp 内核级提权拦截已随 bwrap 后端删除而移除。wsl-ubuntu 后端以 root 完整权限直连,无提权授权概念。windows-mic/DIRECT 后端通过 Restricted Token / PermissionGate 文本扫描拦截危险命令。

### 7.12 原生 git 执行与凭证

工作区 git 快操作(`git.*`)不自己复刻 git 语义,由 `NativeGit` 调宿主**原生 git 可执行文件 argv 直传**执行(不经 shell 字符串拼接),复用 `OsSandbox` 的超时/输出上限/env 清理;**不走命令沙箱的 wsl/mic 后端**——git 是前端按钮触发的受控操作(参数受控、路径被 `Sandbox` jail),降权/进发行版会引入路径映射与权限差异(与 JGit 曾有的 bug 同源)。

- **可执行文件定位**:启动探测一次并缓存——`worker.git.executable` 显式指定 > Windows 常见安装路径(`C:\Program Files\Git\bin\git.exe`、`C:\Program Files\Git\cmd\git.exe`、`C:\Program Files (x86)\Git\...`) > PATH 兜底;全部失败明确报错(「git 不可用,请安装 Git for Windows」),不静默回退。
- **稳定化参数**:每个命令预置 `git -C <workspace> -c color.ui=false -c core.quotepath=false --no-pager`,读命令加 `--no-optional-locks`(防 `.git/index.lock` 残留/竞争);env 设 `GIT_TERMINAL_PROMPT=0`(缺凭证 fail-fast,不卡死)、`LC_ALL=C.UTF-8`(输出编码稳定)。
- **路径沙箱**:复用 `Sandbox` realpath 前缀 jail(§5.9);用户 path 参数先经 `Sandbox` 校验再进 argv。
- **commit 路径过滤**:`git.commit` 显式 paths 先对全部路径做 jail 校验,再与当前变更集(status 7 类合集)求交集——已无变更的陈旧路径(如前端勾选后被删除的未跟踪文件,git status 中彻底不可见)对本次提交是 no-op,直接跳过,避免 `git add` 因 unmatched pathspec 整体失败;交集为空报 `BAD_PARAMS`(提示刷新),绝不静默回退成全量提交。
- **并发**:per-workspace 串行锁——写操作(`commit/pull/push/discard/init/clone/remote.add` 与自动同步)同 workspace 串行;读操作带 `--no-optional-locks` 可并发。
- **执行出口**:`OsSandbox.spawnNative(String[] argv, Path cwd, Map<String,String> env)`(宿主原生 argv 直传,非 wsl/mic);git 超时用 `worker.git.timeout-ms`(默认长于统一命令超时,clone/pull/push 大仓库可能较慢)。

**凭证**(`git.clone/pull/push` 共用四档解析链):

1. RPC 临时凭证(username/password,不落盘)→ 生成临时 askpass 脚本,凭证值经 `GIT_EA_USERNAME`/`GIT_EA_PASSWORD` env 注入,`GIT_ASKPASS`/`SSH_ASKPASS` 指向脚本(不拼 argv、不把密码写进脚本文件);
2. 本机默认凭证 → **不注入任何凭证**,git 自行走 `credential.helper` / credential manager / `ssh-agent` / `~/.ssh`(静默,原生 git 开箱即用);
3. 认证失败 → 读工作区 `.everyagent/.git-credentials.enc` 该 host 条目 → 解密后按 ① 注入重试;
4. 仍失败 → `rpc.err(AUTH_REQUIRED)` 弹凭证输入(判定 = 非零退出 **且** stderr 命中 `Authentication failed` / `could not read Username` / `could not read Password` 等关键字,避免网络错误/远端 404 误判)。

- **加密存储**:每工作区一把密钥 `<workspaceRoot>/.everyagent/.git-credential.key`(首次启动自动生成 32B AES-256,与密文 `.git-credentials.enc` 同级);算法 AES/GCM/NoPadding,随机 IV,AAD=host 绑定条目;密文 JSON `{version, entries:{host:{iv,cipher,ts}}}` 存工作区 `.everyagent/.git-credentials.enc`,明文永不落盘。
- 前端 Git 面板捕获 `AUTH_REQUIRED(host)` → 凭证 Modal(账号/密码/「保存凭证到工作区(加密)」复选框)→ 先带临时凭证重试(克隆时根仍为空),成功后再 `git.credential.save` 落盘。
- 凭证仅存工作区加密文件与 worker 内存,不经 hub / 前端 localStorage;协议不提供"读取凭证"RPC(save 只进不出)。
- **多远端推送**:`git.push` 与自动同步(`syncRemote`)均推送到<b>所有</b>已配置远端(`git remote -v` 列出的每个 remote),而非仅 origin;自动同步仅从 origin 拉取、推送全远端;推送逐个远端执行,部分失败时仍尝试其余远端,最终汇总错误。推送始终显式指定当前分支名;分支无 upstream 时,首个远端自动带 `--set-upstream` 建立跟踪。
- 自动同步(git 自动提交)保持静默:只走本机凭证 + 加密凭证,不弹窗。

### 7.13 任务流传输(混合模型:定向推送 + 拉取)

**实时增量 = worker 定向推送**(DataPusher):前端 sub `u.K.task.<id>.stream` → hub 向 worker 发 `subscriber.join{sessionId,taskId}` → DataPusherManager 校验归属后按 (sessionId,taskId) 建推送器;推送器虚拟线程把运行中任务内存 EventLog 增量(含瞬态 delta/thinking)推到 stream 频道,`ext={target:sessionId, operate, initial}`。

- **窗口式背压(credit + ack)**:DataPusher 维护 `nextPushIndex`(每推一帧 +1)与 `ackedIndex`;`nextPushIndex - ackedIndex >= CREDIT_WINDOW(128)` 时**持续真阻塞**等待前端 ack——直到 ack / 连接断开 / 推送器销毁,无超时降级(全链端统一升级,无老前端兼容负担);每帧 ext 携带 `credit=true/creditIndex`;前端消费完一帧后经 worker 级 input 频道回 `stream.ack{taskId,creditIndex}`,worker 只路由释放窗口、不建推送器。多前端窗口独立,慢端不拖累快端。
- **先订阅后首拉**:前端 `open()` 先 sub stream 再拉初始(rounds + roundTail),推送首扫与首拉重叠的部分前端按 seq 去重吸收。
- **生命周期**:unsub/前端断连 → 销毁推送器;worker⇄hub 断链 → 清扫该连接推送器;任务再运行换新 EventLog → 换挂从头推;任务终态 → 收尾排水一次后空转。
- **降级语义**:推送非阻塞,出站队列满丢帧 + WARN(事件日志是事实源);前端慢 → hub sink 溢出断连 → 重连 reconnect;漏帧由前端按需拉取补齐。
- **慢消费者三道防线**:① 真背压——窗口满即持续阻塞等待 ack,无超时降级;② `CREDIT_WINDOW(128)`——多任务同屏总积压 N×128 < hub 出口队列 1000;③ 前端 hidden 时对全部活跃 stream 频道主动 unsub 降载(hub 发 subscriber.leave → worker 销毁推送器,彻底不推),visible 时重 sub + 重拉校准——`visibilitychange` 只管订阅降载,不参与连接生死(连接生死唯一由心跳判定,§5.1)。

**`task.poll { taskId, afterSeq?, beforeSeq?, limit?, mode?('events'|'rounds'), count?, waitMs? }`** 是任务流的**统一读取 RPC**:打开首拉、上滚分页、区间拉取、重连补齐、终局补拉、长轮询全部经它完成。

- 数据源 = 磁盘窗口 ∪ 内存 EventLog 尾部,按 seq 归并、同 seq 以内存为准。磁盘侧用随机访问分块反向扫描 jsonl(`ReverseLineReader`),单次 O(命中行数 × agent 文件数)。
- `mode='rounds'`:从尾部定位最近 count 个轮次起点,返回覆盖完整轮次的事件段(打开/重连用 `{mode:'rounds',count:1}` 秒拉尾段;注意 rounds 分支只按 count 定位、忽略 beforeSeq)。
- `mode='events'`:按 afterSeq(增量)/beforeSeq(上滚)+ limit 精确窗口;afterSeq+beforeSeq 同给 = 开区间查询(前端展开轮次按 startSeq/endSeq 一次拉全一轮)。
- `waitMs>0` 无增量时挂起虚拟线程等新事件(长轮询,与 askuser 同款底座)。
- 回放只含持久事件(瞬态从未落盘):`message` 事件自带整轮 thinking + toolCalls,由它直接组装完成态。

### 7.14 子 Agent(进程内,模型工具)

子 Agent 采用**进程内工具**方案,不采用"每个子 agent 一个独立后端程序 + hub 协调"。理由:同套底层 AI、同模型、同沙箱、零网络协调、运行时随意 spawn;跨机分工留给 v2 舰队/编排。

**工具面**(注册给模型的工具,与前端 RPC 无关):

| 工具 | 语义 |
|---|---|
| `run_agent(input, title, agentId?)` | 异步派发子 agent;无 agentId 新建(title 必填,agentId 动态生成);传 agentId 即续跑(复用其上下文);立即返回 agentId,需用 wait_agents 等待结果 |
| `list_agents()` | 列出本任务下全部子 agent(agentId/title/createdAt/status/latestActivity,不回灌完整历史) |
| `wait_agents(agentId?, timeoutMs?)` | 等待子 agent 完成/超时 |
| `stop_agent(agentId)` | 停止指定子 agent |

**运行语义**:

- 子 agent = 同一 ChatModel + 收窄工具集 + 独立 system prompt 的嵌套循环;虚拟线程承载。
- **上下文隔离**:子 agent 只收到 input 文本与自身 system prompt,不继承父 conversation 任何历史;信息交换唯一通道 = 下行 input、上行 `agent.done` 结果。
- **递归禁用从根源做**:子的工具集剔除全部 agent 工具 → 结构上不可能派生孙 agent(深度上限 1)。
- **父停止级联**:父任务取消 → 全部子 agent 停止。
- **收口前自动等待**:父任务结束前自动 wait 全部子 agent 聚合回灌;安全超时(默认 5 min)。
- 并发守卫:运行中的 agentId 再次 run_agent 报错。

**事件与持久化**:子 agent 不建独立 Task,事件与主 agent 同名、以 agentId 字段嵌套在父任务流(spawn 生命周期为 agent.started/agent.done);**每个子 agent 一个独立会话文件 `<subAgentId>.jsonl`**;冷启动重建、断线续播、ask(带 agentId)全部复用既有机制。子 agent 台账**独立落盘任务目录 `agents.json`**(形状 `{"agents":[...]}`,临时文件 + 原子 move 写入、空台账删文件)——从 meta.json 拆出,TaskSummary 不再携带 agents 数组,`tasks.list` 读 meta 的任务列表数据因此减负;台账项 = AgentLedger 事件投影(形状与 `AgentEntity.toSummary()` 对齐):agentId/title/createdAt/status/latestActivity/**usage(累计)**/lastText/**context(最近一轮上下文快照 `{inputTokens, contextWindowTokens, model}`,有数据才写)**/**metadata(含 creator,随 agent.started 事件投影)**,前端经 `task.agents` 拉取。台账投影与 agents.json 读写由 worker core `AgentLedger` 承担(订阅 EventLog 的 agent.started/done/status/usage/message/error 事件,§7.20.1;原插件 `SubAgentLedger` 退役);agent.started/agent.done/agent.status/agent 级 error 由 advisor 链驱动、`AgentEntity` 一处发射(per-run 生命周期,主/子一视同仁,§7.20.1)——SubAgentManager 不再手动 put/emit,复用续跑路径也不补发 started。usage 事件语义:WorkerToolEventAdvisor 每轮模型调用 usage 发射前先 `addUsage` 累计进 AgentEntity——usage 事件 total 载荷 = 含本轮累计;子 agent 每轮 usage 后由 AgentLedger 刷新内存台账并触发 agents.json 落盘(30s 定时 / 终态 persistFinal 两路径)。

**域中性(§7.20/§14.11)**:subagent 是**执行域能力插件**(spawn/wait/stop 子 agent 是执行域能力,不是任务域能力——没有 task 只有 workflow 的执行主体同样可用)。`SubAgentManager` 构造零服务依赖,方法收 `ExecContext`(run/list/waitFor/stop/awaitAllBeforeFinish/stopAll),工具入口绑 `ToolContext`(extends ExecContext,§7.20.5) 不再反查任务;per-subject 状态自持 `TaskSubState`(监视器内部化,对任务对象的锁依赖消失)。台账 agents.json 住主体 dataDir 下,IO 已由 worker core `AgentLedger` 收编(事件投影 + 原子读写 + 冷启动恢复,原插件 `SubAgentLedger` 退役,§7.20.1);生命周期节点(track/untrack/persist 由 AgentLedger 内置节点承担,§7.20.1)壳留 task 面、逻辑取 ExecContext 槽位,工作流落地时同一核心以同构生命周期壳挂载、零新逻辑。

### 7.14.1 任务生命周期洋葱模型（Phase 1）

任务开始/结束收口采用 **洋葱模型**（Servlet Filter 风格参与式链）：节点拿到运行上下文，调用 `result = next(context)` 得到后面全部节点+内核的执行结果。`next()` 之前 = 下行（开始）阶段，之后 = 上行（结束）阶段。

- **契约**：`TaskLifecycleNode`（plugin-api）、`TaskLifecycleContext`、`TaskOutcome`、`TaskKernel`——放 plugin-api `dev.everyagent.plugin.api.task` 包，插件可贡献节点；`TaskLifecycleContext extends ExecContext`（任务域成员保留 + default 桥接，§7.20.5）。
- **执行器**：`TaskLifecycleExecutor`（worker `task.lifecycle` 包）——按 order 升序稳定排序折叠为嵌套链，链尾接内核；`InterruptedException`→CANCELLED 兜底。
- **临界段**：order ∈ [420, 850] 的连续 `UpstreamNode` 段共享一次 `synchronized(taskLock)`，对外表现为 order=850 的单一链位置。段内上行执行序 = order 降序（与现状 finish 持锁段逐项一致）。
- **注册表**：`TaskLifecycleRegistry`（`plugin/registry/` 第 8 个注册表）——CopyOnWriteArrayList + float 稳定排序。850..420 区间拒绝插件节点插入。（注册表不感知插件禁用：禁用的插件根本不会被 `activate`，也就不会往这里注册节点，见 §8.5）
- **内置节点（17 个）**：
  - 下行 4：`persistence.track`(100) → `task.wires`(200) → `status.start`(300) → `main.agent`(390)
  - **轮次循环段（每轮重入，临界段内侧）**：`queue.loop`(870，task-input-queue 插件) 包裹 `[ file.reference.process(875) → edit.resend(877，task-edit-resend 插件) → consume.input(880) → 内核 runner.run(main) 一次 ]`——每轮 poll 队列项后覆盖 ctx.input 再 proceed;队列 poll 空时先回收「插入对话」队列里未被 advisor drain 的项(回收成功就继续续跑),`cascade.stop`(900)/`spawned.await`(950) 亦在循环内侧(逐轮失败级联停/子 agent 等待)。**轮次循环必须在临界段 [420,850] 内侧**：若在 420 之外包裹(如 order=395),每轮上行段会把 status 终态/registry.remove/persistence.untrack/concurrency.release 等一次性收口节点逐轮执行——任务中途被移出注册表(实时推送断流、cancel/task.poll 失效)、writer 提前关闭(后续轮次事件不落盘)、并发计数重复扣减
  - 上行 13：`spawned.await`(950) → `cascade.stop`(900) → `ledger.persist`(860) → **[临界段]** `status.finalize`(850) → `concurrency.release`(800) → `log.flush`(750) → `queue.persist`(700) → `status.persist`(650) → `ledger.persist`(600) → `disk.index`(550) → `persistence.untrack`(500) → `gate.evict`(450) → `registry.remove`(420) **[/临界段]** → `workspace.activity`(350)
- **行为零变化**：事件发射顺序、seq 语义、落盘内容与重构前逐项一致（§3.3 基线表逐字映射）。
- **后续 Phase**：agent 层独立(Phase 2)、拦截链范式统一(Phase 3)、subagent 插件(Phase 4)、队列插件(Phase 5)。详见 `docs/design-agent-layer-onion.md`。

### 7.14.2 TaskManager 职责拆分与通信解耦（Phase 2.5）

TaskManager 构造参数从 23 降至 15（SubAgentManager 从 11 降至 4），迁出的职责与承接组件：

| 承接组件 | 层 | 职责 |
|---|---|---|
| `AgentFactory`（agent 层） | agent 层 | 主/子 agent 装配（模型/工具/沙箱/权限聚合） |
| `ConfigRpcHandler` | 基础设施 | config.get / config.reload / skill.reload RPC（自注册） |
| `SlashTaskCallbacks` | 基础设施 | slash 任务 token 建后回调 |
| `EventSink`（接口） | 基础设施 | `fanout(channelNamer, event, ...)` 扇出发布——无调用者语义，HubPool 实现；task 层广播任务状态、未来工作流层广播流状态共用 |
| `TaskMessageRouter` + `TaskInputHandler` | 基础设施 | worker 输入频道订阅与消息路由（TASK_INPUT/DIALOG_INSERT/ASK_REPLY）；接口在基础设施层定义、TaskManager 实现——依赖方向与 AgentContext 相同 |
| `StreamSourceRegistry`（洋葱节点直持） | 基础设施 | PersistenceTrack/UntrackNode 直接 attach/detach 流源 |

**分层与核心边界**：工作区 → task 层/工作流层（平级编排）→ agent 层 → 基础设施层；**下层不知道上层**。`PendingAsks` 归基础设施层（askuser 交互能力），洋葱 CascadeStopNode 直持。留在 TaskManager 的 `workspaces`（任务创建注册工作区）与 `gate`（每轮授权失效 beginRun）是 task 编排活依赖。事件三条出路（落盘=TaskStore 监听 EventLog、实时推送=DataPusher 经 StreamSourceRegistry 取 EventLog 定向推、历史拉取=task.poll 磁盘窗口∪内存尾部归并）与广播（EventSink.fanout → hub 只投已订阅连接）对 task 层与未来工作流层完全同构。**执行上下文同构**:执行数据经 `ExecContext`(plugin-api,§14.11)显式类型化——task 层构造(`TaskEntry` 即其实现,subjectId=taskId)并预绑定 `agentFactory()`/`emitter()` 往下传,agent 层/工具链/授权链只见 ExecContext 槽位,未来工作流层实现 `WorkflowRuntime implements ExecContext` 即复用全部横切基础设施;事件管道与执行上下文管道双通道同构。

### 7.14.3 拦截链范式统一（Phase 3）

worker 的两条运行期责任链迁移为与任务洋葱同一的 filter 形态（`result = next(ctx)`）：

| 链 | 旧 | 新 | 收益 |
|---|---|---|---|
| 工具执行拦截链 | `beforeToolExecution(...)` 返回 null/非null（仅下行） | `invoke(ctx, next)`：下行=检查/短路，上行=执行后审计 | 获得上行钩子 |
| 授权决议链 | `applies() + decide()` 两步 | `invoke(req, next)`：req=`AuthorizationRequest(ExecContext,agentId,grantKey,prompt)`(域中性,§14.11)；不处理=proceed，决议=短路 | 获得决议后包裹 |
| PermissionGate 内部三链 | `check(ctx)` 返回 ALLOW/DENY/SKIP | `invoke(ctx, next)`：SKIP=proceed，短路=ALLOW/DENY | 与洋葱同构 |

- **链组装器**：`ToolExecutionChainExecutor` / `AuthorizationChainExecutor` / `PermissionChainImpl`，均按 order 升序折叠为嵌套链，与 `TaskLifecycleExecutor` 同构。
- **ThreadLocal 消除**：`InterceptingToolCallingManager` 改为 per-run 实例、以构造参数直持 `ExecContext`(S4,§7.20)——reactive 流的工具执行可能切换到 boundedElastic 线程,ThreadLocal 不可靠;插件的 `ToolExecutionContext` 本身即 ExecContext(extends,§7.20.5),拦截器直读槽位,不再直接依赖 worker 内部类。
- **`LoopRepeatGuardToolManager` 保持装饰器**：核心守卫不是插件扩展点，且需在 `executeToolCalls` 处合成工具结果回传模型，与插件链职责不同。
- **行为零变化**：短路/放行/兜底语义逐项等价（§6.3 迁移映射表）。

### 7.14.4 任务队列插件（Phase 5）

任务队列插件将「并发上限即拒 ERR_BUSY」语义替换为「排队等待」语义。插件以 `@Component` + 构造器注入 worker 注册表的模式注册（同 git 插件 `GitPluginRegistrar` 先例）。

- **`QueueAdmissionNode`**（order=250，形态三 try/finally 成对节点）：落在洋葱下行空隙 100~400 之间（`persistence.track`=100 之后、`status.start`=300 之前）。下行段 `acquire(taskId)` 获取运行许可（`Semaphore` fair 模式，permits=maxConcurrentTasks），并发满时虚拟线程 park 阻塞（零线程开销）；finally 段 `release(taskId)` 释放许可并唤醒下一个等待者。下行抛异常时 release 不执行（未进入不收口语义）。
- **`TaskAdmissionPolicy` SPI**（plugin-api）：RPC 边缘预检扩展点。队列插件注册 `TaskQueueAdmissionPolicy`（always-admit）后，`TaskManager.rpcTaskRun` 不再硬拒绝 ERR_BUSY，而是放任务进入洋葱由 `QueueAdmissionNode` 排队处理。无注册策略时保持原有行为。
- **`task.queued` 事件**（tasks 频道）：任务因并发满而排队等待时广播队列状态（payload: `{queueLength, queue:[taskId...]}`），前端据此渲染排队状态。
- **`task.queueList` RPC**：返回当前队列快照 `{availablePermits, queueLength, queue:[...]}`。
- **`TaskQueue`**（@Component）：`Semaphore`(permits=maxConcurrentTasks, fair) + `ConcurrentLinkedQueue<String>` 跟踪排队任务；acquire/release 管理 Semaphore 许可并广播队列状态变化。
- **worker pom 挂载**：`every-agent-worker/pom.xml` 依赖 `task-queue` 模块（worker 自行 repackage 可执行 jar,mainClass=`WorkerApplication`）。

### 7.15 持久化与磁盘布局

```
~/.everyagent/                       # <home>(EVERYAGENT_HOME 可覆盖;docker 挂卷)
├─ defaultworkspace/                 # 默认工作区根(原 workspace/ 改名,自动注册 id=defaultworkspace)
│   └─ .everyagent/                  # 工作区级 git 凭证加密存储(密文 .git-credentials.enc + 密钥 .git-credential.key 同级,§7.12)
├─ workspaces/
│   ├─ workspaces.json               # 唯一工作区注册表 {id, root, addedTs, lastActivityTs?, externalRoots?}(默认工作区也在册)
│   ├─ defaultworkspace/tasks/<taskId>/      # 默认工作区任务目录;永久保留
│   │   ├─ meta.json                 # TaskSummary(含 workspaceId、最近一轮上下文用量、任务级开关)+ mainAgentId
│   │   ├─ agents.json               # 子 agent 台账 {agents:[...]}(从 meta.json 拆出独立落盘,减轻任务列表数据;含累计 usage 与最近一轮 context 快照 + metadata.creator;临时文件 + 原子 move 写入,空台账删文件;worker core AgentLedger 事件投影 + 自持 IO,§7.20.1)
│   │   ├─ grants.json               # task 档授权 {taskGrants, extraRoots}(§7.8,首次授权时原子写)
│   │   ├─ <mainAgentId>.jsonl       # 主 agent 会话 + 任务级事件
│   │   ├─ rounds.jsonl              # 轮次索引(§7.15.1)
│   │   ├─ <subAgentId>.jsonl        # 子 agent 独立会话
│   │   └─ file-changes/<roundId>.json   # 单轮文件变更记录(经 task.fileChanges 拉取)
│   └─ w_xxxxx/tasks/<taskId>/       # 其它工作区任务目录(结构同默认工作区)
├─ sandbox/                          # 沙箱持久状态(home/opt/usr-local/resolv.conf/env;worker.sandbox.persistent-root 可覆盖)
│   └─ distro/                       # WSL 托管发行版 rootfs(原 wsl/distro 迁入;运行期状态,可整体重装)
└─ skills/                           # skill 目录(一目录一 skill,目录下必有 skill.md)
    ├─ agent-dispatch/skill.md       #   内置(启动时从 classpath 物化)
    ├─ plan/skill.md                 #   内置
    └─ <user-skill>/skill.md         #   外部(用户放置,可附带 scripts/ 等由 skill.md 引用)
```

**旧布局迁移**:`data/` 与 `wsl/` 目录已彻底删除;存量旧数据由独立迁移脚本手动执行一次迁移——`python3 scripts/migrate-workspaces.py [--home <EVERYAGENT_HOME>]`(幂等、可重试,不随 worker 启动自动跑;迁移逻辑为 Python,不依赖 mvn 打包)。

**五项持久化规则**(实现定死):

1. **完成态 `message` 落盘**(主/子同名):整轮思考(thinking)+ 正文(text)+ 工具调用下发(toolCalls,真实模型 toolCall id;缺失兜底生成且三处一致)。工具返回**单独**落 `tool.result`。
2. **瞬态不落盘**:`delta`/`thinking` 只发前端,消耗 seq 但不写盘(磁盘回放有洞的来源)。
3. **ext 为 null 不写行**:jsonl 行 = `{seq, ts, event, agentId, payload[, ext]}`。
4. **jsonl 每行必记 agentId**(文件内字段;线上 wire 主 agent payload 不带)。
5. **按 agent 分文件**:每 agent 一个 `<agentId>.jsonl`;读取按 seq 归并全部文件。

#### 7.15.1 轮次索引 rounds.jsonl

主 agent 侧生成轮次索引(每行一轮:用户输入 → 主 agent 最终回复):

- 行格式:`{index, startSeq, endSeq, user, finalReply, durationMs, startedAt, agentRanges, userMessage}`(**轮行不含任何插件业务字段**,文件变更等按轮旁路数据由插件自持文件 + 插件 RPC 提供,§7.15.2);seq 一律字符串;`endSeq=""` = 未闭合轮;`startedAt` = 开轮落盘时刻(epoch 毫秒,耗时从磁盘算的起点;`durationMs` = 闭合时当前时间 − startedAt);`userMessage` = 完整 user.message payload(懒加载骨架),**形状必须与 user.message 事件 payload 一致**(`{content, data:{rawContent?}}`)——开轮落盘(openRoundAtStart)与 scan 重建两条路径同形,前端 foldRound 按 `userMessage.data.rawContent` 回放 @文件胶囊。
- 增量写:消费用户输入即 `openRoundAtStart` 落一行 `endSeq=""`(并把 `startedAt = System.currentTimeMillis()` 随行落盘);`RoundIndexAdvisor` 在主 agent 最终回复后 `rewriteRound` 原位改写闭合(临时文件 + 原子 move,与追加同锁串行)。**`durationMs` 随闭合行同一次落盘内联写入**——耗时不再内存中计算:闭合轮时 `applyRounds` 取当前时间减去磁盘行的 `startedAt`(开轮落盘时刻)得到;任务出错停止后继续(续跑改判闭合)也以最初开轮时刻计耗时,跨运行延续不失真。`round.closed` 通知在闭合行落盘**之后**推送——前端收到通知拉 `task.rounds` 时耗时必已就位。历史上「先闭合推送、后单独回填耗时」的两段写存在竞态:前端在回填完成前拉快照会拿到 `durationMs=0` 且无后续刷新触发,表现为本轮耗时不显示(重连才恢复)。旧行/scan 行无 `startedAt`(0)时闭合不计算耗时(保持 0,优雅降级)。
- 旧任务首次 `task.rounds` 惰性全量生成落盘;任务终态 do `finalizeRounds` 补写未闭合轮。中断/失败/取消的未闭合轮自然保留。
- 消息编辑重发(`truncateAfterSeq`):事件日志按 `seq >= editSeq` 截断重写;rounds.jsonl 同步截断为 `startSeq < editSeq` 的行(**编辑点之前的轮次保留**,新轮 index 顺延)。事件文件重写只针对 agent 事件日志(`<agentId>.jsonl`)——任务目录下的 `rounds.jsonl`/`queue.jsonl` 不属事件空间(行无 seq 字段),误入事件重写会被「seq<=0 丢弃」整文件清空,表现为编辑后历史轮次全丢、新轮 index 归 1。
- 前端"双击打开任务" = 拉 meta → 一次 `task.rounds` 渲染折叠轮次 → 展开按 seq 区间懒加载过程内容。

#### 7.15.2 文件变更(file changes)

每一轮 agent 执行中对工作区的文件写操作由 **file-change 插件自管**(task 核心零 fileChanges 概念,§14.11):

- **写侧**:`FileChangeAdvisor`(order=HIGHEST+301,工具 advisor 内层)per-run 物化 collector,从模型流的 toolCalls 里识别 `create_file`/`update_file`;主 agent 最后一轮(无 toolCalls)收口把 collector 暂存进 provider,`RoundIndexStore` 闭合轮后经 `RoundClosedListener` 回调按 roundId 写全文分片 `file-changes/<roundId>.json`(轻量摘要与全文同在该文件,不落 rounds.jsonl 行)。
- **读侧**:唯一取数口是插件自己的 `task.fileChanges` RPC——`roundId` 必填返回该轮全文;省略 `roundId` 返回全任务各轮的轻量摘要(`{rounds:[{roundId, changes:[{filePath,fileName,changeType,saveCount}]}]}`),供前端轮末面板一次拉全。
- **失效与刷新**:前端插件 web 侧按 taskId 缓存全任务摘要;宿主 `taskStream` 收到瞬态 `round.closed` 信号时 emit 领域事件 **`task-round-closed`**(通用名,不含文件变更语义,payload `{taskId, startSeq, endSeq}`),插件订阅后作废该任务缓存并重拉——旧任务(重构前落盘的分片)同样可显示,摘要始终由插件数据文件反推,不依赖轮行内联字段。

### 7.16 数据模型(完整)

**两个基础决策**:

1. **双事实源分离**:`Event`(不可变,磁盘 jsonl)是传输/回放/审计的事实源;`conversation: Message[]` 是 LLM 工作态,随运行销毁,再运行时由 ConversationLoader 重建。**上下文压缩只改写发送给模型的视图,不触碰两条事实源**。
2. **用户输入入日志**:输入被消费时追加 `user.message` 事件——否则重开后前端无法还原用户说过什么,其他前端也看不到。

**实体总览**:

```
worker(进程)
├─ WorkerConfig:workerId、hubs[{url,apiKey,hubKey}]、homeDir(系统目录)、skillsDir、workspaceRoot(默认工作区根,默认 `<home>/defaultworkspace`)、
│              limits{maxConcurrentTasks(20)、maxConcurrentSubs、askTimeoutMs(30min)、subWaitTimeoutMs(5min)、
│              maxEventsPerTask(50万)、context 系列、maxRepeatedToolRounds(3)}
├─ ModelConfig[]:configId、provider、baseUrl、model、params、default   ← 由 Spring 配置承载(§7.17)
├─ Task[](运行中,内存驻留;终态即销毁)
│   ├─ EventLog(内存)+ → 磁盘 *.jsonl(按 agent 分文件)
│   ├─ Input[](串行输入队列,待消费快照 pendingInputs)
│   ├─ main Agent(唯一)
│   │    ├─ conversation: Message[] ←  LLM 工作态(运行结束销毁;再运行重建)
│   │    └─ Ask[]
│   └─ sub Agent[](parent = main,深度 ≤1)
│        ├─ conversation: Message[]
│        └─ Ask[]
└─ 磁盘路由索引:taskId → {dir, summary}(终态任务;常驻内存)
```

**实体字段**:

| 实体 | 字段 | 说明 |
|---|---|---|
| **Task** | taskId(短 ID `t_…`)、workerId、title?、workspaceId/workspace(均 meta 属性)、status、modelSnapshot、createdAt/startedAt/endedAt、summary?、error?、usage、inputQueue、agents(主体活动 agent 注册表 = `ExecContext.agents()` 槽位,§7.20)、asks、eventLog、mainAgentId | modelSnapshot 为创建时快照;usage 聚合值 |
| **Agent** | agentId(主 `a_…` / 子 `sub_…`)、taskId、parentId、kind(main/sub)、title、status、systemPrompt、toolset、conversation、usage、createdAt/endedAt | 主/子统一建模;子的 toolset 剔除 agent 工具(结构性禁递归) |
| **Message** | messageId、role(system/user/assistant/tool)、content、toolCalls?、toolCallId?、ts、meta{compressed?} | LLM 语义条目,仅存 conversation |
| **Event** | taskId、seq、ts、event、agentId、payload、ext? | 不可变;文件行 agentId 恒非空;瞬态不入盘 |
| **Ask** | askId(短 ID `q_…`)、taskId、agentId、kind、question、options?、status、answer?、answeredBy?、timeoutAt | 运行时的 CompletableFuture 不入模型 |
| **Input** | taskId、text、rawContent?、ts、from(sessionId) | 状态:queued → consumed(取消时 discarded);`rawContent` 为原始输入(含 opaque token 串) |

**斜杠命令与任务级开关**:斜杠命令由 worker 动态注册(`slash.list`/`slash.select`/`slash.cancel`);任选中可返回多个结果(如 `/无人值守` 一次返回「无人值守」+「AI 审议」两个胶囊);任务级 token(模型池、AI 审议、无人值守、禁用网络等)随 meta 持久化、再运行保持,`slash.taskTokens.apply` 用于落地 token 携带的数据。

**composer token(opaque token)双轨与 worker 解析**:输入框胶囊(斜杠命令、`@` 文件引用等)由前端构造为 inline opaque token(`[[[[agent-token::::<kind>||||label/summary/payload…]]]]`,4 连符号定界零转义);提交走**双轨**——`text` 为人类可读明文,原始 token 串随 `Input.rawContent` 上行(重开/回放按 rawContent 还原胶囊)。worker 侧 `SlashTokenResolveAdvisor` 在 user 消息进入模型前扫描正文、交 `SlashTokenHandler` 按 kind 分发解析为提交文本(未知 kind/解析失败保留原串);已注册 kind:技能命令 → 技能名、`git.auto_sync` → 空串、`system.workspace_file` → 工作区相对路径明文。

**`@` 弹窗外部文件引用(kind=`system.external_file`)**:payload `{absolutePath, fileName, kind:"file"|"directory"}`;前端不解析该 token,提交时原串上行(复用 slash token 通路),worker 统一解析:① realpath 不存在 → 替换为失效提示文本;② realpath 落在工作区内 → 退化为「工作区相对路径」明文(与 `system.workspace_file` 提交语义一致);③ 工作区外 → 注册为该工作区外部授权根(§7.17)并替换为「原生绝对路径 + 沙箱内路径」文本。前端入口:`@` 弹窗标题行 `+` 图标打开外部文件选择框(`fs.browse` `includeFiles=true` 数据源;默认目录=当前工作区根,面包屑+返回父目录,最顶层为盘符根列表;目录行可进入+可选,文件行可选;响应缺 `supportsFiles` 时降级仅目录);点击 `+` 先删除输入框中的 `@` 触发片段,选中后与 @ 搜索选中一致走 insertToken 插入胶囊。

**队列输入与「插入到当前对话」**:任务运行中输入入队(pendingInputs 经 `task.updated` 广播外显、`task.queueSnapshot` RPC 拉取——队列面板数据源由插件自持,`ComposerPanelCtx` 等公共类型不携带插件专有字段;可 `task.queueRemove`/`task.queueMove` 管理,消费一项即广播刷新);「插入到当前对话」走 `task.run{taskId, input, metadata:{insert:true, index}}`(队列插件在 `queue.dispatch` 消费该 metadata;**先按 index 从输入队列摘掉该项**——摘不到再按正文匹配,插入即消费,不摘则面板原地不动且本轮跑完会被当新一轮输入重复提交,再把摘到的队列项——带 `rawContent`,非前端回传的裸文本——交给本轮主 agent 的插入队列),`DialogInsertAdvisor` 随下一轮工具结果以 role=user 提交给 AI + 发 `user.message`(payload 形状与 `consumeInput` 同构 `{content, data:{rawContent}}`,并进 agent conversation)——advisor **按 taskId 现取插入队列**,不在构造期缓存引用(队列是点击时懒建的,缓存会整个 run 持 null 静默失效);本 run 再无工具循环下行时机(AI 已收尾/取消/失败)而未 drain 的插入项由 `queue.loop` 回收进输入队列、随 `queue.jsonl` 落盘为悬空队列,下次运行作普通轮次消费——**用户输入不丢**。

队列项操作语义:**编辑**=回填输入框 + 摘除该项两步都必须发生(只摘不回填等于删掉这条输入)——回填走插件自己的 `ctx.ui.setComposerRawContent`(有 `pendingInputsRaw` 时,输入框重解析还原 @文件胶囊)或 `ctx.ui.appendComposerText`(仅纯文本),再 `task.queueRemove`;面板经插件内 `pluginRuntime` holder 取 `ctx.ui`(惯例同 task-edit-resend/file-change),`ComposerPanelCtx` 公共上下文不携带宿主能力;草稿非空时拒绝回填以免覆盖用户正在输入的内容。宿主 `TaskChat` 曾留一个未接线的 `handleEditQueuedDraft` 纯文本回调(注释谎称队列面板回填已由宿主代劳,是「编辑=删除」bug 的成因),已随该修复删除。

**不变式**:

1. Task ↔ 主 agent 一对一;taskId/mainAgentId 跨运行不变。
2. sub.parent 恒等于主 agent → 深度 ≤1(结构性保证)。
3. conversation 按 agent 隔离(独立 jsonl);子 agent 不继承父历史。
4. 同 task 多轮用户输入(含跨运行)全部进同一主 agent 历史;经 inputQueue 串行消费。
5. task.status 是聚合投影:`waiting-user` ⇔ 存在 pending ask;终态前提 = 主 agent 完成且全部子 settled。
6. seq 任务级单调递增(跨运行延续);瞬态消耗 seq 不落盘 → 磁盘有洞合法。
7. task.usage = 主 agent.usage + Σ 子 agent.usage;meta 持久化最近一轮主 agent 上下文占用快照。
8. 日志永不修剪,任务永不自动消失;唯一删除 = task.delete;内存 EventLog 超上限抛 LogOverflow(护栏)。

**状态机**:

```
Task:   (无) → running ⇄ waiting-user → done|failed|cancelled;终态 --task.input--> running(冷启动普通运行)
Agent:  (main) running ⇄ waiting-user → done | failed | stopped(级联)
        (sub)  spawned → running ⇄ waiting-user → done | failed | stopped
Ask:    pending → answered | timeout | cancelled
Input:  queued → consumed | discarded(任务取消)
```

### 7.17 系统目录、配置与工作区

**系统目录 `~/.everyagent/`**(`EVERYAGENT_HOME` 可覆盖):worker 的机器级状态,与工作区无关。

| 路径 | 内容 |
|---|---|
| `application-worker.yaml` | 【可选】worker 用户配置覆盖(模型 `worker.models`、hub 连接、沙箱等;存在才生效) |
| `application-hub.yaml` | 【可选】hub 用户配置覆盖 |
| `application-dev.yaml` | 【可选】开发覆盖(IDEA 经 additional-location 显式指定) |
| `skills/` | skill 目录(一目录一 skill,目录下必有 `skill.md`);内置 skill 启动时从 classpath 物化为 `<id>/skill.md`(并清理旧扁平 `<id>.md` 担留);外部 skill 由用户放置 `<目录名>/skill.md`(可附带 `scripts/` 等由正文引用、AI 用对应解释器执行——bash/python/node 等)。AI 经 read_file 读写访问;同时读写挂入 wsl 沙箱供 bash 工具操作,§7.10。详见下文「skill 目录结构与外部 skill」 |
| `defaultworkspace/` | 默认工作区根(原 `workspace/` 改名,自动注册 id=defaultworkspace,始终在册;内含 `.everyagent/` 工作区级 git 凭证加密存储) |
| `workspaces/` | 唯一工作区注册表 `workspaces.json` + 按工作区归类的任务数据 `workspaces/<workspaceId>/tasks/<taskId>/` |
| `sandbox/` | 沙箱持久状态(home/opt/usr-local/resolv.conf/env;`worker.sandbox.persistent-root` 可覆盖);内含 `distro/` = WSL 托管发行版 rootfs(原 `wsl/distro` 迁入,运行期状态,可整体重装) |

**配置分层**:进程配置全部来自 jar 内 `application.yml` 默认 + `~/.everyagent/application-*.yaml` 用户覆盖(`spring.config.additional-location: optional:file:${EVERYAGENT_HOME:${user.home}/.everyagent}/application-worker.yaml`,自动加载,无自定义则零配置文件)。**模型配置由 `worker.models`(Spring 配置)承载**,默认在 jar 内(apiKey 占位符),真实 key 只写用户覆盖文件(机器级、不进工作区、不进 jar/git、不进事件日志)。

**程序附属文件**:rg 二进制、eagent-run.py、WSL 托管镜像统一放**程序根 `<程序根>/runtime/`**(程序根 = JVM 工作目录 user.dir;打包态 = resources 目录,IDE 态 = 仓库根),随安装包分发、运行时只读引用、以字面相对路径 `./runtime` 解析;不打进 jar、不写入系统目录。`worker.program-dir` 配置用于打包态显式指定。

**多工作区并行**:注册表 `workspaces/workspaces.json` 条目 `{id, root, addedTs, lastActivityTs?, externalRoots?}`,引入**稳定 workspaceId**——默认工作区 id 恒为 `defaultworkspace`;其它工作区首次注册用 ShortIds 生成 `w_xxxxx` 短 id,落盘进注册表 `id` 字段,此后不变。默认工作区根默认 `<home>/defaultworkspace` 并自动注册进注册表(id=defaultworkspace),始终在册、不可移除。`fs.*`/`git.*`/`task.run`(新建)每次调用**必带 `workspace` 参数**(绝对路径),沙箱根在调用时按该参数解析;注册表变化广播 `workspaces.changed`(`workspaces.list` 与快照每项带 `id`、`addedAt`、`lastActivityAt`(任务收口刷新,旧条目回退注册时间),仍带 `defaultRoot`;缺失项含缺失标记 `missing`);写操作广播 `fs.changed{workspace,path,kind}`,前端按工作区分组刷新。**任务收口按「最后活动时间」倒序渲染**:收口路径(`TaskManager.finish`)经独立组件 `WorkspaceActivityTracker` 刷新任务挂靠工作区的 `lastActivityTs` 并广播(失败不阻塞收口);前端 `workspaceRegistry` 合并后按 `lastActivityAt ?? addedAt` 倒序,任务面板 / 文件管理器 / 源代码管理器渲染工作区顺序一致地对齐「最近活动的在最上面」。

**工作区外部授权根(externalRoots)**:工作区条目的 `externalRoots` 字段(realpath 规范化路径数组)承载用户经 `@` 弹窗 `+` 图标显式选择的工作区外路径(§7.16),授权语义 = **完全读写(READ+WRITE+EXEC)**——「用户显式选择=已授权」:文件路径责任链 `ExternalRootAllowCheck` 放行环直接放行、不弹授权 ask(§7.8),各沙箱后端按 §7.10 消费。**注册规则**:目录=自身、文件=父目录;去重与包含吸收(新根被已有根包含 → 跳过,已有根被新根包含 → 替换);复用 `OverBroadRootCheck` 语义拒收过宽根(盘根、工作区祖先/工作区自身)。**生命周期为工作区级**(跟工作区走,非任务级);`workspaces.remove` 删除工作区时级联清理:仅 wsl-direct 后端,对该工作区**独有**(其余工作区 externalRoots 的 realpath 均未引用)的根 best-effort umount——`wsl.exe -d EveryAgent -u root -e umount <挂载点>`(挂载点 = `WslPathMapper.toDirectMount(原生路径)`),失败 lazy umount 兜底,仍失败仅 WARN 不阻塞删除;bwrap 按次 bind 天然跟随,mic 零副作用天然跟随。

**启动自检(工作区被移动/删除)**:worker 启动时校验 `workspaces/workspaces.json` 载入的已注册目录,缺失者(用户移动/删除目录后重启)在注册表快照标记 `missing`并广播,前端弹窗要求二选一——`workspaces.resolveMissing {action:"delete"}` 删除注册并**直接删 `workspaces/<wsId>/` 整个目录(任务数据随删)**,或 `{action:"redirect",newRoot}` 纠正到移动后的新目录——**保留 `id`、只改 `root` 并迁移挂靠任务的 `meta.workspace`,任务目录不搬**;默认工作区不可删除、只可纠正(纠正后的根直接写回 `workspaces.json` 中 id=defaultworkspace 条目的 `root`,重启读回,不再需要 `workspace-default.json` 覆盖文件)。未落定的缺失工作区 `resolve` 拒绝,避免沙箱挂载失败或静默新建空目录掩盖数据丢失。

**skill 读写放行**:系统目录 `skills/` 是 AI 文件工具对系统路径的**唯一免授权**例外——``read_file`/`fs.write`/`fs.mkdir` 等经权限责任链节点 `SkillsReadAllowCheck` 直接放行(realpath 前缀判定,READ+WRITE 均放行);EXEC 不在此放行,仍走授权决议链;其余系统路径(workspaces/、sandbox/、runtime/ 等)与普通工作区外目录同权,一律走授权决议(弹窗/AI 审议)。`skills/` 同时读写挂入 wsl 系列沙箱(§7.10:wsl-direct drvfs、wsl-bwrap `--bind`),bash 工具在沙箱内同样可达,读写均放行;windows-mic 后端跑在宿主,Medium IL 读写用户文件本就放行,无需挂载。以上读写放行与沙箱读写挂载同时覆盖内置与外部 skill 目录;外部 skill 携带的脚本经对应解释器执行(如 `bash script.sh`/`python3 run.py`/`node run.js`)。

**skill 知识包路径的沙箱注入**:`SkillAdvisor` 注入 system prompt 的知识包路径按当前沙箱后端解析(§7.17):WSL 系列沙箱下 `Skill.knowledgePath`(宿主 Windows 绝对路径)经 `WslPathMapper` 翻译为 AI 沙箱内可见的 `/` 开头 Linux 路径(wsl-direct `/c/...`、wsl-bwrap `/mnt/c/...`),使 AI 的 `bash`(`cat`/`grep`)与 `read_file`(经 `FsToolSupport.resolveWslPath` 反向翻译回宿主路径)均能直接使用同一路径;非 WSL 后端原样注入宿主路径。`Skill` record 仍存宿主绝对路径(物化/沙箱挂载均以此为准),路径翻译仅发生在注入提示词时。

**skill 目录结构与外部 skill**:skill 统一为「一个目录一个 skill」,目录下必须有 `skill.md`。内置 skill(agent-dispatch、plan、skill-creator)启动时由 classpath `skill/<id>.md` 物化为 `<skillsDir>/<id>/skill.md`(幂等:目标已存在且大小一致则跳过;不一致则覆盖),同时清理旧扁平 `<id>.md` 担留(从扁平单文件迁移到目录形态的一次性清理)。外部 skill 由用户手工放置 `<目录名>/skill.md`(可附带 `scripts/`、配置等任意文件,由 `skill.md` 正文引用、AI 用对应解释器执行——bash/python/node 等不限语言)。**id 规则**:目录名即 skill id,仅允许 `[a-z0-9][a-z0-9-]*`;realpath 必须仍在 `skillsDir` 内(拒绝符号链接越界);与内置或其它外部 skill 同名冲突时跳过并 WARN(内置优先;内置含主动与被动两类,均排除外部重名)。**描述提取**:无 frontmatter、不引入 YAML 依赖;描述 = `skill.md` 首个非空且非 `#` 标题行的正文行,截断至 200 字符;提取失败(不可读/全文仅标题)则描述为空串,仍注册。**披露通道分离**:内置 skill 分主动(`agent-dispatch`、`plan`)与被动(`skill-creator`)两类——主动 skill 由 `BuiltInSkills.getActiveSkills()` 返回 → `SkillAdvisor` 自动注入 system prompt(渐进式披露索引,现有行为不变);被动 skill 与外部 skill 一样**不进 system prompt**,只注册进 `/` 菜单(`SkillSlashProvider` 数据源 = `BuiltInSkills.getAllSkills()`(主动+被动) + 外部合并列表),由用户手动选择后走现有 `SkillSlashTokenResolver` 链路(token 解析为「请使用技能:`<title>`。知识包路径:`<skillPath>`」——被动/外部 skill 不在 prompt 中,路径是 AI 唯一能 `read_file` 知识包的来源,故始终注入),AI 按需 `read_file` 知识包——零新增组件。`/` 菜单条目 title = 目录名,副标题 = 描述提取结果(被动内置 skill 的描述在 `Skill` 构造期硬编码,与主动 skill 一致)。**扫描时机**:worker 启动时一次性扫描(`@PostConstruct`),运行时经 `skill.reload` RPC 热加载(重新扫描并替换缓存,广播 `config.changed{keys:["skills"]}`,前端 `/` 菜单下次打开即拉取最新列表);只认 `skillsDir` 一级子目录,不递归;扫描器排除全部内置 id(含被动,`BuiltInSkills.getAllSkills()`)。**明确不做**:目录监听/watch、skill 开关 UI、脚本注册为独立 AI 工具(脚本一律由 AI 用对应解释器执行——bash/python/node 等不限语言,复用现有沙箱与 PermissionGate)、frontmatter/完整 YAML 语法支持。

### 7.18 内嵌终端(term.*)

文件树目录右键「在终端中打开」→ 前端主区开 xterm.js 内嵌终端标签页,worker 用**真 PTY** 拉起交互式 shell,cwd 为右键目录;输出经频道推送、输入走 RPC(§5.5)。

- **PTY 实现**:Windows 用 ConPTY(`CreatePseudoConsole`/`ResizePseudoConsole`/`ClosePseudoConsole` + `InitializeProcThreadAttributeList`/`UpdateProcThreadAttribute` + `STARTUPINFOEX`/`PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE`),经 `Win32Ex` 同款「扩展平台接口再 `Native.load`」范式补齐(jna-platform 5.16 已在 worker 依赖树,经已有 JNA 依赖,零新增);Unix/macOS 用 libc `openpty`(或 `posix_openpt`+`grantpt`+`unlockpt`+`ptsname`)经 JNA。**非本平台实现类不加载**(跨平台编译安全降级,仿 `WindowsSandbox`)。
- **shell 选择与 cwd**:Windows 取 `ComSpec`/`cmd.exe`,Unix 取 `$SHELL`/`/bin/sh`;cwd 由 `workspace`+`path` 经沙箱 `resolveExisting` 解析且必为目录(与 `fs.*` 同级权限,不额外提权);argv 直传无 shell 解析。
- **双向传输**:worker 起**虚拟线程**读 PTY 输出并 `pubForOwner` 定向推送到 `u.<K>.term.<termId>.stream`(event `term.output`,payload `{data: base64}`;进程退出推 `term.exited`);输入/尺寸/关闭走 `term.input`/`term.resize`/`term.close` RPC。hub 零状态只路由,不存会话、不存订阅簿。
- **会话生命周期**:存 worker 内存 `ConcurrentHashMap<termId, 会话>`;进程退出或 `term.close` 回收;worker 重启会话即失效;不实现跨前端重载续接(前端标签状态本就不持久化)。
- **前端契约**:termId 由前端生成,**先 sub 频道再 `term.open`** 避免丢首帧;xterm `onData` → `term.input`(base64);ResizeObserver/FitAddon → `term.resize`;标签关闭 → `term.close`。
- **平台降级**:无 PTY 能力/无桌面环境的平台抛 IO 异常转 RPC 错误,前端 toast 提示,不影响其他功能。

### 7.19 通用事件发射器与 Advisor 插件化

本节记录「通用事件发射器(EventEmitter)+ Advisor 插件化(重试/压缩/限流统一为 Advisor)」的完整架构(§14.0 红线的展开说明)。与之镜像的执行上下文管道(§14.11)见 §7.20。

#### 7.19.1 EventEmitter 通用事件发射器(plugin-api)

```java
// plugin-api: dev.everyagent.plugin.api.model
public interface EventEmitter {
    void emit(EmitEvent event);
}
```

插件/底层组件经此接口发射**强类型事件载荷**(`EmitEvent`):当前唯一实现 `TraceData`(id/kind/title/summary/content/status/persist),不暴露 JsonNode,不知道 agentId/taskId/seq/wire 格式/前端展示方式。

**分层管道**(语义→wire 映射在 task 层,§14.0):

```
Advisor 插件
  │  emitter.emit(EmitEvent.TraceData.transientOf("model_rate_wait", null, "模型排队中", null, "waiting"))
  │  语义事件名,不是 wire 格式;不知道 task.trace / wf.trace
  ▼
agent 层（ChatModelFactory.build() 内部 lambda）
  │  填上 agentId,转发给 task 层
  ▼
task 层（TaskEvents.emit）
  │  语义事件→wire 事件映射:
  │  instanceof TraceData → task.trace（kind=trace.kind, traceId=trace.id, title/summary/content/status/createdAt）
  │  "delta" → delta（直接透传）
  │  "error" → error（直接透传）
  │  然后 log.append(wireEventName, wirePayload, agentId, ext, !persist)
  ▼
EventLog → DataPusher.readFrom → WebSocketEmitter.push → conn.pub → 前端
```

> `TaskEvents.emit()` 只写 EventLog(包装 + log.append),不直接调 WebSocketEmitter。推送仍由 DataPusher 的 `onAppend → readFrom → push` 链处理(push 委托 wsEmitter)。与改造前行为完全一致。以后工作流层实现自己的映射(`model_rate_wait` → `wf.trace`,自己的包装格式),自己的推送管道,前端按工作流 wire 格式解析。

#### 7.19.2 WebSocketEmitter 被动推送管道(worker)

只做:**背压控制 + 定向推送到前端 websocket**。不读 EventLog、不回扫、不对账。

- 从 `DataPusher` 原样搬来背压逻辑:`CREDIT_WINDOW`(128)、`nextPushIndex`、`ackedIndex`、`acquireCredit()`(synchronized 保护)。
- `DataPusher.push()` 组装 ext/payload 后委托 `wsEmitter.push(channel, event, seq, ts, payload, ext)`;DataPusher 删除 `acquireCredit`/`onAck`/背压字段。
- `DataPusherManager.onAck` 路由到 `WebSocketEmitter.onAck`。
- DataPusher 回扫路径和 EventEmitter 实时路径共用同一背压窗口,并发调 `push()` 需线程安全(`acquireCredit` 已有 synchronized 保护)。
- **前端零改动**:收到的帧格式完全不变。

#### 7.19.3 限流 Advisor 插件(every-agent-plugins/model-rate-limit)

限流插件以 Advisor 形式注入 Agent 执行链,不再经独立的模型请求中间层:

- **`RateLimitAdvisor` implements `CallAdvisor, StreamAdvisor`**:order=`ToolCallingAdvisor.DEFAULT_ORDER+500`(最内层,ContextCompression +400 之后),对主/子/AI 审议/池成员全部自动生效。
- `adviseCall`/`adviseStream` 中经 `a.snapshot()` 查 limiter:无限流配置 → 直通下一层;有配置 → `acquire()` 排队等待(虚拟线程 park,零线程开销),等待期间经 `a.emitter().emit(EmitEvent.transientOf(...))` 发语义事件(`AdvisorContext extends ExecContext`,直读槽位,§7.20)。
- 通过 Reactor 操作符(`doOnNext`/`doOnCancel`/`doOnError`/`doOnComplete`)绑定 Permit 生命周期:`doOnNext` 驱动流中 token 估算(`permit::onChunk`),`doOnComplete` 驱动事后精确记账与信号量释放(`permit::complete`),`doOnError`/`doOnCancel` 驱动取消与释放(`permit::cancel`)。不再经 `ModelRequestContext` 回调,直接在 Advisor 链操作。
- **限流类驻插件包**:`ModelRateLimiter` / `RateLimitAdvisor` / `RateLimitAdvisorProvider` / `ModelRateLimitConfig` / `ModelRateLimiterRegistry` / `BuiltinTokenEstimator` / `TokenCalibrationAdvisor`(+ Provider);测试一并随插件。
- **不耦合内核**:插件只依赖 `ModelConfig` + `EventEmitter`,不耦合 `ChatModel`/`Prompt`/`ChatResponse`/`Flux`/`TaskEvents`/`agentId`/`taskId`/`DataPusher`。
- **worker 核心清理**:无限流器引用;`ConfigRpcHandler.rateStatus` 返回空数组(插件后续自行注册 RPC);fallback `SimpleTokenEstimator` 就位(供 `model-length-guard` 插件使用)。
- **回退**:插件未加载时无限流 Advisor → 直通 → 不限流;WebSocketEmitter 未建立时(无前端订阅)`emit` 仍写 EventLog,不推前端。
- `WorkerProperties.ModelRate` 配置类留 worker(全局配置项);`TokenEstimator` SPI 接口留 plugin-api(`model-length-guard` 插件也依赖);插件经 `AdvisorProvider` 注册 Advisor。

#### 7.19.4 后续迁移路径

EventEmitter 是通用出口,后续把 `AgentEventChannel` 的 30 个具体方法逐步迁移到 `emit`:

| Phase | 内容 | 状态 |
|---|---|---|
| Phase 1(本次) | EventEmitter 接口 + WebSocketEmitter + 限流/重试/压缩统一为 Advisor 插件 | ✅ 已完成 |
| Phase 2 | `ModelPoolChatModel` 容灾切换 → `emit(TraceData)` + `ChatModelEnhancer` SPI + `model-pool` 插件抽离 | ✅ 已完成 |
| Phase 2.5 | ModelLengthGuardAdvisor → model-length-guard 插件抽离 + AdaptiveMaxTokensAdvisor 新增 | ✅ 已完成 |
| Phase 3 | Advisor 层事件(delta/thinking/usage/retry)→ emit | 待执行 |
| Phase 4 | 生命周期事件(agent_started/done/status/error)→ emit | 已收敛:agent.* 由 AgentEntity 一处发射(§7.20.1) |
| Phase 5 | `AgentEventChannel` 瘦身为仅含 emit 的接口(30 方法胖接口消解) | 待执行 |
| Phase 6 | 内核消费 ModelConfig(版本化缓存,支持动态配置热更新) | 待执行 |

> `AgentEventChannel` 当前不动(30 方法胖接口是独立架构债,§14.0 改造纪律约束迁移过程)。每次迁移一个方法:插件/底层只发强类型 `EmitEvent` 载荷,task 层用 `instanceof` 判断类型做映射,推送层和前端不变。

#### 7.19.5 ChatModelEnhancer SPI(plugin-api)

`ChatModelEnhancer` 用于**模型构建层替换**——插件在 `ChatModelFactory` 构建阶段介入,组合多个成员模型。

```java
// plugin-api: dev.everyagent.plugin.api.model
public interface ChatModelEnhancer {
    String id();
    boolean supports(String provider);
    EnhancedChatModel enhance(EnhancerContext ctx);
}
```

- `ChatModelFactory` 通过 `ChatModelEnhancerRegistry` 查找匹配的 enhancer(`find(provider)`),委托构建。
- `EnhancerContext` 提供 `resolveMember(String configId)` → `MemberSpec | null`（不存在返回 null,供插件跳过+告警）和 `buildMember(MemberSpec)` 回调,让插件复用 `ChatModelFactory.build()` 构建单个成员模型。`poolConfig()` 返回原始配置（`model` 字段即逗号串等插件私有格式,未解析）,成员格式解析/校验/组合策略全归插件。
- `EnhancedChatModel` 返回组合 `ChatModel` + 主成员 `ChatOptions`。

**`model-pool` 插件**(`every-agent-plugins/model-pool`):第一个 `ChatModelEnhancer` 使用者。检测 `provider=model-pool` → 解析 `poolConfig().model()` 逗号串（trim/去空/去重/顺序保持）→ 逐个 `resolveMember`：null 跳过 + error 日志;成员 provider 是本插件 id（组合型）→ 抛异常禁套池;全空 → 抛异常。通过的成员经 `ctx.buildMember()` 逐个构建 `ChatModel`,组装为 `ModelPoolChatModel`(按序容灾切换),返回 `EnhancedChatModel(pool, primaryOptions)`。容灾切换经 `EventEmitter.emit(EmitEvent.TraceData.of("model_failover", ...))` 发 trace,链状态 per-request 管理(`Flux.defer` 闭包内 `AtomicReference`)。

### 7.20 统一执行上下文管道（ExecContext）

本节记录「统一执行上下文 ExecContext + 三预绑定端口」的完整架构（§14.11 红线的展开说明），与 §7.19 事件管道互为镜像；方案定稿见 `docs/design-exec-context.md`（S1–S5 落地）。

**背景**：原执行主干道是穿透 agent 层的无类型黑盒 map `properties: {taskEntry, taskId, workspaceRoot, configId}`——task 层填入、`AgentContext.properties()` 透传、20+ 处 advisor 强转 `get("taskEntry")` 取用，工具链/授权链/子 agent/审议 agent 各自再手工组装一遍同一四件套。问题不在任何单一链路，在于执行上下文以无类型黑盒形态流通、每层消费者各自强转取数。收编后黑盒退役：`AgentContext.properties()` 删除（字段替换为 `execution`）、`ToolContextImpl.taskEntry()` 删除、`WorkerServices.agentFactory()` 删除、`TaskStoreService` agents 读写段退役、permission 包 `TaskInfo` 接口删除。

**分层管道**（与 §14.0 事件管道同构：每层只消费自己层级的槽位，上层预绑定往下传）：

```
task 层（TaskEntry = ExecContext 唯一实现）
  构造执行上下文并预绑定三端口（emitter / agentFactory / interaction）
        │
agent 层（AgentContext）
  execution() → ExecContext          ← properties() 黑盒已删
  emitter()（agent 级 agentId 包装）保留,包的是 execution().emitter()
        │
advisor 链
  a.snapshot().configId() / .emitter() / .terminal() ...   ← AdvisorContext extends ExecContext,强转消失
        │
工具执行链（InterceptingToolCallingManager per-run 构造参数直持 ExecContext）
  ToolContext / ToolExecutionContext / AdvisorContext（均 extends ExecContext，直读槽位，§7.20.5）
  FsToolSupport / CommandExecutor: gate.requirePath(ExecContext, agentId, path, op)
        │
授权链（AuthorizationRequest）
  record(ExecContext context, agentId, grantKey, prompt)
  GrantRegistry 按 ctx.subjectId() 分区、ctx.dataDir() 落盘 grants.json
        │
子 agent / 审议 agent（终端消费者）
  SubAgentManager: ctx.agentFactory().create(agentId)
  AiAuthReviewer:  req.context().agentFactory().create(agentId, reviewModel?)
```

每层只消费自己层级的槽位；下层不知道上层是谁（task 还是未来 workflow）。三预绑定端口 `emitter()`/`agentFactory()`/`interaction()` 同范式（静态代理，worker 内部实现，可链式套娃），与 EventEmitter 的 agentId 包装（§14.0）同构——**上层预绑定能力往下传，不传裸工厂/裸服务、不传任务域对象**。

#### 7.20.1 ExecContext 槽位表（plugin-api `execution` 包）

槽位判据：任何执行主体都必然具备的核心属性与端口才进接口；插件功能与主体特有槽位不进核心接口。

| 槽位 | 语义 | 收编前取法 | 主要消费者 |
|---|---|---|---|
| `subjectId()` | 执行主体 ID（授权状态分区键/审计字段；今天=taskId，未来=workflowId） | `t.taskId()` | 授权链、重试/护栏 advisor、GitAutoSync |
| `workspaceRoot()` | 工作区根路径 | `t.workspaceRoot()` | AgentsMd/SystemInfo/git 自动同步/外层权限链/FsTool |
| `workspaceId()` | 工作区稳定 ID | `t.workspaceId()` | 事件 payload 归属 |
| `snapshot()` | 完整模型配置快照（**configId 不设独立槽**，经 `snapshot().configId()` 取） | `t.snapshot().configId()/params()` | RateLimit/AdaptiveMaxTokens/LengthGuard/Compression |
| `emitter()` | 已绑定主体的任务级事件口（trace/审计/agent.started 落此处） | `t.events()` | 全部 advisor、SubAgentManager、AiAuthReviewer |
| `agentFactory()` | 已绑定主体的 Agent 工厂（静态代理，见 §7.20.3） | 手工组装四件套 → create(...) | SubAgentManager、AiAuthReviewer |
| `metadata()` | 主体策略标记（随 meta.json 落盘的持久数据，不混入运行时瞬态） | `t.metadata()` | Unattended/AiReview 授权节点、slash provider |
| `dataDir()` | 数据目录（grants.json/agents.json 落盘；今天=任务数据目录） | `t.taskDir()` | GrantRegistry、AgentLedger |
| `terminal()` | 主体是否已收口（leak-guard：终态后不再发射事件） | `t.status.terminal()` | WorkerToolEventAdvisor |
| `interaction()` | 已绑定主体的用户交互口（静态代理，ask 的 context map 自动填 subjectId；替代三处手动组装 `Map.of("taskId",...)`） | 手动组装 context map | Human 授权节点、ImageReferenceHandler、AskUserTool |
| `agents()` | 主体活动 agent 注册表（可读写 Map：主 agent + 各插件派生——子 agent、审议 agent；**由 `AgentBuilder.build()` 自动填充（只 put；agent.* 生命周期事件由 advisor 链 + `AgentEntity` 统一发射，§7.20.1），插件不再手动 put/emit**；无子 agent 是正常形态） | `t.agents()` | SubAgentManager（复用判定）、AiAuthReviewer（复用判定）、AgentLedger（台账投影） |

**不进 ExecContext 的槽位**：fileChanges 曾是「留 TaskRuntime 的插件功能槽」，现已彻底退役出核心接口（连 TaskRuntime 也不留，§7.15.2）——collector 住 `FileChangeAdvisor` per-run 实例字段，按轮落盘靠 `RoundClosedListener` 回调，读侧靠插件自注册的 `task.fileChanges` RPC；status 完整状态/touch 等任务操作同为任务域私有，横切层只需要 `terminal()`。

**agent 层 per-run 生命周期状态机 + 统一台账（`AgentContext` 槽位，非 ExecContext）**：

- **状态机与发射单点**：agent 级生命周期事件（`agent.started` / `agent.status` / `agent.done` / agent 级 `error`）由 worker `AgentEntity`（状态持有者）**一处发射**，触发源在别处、零手搓事件：
  - `AgentStatusAdvisor`（worker agent 层，`StreamAdvisor`，order=`HIGHEST_PRECEDENCE+5`=全链最外层）把 ChatClient 流生命周期信号翻译成契约方法——`adviseStream` 入口 → `AgentContext.beginRun()`（started + running）；`doOnComplete` → `claimTerminal(completed)`；`doOnError` → `claimTerminal(error, 根因)`；`doOnCancel` → `claimTerminal(stopped, "已取消")`。它**不继承** `ToolCallingAdvisor`：只需「整轮一次」的流信号，而工具循环递归只重入比自己更内层的 advisor，故挂在外侧恰好每轮 `run()` 进一次；最外层还保证终态事件晚于 `WorkerToolEventAdvisor(+300)` 的 message/usage 与 `RoundIndexAdvisor(+10)` 的 rounds 闭合落盘（前端正是以主 agent 终态 `agent.status` 触发拉 rounds）。
  - `InteractionServiceImpl` 的 ask 生命周期 → `AgentContext.markWaitingUser()` / `markAskResolved()`。**由 ask 入口驱动而非按 `ask_user` 工具名嗅探**：危险命令授权（`HumanAuthorizationHandler`）、图片理解授权（`ImageReferenceHandler`）与 `ask_user` 走同一个 `ask()` 入口，按工具名嗅探会漏掉后两类（授权发生在工具实现内部，工具拦截链看不见）。
  - per-run 微观状态 `idle → running ⇄ waiting-user → terminal` 与终态声明都是 `AgentEntity` 内的 CAS 原语，每轮 `beginRun()` 复位；宏观 `status`（completed/stopped/error，被 list_agents/wait_agents 与台账消费）由终态声明同步写入。
- **主 agent 与派生 agent 一视同仁**：不按 creator 分支。主 agent 每轮 `run()` 也走 `started → … → done`；task 级状态走 `task.updated`（StatusNode / TaskManager.setStatus），两个维度互不混用。
- **`AgentBuilder.build()` 只管注册**：`ctx.agentFactory().create(id).creator(...).build()` 末尾自动 `exec.agents().put(id, agent)`；**不再发射 `agent.started`**（移入 advisor 链）。**插件只管创建与配置，永远不碰 put 和 agent.* 事件**；复用路径（`agents().get(id)` 命中续跑）也不再手动补发 `agent.started`——`resetForRerun()` + `run()` 即由 advisor 发新一轮出生事件。
- **插件侧唯一保留的兜底**：`SubAgentManager.stop()/stopAll()` 与 `runSub()` 的 catch 分支调 `Agent.claimTerminal(...)`。原因是 `FutureTask` 被 cancel 时可能根本没开始执行、或 `run()` 在订阅前就抛——这些路径没有流生命周期信号，不发终态前端就永远显示 running。CAS 使它与 advisor 的终态互斥，绝不重复发事件；插件构造 EmitEvent 的代码全部退役。
- **终态发射顺序固定** `error? → agent.done → agent.status{终态}`：台账的 `agent.done` 分支无条件写 `status=completed`，终态 status 必须后发才不被改判。
- **台账收编 worker core `AgentLedger`**：原 subagent 插件 `SubAgentLedger` 退役。`AgentLedger`（worker agent 层，`@Component`）订阅主体 EventLog 的 agent.started/done/status/usage/message/error 事件，维护 per-subject 内存台账（agentId → 摘要），经 `AtomicFiles` 自行读写 `ctx.dataDir().resolve("agents.json")`（30s 定时 + 终态 persistFinal 两路径），冷启动从 agents.json 恢复（running/waiting-user → stopped 归一，旧任务回退 meta.agents）。
- **per-run 语义下 `agent.started` 在台账侧是「合并」不是「整项替换」**：同一 agentId 每轮都发 started，`createdAt`/`usage`/`context`/`latestActivity` 是跨轮累积资产，替换会把它们抹零并把排序键 `createdAt` 重置成本轮时刻（复用子 agent 跳到列表末尾）；合并只刷 `status=running` 与 title/creator/metadata。
- **`AgentContext.creator()` 槽位**（plugin-api，default 返回 null）：**顶级字段**（不再是 `agentMetadata` 里的约定键），取值 `task`（主 agent）/ `subagent` / `ai-review`，由 `AgentBuilder.creator(String)` 设定，经 `agent.started` 事件 payload 投影进台账**顶级 `creator` 字段**随 agents.json 落盘。`agentMetadata()` Map 保留给其他元数据。**是否可见是消费方决策**——`list_agents` 工具与 `task.agents` RPC 只取 `creator=subagent`（旧条目无顶级 creator 时回退 `metadata.creator`，两者皆空视为旧格式子 agent 保留）；审议 agent（`creator=ai-review`）不出现在子 agent 列表；`task.agents` 另恒保留 `agentId == mainAgentId` 的条目（前端 `agentMeta['']` 的 title/usage/context 基线在冷启动时只认台账这一个数据源）。

#### 7.20.2 TaskRuntime 与 TaskInfo

- `TaskRuntime extends ExecContext`：任务域私有成员保留在本接口（taskId/status/taskDir/mainAgentId/log/时间戳/touch/truncateLogAfter；fileChanges 系列已退役出核心，见 §7.15.2）；`subjectId()`/`emitter()`/`dataDir()` 以 default 桥接方法映射到任务域成员（taskId/events/taskDir）。
- `TaskEntry`（worker）是**唯一实现**：`implements TaskRuntime`，`subjectId() = taskId`；三预绑定端口在 TaskEntry 内实现（`agentFactory()` 经 `AgentFactoryImpl.bind()` 取代理、`interaction()` 懒加载 `SubjectBoundInteractionService`）。
- **`TaskInfo` 已退役（S3 删除）**：原 permission 包接口五成员（taskId/metadata/taskDir/terminal/status）全部被吸收（taskId→subjectId、metadata→metadata、taskDir→dataDir、terminal→terminal、status→TaskRuntime），消费者改 import `TaskRuntime` 或 `ExecContext`。

#### 7.20.3 AgentFactory 绑定工厂（plugin-api 两参签名）

```java
public interface AgentFactory {
    AgentBuilder create(String agentId);                   // 绑定默认 configId = snapshot().configId()
    AgentBuilder create(String agentId, String configId);  // 覆盖模型（null = 绑定默认值）
}
```

- `AgentFactoryImpl`（worker 内部化）：全参主干 `create(agentId, configId, ExecContext)`——emitter 固定取 `exec.emitter()`，configId 为 null 时取绑定默认；`bind(ExecContext)` 产出绑定代理。
- `TaskBoundAgentFactory`：闭包「工厂实现 + 执行上下文」的静态代理（包内可见，`implements AgentFactory`，插件依赖类型不变，可链式套娃——未来 `WorkflowBoundAgentFactory` 闭包 workflow 绑定、透传 task 绑定值）。
- **`ExecContext.agentFactory()` 是唯一获取口**（`WorkerServices.agentFactory()` 已删）；原四参签名（emitter + properties map）从公共 API 消失，调用方（子 agent/审议 agent 创建方）单参即可创建，不再手工组装 emitter/properties。

#### 7.20.4 授权链新契约（§7.8/§7.14.3 展开）

- `AuthorizationRequest(ExecContext context, agentId, grantKey, prompt)`——主体数据面全部由 ExecContext 槽位携带（subjectId/metadata/dataDir/emitter/agentFactory/interaction），授权请求只剩 `grantKey`（授权状态分区键）与 `prompt`（授权请求原文）两个授权专属参数。
- `GrantRegistry` 域中性：按 `subjectId()` 分区授权状态（beginRun/untrack/extraRoots/execRoots 参数语义改 subjectId，值不变——今天=taskId）、`dataDir()` 落盘 grants.json（wire 格式不变）。
- 外层权限链 `PermissionContext` 域中性形态：不携带 `TaskEntry`——路径判定用 `workspaceRoot` 字符串 + 授权决议经 `authReq`（AuthorizationRequest，内含 ExecContext）；调用方（`FsToolSupport`/`CommandExecutor`）直取 `ToolContext`（本身即 ExecContext，§7.20.5）构造。

#### 7.20.5 Context 接口收编（全部 extends ExecContext）

各横切 Context 接口统一 `extends ExecContext`（与 `TaskRuntime` 桥接范式同构，§7.20.2）：重复字段一律删除、`execution()` 槽位一律退役——**Context 即执行上下文本体，消费侧直读槽位**，不再有间接访问器。

| 接口 | 删除 | 保留（领域专属成员） | 桥接/实现 |
|---|---|---|---|
| `ToolContext`（spi 包） | `taskId()`、`workspaceRoot():Path`（与 ExecContext 的 String 返回类型冲突，消费侧改 `Path.of(ctx.workspaceRoot())`）、`interaction()`（由 ExecContext 预绑定交互口取代）、`execution()` | agentId/sandbox/workspaces/rgBinary/shellExecutor（工具创建专属） | worker `ToolContextImpl` 持有 ExecContext 并委托全部槽位 |
| `AdvisorContext` | `taskId()`、`workspaceRoot():Path`、`execution()` | agentId/toolCallingManager/agentEntity；**`configId()` 保留**——它是 per-agent 语义（审议 agent 覆盖模型时与 `exec.snapshot().configId()` 不同），不是重复字段 | worker `AdvisorContextImpl` 的 ExecContext 槽位委托 `agentEntity.execution()` |
| `FileReferenceContext` | `taskId()`、`workspaceId()`、`workspaceRoot():Path`、`execution()` | （无领域专属成员） | 早期节点任务未创建时槽位可为 null，语义保留 |
| `ToolExecutionContext` | `execution()` | prompt/chatResponse/toolCalls（拦截专属） | 拦截器直读槽位（如 `ctx.metadata()`） |
| `TaskLifecycleContext` | — | `taskId()`/`workspaceRoot()`/`workspaceId()` 作为**任务域成员**保留（消费侧零改动） | 仿 TaskRuntime 范式：新增 default 桥接（`subjectId()`→`taskId()` 等），其余 ExecContext 槽位以 default 委托 `taskRuntime()`（早期 RPC 阶段任务未创建时返回 null/空，语义保留） |

**消费侧命名迁移规则**：横切层取主体 ID 一律 `subjectId()`；原 `ctx.taskId()` 调用点（ToolContext/AdvisorContext/FileReferenceContext/ToolExecutionContext 的消费侧）全部迁移到 `subjectId()`，`taskId()` 只保留在 TaskRuntime/TaskLifecycleContext 等任务域接口上作为任务域成员。

#### 7.20.6 消费者取数路径（properties 黑盒与 map 槽退役后）

- advisor：`AgentContext.execution()`（`AgentEntity` 字段替换为 execution）；`AdvisorContext extends ExecContext`，槽位委托 `agentEntity.execution()`（§7.20.5）。
- 工具 provider：`ToolContext`/`ToolExecutionContext` 本身即 ExecContext（`extends`，`execution()` 槽位已删、直读槽位；`ToolContextImpl.taskEntry()` 已删，§7.20.5）；`InterceptingToolCallingManager` per-run 实例构造参数直持 ExecContext（不依赖 ThreadLocal——reactive 流工具执行可能切 boundedElastic 线程）。
- subagent 插件：全面中性化（§7.14）——Manager 零服务依赖收 ExecContext、**台账收编 worker core `AgentLedger`（`SubAgentLedger` 退役，agents.json 读写/事件投影/冷启动恢复全部由 worker agent 层承担）**、`TaskStoreService` agents 段退役、生命周期节点壳核分离、`task.agents` RPC 壳留 task 面（mainAgentId/meta.json 是任务概念，读侧按顶级 `creator=subagent` 过滤 + 恒保留主 agent 条目，§7.20.1）、方法名常量 TASK_AGENTS 跟注册方走（住插件侧）；SubAgentManager 创建子 agent 时设 `creator("subagent")`、移除手动 put（由 `build()` 自动完成）与**全部 agent.* 事件手搓发射**（含复用路径的 `agent.started` 补发）；`TaskStore.truncateAfterSeq` 编辑重发截断只动 *.jsonl/rounds.jsonl，**不删任何插件数据文件**（agents.json/file-changes 残留陈旧条目被接受；后续可增加 `task.truncated` 截断事件通知由插件自清——开放项，task 核心永不知晓插件文件名）。
- AI 审议 agent：per-task 固定 agentId `review-<subjectId>` 注册进 `agents()` 跨请求复用会话（§7.9）——`agents().get(id)` 命中即续跑（复用路径不补发 `agent.started`——advisor 链每轮 run() 自动发）；未命中经 `ctx.agentFactory().create(id, reviewModel)` 创建，设 `creator("ai-review")` 后 `build()` 自动注册（台账按 creator 过滤不出现在子 agent 列表）。
- `InteractionService` ask 的 context map `"taskId"` 键保留（值=subjectId，今天相等，前端兼容）；`SubjectBoundInteractionService` 静态代理在键缺失时自动补填，替代各处手动组装。

#### 7.20.7 工作流复用终态

未来工作流层 `WorkflowRuntime implements ExecContext`（subjectId=workflowId、dataDir=工作流数据目录、emitter=工作流事件口、agentFactory=WorkflowBoundAgentFactory）——同一条授权链、同一批 advisor、同一工具链、同一 subagent/审议能力**零改动复用**（§7.14.2 分层：工作区 → task 层/工作流层平级编排 → agent 层 → 基础设施层，下层不知道上层）；事件管道（§14.0）与执行上下文管道双通道同构。

---

## 8. 前端接入层(every-agent-web)

协议语言中立,任何 WS 客户端(移动端、桌面、另一个 agent)都按第 5 章自行实现。仓内交付 React 前端,`src/sdk/` 内置 TS 客户端 SDK(原独立模块已并入)。

### 8.1 SDK 面

- **HubClient** — connect / hello / sub / pub / 自动重连(重连后自动重订阅 desiredSubs + 广播 onReconnect 供上层重拉校准);应用层心跳——5s 周期仅空闲时(本周期无任何帧到达)发 ping 探测,ping 后 15s 无帧判死,判死后零退避首试重连(§5.1);`rpc(workerId, method, params)` 按 reqId 匹配 ok/err/data/progress,默认 30s 超时。
- **订阅次序约束** — 对任一 worker:**先 sub 其 `evt` 频道,再发 `cmd`**(rpc 应答全部落在 evt 频道)。
- **TaskPacketView** — 数据包模式:打开任务 = 先 sub stream 频道(worker 据 join 建推送器收到实时增量)→ `task.rounds` + `task.roundTail` + `task.agents`(子 agent 台账,在 task.rounds 之后调用)拉初始 → 之后仅靠定向推送收流式(帧与拉取帧同一路 seq 去重/排序聚合);帧消费后回 `stream.ack` 释放背压窗口(§7.13);上滚 `loadBefore(beforeSeq)` 拉更早轮次;`resync()` = 重订阅 + 重拉。`task.agents` 应答灌入 eventFolder 新状态 `agentMeta`(键:主 agent=''、子 agent=子 id;流事件 usage/agent.started/agent.done 实时覆盖合并);AgentListPanel 胶囊列表数据源从「items 派生」扩展为 **items ∪ agentMeta**,悬停胶囊显示信息卡(标题/状态/创建时间/模型/累计 tokens/上下文用量),子 agent 胶囊底部边框内一条 2px 用量线(比例 = 最近一轮 prompt/上下文窗口,父级 overflow:hidden 裁剪不越圆角)。
- **channels / ownerKey** — 频道名构造与 sha256 身份,与 Java 契约逐字对齐。

### 8.2 多 worker 聚合

一个 hub 下可有多台 worker。前端以 1 条目录连接(hubKey)看全部在线 worker(presence),对每台已启用的 worker 用其 apiKey 建数据连接;**worker 离线时连接保持建立**(WS 本身健康,仅 presence 变化;显示态 = `presence && wsOpen` 双条件,不靠断连表达离线),RPC 向离线 worker 排队直到超时拒绝(holdTimer 兜底),仅致命错误(鉴权失败等,凭证不修正重试永远失败)才替换连接实例,任务列表/工作区/git 按 worker 合并展示、按归属定向操作;任务归属 worker 由前端按帧来源动态标注(TaskSummary 后端不含 workerId)。凭证 AES-GCM 加密存 localStorage,presence 指纹(ownerFingerprint 前 16 hex)支持 worker 改名后自动复用凭证。

### 8.3 轮次浏览与懒加载

对话按"轮次"折叠展示(每轮 = 用户消息 → AI 最终回复);过程内容(delta/thinking/工具调用)默认折叠为可展开标记,展开时经 `task.poll` 按 seq 区间懒加载(滚动到 0 高度占位元素进入可视区才续拉 `LazyLoadSentinel`)。渲染层任务线程按 **seq 键控增量 upsert**(多子 agent 并发/重连回放不丢事件;大整数 seq 用 BigInt 精确比较);`userControll` 自动滚动(默认 false = 收到数据贴底,用户手动滚动置 true,滚回底部复位)。

### 8.4 通知

浏览器(Web Notification)与桌面(Electron 系统通知)经统一通知适配器抽象(依赖注入 + 适配器注册表,web 模块零 electron 依赖)。触发场景:授权请求、任务完成/错误、ask_user 提问;不在前台才弹;桌面同 tag 2s 去重,点击回带到前台。

### 8.5 插件系统（统一加载架构）

插件系统采用**多扫描器 + 统一加载**架构,内置与外部插件经同一链路发现、加载、激活。

- **后端扫描器** — `PluginScanner` 接口(`List<ScannedPlugin> scan()`;`ScannedPlugin = record(Path pluginDir, String source)`)有两个实现,由 `PluginLoader` 注入 `List<PluginScanner>` 统一遍历,按 source 排序(builtin 优先)逐个 `loadPlugin`(建 `URLClassLoader` → 解析 `plugin.json` → `activate`):
  - `BuiltInPluginScanner`(source=`"builtin"`)扫描 `every-agent-plugins/<id>/` 目录;Java 插件从 `target/` 找 jar + `target/classes/plugin.json` 定位产物;纯 web 插件从插件根目录读 `plugin.json`。
  - `ExternalPluginScanner`(source=`"external"`)扫描 `~/.everyagent/plugins/<id>/`,逻辑与内置一致但目录不同。
- **禁用插件 = 核心不调它的 `activate`**（`PluginLoader.loadPlugin`）——插件的一切贡献（advisor/tool/interceptor/授权链节点/slash 候选/token 解析器/RPC 方法）都只在 `activate(ctx)` 里注册，所以禁用的唯一正确落点就是加载器：命中禁用名单即跳过激活，插件压根没机会注册任何东西。**禁止**让各 SPI 注册表自己拿禁用名单做二次过滤（那会让每个注册表都长出「禁用」这个它不该知道的概念，且必然漏掉某个注册表——`/` 菜单里残留已禁用插件的候选就是这么来的）。禁用名单真相源是 `PluginStateStore`（只依赖 `WorkerProperties`，构造时读盘、变更即落盘 `~/.everyagent/plugins/.disabled-plugins`，每行一个 id）；`PluginRegistry` 退为「目录聚合 + 开关门面」委托它，注册表注入它不成环（`SlashCommandRegistry → PluginRegistry → PluginLoader → SlashCommandRegistry` 才是禁区）。被禁用插件**仍登记进已加载清单**（`active=false`、`status="已禁用(未激活)"`），否则扩展管理面板看不见它、也就无法再启用。插件系统没有 `deactivate` 钩子（`EveryAgentPlugin.deactivate()` 只是可选空实现、核心从不调用），故名单变更对**下一次 worker 启动**完全生效，`plugin.enable/disable` 的应答文案须如实带上「重启 worker 后生效」（与 install/uninstall 同语义）。
- **LoadedPlugin** record 含 `source`/`main`/`webMain` 字段;`PluginRegistry` 不再扫 `classpath*:plugin.json`,改为从 `PluginLoader.getLoadedPlugins()` 统一聚合(`@DependsOn("pluginLoader")`);`PluginManifest` record 新增 `pluginDir` 字段供 `webSource` RPC 统一获取插件目录(不再区分内外)。
- **前端加载统一** — 所有插件(builtin/external)经 `plugin.webSource` RPC 获取 JS 源码 → `rewriteBareImports`(bare import 改写为 `window.__EA_REACT__` 等全局变量引用)→ blob URL → `import()` 动态加载 → `PluginModule.activate(ctx)`。不再有 `import.meta.glob` / Vite glob 映射 / `builtInPlugins.ts` / `internalLoaders`。内置插件需先用 esbuild 预编译(`npm run build:plugins` → `every-agent-web/scripts/build-plugins.mjs`),每个插件 `web/index.ts` → `web/index.js`(ESM,external: react/react-dom/antd/@ant-design/icons,带 sourcemap;产物被 `.gitignore` 排除)。
- **公共 API 边界（VSCode 模式）** — 插件只引用 `@everyagent/plugin-api`(纯类型包)+ 运行时 `ctx`(`PluginContext`),**禁止 `@/` 引用宿主 web 模块**;UI 组件(SVG/antd)一律插件自实现。`ctx` 字段:`ui`(`UiRegistry`:`openPluginTab`/`openFileTab`/`openDiffTab`/`appendComposerText`)、`sdk`(`PluginSdk` rpc + `workspace.list()`/`workerIdOfRoot()`)、`events`(`PluginEvents` 事件总线,`PluginDomainEvent`)、`fs`(`PluginFs` 文件系统)、`storage`、`commands`。
- **扩展点贡献变更须可订阅(否则插件 UI 入口不上屏)** — 插件在 `activate()` 里经 `ctx.ui.register*` 注册贡献,而 `activate` 由 `plugin.webSource` RPC 异步驱动,几乎必然晚于宿主首屏渲染。因此扩展点注册表(`ExtensionRegistry`)除 `register`/`getAll` 外必须提供 `subscribe(listener)`(注册与 dispose 均通知),`PluginDispatcher` 汇总为全局 `subscribeExtensionsChanged` + `getExtensionsVersion`(自增计数,作稳定快照;返回新数组会让 `useSyncExternalStore` 判为快照不一致而无限重渲染)。宿主侧边栏(活动栏图标/面板列表/选中态/合法面板 ID 校验)以 `useSyncExternalStore` 消费该版本,使 git/扩展管理等插件图标注册即显示——不在 React 里订阅而只在渲染期读 `listRegistered*()` 快照,图标会一直缺失,直到别处 `setState` 触发重渲染才"顺带"出现。
- **侧边栏入口排序 `order`(float,统一坐标系)** — `ui.sidebar_items` 的 `UiSidebarItemDefinition.order` 是活动栏唯一的排序依据:内置项与插件贡献合并后按 `order` **升序混排**(float,同值按贡献先后稳定排列),不再隐含"内置在前、插件在后"的注册顺序假设。内置项占 `tasks=1 / files=2 / search=3 / settings=10`,中间空位留给插件插队(git=5、扩展管理=9);未声明 `order` 的贡献按 `DEFAULT_SIDEBAR_ORDER=100` 兜底,即排在所有已声明项之后。排序发生在 `Layout.tsx` 的 `buildSidebarActivityItems()`(活动栏图标与移动端底部栏共用同一条目序列),面板槽位显隐不受顺序影响,故无需同步排序。
- **扩展点 `ui.tool_call_views`** — 按工具名**整体接管工具调用视图**(折叠态 + 展开态):插件注册 `ToolCallViewDefinition{pluginId, toolName, Component}`,`Component` 与核心内置视图同契约(`ToolViewProps`,聚合后的 `details` 数组)。解析优先级:插件注册的视图 > 内置 `toolViews/` 目录注册表 > `DefaultToolView`。插件视图完全自治(折叠行、展开头部、参数/结果/错误块均由插件渲染),但只能用 plugin-api 类型 + ctx 能力,不引宿主组件。内置 `update-file-view` 插件以此接管 `update_file`:折叠态显示文件名与变更统计徽章,展开态内嵌 oldcontent→content 行级 diff。

---

## 9. 桌面版(every-agent-desktop)

Electron 将 web + hub + worker **一体打包**为 Windows x64 便携(portable)与安装包(NSIS):

- **进程模型**:主进程 spawn 本地 hub 与 worker 两个 Spring Boot 子进程(`javaw.exe`,jlink 精简 JRE 随包);前端经本地静态服务加载(127.0.0.1 随机端口,保证 localhost 安全上下文),preload 以 contextBridge 注入开箱即用连接配置。**hub 始终跟随 desktop 启停**(desktop 独占管理,退出时一并停止);**worker 支持外部进程复用**——启动前调 `GET /admin/identify`(认证探测,携带 `X-Admin-Key` = workerApiKey)判断 worker(:6102)是否已在运行,已在运行则跳过启动直接复用;端口被别的程序占用则报错。
- **独立 worker 启动**:`resources/start-backend.bat` 可脱离 Desktop GUI 独立启动 worker(hub 仍由 desktop 管理,不在此启动);适配 Windows 任务计划程序"系统启动时"触发器(Session 0 无 GUI 场景);worker 启动后自动重试连接 hub,desktop 后续打开时自动检测到已有 worker,不重复启动。
- **配置注入**:生成 hub/worker yaml 经 `--spring.config.additional-location` 覆盖 jar 内默认(整表覆盖 `worker.hubs`,避免误连远端);数据目录复用 `EVERYAGENT_HOME`(缺省 `~/.everyagent`),与命令行/docker 共用同一批任务/工作区/模型。
- **运行时配置**:每次启动读 `<EVERYAGENT_HOME>/desktop-config.json`(hubKey/workerApiKey/workerId/端口),首次生成;日志统一落 `<EVERYAGENT_HOME>/logs/`。
- **管理端点(仅 worker)**:worker 提供 `GET /admin/identify`(认证后返回 worker 身份)与 `POST /admin/shutdown`(认证后触发 Spring 优雅关闭);认证用 `X-Admin-Key` 请求头(与 hubs[0].apiKey 明文比对);仅监听 127.0.0.1,POST + 自定义头防 CSRF。hub 无 admin 端点(hub 可能公网部署,暴露 shutdown 接口会被持有 hubKey 的人关掉)。
- **生命周期**:单实例锁、占位页/错误页(含日志目录)、托盘提供「退出桌面」(停 hub,worker 保留运行,下次启动自动复用)与「全部退出」(停 hub + 对所有 worker 发 `POST /admin/shutdown` 优雅关闭);`before-quit` 按 `quitScope` 决定停 hub 或停全部。
- **窗口不可见不后台化**:主窗口 `backgroundThrottling: false` + 启动开关 `--disable-backgrounding-occluded-windows`——窗口被遮挡/最小化时 Chromium 默认会挂起渲染进程、杀掉 WebSocket,导致每次回到前台必断连重连、弹「正在重新连接」模态框;连接生死唯一由前端应用层心跳判定(§5.1),渲染进程须持续运行(心跳与任务流推送不中断,同时 §4.2.2 的 visibility 降载在桌面端不触发——本地回环,无降载需求)。
- **构建流水线**:`build-backend.mjs`(mvn 打包)、`build-web.mjs`(前端 dist)、`build-jre.ps1`(jlink);electron-builder `extraResources(from: ../runtime → to: runtime)` 把程序附属文件打进安装包。

---

## 10. 关键流程

### 10.1 任务创建与流式输出

```
前端A                     hub                         worker(家中PC)
 │─cmd: rpc{task.run}───→│──转发───────────────────→│ 创建任务,起虚拟线程
 │←─u.K.tasks: task.created{taskId}───────────────────│
 │─sub u.K.task.<id>.stream──────────────────────────→│(hub 定向通知 worker:join)
 │                                                      │ DataPusherManager 建定向推送器
 │─cmd: rpc{task.rounds + task.roundTail + task.agents}→│ 初始渲染(轮次 + 尾段 + 子 agent 台账)
 │←─evt: rpc.data / rpc.ok────────────────────────────│
 │←─msg: stream 频道定向推送(delta/thinking/message)──│ 实时增量(ext.target=本会话)
 │           消费后回 stream.ack 释放背压窗口          │
```

### 10.2 浏览器关闭后重开(G3:拉尾段 + 增量续播)

```
浏览器重开                 hub                        worker(家中PC)
 │─hello(apiKey+hubKey)─→│
 │←─welcome──────────────│
 │─sub u.<hubK>.workers─→│          (知道 worker 在线/离线)
 │─sub u.K.worker.<id>.evt→│        (先订后请求:rpc 应答在 evt 频道)
 │─cmd: rpc{tasks.list}──│──转发──→│ 按命名空间返回全部任务
 │─cmd: rpc{task.poll, mode:'rounds', count:1}──→│ 拉尾段(磁盘∪内存归并)
 │←─evt: rpc.data/ok────────────────│
 │─sub u.K.task.<id>.stream──→│     (重订阅 → hub 再发 join → 新推送器)
 │←─msg: stream 帧(delta/message)──│ 实时增量续播(seq 去重合并,无缝续播)
```

### 10.3 终态任务继续对话(没有"续跑"概念)

```
前端                        hub                         worker
 │─cmd: rpc{task.run, taskId:t_x, input:"追问…"}───→│ 校验后从磁盘认领
 │←─evt: rpc.ok{taskId}───────────────────────────────│ ConversationLoader 重建历史
 │←─u.K.tasks: task.updated{status:running}───────────│ log.seed(seqLastOf) 接续序号
 │←─task.poll: user.message → message → done──────────│ 一次普通运行,目录/createdAt 不变
```

### 10.4 askuser 全流程

```
worker                         hub                    前端(可能 0 个在线)
 │─ask.create{q_x}────────────→│──扇出──────────→ 渲染卡片
 │  任务虚拟线程挂起(零开销)         │
 │─ask.state(每30s)────────────→│                 ← 前端任何时候上线由此看到问题
 │                               │←─ask.reply{q_x,"yes"}─│ 用户作答(worker 级 input 频道)
 │──转发─────────────────────│
 │  complete(q_x) → 工具返回 → 模型继续
 │─ask.resolved{q_x}─────────→│──扇出──────────→ 卡片定格
```

### 10.5 故障与自愈

| 场景 | 行为 |
|---|---|
| 前端断线 | hub cleanup 发 subscriber.leave → 推送器销毁,任务照跑落盘;重连恢复 = welcome 后重发 desiredSubs(含仍打开的 stream 频道,join 重建推送器)+ 重放在途 RPC + 广播 onReconnect——上层只重拉数据校准,不重建 view(HubClient 实例瞬态重连不替换) |
| 单个 hub 宕机/重启 | 其余连接照常收发;受影响前端重连 + reconnect 补齐,零丢失 |
| 全部 hub 宕机 | 任务继续跑完并落盘(输出无人消费,天然背压);恢复后重订阅 + 从磁盘拉取补齐 |
| worker 断线(到 hub) | 指数退避重连;期间 hub 发 worker.offline,前端显示离线;恢复后 worker.online 触发 reconnect 重拉校准 |
| 家中 PC 关机/worker 崩溃 | 运行中任务终止;**磁盘数据完整**:开机重启后索引重建、任务列表回归、非终态标 failed;发消息继续对话(冷启动) |
| 慢消费者 | hub 出口队列(1000)溢出断开该前端;前端重连 + reconnect(三道防线,§7.13);worker 出站队列满丢帧 + WARN(事件日志为事实源) |

---

## 11. 工程结构与构建部署

### 11.1 目录

```
.                                  # 仓库根 = every-agent 项目根
├── every-agent-hub/               # 消息中心(Spring Boot WebFlux,6101)
├── every-agent-worker/            # 执行器(Spring Boot + Spring AI 2,6102 仅本地健康)
│   └─ src/main/java/.../proto/    # 业务常量住 worker:事件名 / DTO / RPC 方法名 / 频道构造 / 短 ID
├── every-agent-web/               # React 前端(内置 TS 客户端 SDK src/sdk/)
├── every-agent-contract/          # 纯协议契约:帧信封 / RPC 信封 / 错误码 / 身份哈希(Java DTO + TS 类型)
├── every-agent-plugin-api/        # 插件 API 契约:ExecContext(统一执行上下文) / EventEmitter / EmitEvent / ChatModelEnhancer / ModelConfig / TaskLifecycleNode 等接口(纯类型,插件与 worker 共用)
├── every-agent-plugins/           # 内置插件:model-rate-limit(限流) / task-queue(队列) / subagent / git / ai-review / empty-response-retry(空响应重试) / transient-error-retry(瞬时错误重试) / context-compression(上下文压缩) / ...
├── every-agent-desktop/           # Electron 桌面打包
├── runtime/                       # 程序附属文件(rg 二进制、eagent-run.py、WSL 托管镜像)
└── docs/ARCHITECTURE.md           # 本文档(唯一架构事实源)
```

三层只依赖 contract,互相零依赖;contract 是纯协议边界,业务全部住 worker proto。

### 11.2 构建 / 测试 / 运行

```bash
# Java 部分(JDK 25;Spring Boot 4.1.x / Spring AI 2.0.x 由根 pom 锁定)
mvn -pl every-agent-hub spring-boot:run          # hub @ 6101
mvn -pl every-agent-worker spring-boot:run       # worker,出站连 hub

# 前端
cd every-agent-web && npm install && npm run dev

# 测试
mvn test                        # contract + hub + worker(worker 含真实 hub 全链路 E2E)
cd every-agent-web && npm run typecheck
```

docker-compose 一键:`HUB_KEY=你的密钥 docker-compose up --build`;数据落在 named volume。

### 11.3 部署形态

- **dev**:docker-compose,或本地 mvn ×2 + npm。
- **prod**:hub 与前端静态站部署公网服务器(LB 的 WS 空闲超时 ≥ 60s);worker 在个人 PC 以出站 wss 连入(docker 或系统服务),通过 `worker.hubs` 配置(`worker.hubs[].url` 指向公网 hub,每项 `url + api-key + hub-key`);6102 管理端口仅绑定 127.0.0.1。
- **桌面版**:every-agent-desktop 安装包开箱即用,本地 6101/6102,与命令行/docker 共用 `EVERYAGENT_HOME` 数据。

---

## 12. 已确认的设计决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | hub 用 Spring WebFlux | 栈统一,Reactor 扇出是强项 |
| D2 | hub 零状态零缓冲零 ack 零业务理解 | 消息完整性与业务规则全部收敛到 worker 一处 |
| D3 | worker 用 JDK 内置 HttpClient WS | 零额外依赖,出站连接穿透 NAT |
| D4 | 前端 React + TS(仓内 every-agent-web,内置 TS 客户端 SDK) | 协议语言中立不锁死 |
| D5 | worker 复用 owner apiKey,无 agentToken | 命名空间已含鉴权,少一个凭证 |
| D6 | seq 由 worker 在写日志时分配 | 实时/历史同源,前端去重排序有统一根基 |
| D7 | 重连/重开经 task.poll 拉取(可全量);localStorage 仅加速渲染 | G3:重连后必见整个 task |
| D8 | **任务永久保留,日志永不修剪;唯一删除 = task.delete** | 个人任务量级磁盘可承受;磁盘是唯一事实源 |
| D9 | 持久化必选内置、fire-and-forget | 主循环绝不阻塞于落盘 |
| D10 | 一切数据交换走 hub 单隧道,三动词 RPC/Task/Event | NAT 拓扑下无第二条路;hub 零理解,新功能零改协议 |
| D11 | fs/git 一律沙箱 jailed 到 workspace 根 | apiKey 即全权,至少圈住文件系统边界 |
| D12 | 前端仓内交付(web 内置 TS 客户端 SDK) | 视觉基准统一,数据流走 hub |
| D13 | 子 Agent 用进程内工具方案:run/list/wait/stop + 同名事件带 agentId 嵌套 | 同模型同沙箱零协调;跨机分工留给 v2 |
| D14 | 信封预留:must-ignore 总则 + 握手级 ver + 帧级开放 ext 映射 | 前向兼容靠 must-ignore,生态收敛靠 ext |
| D15 | 系统目录 `~/.everyagent` 与工作区分离;模型配置由 Spring 配置承载 | 模型配置是机器级系统功能,apiKey 不落入 agent 沙箱 |
| D16 | 多工作区并行:注册表 + 按调用必带 workspace 参数 | 并行项目互不干扰;按调用绑定根让沙箱与前端分组对齐 |
| D17 | hub 不做角色×频道 ACL 矩阵,只留连接级命名空间校验;同命名空间互信 | hub 是 RPC 转发中心,业务规则住 worker;个人部署可接受 |
| D18 | 运行即销毁 + 冷启动:终态驱逐内存驻留,再运行从磁盘载入 | 内存不随历史任务数增长;磁盘唯一真相源 |
| D19 | worker 多 hub 注册(HubPool):事件按连接扇出、RPC 回源 | 同 key 多 hub 冗余 / 多命名空间共用一台 worker;hub 零改动 |
| D20 | **任务数据按工作区归类 `workspaces/<workspaceId>/tasks/<taskId>/`(不做 owner 隔离)** | 单人部署简化;数据边界靠命名空间 + 文件沙箱;删除工作区即随删该区任务数据 |
| D21 | 事件分类:瞬态(delta/thinking)只发前端消耗 seq;持久(message 等)落盘回放 | 流式体验与权威记录分层 |
| D22 | 按 agent 分文件 `<agentId>.jsonl`,行内恒记 agentId | agentId 即 conversationId;冷启动与回放归并单位 |
| D23 | 输入走 worker 级频道 `u.K.worker.<id>.input` | 订阅数 O(worker×hub) 不随任务数增长 |
| D24 | 短 ID:`{前缀}_{3位盐}{base36 序号}`(t_/a_/sub_/q_) | 人可读可念;单 worker 查重兜底 |
| D25 | contract 只承载纯协议,业务常量住 worker proto | workflow 演进零改 contract、零改 hub |
| D26 | 命令沙箱多后端:Windows 默认 wsl-direct、wsl-bwrap 显式、windows-mic 回退 | 「零管理员 + 网络硬隔离 + 零宿主残留」在原生 Windows 不可兼得;WSL2 生态已验证 |
| D27 | 授权语义(seccomp 场景)= WSL 原生 root 重跑 | NNP + userns 不映射 uid0 + 基座只读 → 沙箱内真实提权物理不可行 |
| D28 | AI 审议与无人值守为独立任务级开关,开启时联动、事后可拆分 | 分别满足"无人监督但有把关"与"全流程无人值守"两种需求 |
| D29 | **统一执行上下文 ExecContext**:黑盒 properties 四件套(taskEntry/taskId/workspaceRoot/configId)显式类型化为 plugin-api 接口槽位(仅主体必然具备的核心属性与端口,含 agents 活动实体注册表=主+各插件派生 agent;configId 无独立槽);三预绑定端口 `emitter()/agentFactory()/interaction()`(静态代理)经 ctx 下传;`TaskInfo` 退役;授权请求收编为 `AuthorizationRequest(ExecContext,agentId,grantKey,prompt)`;fileChanges 等插件功能槽位留 TaskRuntime;subagent 作为执行域能力插件全面中性化(零服务依赖/生命周期壳核分离;**台账后续收编 worker core `AgentLedger`——原 `SubAgentLedger` IO 自持已退役,`AgentBuilder.build()` 自动注册+发 `agent.started`,见 §7.20.1**);task 核心去插件概念(截断不删插件数据文件);审议 agent per-task 固定 id 复用会话;各横切 Context 接口(ToolContext/AdvisorContext/FileReferenceContext/ToolExecutionContext/TaskLifecycleContext)收编为 `extends ExecContext`(重复字段与 execution() 槽位删除,消费侧直读槽位、主体 ID 一律 subjectId(),§7.20.5) | 授权链与全部横切层(advisor/工具/子 agent/审议)域中性,subagent 无 task 只有 workflow 亦可复用;未来工作流实现 ExecContext 即零改动复用;20+ 处强转消失;详见 docs/design-exec-context.md 与 §7.20 |

---

## 13. v2 预留(明确不在 v1 做)

运行中任务的崩溃恢复 · apiKey 签发/吊销 · 多 hub 实例集群(Redis pub/sub 桥 + 亲和)· 前端间自定义频道协作 · 任务归属转移 · tasks.list 搜索 · 大文件上传二进制分帧/压缩 · **worker 编排(调度)**:一台"调度 worker"向其他 worker 的 cmd 频道发命令建任务收结果(命名空间天然放行,零协议改动;v1 中 worker 之间互不知晓)· 子 Agent 跨机分工(dispatch_agent 走 cmd 频道)。

---

## 14. 实现约束(开发者红线)

本章是给实现者的红线清单:以下行为已定死,不按个人偏好变更。与其余章节冲突时,先改文档再改代码。

0. **事件管道分层(类 OSI 七层)**:事件从产生到前端展示,经多层逐层包装/解包,每层只负责自己层的数据,不感知上下层语义:
   - **插件/底层(最内层)**:发语义事件(`EventEmitter.emit(EmitEvent.TraceData.transientOf("model_rate_wait", ...))`),只知道事件名 + payload + persist 标志,不知道 agentId/taskId/seq/wire 格式/前端展示方式。
   - **agent 层**:填 agentId(包装 payload 或附加参数),不知道 taskId/seq/wire 格式。
   - **task 层**(或以后的工作流层):语义事件→wire 事件映射(如 `model_rate_wait` → `task.trace` + kind/tile/summary/status/createdAt;`delta` → `delta`;`error` → `error`)、分配 seq、按 persist 落盘 jsonl。
   - **推送层(WebSocketEmitter)**:背压 + 定向推送到前端 websocket,不感知事件语义。
   - **前端(最外层)**:按 wire 事件名 + payload 字段映射为 trace/消息/状态等展示组件。
   - **改造纪律**:后续把 `AgentEventChannel` 的 30 个具体方法逐步迁移到 `EventEmitter.emit(EmitEvent)` 时,每迁一个方法:插件/底层只发强类型 `EmitEvent` 载荷,task 层用 `instanceof` 判断类型做映射,推送层和前端不变。以后工作流层实现自己的映射(task.trace → wf.trace,自己的包装格式),自己的推送管道,前端按工作流 wire 格式解析。**插件永远不感知 task 层语义(wire 事件名、traceId、seq 等)。**
1. **编码、时间与 ID**:帧为 UTF-8 JSON;ts 一律 epoch 毫秒(UTC);短 ID 规则 `{前缀}_{3位盐}{base36 序号}`,全局唯一从不复用;ownerKey = sha256(apiKey) 64 位小写 hex。
2. **频道与信封(hub 红线)**:频道名字符集 `[a-z0-9._-]` 长度 ≤160,必须以 `u.<ownerKey>.` 开头;hub 只解析 `type`/`channel`(及 hello 握手字段),`event`/`seq`/`payload`/`ext` 原样转发;不存在角色×频道权限矩阵;seq 只属于任务流事件空间,由 task.poll/stream 携带;error 分级(断开 vs 拒单帧);连接抢占(worker 同 clientId 新连关旧连)。
3. **RPC 生命周期**:reqId 连接内唯一,ok/err 已出则后续同 reqId 帧忽略;未知 method → UNKNOWN_METHOD;参数不合法 → BAD_PARAMS;超时是纯客户端语义(SDK 默认 30s),要中断须显式 rpc.cancel;task.run 新建支持 idempotencyKey(10 分钟窗口去重);task.delete 是任务唯一删除路径,无任何自动清理。
4. **错误码两个命名空间,勿混用**:hub `error` = NOT_AUTHENTICATED/VERSION_MISMATCH(断开)、ACL_DENIED/FRAME_TOO_LARGE/RATE_LIMITED(单帧拒绝);`rpc.err` = UNKNOWN_METHOD/BAD_PARAMS/NOT_FOUND/SANDBOX_DENIED/BUSY/INTERNAL/AUTH_REQUIRED。
5. **并发与上限**:maxConcurrentTasks(20)超限 task.run 新建 → BUSY(不排队);maxConcurrentSubs 超限 run_agent 返回错误文本由模型自决;maxEventsPerTask(50 万)超限抛 LogOverflow(磁盘 jsonl 全量不受影响);续跑放行不查并发上限。队列插件启用时超限任务排队等待（QueueAdmissionNode order=250, Semaphore fair）而非 BUSY 拒绝；无队列插件时保持 ERR_BUSY 硬拒绝。
6. **沙箱(插件化)**:SandboxBackend SPI 极简化为 mount + onWorkspaceRemoved + id;路径翻译由核心 SandboxPathRegistry 中间人承担;沙箱插件提供自己的 CommandExecutor 和 ToolProvider;PermissionGate 不暴露到 plugin-api(核心内部保留);路径必须先规范化(realpath)再校验 workspace 根前缀,拒绝 `..`、绝对路径逃逸与符号链接逃逸;授权护的是「工作区外」,不是删除动作本身;不得绕过 PermissionGate 直接放行越界 IO;windows-mic 后端沙箱进程运行在 Medium IL,不对文件系统做标注或 ACL 修改;git 凭证只存 worker 本机加密文件,不经协议传输,注入走 env(askpass) 不经 shell 参数;
7. **生命周期**:终态任务收到 task.run{taskId} = 冷启动一次普通运行;worker 优雅停机(SIGTERM)受影响任务标 failed 再关连接;6102 仅绑定 127.0.0.1;worker 每条 hub 连接建立即 sub 该命名空间 cmd + input 两个频道,从不订阅 per-task 频道。
8. **复用 Spring AI,禁止重复造轮子**:agent 执行必须走 ChatClient + Advisor 生态,不得手搓 agent 循环、工具循环、响应聚合、system 拼接;执行链只能是很薄一层;新增 agent 能力优先做成 Advisor;一个 Advisor 只负责一个功能;事件发射等需挂钩工具循环的增强通过继承 ToolCallingAdvisor 并重写受保护 hook 实现;主/子 agent 共用同一运行入口与 Advisor 链,仅 agentId 不同。
9. **插件零 worker 依赖**:插件的 pom 中不得出现对 `every-agent-worker` 的依赖,compile/provided/runtime/test 任何 scope 一律禁止;插件测试需要任务/agent/配置等桩时,在测试源码内自建实现 plugin-api 接口的等价桩类,不得把 worker 具体实现类(TaskEntry/AgentEntity/WorkerProperties/SlashCommandRegistry 等)当测试脚手架;类型确实需要跨 worker 与插件共享时,先下沉到 plugin-api(§1.1,文档先行)。
10. **文档**:本文档是唯一架构事实源;根目录 AGENTS.md 只写核心约束(每会话加载,保持精简),细节一律进 docs/。
11. **执行上下文管道(ExecContext,与 §14.0 事件管道同构;展开说明见 §7.20)**：执行数据沿 `task 层 → agent 层 → 工具执行链 → 授权链` 逐层传递,每层只消费自己层级的槽位,下层不知道上层是谁(task 还是未来 workflow):
    - **槽位判据**:ExecContext 槽位 = 任何执行主体都必然具备的核心属性与端口(subjectId/workspaceRoot/workspaceId/snapshot/emitter/agentFactory/interaction/metadata/dataDir/terminal/agents 活动实体注册表;configId 不设独立槽,经 snapshot().configId() 取);**插件功能与主体特有槽位不进核心接口**——file-change 插件的 collector 连 `TaskRuntime` 都不进(留 advisor per-run 实例字段 + `RoundClosedListener` 落盘 + 插件自注册 RPC 读,§7.15.2),metadata 只承载随 meta.json 落盘的持久策略标记(不混入运行时瞬态数据);agents() 收纳主体上下文内全部 agent(主 agent + 各插件派生:子 agent、审议 agent),由 `AgentBuilder.build()` 自动注册(只 put,**不发事件**),插件不再手动 put/emit;`AgentContext.creator()` 槽位(task/subagent/ai-review 来源标记,**顶级字段**,非 ExecContext 槽位)随 `agent.started` 持久化进台账顶级 creator,消费方按 creator 决定展示范围;`agent.*` 生命周期事件的发射单点是 `AgentEntity`(per-run 状态机),触发源 = `AgentStatusAdvisor` 流生命周期 + 交互层 ask 生命周期(§7.20.1);无子 agent 是正常形态。
    - **task 层构造并预绑定**:`TaskEntry implements TaskRuntime extends ExecContext`(subjectId=taskId);三预绑定端口同范式(静态代理,worker 内部实现,可链式套娃)——`emitter()`(主体事件口)、`agentFactory()`(主体 agent 装配,`create(agentId)` 单参)、`interaction()`(主体交互口,ask 的 context map 自动填 subjectId)——**上层预绑定能力往下传,不传裸工厂/裸服务、不传任务域对象**。
    - **agent 层/工具链唯一取数口**:`AgentContext.execution()`;`ToolContext`/`ToolExecutionContext`/`AdvisorContext`/`FileReferenceContext`/`TaskLifecycleContext` 一律 `extends ExecContext`(重复字段与 `execution()` 槽位已删,消费侧直读槽位、横切层取主体 ID 一律 `subjectId()`,§7.20.5;`InterceptingToolCallingManager` per-run 构造参数直持 ExecContext,不依赖 ThreadLocal);`properties` 黑盒 map 与 `get("taskEntry")` 强转**禁止再现**。
    - **advisor 只取槽位**:`snapshot()`(模型配置,configId 经 snapshot().configId())、`subjectId()`(审计)、`emitter()`(事件)、`terminal()`(leak-guard)——不 import 任务域类型;**agent.生命周期事件发射单点(§7.20.1)**:`agent.started`/`agent.status`/`agent.done`/agent 级 `error` 一律由 `AgentEntity`(per-run 状态机 + CAS)发射,触发源是 `AgentStatusAdvisor` 的流生命周期信号与交互层的 ask 生命周期;task 层(StatusNode/TaskLifecycleContext.agentStatus)与插件(SubAgentManager/AiAuthReviewer)不得再手搓这四个事件的 EmitEvent——插件唯一允许的调用是兜底 `Agent.claimTerminal(...)`,CAS 保证与 advisor 终态互斥;**subagent 是执行域能力插件(非任务域),全面中性化**:SubAgentManager 零服务依赖(方法收 ExecContext、监视器内部化、`agents()` 槽位替代 task.agents)、创建子 agent 时设 `creator("subagent")` 交由 `AgentBuilder.build()` 自动注册(插件不再手动 put)、agent.* 生命周期事件由 advisor 链统一发射(插件不再 emit、只在运行体从未启动的兜底路径调 `claimTerminal`)、台账 agents.json 由 worker core `AgentLedger` 收编(`SubAgentLedger` 退役;事件投影+原子读写+冷启动恢复,§7.20.1)、生命周期节点壳留 task 面/逻辑取 ExecContext 槽位、`task.agents` RPC 壳留 task 面(mainAgentId 是任务概念,读侧按 creator 过滤);**task 核心不依赖插件**:编辑重发截断只动 *.jsonl/rounds.jsonl 不删插件数据文件(agents.json/file-changes 残留被接受,事件通知自清为开放项),`task.agents` 方法名常量住插件侧;AI 审议 agent 以 per-task 固定 agentId(creator=ai-review)注册进 agents() 跨请求复用会话(既往授权决策留在审议员上下文),每轮审议同样走 advisor 链的 per-run 出生/终态事件;任务域插件(file-change/edit-resend/git)经 `TaskService`/`taskRuntime()` 取 `TaskRuntime` 是合法本职依赖。
    - **授权请求域中性**:`AuthorizationRequest(ExecContext, agentId, grantKey, prompt)`;GrantRegistry 按 `subjectId()` 分区、`dataDir()` 落盘;`InteractionService` ask 的 context map `"taskId"` 键保留(值=subjectId,前端兼容)。
    - **禁止**:横切层 import `TaskEntry/TaskRuntime/TaskInfo`;绕过 `ctx.agentFactory()` 手工组装四件套 map;绕过 `ctx.interaction()` 手动填 taskId context;worker 侧新增 properties 透传通道。工作流层实现 `WorkflowRuntime implements ExecContext` 后,同一条授权链、同一批 advisor、同一工具链零改动复用。