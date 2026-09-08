# Every Agent — 架构说明

> **哪里都可以使用的 Agent**:worker 执行器运行在本地局域网的个人电脑上,任务里的 agent 在其中运行,进度从任何地方查看。
> 本文档是项目的**唯一架构事实源**。实现与本文冲突时,以本文为准(或先修订本文)。
> 面向读者:项目贡献者、希望二次开发或自部署的开发者、以及想理解其设计思路的评估者。协议层是语言中立的,任何 WS 客户端都可按第 5 章自行实现。

---

## 1. 项目概览

Every Agent 是一套「**公网可及、本机执行**」的 AI Agent 系统:AI 任务(agent 循环)跑在你自己电脑上的执行器(worker)里,而你可以从任何地方的浏览器通过一个公网消息中心(hub)查看进度、发指令、处理需要确认的问题。不需要公网 IP、不需要端口映射、不需要把代码和数据上传到第三方服务器。

系统由四个可独立部署的模块 + 一个桌面打包组成:

| 模块 | 职责 | 端口 |
|---|---|---|
| `every-agent-hub` | 公网消息中心:纯中转 WebSocket (WebFlux/Reactor),零状态、零缓冲、零业务逻辑 | 9100 |
| `every-agent-worker` | 执行器:Spring Boot + Spring AI 2,托管任务运行时、模型调用、workspace、沙箱进程 | 9200(仅本地健康检查) |
| `every-agent-web` | 前端:React + TS,内置 TS 客户端 SDK,经 hub 遥控 worker | 5174(dev) |
| `every-agent-contract` | 纯协议契约:帧信封 / RPC 信封 / 通用错误码 / 身份哈希(Java DTO + TS 类型) | — |
| `every-agent-desktop` | Electron 桌面版:web + hub + worker 一体打包(Windows x64 便携/安装包) | 本地 9100/9200 |

### 1.1 设计理念

1. **三层完全解耦**：hub / worker / 前端是独立程序、独立部署、独立演进,互相只认消息协议,不认实现;三者只依赖 `every-agent-contract`,互相零依赖。
2. **hub 不理解业务(红线)**：hub 是 RPC 转发中心——不校验事件语义、不认识"任务"、不维护业务规则。它唯一保留的校验是**连接级命名空间鉴权**(频道前缀是否匹配连接身份),这是身份边界,不是业务理解。
3. **磁盘是唯一事实源**：任务数据(事件日志 + 元数据)永久落盘;worker 内存只是运行期驻留,任务结束即销毁。重启、崩溃、换机后一切从磁盘重建。
4. **任务永久保留**：无自动清理;用户主动删除是唯一移除路径。
5. **运行即销毁、冷启动**：agent 运行结束即释放内存;再次运行是从磁盘载入历史后的一次**普通运行**,没有"续跑"特殊状态。
6. **worker 输出不受下游影响**：前端离线、hub 宕机(全部宕机也不例外)、落盘慢,都不阻塞任务主循环。
7. **阻塞等待零成本**：Java 25 虚拟线程——"必须等用户输入"的操作以挂起虚拟线程实现,不占资源。
8. **无账户模型**：持有密钥即身份;数据按命名空间隔离;无用户表、无注册。
9. **单隧道 + 三条动词**：公网到局域网 worker 只有 hub 这一条隧道,一切数据交换走它;隧道之上只有 RPC / Task / Event 三条交互动词,新增功能不改协议、不改 hub。

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
| hub | Java 25,Spring Boot WebFlux(Reactor Netty WS) | 9100(/ws + /health) |
| worker | Java 25,Spring Boot + Spring AI 2,JDK 内置 HttpClient WebSocket(多连接 HubPool),虚拟线程 | 9200(仅 127.0.0.1 健康/管理) |
| web | React + TS,内置 TS 客户端 SDK | 5174(dev)/ 静态托管 |
| contract | 纯协议:帧/RPC 信封/错误码/身份哈希(Java DTO + TS 类型) | — |
| desktop | Electron(内置 Node 24),spawn 本地 hub/worker 子进程(jlink 精简 JRE 25) | 本地 9100/9200 |

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
wss://hub:9100/ws
→ { "type":"hello", "ver":2, "role":"frontend"|"worker", "apiKey":"sk-...", "hubKey":"hub-secret", "clientId":"fe-1",
    "meta": { "hostname":"home-pc", "version":"0.1.0" } }        // hubKey 必填;meta 可选,worker 上报
← { "type":"welcome", "ver":2, "sessionId":"s-17", "serverTs":1755859200000 }
```

- `ver` 为协议版本(当前 **2**),握手协商一次;无共同版本 → `VERSION_MISMATCH` 断开。不逐帧携带版本。
- 未 hello 就 pub/sub → `NOT_AUTHENTICATED` 并断开。
- 控制帧全集:`hello` `welcome` `sub` `unsub` `pub` `msg` `error`。

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
| task.poll / stream | `user.message` | ✓ | `{text, rawContent?}` 用户输入入日志 |
| task.poll / stream | `delta` / `thinking` | ✗ 瞬态 | 逐 token / 推理增量(主/子同名,子带 agentId) |
| task.poll / stream | `message` | ✓ | **每轮权威完成记录**:`{thinking, text, toolCalls:[{id,name,arguments}], agentId?}` |
| task.poll / stream | `tool.result` | ✓ | `{callId, name, summary, truncated?}` |
| task.poll / stream | `usage` | ✓ | 每轮模型 token 实测用量 + 累计 |
| task.poll / stream | `ask.create` / `ask.state` / `ask.resolved` | ✓ | 见 §7.8 |
| task.poll / stream | `agent.started` / `agent.done` | ✓ | 子 agent spawn 生命周期(§7.14) |
| task.poll / stream | `agent.status` | ✓ | 主/子统一状态事件:running / waiting-user / done / failed / stopped |
| task.poll / stream | `error` / `cancelled` | ✓ | `{message(带 agentId 即该子 agent 失败)}` / `{by}` |
| task.poll / stream | `task.trace` | ✓/✗ 按 ext | **统一纯显示 trace**(重试生命周期、任务耗时、模型容灾、授权审计等):`{traceId, kind, title, summary?, content?, status?, createdAt, metadata?}`;`ext.persist=false` 标记瞬态实例 |
| task.poll / stream | `round.opened` / `round.closed` | ✗ 瞬态 | 轮次开/闭通知:`{startSeq,user}` / `{startSeq,endSeq,finalReply}` |
| input | `task.input` | — | `{taskId, text, rawContent?}`(worker 级频道;热非终态入队/终态触发一次普通运行) |
| input | `task.dialogInsert` | — | `{taskId, index?, text}` 队列项「插入到当前对话」(§7.16) |
| input | `ask.reply` | — | `{askId, answer}` |
| input | `stream.ack` | — | `{taskId, creditIndex}` 流消费进度回报(§7.13 背压) |

> **线上 wire 形态**:事件名不分主/子 agent(delta/message/error 同名),归属由 `agentId` 决定——主 agent payload 不带 agentId,子 agent 必带;磁盘 jsonl 每行必记 agentId。仅 spawn 生命周期用 `agent.started`/`agent.done` 专用名(只对子 agent 发)。

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
| `task.run` / `task.cancel` / `task.delete` | 运行任务(**创建/续跑合一**):不传 taskId=新建(必带 workspace)并开跑;传 taskId=载入老任务历史续跑(运行中则入队)。delete = 唯一删除路径(运行中拒绝) |
| `task.poll` | 任务流纯拉取:历史(磁盘)∪ 实时(内存尾部)按 seq 归并;支持 afterSeq/beforeSeq/区间/mode('events'/'rounds')/waitMs 长轮询 |
| `task.rounds` | 轮次索引拉取(rounds.jsonl 全部行 + 运行中未闭合轮 open;旧任务首次惰性全量生成落盘) |
| `task.roundTail` | 按轮起点(startSeq)取该轮末尾 limit 条事件,用于初始渲染 |
| `task.fileChanges` | 单轮文件变更全文:`file-changes/<roundId>.json` 的 `{changes:[...]}` |
| `task.queueRemove` / `task.queueMove` | 删除/重排某条队列输入 |
| `config.get` | 模型配置只读(Spring 配置承载,见 §7.17) |
| `workspaces.list` / `workspaces.add` / `workspaces.remove` | 工作区注册表 CRUD(多工作区并行) |
| `fs.list` / `fs.reveal` / `fs.read` / `fs.write` / `fs.mkdir` / `fs.move` / `fs.delete` / `fs.browse` | 工作区文件操作,**必带 workspace 参数**,沙箱限定;文件树懒加载；`fs.browse` 列盘符/逐层浏览目录 |
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
- **心跳** — WS ping 每 15s,45s 无 pong 判死。

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
| **HubPool** | 多 hub 出站连接池:每连接独立 WS 客户端 + 重连循环 + 心跳;路由 API 按命名空间扇出 / 回源 |
| **EventLog** | 每任务内存日志(运行中);append 即分配 seq;`seed(seqLastOf)` 供再运行接续;尾部只读供 task.poll 归并 |
| **TaskStore** | 持久层:`data/tasks/<taskId>/` 按 agent 分文件 `*.jsonl`;启动扫描建索引;**随机访问分块反向读取原语**(ReverseLineReader 从文件尾 64KB 块向前扫,不整文件重扫) |
| **TaskManager** | 运行编排:创建/取消/再运行(冷启动)/删除;`finish()` 驱逐内存驻留 |
| **DataPusher / DataPusherManager** | 定向推送器(§7.13):每 (sessionId,taskId) 一个虚拟线程,把运行中任务 EventLog 增量(含瞬态)推到 stream 频道;含**窗口式 credit 背压**(stream.ack) |
| **ConversationLoader** | 冷启动:从磁盘 jsonl 重建 conversation |
| **PendingAsks** | askId → CompletableFuture;ask 工具在此挂起(§7.8) |
| **TaskPoll** | 应答 `task.poll` RPC:磁盘反向窗口 ∪ 内存尾部按 seq 归并,rpc.data 分批 |
| **RpcDispatcher** | 方法注册表;每请求一个虚拟线程 |
| **功能模块** | ConfigStore(模型配置只读)、WorkspaceManager(工作区注册表)、FsService(沙箱内文件操作)、GitService(工作区 git 快操作 RPC)、NativeGit(宿主原生 git 执行器,§7.12)、GitCredentialStore(git 凭证加密存储) |
| **任务路由索引** | `taskId → {dir, summary}`(磁盘任务的元数据索引,boot 扫描构建) |

**无修剪、无 retention**:任务永久保留。内存 EventLog 受 `maxEventsPerTask`(默认 50 万)护栏(防 RAM 失控;磁盘 jsonl 全量不受影响)。

### 7.3 Agent 执行链(Spring AI Advisor 生态)

worker 的 agent 执行**复用 Spring AI 2 框架**,不手搓 agent 循环/工具循环/响应聚合。(红线:`AgentRunner` 等执行链只是很薄一层——把 `Prompt` 交给 `ChatClient`,`ToolCallingAdvisor` 接管工具循环,自定义 `Advisor` 注入技能/记忆/护栏等增强;新增 agent 能力优先做成 Advisor。)

主 agent advisor 链(每 run 新建实例,状态随实例隔离):

```
MeasureDurationAdvisor(计时) → SkillAdvisor(skill 渐进式披露索引) → LoopRepeatGuardAdvisor(事件发射 + 工具循环 + 死循环检测)
→ DialogInsertAdvisor(队列项「插入到当前对话」,主 agent 专属) → EmptyResponseRetryAdvisor(空响应重调)
→ TransientErrorRetryAdvisor(瞬时错误退避) → ContextCompressionAdvisor(上下文压缩,最内层)
```

- 核心事件发射由 `WorkerToolEventAdvisor` 完成(继承 Spring AI `ToolCallingAdvisor`,重写受保护 hook 发射 delta/message/usage/tool 等事件,**不另起一层重复实现递归循环**)。
- `LoopRepeatGuardAdvisor` 叠加**死循环检测**:比较本轮与上一轮工具调用签名(名称+参数集合,顺序无关),连续重复达 `worker.limits.max-repeated-tool-rounds`(默认 3)即中断任务(error 收口)。
- `DialogInsertAdvisor`(普通 StreamAdvisor,在主 agent 的工具循环下行阶段)把任务队列「插入到当前对话」的用户消息 drain 并追加给 AI + 发射 `user.message` 事件;子 agent 按 kind==MAIN 旁路(对话是一次性嵌套,不接收任务队列输入)。
- 主 Agent 与子 Agent **共用同一执行入口与 Advisor 链**,仅 agentId 不同;子 agent 不挂计时与 skill,但同挂上下文压缩。

### 7.4 模型池容灾

**模型池 = 一个模型 provider**(`provider: model-pool`,产出 `ModelPoolChatModel`),主/子 agent 与 AI 审议共用同一入口:

- `worker.models` 里新增一种特殊配置项:`model` 字段用逗号分隔的池成员 configId 列表(`model: "deepseek,qwen"`,首个 = 主模型),configId 指向它即「任务默认带容灾」。
- `ChatModelFactory.buildAgentModel` 遇到该 provider 产出 `ModelPoolChatModel`(组合各成员的 OpenAiChatModel,按序逐个尝试):请求异常(非网络、非终态)时切下一个成员重试——每个成员用**自己的完整 options 快照**(baseUrl/apiKey/model 在构建时固定),成功即返回该成员真实响应;网络异常、空响应耗尽、取消类原样上抛;流式带防重护栏(已下发 chunk 后流中断不切换)。容灾切换发 `task.trace(kind=model_failover)`。
- 待池耗尽不做包络,最后异常原样上抛,交给外层瞬时错误重试 advisor 退避重跑。

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
- **任务不做 owner 隔离**:任务数据统一 `data/tasks/<taskId>/`,任务事件扇出到 worker 的全部连接;`tasks.list` 返回全部任务。数据隔离靠命名空间(不同 apiKey 连接到不同 hub/频道域)+ worker 侧文件沙箱。
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
- **运行中**:新到输入入 inputQueue 在本次运行内消费,队列增减广播 `task.updated`(pendingInputs 快照;运行时态不落盘)。
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
| 文件路径 | `WorkspaceAllowCheck`(工作区内放行)→ `MissingPathCheck`(读不存在 NotFound)→ `SkillsReadAllowCheck`(skills 目录只读放行,§7.17;写操作不放行仍走授权链)→ `OverBroadRootCheck`(盘根/工作区祖先拒收)→ `AuthorizeCheck`(委托授权决议) |
| 命令 | `CommandCheck`(危险动词 + 越界已存在路径逐项授权,系统目录同权) |
| 提权 | `PrivilegeCheck`(提权动词 / seccomp setuid exec,§7.11) |

**弹窗形态**:`ask.create{kind:"authorization"}` 三选项(拒绝 / 本轮运行内允许 / 本任务全程允许),答案回传稳定 token `deny`/`run`/`task`;未识别/超时/取消一律按拒绝(安全缺省)。

**两档生效**:`run` 档纯内存,本轮输入处理完即清;`task` 档持久化 `data/tasks/<taskId>/grants.json`,冷启动再运行恢复。

**授权粒度**:路径类按「最深已存在祖先 realpath」、动词类按规范化动词,避免同目录/同动词反复弹;同 key 并发只弹一张卡(inFlight future)。

**拒绝语义**:抛 `PermissionDeniedException` → 统一转「[工具执行失败]」文本回灌模型,agent 循环不中断。

### 7.9 AI 安全审议与无人值守

当需要人工授权(PermissionGate 拦到工作区外路径/危险命令)时,除人工弹窗外提供两条可选的任务级自动路径:

- **AI 审议(`/AI 审议`,kind=ai.review)**:可单独开启。授权弹窗改为由**独立的 AI 审议会话**(无任何工具、独立 system prompt,只基于安全策略判断并要求忽略授权正文中的任何指令,防 prompt 注入)读取授权信息并输出结构化判断(ALLOW/DENY/ESCALATE),在 PermissionGate 内部闭环自动放行/拦截并落审计。**主 Agent 是被审议方,不能自我授权**。
- **无人值守(`/无人值守`,kind=unattended.mode)**:开启时**联动**开启 AI 审议(selectHandler 一次返回两个胶囊,前端各自 apply),并剥离 `ask_user` 工具(主/子同挂;AI 不可见即不可提问)+ 注入提示词「当下处于无人值守模式,如果有疑问,按你推荐的实现即可。」(`UnattendedModeAdvisor` 每轮实时读任务级开关)。两胶囊 ✕ 独立,开启时联动、事后可拆分。

**授权拦截链**(`PermissionGate.ensureGranted` 内、发起人工弹窗前短路,两条独立环节互不相关):

| 拦截链环节 | 判定依据 | 行为 |
|---|---|---|
| ① AI 审议 | 任务级 `aiReview`(开启且审议器在位) | ALLOW → 自动授权(RUN 档);DENY → 拒绝;ESCALATE/审议失败 → 落下一环节 |
| ② 无人值守 | 任务级 `unattended` | 开启 → 授权直接拒绝(无人工可弹);未开 → 正常人工弹窗 |

- 审议路径不发 `ask.create/ask.state` → 任务保持 RUNNING(不误转 waiting-user)。
- 审议异常/超时/输出非 JSON/缺字段 → 默认 DENY(fail-closed);**审议失败绝不自动放行**。
- 审计:`task.trace(kind=auth.review)` 持久落盘,metadata 含 decision/confidence/reason/scope/grantKey/prompt/taskId/审议 agentId;审议链的重试/容灾 trace 同入审计。
- 配置:`worker.permissions.review-timeout-ms`(默认 60s,总预算硬闸)、`review-deny-on-error`(默认 true)、`review-model`(可选,审议专用模型 configId,空则用任务当前模型)。

### 7.10 命令沙箱(多后端)

命令执行(命令行工具、脚本、git 等)统一经 `OsSandbox` 门面按**后端**分发:

| 后端 | 语义 | 何时启用 |
|---|---|---|
| **wsl-direct**(Windows 默认) | 命令在托管的 WSL2 发行版(`eagent`,可丢弃系统)内以 root 运行;宿主盘隔离 = 关闭 automount + 每命令手动挂载工作区 + seccomp deny-mount 过滤器;网络按任务级开关 unshare -n | `auto`(Windows 默认)/ 显式 `wsl-direct`;发行版缺失自动导入(rootfs 随包,sha256 校验) |
| **wsl-bwrap** | 命令经 bwrap 挂载命名空间运行:授权根 = `--bind` 白名单(授权=绑定,撤销=下次不绑,宿主零残留),网络 `--unshare-net` 硬拒,工作区外宿主盘**不可见**(读白名单) | 显式 `wsl-bwrap`(更强隔离的用户知情选择) |
| **windows-mic** | Restricted Token + Low IL + Job Object + 目录 Low 标注 + DACL 可写授权(Windows 原生路径) | `windows-mic` / WSL 探测失败回退 |
| **none/direct** | 直接 spawn(仅超时/输出护栏/网络代理 env 剥离) | 显式 `none` / 非 Windows |

- `worker.sandbox.type`: `auto`(默认)| `wsl-direct` | `wsl-bwrap` | `windows-mic` | `none`(别名 acl/wsl/direct 兼容)。
- **Windows Low IL 可写性契约**(对 windows-mic 后端):工作区树 + EXEC 授权目录必须由 worker 在命令执行前配置为沙箱可写——① 标注 Low 完整性(SACL `S:(ML;OICI;NW;;;LW)`),解决 MIC 的 NO_WRITE_UP;② `WindowsAcl` 给工作区根追加可继承 Allow ACE(本地 Users `(OI)(CI)` 修改+删除权限),解决 ACL 残缺。工作区外保持默认 Medium → 沙箱内写被 OS 拒,构成弹窗授权之外的 OS 级兜底。
- **网络策略**:默认 deny(命令无网络);wsl-direct 未开网络开关则不 unshare(可访问网络);wsl-bwrap deny = `--unshare-net`。
- **Windows 沙箱技术路线说明**:曾评估 AppContainer(Low IL 标注的继任者),因"capability 模型不适合开放式开发工作流+普通 ACE 全失效的读模型破坏面太大"(OpenAI 对 Windows 沙箱的弃用理由同源)而放弃,整体迁往 WSL2 生态(Claude Code 对 Windows 用户的官方推荐路径);windows-mic 保留为回退后端。

### 7.11 提权拦截(seccomp,LINUX 侧)

文本扫描拦不住别名/脚本内/静态链接等形态的提权;`wsl-direct`/`wsl-bwrap` 后端在发行版内安装 **seccomp 用户通知**过滤器(内核 ≥5.0 的 `SECCOMP_RET_USER_NOTIF`):只要最终要执行 setuid 二进制(如 sudo)必经 execve 系统调用,内核在该点拦截。

- 非 setuid 的 exec(ls/git/java)由监听者快路径放行;setuid(如 `/usr/bin/sudo`)经 stdout 控制帧 → worker `PermissionGate.requirePrivilegeExec`(AI 审议 → 无人值守拒 → 人工弹窗,与文本扫描共用 grant key)。
- **授权语义(真机验证后定案)**:非特权 supervisor 架构下"拦截 → 授权 → 沙箱内真实提权"物理不可行(NNP 标志 + user namespace 不映射 uid 0 + 基座只读)。因此——**授权 = WSL 原生 root 重跑**:沙箱内该次 exec 以 EPERM 终止(沙箱内 sudo 永远失败),worker 另起 `wsl -d <distro> -u root -e bash -c "<原命令原样>"`,stdout/stderr/exit 合并回传该次工具调用(标注 root-rerun)。root 进程只出现在这一条受控路径,supervisor/worker 均不提权;`wsl -u root` 是 WSL 既有安全模型,不新增权限面。
- 读内存失败/解析失败/worker 崩溃/stdin 断开 → 一律按拒绝(EPERM),fail-closed。

### 7.12 原生 git 执行与凭证

工作区 git 快操作(`git.*`)不自己复刻 git 语义,由 `NativeGit` 调宿主**原生 git 可执行文件 argv 直传**执行(不经 shell 字符串拼接),复用 `OsSandbox` 的超时/输出上限/env 清理;**不走命令沙箱的 wsl/mic 后端**——git 是前端按钮触发的受控操作(参数受控、路径被 `Sandbox` jail),降权/进发行版会引入路径映射与权限差异(与 JGit 曾有的 bug 同源)。

- **可执行文件定位**:启动探测一次并缓存——`worker.git.executable` 显式指定 > Windows 常见安装路径(`C:\Program Files\Git\bin\git.exe`、`C:\Program Files\Git\cmd\git.exe`、`C:\Program Files (x86)\Git\...`) > PATH 兜底;全部失败明确报错(「git 不可用,请安装 Git for Windows」),不静默回退。
- **稳定化参数**:每个命令预置 `git -C <workspace> -c color.ui=false -c core.quotepath=false --no-pager`,读命令加 `--no-optional-locks`(防 `.git/index.lock` 残留/竞争);env 设 `GIT_TERMINAL_PROMPT=0`(缺凭证 fail-fast,不卡死)、`LC_ALL=C.UTF-8`(输出编码稳定)。
- **路径沙箱**:复用 `Sandbox` realpath 前缀 jail(§5.9);用户 path 参数先经 `Sandbox` 校验再进 argv。
- **并发**:per-workspace 串行锁——写操作(`commit/pull/push/discard/init/clone/remote.add` 与自动同步)同 workspace 串行;读操作带 `--no-optional-locks` 可并发。
- **执行出口**:`OsSandbox.spawnNative(String[] argv, Path cwd, Map<String,String> env)`(宿主原生 argv 直传,非 wsl/mic);git 超时用 `worker.git.timeout-ms`(默认长于统一命令超时,clone/pull/push 大仓库可能较慢)。

**凭证**(`git.clone/pull/push` 共用四档解析链):

1. RPC 临时凭证(username/password,不落盘)→ 生成临时 askpass 脚本,凭证值经 `GIT_EA_USERNAME`/`GIT_EA_PASSWORD` env 注入,`GIT_ASKPASS`/`SSH_ASKPASS` 指向脚本(不拼 argv、不把密码写进脚本文件);
2. 本机默认凭证 → **不注入任何凭证**,git 自行走 `credential.helper` / credential manager / `ssh-agent` / `~/.ssh`(静默,原生 git 开箱即用);
3. 认证失败 → 读工作区 `.everyagent/.git-credentials.enc` 该 host 条目 → 解密后按 ① 注入重试;
4. 仍失败 → `rpc.err(AUTH_REQUIRED)` 弹凭证输入(判定 = 非零退出 **且** stderr 命中 `Authentication failed` / `could not read Username` / `could not read Password` 等关键字,避免网络错误/远端 404 误判)。

- **加密存储**:密钥 `<dataDir>/keys/git-credential.key`(首次启动自动生成 32B AES-256);算法 AES/GCM/NoPadding,随机 IV,AAD=host 绑定条目;密文 JSON `{version, entries:{host:{iv,cipher,ts}}}` 存工作区 `.everyagent/.git-credentials.enc`,明文永不落盘。
- 前端 Git 面板捕获 `AUTH_REQUIRED(host)` → 凭证 Modal(账号/密码/「保存凭证到工作区(加密)」复选框)→ 先带临时凭证重试(克隆时根仍为空),成功后再 `git.credential.save` 落盘。
- 凭证仅存工作区加密文件与 worker 内存,不经 hub / 前端 localStorage;协议不提供"读取凭证"RPC(save 只进不出)。
- 自动同步(git 自动提交)保持静默:只走本机凭证 + 加密凭证,不弹窗。

### 7.13 任务流传输(混合模型:定向推送 + 拉取)

**实时增量 = worker 定向推送**(DataPusher):前端 sub `u.K.task.<id>.stream` → hub 向 worker 发 `subscriber.join{sessionId,taskId}` → DataPusherManager 校验归属后按 (sessionId,taskId) 建推送器;推送器虚拟线程把运行中任务内存 EventLog 增量(含瞬态 delta/thinking)推到 stream 频道,`ext={target:sessionId, operate, initial}`。

- **窗口式背压(credit + ack)**:DataPusher 维护 `nextPushIndex`(每推一帧 +1)与 `ackedIndex`;`nextPushIndex - ackedIndex >= CREDIT_WINDOW(512)` 且未超时(`ACK_TIMEOUT_MS=5000`)时阻塞等待前端 ack;每帧 ext 携带 `credit=true/creditIndex`;前端消费完一帧后经 worker 级 input 频道回 `stream.ack{taskId,creditIndex}`,worker 只路由释放窗口、不建推送器。老前端不识别 credit 则不 ack → 超时降级无背压,兼容。多前端窗口独立,慢端不拖累快端。
- **先订阅后首拉**:前端 `open()` 先 sub stream 再拉初始(rounds + roundTail),推送首扫与首拉重叠的部分前端按 seq 去重吸收。
- **生命周期**:unsub/前端断连 → 销毁推送器;worker⇄hub 断链 → 清扫该连接推送器;任务再运行换新 EventLog → 换挂从头推;任务终态 → 收尾排水一次后空转。
- **降级语义**:推送非阻塞,出站队列满丢帧 + WARN(事件日志是事实源);前端慢 → hub sink 溢出断连 → 重连 resync;漏帧由前端按需拉取补齐。

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
| `run_agent(input, title, agentId?, blocking?)` | 派发子 agent;无 agentId 新建(title 必填,agentId 动态生成);传 agentId 即续跑(复用其上下文);blocking 等结果 |
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

**事件与持久化**:子 agent 不建独立 Task,事件与主 agent 同名、以 agentId 字段嵌套在父任务流(spawn 生命周期为 agent.started/agent.done);**每个子 agent 一个独立会话文件 `<subAgentId>.jsonl`**;冷启动重建、断线续播、ask(带 agentId)全部复用既有机制。

### 7.15 持久化与磁盘布局

```
data/                                # <home>/data(EVERYAGENT_HOME 可覆盖;docker 挂卷)
├─ workspaces.json                   # 工作区注册表 {root, addedAt}
└─ tasks/<taskId>/                   # 任务目录(不再按用户/ownerKey 分目录);永久保留
   ├─ meta.json                      # TaskSummary(含最近一轮上下文用量、agents 子 agent 台账、任务级开关)+ mainAgentId
   ├─ grants.json                    # task 档授权 {taskGrants, extraRoots}(§7.8,首次授权时原子写)
   ├─ <mainAgentId>.jsonl            # 主 agent 会话 + 任务级事件
   ├─ rounds.jsonl                   # 轮次索引(§7.15.1)
   ├─ <subAgentId>.jsonl             # 子 agent 独立会话
   └─ file-changes/<roundId>.json    # 单轮文件变更记录(经 task.fileChanges 拉取)
```

**五项持久化规则**(实现定死):

1. **完成态 `message` 落盘**(主/子同名):整轮思考(thinking)+ 正文(text)+ 工具调用下发(toolCalls,真实模型 toolCall id;缺失兜底生成且三处一致)。工具返回**单独**落 `tool.result`。
2. **瞬态不落盘**:`delta`/`thinking` 只发前端,消耗 seq 但不写盘(磁盘回放有洞的来源)。
3. **ext 为 null 不写行**:jsonl 行 = `{seq, ts, event, agentId, payload[, ext]}`。
4. **jsonl 每行必记 agentId**(文件内字段;线上 wire 主 agent payload 不带)。
5. **按 agent 分文件**:每 agent 一个 `<agentId>.jsonl`;读取按 seq 归并全部文件。

#### 7.15.1 轮次索引 rounds.jsonl

主 agent 侧生成轮次索引(每行一轮:用户输入 → 主 agent 最终回复):

- 行格式:`{index, startSeq, endSeq, user, finalReply, processCount, subs, durationMs, fileChanges, userMessage}`;seq 一律字符串;`endSeq=""` = 未闭合轮;`processCount` = 该轮开区间内过程事件数(0 = 纯问答轮,前端不显示折叠标记);`userMessage` = 完整 user.message payload(懒加载骨架)。
- 增量写:消费用户输入即 `openRoundAtStart` 落一行 `endSeq=""`;`RoundIndexAdvisor` 在主 agent 最终回复后 `rewriteRound` 原位改写闭合(临时文件 + 原子 move,与追加同锁串行)。
- 旧任务首次 `task.rounds` 惰性全量生成落盘;任务终态 do `finalizeRounds` 补写未闭合轮。中断/失败/取消的未闭合轮自然保留。
- 前端"双击打开任务" = 拉 meta → 一次 `task.rounds` 渲染折叠轮次 → 展开按 seq 区间懒加载过程内容。

#### 7.15.2 文件变更(file changes)

每一轮 agent 执行中对工作区的文件写操作被记录为 `round.filesChanged` 类过程数据,落盘 `file-changes/<roundId>.json`(rounds.jsonl 的 fileChanges 字段引用),前端轮详情可拉 `task.fileChanges` 查看该轮改了哪些文件(新增/修改/删除)。

### 7.16 数据模型(完整)

**两个基础决策**:

1. **双事实源分离**:`Event`(不可变,磁盘 jsonl)是传输/回放/审计的事实源;`conversation: Message[]` 是 LLM 工作态,随运行销毁,再运行时由 ConversationLoader 重建。**上下文压缩只改写发送给模型的视图,不触碰两条事实源**。
2. **用户输入入日志**:输入被消费时追加 `user.message` 事件——否则重开后前端无法还原用户说过什么,其他前端也看不到。

**实体总览**:

```
worker(进程)
├─ WorkerConfig:workerId、hubs[{url,apiKey,hubKey}]、homeDir(系统目录)、skillsDir、workspaceRoot(默认工作区初始值)、
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
| **Task** | taskId(短 ID `t_…`)、workerId、title?、workspace(meta 属性)、status、modelSnapshot、createdAt/startedAt/endedAt、summary?、error?、usage、inputQueue、agents、asks、eventLog、mainAgentId | modelSnapshot 为创建时快照;usage 聚合值 |
| **Agent** | agentId(主 `a_…` / 子 `sub_…`)、taskId、parentId、kind(main/sub)、title、status、systemPrompt、toolset、conversation、usage、createdAt/endedAt | 主/子统一建模;子的 toolset 剔除 agent 工具(结构性禁递归) |
| **Message** | messageId、role(system/user/assistant/tool)、content、toolCalls?、toolCallId?、ts、meta{compressed?} | LLM 语义条目,仅存 conversation |
| **Event** | taskId、seq、ts、event、agentId、payload、ext? | 不可变;文件行 agentId 恒非空;瞬态不入盘 |
| **Ask** | askId(短 ID `q_…`)、taskId、agentId、kind、question、options?、status、answer?、answeredBy?、timeoutAt | 运行时的 CompletableFuture 不入模型 |
| **Input** | taskId、text、rawContent?、ts、from(sessionId) | 状态:queued → consumed(取消时 discarded);`rawContent` 为原始输入(含 opaque token 串) |

**斜杠命令与任务级开关**:斜杠命令由 worker 动态注册(`slash.list`/`slash.select`/`slash.cancel`);任选中可返回多个结果(如 `/无人值守` 一次返回「无人值守」+「AI 审议」两个胶囊);任务级 token(模型池、AI 审议、无人值守等)随 meta 持久化、再运行保持,`slash.taskTokens.apply` 用于落地 token 携带的数据。

**队列输入与「插入到当前对话」**:任务运行中输入入队(pendingInputs 外显,可 `task.queueRemove`/`task.queueMove` 管理);「插入到当前对话」(`task.dialogInsert`)把队列项交给本轮主 agent 的插入队列,`DialogInsertAdvisor` 随下一轮工具结果以 role=user 提交给 AI + 发 `user.message`;终态/停止即随 AgentEntity 作废。

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
| `skills/` | 内置 skill 知识包(启动时从 classpath 物化,AI 经 read_file 只读访问) |
| `workspace/` | 默认工作区(注册表首项,始终在册) |
| `wsl/distro/` | WSL 托管发行版 rootfs(运行期状态,可整体重装) |
| `data/` | 工作区注册表 `workspaces.json` + 任务数据 `tasks/<taskId>/` |

**配置分层**:进程配置全部来自 jar 内 `application.yml` 默认 + `~/.everyagent/application-*.yaml` 用户覆盖(`spring.config.additional-location: optional:file:${EVERYAGENT_HOME:${user.home}/.everyagent}/application-worker.yaml`,自动加载,无自定义则零配置文件)。**模型配置由 `worker.models`(Spring 配置)承载**,默认在 jar 内(apiKey 占位符),真实 key 只写用户覆盖文件(机器级、不进工作区、不进 jar/git、不进事件日志)。

**程序附属文件**:rg 二进制、eagent-run.py、WSL 托管镜像统一放**程序根 `<程序根>/runtime/`**(程序根 = JVM 工作目录 user.dir;打包态 = resources 目录,IDE 态 = 仓库根),随安装包分发、运行时只读引用、以字面相对路径 `./runtime` 解析;不打进 jar、不写入系统目录。`worker.program-dir` 配置用于打包态显式指定。

**多工作区并行**:`data/workspaces.json` 注册表 `{root, addedAt}`;`fs.*`/`git.*`/`task.run`(新建)每次调用**必带 `workspace` 参数**(绝对路径),沙箱根在调用时按该参数解析;默认工作区始终在册、不可移除;注册表变化广播 `workspaces.changed`;写操作广播 `fs.changed{workspace,path,kind}`,前端按工作区分组刷新。

**skill 只读例外**:系统目录 `skills/` 是 AI 文件工具对系统路径的**唯一只读免授权**例外——`read_file` 经权限责任链节点 `SkillsReadAllowCheck` 直接放行(realpath 前缀判定);**任何写操作不在此放行,仍走授权决议链**;其余系统路径(data/、runtime/ 等)与普通工作区外目录同权,一律走授权决议(弹窗/AI 审议)。

---

## 8. 前端接入层(every-agent-web)

协议语言中立,任何 WS 客户端(移动端、桌面、另一个 agent)都按第 5 章自行实现。仓内交付 React 前端,`src/sdk/` 内置 TS 客户端 SDK(原独立模块已并入)。

### 8.1 SDK 面

- **HubClient** — connect / hello / sub / pub / 自动重连(重连后自动重订阅 + onResync 重拉校准);`rpc(workerId, method, params)` 按 reqId 匹配 ok/err/data/progress,默认 30s 超时。
- **订阅次序约束** — 对任一 worker:**先 sub 其 `evt` 频道,再发 `cmd`**(rpc 应答全部落在 evt 频道)。
- **TaskPacketView** — 数据包模式:打开任务 = 先 sub stream 频道(worker 据 join 建推送器收到实时增量)→ `task.rounds` + `task.roundTail` 拉初始 → 之后仅靠定向推送收流式(帧与拉取帧同一路 seq 去重/排序聚合);帧消费后回 `stream.ack` 释放背压窗口(§7.13);上滚 `loadBefore(beforeSeq)` 拉更早轮次;`resync()` = 重订阅 + 重拉。
- **channels / ownerKey** — 频道名构造与 sha256 身份,与 Java 契约逐字对齐。

### 8.2 多 worker 聚合

一个 hub 下可有多台 worker。前端以 1 条目录连接(hubKey)看全部在线 worker(presence),对每台已启用 worker 用其 apiKey 建数据连接,任务列表/工作区/git 按 worker 合并展示、按归属定向操作;任务归属 worker 由前端按帧来源动态标注(TaskSummary 后端不含 workerId)。凭证 AES-GCM 加密存 localStorage,presence 指纹(ownerFingerprint 前 16 hex)支持 worker 改名后自动复用凭证。

### 8.3 轮次浏览与懒加载

对话按"轮次"折叠展示(每轮 = 用户消息 → AI 最终回复);过程内容(delta/thinking/工具调用)默认折叠为可展开标记,展开时经 `task.poll` 按 seq 区间懒加载(滚动到 0 高度占位元素进入可视区才续拉 `LazyLoadSentinel`)。渲染层任务线程按 **seq 键控增量 upsert**(多子 agent 并发/重连回放不丢事件;大整数 seq 用 BigInt 精确比较);`userControll` 自动滚动(默认 false = 收到数据贴底,用户手动滚动置 true,滚回底部复位)。

### 8.4 通知

浏览器(Web Notification)与桌面(Electron 系统通知)经统一通知适配器抽象(依赖注入 + 适配器注册表,web 模块零 electron 依赖)。触发场景:授权请求、任务完成/错误、ask_user 提问;不在前台才弹;桌面同 tag 2s 去重,点击回带到前台。

---

## 9. 桌面版(every-agent-desktop)

Electron 将 web + hub + worker **一体打包**为 Windows x64 便携(portable)与安装包(NSIS):

- **进程模型**:主进程 spawn 本地 hub 与 worker 两个 Spring Boot 子进程(`javaw.exe`,jlink 精简 JRE 随包);前端经本地静态服务加载(127.0.0.1 随机端口,保证 localhost 安全上下文),preload 以 contextBridge 注入开箱即用连接配置。
- **配置注入**:生成 hub/worker yaml 经 `--spring.config.additional-location` 覆盖 jar 内默认(整表覆盖 `worker.hubs`,避免误连远端);数据目录复用 `EVERYAGENT_HOME`(缺省 `~/.everyagent`),与命令行/docker 共用同一批任务/工作区/模型。
- **运行时配置**:每次启动读 `<EVERYAGENT_HOME>/desktop-config.json`(hubKey/workerApiKey/workerId/端口),首次生成;日志统一落 `<EVERYAGENT_HOME>/logs/`。
- **生命周期**:单实例锁、占位页/错误页(含日志目录)、before-quit 先停 worker 再停 hub(超时强杀)。
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
 │─cmd: rpc{task.rounds + task.roundTail}────────────→│ 初始渲染(轮次 + 尾段)
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
| 前端断线 | hub cleanup 发 subscriber.leave → 推送器销毁,任务照跑落盘;重连后重订阅(join 重建推送器)+ resync 补齐 |
| 单个 hub 宕机/重启 | 其余连接照常收发;受影响前端重连 + resync,零丢失 |
| 全部 hub 宕机 | 任务继续跑完并落盘(输出无人消费,天然背压);恢复后重订阅 + 从磁盘拉取补齐 |
| worker 断线(到 hub) | 指数退避重连;期间 hub 发 worker.offline,前端显示离线;恢复后 worker.online 触发 resync |
| 家中 PC 关机/worker 崩溃 | 运行中任务终止;**磁盘数据完整**:开机重启后索引重建、任务列表回归、非终态标 failed;发消息继续对话(冷启动) |
| 慢消费者 | hub 出口队列(1000)溢出断开该前端;前端重连 + resync;worker 出站队列满丢帧 + WARN(事件日志为事实源) |

---

## 11. 工程结构与构建部署

### 11.1 目录

```
.                                  # 仓库根 = every-agent 项目根
├── every-agent-hub/               # 消息中心(Spring Boot WebFlux,9100)
├── every-agent-worker/            # 执行器(Spring Boot + Spring AI 2,9200 仅本地健康)
│   └─ src/main/java/.../proto/    # 业务常量住 worker:事件名 / DTO / RPC 方法名 / 频道构造 / 短 ID
├── every-agent-web/               # React 前端(内置 TS 客户端 SDK src/sdk/)
├── every-agent-contract/          # 纯协议契约:帧信封 / RPC 信封 / 错误码 / 身份哈希(Java DTO + TS 类型)
├── every-agent-desktop/           # Electron 桌面打包
├── runtime/                       # 程序附属文件(rg 二进制、eagent-run.py、WSL 托管镜像)
└── docs/ARCHITECTURE.md           # 本文档(唯一架构事实源)
```

三层只依赖 contract,互相零依赖;contract 是纯协议边界,业务全部住 worker proto。

### 11.2 构建 / 测试 / 运行

```bash
# Java 部分(JDK 25;Spring Boot 4.1.x / Spring AI 2.0.x 由根 pom 锁定)
mvn -pl every-agent-hub spring-boot:run          # hub @ 9100
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
- **prod**:hub 与前端静态站部署公网服务器(LB 的 WS 空闲超时 ≥ 60s);worker 在个人 PC 以出站 wss 连入(docker 或系统服务),通过 `worker.hubs` 配置(`worker.hubs[].url` 指向公网 hub,每项 `url + api-key + hub-key`);9200 管理端口仅绑定 127.0.0.1。
- **桌面版**:every-agent-desktop 安装包开箱即用,本地 9100/9200,与命令行/docker 共用 `EVERYAGENT_HOME` 数据。

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
| D20 | **任务数据统一 `data/tasks/<taskId>/`(不做 owner 隔离)** | 单人部署简化;数据边界靠命名空间 + 文件沙箱 |
| D21 | 事件分类:瞬态(delta/thinking)只发前端消耗 seq;持久(message 等)落盘回放 | 流式体验与权威记录分层 |
| D22 | 按 agent 分文件 `<agentId>.jsonl`,行内恒记 agentId | agentId 即 conversationId;冷启动与回放归并单位 |
| D23 | 输入走 worker 级频道 `u.K.worker.<id>.input` | 订阅数 O(worker×hub) 不随任务数增长 |
| D24 | 短 ID:`{前缀}_{2位盐}{base36 序号}`(t_/a_/sub_/q_) | 人可读可念;单 worker 查重兜底 |
| D25 | contract 只承载纯协议,业务常量住 worker proto | workflow 演进零改 contract、零改 hub |
| D26 | 命令沙箱多后端:Windows 默认 wsl-direct、wsl-bwrap 显式、windows-mic 回退 | 「零管理员 + 网络硬隔离 + 零宿主残留」在原生 Windows 不可兼得;WSL2 生态已验证 |
| D27 | 授权语义(seccomp 场景)= WSL 原生 root 重跑 | NNP + userns 不映射 uid0 + 基座只读 → 沙箱内真实提权物理不可行 |
| D28 | AI 审议与无人值守为独立任务级开关,开启时联动、事后可拆分 | 分别满足"无人监督但有把关"与"全流程无人值守"两种需求 |

---

## 13. v2 预留(明确不在 v1 做)

运行中任务的崩溃恢复 · apiKey 签发/吊销 · 多 hub 实例集群(Redis pub/sub 桥 + 亲和)· 前端间自定义频道协作 · 任务归属转移 · tasks.list 搜索 · 大文件上传二进制分帧/压缩 · **worker 编排(调度)**:一台"调度 worker"向其他 worker 的 cmd 频道发命令建任务收结果(命名空间天然放行,零协议改动;v1 中 worker 之间互不知晓)· 子 Agent 跨机分工(dispatch_agent 走 cmd 频道)。

---

## 14. 实现约束(开发者红线)

本章是给实现者的红线清单:以下行为已定死,不按个人偏好变更。与其余章节冲突时,先改文档再改代码。

1. **编码、时间与 ID**:帧为 UTF-8 JSON;ts 一律 epoch 毫秒(UTC);短 ID 规则 `{前缀}_{2位盐}{base36 序号}`,全局唯一从不复用;ownerKey = sha256(apiKey) 64 位小写 hex。
2. **频道与信封(hub 红线)**:频道名字符集 `[a-z0-9._-]` 长度 ≤160,必须以 `u.<ownerKey>.` 开头;hub 只解析 `type`/`channel`(及 hello 握手字段),`event`/`seq`/`payload`/`ext` 原样转发;不存在角色×频道权限矩阵;seq 只属于任务流事件空间,由 task.poll/stream 携带;error 分级(断开 vs 拒单帧);连接抢占(worker 同 clientId 新连关旧连)。
3. **RPC 生命周期**:reqId 连接内唯一,ok/err 已出则后续同 reqId 帧忽略;未知 method → UNKNOWN_METHOD;参数不合法 → BAD_PARAMS;超时是纯客户端语义(SDK 默认 30s),要中断须显式 rpc.cancel;task.run 新建支持 idempotencyKey(10 分钟窗口去重);task.delete 是任务唯一删除路径,无任何自动清理。
4. **错误码两个命名空间,勿混用**:hub `error` = NOT_AUTHENTICATED/VERSION_MISMATCH(断开)、ACL_DENIED/FRAME_TOO_LARGE/RATE_LIMITED(单帧拒绝);`rpc.err` = UNKNOWN_METHOD/BAD_PARAMS/NOT_FOUND/SANDBOX_DENIED/BUSY/INTERNAL/AUTH_REQUIRED。
5. **并发与上限**:maxConcurrentTasks(20)超限 task.run 新建 → BUSY(不排队);maxConcurrentSubs 超限 run_agent 返回错误文本由模型自决;maxEventsPerTask(50 万)超限抛 LogOverflow(磁盘 jsonl 全量不受影响);续跑放行不查并发上限。
6. **沙箱**:路径必须先规范化(realpath)再校验 workspace 根前缀,拒绝 `..`、绝对路径逃逸与符号链接逃逸;字符串前缀匹配不够;授权护的是「工作区外」,不是删除动作本身;不得绕过 PermissionGate 直接放行越界 IO;Windows Low IL 树标注与 DACL 授权只对工作区/EXEC 授权根生效,不得开放到 Everyone;git 凭证只存 worker 本机加密文件,不经协议传输,注入走 env(askpass) 不经 shell 参数;
7. **生命周期**:终态任务收到 task.run{taskId} = 冷启动一次普通运行;worker 优雅停机(SIGTERM)受影响任务标 failed 再关连接;9200 仅绑定 127.0.0.1;worker 每条 hub 连接建立即 sub 该命名空间 cmd + input 两个频道,从不订阅 per-task 频道。
8. **复用 Spring AI,禁止重复造轮子**:agent 执行必须走 ChatClient + Advisor 生态,不得手搓 agent 循环、工具循环、响应聚合、system 拼接;执行链只能是很薄一层;新增 agent 能力优先做成 Advisor;一个 Advisor 只负责一个功能;事件发射等需挂钩工具循环的增强通过继承 ToolCallingAdvisor 并重写受保护 hook 实现;主/子 agent 共用同一运行入口与 Advisor 链,仅 agentId 不同。
9. **文档**:本文档是唯一架构事实源;根目录 AGENTS.md 只写核心约束(每会话加载,保持精简),细节一律进 docs/。