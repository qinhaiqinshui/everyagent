---
title: 架构说明（唯一事实源）
nav_order: 2
has_children: false
---

# Every Agent — 架构说明

> **哪里都可以使用的 Agent**:worker 执行器运行在本地局域网的个人电脑上,任务里的 agent 在其中运行,进度从任何地方查看。
> 本文档是项目的**唯一架构事实源**。实现与本文冲突时,以本文为准(或先修订本文)。
> **收录边界**:本文只写架构——模块边界与依赖方向、wire 协议与契约、持久化布局、不变式、设计决策与红线;实现细节一律不写入本文(判据见 §14.10)。
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
| `every-agent-plugin-api` | 插件 API 契约:ExecContext(统一执行上下文) / EventEmitter / EmitEvent / ChatModelEnhancer / ModelConfig / TaskLifecycleNode 等接口 + 少量工具类(`util/AtomicFiles` 原子写、`util/SecretPatterns` 凭据模式、`slash/SlashTokenEncoder`、`tool/PlainTextToolCallResultConverter`、`skill/SkillMaterializer` 技能包物化;并非纯类型模块,显式依赖 `spring-core`,插件与 worker 共用) | — |
| `every-agent-plugin-parent` | **插件 SDK parent**(仅 `pom.xml`):内置与外部插件共用的构建约定——Java 25 / 编码 / 插件依赖版本管理 / `plugin.json` 打包 / 测试栈。插件只继承它,**不继承应用的 every-agent-parent** | — |
| `every-agent-plugins` | 内置插件集,清单见 plugin-guide `reference/builtin-plugins.md` | — |
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
| desktop | Electron(随包内置 Node 运行时),spawn 本地 hub/worker 子进程(jlink 精简 JRE 25) | 本地 6101/6102 |

版本与构建约定(细节见根 pom 与 `scripts/`):

- 版本统一由根父 pom 锁定(Spring Boot 4.1.x / Spring AI 2.0.x),各模块不得各自升版本。
- **工程版本单点源**:根 pom `<properties><revision>` 是全仓唯一 Maven 版本写点(CI-friendly versions),contract / hub / worker / plugin-api / plugin-parent 都不写自身 `<version>`,一律继承 `${revision}`;跨模块引用写 `${revision}`,**不得写 `${project.version}`**——它解析的是「消费方自己」的版本,在插件(自身版本与工程版本不同号)里会静默指错坐标。install/deploy 侧由 `flatten-maven-plugin`(`resolveCiFriendliesOnly`)把 `${revision}` 解析成实际版本。
- **插件只继承 `every-agent-plugin-parent`(插件 SDK parent),不继承应用的 every-agent-parent**;仅由 `PluginLoader` 运行时加载的内置插件各自持有自己的版本(与自己的 `plugin.json` 同号),不随工程版本走,升级工程版本时插件 pom 零改动。
- 改版本脚本:`scripts/bump-version.py`(工程版本 + 前端 package.json + 残留硬编码体检)与 `scripts/sync-plugin-template-versions.ps1`(文档站字面量)。

## 4. 身份、信任与安全模型

### 4.1 无账户:密钥即身份

- 无用户表、无注册。**apiKey 即身份**:`ownerKey = sha256(apiKey)`(64 位小写 hex,即频道名中的 `<K>`)。
- 持有密钥即该命名空间全权,密钥泄露 = 身份泄露;公网部署必须 wss。

### 4.2 双道鉴权:hub key 管"连上",worker apiKey 管"访问"

- **hub key(必填)**:hub 启动即强制校验 `hub.hub-key`,**必须显式配置**——打包默认值为空、fail-closed,未配置即启动失败;jar 内不携带任何开发默认密钥。所有 frontend/worker 连接必须在 hello 携带原始 `hubKey`,缺失/不符一律 `NOT_AUTHENTICATED`。
- **worker apiKey(按 worker 各自配置)**:连上 hub 后,要访问某台 worker 的任务、文件、git 数据,必须持该 worker 的 apiKey 建立对应命名空间的连接;worker 端 RPC 按连接身份处理。
- 前端因此有**两类连接**:一条"目录连接"(用 hubKey 连,订阅 `u.<sha256(hubKey)>.workers` 看在线 worker 目录)+ 每条 worker 一条"数据连接"(用该 worker 的 apiKey,订阅其任务与事件频道,任务与 RPC 走这条)。

### 4.3 频道即鉴权边界

- 频道名本身即 ACL:频道必须落在连接自己的 `u.<ownerKey>.` 前缀内(字符集 `[a-z0-9._-]`,长度 ≤160)。hub 对每个 sub/pub 强制校验,越命名空间返回 `ACL_DENIED`。
- 前缀校验由 hub 在连接级完成 → 客户端伪造归属物理上不可能。
- **同命名空间内互信(取舍)**:持有同一 apiKey 的任何角色可 pub/sub 该命名空间内任意频道;任务归属、越权等业务规则全部由 worker 处理(hub 不理解业务)。对单人/小团队部署可接受。
- **互信 ≠ 混频道:任务事件与任务流频道带 worker 段(§5.2)**:同一 apiKey 下可有多台 worker(同 `u.<K>.`,不同 workerId);一台 worker 的数据只进它自己的频道,前端从「我订阅了谁的频道」即可无歧义地知道归属。**禁止**客户端按 ownerKey 前缀猜测帧来源 worker(§14.12)。

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
→ { "type":"hello", "ver":4, "role":"frontend"|"worker", "apiKey":"sk-...", "hubKey":"hub-secret", "clientId":"fe-1",
    "meta": { "hostname":"home-pc", "version":"0.1.0" } }        // hubKey 必填;meta 可选,worker 上报
← { "type":"welcome", "ver":4, "sessionId":"s-17", "serverTs":1755859200000 }
```

- `ver` 为协议版本(当前 **4**),握手协商一次;无共同版本 → `VERSION_MISMATCH` 断开。不逐帧携带版本。
- 未 hello 就 pub/sub → `NOT_AUTHENTICATED` 并断开。
- 控制帧全集:`hello` `welcome` `sub` `unsub` `pub` `msg` `error` `ping` `pong`。
- **ping/pong(应用层心跳帧)**:`{"type":"ping","ts":…}` / `{"type":"pong","ts":…}`。前端与 worker 的应用层心跳:间隔 5s,**仅当本周期内无任何帧到达时才发 ping**;判死唯一依据 = **发出 ping 后 15s 无任何帧到达**(不是「距上帧超时」——主动退订降载后连接合法空闲,按距上帧判死会误杀;探测有应答=活,探测超时=死);hub 收到 ping 即回 pong(不路由、不记录)。协议 v4 全链端统一升级,不兼容 v3(v3→v4 的唯一 wire 变更是任务事件/任务流频道加 worker 段,见 §5.2;不做双订阅兼容)。

```jsonc
// 订阅 / 退订(hub 无缓冲,sub 不带 since)
{ "type":"sub",   "channel":"u.K.worker.w7.evt" }
{ "type":"unsub", "channel":"u.K.worker.w7.evt" }

// 发布(ext 开放扩展;任务流实时增量由 worker 定向 pub 到 stream 频道,ext.target=前端 sessionId)
{ "type":"pub", "mid":"uuid", "channel":"u.K.worker.w7.tasks",
  "event":"task.updated", "ts":1755859200000, "payload": { "taskId":"t_k3f0", "workerId":"w7", "status":"running" },
  "ext": { "traceparent":"00-…-01" } }

// hub → 订阅者(原样投递,附已认证 from——含 sessionId 供 worker/前端识别来源连接,ext 原样转发)
{ "type":"msg", "channel":"u.K.worker.w7.tasks", "event":"task.updated",
  "ts":1755859200000, "from":{ "clientId":"w7", "role":"worker", "sessionId":"s-42" },
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
| `u.<K>.worker.<id>.input` | **worker 级输入频道**:`ask.reply` / `stream.ack`(worker 每连接订阅一次,订阅数 O(worker×hub))。**`task.input` 事件已删除**:热任务入队与终态续跑统一走 `task.run{taskId}`(§5.5),worker 不再消费任何「输入事件」 | 命名空间内任意角色 |
| `u.<K>.worker.<id>.tasks` | 任务生命周期:`task.created` / `task.updated` / `task.deleted`(**worker 段必填**:一台 worker 的任务事件只进自己的频道,payload 另带 `workerId`) | 命名空间内任意角色 |
| `u.<K>.worker.<id>.plugin` | **worker 级插件通知频道**(worker 段必填,与 `.tasks` 同理):插件 worker 侧把**非任务域**的数据变更以 `plugin.<pluginId>.<name>` 事件扇出到此(如 workflow 的 `plugin.workflow.changed{kind,action,workspaceId,id,workerId}`)。通知只作「失效→重拉」信号,不承载数据本体——数据真相源仍是 worker 磁盘,前端经该插件 RPC 全量拉取(与 `.tasks` 的 upsert 增量模型不同,故不入 taskStore)。hub 零状态只路由;前端宿主把帧桥接为领域事件 `plugin-worker-event{workerId,event,data}` 供插件 web 侧订阅 | 命名空间内任意角色 |
| `u.<K>.worker.<id>.task.<taskId>.stream` | **运行中任务实时增量**(worker 定向推送,`ext.target=sessionId` 只投该会话;worker 段用于区分同 apiKey 下的不同 worker) | 命名空间内任意角色 |
| `u.<K>.worker.<id>.wf.<instanceId>.stream` | **工作流实例事件实时增量**(workflow 插件经 `EventSink.fanout` 广播扇出,§7.13 广播形态;帧 `event`=实例事件名、`payload` 与 `workflow.instance.events` RPC 应答条目同形 `{seq,ts,event,payload}`,seq 字符串雪花且单实例内严格递增)。**无 subscriber 通知/无背压机**——低频元数据事件走广播即达,历史/断线补齐走 `workflow.instance.events` 拉取,前端按 seq 去重合流;worker 段语义同 tasks | 命名空间内任意角色 |
| `u.<K>.term.<termId>.stream` | **内嵌终端实时输出**(worker 定向推送：event `term.output` payload `{data: base64}`；进程退出推 `term.exited`)。**不加 worker 段**:`termId` 由前端生成且 `term.open` RPC 已按 worker 定向,一台 worker 的 PTY 只对它自己可见,不构成串台 | 命名空间内任意角色 |

> **stream 订阅通知**:前端 sub/unsub `u.<K>.worker.<id>.task.<taskId>.stream` 时,hub 发 `subscriber.join/subscriber.leave`(`payload={sessionId, taskId}`)——这是无状态 fire-and-forget 通知(hub 不存订阅簿),worker 据此按 (sessionId,taskId) 建/销 DataPusher(§7.13)。投递目标:**频道名带 worker 段时按 `ConnectionRegistry.findWorker(ownerKey, workerId)` 只投那一台**(worker 的 clientId 即其 workerId,§4.4);频道名无 worker 段时退化为投给该命名空间全部在线 worker。hub 只做「按频道名寻址连接」这一路由,不解释任务语义(§6.1 零状态红线不变)。

hub 对频道名不解释业务语义:它只做"前缀必须匹配本连接命名空间 + 字符集/长度合法性"这一件事。业务规则(如任务归属)全部住在 worker。

### 5.3 任务流事件(持久 / 瞬态)

事件分**持久**(落盘 + 回放)与**瞬态**(只存内存 EventLog 尾部,消费 seq 但不落盘)。任务流事件双通道:实时增量由 worker 定向推送到 stream 频道,历史回放/上滚/区间/兜底统一经 RPC `task.poll` 拉取——两路读同一本日志、同一 seq 空间,前端按 seq 去重归并。

> **落盘 ≠ 出网(出网投影层)**:「是否落盘」与「是否下发客户端」是两个正交维度——事件照进 EventLog(落盘不动),而**一切客户端可见形态**(事件与轮次)必须经**单点出网投影器 `EgressProjector`** 转换后才出网。出网口共 4 个(stream 推送、`task.poll`、`task.roundTail`、`task.rounds`),全部复用同一投影器与 filter 链;filter 可丢弃/改写 payload、**不改 `ext`**。详见 §14.13。

| 频道 | 事件 | 持久 | payload 要点 |
|---|---|---|---|
| cmd | `rpc` | — | `reqId, method, params` |
| evt | `rpc.ok` / `rpc.err` | — | `reqId, result` / `reqId, code, message` |
| evt | `rpc.data` / `rpc.progress` | — | 流式应答分批 / 进度 |
| evt | `config.changed` / `fs.changed` / `workspaces.changed` | — | `{keys}` / `{workspace,path,kind}` / 注册表快照 |
| tasks | `task.created` / `task.updated` / `task.deleted` | — | 任务摘要(**必带 `workerId`**,与 `tasks.list` 同形);插件可在广播 payload 自行补充专有快照(核心 TaskSummary 不含插件概念,§5.5);deleted:`{taskId, workerId}` |
| task.poll / stream | `user.message` | ✓ | `{content, data:{rawContent?}}`(content = AI 可见明文;rawContent = 原始输入含 opaque token 串,仅用于前端回放还原胶囊)。主 agent 任务输入与派生 agent(子/审议)收到的指令**同名落盘**,仅 agentId 不同——`<agentId>.jsonl` 由此自包含可重建(§7.14);派生 agent 的输入以 trace 形态展示 |
| task.poll / stream | `delta` / `thinking` | ✗ 瞬态 | 逐 token / 推理增量(主/子同名,子带 agentId) |
| task.poll / stream | `message` | ✓ | **每轮权威完成记录**:`{thinking, text, toolCalls:[{id,name,arguments}], agentId?}`;可选 `thinkingStartedAt`/`thinkingDurationMs`(思考阶段真实流式耗时,老事件缺失 must-ignore) |
| task.poll / stream | `tool.started` | ✗ 瞬态 | 单个工具调用开始执行的**事实声明**:`{callId, name, arguments, startedAt}`;callId 直配 message.toolCalls[].id;瞬态不落盘 → 历史回放无此事件,直接展示 result 的 durationMs;中断批已 started 无 result = 执行中被中断 |
| task.poll / stream | `tool.result` | ✓ | `{callId, name, summary, status, startedAt?, durationMs?}`,每次调用一条、callId 直配;结果文本 = 工具执行链上行改写后的原文(脱敏等照常生效),**事件侧不截断**;`status` = `success`/`error` 由工具执行链判定(失败前缀约定 + 非零退出码账本;**不做通用文案匹配**——那会让「读到失败文案源码」的命令自指误判);无 status 字段的历史记录按成功处理 |
| task.poll / stream | `usage` | ✓ | 每轮模型 token 实测用量 + 累计 |
| task.poll / stream | `ask.create` / `ask.state` / `ask.resolved` | ✓ | 见 §7.8 |
| task.poll / stream | `agent.started` / `agent.done` / `agent.status` | ✓ | agent per-run 生命周期,主/子统一(§7.20.1) |
| task.poll / stream | `error` / `cancelled` | ✓ | `{message}`(带 agentId 即该子 agent 失败)/ `{by}` |
| stream | `message.edited` | — | 消息编辑同步事件(非持久):`{seq, text, rawContent?}`;客户端据此移除 seq > 该消息的本地事件并更新内容 |
| task.poll / stream | `task.trace` | ✓/✗ 按 ext | 统一纯显示 trace(重试、压缩、容灾、授权审计等):`{traceId, kind, title, summary?, content?, status?, createdAt, metadata?}`;`ext.persist=false` 标记瞬态 |
| task.poll / stream | `round.opened` / `round.closed` | ✗ 瞬态 | 轮次开/闭通知:`{startSeq,user}` / `{startSeq,endSeq,finalReply}` |
| stream | `plugin.<pluginId>.*` | ✗ 瞬态 | 插件自定义任务直推事件:宿主 taskStream 一律透传领域事件,不折入线程;瞬态不落盘,历史/补齐走插件自持落盘 + 插件 RPC |
| input | `ask.reply` / `stream.ack` | — | `{askId, answer}` / `{taskId, creditIndex}` 流消费进度回报(§7.13 背压) |

- **线上 wire 形态**:事件名不分主/子 agent(delta/message/error 同名),归属由 `agentId` 决定——**所有事件必带 payload.agentId**;前端按 **task 层传入的固定 `mainAgentId`** 判主,不使用「缺省=主线程」魔法;旧任务事件行 agentId 缺失时前端归一到 mainAgentId;磁盘 jsonl 每行必记 agentId。
- **状态 = per-run 生命周期**:每一轮 `Agent.run()` 是一个完整周期 `agent.started → agent.status{running} → [waiting-user ⇄ running]* → error? → agent.done → agent.status{终态}`;task 级状态(`task.updated`)与 agent 级状态分属两个维度,不互相替代(§7.20.1)。
- 单帧上限默认 16 MiB(`hub.max-frame-bytes`);工具结果事件不在事件侧截断,体量上界由执行侧输出上限保证;模型侧另有独立的上下文压缩闸门(§7.5)——「前端所见」以事件原文为准,不与模型最终读到的压缩文本承诺逐字相同。

### 5.4 seq 规则(协议的秩序根基)

- `seq` 由 worker 按任务**从 1 单调递增分配**,写入事件日志的瞬间确定;**跨运行延续**(再运行从上次水位接续)。
- **瞬态事件也消耗 seq**(delta/thinking 等消费序号但不落盘)→ **磁盘回放的 seq 有洞是合法状态**;seq 仍严格递增、从不复用。
- **出网被过滤的事件也造成客户端所见 seq 洞**:事件已落盘(运行中日志永不修剪),但被出网过滤链丢弃(§5.3)→ 客户端按 seq 看到的序列因而有洞,与「瞬态占号不落盘」同属合法状态;seq 空间本身不变、仍严格递增,前端按 seq 去重归并照常工作。
- **`seq` 属于任务流事件空间**(每任务一个),wire 上以**字符串**传输(`String.valueOf(seq)`):seq 是 64 位 Snowflake(≈10^17),远超 JS `Number.MAX_SAFE_INTEGER(2^53)`;按 number 输出会在浏览器 `JSON.parse` 丢精度。前端 `compareSeq` 按「位数优先 + 字典序」比较字符串,等价数值序且不丢精度。
- 实时与历史来自同一本日志、同一 seq 空间 → 前端按 seq 去重、排序;游标 lastSeq 取自事件项的 seq 字符串。
- cmd/evt 频道的 rpc 帧不携带 seq(seq 只属于任务流事件空间)。

### 5.5 三条动词与 RPC 方法注册表

worker 端 `RpcDispatcher` 注册方法;应答回**请求来源连接**的 `evt` 频道,reqId 供请求方匹配。下表为方法清单与一句话语义;参数级校验细节以 worker 侧 RPC 实现(常量住 `RpcMethods`)与插件指南为准。

| method | 说明 |
|---|---|
| `tasks.list` | 任务列表快照(内存运行中 ∪ 磁盘索引合并;可选 `workspace`/`limit`/`offset`/`taskIds`) |
| `task.run` / `task.cancel` / `task.delete` | **创建/续跑合一**:不传 taskId=新建(必带 workspace)并开跑;传 taskId=载入老任务历史续跑(运行中则入队)。delete = 唯一删除路径(运行中拒绝) |
| `task.poll` | 任务流统一拉取:磁盘窗口 ∪ 内存尾部按 seq 归并,**经 EgressProjector 投影后**下发;支持 afterSeq/beforeSeq/区间/mode('events'/'rounds')/waitMs 长轮询;应答字符串字段 `nextSeq` = 未过滤口径推进游标(§7.13) |
| `task.rounds` / `task.roundTail` | 轮次索引拉取(含运行中 open 轮)/ 按轮起点取该轮末尾事件(初始渲染);均经投影器下发;rounds 项与 open 轮对象均携带稳定主键 `roundId`(§7.15.1) |
| `task.agents` | 任务下**全部** agent 台账一次性拉取(**task 域 RPC**,无 creator 过滤,主 agent + 各来源派生;live 优先,冷任务读 agents.json);应答 `{agents, mainAgentId}`;`run_agent`/`list_agents`/`wait_agents`/`stop_agent` **工具**仍属 subagent 插件(§7.14) |
| `task.dataDir` | 任务数据目录在 worker 宿主上的**绝对路径**(随取随发,不进 TaskSummary wire);应答 `{path}` |
| `task.fileChanges` | file-change 插件注册的 RPC(方法名常量与语义住插件侧,task 核心不感知):带 `roundId` → 该轮变更全文;省略 → 全任务各轮轻量摘要(§7.15.2) |
| `task.queueList` / `task.queueRemove` / `task.queueMove` / `task.queueSnapshot` | 队列插件族(§7.16):运行许可/排队快照、队列项删除/重排、待消费输入快照——插件专有数据不进核心 TaskSummary/ComposerPanelCtx |
| `task.run{metadata:{editSeq}}`(消息编辑重发) | **无独立 RPC**:统一走 `task.run` 携带 `metadata.editSeq`,由 task-edit-resend 插件的 `EditResendNode` 截断 seq > 该消息的磁盘事件、原地更新消息、广播 `message.edited`、冷启动重跑;任务运行中拒绝 |
| `config.get` / `config.reload` | 模型配置读取(apiKey 恒为掩码,明文不出 worker)/ 重新读取并广播 `config.changed` |
| `config.model.add` / `update` / `remove` | 模型配置增删改:整表原子落盘 `<homeDir>/application-worker.yaml` 的 `worker.models` 段 + 热重建内存索引 + 广播;仅影响后续新建任务,运行中任务用创建时冻结的快照 |
| `skill.list` / `skill.exposure.set` | skill 全量清单(目录扫描唯一真相源 + 各自按工作区暴露配置)/ 设置暴露配置(黑白名单并存,黑名单优先;§7.17) |
| `skill.export` / `skill.import` | skill 目录导出/导入 zip(**跨 worker/跨机器迁移格式**;导入即失效缓存重扫、无需重启;zip-slip 防护 + 解压总量上限;§7.17) |
| `workspaces.list` / `add` / `remove` / `addExternalRoot` / `resolveMissing` | 工作区注册表 CRUD、工作区外部授权根注册、缺失工作区落定(action=delete 级联删任务数据 / redirect 纠正到新目录并迁移任务归属;§7.17) |
| `fs.list` / `reveal` / `read` / `write` / `mkdir` / `move` / `delete` / `copy` / `browse` | 工作区文件操作,**必带 workspace 参数**,沙箱 jailed;文件树懒加载、条目 stat 逐条容错;**只读操作**并入工作区外部授权根 + 技能目录 + 在途授权根,**写操作**仅并入外部授权根与技能目录(§7.8/§7.17);`fs.copy` = 递归复制(目标已存在 / 位于源子树内拒绝,符号链接按链接自身复制);`fs.browse` = 全盘逐层浏览(不经沙箱,仅列名,可选 includeFiles) |
| `fs.readRaw` / `writeRaw` / `statRaw` / `grantRawRoot` / `fs.replaceRaw` | 机器绝对路径 Raw 系列(文件标签页用户操作专用,无 workspace 参数)。**信任边界与 fs.\* 沙箱同构(§14.6)**:合法根集合 = 在册工作区根 ∪ 外部授权根 ∪ 技能目录 ∪ 在途授权根 ∪ 会话用户放行目录,越界一律 `SANDBOX_DENIED` → 前端弹确认 → `fs.grantRawRoot` 显式注册放行目录(**会话级内存态,仅此 RPC 可放行**,其余 Raw 操作绝不自动放行);`fs.readRaw` 大文件流式分批回传;`fs.replaceRaw` = 「定位-替换」写(差异回滚专用,与 update_file 工具同语义:oldContent 唯一命中、行尾宽容、原子落盘) |
| `fs.revealInOs` | 在运行 worker 的宿主机器上打开系统文件管理器并选中目标(跨平台,argv 直传无 shell 解析) |
| `term.open` / `input` / `resize` / `close` | Web 内嵌终端会话(§7.18,真 PTY);termId 由前端生成,先 sub 频道再 open 防丢首帧 |
| `search` | **统一搜索入口(破坏性变更:取代已移除的 `fs.search`/`fs.find`/`task.search`,不保留兼容壳)**——**核心零类型感知**:只「遍历 `SearchProviderRegistry`(可选按 `kinds` 过滤)→ 触发 provider → 聚合」,类型清单完全来自注册表;入参仅 `workspace`(必带,沙箱 jailed)/`pattern`(核心不解释)/`kinds?`/`filters?`(**不透明参数袋**,key 为 `${kind}.${field}`,核心绝不解释内容);内置 `file-content`/`file-name`/`task` 三引擎均为 `SearchProvider`,与插件 provider 同权(§8.5);大结果复用 `rpc.data` 分批 |
| `git.status` / `log` / `diff` / `show` / `commit` / `pull` / `push` / `discard` / `init` / `clone` / `remote.add` / `remote.list` | 工作区 git 快操作(git 插件注册,必带 workspace);由 `NativeGit` 调宿主原生 git argv 直传执行(§7.12);大型迁移(批量 checkout / 大仓库迁移)建为 Task,进度走任务流 |
| `git.credential.save` | 保存 git 远端凭证(AES-GCM 加密落盘,§7.12;只写不读回) |
| `workflow.*` | 工作流插件自注册的方法族:`def.list`/`def.get`/`def.save`/`def.delete`/`def.export`/`def.import`、`run`(RPC 直启仅限无 AI 节点的定义——含 AI 节点须任务内运行,§7.20.8)、`instance.list`/`instance.get`/`instance.runLog`/`instance.events`/`instance.cancel`/`instance.delete`/`instance.decide`/`instance.dataDir`(实例目录绝对路径,前端「复制工作流路径」按需拉取,`task.dataDir` 同款纪律:路径不进列表 wire);**写操作必带 workspace 参数**(同 `task.run` 纪律);数据住 `workspaces/<id>/workflows/`(§7.15);实例事件混合模型:实时增量由插件扇出到实例流频道(§5.2),历史/断线补齐走 `instance.events` 拉取,前端按 seq 去重合流(§7.13 广播形态) |
| `slash.list` / `select` / `cancel` / `taskTokens.apply` / `taskTokens.list` | 斜杠命令清单与选中/取消、任务级 token 应用与现值拉取(§7.16) |
| `mention.query` | `@` 文件搜索(后端子序列模糊匹配,独立于统一 `search`,不属搜索面板) |
| `rpc.cancel` | 取消进行中的长 RPC(Future.cancel) |
| `plugin.list` / `install` / `uninstall` / `enable` / `disable` / `webSource` / `asset` | 插件管理(worker 核心注册,§8.5):目录(含禁用项)、`.eap` 安装/卸载、启停(**均重启 worker 后生效**)、web 源码/静态资源读取 |
| `sys.methods` / `sys.info` | 能力发现:本 worker 支持的方法清单与版本、workspace/模型/hub 元信息 |
| `sys.hubs` | 本 worker 的 hub 连接清单(**只读展示**):每条 `{url, connected, local}`,配置序;**不含任何凭据**(apiKey/hubKey 明文不出 worker,同 `config.get` 的掩码原则)。设置页「连接到远程 Hub」弹窗据此展示「该 worker 已连接到的 hub(含本地)」——用户据此选对要连的地址 |
| `worker.restart` | 重启 worker 进程(设置页「重启 Worker」按钮):应答 ok 后优雅关闭 ApplicationContext(断开 hub 连接、销毁插件),再按运行环境拉起新进程——**systemd 管辖**(服务器 host 部署)不自 fork,以约定退出码交接 supervisor 拉起;**无 supervisor**(desktop/bat/nohup)以重建的启动命令**自重启**;启动命令无法重建时拒绝执行(worker 不重启) |

**扩展规则**:协议固定的是交互形状(请求/应答/分批/进度/取消/通知),不是内容;`method` 只是字符串命名空间(`域.动作`),`params`/`result` 是自由 JSON。新功能 = 注册新 method,零改协议、零改 hub。兼容规则:method 只加不改;breaking change 用新名,老客户端靠 `sys.methods` 发现能力。

### 5.6 前向兼容与预留

- **must-ignore 总则**:接收方对信封中任何未知字段一律忽略、不得报错——这是真正的演进机制。
- **`ver`(握手级)**:协议版本在 hello/welcome 协商一次;方法级版本由 `sys.methods` 报告。
- **`ext`(帧级,开放映射)**:自由 JSON 键值;hub 零理解、逐帧原样转发。生态约定:`ext.traceparent`/`ext.baggage` 按 W3C Trace Context 携带链路追踪;worker 将其随持久事件入库,`task.poll` 重放保真。
- **method 只加不改,breaking change 用新名**:RPC 方法一经发布只增不改,破坏性变更以新名落地、旧名直接移除(不做兼容壳),老客户端靠 `sys.methods` 发现能力。**已发生的破坏性变更**:统一 `search` 取代并移除了 `fs.search` / `fs.find` / `task.search` 三方法(§5.5),老客户端调用旧名得 `UNKNOWN_METHOD`,须改调统一 `search` 并按 `kinds` 指定类型;`mention.query`(不属搜索面板)不受影响。
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
- **ChannelRegistry** — channel → 订阅者集合;pub 到来即遍历投递(带 `ext.target` 时只定向投给该 sessionId);前端 sub/unsub stream 频道时发 join/leave 通知——**频道名带 worker 段则按 `findWorker(ownerKey, workerId)` 只投那一台**,否则投该命名空间全部在线 worker(仍是无状态 fire-and-forget,不存订阅簿,§5.2)。
- **PresenceService** — worker 会话建立/断开时向 `u.<K>.workers` 发 worker.online/offline;订阅时补发全量快照。
- **慢消费者保护** — 每连接出口队列上限 1000 条,溢出断开;前端自动重连 + 重新拉取,不丢数据。
- **心跳** — WS protocol-level ping 每 15s(`hub.ping-interval-ms`);判死开关 `hub.stale-read-ms` **默认 0(关闭)**,>0 时按「无任何入站帧超过该时长」close 1001(不是按 pong 计数);同时响应应用层 ping 帧——收到即回 pong,不路由、不记录(§5.1)。

### 6.3 公网加固清单

wss 强制 + 证书;hello 失败限速(防 key 枚举,60 次/分/单 IP);单帧上限;pub 令牌桶(100/s + 突发 100,**当前代码临时停用**——定向流式推送会被误限 RATE_LIMITED 丢帧,常量与配置保留、恢复只差取消注释);hub-key 连接鉴权(必填;打包默认值为空,fail-closed——未显式配置即拒绝启动,无任何内置默认密钥,公网部署必须显式配置);apiKey 白名单不做(它定义命名空间边界)。

---

## 7. Worker 执行层(every-agent-worker)

### 7.1 出口路径:本地事件日志先行(一进三出,解耦的根基)

```
任务虚拟线程 ──append──→ 内存事件日志(每任务,分配 seq)──异步──→ 磁盘 *.jsonl(按 agent 分文件)
                              │                                      ↑
                              ↓                                      │
   EgressProjector(单点出网投影:pre-wire 过滤链,4 口共用)           │
        ├── DataPusher.push ──→ conn.pub → stream 频道(实时增量)     │
        └── task.poll / task.roundTail / task.rounds                 │
            (内存尾部 ∪ 磁盘反向窗口按 seq 归并,rpc.data 分批)─→ 前端
```

- 发布永不阻塞任务线程:无消费者、hub 全部宕机、落盘慢,任务完全无感。
- 持久化是"订阅本地日志的异步 sink"(fire-and-forget)。
- 任务终态时 flush 落盘 → 更新 meta → **销毁内存驻留**。
- **一进三出**:一进 = `append` 进 EventLog;三出 = ①落盘(事实源)②定向推送(stream)③`task.poll`/轮次拉取(pull)。**出网与落盘解耦**——落盘先于且独立于出网,客户端只可见经 `EgressProjector` 投影后的形态(§5.3)。4 个出网口(stream 推送、`task.poll`、`task.roundTail`、`task.rounds`)全部经该投影器,过滤链可丢弃/改写事件但**不改 `ext`**;被丢弃的事件仍已落盘(客户端所见 seq 有洞合法,§5.4)。

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
| **功能模块** | ConfigStore(模型配置存储,增删落盘 application-worker.yaml 并热重建)、WorkspaceManager(工作区注册表)、FsService(沙箱内文件操作)、GitService(工作区 git 快操作 RPC)、NativeGit(宿主原生 git 执行器,§7.12)、GitCredentialStore(git 凭证加密存储) |
| **任务路由索引** | `taskId → {dir, summary}`(磁盘任务的元数据索引,boot 扫描构建) |

**无修剪、无 retention**:任务永久保留。内存 EventLog 受 `maxEventsPerTask`(默认 50 万)护栏(防 RAM 失控;磁盘 jsonl 全量不受影响)。计数口径:护栏只计**持久(落盘)事件**;流式瞬态事件(delta/thinking 及 `ext.persist=false` 的 trace)虽进内存缓冲供实时推送,但不占用计数——瞬态风暴由 `ModelLengthGuardAdvisor`(§7.3)治理。

### 7.3 Agent 执行链(Spring AI Advisor 生态)

worker 的 agent 执行**复用 Spring AI 2 框架**,不手搓 agent 循环/工具循环/响应聚合(红线见 §14.8)。`AgentRunner` 等执行链只是很薄一层:把 `Prompt` 交给 `ChatClient`,`ToolCallingAdvisor` 接管工具循环,自定义 `Advisor` 注入增强。主 agent advisor 链(每 run 新建实例,状态随实例隔离)按层自外向内:

```
AgentStatusAdvisor(agent 生命周期事件,最外层)
→ 轮次/注入类(RoundIndex / SystemInfo / AgentsMd / Skill / GitAutoSync / SlashToken / FileAttachment)
→ [工具循环 ToolCallingAdvisor 层](WorkerToolEventAdvisor 模型流事件 + 插件 advisor)
→ 重试/护栏层(DialogInsert / WaitTask / 空响应重试 / 瞬时错误退避 / 自适应输出预算 / 输出预算护栏)
→ 上下文压缩 / 模型限流 / token 估算校准(最内层)
```

精确 order 与完整清单以 `AdvisorProvider` 注册表为准(代码内单点,不复制进本文档);新增 agent 能力一律优先做成 Advisor(§14 红线)。

- **AgentStatusAdvisor 挂最外层且不继承 `ToolCallingAdvisor`**:只需「整轮一次」的流生命周期信号(工具循环递归只重入更内层 advisor);最外层还保证 agent 终态事件晚于 message/usage 与 rounds 闭合落盘(前端以主 agent 终态 `agent.status` 为拉 rounds 的触发点)。见 §7.20.1。
- **模型流事件 vs 工具侧事件**:delta/message/usage 等模型流事件由 `WorkerToolEventAdvisor`(继承 ToolCallingAdvisor、重写受保护 hook)发射;`tool.started`/`tool.result` 在 per-call 工具执行链发射(§7.14.3)。
- **流式重订铁律**:一切升级/重试重订必须经 `original.copy(this)` 取 fresh 链(Spring AI 的 advisor 链是一次性消费的,同链二次进入必抛错;测试桩链必须同样实现一次性语义,否则此类 bug 单测全绿);重订点必须检查 **agent 级终态闸门**——`stop_agent`/级联停止后,旧流读秒到期不得复活重订模型(与重新派发的新运行并发)。
- **WaitTaskAdvisor 在席等待**:等待绑定 take 消费后迁移到「在席等待」而非移除,收口才退场——周期检测因此能识别**并发互等竞态**;等待期间打心跳日志(静默挂起必须在日志可见)。
- **ModelLengthGuardAdvisor(输出预算耗尽护栏)**:识别「输出已达上限」的确定性失败,三条路径殊途同归报同一错误;**先向下游下发合成 `finish_reason=length` 帧、再抛非重试异常**——合成帧语义是跨插件协议契约(AdaptiveMaxTokensAdvisor 只认帧,不认异常类型),不可移除。错误为自定义非重试异常(穿透瞬时重试),信息含用户侧建议。
- **LoopRepeatGuard** 是装饰 `ToolCallingManager` 的核心守卫(不是 advisor):比较本轮与上一轮工具调用签名(名称+参数集合),连续重复达阈值**先以提醒文本作为该轮工具结果回传模型**留一次纠正机会(本轮不真正执行),再重复才中断任务;粒度有意保持批级。
- **隐式工具(hidden tools)**:`ToolProvider.createHiddenTools(ToolContext)` 产出 hidden 池(键=工具名)——**常驻下行、始终可执行**:默认不进请求 tool 定义,frontmatter `tools` 绑定的工具在模型读取对应知识包后以**完整定义(含参数 schema)自动进请求**(自动暴露语义与 exposedTools 持久化见下条「skill 工具自动暴露」;子 agent 工具族 `run_agent`/`list_agents`/`wait_agents`/`stop_agent` 即此形态,§7.14);未暴露期间模型仍可按 skill 文档硬调。hidden 池随 prompt options 的框架 ToolContext map 下行(per-run 生命周期、独立键、每 run 组装期一次定型,与 RunContext 同路);工具名解析 fallback **仅查本请求 hidden 池**,未命中走既有 soft-fail 兜底——**不启用 Spring 全 Bean 解析**(安全边界:未知工具名不得凭全局注册表命中)。**无配置档位**:自动暴露是唯一形态(应急回退布尔已删除)。
- **skill 工具自动暴露**:AI 经 `read_file` **成功**读取某 skill 知识包(per-call 链被动观察节点 `skill-tool-expose`:失败前缀/非 read_file 不触发;path 经沙箱反译后与 SkillCatalog 的 knowledgePath 归一比对——normalize + 分隔符折叠,Windows 大小写不敏感)后,该 skill frontmatter `tools` 绑定的工具名写入本 agent 的 exposedTools 集合(`AgentContext.exposeTools` 契约方法,纯可见性语义——不在集合内的 hidden 工具仍可按 skill 文档硬调),组装期取与 hidden 池的**交集**以完整定义并入请求 toolCallbacks(frontmatter 捏造的未注册名不生效)。集合跨轮累积,持久化并入 `AgentLedger` 台账条目 `exposedTools` 数组(既有 30s/persistFinal 落盘路径,§7.20.1),agent 重建(`AgentBuilder.build()`,主 agent 每轮新建/子 agent 档案重建均经此)时回灌——下一轮/重启后无需重读知识包。
- **DialogInsertAdvisor 限主 agent**(子 agent 对话是一次性嵌套,不接收任务队列输入)。
- **主/子共用同一执行入口与 Advisor 链**,仅 agentId 不同;链上 advisor 对主/子一视同仁装配,仅明确按主体区分的能力才经 `AdvisorProvider.appliesTo` 显式过滤。
- **advisor 取数统一走 `AgentContext.execution()`(ExecContext 槽位,§7.20)**:taskId→`subjectId()`、模型配置→`snapshot()`、事件→`emitter()`、终态判定→`terminal()`;agent 装配经 `ctx.agentFactory().create(agentId)`(预绑定工厂)。不 import 任务域类型。fileChanges 等插件功能不进 ExecContext 也不进 TaskRuntime(§7.15.2/§14.11)。

### 7.4 模型构建与容灾

worker 的模型构建经 `ChatModelFactory` 统一入口,遇到特殊 provider(如组合型模型)时经 `ChatModelEnhancer` SPI(§7.19.5)委托给匹配的插件构建。worker 核心 `ConfigStore` 原样存储 provider/model 字符串,不解析插件私有格式。容灾切换经 `EventEmitter.emit(EmitEvent.TraceData.of("model_failover", ...))` 发 `task.trace(kind=model_failover)`。

模型 HTTP 超时经 `StreamTimeoutReleaseInterceptor` 解除 okhttp `callTimeout`(流式长思考不限总时长);`FirstChunkWatchdogAdvisor`(advisor 链最内层)在首帧超时时以 `TimeoutException` 中断,交瞬时错误重试。

### 7.5 上下文管理

**双事实源分离**(红线):`Event`(不可变,磁盘 jsonl)是传输/回放/审计的事实源;`conversation: Message[]` 是 LLM 工作态,随运行销毁,再运行时由 ConversationLoader 重建。

**上下文压缩**(`ContextCompressionAdvisor`,主/子同挂):只改写**发送给模型的 instructions 视图**,内存 conversation 与磁盘事件日志始终全量。

- **offset 校准(双向)**:每轮以「上一轮 provider 实测 inputTokens − 粗估算值」为加性修正项,**正负都要采纳**——只采纳正 offset 会让触发阈值退化为「粗估 × ratio」,粗估高估时上下文远未真实占满即误触发压缩。
- **冷启动校准种子(跨运行)**:压缩 advisor 在发送视图为全量未压缩时记账估算,随 `usage` 事件落盘并**粘性**写入 `meta.usageByAgent[agentId]`(压缩视图轮次不覆盖),再运行回灌(与聚合值成对,防「续跑后聚合塌成只剩主 agent」);首个请求用**比率折算**(随内容规模自缩放,把固定开销折算在内)替代加性 offset,本运行首个实测 usage 回来后回到逐轮加性重算;换模型作废回退。
- **单一估算口径**:复用全局 `TokenEstimator` SPI(provider 延迟解析,缺失回退 UTF-8 bytes/3);估算函数方向盲,不分设输入/输出两套口径;输入侧残余偏差由压缩自身 offset 校准承担。
- **累积式裁剪 + 摘要压缩**:首次压缩后持有基线,后续轮只把新增消息 delta 接到基线上增量裁剪;丢弃历史轮前生成要点摘要(失败退化为「保留该轮 user 截断简版」防永久失忆);单条超大工具结果做首尾保留的确定性截断。
- 配置:`worker.limits.context-compression-enabled` / `context-offset-enabled` / `context-summary-enabled` / `context-summary-max-tokens` / `context-max-tool-result-chars`(超大结果发给模型前的截断闸门)/ `context-trigger-ratio`(0.95)/ `context-target-ratio`(0.50)。
- 可见性:`task.trace(kind=context_compression)`;用户可经 `/压缩上下文` 斜杠命令**强制压缩**一次(绕过阈值,整体关闭时也生效)。该命令是**输入框内联一次性胶囊**:随消息提交触发、本 run 首个模型请求消费后即弃,不落盘不常驻不重复触发——与无人值守等持久开关(bottom 胶囊 + 任务级 token)划清边界。

**用量**:每轮实测 token 用量由 `usage` 事件上报;task 层 usage 投影把任务下全部 agent 的「最近一轮上下文占用」聚合为 `TaskSummary.usage`(Σ inputTokens / Σ contextWindowTokens,前端任务列表与上下文电池同源同值;已终局 agent 保留最后快照),随 meta.json 持久化并与 per-agent 基线成对回灌;per-agent 口径(主/子一视同仁)另经台账 agents.json 与流内 usage 事件供给前端 agent 卡片。

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
                              运行中=入队等待          作为一次普通运行(没有"续跑"概念)
```

- **创建**:`task.run`(不传 taskId;必带 workspace)→ 建目录 → 任务驻留内存。
- **运行中**:新到输入入 inputQueue 在本次运行内消费(轮次循环 queue.loop 在每轮内核返回后 poll,临界段内侧续跑;consumeInput 消费下一条输入前经 `ConversationLoader.catchUpRuntime` 从事件流追回上一段落最终回答轮,保证会话内存 user/assistant 交替完整——advisor 不回写会话内存),队列增减**与逐项消费**均广播 `task.updated`(pendingInputs 快照;运行时态不落盘);悬空队列(终态未消费项)随 queue.jsonl 持久化、再运行时恢复。
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

- 工具实现(`ask_user` 工具由 ask-user 插件提供,主/子 agent 均可用):`askUser(...)` 发 `ask.create` 后 `pendingAsks.await(askId, timeout)`——虚拟线程挂起零开销。
- 多前端抢答:complete() 幂等,先到先得;ask.state 30s 重发 + 持久事件让重连/新上线前端必然看到挂起问题。
- 超时(默认 30 分钟):向模型返回"用户未响应",由模型自决;任务在等待期间转入 `waiting-user`;取消时 future 异常完成。
- 前端 ask 卡片生命周期:**终态事件按 seq 判定**——只结算 seq 早于该终态事件的挂起 ask,历史终态事件不得误清当前挂起;已落定的 ask 不因回放帧/迟到实时帧复活;仅本地误清(worker 连接变化等)的 ask 由 worker 实时 `ask.create`/`ask.state(pending)` 复活——worker 仍在等待即应可见可答(G4)。

#### 危险操作授权(PermissionGate)

ask 管道承载第二类阻塞请求:**危险操作授权**。`PermissionGate` 在工具执行前拦截,拦截面:

1. 文件工具目标在**工作区外**(realpath 判定,符号链接逃逸同判;读 → READ,写 → WRITE);
2. 命令串中的**危险动词**(`worker.permissions.dangerous-patterns` 可配)——**仅当命令引用可能落在工作区外的路径时**才需授权;工作区内增删改查直接放行(授权护的是「工作区外」,不是删除动作本身;cwd 锁定 + 沙箱可写性契约兜底);
3. 命令串中引用的已存在工作区外路径(EXEC 权限)——落在该工作区**外部授权根**内者除外(用户 `@` 显式选择 = 已授权,§7.17)。

**权限责任链**(worker `tools/permission/`):节点产出三态 `ALLOW|DENY|SKIP`,链上顺序执行,任一 ALLOW/DENY 即短路;全链 SKIP 按拒绝兜底。

| 入口 | 链(顺序) |
|---|---|
| 文件路径 | 工作区内放行 → 读不存在 NotFound → skills 目录放行 → 外部授权根放行环 → 过宽根(盘根/工作区祖先)拒收 → 授权决议 |
| 命令 | 危险动词 + 越界已存在路径逐项授权(**外部授权根内的引用视同已授权**) |
| 提权 | 提权动词(§7.11) |

**用户显式选择=已授权(工作区外部授权根)**:经 `@` 弹窗 `+` 图标显式选择的工作区外路径由 worker 注册为**工作区外部授权根**(§7.17,语义 = 完全读写)——该选择本身就是授权动作,经文件路径链放行环直接放行,PermissionGate **不再弹授权 ask**。这是与人工弹窗/AI 审议并列的授权来源,不是绕过 gate:全链 SKIP 兜底、过宽根拒收等语义不变。**放行环两链同源**:外部授权根放行谓词是单点实现,文件路径链与命令链同样消费——曾漏接时「用户 `@` 引用过的目录」文件工具读得进、一句命令照样弹授权,与口径自相矛盾。

**弹窗形态**:经 `ask.create` 发一条单题选择题,三选项 `value` = 稳定 token `deny`/`run`/`task`(前端纯数据驱动渲染,不按语义特判);前端把作答统一折成「题干：值」文本回传,worker 解析**只判值段**(兼容裸 token/旧协议/磁盘回放);未识别/超时/取消一律按拒绝(安全缺省)。`AskQuestion` 另有结构化信息槽 `fields`(机器可读字段与人类可读文案分离,must-ignore 双向兼容)。

**两档生效**:`run` 档纯内存,本轮输入处理完即清;`task` 档持久化 `grants.json`,冷启动恢复。**档位由决议节点随结论带出链外**(`AuthorizationDecision.Scope`:ALLOW+RUN / ALLOW+TASK)——自动放行节点(AI 审议、第三方白名单)只能用默认 RUN(不得替用户做「本任务全程」的持久化承诺),只有真正问过用户的人工弹窗才可返回 TASK;`GrantRegistry` 不得把 ALLOW 一律收成 RUN。

**授权粒度**:文件工具链按**授权单元 = 目标路径本身**(已存在文件不提升到父目录——「新建/覆写一个文件」的授权不放大成同目录其它文件的写权限,同一路径重复访问才免弹);命令链 EXEC 根按「已存在目标所在目录」归并;动词类按规范化动词。**弹窗文案与判定同源**(不得比实际判定宽,既误导人类授权者又误导 AI 审议员);同 key 并发只弹一张卡。

**授权下发沙箱(沙箱可访问范围 = 授权范围)**:授权落定时把随附的**沙箱范围根**交给 `SandboxPathRegistry`(owner=`grants:<subjectId>`),由它跨主体聚合后**差量** grant/revoke(§7.10)。约束:

- **不放大(P5)**:只有确实存在的授权单元才下发(目录 → 目录、已存在文件 → 单文件);「新建文件」无法在请求粒度落地故不下发(该路径仍可经 file 工具通道访问),绝不悄悄放宽到父目录。
- **回收按主体生命周期**:run 档清空 / 主体驱逐 → 无人期望的根被 revoke;**跨主体共享的根在仍有主体期望时保留**;进程重启由 grants.json 重放。
- **访问语义按 key**:`p::read::` 只读,写/EXEC 读写;物理回收机制(令牌模型/ACE 撤销)见 §7.10。

**拒绝语义**:抛 `PermissionDeniedException` → 统一转「[工具执行失败]」文本回灌模型,agent 循环不中断。

### 7.9 授权决议链

授权决议链(`PermissionGate.ensureGranted` 内、发起人工弹窗前短路)采用域中性契约:决议请求 `AuthorizationRequest(ExecContext context, agentId, grantKey, prompt)`——链节点只见 ExecContext 槽位(经 `WorkerServices.slashTokens()` 判任务级 slash token 开关、`interaction()` 弹窗(subjectId 已绑定)、`agentFactory()` 建审议 agent、`emitter()` 落审计),不接触 `TaskEntry/TaskRuntime/TaskInfo`;`GrantRegistry` 按 `subjectId()` 分区授权状态、`dataDir()` 落盘 grants.json。链节点可包括人工弹窗、AI 审议、无人值守等(由各自插件贡献)。人工弹窗是链终结节点(从不 PASS):用户选「本轮运行内允许」→ ALLOW+RUN;「本任务全程允许」→ **ALLOW+TASK**(落盘 grants.json);拒绝/超时/未识别 → DENY。

### 7.10 命令沙箱(插件化,多后端)

沙箱后端已从 worker 核心抽离为独立插件。`SandboxBackend` SPI 只表达**「对沙箱做了什么」+「路径在沙箱世界里长什么样」**,不执行命令、不涉及工具注册、不做授权策略判定;核心不感知任何后端细节。各后端的具体实现细节(隔离模型、账户/令牌机制、挂载方式)见各插件自身文档。

**SandboxBackend SPI(效果 2 + 查询 2 + 标识):**

- `id()` — 后端标识(也是判「后端切换 → 整批重放」的键)。
- `grant(List<PathGrant>)` — 声明这些宿主路径在沙箱内**可访问**(建立权限 + 映射基准)。幂等、可重放;**best-effort 且不放大(§7.8 P5)**:无法在请求粒度落地时跳过并记日志,绝不放大到父目录。
- `revoke(List<Path>)` — 撤销权限与映射。幂等;**只带路径,不带任务/工作区语义**(是否还有人需要该根由上层聚合判断)。
- `toSandbox(Path)` / `toHost(String)` — **纯查询、无副作用、默认恒等**;翻译由 grant 确立的映射基准派生。

| 后端 | id | priority | 说明 |
|---|---|---|---|
| **WSL Ubuntu** | `wsl-ubuntu` | 10 | 独立插件;plugin.json 默认 enabled=false |
| **Windows Codex** | `codex` | Windows?8:0 | Windows 原生强隔离(WRITE_RESTRICTED 受限令牌 + capability SID);默认启用 |
| **DIRECT(兜底)** | `direct` | — | OsSandbox 自身,无 Provider;auto 下无任何可用后端插件时兜底直跑宿主 |

**效果与时机分离(§7.8)**:沙箱只表达效果,**何时授权/回收由上层决定**——`PermissionGate`/`GrantRegistry` 是唯一翻译点,故沙箱插件**不订阅任何领域事件**,也不需要事件总线。

**命令门禁由插件主动调用(`ToolContext.commandGate()`)**:危险动词/工作区外路径引用的授权判定归 worker(`PermissionGate.requireCommand`),但**命令串只在插件自己的执行器里**——经 `ToolContext.commandGate()` 下发窄接口 `CommandGate.authorize(command)`,后端自建执行器的插件**必须**在 spawn 前先过门禁;授权通过后 worker **同步下发沙箱范围**;拒绝 → 抛异常回灌模型,命令不执行。**不接门禁的后果**:该后端的命令永不触发授权,表现为工作区外读写被 OS 直接拒绝且从不弹窗。codex / wsl-ubuntu 已接门禁;DIRECT 走 worker 内建门禁。

**后端选择时机(时序红线)**:`SandboxProvider` 全部由插件在 `PluginLoader` 激活期注册,而构造依赖链决定了 OsSandbox 一定先于插件激活完成初始化——后端**不得在 `@PostConstruct` 一次性定论**:

- delegate 按 **`SandboxProviderRegistry` 代次(generation,每次注册/注销自增)惰性解析**:首次访问或代次变化时重新 `select()`(先 `isAvailable()` 过滤再对胜出者 `create()`),解析结果按代次缓存;
- `grant()/revoke()/toSandbox()/toHost()` 由 OsSandbox **必须转发给 delegate**(delegate 为 null 才走 SPI 默认 no-op/恒等)——门面自己吞掉会让路径翻译与权限下发整体失效。

**核心授权账本与下发适配(SandboxPathRegistry)**:worker 核心内部 `@Component`,只做两件事——**聚合账本**(按 owner 分组登记「期望可访问的宿主路径」,跨 owner 去重、读写语义覆盖只读)+ **差量下发**(并集变化时只把差量交给 `grant`/`revoke`);**不持有映射表**,路径翻译直接转发后端。要点:

- `register(owner, grants)` 追加(幂等);`sync(owner, grants)` 覆盖式对齐(差集自动撤销);`unregister(owner, path)` / `unregisterOwner(owner)`;`onWorkspaceRemoved(path)` 从全部 owner 移除该根。
- **下发只发差量**;**后端 id 变化**(插件晚注册/后端切换)即**整批重放**——同时用首次翻译查询兜底启动期时序;下发失败只 WARN 保留旧快照、下次自然重试,**不阻断任务线程**。
- `toSandboxPath(Path)` / `toHostPath(String)`:转发后端;后端异常时前者退化为原路径、后者返回 null(调用方原样交给 Java NIO 自然报错)。

**登记方(owner 分组,谁的路径谁登记):**

| owner | 登记方 | 内容 |
|---|---|---|
| `workspaces` | `WorkspaceManager` | 全部在册工作区根 + 各工作区 `externalRoots`(READ_WRITE;注册表变更单一收口点刷新) |
| `skills` | `BuiltInSkillPacks` | 系统技能目录根(READ_WRITE,§7.17) |
| `grants:<subjectId>` | `GrantRegistry` | 该主体的**单次授权根**(§7.8:授权范围 = 沙箱可访问范围;P5 不放大) |

**授权根的回收语义**:回收走账本差量——无人期望的根被 revoke;跨主体共享的根在仍有主体期望时不被回收(聚合在账本层,SPI 只带路径)。各后端物理回收机制见各插件自身文档。

**路径翻译流程**:

1. 路径提供方登记 → 差量经 `sandbox.grant()` 下发(建立权限 + 映射基准)。
2. 工具参数翻译:`FsToolSupport` 调 `pathRegistry.toHostPath()` → 转发 `sandbox.toHost()`;无映射返回 null,原样保留让 Java NIO 自然报错。
3. 非工具路径翻译(`SkillAdvisor` 知识包路径、`@` 引用)直接调 `pathRegistry.toSandboxPath()`(默认恒等)。
4. 命令组装翻译:由各后端自持(如 wsl 用挂载表映射为 `/c/...`;codex 恒等)。

**`worker.sandbox.type` 配置项**:`auto`(默认)| `wsl-ubuntu` | `none`;历史别名归一兼容(`wsl-direct`/`direct` → `wsl-ubuntu` 等)。各后端专属配置由各插件通过 plugin.json `contributes.config` 自管。

**核心宿主访问工具与沙箱命令工具共存**:

- **核心的命令工具**(DIRECT 后端):Windows 上 `powershell` 工具、Linux 上 `bash` 工具,直接 ProcessBuilder 执行,走自己的授权链;pwsh/powershell 探测与脚本管线为最小自持实现,不依赖插件。
- **沙箱插件的命令工具**:沙箱插件不只提供 `SandboxBackend`,还提供自己的 `ToolProvider`(自建 CommandExecutor、自持授权策略),经 `appliesTo(ToolContext)` 控制生效条件;两者不冲突。
- **只有某后端才做得到的隔离能力,其命令与状态一律归该插件**(如 wsl-ubuntu 的 `/禁用网络` 由该插件自己注册 slash 命令与 token 解析器,worker 核心不持有该开关)。

**网络策略(两级开关框架)**:默认放行——① 全局 `worker.sandbox.allow-network=false`;② 任务级 `/禁用网络`(只有具备断网能力的后端才提供,整个能力归对应插件自带,worker 核心不持有任何任务级禁网状态)。

**命令 stdin 契约**:AI 命令的 stdin 语义口径是**无输入可用、不可交互输入**(非交互/headless 工具的通用做法);契约按**效果**表述,实现手段各后端不同且不必相同;「不可交互」由**显式 env** 钉住(口径同 git 的 `GIT_TERMINAL_PROMPT=0`),不依赖 stdin 形态。

> **环境侧信道闸门(§7.17 凭据纪律)**:一切子进程环境构造点必须先过 `SecretPatterns.scrubEnv`(凭据指纹 + 凭据词族名双判),审计只打被删变量名、绝不打值;worker 与沙箱插件自身日志出口的凭据形态掩码走 `SecretPatterns.maskSecrets`。

### 7.11 提权拦截

wsl-bwrap 后端的 seccomp 内核级提权拦截已随 bwrap 后端删除而移除。wsl-ubuntu 后端以 root 完整权限直连,无提权授权概念。codex / DIRECT 后端通过 Restricted Token(codex=WRITE_RESTRICTED + capability)+ PermissionGate 文本扫描拦截危险命令。

### 7.12 原生 git 执行与凭证

工作区 git 快操作(`git.*`)不自己复刻 git 语义,由 `NativeGit` 调宿主**原生 git 可执行文件 argv 直传**执行(不经 shell 字符串拼接),复用 `OsSandbox` 的超时/输出上限/env 清理;**不走命令沙箱后端**——git 是前端按钮触发的受控操作(参数受控、路径被 `Sandbox` jail),降权/进发行版会引入路径映射与权限差异。

- **可执行文件定位**:启动探测一次并缓存(显式配置 > Windows 常见安装路径 > PATH 兜底);全部失败明确报错,不静默回退。
- **稳定化参数**:每命令预置 `git -C <workspace> -c color.ui=false -c core.quotepath=false --no-optional-locks --no-pager`;env 设 `GIT_TERMINAL_PROMPT=0`(缺凭证 fail-fast 不卡死)、`LC_ALL=C.UTF-8`(输出编码稳定)。
- **路径沙箱**:复用 `Sandbox` realpath 前缀 jail(§7.10);用户 path 参数先校验再进 argv。
- **commit 路径过滤**:显式 paths 先做 jail 校验,再与当前变更集求交集——已无变更的陈旧路径对本次提交是 no-op 直接跳过(避免 unmatched pathspec 整体失败);交集为空报 `BAD_PARAMS`,绝不静默回退成全量提交。
- **并发**:per-workspace 串行锁——写操作(含自动同步)同 workspace 串行,读操作可并发。
- **执行出口**:`OsSandbox.spawnNative(String[] argv, Path cwd, Map env)`(宿主原生 argv 直传);git 超时独立配置(默认长于统一命令超时)。**中断语义(不变式)**:任务取消/级联停止的 cancel 打断执行等待时,一律恢复中断标记并按「exec 被中断」收口,不得吞进通用异常分支伪装成 rc=1 普通失败(掩盖取消/挂起排查线索)。

**凭证**(`git.clone/pull/push` 共用四档解析链):

1. RPC 临时凭证(username/password,不落盘)→ 生成临时 askpass 脚本,凭证值经 env 注入(不拼 argv、不把密码写进脚本文件);
2. 本机默认凭证 → **不注入任何凭证**,git 自行走 credential.helper / credential manager / ssh-agent(静默,原生 git 开箱即用);
3. 认证失败 → 读工作区 `.everyagent/.git-credentials.enc` 该 host 条目解密后按 ① 注入重试;
4. 仍失败 → `rpc.err(AUTH_REQUIRED)` 弹凭证输入(判定 = 非零退出 **且** stderr 命中认证失败关键字,避免网络错误/远端 404 误判)。

- **加密存储**:每工作区一把 32B AES-256 主密钥;算法 AES/GCM/NoPadding,随机 IV,AAD=host 绑定条目;密文 JSON 存工作区 `.everyagent/.git-credentials.enc`,明文永不落盘。主密钥**存放形态**(防"拷走工作区目录即同时拿到密文+钥匙"):Windows 经 DPAPI(当前用户作用域)包裹为信封落盘,离开本机/本用户即不可解;非 Windows / DPAPI 不可用时外置 `<home>/git-credential-keys/`(工作区外);旧布局懒迁移(主密钥值不变、仅换存放形态)。
- 前端 Git 面板捕获 `AUTH_REQUIRED(host)` → 凭证 Modal(可勾选「保存凭证到工作区(加密)」)→ 先带临时凭证重试,成功后再 `git.credential.save` 落盘。
- 凭证仅存 worker 本机加密文件与内存,不经 hub / 前端 localStorage;协议不提供"读取凭证"RPC(save 只进不出)。
- **多远端推送**:`git.push` 与自动同步均推送到**所有**已配置远端(非仅 origin);自动同步仅从 origin 拉取、推送全远端,部分失败仍尝试其余远端;推送始终显式指定当前分支,无 upstream 时首个远端自动 `--set-upstream`。
- 自动同步(git 自动提交)保持静默:只走本机凭证 + 加密凭证,不弹窗。

### 7.13 任务流传输(混合模型:定向推送 + 拉取)

**实时增量 = worker 定向推送**(DataPusher):前端 sub `u.K.worker.<id>.task.<taskId>.stream` → hub 按频道名的 worker 段向**那一台** worker 发 `subscriber.join{sessionId,taskId}`(无 worker 段时退化为投该命名空间全部在线 worker)→ DataPusherManager 校验归属后按 (sessionId,taskId) 建推送器;推送器虚拟线程把运行中任务内存 EventLog 增量(含瞬态)经**单点出网投影器**投影后推到 stream 频道,`ext={target:sessionId, operate, initial}`(投影只作用于 payload 与是否出网,不碰 `ext`)。

- **归属自检(硬约束)**:worker 收到 join/leave 时,先比对频道名里的 worker 段与自身 `worker.worker-id`;**不相等一律丢弃,不建也不销推送器**——与 hub 的定向投递构成双保险(§14.12:非寻址那台建起推送器也收不到 ack,背压窗口永不释放,白占出站队列与虚拟线程)。
- **窗口式背压(credit + ack)**:DataPusher 维护 `nextPushIndex`/`ackedIndex`,`CREDIT_WINDOW(128)` 满即**持续真阻塞**等前端 ack(无超时降级);每帧 ext 携带 `creditIndex`;前端消费完一帧经 worker 级 input 频道回 `stream.ack{taskId,creditIndex}`,worker 只路由释放窗口。多前端窗口独立,慢端不拖累快端。
- **先订阅后首拉**:前端 `open()` 先 sub stream 再拉初始(rounds + roundTail),推送首扫与首拉重叠部分按 seq 去重吸收。
- **生命周期**:unsub/前端断连 → 销毁推送器;worker⇄hub 断链 → 清扫该连接推送器;任务再运行换新 EventLog → 换挂从头推;任务终态 → 收尾排水一次后空转。
- **降级语义**:推送非阻塞,出站队列满丢帧 + WARN(事件日志是事实源);前端慢 → hub sink 溢出断连 → 重连重拉。漏帧由前端**检测并按需拉取补齐**——每帧 `creditIndex` 在推送器会话内从 0 严格递增且链路按序投递,按帧间**跳号**即可判定该区间帧被丢弃(出网过滤不占号:被过滤事件根本不进 `push()`);检出后以 seq 开区间发起 `task.poll` 补拉并按 seq 去重折入,否则丢帧将成为会话期永久空洞。`creditIndex` **回退** = 新推送器会话(重连/visible 重订/任务再运行,从 0 重新编号),前端重置基线不误报;误报方向只会触发一次幂等补拉,无害。
- **慢消费者三道防线**:① 真背压——窗口满持续阻塞等 ack;② `CREDIT_WINDOW(128)`——多任务同屏总积压有界(N×128 < hub 出口队列 1000);③ 前端 hidden 时对全部活跃 stream 频道主动 unsub 降载(visible 重 sub + 重拉校准)——`visibilitychange` 只管订阅降载,不参与连接生死(连接生死唯一由心跳判定,§5.1)。

**`task.poll { taskId, afterSeq?, beforeSeq?, limit?, mode?('events'|'rounds'), count?, waitMs? }`** 是任务流的**统一读取 RPC**:打开首拉、上滚分页、区间拉取、重连补齐、终局补拉、长轮询全部经它完成。

- 数据源 = 磁盘窗口 ∪ 内存 EventLog 尾部,按 seq 归并、同 seq 以内存为准(磁盘侧随机访问分块反向扫描 jsonl,不整文件重扫)。
- `mode='rounds'`:从尾部定位最近 count 个轮次起点,返回覆盖完整轮次的事件段(打开/重连秒拉尾段;只按 count 定位、忽略 beforeSeq);`mode='events'`:按 afterSeq(增量)/beforeSeq(上滚)+ limit 精确窗口,同给 = 开区间查询;`waitMs>0` 无增量时挂起虚拟线程等新事件(长轮询,与 askuser 同款底座)。
- 回放只含持久事件(瞬态从未落盘):`message` 事件自带整轮 thinking + toolCalls,由它直接组装完成态。
- **出网投影 + 分页游标**(4 出网口之一):应答经 `EgressProjector` 投影后下发(§5.3);字符串字段 **`nextSeq`** = **未过滤口径**的推进游标(批尾原始 seq,避免「过滤后空批 = 取完」的误判);`hasMore` 保持 raw 语义,`firstSeq`/`lastSeq` 同为 raw 口径且**一律字符串携带**——雪花大数以 JSON 数值下发会在 JS 侧丢精度,不得再作游标或比较键。

**工作流实例事件 = 同一混合模型的广播形态**(频道 `u.<K>.worker.<id>.wf.<instanceId>.stream`,§5.2):实时增量由 workflow 插件在 events.jsonl **成功落盘后**经 `EventSink.fanout` 扇出(帧 event=实例事件名,payload 与 `workflow.instance.events` 应答条目同形);历史/断线补齐走该 RPC 拉取,前端先订阅后回放、按 seq(单实例内严格递增)去重合流。与任务流的两点刻意差异:① **无 subscriber 通知、无背压机**——实例事件是低频元数据(每节点运行数条),广播扇出(hub 只投已订阅连接)即达,不值得 DataPusher 的定向推送器与 credit 窗口;② **无出网投影**——插件事件无瞬态/过滤语义,落盘即出网。订阅恢复(重连/可见性/worker 集变化)由前端宿主统一重发并通知插件重拉校准;漏帧以磁盘事实源 + 拉取补齐(推送是无背压广播,不做跳号检测)。

### 7.14 子 Agent(进程内,模型工具)

子 Agent 采用**进程内工具**方案,不采用"每个子 agent 一个独立后端程序 + hub 协调"。理由:同套底层 AI、同模型、同沙箱、零网络协调、运行时随意 spawn;跨机分工留给 v2 舰队/编排。

**工具面**(注册给模型的工具,与前端 RPC 无关):

| 工具 | 语义 |
|---|---|
| `run_agent(input, title, agentId?)` | 异步派发子 agent;无 agentId 新建(title 必填,agentId 动态生成);传 agentId 即续跑——已落定实体(轮内)与跨轮/重启统一走**会话档案全保真重建**(见下);立即返回 agentId,需用 wait_agents 等待结果 |
| `list_agents()` | 列出本主体下全部子 agent(agentId/title/createdAt/status/latestActivity,不回灌完整历史;**含此前轮次已收口的,跨轮可见**) |
| `wait_agents(agentId?, timeoutMs?)` | 等待子 agent 完成/超时;对已收口的历史 agentId 直接返回终态摘要 |
| `stop_agent(agentId)` | 停止指定子 agent;对已收口的历史 agentId 应答「已结束,无需停止」 |

> **归属与前端**:`task.agents` RPC 属 task 域(§5.5),subagent 插件**不再提供该 RPC,也不再提供 web 前端功能**(原面板退役,agent 胶囊列表由 web 核心渲染);上表四个工具仍属 subagent 插件(执行域能力,不迁移)。

**运行语义**:

- 子 agent = 同一 ChatModel + 收窄工具集 + 独立 system prompt 的嵌套循环;虚拟线程承载。
- **上下文隔离**:子 agent 只收到 input 文本与自身 system prompt,不继承父 conversation 任何历史;信息交换唯一通道 = 下行 input、上行 `agent.done` 结果。
- **递归禁用从根源做**:子的工具集剔除全部 agent 工具 → 结构上不可能派生孙 agent(深度上限 1)。
- **父停止级联**:父任务取消 → 全部子 agent 停止。
- **收口前自动等待**:父任务结束前自动 wait 全部子 agent 聚合回灌;安全超时(默认 5 min)——主 agent 未调 wait_agents 即收口时,运行中的子 agent 被等待到自然完成,超过上限才级联停。
- 并发守卫:运行中的 agentId 再次 run_agent 报错;`wait_agents` 报 completed 前必等运行体 future 落定(早退会让紧随的续跑撞「已在运行」守卫)。

**跨轮可见性与续跑(磁盘是唯一事实源)**:子 agent 是**主体级资产,不随单轮收口消失**——历史全部落盘,进程内存零依赖。三个机制补齐跨轮:

1. **指令落盘**:`run_agent` 的每条指令(新建/复用/重建三路径)发 `user.message` 事件(带 agentId,与主 agent 任务输入同一名事件)→ `<agentId>.jsonl` 自包含(user → assistant 轮次完整),是续跑重建的依据;
2. **读侧合并**:工具(list/wait/stop)按「本轮 live 实体 → 磁盘台账 `agents.json`」两层合并;非在场实体的 running/waiting-user 一律归一 stopped(与冷启动归一同规则);
3. **中性重建**:跨轮/重启的续跑,或实体在场但已落定的轮内复用续跑,均经 plugin-api `ExecContext.conversationArchive()` **会话档案口**(角色中性:主/子/审议/未来派生 agent 一视同仁,只认 agentId)从 `<agentId>.jsonl` **全保真重建**会话种子(工具调用与结果完整保留)再续跑——`[system, ...prior, user(新)]` 形态,档案尾孤立 user 时补占位 assistant(交替不变量);新 emitter/新 ChatClient 绑定**本轮** ExecContext(复用旧实体会把事件发进已销毁的上一轮日志)。磁盘亦无档案(凭空 id/损坏)→ 同 id 全新会话;档案读失败 → 回退内存会话续跑(低保真)并 warn。**不用「指令→最终汇报」低保真回写续跑**——低保真少样本模式下,模板化密集续跑会诱发模型跳过执行直接编造完成汇报;全保真重建(工具对完整)是定稿路径,与 D18「运行即销毁、冷启动普通运行」对齐。

**展示分工**(agent 层 vs 展示层):user.message 的「主线程用户气泡/轮次开合」是**主 agent 专属语义**,判据一律是 task 层传入的固定 `mainAgentId`;折叠窗口语义归 rounds.jsonl 唯一权威(§7.15.1),前端线程项无折叠标记;wire 出网所有事件必带 payload.agentId,前端经 `isMain(agentKey) === mainAgentId` 区分:主 agent 渲染 user 气泡,派生 agent 以 trace 形态展示(内容零丢失);旧事件缺 agentId 时前端归一到 mainAgentId。协议层不为此发明新事件名,也不用「空串=主」魔法。

**事件与持久化**:子 agent 不建独立 Task,事件与主 agent 同名、以 agentId 字段嵌套在父任务流(spawn 生命周期为 agent.started/agent.done);**每个子 agent 一个独立会话文件 `<subAgentId>.jsonl`**(含 user.message 指令轮,自包含可重建);断线续播、ask(带 agentId)全部复用既有机制。子 agent 台账**独立落盘任务目录 `agents.json`**(从 meta.json 拆出;台账投影与 agents.json 读写由 worker core `AgentLedger` 承担——订阅 EventLog 的 agent 事件做投影、原子读写、冷启动恢复,§7.20.1);usage 事件载荷含本轮累计,子 agent 每轮 usage 后刷新内存台账并触发落盘。

**域中性(§7.20/§14.11)**:subagent 是**执行域能力插件**(spawn/wait/stop 是执行域能力,不是任务域能力——没有 task 只有 workflow 的执行主体同样可用)。`SubAgentManager` 构造零服务依赖,方法收 `ExecContext`,工具入口绑 `ToolContext`;per-subject 状态自持;生命周期节点壳留 task 面、逻辑取 ExecContext 槽位,工作流落地时同一核心以同构生命周期壳挂载、零新逻辑。

### 7.14.1 任务生命周期洋葱模型（Phase 1）

任务开始/结束收口采用 **洋葱模型**（Servlet Filter 风格参与式链）：节点拿到运行上下文，调用 `result = next(context)` 得到后面全部节点+内核的执行结果。`next()` 之前 = 下行（开始）阶段，之后 = 上行（结束）阶段。

- **契约**：`TaskLifecycleNode`（plugin-api）、`TaskLifecycleContext`（`extends ExecContext`）、`TaskOutcome`、`TaskKernel`——插件可贡献节点。
- **执行器**：`TaskLifecycleExecutor`——按 order 升序稳定排序折叠为嵌套链，链尾接内核；`InterruptedException`→CANCELLED 兜底。
- **临界段**：一段连续 `UpstreamNode` 共享一次 `synchronized(taskLock)`，对外表现为单一链位置；段内上行执行序 = order 降序。
- **注册表**：`TaskLifecycleRegistry`（CopyOnWriteArrayList + float 稳定排序）；临界段区间拒绝插件节点插入；注册表不感知插件禁用（禁用的插件不会被 activate）。
- **节点全集与 order 全表**：详见插件指南 `docs/plugin-guide/backend/task-and-rpc.md` §2.3（以插件指南全表为准，本文不复制清单）。要点：下行含 RPC 线程段 → `persistence.track`(100) → `task.wires`(200) → `main.agent`(390)；**轮次循环段（每轮重入，临界段内侧）** `queue.loop`(870) 包裹 `[ edit.resend(877) → consume.input(880) → 内核 runner.run(main) 一次 ]`，`cascade.stop`(900)/`spawned.await`(950) 亦在循环内侧。**轮次循环必须在临界段内侧**：若在临界段外包裹，每轮上行段会把一次性收口节点（终态/移出注册表/untrack/并发释放等）逐轮执行——任务中途被移出注册表（实时推送断流、cancel/task.poll 失效）、writer 提前关闭、并发计数重复扣减。
- **行为零变化**：重构时事件发射顺序、seq 语义、落盘内容以逐项基线表核对映射。
- **后续 Phase**：agent 层独立(Phase 2)、拦截链范式统一(Phase 3)、subagent 插件(Phase 4)、队列插件(Phase 5)。详见 `docs/design-agent-layer-onion.md`。

### 7.14.2 TaskManager 职责拆分与通信解耦（Phase 2.5）

TaskManager 构造参数从 23 降至 15（SubAgentManager 从 11 降至 4），迁出的职责与承接组件：

| 承接组件 | 层 | 职责 |
|---|---|---|
| `AgentFactory`（agent 层） | agent 层 | 主/子 agent 装配（模型/工具/沙箱/权限聚合） |
| `ConfigRpcHandler` | 基础设施 | config.get / config.reload / config.model.add / config.model.update / config.model.remove / skill.list / skill.exposure.set RPC（自注册） |
| `SlashTaskCallbacks` | 基础设施 | slash 任务 token 建后回调 |
| `EventSink`（接口） | 基础设施 | `fanout(channelNamer, event, ...)` 扇出发布——无调用者语义，HubPool 实现；task 层广播任务状态、未来工作流层广播流状态共用 |
| `TaskMessageRouter` + `TaskInputHandler` | 基础设施 | worker 输入频道订阅与消息路由（TASK_INPUT/DIALOG_INSERT/ASK_REPLY）；接口在基础设施层定义、TaskManager 实现——依赖方向与 AgentContext 相同 |
| `StreamSourceRegistry`（洋葱节点直持） | 基础设施 | PersistenceTrack/UntrackNode 直接 attach/detach 流源 |

**分层与核心边界**：工作区 → task 层/工作流层（平级编排）→ agent 层 → 基础设施层；**下层不知道上层**。`PendingAsks` 归基础设施层（askuser 交互能力），洋葱 CascadeStopNode 直持。留在 TaskManager 的 `workspaces`（任务创建注册工作区）与 `gate`（每轮授权失效 beginRun）是 task 编排活依赖。事件三条出路（落盘=TaskStore 监听 EventLog、实时推送=DataPusher 经 StreamSourceRegistry 取 EventLog 定向推、历史拉取=task.poll 磁盘窗口∪内存尾部归并；推送与拉取均经 `EgressProjector` 投影,§5.3）与广播（EventSink.fanout → hub 只投已订阅连接）对 task 层与未来工作流层完全同构。**执行上下文同构**:执行数据经 `ExecContext`(plugin-api,§14.11)显式类型化——task 层构造(`TaskEntry` 即其实现,subjectId=taskId)并预绑定 `agentFactory()`/`emitter()` 往下传,agent 层/工具链/授权链只见 ExecContext 槽位,未来工作流层实现 `WorkflowRuntime implements ExecContext` 即复用全部横切基础设施;事件管道与执行上下文管道双通道同构。

### 7.14.3 拦截链范式统一（Phase 3）

worker 的运行期责任链统一为 filter 形态（`result = next(ctx)`）：

| 链 | 形态 | 收益 |
|---|---|---|
| 工具执行拦截链 | `invoke(ctx, next)`：下行=检查/短路，上行=执行后审计 | 获得上行钩子 |
| 授权决议链 | `invoke(req, next)`：req=`AuthorizationRequest(ExecContext,agentId,grantKey,prompt)`(域中性)；不处理=proceed，决议=短路 | 获得决议后包裹 |
| PermissionGate 内部三链 | SKIP=proceed，短路=ALLOW/DENY | 与洋葱同构 |

链组装器均按 order 升序折叠为嵌套链，与 `TaskLifecycleExecutor` 同构。

- **工具执行链 per-call 化（2026 定案，替代原批级 `InterceptingToolCallingManager`——已退役删除）**：worker 持有 `PerCallToolCallingManager implements ToolCallingManager`（共享无状态单例），**照抄** Spring AI `DefaultToolCallingManager` 的工具调用循环源码（自建框架 package-private 类的等价物），在循环内插**两处切口**：下行（回调解析后、observation 前——ToolCall 全字段+ToolCallback 在手）与上行（observe 返回后、ToolResponse 构造前——结果串+配对 id 在手）。每次工具调用执行一遍插件链，链尾 = 框架原生 observation+call+异常处理器；链节点的下行段/上行段恰好各落一个切口位置。
  - **切口语义 = 方案 B（严格，写入 `ToolExecutionInterceptor` javadoc，插件契约）**：下行可改写参数（改写对真实执行与 observation 生效）、可合成当前调用结果（`ctx.synthesize(串)` 哨兵 → 链尾跳过 observation+真实调用，合成串**沿框架 ToolResponse 构造路径**流经全部上行改写节点——脱敏对合成答案照常生效；混合轮天然正确，逐调用判定）、**禁止 mid-batch break**（框架循环无批内中断，自造「N 调用 M 结果」的模型配对义务不被采纳）；上行可改写结果串（脱敏/hint）、可发事件，不可撤销已执行调用；拦截器也可直接 `return` 串不调 `next`（经典硬短路，仅限极端场景，合成一律走 `synthesize`）。
  - **per-run 数据通道（单例无状态的代价）**：per-call 链 ctx 的 ExecContext 槽位取自 **prompt options 的框架 `ToolContext` map**（`AgentRunner` 组装 prompt 时下行 `RunContext` 四件套：agentId + agent 级 emitter + `ExecContext` + `ToolOutcomeLog`）——框架原生通道，`buildToolContext` 原样透传给工具回调；map 无该键（单测/裸用）时切口退化为纯框架路径。**ThreadLocal 依旧禁用**（reactive 流的工具执行可能切换线程）。
  - **internal 依赖策略**：照抄块唯一抄不掉的 internal 依赖收敛到唯一包装类（不用反射；internal 类变动 = 编译红灯显性可见）。
  - **红线豁免记录（用户确认）**：照抄框架循环体+插切口 = 自持循环的**轻量形态**（类注释标明来源版本，Spring AI 升级时 diff 框架源码对齐），豁免「不得手搓工具调用循环」红线；豁免范围仅限该照抄块。
  - **装配**：worker 配置直接 `new PerCallToolCallingManager(...)`（异常处理器/回调解析器/limits 显式传构造，行为逐位保真；内置 `ToolArgumentsGuardInterceptor` 与 `ToolEventInterceptor` 同处注册）；`AgentBuilder` 装配简化为 `LoopRepeatGuardToolManager(PerCall)` 两层装饰。
  - **现役链序**（order 升序 = 下行执行序，上行逆序）：`rg-hint`(0，最外层，上行最后——看到全部改写后的最终文本)→ `tool-args-guard`(50，参数护栏：对照 `ToolDefinition.inputSchema()` 的 `required` 清单，必填缺失或为 JSON null 一律 `synthesize` 失败串回灌模型、跳过真实调用，并附「疑似改名」提示；多余键只提示不拦；**不变式：必填参数缺失绝不进入工具**——Spring AI 2.0.1 的 `MethodToolCallback` 对缺参零校验、形参以 null 直入，框架升级后此护栏仍须保留)→ `unattended`(100，ask_user 合成)→ `file-change`(400，上行一阶段记账，§7.15.2)→ `tool-events`(500，发射点：`tool.started` 下行瞬态 + `tool.result` 上行落盘/callId 写账；在 secret-redaction 外层故结果串已是掩码后文本)→ `secret-redaction`(900，最内层结果改写，上行第一个拿到原始串)→ 链尾（框架执行/合成短路）。
- **`LoopRepeatGuardToolManager` 保持装饰器（零改动）**：核心守卫不是插件扩展点，且需在 `executeToolCalls` 处合成工具结果回传模型，与插件链职责不同；死循环检测语义单位 = 轮不是调用，批级挂载对其正确粒度（有意不做 per-call）。已知边界：守卫 WARN 轮的合成提醒不经 per-call 链 → 无 `tool.started`/`tool.result` 事件（前端调用行停留无结果态）。
- **行为零变化**：短路/放行/兜底语义逐项等价（迁移映射表见当时提交）。

### 7.14.4 任务队列插件（Phase 5）

任务队列插件将「并发上限即拒 ERR_BUSY」语义替换为「排队等待」语义。插件实现 `EveryAgentPlugin.activate(WorkerPluginContext)`，在 activate 里经 `ctx.register*` 注册（全仓插件源码零 `@Component`，插件由 `URLClassLoader` 加载、非 Spring 托管）。

- **`QueueAdmissionNode`**（洋葱下行段，形态三 try/finally 成对节点）：落在 RPC 线程段之后、`taskentry.create` 之前（31 节点全表见插件指南 §2.3）。下行段 `acquire(taskId)` 获取运行许可（公平 `Semaphore`，permits=maxConcurrentTasks），并发满时虚拟线程 park 阻塞（零线程开销）；finally 段 `release` 释放许可并唤醒下一个等待者；下行抛异常时 release 不执行（未进入不收口语义）。
- **`TaskAdmissionPolicy` SPI**（plugin-api）：RPC 边缘预检扩展点。队列插件注册 always-admit 策略后，`task.run` 不再硬拒绝 ERR_BUSY，而是放任务进洋葱由 `QueueAdmissionNode` 排队处理；无注册策略时保持原有行为。
- **`task.queued` 事件**（tasks 频道）与 **`task.queueList` RPC**：排队状态广播（`{queueLength, queue:[taskId...]}`）与快照拉取，前端据此渲染排队状态。
- worker pom 依赖 task-queue 模块（随 worker 打包）。

### 7.15 持久化与磁盘布局

```
~/.everyagent/                       # <home>(EVERYAGENT_HOME 可覆盖;docker 挂卷)
├─ defaultworkspace/                 # 默认工作区根(原 workspace/ 改名,自动注册 id=defaultworkspace)
│   └─ .everyagent/                  # 工作区级 git 凭证加密存储(密文 .git-credentials.enc + 主密钥信封 .git-credential.key:Windows 为 DPAPI 用户态信封,其余平台外置 <home>/git-credential-keys/,§7.12)
├─ workspaces/
│   ├─ workspaces.json               # 唯一工作区注册表 {id, root, addedTs, lastActivityTs?, externalRoots?}(默认工作区也在册)
│   ├─ defaultworkspace/tasks/<taskId>/      # 默认工作区任务目录;永久保留
│   │   ├─ meta.json                 # TaskSummary(含 workspaceId、最近一轮上下文用量、任务级开关)+ mainAgentId
│   │   ├─ agents.json               # 子 agent 台账 {agents:[...]}(从 meta.json 拆出独立落盘,减轻任务列表数据;含累计 usage 与最近一轮 context 快照 + metadata.creator + exposedTools(skill 工具自动暴露回灌,§7.14,旧前端 must-ignore);临时文件 + 原子 move 写入,空台账删文件;worker core AgentLedger 事件投影 + 自持 IO,§7.20.1)
│   │   ├─ <subAgentId>.jsonl        # 子 agent 独立会话(含 user.message 指令轮,自包含;续跑经 ExecContext.conversationArchive() 按 agentId 中性重建,§7.14)
│   │   ├─ grants.json               # task 档授权 {taskGrants, extraRoots}(§7.8,首次授权时原子写)
│   │   ├─ <mainAgentId>.jsonl       # 主 agent 会话 + 任务级事件
│   │   ├─ rounds.jsonl              # 轮次索引(§7.15.1)
│   │   └─ file-changes/<roundId>.json   # 单轮文件变更记录(经 task.fileChanges 拉取)
│   ├─ w_xxxxx/tasks/<taskId>/       # 其它工作区任务目录(结构同默认工作区)
│   └─ <workspaceId>/workflows/      # 工作流插件自持数据(与 tasks/ 平级;工作区即隔离边界,RPC 写操作必带 workspace、root 运行时经注册表解析,同 task 域纪律)
│       ├─ definitions/<wf_id>/definition.json     # 工作流定义
│       └─ instances/<wi_id>/                      # 一次运行 = 一个实例(自包含、可独立回放;实例持定义深拷贝快照)
│           ├─ meta.json / events.jsonl / runs/   # 实例元数据(含 originTaskId 关联锚点) / 实例事件日志(追加写) / 每次节点运行一文件
│           └─ (agents/ 已移除:AI 节点会话单写 origin 任务域 tasks/<taskId>/<agentId>.jsonl,§7.20.8)
├─ sandbox/                          # 沙箱持久状态(home/opt/usr-local/resolv.conf/env;worker.sandbox.persistent-root 可覆盖)
│   └─ distro/                       # WSL 托管发行版 rootfs(原 wsl/distro 迁入;运行期状态,可整体重装)
├─ skill-exposure.json             # skill 按工作区暴露配置(黑白名单并存,黑名单优先) {"<skillId>":{"blacklist":["<workspaceRoot>",...],"whitelist":["<workspaceRoot>",...]}}(身份键=工作区根路径;缺文件=空表=全部暴露,§7.17)
└─ skills/                           # skill 目录(唯一真相源,一目录一 skill,目录下必有带 frontmatter 的 skill.md,§7.17)
    ├─ agent-dispatch/skill.md       #   插件包(subagent 插件 plugin.json 声明 skills,PluginLoader 加载时统一物化)
    ├─ plan/skill.md                 #   内置包(worker 启动时经 SkillMaterializer 从 builtin-skill/ 根物化)
    └─ <user-skill>/skill.md         #   用户自制(直接放置,可附带 scripts/ 等由 skill.md 引用)
```

**旧布局迁移**:`data/` 与 `wsl/` 目录已彻底删除;存量旧数据由独立迁移脚本手动执行一次迁移——`python3 scripts/migrate-workspaces.py [--home <EVERYAGENT_HOME>]`(幂等、可重试,不随 worker 启动自动跑;迁移逻辑为 Python,不依赖 mvn 打包)。

**五项持久化规则**(实现定死):

1. **完成态 `message` 落盘**(主/子同名):整轮思考(thinking)+ 正文(text)+ 工具调用下发(toolCalls,真实模型 toolCall id;缺失兜底生成且三处一致)。工具返回**单独**落 `tool.result`。
2. **瞬态不落盘**:`delta`/`thinking` 只发前端,消耗 seq 但不写盘(磁盘回放有洞的来源)。
3. **ext 为 null 不写行**:jsonl 行 = `{seq, ts, event, agentId, payload[, ext]}`。
4. **jsonl 每行必记 agentId**(文件内字段;线上 wire 主 agent payload 不带)。
5. **按 agent 分文件**:每 agent 一个 `<agentId>.jsonl`;读取按 seq 归并全部文件。

#### 7.15.1 轮次索引 rounds.jsonl

主 agent 侧生成轮次索引（每行一轮：用户输入 → 主 agent 最终回复）：

- 行格式：`{index, roundId, startSeq, endSeq, user, finalReply, durationMs, startedAt, agentRanges, userMessage}`（**轮行不含任何插件业务字段**，文件变更等按轮旁路数据由插件自持文件 + 插件 RPC 提供，§7.15.2）；seq 一律字符串；`endSeq=""` = 未闭合轮；`roundId` = 稳定主键（开轮落盘即生成、闭合原位改写**沿用不重生成**；旧数据行缺失不写，must-ignore）；`startedAt` = 开轮落盘时刻（epoch 毫秒），`durationMs` = **最终回复事件时间 − startedAt**（跨运行延续、迟到补闭合均取轮的真实终点，不把补写前的等待算进耗时；事件时间不可得时回退闭合时刻，旧行无 `startedAt` 保持 0）；`userMessage` = 完整 user.message payload（渲染投影素材），**形状必须与 user.message 事件 payload 一致**（`{content, data:{rawContent?}}`，两条落盘路径同形）——前端按 `rawContent` 合成 user 气泡回放 @文件胶囊（rounds 通道只做渲染投影，不进前端数据层，§8.3）。
- 增量写：消费用户输入即落一行 `endSeq=""`；主 agent 最终回复后原位改写闭合（临时文件 + 原子 move，与追加同锁串行）。**`durationMs` 随闭合行同一次落盘内联写入；`round.closed` 通知在闭合行落盘之后推送**（不变式：前端收到通知拉 `task.rounds` 时耗时必已就位；两段写存在竞态——先推送后回填会让前端拿到 `durationMs=0` 且无刷新触发）。
- **事件是轮次权威，索引行只是缓存**：闭合落盘是"有事发生时顺手补写"（每轮最终回复后 / 开轮前 / 任务终态收口），任何一次都可能因取消、异常、时序而漏做——因此**开轮决策与"当前有没有开着的轮"一律以事件扫描为准**：尾行未闭合时先按事件对账一次（幂等），真未闭合（中间输入/续跑）才沿用，否则补闭合后正常开新轮（否则该输入及其后全部事件落在任何轮区间之外 → 整轮不可见，前端只剩上一轮已闭合态）；任务终态收口再对账一次（校准点，仍真未闭合的轮保持 `endSeq=""`，由前端按"终态未闭合尾轮"常开视图渲染）。
- 旧任务首次 `task.rounds` 惰性全量生成落盘；中断/失败/取消的未闭合轮自然保留（不伪造最终回复）。
- 消息编辑重发（`truncateAfterSeq`）：事件日志按 `seq >= editSeq` 截断重写；rounds.jsonl 同步截断为 `startSeq < editSeq` 的行（编辑点之前的轮次保留，新轮 index 顺延）。**事件文件重写只针对 agent 事件日志（`<agentId>.jsonl`）**——任务目录下的 `rounds.jsonl`/`queue.jsonl` 不属事件空间（行无 seq 字段），误入事件重写会被整文件清空。
- 前端"双击打开任务" = 拉 meta → 一次 `task.rounds` 渲染折叠轮次 → 展开按 seq 区间懒加载过程内容。

#### 7.15.2 文件变更(file changes)

每一轮 agent 执行中对工作区的文件写操作由插件自管(task 核心零 fileChanges 概念,§14.11)。worker 文件工具(`FileTools`)在写盘成功后回调 `FileWriteListener`(plugin-api SPI;插件经 `WorkerServices.addFileWriteListener` 注册),回调点在写锁内、落盘之后。落盘按 roundId 写 `file-changes/<roundId>.json`,读侧走插件自注册的 `task.fileChanges` RPC。直推走 `plugin.*` 前缀瞬态事件,宿主 `taskStream` 一律透传 `plugin-task-event` 通用领域事件,不折入线程。**降级不变式:直推是瞬态帧、允许错过,但错过的轮不允许在会话内永久缺失**——插件 web 端缓存首拉(`task.fileChanges` 全量)兜底之外,已闭合轮的面板挂载发现缺数据时必须节流强制重拉自愈(磁盘分片是事实源),不得把找回寄托于整页刷新。

轮末「文件变更」行打开的核心 diff 标签(FileDiffPanel)在轮快照之上提供**回滚**:整体回滚 = `fs.replaceRaw(oldContent=afterContent, newContent=beforeContent)`(created 变更加 `deleteWhenEmpty` 回滚为删除文件);单块回滚 = 该变更块新文本→旧文本,定位不唯一时前端自动带上下文行扩位重试(§5.4 fs.replaceRaw 行)。回滚是**内容定位替换**,不依赖行号;文件在差异生成后被其他修改 → 定位不再命中 → 回滚失败。

### 7.16 数据模型(完整)

**两个基础决策**:

1. **双事实源分离**:`Event`(不可变,磁盘 jsonl)是传输/回放/审计的事实源;`conversation: Message[]` 是 LLM 工作态,随运行销毁,再运行时由 ConversationLoader 重建。**上下文压缩只改写发送给模型的视图,不触碰两条事实源**。
2. **用户输入入日志**:输入被消费时追加 `user.message` 事件——否则重开后前端无法还原用户说过什么,其他前端也看不到。

**实体总览**:

```
worker(进程)
├─ WorkerConfig:workerId、hubs[{url,apiKey,hubKey}]、homeDir(系统目录)、skillsDir、workspaceRoot(默认 <home>/defaultworkspace)、
│              limits{maxConcurrentTasks(20)、maxConcurrentSubs、askTimeoutMs(30min)、subWaitTimeoutMs(5min)、
│              maxEventsPerTask(50万)、context 系列、maxRepeatedToolRounds(3)}
├─ ModelConfig[]:configId、provider、baseUrl、model、params、default   ← Spring 配置承载(§7.17)
├─ Task[](运行中,内存驻留;终态即销毁)
│   ├─ EventLog(内存)+ → 磁盘 *.jsonl(按 agent 分文件)
│   ├─ Input[](串行输入队列,待消费快照 pendingInputs)
│   ├─ main Agent(唯一)
│   │    ├─ conversation: Message[] ← LLM 工作态(运行结束销毁;再运行重建)
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

**斜杠命令与任务级开关**:斜杠命令由 worker 动态注册(`slash.list`/`select`/`cancel`);任选中可返回多个联动结果(如 `/无人值守` 一次返回「无人值守」+「AI 审议」两个胶囊);任务级 token(模型池、AI 审议、无人值守、禁用网络等)的**唯一真相源**是任务目录下的 `slash-tokens.json`(斜杠层自管存储,恒以磁盘为准,不写任务 `metadata`);所有判定方一律经只读查询口 `SlashTokenService`(`tokensOf`/`hasKind`,经 `WorkerServices.slashTokens()` 获取)查询。

**composer token(opaque token)双轨与 worker 解析**:输入框胶囊由前端构造为 inline opaque token(`[[[[agent-token::::<kind>||||label/summary/payload…]]]]`,4 连符号定界零转义);提交走**双轨**——`text` 为人类可读明文,原始 token 串随 `Input.rawContent` 上行(重开/回放按 rawContent 还原胶囊)。worker 侧在 user 消息进入模型前扫描正文、按 kind 分发解析为提交文本(未知 kind/解析失败保留原串)。**嵌套语义**:一个 token 的 `label`/`payload` 可能内嵌另一个完整 token 串(如「等待任务」胶囊 label 嵌目标任务标题),全链路(识别、字段切分、渲染切分)统一按**深度平衡**配对(`[[[[` 计深度+1、`]]]]` 计 -1,边界 = 深度归零处;顶层字段切分只在深度 0 生效);公共扫描口 Java 侧 `SlashTokenEncoder.scanTokens`、TS 侧 `composerOpaqueToken.ts`(一律复用,禁自写 indexOf/正则配对);纯文本展示一律先拍平,胶囊展示由共享组件渲染(插件经 `ctx.ui` 取宿主注入的同款能力,api 包零运行时代码)。

**`@` 弹窗外部文件引用(kind=`system.external_file`)**:payload `{absolutePath, fileName, kind:"file"|"directory"}`;前端不解析该 token,提交原串上行(复用 slash token 通路),worker 统一解析:① realpath 不存在 → 替换为失效提示;② 落在工作区内 → 退化为「工作区相对路径」明文;③ 工作区外 → 注册为该工作区外部授权根(§7.17)并替换为绝对路径文本。

**@ 文件引用回归纯路径文本（无扩展分发）**:任何文件类型的 @ 引用（工作区相对路径/外部绝对路径）只保留路径文本进用户消息，不做插件类型分发；AI 需要内容时调用 read_file（文本=核心默认，图片等=`FileReadHandler` 按扩展名接管，见下段）；前端粘贴图片本就走「落盘 .everyagent/attachments + @ 引用」链路，自然适配。

**read_file 扩展名接管(`FileReadHandler`,插件 SPI)**:核心 `read_file` 对文本文件提供默认支持;插件可按扩展名注册处理器接管 `read_file`(如 image-vision 接管 .png/.jpg 等图片类型)。核心**先行授权解析**——按 read_file 既有安全模型(`PermissionGate` READ 档授权 + 工作区沙箱 jail + realpath 校验)把 AI 传入路径解析为宿主绝对路径后才交给处理器,插件无法绕过授权;处理器返回工具结果文本 + 附件(`{type:"image", dataUrl, fileName, mimeType}`):文本直接回灌模型,附件合并进任务 `metadata.attachments`(随 meta.json 持久化、跨轮注入),由 `FileAttachmentAdvisor` 注入模型——AI 在工具结果后的下一次模型调用即可看到图片(附件按全等去重,同一图片读两次不重复注入);处理器返回 null 回落核心默认文本读取(行为与接管机制引入前一致);未来 PDF 等类型复用同一 SPI。

**队列输入与「插入到当前对话」**:任务运行中输入入队(pendingInputs 经 `task.updated` 广播外显、`task.queueSnapshot` RPC 拉取——插件自持数据源,公共类型不携带插件专有字段;可 `task.queueRemove`/`task.queueMove` 管理,消费一项即广播刷新);悬空队列(终态未消费项)随 queue.jsonl 持久化、再运行时恢复。「插入到当前对话」走 `task.run{taskId, input, metadata:{insert:true, index}}`(队列插件在消费该 metadata 时**先按 index 从输入队列摘掉该项**——插入即消费,不摘则面板原地不动且会被当新一轮输入重复提交;摘到的队列项带 rawContent,非前端回传裸文本),`DialogInsertAdvisor` 随下一轮工具结果以纯文本 role=user 提交给 AI + 发 user.message(payload 形状与 consumeInput 同构)并进 agent conversation;advisor **按 taskId 现取插入队列**,不在构造期缓存引用;本 run 再无工具循环下行时机而未 drain 的插入项由 `queue.loop` 回收进输入队列、随 queue.jsonl 落盘为悬空队列,下次运行作普通轮次消费——**用户输入不丢**。队列项「编辑」= 回填输入框 + 摘除该项**两步都必须发生**(只摘不回填等于删掉这条输入)。

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
Task:   (无) → running ⇄ waiting-user → done|failed|cancelled;终态 --task.run{taskId}--> running(冷启动普通运行)
Agent:  (main) running ⇄ waiting-user → done | failed | stopped(级联)
        (sub)  spawned → running ⇄ waiting-user → done | failed | stopped
Ask:    pending → answered | timeout | cancelled
Input:  queued → consumed | discarded(任务取消)
```

### 7.17 系统目录、配置与工作区

**系统目录 `~/.everyagent/`**(`EVERYAGENT_HOME` 可覆盖):worker 的机器级状态,与工作区无关。

| 路径 | 内容 |
|---|---|
| `application-worker.yaml` / `application-hub.yaml` / `application-dev.yaml` | 【可选】用户配置覆盖(worker/hub 存在才生效;dev 供 IDEA 显式指定) |
| `skills/` | skill 目录(**唯一真相源**,一目录一 skill,目录下必有带 frontmatter 的 `skill.md`;详见下文) |
| `skill-exposure.json` | skill 按工作区暴露配置(黑白名单并存,黑名单优先;身份键 = 工作区根路径;缺文件/解析失败回退空表 = 全部暴露;`AtomicFiles` 原子写) |
| `defaultworkspace/` | 默认工作区根(自动注册 id=defaultworkspace,始终在册;内含 `.everyagent/` 工作区级 git 凭证加密存储,§7.12) |
| `workspaces/` | 唯一工作区注册表 `workspaces.json` + 按工作区归类的任务数据 |
| `sandbox/` | 沙箱持久状态(`worker.sandbox.persistent-root` 可覆盖);内含 `distro/` = WSL 托管发行版 rootfs(运行期状态,可整体重装) |

**配置分层**:进程配置 = jar 内 `application.yml` 默认 + `~/.everyagent/application-*.yaml` 用户覆盖(worker 经 `spring.config.import` 自动加载;桌面版另经 `--spring.config.additional-location` 注入)。**`worker.hubs` 多来源合并**:用户文件显式定义了 `worker.hubs` 时,非回环条目一律以用户文件为准(高优先级来源的过期快照不得经 Spring 列表跨文件覆盖语义吞掉用户最新配置);用户文件未定义时维持整表覆盖语义。**模型配置由 `worker.models`(Spring 配置)承载**,真实 key 只写用户覆盖文件(机器级、不进工作区、不进 jar/git、不进事件日志)。**搜索限制由 `worker.search.*` 承载**(键清单见插件指南)。

**凭据防护分三块**:①**内容输出掩码**由插件在 per-call 工具执行拦截链上行段实现,核心不持有内容脱敏逻辑;②**env 继承剔除**住在 plugin-api `SecretPatterns`,一切子进程环境构造点先过 `scrubEnv`(不属于任何插件、不随插件装卸而失效);③**日志出口形态掩码**(`maskSecrets`)是常驻纪律,审计只打被删变量名、绝不打值。

**装载前置(易踩)**:内置插件不进根 reactor;部署 worker 时会先 `python scripts/build-plugins.py` 构建全部内置插件再随 release 上传(服务端 worker 才有内置插件;`--skip-plugins` 可关);**只部署 webapp 不碰插件行为**(插件 web 产物由 worker 经 `plugin.webSource` 从它自己磁盘上的插件目录读,改插件必须重新部署/重打包 worker 侧)。**重建全部插件的统一入口是 `python scripts/build-plugins.py`**(动态扫描插件清单,新增插件自动纳入;单改一个插件也可单独 mvn package)。**改内置插件后必须 `clean package`**:`target/` 下残留旧版本 jar 会按字典序排前**遮蔽新类**——表现为"已打包、已重启,改动却毫无效果";旧 jar 被运行中的 worker 锁定时须先停 worker 再构建。

**程序附属文件**:核心附属文件放**程序根 `<程序根>/runtime/`**(程序根 = JVM 工作目录 user.dir;随安装包分发、运行时只读引用,不打进 jar、不写入系统目录);`worker.program-dir` 用于打包态显式指定。插件附属文件由各插件以**插件根 `runtime/` 子目录**声明,desktop 构建链把已启用插件的该目录合并进共享 `runtime/`;插件运行期经 `WorkerConfig.resolveRuntimeDir()` 以契约方式定位,插件目录内同名资源优先。

**多工作区并行**:注册表条目 `{id, root, addedTs, lastActivityTs?, externalRoots?}`,引入**稳定 workspaceId**——默认工作区 id 恒为 `defaultworkspace`(始终在册、不可移除);其它工作区首次注册生成 `w_xxxxx` 短 id 落盘不变。`fs.*`/`git.*`/`task.run`(新建)每次调用**必带 `workspace` 参数**,沙箱根在调用时按该参数解析;注册表变化广播 `workspaces.changed`(快照含 `id`/`addedAt`/`lastActivityAt`/缺失标记);写操作广播 `fs.changed`,前端按工作区分组刷新。**任务收口按「最后活动时间」倒序渲染**:收口路径刷新任务挂靠工作区的 `lastActivityTs` 并广播,前端工作区排序「最近活动的在最上面」。

**工作区外部授权根(externalRoots)**:工作区条目的 `externalRoots` 字段(realpath 规范化路径数组)承载用户经 `@` 弹窗 `+` 图标显式选择的工作区外路径,授权语义 = **完全读写(READ+WRITE+EXEC)**——「用户显式选择=已授权」(§7.8 两链同源消费,各沙箱后端按 §7.10 消费)。**注册规则**:目录=自身、文件=父目录;去重与包含吸收(新根被已有根包含 → 跳过,已有根被新根包含 → 替换);过宽根拒收(盘根、工作区祖先/工作区自身)。**生命周期为工作区级**(跟工作区走,非任务级);删除工作区时级联清理该工作区**独有**的根(best-effort,失败仅 WARN 不阻塞删除)。

**启动自检(工作区被移动/删除)**:worker 启动时校验注册表载入的目录,缺失者在快照标记 `missing` 并广播,前端弹窗二选一——`resolveMissing {action:"delete"}` 删除注册并**级联删该工作区整个任务数据目录**,或 `{action:"redirect",newRoot}` 纠正到新目录(**保留 `id`、只改 `root` 并迁移挂靠任务的 meta.workspace,任务目录不搬**);默认工作区不可删除、只可纠正;未落定的缺失工作区 `resolve` 拒绝(避免沙箱挂载失败或静默新建空目录掩盖数据丢失)。

**skill 目录结构与唯一真相源**:skill 的唯一真相源 = 系统技能目录 `skillsDir` 一路——每个 skill = 一个一级子目录 + 目录下的 `skill.md`;**不存在代码注册通道**:内置包(住 classpath `builtin-skill/<id>/skill.md`,worker 启动时物化)与插件包(plugin.json 声明 `"skills": "skill"`、文件放插件根 `skill/<id>/skill.md`,PluginLoader 统一物化;纯 web 插件也能带)只是**安装方式不同**,经 plugin-api `SkillMaterializer` 物化落盘后与用户自制 skill 完全同权,统一由目录扫描进入 prompt 与 `/` 菜单。物化语义:幂等(与源内容逐字节一致则零写入,判据是内容而非文件大小)、原子写、清理旧扁平残留;发现侧**非法布局不静默**(目录名不合 id 规则打 ERROR 后跳过——「加了包却没出现」有可归因日志);资源缺失/IO 失败 WARN 降级不阻断启动(代价:该 skill 整体消失,重启重试复原)。

- **frontmatter(`name`/`description` 展示 + `tools` 绑定)**:`skill.md` 首部允许 `---` 定界元数据块,最小 `key: value` 子集(**不引入 YAML 依赖**);已知键 `name`(展示标题,缺省回落目录名,不做一致性校验)与 `description`(一句话「何时用」,缺省回落首个非空正文行)只影响展示、不参与注入判定;`tools`(工具名列表)声明本 skill 绑定的 hidden 工具——模型读取知识包后自动进请求(§7.3);暴露控制不在 frontmatter。
- **id 规则**:目录名即 skill id,`[a-z0-9][a-z0-9-]*`;realpath 必须仍在 `skillsDir` 内(拒绝符号链接越界)。
- **目录扫描(首次懒加载缓存)**:`SkillCatalog` 首次 `scan()` 实扫并缓存,此后只读内存快照;skill 目录变更需重启生效(低频运维,可接受),**唯一例外**:`skill.import` 成功后显式失效缓存重扫。
- **暴露控制(黑白名单并存,黑名单优先,默认全部暴露)**:**黑名单** = 列出的工作区不暴露;**白名单**非空 = 仅列出的工作区暴露;同时命中按黑名单处理;两个清单都空 = 恢复默认(删除键)。`/` 菜单不受影响(永远全量);配置随 worker 而非全局(换 worker 需重配);旧格式自动迁移。
- **prompt 注入与菜单(同一份数据)**:`SkillAdvisor` **per-run 装配**(per-run 取 workspaceRoot 现取暴露配置),注入集合 = 目录扫描按当前工作区暴露配置过滤(**无配置 = 全部注入**;workspaceRoot 缺失时宁多勿漏);知识包路径按当前沙箱后端翻译注入(WSL 系翻译为沙箱内路径,非 WSL 原样);索引尾部默认声明**知识包文档化工具可直接调用,即使未出现在随请求下发的工具列表中**(工具全集默认不随请求下发——frontmatter `tools` 绑定者读取知识包后自动进请求,§7.3;平台级默认声明,不依赖各 skill 文档自行说明);`/` 菜单同一份 `SkillCatalog.scan()` 全量列出。**「一次目录扫描 = prompt 与菜单的同一份数据」是本节核心不变量**:不存在「对模型可见但对用户不可见」(或反之)的 skill。子 agent 同规则注入(与主 agent 共用执行入口)。
- **导入/导出**:zip 是**跨 worker/跨机器的迁移格式**(每 skill 一个一级目录,与 skillsDir 布局同构);导出不含 `skill-exposure.json`(身份键是本机工作区根路径,跨机无意义);导入成功即失效缓存重扫(无需重启);同 id 的内置/插件 skill 被覆盖后,重启/插件重载仍按源内容重物化。
- **不兼容变更**:skill 代码注册通道删除;第三方插件需把 skill 文件搬到插件根 `skill/<id>/skill.md`、在 plugin.json 声明 `"skills": "skill"`、删除 `activate()` 里的物化调用。
- **明确不做**:目录监听/watch、脚本注册为独立 AI 工具(一律由 AI 用对应解释器执行——bash/python/node 等不限语言,复用现有沙箱与 PermissionGate)、完整 YAML 语法支持、跨 worker 全局暴露配置。

### 7.18 内嵌终端(term.*)

文件树目录右键「在终端中打开」→ 前端主区开 xterm.js 内嵌终端标签页,worker 用**真 PTY** 拉起交互式 shell,cwd 为右键目录;输出经频道推送、输入走 RPC(§5.5)。

- **PTY 实现**:基于 **pty4j**(worker 依赖,跨平台)——Windows 走 ConPTY、Unix/macOS 走 openpty 族;worker 侧 `TerminalPty`/`TerminalPtyFactory` 只做薄封装(复用现成框架,不自研 JNA 原生绑定)。
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

#### 7.19.3 Advisor 插件化

模型限流、token 估算校准、上下文压缩、瞬时错误重试、空响应重试、模型输出预算护栏等能力均以 `AdvisorProvider` 形式注入 Agent 执行链(§7.3),由各自插件贡献。插件 Advisor 经 `AdvisorContext extends ExecContext` 直读槽位(§7.20),不耦合 `TaskEntry`/`TaskRuntime` 等任务域类型。`TokenEstimator` SPI 接口留 plugin-api(只含 `estimate` 一个方法),worker 保留 fallback `SimpleTokenEstimator`(无校准兜底)。插件未加载时对应 Advisor 缺席 → 直通,不影响运行。

#### 7.19.4 后续迁移路径

EventEmitter 是通用出口,后续把 `AgentEventChannel` 的 30 个具体方法逐步迁移到 `emit`:

| Phase | 内容 | 状态 |
|---|---|---|
| Phase 1 | EventEmitter 接口 + WebSocketEmitter + Advisor 插件化 | ✅ 已完成 |
| Phase 2 | 容灾切换 → `emit(TraceData)` + `ChatModelEnhancer` SPI | ✅ 已完成 |
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
- `EnhancerContext` 提供 `resolveMember(String configId)` → `MemberSpec | null`(不存在返回 null,供插件跳过+告警)和 `buildMember(MemberSpec)` 回调,让插件复用 `ChatModelFactory.build()` 构建单个成员模型。`poolConfig()` 返回原始配置(`model` 字段即逗号串等插件私有格式,未解析),成员格式解析/校验/组合策略全归插件。
- `EnhancedChatModel` 返回组合 `ChatModel` + 主成员 `ChatOptions`。

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
工具执行链（PerCallToolCallingManager 共享单例，per-run 数据经 prompt options 的
  框架 ToolContext map 下行 RunContext(agentId + agent emitter + ExecContext + ToolOutcomeLog)，§7.14.3）
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
| `metadata()` | 持久数据槽（随 meta.json 落盘，如 `attachments`；**任务级开关**（AI 审议/无人值守/禁用网络）不走 metadata，其真相源是 `slash-tokens.json`，经 `WorkerServices.slashTokens()` 查询） | `t.metadata()` | 文件附件注入（FileReadHandler 产出/FileAttachmentAdvisor 注入） |
| `dataDir()` | 数据目录（grants.json/agents.json 落盘；今天=任务数据目录） | `t.taskDir()` | GrantRegistry、AgentLedger |
| `terminal()` | 主体是否已收口（leak-guard：终态后不再发射事件） | `t.status.terminal()` | WorkerToolEventAdvisor |
| `interaction()` | 已绑定主体的用户交互口（静态代理，ask 的 context map 自动填 subjectId；替代手动组装 `Map.of("taskId",...)`） | 手动组装 context map | Human 授权节点、ask-user 插件（AskUserTool） |
| `agents()` | 主体活动 agent 注册表（可读写 Map：主 agent + 各插件派生——子 agent、审议 agent；**由 `AgentBuilder.build()` 自动填充（只 put；agent.* 生命周期事件由 advisor 链 + `AgentEntity` 统一发射，§7.20.1），插件不再手动 put/emit**；无子 agent 是正常形态） | `t.agents()` | SubAgentManager（复用判定）、AiAuthReviewer（复用判定）、AgentLedger（台账投影） |

**不进 ExecContext 的槽位**：fileChanges 曾是「留 TaskRuntime 的插件功能槽」，现已彻底退役出核心接口（连 TaskRuntime 也不留，§7.15.2）——collector 由 file-change provider 维护任务级共享实例（主/子 agent 经 per-call 链上行节点共写，§7.15.2），按轮落盘靠 `RoundClosedListener` 回调，读侧靠插件自注册的 `task.fileChanges` RPC；status 完整状态/touch 等任务操作同为任务域私有，横切层只需要 `terminal()`。

**agent 层 per-run 生命周期状态机 + 统一台账（`AgentContext` 槽位，非 ExecContext）**：

- **状态机与发射单点**：agent 级生命周期事件（`agent.started` / `agent.status` / `agent.done` / agent 级 `error`）由 worker `AgentEntity`（状态持有者）**一处发射**，触发源在别处、零手搓事件：
  - `AgentStatusAdvisor`（worker agent 层，`StreamAdvisor`，order=`HIGHEST_PRECEDENCE+5`=全链最外层）把 ChatClient 流生命周期信号翻译成契约方法——`adviseStream` 入口 → `AgentContext.beginRun()`（started + running）；`doOnComplete` → `claimTerminal(completed)`；`doOnError` → `claimTerminal(error, 根因)`；`doOnCancel` → `claimTerminal(stopped, "已取消")`。它**不继承** `ToolCallingAdvisor`：只需「整轮一次」的流信号，而工具循环递归只重入比自己更内层的 advisor，故挂在外侧恰好每轮 `run()` 进一次；最外层还保证终态事件晚于 `WorkerToolEventAdvisor(+300)` 的 message/usage 与 `RoundIndexAdvisor(+10)` 的 rounds 闭合落盘（前端正是以主 agent 终态 `agent.status` 触发拉 rounds）。
  - `InteractionServiceImpl` 的 ask 生命周期 → `AgentContext.markWaitingUser()` / `markAskResolved()`。**由 ask 入口驱动而非按 `ask_user` 工具名嗅探**：危险操作授权（`HumanAuthorizationHandler`）与 `ask_user` 走同一个 `ask()` 入口，按工具名嗅探会漏掉前一类（授权发生在工具实现内部，工具拦截链看不见）。
  - per-run 微观状态 `idle → running ⇄ waiting-user → terminal` 与终态声明都是 `AgentEntity` 内的 CAS 原语，每轮 `beginRun()` 复位；宏观 `status`（completed/stopped/error，被 list_agents/wait_agents 与台账消费）由终态声明同步写入。
- **主 agent 与派生 agent 一视同仁**：不按 creator 分支。主 agent 每轮 `run()` 也走 `started → … → done`；task 级状态走 `task.updated`（StatusNode / TaskManager.setStatus），两个维度互不混用。
- **`AgentBuilder.build()` 只管注册**：`ctx.agentFactory().create(id).creator(...).build()` 末尾自动 `exec.agents().put(id, agent)`；**不再发射 `agent.started`**（移入 advisor 链）。**插件只管创建与配置，永远不碰 put 和 agent.* 事件**；复用路径（`agents().get(id)` 命中续跑）也不再手动补发 `agent.started`——`resetForRerun()` + `run()` 即由 advisor 发新一轮出生事件。
- **插件侧唯一保留的兜底**：`SubAgentManager.stop()/stopAll()` 与 `runSub()` 的 catch 分支调 `Agent.claimTerminal(...)`。原因是 `FutureTask` 被 cancel 时可能根本没开始执行、或 `run()` 在订阅前就抛——这些路径没有流生命周期信号，不发终态前端就永远显示 running。CAS 使它与 advisor 的终态互斥，绝不重复发事件；插件构造 EmitEvent 的代码全部退役。
- **终态发射顺序固定** `error? → agent.done → agent.status{终态}`：台账的 `agent.done` 分支无条件写 `status=completed`，终态 status 必须后发才不被改判。
- **台账收编 worker core `AgentLedger`**：原 subagent 插件 `SubAgentLedger` 退役。`AgentLedger`（worker agent 层，`@Component`）订阅主体 EventLog 的 agent.started/done/status/usage/message/error 事件，维护 per-subject 内存台账（agentId → 摘要），经 `AtomicFiles` 自行读写 `ctx.dataDir().resolve("agents.json")`（30s 定时 + 终态 persistFinal 两路径），冷启动从 agents.json 恢复（running/waiting-user → stopped 归一，旧任务回退 meta.agents）。条目另含 skill 工具自动暴露的 `exposedTools` 数组（工具链经 `recordExposedTools` 合并写入,与事件投影同以 per-subject 台账 map 加锁;`AgentBuilder.build()` 重建实体时经 `exposedToolsOf` 回灌 entity.exposedTools,§7.14）——台账是暴露集合的唯一持久化通道,不另起独立文件。
- **per-run 语义下 `agent.started` 在台账侧是「合并」不是「整项替换」**：同一 agentId 每轮都发 started，`createdAt`/`usage`/`context`/`latestActivity` 是跨轮累积资产，替换会把它们抹零并把排序键 `createdAt` 重置成本轮时刻（复用子 agent 跳到列表末尾）；合并只刷 `status=running` 与 title/creator/metadata。
- **`AgentContext.creator()` 槽位**（plugin-api，default 返回 null）：**顶级字段**（不再是 `agentMetadata` 里的约定键），取值 `task`（主 agent）/ `subagent` / `ai-review`，由 `AgentBuilder.creator(String)` 设定，经 `agent.started` 事件 payload 投影进台账**顶级 `creator` 字段**随 agents.json 落盘。`agentMetadata()` Map 保留给其他元数据。**是否可见是消费方决策**——`list_agents` **工具**只取 `creator=subagent`（旧条目无顶级 creator 时回退 `metadata.creator`，两者皆空视为旧格式子 agent 保留）；而 **`task.agents` RPC 已收回 task 域**，语义改为「任务下**全部** agent」——返回主 agent + 各来源派生 agent（含 `creator=ai-review` 审议 agent），**删除原按 `creator=subagent` 过滤**；数据源 live 优先 `AgentLedger.getLiveAgents`，冷任务读 agents.json，旧任务回退 `meta.agents`（§5.5/§7.20.6）。主 agent（`agentId == mainAgentId`）条目恒保留——前端 `agentMeta['']` 的 title/usage/context 冷启动基线只认台账这一个数据源。

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
| `ToolExecutionContext` | `execution()` | prompt/chatResponse/toolCalls（批级）+ per-call 专属：`toolCall()`（当前调用）/`toolCallback()`（当前回调）/`arguments()`+`arguments(String)`（可改写入参）/`synthesize(String)`+`synthesizedResult()`（下行合成哨兵）/`startedAt()`+`startedAt(long)`（切口计时）/`toolOutcomes()`（可空账本）+ per-run 归属 `agentId()`（RunContext 下行，MAIN/SUB_AGENT 判定查询键，§7.15.2） | per-call 链 ctx 由 `PerCallToolCallingManager` 切口逐调用构造（§7.14.3），ExecContext 槽位委托 RunContext（emitter 覆写为 agent 级 agentId 包装） |
| `TaskLifecycleContext` | — | `taskId()`/`workspaceRoot()`/`workspaceId()` 作为**任务域成员**保留（消费侧零改动） | 仿 TaskRuntime 范式：新增 default 桥接（`subjectId()`→`taskId()` 等），其余 ExecContext 槽位以 default 委托 `taskRuntime()`（早期 RPC 阶段任务未创建时返回 null/空，语义保留） |

**消费侧命名迁移规则**：横切层取主体 ID 一律 `subjectId()`；原 `ctx.taskId()` 调用点（ToolContext/AdvisorContext/ToolExecutionContext 的消费侧）全部迁移到 `subjectId()`，`taskId()` 只保留在 TaskRuntime/TaskLifecycleContext 等任务域接口上作为任务域成员。

#### 7.20.6 消费者取数路径（properties 黑盒与 map 槽退役后）

- advisor：`AgentContext.execution()`（`AgentEntity` 字段替换为 execution）；`AdvisorContext extends ExecContext`，槽位委托 `agentEntity.execution()`（§7.20.5）。
- 工具 provider：`ToolContext`/`ToolExecutionContext` 本身即 ExecContext（`extends`，`execution()` 槽位已删、直读槽位；`ToolContextImpl.taskEntry()` 已删，§7.20.5）；`PerCallToolCallingManager` 是共享无状态单例，per-run ExecContext 经 prompt options 的框架 ToolContext map 下行（不依赖 ThreadLocal——reactive 流工具执行可能切 boundedElastic 线程，§7.14.3）。
- subagent 插件：全面中性化（§7.14）——Manager 零服务依赖收 ExecContext、**台账收编 worker core `AgentLedger`（`SubAgentLedger` 退役，agents.json 读写/事件投影/冷启动恢复全部由 worker agent 层承担）**、`TaskStoreService` agents 段退役、生命周期节点壳核分离、`task.agents` RPC **已收回 task 域**（常量 `RpcMethods.TASK_AGENTS`、由 `TaskManager` 注册实现，语义=任务下全部 agent、无 creator 过滤，主 agent 条目恒保留，§5.5/§7.20.1）、subagent 插件**不再提供该 RPC 与 web 前端功能**（原 `ui.composer_above_panel` 面板退役，agent 胶囊列表归 web 核心 `TaskChat` + `AgentListPanel`）；SubAgentManager 创建子 agent 时设 `creator("subagent")`、移除手动 put（由 `build()` 自动完成）与**全部 agent.* 事件手搓发射**（含复用路径的 `agent.started` 补发）；`TaskStore.truncateAfterSeq` 编辑重发截断只动 *.jsonl/rounds.jsonl，**不删任何插件数据文件**（agents.json/file-changes 残留陈旧条目被接受；后续可增加 `task.truncated` 截断事件通知由插件自清——开放项，task 核心永不知晓插件文件名）。
- AI 审议 agent：per-task 固定 agentId `review-<subjectId>` 注册进 `agents()` 跨请求复用会话（§7.9）——`agents().get(id)` 命中即续跑（复用路径不补发 `agent.started`——advisor 链每轮 run() 自动发）；未命中经 `ctx.agentFactory().create(id, reviewModel)` 创建，设 `creator("ai-review")` 后 `build()` 自动注册（台账记录其条目；`list_agents` **工具**按 `creator=subagent` 过滤故不可见，而 `task.agents` RPC 现返回全部 agent、包含审议 agent 条目）。
- `InteractionService` ask 的 context map `"taskId"` 键保留（值=subjectId，今天相等，前端兼容）；`SubjectBoundInteractionService` 静态代理在键缺失时自动补填，替代各处手动组装。

#### 7.20.7 工作流层复用终态

工作流层(workflow 内置插件)`WorkflowRuntime implements ExecContext`(subjectId=实例 ID、dataDir=工作流数据目录、emitter=工作流事件口——实例域事件路由进 events.jsonl)——同一条授权链、同一工具链、同一批**域中性** advisor 零改动复用(§7.14.2 分层:工作区 → task 层/工作流层平级编排 → agent 层 → 基础设施层,下层不知道上层);subagent/审议能力的执行链同样零任务域假设,是否对工作流开放由工作流层在装配期决定(如剔除 agent 族工具防生命周期逃逸)。事件管道(§14.0)与执行上下文管道双通道同构:工作流层实现自己的语义事件→wire 映射与自持事件日志。agent 装配不在本层:`WorkflowRuntime.agentFactory()` 恒 null,AI 会话一律经 origin 任务的 ExecContext(§7.20.8)。

- **非任务主体的两个获取口**(plugin-api `WorkerServices`):`agentFactoryFor(ExecContext)` 返回绑定到该 ExecContext 的 `AgentFactory`(静态代理,与任务域 `ExecContext.agentFactory()` 同一绑定形态——非任务主体没有任务域槽位可走,经 worker 服务口取绑定工厂);`modelConfigs()` 返回模型配置全表(apiKey 天然不在返回值,同 `config.get` 掩码纪律),供非任务主体解析节点级模型配置。
- **不变式:任务域 advisor 不上非任务主体**。`AdvisorContext.isTaskSubject()` 是统一域判定(实现 = `agentEntity().execution() instanceof TaskRuntime`,不用 `ctx instanceof`、不依赖按 subjectId 查任务服务);任务域专属 advisor——消费任务域持久化契约(rounds.jsonl、file-changes 分片)或任务级开关副作用(git 自动同步 token)的——其 provider 的 `appliesTo` 必须以它过滤;后续新增 advisor 注册时必须自检「是否任务域专属」,是则同样过滤。
- **工作流 agent 上下文模型**:命名 agent = 实例内一条持续会话线(跨节点延续),会话档案与呈现**单写 origin 任务域**(§7.20.8);运行即销毁、冷启动(同任务域 D18/D22 哲学)。隔离边界互相独立:工作区(存储隔离,§7.15)/ 实例间 / agent 间 / 与 task 子 agent 体系(独立督导会话已随 fail-fast 退役,§7.20.8)。**同 agent 名节点全局互斥 = 会话线串行的不变式**:同一条会话线被并行节点交错读写即上下文损坏,调度器以互斥保证串行。会话线由 AI 节点 `agent` 字段自引用(无集中声明表):模型与 system prompt 配在节点上,提示词的单一事实源 = 拓扑上游首个持有该名的**定义节点**,引用节点不存副本、运行时沿图解析(改定义处即全线下游生效);旧定义的 agents[] 声明在解析口就地迁移到引用节点,不落盘。

#### 7.20.8 工作流层任务嵌入契约

工作流嵌入任务体系运行:任务层是 AI 会话/呈现的唯一载体,工作流域只记节点级事实。契约四条:

- **originTaskId 关联**:`InstanceMeta.originTaskId`(可空;`wf.created` 事件 payload 同步携带)是实例↔任务的持久锚点;运行期绑定 = 引擎运行入口传入的 origin 任务 `ExecContext`(存实例运行态),AI 会话的 agentFactory/emitter/conversationArchive 全部经它取。RPC 直启(`workflow.run`)无绑定,仅限无 AI 节点的定义(测试/兼容通道)。
- **AI 节点会话单写任务域**:AI 节点 = origin 任务里的子 agent——经任务 ExecContext 的 agentFactory 装配(creator=workflow),事件(user.message/message/tool.result/usage 与瞬态)单写任务 EventLog(落 `tasks/<taskId>/<agentId>.jsonl`,任务页呈现/回放/rerun 重建的唯一档案);agentId 约定 `<name>#<instanceId>`(命名,跨节点复用)/`<nodeId>.<runNo>`(匿名一次性)。**fail-fast**:无 originTaskId 绑定或绑定任务事件口已失效时,含 AI 节点的工作流运行被拒绝(错误引导经任务运行)。
- **实例只记节点级三样**:`instances/<wiId>/` 下 meta.json / events.jsonl(node.* 输入输出错误预览) / `runs/<0007_nodeId>.json`,所有节点类型一律;agents/ 目录职责移除(督导 agent 已整体删除,见下条)。
- **任务内运行的错误语义(目标契约)**:supervisor=主 agent,节点失败即工作流运行失败、错误上下文回传调用方(fail-fast;独立督导 agent 会话退役,retry/retryWithInput 决策内核保留供 rerun);`workflow_rerun` 从中断节点续跑——同 agentId 复用、上下文从任务档案重建、依赖 origin 任务存活(任务已删除/不在运行则只能全新运行)。命令节点的失败判定:非零退出码=节点失败(发 `node.error`、状态 `failed`,不得按成功口径静默收束;`maxRuns` 即节点执行次数上限——回指循环/多入边/汇合触发的兜底,失败重试已退役、首败即分流,与后文「优先分支接管」契约一致);失败存在满足条件的出边(onError/exitCode/always)时**优先分支接管**——fail-fast 仅在无人接住时生效;分支接住的失败实例可正常收束,但终态投影与 `workflow_run` 返回体必须携带未收净告警(pending/failed 计数)。命令中止(超时/取消)级联终止整个进程树,实例终态后命令不再有副作用;**终态收口将在飞节点投影为 `cancelled` 并发 `node.interrupted` 关闭事件**——终态投影零 running(中断非节点自身失败:不写 runs;runCount 执行即消耗——开跑计入,被中断的尝试同样占额度)。

---

## 8. 前端接入层(every-agent-web)

协议语言中立,任何 WS 客户端(移动端、桌面、另一个 agent)都按第 5 章自行实现。仓内交付 React 前端,`src/sdk/` 内置 TS 客户端 SDK(原独立模块已并入)。

### 8.1 SDK 面

- **HubClient** — connect / hello / sub / pub / 自动重连(重连后自动重订阅 desiredSubs + 广播 onReconnect 供上层重拉校准);应用层心跳——5s 周期仅空闲时(本周期无任何帧到达)发 ping 探测,ping 后 15s 无帧判死,判死后零退避首试重连(§5.1);`rpc(workerId, method, params)` 按 reqId 匹配 ok/err/data/progress,默认 30s 超时。
- **订阅次序约束** — 对任一 worker:**先 sub 其 `evt` 频道,再发 `cmd`**(rpc 应答全部落在 evt 频道)。
- **TaskPacketView** — 数据包模式:打开任务 = 先 sub stream 频道(worker 据 join 建推送器收到实时增量)→ `task.rounds` + `task.roundTail` + `task.agents`(agent 台账:主 agent + 各来源派生 agent,在 task.rounds 之后调用)拉初始 → 之后仅靠定向推送收流式(帧与拉取帧同一路 seq 去重/排序聚合);帧消费后回 `stream.ack` 释放背压窗口(§7.13);上滚 `loadBefore(beforeSeq)` 拉更早轮次;`resync()` = 重订阅 + 重拉。`task.agents` 应答与流事件(usage/agent.started/agent.done/agent.status/error/cancelled/ask.*)从**同一事件入口**(fold 查表分发)灌入**独立 agent 台账镜像 store `agentLedger`**(`src/task/agentLedger.ts`,键 = 各 agent 原生 agentId:主 agent = mainAgentId(task 层传入的固定 id)、派生 agent = 其 id;`seedAgents` 灌台账基线 + 流事件实时字段级覆盖合并)——**eventFolder 只负责线程折叠(items),不持有 agent 状态**,以组合持有 `folder.ledger` 实例供台账类 handler 与消费方触达,台账变更与 items 折叠共用同一合并 notify 驱动重渲染;**台账生命周期跟任务不跟 items 重置**(`folder.reset()` 只清线程项,台账与 slashTokens/mainAgentId 同理不随重置——流事件重放按字段级合并幂等回填);AgentListPanel 胶囊列表数据源 = **items ∪ 台账 agentMeta**,悬停胶囊显示信息卡(标题/状态/创建时间/模型/累计 tokens/上下文用量),子 agent 胶囊底部边框内一条 2px 用量线(比例 = 最近一轮 prompt/上下文窗口,父级 overflow:hidden 裁剪不越圆角)。
- **channels / ownerKey** — 频道名构造与 sha256 身份,与 Java 契约逐字对齐。

### 8.2 多 worker 聚合(单连模式)

一个 hub 下可有多台 worker。前端以 1 条目录连接(hubKey)看全部在线 worker(presence),对**当前启用的那台** worker 用其 apiKey 建数据连接;**同一时刻只保留一条 worker 数据连接**——在设置页启用另一台时,先关闭并把其他 worker 的本地启用开关置 false(`disconnectOtherWorkers`),再连目标 worker。**worker 离线时连接保持建立**(WS 本身健康,仅 presence 变化;显示态 = `presence && wsOpen` 双条件,不靠断连表达离线),RPC 向离线 worker 排队直到超时拒绝(holdTimer 兜底),仅致命错误(鉴权失败等,凭证不修正重试永远失败)才替换连接实例。

- **任务归属 = 频道携带,不由前端推断**:任务事件住在 `u.<K>.worker.<id>.tasks`、任务流住在 `u.<K>.worker.<id>.task.<taskId>.stream`(§5.2),TaskSummary 与 tasks 事件 payload 恒带 `workerId`。前端「订阅了谁的频道 = 谁的数据」,无歧义;`workerIdOfFrame()` 只认频道的 worker 段与 `payload.workerId`,**禁止**按 ownerKey 前缀猜测首个连接(同 apiKey 两台 worker 时会误判,把禁用那台的任务混进启用这台)。工作区/git 等非任务域仍按 worker 合并展示、按归属定向操作。
- **切换 worker = worker 级客户端状态整体失效**:worker 数据连接集合变化时,`hubSession` 发一次 `onWorkerConnectionsChanged` 通知(仅通知,不含业务),各持有方各自负责清理自己的 per-worker 缓存——任务列表镜像与分页游标(`taskStore`)、任务流句柄(`taskStreamManager`)、模型配置缓存(`modelConfigs.byWorker`)、插件加载态(`pluginLoader`,其 RPC 闭包必须**调用期**解析 workerId 而非创建期捕获)、挂起中的 ask(`askStore`)、工作区镜像(`workspaceRegistry`);同时**关闭全部 `task:*` 标签页**(工作区身份对任务标签是硬依赖,残留标签必然指向已断开的 worker;项目/文件/终端/设置等标签保留)。
- **失败必须可见**:任务详情取不到所属 worker 的连接时,写 `roundsError` 并通知渲染,呈现「未连接 + 重试」错误条;不得 `return` 于无声(此前切 worker 后整页空白、既不报错也不自愈,只能刷新页面,正是因为该路径静默 + 归属快照永不纠正)。
- 凭证 AES-GCM 加密存 localStorage,presence 指纹(ownerFingerprint 前 16 hex)支持 worker 改名后自动复用凭证。

### 8.3 轮次浏览与懒加载(画板 = 纯 wire 折叠产物)

对话按"轮次"折叠展示(每轮 = 用户消息 → AI 最终回复);**画板 = 纯 wire 折叠产物**——线程数据层(items)只由 wire 事件(task.poll 懒加载分页 / task.roundTail 尾段 / stream 定向推送)折叠而成,**rounds.jsonl 通道(task.rounds 快照)只做渲染投影与切片区间元数据,不进数据层**;轮骨架(user 气泡/折叠条/final 摘要/耗时)由面板按 rounds 快照直接合成渲染,折叠窗口语义归 rounds.jsonl 唯一权威、线程项无折叠标记(TaskThread 渲染为平铺分组)。

- **条目契约(五字段 ThreadItem)**:items 的唯一条目形状 `{ seq, ts, agentId, kind, payload }`(定义于 `src/task/threadItem.ts`)。`seq`(string,wire 原始雪花大数串)是排序/去重/切片/懒加载游标的唯一键——**seq 即 id**,旧 `m-`/`t-` 前缀 messageId/traceId 体系废除;`ts`(number)事件时间戳;`agentId`(string)归属 agent(事件缺 agentId 归一到 mainAgentId);`kind`(string,**开放集合**:`user.message`/`message`/`tool.result`/`error`/`cancelled`/trace 族及 defaultHandler 折入的任意未来 kind;瞬态 `round.*`/`tool.started` 与台账类 usage/agent.*/ask.* 不进 items);`payload` **形状所有权归 kind**——由该 kind 的折叠 handler 写、对应视图读;`KindPayloadMap` 为声明合并接口,仅作**编译期** handler↔视图形状配对校验,运行时不存在。
- **切片一律按 seq 区间比较**(`startSeq < seq <= endSeq`,大整数精确比较):骨架出画板后端点锚点条目(起点 user / 终点 final)可能不在 items,禁止 findIndex 端点定位;尾轮切片为 `(tailStartSeq, ∞)`(起点 user 气泡由快照合成渲染,不在尾轮 items 切片内)。
- **过程内容懒加载**:闭合轮展开 / 终态未闭合尾轮走前向分页(滚动到底部 sentinel 续拉,拉到 endSeq 停);运行中尾轮 = 订阅实时流 + 初始「最后一页」(roundTail)+ 顶部 backward sentinel 上翻历史(到达 startSeq 停)。拉取层先查 items 缓存(按 seq 区间求已覆盖边界),已覆盖不 RPC、部分覆盖从缺口续拉。
- **分页状态 key = roundId**(轮次级稳定主键):运行中尾轮用 open 轮 `roundId`(worker 开轮即生成、跨闭合稳定),老 worker 缺失时回退 `'open'` 魔法串(must-ignore 双向兼容);每轮独立游标,轮换新开不复用旧游标。
- **resync/编辑重发的 items 重建**:full 重载仍 reset items(编辑重发后旧轮次 seq 已过期),重建来源 = 尾段 wire 回放 + 实时推送 + **分页状态失效促重拉**——folder 暴露 `resetEpoch` 计数,轮面板观测到 epoch 变化即清空分页状态,已展开轮自动重新发起首页拉取。
- 渲染层任务线程按 **seq 键控增量 upsert**(多子 agent 并发/重连回放不丢事件;大整数 seq 用 BigInt 精确比较);自动滚动跟随由用户显式动作(点击「滚动到底部」等)恢复。

### 8.4 通知

浏览器(Web Notification)与桌面(Electron 系统通知)经统一通知适配器抽象(依赖注入 + 适配器注册表,web 模块零 electron 依赖)。触发场景:授权请求、任务完成/错误、ask_user 提问;不在前台才弹;桌面同 tag 2s 去重,点击回带到前台。

### 8.5 插件系统（统一加载架构）

插件系统采用**多扫描器 + 统一加载**架构,内置与外部插件经同一链路发现、加载、激活。

- **后端扫描器** — `PluginScanner` 接口(`List<ScannedPlugin> scan()`)有两个实现,由 `PluginLoader` 注入 `List<PluginScanner>` 统一遍历,按 source 排序(builtin 优先)逐个 `loadPlugin`(建 `URLClassLoader` → 解析 `plugin.json` → `activate`):
  - `BuiltInPluginScanner`(source=`"builtin"`)扫描 `every-agent-plugins/<id>/` 目录。
  - `ExternalPluginScanner`(source=`"external"`)扫描 `~/.everyagent/plugins/<id>/`,逻辑与内置一致但目录不同。
- **禁用插件 = 核心不调它的 `activate`**（`PluginLoader.loadPlugin`）——插件的一切贡献都只在 `activate(ctx)` 里注册,所以禁用的唯一正确落点就是加载器:命中禁用名单即跳过激活。**禁止**让各 SPI 注册表自己拿禁用名单做二次过滤。禁用名单真相源是 `PluginStateStore`（构造时读盘、变更即落盘 `~/.everyagent/plugins/.disabled-plugins`）。被禁用插件仍登记进已加载清单（`active=false`）,否则扩展管理面板看不见它。`deactivate()` 仅在 worker 优雅关闭时调用,运行期禁用/卸载不触发,故名单变更对**下一次 worker 启动**生效（`plugin.enable/disable` 应答须带「重启 worker 后生效」）。
- **卸载墓碑与启动自愈（`.uninstall-pending`）** — `plugin.uninstall` 删除外部插件目录**前**先写墓碑文件;删除失败（Windows 典型:进程持有句柄）时错误信息附路径与排查指引。`ExternalPluginScanner` 启动扫描遇墓碑目录先递归删除自愈,仍被占用则**重写墓碑**后整目录跳过（绝不加载已决定卸载的插件）,卸载意图跨重启最终一致。
- **`NativeExec` 子进程 cwd 禁入插件目录** — `OsSandbox.spawnNative` 拒绝 cwd 位于外部插件目录内的调用,返回失败 `ExecResult`:插件目录被长驻子进程当作工作目录会在 Windows 上锁死目录、令 `plugin.uninstall` 必败。既有调用方均以工作区为 cwd,零影响。
- **统一 search 的核心零类型感知设计** — worker 只提供**一个**统一 `search` RPC,核心**不含任何硬编码搜索类型名或过滤字段名**:只「遍历 `SearchProviderRegistry` → 触发 provider → 聚合」,类型清单完全来自注册表。内置三引擎（`file-content`/`file-name`/`task`）全部做成 `SearchProvider`;插件经 `ctx.registerSearchProvider` 注册的后端与内置引擎同权。无害性约束:注册表为空零额外行为;单个 provider 抛异常或超时仅 WARN 跳过,不影响其余结果。搜索限制配置化、结果模型字段、去重键、能力接口 `SuggestionProvider` 等详细定义见插件指南。
- **前端加载统一** — 所有插件(builtin/external)经 `plugin.webSource` RPC 获取 JS 源码 → `rewriteBareImports`(bare import 改写为 `window.__EA_REACT__` 等全局变量引用)→ blob URL → `import()` 动态加载 → `PluginModule.activate(ctx)`。不再有 `import.meta.glob` / Vite glob 映射 / `builtInPlugins.ts` / `internalLoaders`。内置插件需先用 esbuild 预编译(`npm run build:plugins`),external 与运行时全局注入三处锁定同一份 bare import 清单。
- **公共 API 边界（VSCode 模式）** — 插件只引用 `@everyagent/plugin-api`(纯类型包)+ 运行时 `ctx`(`PluginContext`),**禁止 `@/` 引用宿主 web 模块**;UI 组件(SVG/antd)一律插件自实现。`ctx` 字段:`ui`/`sdk`/`events`/`fs`/`storage`/`commands`。标签打开改为注册表驱动,`ctx.ui.openTab` 为内置/插件统一的通用入口,核心不写类型分支。
- **扩展点贡献变更须可订阅** — 插件在 `activate()` 里经 `ctx.ui.register*` 注册贡献,而 `activate` 由 `plugin.webSource` RPC 异步驱动,几乎必然晚于宿主首屏渲染。因此扩展点注册表(`ExtensionRegistry`)必须提供 `subscribe(listener)`,`PluginDispatcher` 汇总为全局 `subscribeExtensionsChanged` + `getExtensionsVersion`(自增计数)。宿主侧边栏以 `useSyncExternalStore` 消费该版本,使插件图标注册即显示。
- **侧边栏入口排序 `order`** — `ui.sidebar_items` 的 `order`(float)是活动栏唯一排序依据:内置项与插件贡献合并后按 `order` 升序混排,不再隐含"内置在前、插件在后"。内置项占 `tasks=1 / files=2 / search=3 / settings=10`,插件插队(git=5、扩展管理=9);未声明 `order` 按 `DEFAULT_SIDEBAR_ORDER=100` 兜底。
- **LoadedPlugin / PluginRegistry** — `LoadedPlugin` record 含 `source`/`main`/`webMain` 字段;`PluginRegistry` 不再扫 `classpath*:plugin.json`,改为从 `PluginLoader.getLoadedPlugins()` 统一聚合(`@DependsOn("pluginLoader")`);`PluginManifest` record 含 `pluginDir` 字段供 `webSource` RPC 统一获取插件目录。
- **前端扩展点** — 前端扩展点（`sidebar_items` / `search_types` / `editor_context_menu_actions` / `composer_chip_actions` / `settings_sections` / `themes` / `kind_views` / `tool_call_views` / `workspace_tab_types` 等）的定义详见插件指南 `docs/plugin-guide/web/ui-extensions.md`。
- **插件开发指南与脚手架** — 动手开发全流程见插件指南 `docs/plugin-guide/index.md`;新插件工程用脚手架生成:`create-everyagent-plugin/`,命令 `node create-everyagent-plugin --id <id>`(java/web/full 模板 + `.eap` 打包),不必手搓模板。standalone 形态自带 `vendor/@everyagent/plugin-api/index.d.ts.raw` 类型副本(宿主源码逐字节镜像,由 `scripts/sync-plugin-api-copy.ps1` 维护并有漂移测试守卫)。

### 8.6 测试基建(vitest)

前端测试统一 vitest(`npm test` = `vitest run`,node 环境、不引 jsdom;`npx tsc --noEmit` 覆盖测试文件类型,测试放被测模块同目录)。**特征化测试是重构护栏**:eventFolder(折叠器)、roundsView(轮次视图纯函数)、kindViews 注册表、tool 域共享模块与内置 kind 视图等先「锁现状行为」再重构——先锁后删,重构步骤须同步更新断言而非绕过;断言随条目形状统一/画板纯化等口径演进同步迁移,行为语义保持(乱序归位/去重/流式聚合/区间切片/agent 台账/过滤回归等)。

### 8.7 kind 渲染注册表与内置视图

渲染层对任务线程条目是**两级查表分发模型**(`src/components/task/kindViews/`):

- **第一级(kind 级,分发)** — TaskThread 是唯一分发点:`items.map(item => { const View = getKindView(item.kind) ?? FallbackView; … })`,外层包 `ThreadBoardProvider`(value = 该次渲染的 items 切片)。查 `kindViewRegistry`:插件 `ui.kind_views` 注册的视图**优先**,核心注册三类(`user.message`/`message`/`tool.result`,`builtins.ts` 集中注册);查表未注册返回 **`undefined`**,`?? FallbackView` 是**分发语义**,不埋进注册表;`key = item.seq`(seq 即 id)。
- **第二级(呈现级,trace 形状)** — FallbackView(含 error/cancelled 等)的呈现均复用 `TaskTraceShell` 壳:壳内按传入 kind **再查一次** `traceTypeRegistry`(插件 `ui.trace_types` 扩展点旁路写入;**注册键 = wire kind**),命中用插件定义(getSummary/renderContent/canExpand/getIcon/hideTitle;**无 tone 字段**),未注册走通用降级——这一级查表**永不返回 undefined**。两级兜底语义的差异是刻意的:第一级决定「**由谁渲染整行**」,第二级只决定「**壳内怎么呈现**」。
- **端到端保证** — 两级均未注册的新 kind 自动可见、**内容零丢失**(payload 宽松读取归一 title/summary/content;`data` 字段原样透传给第二级——它是插件 trace 视图的唯一 metadata 供体,**归一不得吃掉 `payload.data`**,否则插件 trace 视图拿到空 metadata、卡片渲染了却全是空字段)。工具调用行的 per-tool 美化视图(按**工具名**查 `toolViews/registry.ts`,插件 `ui.tool_call_views` 同表)是消息/工具路径内部的又一处查表,只管展开详情,不属这两级主链。

- **kindViewRegistry** — `registerKindView(kind, View)`(重复注册覆盖,空 kind 抛错)/ `getKindView(kind)`(插件优先 > 核心表)/ `unregisterKindView(kind)`;**KindPayloadMap 编译期配对发生在注册点**(内置视图以 `KindViewProps<'message'>` 等泛型签名注册,payload 类型随 kind 锁定;分发点 kind 动态,payload 直传)。
- **插件 kind 视图扩展点 `ui.kind_views`** — `ctx.ui.registerKindView(kind, Component)`(Disposable dispose 即注销回退核心渲染);`Component` 是**叶子视图**——props 不含兄弟行 items,需要兄弟行语义(tool.result 配对/吸收判定/终态信号等)一律经 `useThreadBoard()` context 取;只管整行接管,壳内呈现换装走 `ui.trace_types`(第二级);插件可接管任意 kind(含内置三类,接管即整行让位,慎用)。插件不注册任何东西时 FallbackView 兜底自动可见——**扩展点只为「美化某个 kind」存在,不为「让某个 kind 可见」存在**。
- **工具配对归渲染层 tool 域共享模块**(`kindViews/toolDomain.ts`,纯函数):message 视图按 callId 从画板 items 配对 tool.result(未到 → null 对位)、孤儿 tool.result 兜底渲染判定、ThreadItem 载荷 → `AggregatedToolDetail`(per-tool 美化视图唯一输入)、批中断的可见终态信号判定——核心与画板零知识。
- **内置视图族**:`message` 视图 = 批壳 + 调用行模型(一次 message 的 `payload.toolCalls` 为一批:批头可折叠、调用行 = 状态点 + 等宽工具名 + 参数摘要 + 状态与耗时,点击展开参数与结果、长内容内部滚动;批内同名多调用不折叠;无正文纯下发轮不加占位文案);运行中调用行由 `tool.started`(瞬态,不进 items)按 callId 直配点亮与读秒,结果到达冻结权威 durationMs,历史回放直接展示 result 的 durationMs,批中断按可见终态信号标「执行中被中断/未执行」,不依赖框架执行顺序。`user.message` 视图 = 回放胶囊段(opaque 串自包含切分回放 @文件胶囊)+ 子 agent 输入低调展示;`tool.result` 孤儿视图 = 独立兜底条目;error/cancelled 收敛进第二级(自然落 FallbackView → TaskTraceShell)。
- **共享壳几何**:trace 行与工具行同类(图标 + 标题 + 摘要 + 可展开详情),折叠行列几何与展开几何一律由 `EntryShell` 的基线单一提供,行族容器类不得再各自定义展开外观;图标着色属插件领域语义(经 `getIcon` 的 ReactNode 自带,宿主不代管配色)。
- 旧 renderMessageGroups 分组渲染生态(连续 tool 合并、role 分组包裹、join 索引、按名切组聚合等)已全部删除,由「两级分发 + 吸收模型」替代;`toolViews/registry.ts`(per-tool 美化视图注册表)保留,由批壳调用行与孤儿视图直接消费。

## 9. 桌面版(every-agent-desktop)

Electron 将 web + hub + worker **一体打包**为 Windows x64 便携(portable)与安装包(NSIS):

- **进程模型**:主进程 spawn 本地 hub 与 worker 两个 Spring Boot 子进程(`javaw.exe`,jlink 精简 JRE 随包);前端经本地静态服务加载(127.0.0.1 随机端口,保证 localhost 安全上下文),preload 以 contextBridge 注入开箱即用连接配置。**hub 始终跟随 desktop 启停**(desktop 独占管理,退出时一并停止);**worker 支持外部进程复用**——启动前调 `GET /admin/identify`(认证探测)判断 worker(:6102)是否已在运行,已在则跳过启动直接复用;端口被占则报错。
- **独立 worker 启动**:`resources/start-backend.bat` 可脱离 Desktop GUI 独立启动 worker(hub 仍由 desktop 管理);适配 Windows 任务计划程序"系统启动时"触发器;desktop 后续打开时自动检测已有 worker,不重复启动。
- **配置注入**:生成 hub/worker yaml 经 `--spring.config.additional-location` 覆盖 jar 内默认(整表覆盖 `worker.hubs`,避免误连远端);数据目录复用 `EVERYAGENT_HOME`,与命令行/docker 共用同一批任务/工作区/模型;每次启动读 `<EVERYAGENT_HOME>/desktop-config.json`(首次生成),日志统一落 `<EVERYAGENT_HOME>/logs/`。
- **管理端点(仅 worker)**:`GET /admin/identify`(认证后返回 worker 身份)与 `POST /admin/shutdown`(认证后触发 Spring 优雅关闭);认证用 `X-Admin-Key` 请求头(与 hubs[0].apiKey 比对);仅监听 127.0.0.1,POST + 自定义头防 CSRF。**hub 无 admin 端点**(hub 可能公网部署,暴露 shutdown 接口会被持有 hubKey 的人关掉)。
- **生命周期**:单实例锁、占位页/错误页(含日志目录)、托盘提供「退出桌面」(停 hub,worker 保留运行,下次自动复用)与「全部退出」(停 hub + 对所有 worker 发 shutdown 优雅关闭);`before-quit` 按 `quitScope` 决定停哪些。
- **窗口不可见不后台化**:主窗口 `backgroundThrottling: false`——窗口被遮挡/最小化时 Chromium 默认会挂起渲染进程、杀掉 WebSocket,导致每次回前台必断连重连;连接生死唯一由前端应用层心跳判定(§5.1),渲染进程须持续运行(本地回环,无降载需求,§7.13 的 visibility 降载在桌面端不触发)。
- **desktop 不感知任何具体插件**:主进程只编排 hub/worker 进程(端口、健康检查、启停),不携带任何插件领域行为——沙箱可用性保证(如 WSL 发行版探测)一律由对应插件在 worker 侧惰性完成(§7.10)。
- **构建流水线**:`build-backend.mjs`(mvn 打包)、`build:plugins` + `copy:plugins`(重建全部内置插件并按 Scanner 期待结构 staging:Java 产物 + web 产物 + README + **插件 `bin/` 目录**一并复制;禁用插件不进包也不残留;任一启用插件产物缺失 fail-fast)、`build-web.mjs`、jlink JRE、插件 runtime 附属合并;electron-builder `extraResources` 打包。全新环境首次 dist 前需对根 reactor 跑过一次 `mvn install`(插件 pom 的 parent/contract 构件须在本地 .m2)。

## 10. 关键流程

### 10.1 任务创建与流式输出

```
前端A                     hub                         worker(家中PC)
 │─cmd: rpc{task.run}───→│──转发───────────────────→│ 创建任务,起虚拟线程
 │←─u.K.worker.<id>.tasks: task.created{taskId,workerId}│
 │─sub u.K.worker.<id>.task.<taskId>.stream─────────→│(hub 按 worker 段定向通知那一台:join)
 │                                                      │ DataPusherManager 自检归属后建定向推送器
 │─cmd: rpc{task.rounds + task.roundTail + task.agents}→│ 初始渲染(轮次 + 尾段 + agent 台账)
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
 │─sub u.K.worker.<id>.task.<taskId>.stream──→│  (重订阅 → hub 定向再发 join → 新推送器)
 │←─msg: stream 帧(delta/message)──│ 实时增量续播(seq 去重合并,无缝续播)
```

### 10.3 终态任务继续对话(没有"续跑"概念)

```
前端                        hub                         worker
 │─cmd: rpc{task.run, taskId:t_x, input:"追问…"}───→│ 校验后从磁盘认领
 │←─evt: rpc.ok{taskId}───────────────────────────────│ ConversationLoader 重建历史
 │←─u.K.worker.<id>.tasks: task.updated{status:running}│ log.seed(seqLastOf) 接续序号
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
├── every-agent-plugin-parent/     # 插件 SDK parent(仅 pom):Java 25 / 编码 / 插件依赖版本管理 / plugin.json 打包 / 测试栈,内置与外部插件共用
├── every-agent-plugins/           # 内置插件:model-rate-limit(限流) / token-estimator(token 估算与校准) / task-queue(队列) / subagent / git / ai-review / empty-response-retry(空响应重试) / transient-error-retry(瞬时错误重试) / context-compression(上下文压缩) / workflow(工作流引擎与界面,java+web 双形态) / ...
├── create-everyagent-plugin/      # 插件工程脚手架 CLI(node create-everyagent-plugin --id <id>,java/web/full 模板 + .eap 打包)
├── every-agent-desktop/           # Electron 桌面打包
├── runtime/                       # 程序附属文件(核心 rg 二进制;插件附属资源由各插件 runtime/ 子目录并入)
├── docs/ARCHITECTURE.md           # 本文档(唯一架构事实源)
└── docs/plugin-guide/             # 插件开发指南(快速上手/扩展点/构建分发/排查,入口 index.md)
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

# 新建插件工程(脚手架;插件开发指南 docs/plugin-guide/index.md)
node create-everyagent-plugin --id <id>
```

docker-compose 一键:`HUB_KEY=你的密钥 WORKER_API_KEY=你的worker密钥 docker-compose up --build`;数据落在 named volume(hub 侧显式注入 `SERVER_ADDRESS=0.0.0.0`,worker 侧经 `WORKER_HUB_URL=ws://hub:6101/ws` 走容器网络)。

### 11.3 部署形态

- **dev**:docker-compose,或本地 mvn ×2 + npm。
- **prod**:hub 与前端静态站部署公网服务器(LB 的 WS 空闲超时 ≥ 60s);worker 在个人 PC 以出站 wss 连入(docker 或系统服务),通过 `worker.hubs` 配置(`worker.hubs[].url` 指向公网 hub,每项 `url + api-key + hub-key`,凭据必填、无打包默认值);6102 管理端口仅绑定 127.0.0.1;hub 监听地址打包默认收敛为 `127.0.0.1`(`${SERVER_ADDRESS:127.0.0.1}`),对外部署由 compose/systemd 单元显式放开。
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
| D26 | 命令沙箱多后端**插件化**:`auto` 取可用插件中 priority 最高者,无后端时 DIRECT 兜底 | 隔离能力全部下沉插件(§7.10);各后端实现细节见各自插件文档 |
| D27 | (历史)授权语义(seccomp 场景)= WSL 原生 root 重跑;bwrap 后端已删除,seccomp 拦截随之移除(§7.11) | 当时的 NNP + userns 不映射 uid0 + 基座只读 → 沙箱内真实提权物理不可行;现由 Restricted Token + PermissionGate 文本扫描承担 |
| D28 | 授权决议链支持自动路径(AI 审议/无人值守)与人工弹窗,均为独立任务级开关 | 分别满足"无人监督但有把关"与"全流程无人值守"两种需求 |
| D29 | **统一执行上下文 ExecContext**:执行数据显式类型化为 plugin-api 接口槽位(仅主体必然具备的核心属性与端口;configId 无独立槽,经 `snapshot().configId()` 取);三预绑定端口 `emitter()/agentFactory()/interaction()`(静态代理)经 ctx 下传;`TaskInfo` 退役;授权请求收编为 `AuthorizationRequest(ExecContext,agentId,grantKey,prompt)`;subagent 作为执行域能力插件全面中性化(台账收编 worker core `AgentLedger`);各横切 Context 接口收编为 `extends ExecContext`(重复字段与 execution() 槽位删除,消费侧直读槽位、主体 ID 一律 subjectId()) | 横切层域中性,未来工作流实现 ExecContext 即零改动复用;20+ 处强转消失。详见 §7.20/§14.11 |
| D30 | **任务事件与任务流频道加 worker 段**(`u.<K>.worker.<id>.tasks` / `…task.<id>.stream`)+ 任务摘要 payload 带 `workerId`;**切换启用 worker 时 worker 级客户端状态整体失效并关闭全部 `task:*` 标签** | apiKey 即身份 ⇒ 同一 apiKey 下多台 worker 共用命名空间,归属必须是 wire 事实而非前端推断(误判 = 任务混列表、点开必报不存在、切换后空白不自愈)。详见 §4.3/§5.2/§8.2/§14.12;协议 v3→4 |
| D31 | **出网投影单点(EgressProjector)**:客户端可见事件/轮次必须经单点投影器(worker `ship` 包,域中性)转换;4 出网口(stream 推送/`task.poll`/`task.roundTail`/`task.rounds`)共用同一投影器与 filter 链;pre-wire 过滤可丢弃(事件仍落盘)/改写 payload、**不改 `ext`**;`wireEvent` 不再是公共 API | 把「不可展示」当**出网问题**而非产生问题:事件照旧落盘(事实源与 seq 空间不变),出网收敛到单点即实现层无法绕过;「已落盘但不出网」的 seq 洞合法(与「瞬态占号不落盘」并列)。详见 §5.3/§14.13 |
| D32 | **`task.agents` 收回 task 域 + subagent 插件去 web**:常量入 `RpcMethods.TASK_AGENTS`、`TaskManager` 注册实现,语义 = 任务下**全部** agent(主 + 各来源派生,含审议 agent),删除按 `creator=subagent` 过滤;subagent 插件不再提供该 RPC 与 web 面板,胶囊列表归 web 核心;`run_agent`/`list_agents`/`wait_agents`/`stop_agent` 工具仍属 subagent 插件 | `task.*` 前缀本属 task 域;「任务下全部 agent」是任务概念,按 creator 过滤会漏掉审议 agent 的归属;前端列表收口核心、插件去 web 减少迁移中间态。详见 §5.5/§7.14/§7.20.1 |

---

## 13. v2 预留(明确不在 v1 做)

运行中任务的崩溃恢复 · apiKey 签发/吊销 · 多 hub 实例集群(Redis pub/sub 桥 + 亲和)· 前端间自定义频道协作 · 任务归属转移 · tasks.list 搜索 · 大文件上传二进制分帧/压缩 · **worker 编排(调度)**:一台"调度 worker"向其他 worker 的 cmd 频道发命令建任务收结果(命名空间天然放行,零协议改动;v1 中 worker 之间互不知晓)· 子 Agent 跨机分工(dispatch_agent 走 cmd 频道)。

---

## 14. 实现约束(开发者红线)

本章是给实现者的红线清单:以下行为已定死,不按个人偏好变更。与其余章节冲突时,先改文档再改代码。

0. **事件管道分层(类 OSI 七层)**:事件从产生到前端展示,经多层逐层包装/解包,每层只负责自己层的数据,不感知上下层语义:
   - **插件/底层(最内层)**:发语义事件(`EventEmitter.emit(EmitEvent.TraceData.transientOf("model_rate_wait", ...))`),只知道事件名 + payload + persist 标志,不知道 agentId/taskId/seq/wire 格式/前端展示方式。
   - **agent 层**:填 agentId(包装 payload 或附加参数),不知道 taskId/seq/wire 格式。
   - **task 层**(或以后的工作流层):语义事件→wire 事件映射(如 `model_rate_wait` → `task.trace`;`delta` → `delta`;`error` → `error`)、分配 seq、按 persist 落盘 jsonl。
   - **出网投影层(EgressProjector)**:task 层 wire 映射后、推送/拉取前,把落盘态 `EventRecord` 投影为客户端可见形态——pre-wire 出网过滤链可丢弃(返回 null)/改写 payload,**不改 `ext`**;4 个出网口共用同一投影器(§5.3/§14.13)。
   - **推送层(WebSocketEmitter)**:背压 + 定向推送到前端 websocket,不感知事件语义。
   - **前端(最外层)**:按 wire 事件名 + payload 字段映射为 trace/消息/状态等展示组件。
   - **改造纪律**:后续把 `AgentEventChannel` 的具体方法逐步迁移到 `EventEmitter.emit(EmitEvent)` 时,每迁一个方法:插件/底层只发强类型 `EmitEvent` 载荷,task 层用 `instanceof` 判断类型做映射,推送层和前端不变;工作流层实现自己的映射与推送管道,前端按工作流 wire 格式解析。**插件永远不感知 task 层语义(wire 事件名、traceId、seq 等)。**
1. **编码、时间与 ID**:帧为 UTF-8 JSON;ts 一律 epoch 毫秒(UTC);短 ID 规则 `{前缀}_{3位盐}{base36 序号}`,全局唯一从不复用;ownerKey = sha256(apiKey) 64 位小写 hex。
2. **频道与信封(hub 红线)**:频道名字符集 `[a-z0-9._-]` 长度 ≤160,必须以 `u.<ownerKey>.` 开头;hub 只解析 `type`/`channel`(及 hello 握手字段),`event`/`seq`/`payload`/`ext` 原样转发;不存在角色×频道权限矩阵;seq 只属于任务流事件空间,由 task.poll/stream 携带;error 分级(断开 vs 拒单帧);连接抢占(worker 同 clientId 新连关旧连)。
3. **RPC 生命周期**:reqId 连接内唯一,ok/err 已出则后续同 reqId 帧忽略;未知 method → UNKNOWN_METHOD;参数不合法 → BAD_PARAMS;超时是纯客户端语义(SDK 默认 30s),要中断须显式 rpc.cancel;task.run 新建支持 idempotencyKey(10 分钟窗口去重);task.delete 是任务唯一删除路径,无任何自动清理。
4. **错误码两个命名空间,勿混用**:hub `error` = NOT_AUTHENTICATED/VERSION_MISMATCH(断开)、ACL_DENIED/FRAME_TOO_LARGE/RATE_LIMITED(单帧拒绝);`rpc.err` = UNKNOWN_METHOD/BAD_PARAMS/NOT_FOUND/SANDBOX_DENIED/BUSY/INTERNAL/AUTH_REQUIRED/UNAVAILABLE;UNAVAILABLE=插件依赖的本机工具/环境不可用(如未安装 git),前端应静默降级提示而非弹错误框。
5. **并发与上限**:maxConcurrentTasks(20)超限 task.run 新建 → BUSY(不排队);maxConcurrentSubs 超限 run_agent 返回错误文本由模型自决;maxEventsPerTask(50 万,只计持久事件)超限抛 LogOverflow(磁盘 jsonl 全量不受影响);续跑放行不查并发上限。队列插件启用时超限任务排队等待(`QueueAdmissionNode`)而非 BUSY 拒绝;无队列插件时保持 ERR_BUSY 硬拒绝。
6. **沙箱(插件化)**:SandboxBackend SPI = 效果 2(`grant`/`revoke`,路径级、无语义、幂等可重放)+ 查询 2(`toSandbox`/`toHost`,纯函数、默认恒等)+ `id`;**效果与时机分离——何时授权/回收由上层决定**(PermissionGate/GrantRegistry 是唯一翻译点,沙箱不感知任务/工作区、不订阅领域事件);授权下发遵循**不放大原则**(无法在请求粒度落地则不下发,绝不放宽到父目录);回收是路径级且按主体生命周期收敛,跨主体共享的根在仍有主体期望时保留;沙箱插件提供自己的 CommandExecutor 和 ToolProvider;PermissionGate 不暴露到 plugin-api(核心内部保留);路径必须先规范化(realpath)再校验 workspace 根前缀,拒绝 `..`、绝对路径逃逸与符号链接逃逸;授权护的是「工作区外」,不是删除动作本身;不得绕过 PermissionGate 直接放行越界 IO;fs.\*Raw 系列虽是机器绝对路径(无 workspace 参数)同样不得绕过——必须校验合法根集合(在册工作区根 ∪ 外部授权根 ∪ 技能目录 ∪ 在途授权根 ∪ 会话用户放行目录,§5.5),越界返回 `SANDBOX_DENIED` 交前端确认放行(`fs.grantRawRoot`,会话级内存态,仅此 RPC 可注册);各后端的具体隔离机制见各自插件文档;git 凭证只存 worker 本机加密文件,不经协议传输,注入走 env(askpass) 不经 shell 参数。
7. **生命周期**:终态任务收到 task.run{taskId} = 冷启动一次普通运行;worker 优雅停机(SIGTERM)受影响任务标 failed 再关连接;6102 仅绑定 127.0.0.1;worker 每条 hub 连接建立即 sub 该命名空间 cmd + input 两个频道,从不订阅 per-task 频道。
8. **复用 Spring AI,禁止重复造轮子**:agent 执行必须走 ChatClient + Advisor 生态,不得手搓 agent 循环、工具循环、响应聚合、system 拼接;执行链只能是很薄一层;新增 agent 能力优先做成 Advisor;一个 Advisor 只负责一个功能;事件发射等需挂钩工具循环的增强通过继承 ToolCallingAdvisor 并重写受保护 hook 实现;主/子 agent 共用同一运行入口与 Advisor 链,仅 agentId 不同。
9. **插件零 worker 依赖**:插件的 pom 中不得出现对 `every-agent-worker` 的依赖,compile/provided/runtime/test 任何 scope 一律禁止;插件测试需要任务/agent/配置等桩时,在测试源码内自建实现 plugin-api 接口的等价桩类,不得把 worker 具体实现类(TaskEntry/AgentEntity/WorkerProperties/SlashCommandRegistry 等)当测试脚手架;类型确实需要跨 worker 与插件共享时,先下沉到 plugin-api(§1.1,文档先行)。
10. **文档**:本文档是唯一架构事实源;根目录 AGENTS.md 只写核心约束(每会话加载,保持精简),细节一律进 docs/。**收录判据(防膨胀红线)**:本文只收录「换一种实现方式仍然成立」的内容——模块边界与依赖方向、wire 协议与契约、持久化布局、不变式、设计决策与红线。以下实现细节**一律不得写入**本文,各自落在正确的位置:
    - 带日期/事故 ID/机型与占比数字的修复叙事(「2026-11 修复」「t_xxx 实锤」「某模型 xx%」)——教训须沉淀为不变式或红线的一句话,叙事本身留在 commit 信息;
    - 算法内部步骤与调参常数(阈值、比率、窗口大小、像素值、CSS 类名);
    - 文件级/函数级清单(哪个文件哪个函数怎么写)与重构步骤史(「步骤 N 起…」);
    - 日志语句、错误文案、构建脚本参数细节;
    - 单个 UI 元素的展示细节(某处显示什么、悬停提示什么)。
    实现细节的归宿:代码与测试的注释(最近真相源)、`docs/plugin-guide/`(插件契约与扩展点)、`docs/design-*.md`(专项设计)。向本文新增内容前先自检两问:**「换一种实现还成立吗?」**(不成立 = 实现细节,不写)**「与既有章节重复吗?」**(重复的不加,引用既有章节)。
11. **执行上下文管道(ExecContext,与 §14.0 事件管道同构;展开说明见 §7.20)**:执行数据沿 `task 层 → agent 层 → 工具执行链 → 授权链` 逐层传递,每层只消费自己层级的槽位,下层不知道上层是谁(task 还是未来 workflow):
    - **槽位判据**:ExecContext 槽位 = 任何执行主体都必然具备的核心属性与端口(subjectId/workspaceRoot/workspaceId/snapshot/emitter/agentFactory/interaction/metadata/dataDir/terminal/agents 活动实体注册表;configId 不设独立槽,经 `snapshot().configId()` 取);**插件功能与主体特有槽位不进核心接口**(file-change 的 collector 连 `TaskRuntime` 都不进——留 provider 任务级共享 collector + `RoundClosedListener` 落盘 + 插件自注册 RPC 读,§7.15.2;metadata 承载随 meta.json 落盘的持久数据如 attachments;**任务级开关**(AI 审议/无人值守/禁用网络)不走 metadata,真相源是 `slash-tokens.json`,经 `WorkerServices.slashTokens()` 查询);agents() 由 `AgentBuilder.build()` 自动注册(只 put,**不发事件**),插件不再手动 put/emit;`AgentContext.creator()` 槽位(task/subagent/ai-review)随 `agent.started` 持久化进台账,消费方按 creator 决定展示范围;`agent.*` 生命周期事件的发射单点是 `AgentEntity`(per-run 状态机),触发源 = `AgentStatusAdvisor` 流生命周期 + 交互层 ask 生命周期(§7.20.1);无子 agent 是正常形态。
    - **task 层构造并预绑定**:`TaskEntry implements TaskRuntime extends ExecContext`(subjectId=taskId);三预绑定端口同范式(静态代理,可链式套娃)——`emitter()`(主体事件口)、`agentFactory()`(主体 agent 装配,`create(agentId)` 单参)、`interaction()`(主体交互口,ask 的 context map 自动填 subjectId)——**上层预绑定能力往下传,不传裸工厂/裸服务、不传任务域对象**。
    - **agent 层/工具链唯一取数口**:`AgentContext.execution()`;`ToolContext`/`ToolExecutionContext`/`AdvisorContext`/`TaskLifecycleContext` 一律 `extends ExecContext`(重复字段与 `execution()` 槽位已删,消费侧直读槽位、横切层取主体 ID 一律 `subjectId()`,§7.20.5);per-run 数据经 prompt options 的框架 ToolContext map 下行,**不依赖 ThreadLocal**;`properties` 黑盒 map 与 `get("taskEntry")` 强转**禁止再现**。
    - **advisor 只取槽位**:`snapshot()`/`subjectId()`/`emitter()`/`terminal()`——不 import 任务域类型;**agent 生命周期事件发射单点(§7.20.1)**:`agent.started`/`agent.status`/`agent.done`/agent 级 `error` 一律由 `AgentEntity`(per-run 状态机 + CAS)发射;task 层与插件不得手搓这四个事件——插件唯一允许的调用是兜底 `Agent.claimTerminal(...)`(运行体从未进入流生命周期的路径),CAS 保证与 advisor 终态互斥;**subagent 是执行域能力插件(非任务域),全面中性化**(零服务依赖、creator 标记、台账收编 worker core `AgentLedger`、生命周期节点壳核分离、`task.agents` RPC 收回 task 域,§7.14/§7.20.1);**task 核心不依赖插件**(编辑重发截断只动 *.jsonl/rounds.jsonl 不删插件数据文件;`task.fileChanges` 方法名常量住插件侧);AI 审议 agent per-task 固定 agentId 复用会话;任务域插件(file-change/edit-resend/git)经 `TaskService`/`taskRuntime()` 取 `TaskRuntime` 是合法本职依赖。
    - **授权请求域中性**:`AuthorizationRequest(ExecContext, agentId, grantKey, prompt)`;GrantRegistry 按 `subjectId()` 分区、`dataDir()` 落盘;`InteractionService` ask 的 context map `"taskId"` 键保留(值=subjectId,前端兼容)。
    - **禁止**:横切层 import `TaskEntry/TaskRuntime/TaskInfo`;绕过 `ctx.agentFactory()` 手工组装四件套 map;绕过 `ctx.interaction()` 手动填 taskId context;worker 侧新增 properties 透传通道。工作流层实现 `WorkflowRuntime implements ExecContext` 后,同一条授权链、同一批 advisor、同一工具链零改动复用。
12. **worker 归属是 wire 事实,禁止客户端推断(与 §4.3/§5.2/§8.2 同口径)**:
    - **频道**:任务生命周期事件与任务流实时增量必须住在带 worker 段的频道里——`u.<K>.worker.<wid>.tasks`、`u.<K>.worker.<wid>.task.<taskId>.stream`;**禁止**把它们发回 ownerKey 级共享频道(那等于把 A 的任务混进 B 的前端列表)。
    - **payload**:`task.created/updated/deleted` 与 `tasks.list` 的每条任务摘要必须带 `workerId`。
    - **客户端**:`workerIdOfFrame()` 只认频道名的 worker 段与 `payload.workerId`;**禁止**「按 ownerKey 前缀匹配首个连接」这类猜测回退——判不出归属就丢帧,不许蒙一台;任务条目的 `workerId` 一律以**本次数据来源**为权威,不得让旧镜像压过新来源。
    - **worker**:收到 `subscriber.join` 时若频道 worker 段非本机、或该 taskId 本机不可见(内存/磁盘索引/任务目录均无),**一律不建定向推送器**(推送器永远等不到前端 ack,背压窗口永不释放 → 白占虚拟线程与出站队列)。
    - **hub**:定向投递 join 只允许「按频道名解析 worker 段 → 查现成 `findWorker` 索引」,仍属路由;不得新增任何订阅簿或状态(§6.1/§14.2 零状态红线不破)。
    - **客户端状态生命周期**:worker 数据连接是任务列表/详情/RPC/模型配置/插件的**作用域**;连接集合一变(`hubSession.onWorkerConnectionsChanged`),所有按 worker 缓存的状态必须由各自持有方失效,且全部 `task:*` 标签关闭——禁止只换连接不清状态(残留句柄指向已断开连接,表现为空白且不自愈)。
13. **出网投影单点(EgressProjector,与 §14.0 事件管道同构)**:客户端可见的事件与轮次**必须**经单点投影器 `EgressProjector`(worker `ship` 包,域中性)转换后才出网,**不得绕过**。出网口共 4 个(stream 推送、`task.poll`、`task.roundTail`、`task.rounds`),全部复用同一投影器与同一 filter 链;`wireEvent` 实现内聚进投影器、不再是公共 API(实现层无第二条出网路径)。
    - **过滤链**:filter 作用于 **pre-wire 的 `EventRecord`**,可丢弃(返回 null,**事件仍已落盘**)或改写 payload;**不得改动 `ext`**(ext 是落盘态镜像),加密等改写须在 `payload` 内自成信封;filter 域中性,只带 `subjectId`。插件据此可实现「审议过程不出网」等出网过滤——会话内容事件被丢弃,而状态与用量保持可见。
    - **seq 洞合法**:「**已落盘但不出网**」造成的客户端所见 seq 洞合法,与既有「瞬态占号不落盘」并列(§5.4);落盘(事实源)与出网(可投影视图)解耦,落盘永不因出网过滤而变更;`task.poll` 另以字符串字段 `nextSeq`(未过滤口径推进游标)支持客户端分页(§7.13)。
    - **task.agents 归属**:`task.agents` RPC 属 task 域(常量 `RpcMethods.TASK_AGENTS`,`TaskManager` 注册实现),语义 = 任务下**全部** agent,**无 creator 过滤**;subagent 插件不再提供该 RPC 与 web 前端功能,`list_agents`/`wait_agents`/`run_agent`/`stop_agent` 工具仍属 subagent 插件(§7.14/§7.20.1)。
    - **授权决议解析容错**:自动审议路径的输出解析失败 → 重试一次;仍失败 → ESCALATE(交下一节点/人工),不再 DENY;fail-closed(DENY)仅保留给超时/异常路径(§7.9)。
