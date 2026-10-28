---
title: 架构说明（唯一事实源）
nav_order: 2
has_children: false
---

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

版本统一由仓库根父 pom 锁定(Spring Boot 4.1.x / Spring AI 2.0.x),各模块不得各自升版本。**工程版本单点源**:根 pom 的 `<properties><revision>` 是全仓唯一 Maven 版本写点(CI-friendly versions),contract / hub / worker / plugin-api / plugin-parent 都不写自身 `<version>`,一律继承 `${revision}`;跨模块引用写 `${revision}`,**不得写 `${project.version}`**——它解析的是「消费方自己」的版本,在插件(自身版本与工程版本不同号)里会静默指错坐标。**插件只继承 `every-agent-plugin-parent`(插件 SDK parent),不继承应用的 `every-agent-parent`**:SDK parent 承载 Java 25 / 编码 / 插件依赖管理 / `plugin.json` 打包 / 测试栈,插件 pom 因此只剩「坐标 + 自己额外需要的依赖」,并且内置与外部(standalone)插件共用同一份脚手架模板。**仅由 `PluginLoader` 运行时加载的内置插件各自持有自己的版本(与它自己的 `plugin.json` 的 `version` 同号),不随工程版本走**,故升级工程版本时插件 pom 零改动(全部内置插件均如此,无例外)。。install/deploy 侧由 `flatten-maven-plugin`(`resolveCiFriendliesOnly`)把 `${revision}` 解析成实际版本:否则装进本地仓库/发到 Central 的 pom 里仍是表达式,仓库外消费者解析 parent 直接失败(`Could not find artifact every-agent-parent:pom:${revision}`;**实测** `-Drevision=<v>` 也救不回来,只能靠 flatten)。改版本跑两个脚本:`scripts/bump-version.py`(工程版本 + 三处 package.json,并体检残留硬编码 parent 引用)与 `scripts/sync-plugin-template-versions.ps1`(文档站字面量;脚手架 pom 模板已无版本字面量,不进规则表)。

---

## 4. 身份、信任与安全模型

### 4.1 无账户:密钥即身份

- 无用户表、无注册。**apiKey 即身份**:`ownerKey = sha256(apiKey)`(64 位小写 hex,即频道名中的 `<K>`)。
- 持有密钥即该命名空间全权,密钥泄露 = 身份泄露;公网部署必须 wss。

### 4.2 双道鉴权:hub key 管"连上",worker apiKey 管"访问"

- **hub key(必填)**:hub 启动即强制校验 `hub.hub-key`(配置直接填原始密钥,程序启动自算 sha256)。**必须显式配置**(安全整改:打包默认值为空 `${HUB_KEY:}`,jar 内不再携带任何开发默认密钥,fail-closed——未配置即启动失败,报错文案指明 HUB_KEY 环境变量 / hub.hub-key 配置项 / --hub.hub-key 启动参数三种配置方式);docker/systemd 部署链路(docker-compose.yml、deploy.py)均显式注入。所有 frontend/worker 连接必须在 hello 携带原始 `hubKey`,缺失/不符一律 `NOT_AUTHENTICATED`。
- **worker apiKey(按 worker 各自配置)**:连上 hub 后,要访问某台 worker 的任务、文件、git 数据,必须持该 worker 的 apiKey 建立对应命名空间的连接;worker 端 RPC 按连接身份处理。
- 前端因此有**两类连接**:一条"目录连接"(用 hubKey 连,订阅 `u.<sha256(hubKey)>.workers` 看在线 worker 目录)+ 每条 worker 一条"数据连接"(用该 worker 的 apiKey,订阅其 `u.<K>.worker.<id>.tasks` 与 `u.<K>.worker.<id>.evt`,任务与 RPC 走这条)。

### 4.3 频道即鉴权边界

- 频道名本身即 ACL:频道必须落在连接自己的 `u.<ownerKey>.` 前缀内(字符集 `[a-z0-9._-]`,长度 ≤160)。hub 对每个 sub/pub 强制校验,越命名空间返回 `ACL_DENIED`。
- 由于前缀校验由 hub 在连接级完成,A 的连接物理上无法订阅 `u.B.**`(B 为另一命名空间)→ 客户端伪造归属不可能。
- **同命名空间内互信(取舍)**:持有同一 apiKey 的任何角色可 pub/sub 该命名空间内任意频道;任务归属、越权等业务规则全部由 worker 处理(hub 不理解业务)。对单人/小团队部署可接受。
- **互信 ≠ 混频道:任务事件与任务流频道带 worker 段(§5.2)**。同一 apiKey 下可有多台 worker(同 `u.<K>.`,不同 workerId);任务生命周期事件与运行中任务的实时增量是**某一台 worker 的数据**,不是命名空间的数据。因此二者住在 worker 段频道 `u.<K>.worker.<id>.tasks` / `u.<K>.worker.<id>.task.<taskId>.stream`:一台 worker 的事件物理上只进它自己的频道,前端从「我订阅了谁的频道」即可无歧义地知道归属。**禁止**客户端按 ownerKey 前缀猜测帧来源 worker(§14.12)。

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
| `u.<K>.worker.<id>.task.<taskId>.stream` | **运行中任务实时增量**(worker 定向推送,`ext.target=sessionId` 只投该会话;worker 段用于区分同 apiKey 下的不同 worker) | 命名空间内任意角色 |
| `u.<K>.term.<termId>.stream` | **内嵌终端实时输出**(worker 定向推送：event `term.output` payload `{data: base64}`；进程退出推 `term.exited`)。**不加 worker 段**:`termId` 由前端生成且 `term.open` RPC 已按 worker 定向,一台 worker 的 PTY 只对它自己可见,不构成串台 | 命名空间内任意角色 |

> **stream 订阅通知**:前端 sub/unsub `u.<K>.worker.<id>.task.<taskId>.stream` 时,hub 发 `subscriber.join/subscriber.leave`(`payload={sessionId, taskId}`)——这是无状态 fire-and-forget 通知(hub 不存订阅簿),worker 据此按 (sessionId,taskId) 建/销 DataPusher(§7.13)。投递目标:**频道名带 worker 段时按 `ConnectionRegistry.findWorker(ownerKey, workerId)` 只投那一台**(worker 的 clientId 即其 workerId,§4.4);频道名无 worker 段时退化为投给该命名空间全部在线 worker。hub 只做「按频道名寻址连接」这一路由,不解释任务语义(§6.1 零状态红线不变)。

hub 对频道名不解释业务语义:它只做"前缀必须匹配本连接命名空间 + 字符集/长度合法性"这一件事。业务规则(如任务归属)全部住在 worker。

### 5.3 任务流事件(持久 / 瞬态)

事件分**持久**(落盘 + 回放)与**瞬态**(只存内存 EventLog 尾部,消费 seq 但不落盘)。任务流事件双通道:实时增量由 worker 定向推送到 stream 频道,历史回放/上滚/区间/兜底统一经 RPC `task.poll` 拉取——两路读同一本日志、同一 seq 空间,前端按 seq 去重归并。

> **落盘 ≠ 出网(出网投影层)**:「是否落盘」与「是否下发客户端」是两个正交维度——事件照进 `EventLog`(落盘不动),而**一切客户端可见形态**(事件与轮次)必须经**单点出网投影器 `EgressProjector`**(worker `ship` 包,域中性,与 `DataPusher` 同域)转换后才出网。出网口共 **4 个**:stream 推送(`DataPusher.push`)、`task.poll`、`task.roundTail`(事件级)与 `task.rounds`(轮次级),全部复用同一投影器与同一 filter 链;原 `wireEvent`(`TaskEvents.wireEvent` 与 `EventWireFormatter`)不再是公共 API,实现内聚进投影器(唯一出网转换者,新出网口在类型层面无法绕过)。投影器内跑**可插拔出网过滤链**(`EventEgressFilter`/`RoundEgressFilter`,按 `order` 排序,仿 `ToolExecutionInterceptorRegistry`):filter 作用于 **pre-wire 的 `EventRecord`**,可丢弃(返回 null,**事件仍已落盘**)或改写 payload;**不得改动 `ext`**(ext 是落盘态镜像),加密等改写须在 `payload` 内自成信封;filter 域中性,只带 `subjectId`(task→taskId,workflow→workflowId)。这样「AI 审议过程不出网」只作用于出网侧,落盘与 seq 空间完全不变(§5.4/§7.1/§14.13)。

| 频道 | 事件 | 持久 | payload 要点 |
|---|---|---|---|
| cmd | `rpc` | — | `reqId, method, params` |
| evt | `rpc.ok` / `rpc.err` | — | `reqId, result` / `reqId, code, message` |
| evt | `rpc.data` | — | `reqId, batch, hasMore`(流式应答分批) |
| evt | `rpc.progress` | — | `reqId, message, pct?` |
| evt | `config.changed` / `fs.changed` / `workspaces.changed` | — | `{keys}` / `{workspace,path,kind}` / 注册表快照 |
| `worker.<id>.tasks` | `task.created` / `task.updated` / `task.deleted` | — | 任务摘要(**必带 `workerId`**,与 `tasks.list` 的 TaskSummary 同形)。**例外**:`pendingInputs` 待消费输入快照由 task-input-queue 插件在广播 payload 里自行补充(核心 `TaskSummary` 不含队列概念,`tasks.list` 也不含——队列专有数据的前端数据源是插件 RPC `task.queueSnapshot`,§5.5);deleted:`{taskId, workerId}` |
| task.poll / stream | `user.message` | ✓ | 用户输入入日志:`{content, data:{rawContent?}}`(统一事件模型,content=AI 可见明文;rawContent=原始输入含 opaque token 串,仅用于前端回放还原胶囊,在 `data` 内、非顶层)。**agent 层一视同仁**:主 agent 任务输入与派生 agent(子/审议)收到的指令(spawn 初始任务/续跑新指令,subagent 插件发)同名落盘,仅 agentId 不同(主不带/派生必带)——`<agentId>.jsonl` 由此自包含,ConversationLoader 按 agentId 中性重建任意 agent 会话(§5.3/§7.14);主线程 user 气泡/轮次开合是 task 层职责(轮次索引只认主 agent 的 user.message 开轮),前端按 agentId 区分展示(派生 agent 的 user.message 不进主气泡,以 trace 形态展示) |
| task.poll / stream | `delta` / `thinking` | ✗ 瞬态 | 逐 token / 推理增量(主/子同名,子带 agentId) |
| task.poll / stream | `message` | ✓ | **每轮权威完成记录**:`{thinking, text, toolCalls:[{id,name,arguments}], agentId?}`,另可选 `thinkingStartedAt`/`thinkingDurationMs`(毫秒,思考阶段真实流式耗时:首个→末个思考增量,与 `tool.result` 的 startedAt/durationMs 同构;供前端思考块实时读秒与历史回放冻结展示,老事件缺失 must-ignore) |
| task.poll / stream | `tool.started` | ✗ 瞬态 | 单个工具调用开始执行的**事实声明**(per-call 工具执行链下行段发射,§7.14.3):`{callId, name, arguments, startedAt(毫秒)}`。callId 直配 message.toolCalls[].id(前端调用行点亮 running + 读秒 `now - startedAt`,不依赖执行顺序);瞬态不落盘(同 delta/thinking 路径)→ 历史回放无此事件,直接展示 result 的 durationMs;中断批已 started 无 result = 「执行中被中断」(终态清理)。老前端未注册该 kind 走 no-op(不进线程),must-ignore |
| task.poll / stream | `tool.result` | ✓ | `{callId, name, summary, status, startedAt?, durationMs?}`(工具结果文本 = 链上行改写后的 `responseData` 原文——secret-redaction 掩码/rg-hint 追加照常生效,**事件侧不截断**:事件/落盘/推送不再复加损失;`startedAt`/`durationMs` 毫秒,计时在 per-call 链下/上行切口间自然可得,可选字段老前端 must-ignore)。**发射点 = per-call 工具执行链上行段**(每次调用一条,callId 直配,不再从 conversationHistory 回扫)。`status` = `success`/`error`:①结果串以 `ExecResults.TOOL_FAILURE_PREFIX`(`[工具执行失败]`)开头 → error(异常处理器与缺工具兜底的产出前缀约定,工具抛异常/缺工具都由它覆盖);②否则查**结构化结果账本** `ToolOutcomeLog`——Spring AI 的类型层没有失败位(`ToolResponseMessage.ToolResponse` 只有 id/name/responseData,框架也不把工具调用 id 交给工具层),shell 退出码/超时仍由各后端执行器按「工具名+命令」记账(**非零退出码即失败**,2026-12 用户决策),链上发射侧按同键查账兜底(旧「工具名+入参」抛异常记账线已随 callId 账落地删除,其覆盖面由①前缀约定取代);**不做通用文案匹配**——那会让「读/grep 到失败文案源码」的命令自指误判为失败。链上行同时按 **callId** 写账(`recordCallOutcome`,后置消费者如 file-change 一阶段记账的查询键,§7.15.2);前端据此把失败工具块标红;无 status 字段的历史记录按成功处理 |
| task.poll / stream | `usage` | ✓ | 每轮模型 token 实测用量 + 累计 |
| task.poll / stream | `ask.create` / `ask.state` / `ask.resolved` | ✓ | 见 §7.8 |
| task.poll / stream | `agent.started` / `agent.done` | ✓ | agent per-run 生命周期(主/子统一,§7.20.1);started 携带 title/creator/metadata,done 携带 usage+末轮正文 |
| task.poll / stream | `agent.status` | ✓ | 主/子统一状态事件:running / waiting-user / done / failed / stopped |
| task.poll / stream | `error` / `cancelled` | ✓ | `{message(带 agentId 即该子 agent 失败)}` / `{by}` |
| stream | `message.edited` | — | 消息编辑同步事件(非持久,worker 截断磁盘后广播到 stream 频道):`{seq, text, rawContent?}`;客户端据此移除 seq > 该消息的本地事件并更新消息内容 |
| task.poll / stream | `task.trace` | ✓/✗ 按 ext | **统一纯显示 trace**(重试生命周期、任务耗时、模型容灾、授权审计等):`{traceId, kind, title, summary?, content?, status?, createdAt, metadata?}`;`ext.persist=false` 标记瞬态实例 |
| task.poll / stream | `round.opened` / `round.closed` | ✗ 瞬态 | 轮次开/闭通知:`{startSeq,user}` / `{startSeq,endSeq,finalReply}` |
| stream | `plugin.<pluginId>.*` | ✗ 瞬态 | 插件自定义任务直推事件(如 file-change 轮末摘要 `plugin.file-change.roundChanges`,§7.15.2):data 由插件自定;宿主前端 taskStream 不折入线程 items,一律透传领域事件 `plugin-task-event` 由插件 web 端消费;瞬态不落盘,历史/补齐走插件自持落盘 + 插件 RPC |
| input | `ask.reply` | — | `{askId, answer}` |
| input | `stream.ack` | — | `{taskId, creditIndex}` 流消费进度回报(§7.13 背压) |

> **线上 wire 形态**:事件名不分主/子 agent(delta/message/error 同名),归属由 `agentId` 决定——**所有事件必带 payload.agentId**(主 agent = mainAgentId,子 agent = 各自 id;agent 层一视同仁);前端按 **task 层传入的固定 mainAgentId**(`task.agents` 应答 / `TaskSummary.mainAgentId`)判主,不使用「缺省=主线程」魔法;旧任务事件行 agentId 缺失时前端归一到 mainAgentId;磁盘 jsonl 每行必记 agentId。`agent.started`/`agent.done`/`agent.status` 为 agent 生命周期专用名:**状态 = per-run() 生命周期**——每一轮 `Agent.run()` 是一个完整周期 `agent.started → agent.status{running} → [agent.status{waiting-user} ⇄ running]* → error? → agent.done → agent.status{done|failed|stopped}`,队列取下一条输入/复用续跑即开新一轮(again `started`)。四类事件由 advisor 链驱动、由 agent 实体一处发射(`AgentStatusAdvisor` 把流生命周期信号翻译成 `AgentEntity` 的 per-run 状态机方法,§7.20.1),**主 agent 与各插件派生 agent 一视同仁**;task 层与插件不再手搓 agent.* 事件(插件仅在「运行体从未进入流生命周期」的兜底路径调 `claimTerminal`,CAS 保证不重复)。task 级状态(`task.updated`,由 StatusNode/TaskManager.setStatus 驱动)与 agent 级状态分属两个维度,不互相替代。

单帧上限默认 16 MiB(可配 `hub.max-frame-bytes`);工具结果事件**不在事件侧截断**(原样携带 `ToolResponseMessage.responseData`,不设独立字符上限),其体量上界由执行侧输出上限保证。注意模型侧另有独立的上下文压缩闸门(`worker.limits.context-max-tool-result-chars`,默认 40000,order = `ToolCallingAdvisor.DEFAULT_ORDER + 400` 即每轮模型请求前生效):超大结果发给模型前会被首尾保留截断,故「前端所见」以事件原文为准,与模型最终读到的压缩文本不承诺逐字相同(前者是后者的输入)。

### 5.4 seq 规则(协议的秩序根基)

- `seq` 由 worker 按任务**从 1 单调递增分配**,写入事件日志的瞬间确定;**跨运行延续**(再运行从上次水位接续)。
- **瞬态事件也消耗 seq**(delta/thinking 等消费序号但不落盘)→ **磁盘回放的 seq 有洞是合法状态**;seq 仍严格递增、从不复用。
- **出网被过滤的事件也造成客户端所见 seq 洞**:事件已落盘(运行中日志永不修剪),但被出网过滤链丢弃(§5.3)→ 客户端按 seq 看到的序列因而有洞,与「瞬态占号不落盘」同属合法状态;seq 空间本身不变、仍严格递增,前端按 seq 去重归并照常工作。
- **`seq` 属于任务流事件空间**(每任务一个),wire 上以**字符串**传输(`String.valueOf(seq)`):seq 是 64 位 Snowflake(≈10^17),远超 JS `Number.MAX_SAFE_INTEGER(2^53)`;按 number 输出会在浏览器 `JSON.parse` 丢精度。前端 `compareSeq` 按「位数优先 + 字典序」比较字符串,等价数值序且不丢精度。
- 实时与历史来自同一本日志、同一 seq 空间 → 前端按 seq 去重、排序;游标 lastSeq 取自事件项的 seq 字符串。
- cmd/evt 频道的 rpc 帧不携带 seq(seq 只属于任务流事件空间)。

### 5.5 三条动词与 RPC 方法注册表

worker 端 `RpcDispatcher` 注册方法;应答回**请求来源连接**的 `evt` 频道,reqId 供请求方匹配,同命名空间其余前端可作缓存刷新。

| method | 说明 |
|---|---|
| `tasks.list` | 任务列表快照(内存运行中 + 磁盘索引合并;可选 `workspace`/`limit`/`offset`/`taskIds`) |
| `task.run` / `task.cancel` / `task.delete` | 运行任务(**创建/续跑合一**):不传 taskId=新建(必带 workspace)并开跑;传 taskId=载入老任务历史续跑(运行中则入队)。`metadata` 为一次性插件参数(经 `runParams` 下传、不落盘),队列插件认 `{insert:true, index}`=队列项「插入到当前对话」(§7.16)。delete = 唯一删除路径(运行中拒绝) |
| `task.poll` | 任务流纯拉取:历史(磁盘)∪ 实时(内存尾部)按 seq 归并,**经 `EgressProjector` 投影后**下发(§5.3);支持 afterSeq/beforeSeq/区间/mode('events'/'rounds')/waitMs 长轮询;应答新增**字符串字段 `nextSeq`** = **未过滤口径**的推进游标(批尾原始 seq,供客户端分页,避免「过滤后空批=取完」的误判),`hasMore`/`firstSeq`/`lastSeq` 保持 raw 语义 |
| `task.rounds` | 轮次索引拉取(rounds.jsonl 全部行 + 运行中未闭合轮 open;旧任务首次惰性全量生成落盘),**经 `EgressProjector` 轮次级投影后**下发(§5.3);rounds 项与 **open 轮对象均携带 `roundId`**(稳定主键:**开轮落盘即生成、闭合原位改写沿用不重生成**,open 轮取自 rounds.jsonl 未闭合行、与该轮随后出现在 rounds 里的 roundId 相同——前端轮次分页 key 的天然候选;旧行缺失不写,老前端/老数据 must-ignore 双向兼容,§5.6) |
| `task.roundTail` | 按轮起点(startSeq)取该轮末尾 limit 条事件,用于初始渲染,**经 `EgressProjector` 投影后**下发(§5.3) |
| `task.agents` | 任务下**全部** agent 台账一次性拉取(**task 域 RPC**:常量入 `RpcMethods.TASK_AGENTS`,由 `TaskManager` 注册实现,不再属 subagent 插件;前端打开任务详情、建 agent 胶囊列表的唯一取数口;语义 = 主 agent + 各来源派生 agent(含 `creator=ai-review` 审议 agent),**无 creator 过滤**;数据源 live 优先 `AgentLedger.getLiveAgents`,冷任务读 agents.json,旧任务回退 meta.json 的 agents 数组只读;按 createdAt 升序;应答 `{agents:[台账项], mainAgentId}`)。注:`list_agents`/`wait_agents`/`run_agent` 等**工具**仍属 subagent 插件(执行域能力),不迁移(§7.14/§7.20.1) |
| `task.fileChanges` | **file-change 插件注册的 RPC**(方法名常量与语义全住插件侧,task 核心不感知,§14.11/§7.15.2):带 `roundId` → 该轮变更全文 `file-changes/<roundId>.json` 的 `{changes:[...]}`(含 beforeContent/afterContent);**省略 `roundId`** → 全任务各轮轻量摘要 `{rounds:[{roundId, changes:[{filePath,fileName,changeType,saveCount}]}]}`(前端轮末面板一次拉全,不做逐轮 N 次 RPC) |
| `task.queueList` | 当前运行许可/排队快照 `{availablePermits, queueLength, queue:[taskId...]}`(task-queue 插件注册,§7.14.4) |
| `task.queueRemove` / `task.queueMove` | 删除/重排某条队列输入(task-input-queue 插件注册;运行中热队列直改内存、终态改磁盘悬空队列 queue.jsonl) |
| `task.queueSnapshot` | 拉取队列快照 `{taskId, pendingInputs:[…), pendingInputsRaw:[…]}`(task-input-queue 插件注册;热任务取内存 InputQueue,终态任务读 queue.jsonl 悬空队列)——前端队列面板自持数据源(插件专有数据不进核心 ComposerPanelCtx/taskStore);`pendingInputsRaw` 与 `pendingInputs` 等长按位对齐(空串=无原始内容),供面板「编辑」回填还原胶囊;入队/消费/增删均广播 `task.updated`(携带 pendingInputs)驱动刷新 |
| `task.run{taskId, metadata:{editSeq}}`(消息编辑重发) | 编辑已发送的用户消息:**无独立 RPC**(历史方法 `task.message.edit` 已退役),统一走 `task.run` 携带 `metadata.editSeq`,由 task-edit-resend 插件的 `EditResendNode`(order=877,§7.14.1)截断 seq > 该消息的所有磁盘事件(仅 *.jsonl/rounds.jsonl,**不删插件数据文件**——agents.json/file-changes/ 残留陈旧条目被接受,task 核心不感知插件文件名;后续可增加截断事件通知由插件自清)、原地更新该消息内容、广播 `message.edited` 同步事件、冷启动重跑(不写新 user.message,对话历史已含编辑后的消息);任务运行中拒绝 |
| `config.get` | 模型配置读取(Spring 配置承载,见 §7.17;apiKey 恒为掩码——前 4 + `…` + 后 4,明文不出 worker) |
| `config.reload` | 重新读取模型配置(重新解析 worker.models,应用用户在外部 YAML 中的修改);广播 `config.changed{keys:["models"]}` |
| `config.model.add` / `config.model.update` / `config.model.remove` | 模型配置增删改(设置页「模型配置」表格):add 参数 `{configId, provider, baseUrl?, fullUrl?, model, apiKey?, params?, isDefault?}`(configId 非空且不重复、model 非空、baseUrl/fullUrl **二选一**(两者都填或都不填 → `BAD_PARAMS`),否则 `BAD_PARAMS`),update 参数同 add 但按 `configId` 定位已存在条目(不存在 → `NOT_FOUND`);`apiKey` 留空/缺省 = 保持原 key 不变(明文不出 worker,前端仅见掩码),`params`/`isDefault` 缺省同样保留原值,其余字段整条覆盖;remove 参数 `configId`(不存在 → `NOT_FOUND`);三者均把结果整表原子落盘 `<homeDir>/application-worker.yaml` 的 `worker.models` 段(保留文件内其他配置键)+ 热重建内存索引 + 广播 `config.changed{keys:["models"]}`;仅影响后续新建任务,运行中任务用创建时冻结的快照 |
| `skill.list` | 该 worker 的全量 skill 清单 `[{id, name, description, knowledgePath, blacklist?, whitelist?}]`——数据源 = `skillsDir` 目录扫描(唯一真相源),每项附各自的按工作区暴露配置(黑白名单并存,黑名单优先);供设置页「Skill 列表」表格(按 worker 分组展示,不同 worker 的 skillsDir 不同、同名 id 非同一 skill,不跨 worker 合并;§7.17) |
| `skill.exposure.set` | 设置某 skill 的暴露配置(参数 `skillId` + `blacklist:[root,...]` + `whitelist:[root,...]`):`skillId` 须在当前目录扫描结果内(否则 `NOT_FOUND`),`blacklist`/`whitelist` 逐项须为该 worker 已注册工作区 root(normalize 后字符串相等,未注册项 `BAD_PARAMS` 拒绝);**黑名单**:列出的工作区不暴露;**白名单**:非空时仅列出的工作区暴露;**黑名单优先**:同时命中黑白名单按黑名单处理;两个清单都空 = 恢复默认(删除键);`AtomicFiles` 原子落盘 `<homeDir>/skill-exposure.json` 后广播 `config.changed{keys:["skills"]}`。`/` 菜单不受影响(永远全量);运行中任务不受影响(skill 注入发生在 run 开始;§7.17) |
| `workspaces.list` / `workspaces.add` / `workspaces.remove` / `workspaces.addExternalRoot` | 工作区注册表 CRUD(多工作区并行);addExternalRoot = 注册工作区外部授权根(`@` 弹窗 `+` 显式选择路径,§7.17) |
| `workspaces.resolveMissing` | 启动自检缺失工作区落定:action=delete(删除注册并级联任务数据)/redirect(纠正到新目录并迁移任务归属) |
| `fs.list` / `fs.reveal` / `fs.read` / `fs.write` / `fs.mkdir` / `fs.move` / `fs.delete` / `fs.browse` | 工作区文件操作,**必带 workspace 参数**,沙箱限定;文件树懒加载;条目 stat 逐条容错:`fs.list`/`fs.reveal` 对单条目读属性失败(典型:Windows 上 WSL/npm 生成的 LX symlink reparse 点,Win32 跟随链接读属性报 ERROR_CANT_ACCESS_FILE「系统无法访问此文件」)不拖垮整个枚举——先回退 NOFOLLOW 读链接自身属性,再失败按 `dir:false`、时间戳 0 的普通文件条目返回(前端按「未知」展示);沙箱附加根按操作语义分流(§7.17):**只读操作**(`fs.list`/`fs.reveal`/`fs.read`/`fs.revealInOs`)并入工作区外部授权根(externalRoots,完全读写已授权)+ 系统技能目录(skills 读写免授权,§7.17)+ PermissionGate 在途授权根(`GrantRegistry` 内存全主体并集;任务收口 `gate.evict` 驱逐即失效,授权生命周期天然跟随任务)——使前端「打开文件」标签页能读取 AI 已读的 skill/外部授权/本任务经授权读过的文件(如点击工具调用里的工作区外路径 chip);**写操作**(`fs.write`/`fs.mkdir`/`fs.move`/`fs.delete`)仅并入 externalRoots(完全读写),技能根同样并入(读写均开放,§7.17);`fs.browse`(不经沙箱)列盘符/逐层浏览目录,可选 `includeFiles`(boolean,缺省 false 仅目录,完全兼容现有行为):true 时目录条目同时列出文件,每条目带 `kind:"file"\|"directory"`,响应带 `supportsFiles:true` 能力标记(前端能力探测;老前端不传/老 worker 不带按 must-ignore 双向兼容,§5.6) |
| `fs.readRaw` / `fs.writeRaw` / `fs.statRaw` / `fs.grantRawRoot` | 机器绝对路径 Raw 系列(文件标签页用户操作专用,不携带 workspace 参数;**不再「信任前端动作来源」**)。**信任边界与 fs.\* 沙箱同构(§14.6)**:路径 normalize 后做词法前缀 + 最近已存在祖先 realpath 双前缀校验,合法根集合 = 全部在册工作区根(词法+realpath)∪ 各工作区外部授权根(externalRoots,§7.17)∪ 系统技能目录(§7.17;Raw 系列读写均放行——设置页 skill 编辑走 writeRaw)∪ PermissionGate 在途授权根(`GrantRegistry` 全主体并集,任务收口驱逐即失效)∪ **本会话用户放行目录**(`RawRootRegistry`);越界一律 `SANDBOX_DENIED`(消息含路径),前端识别错误码后弹确认框(与 §7.8 kind=authorization 同语义),用户确认 → 调 `fs.grantRawRoot` 注册放行目录 → 自动重试原操作。`fs.grantRawRoot` 参数 `{path}`(机器绝对路径,文件→取所在目录),放行粒度=目录(realpath+词法双形态入册,符号链接形态确认后重试双前缀均可命中),**会话级内存态**(per ownerKey,worker 重启即清、不落盘、不进沙箱后端下发),文件系统根(盘根)拒收;**仅此 RPC 能注册放行目录**,readRaw/writeRaw/statRaw 自身绝不自动放行。`fs.readRaw` 应答形态与 fs.read 一致但大文件**流式分批**(≤INLINE_MAX 256KB 内联 base64,超限按 CHUNK 192KB rpc.data 分批 + 末帧 ok 汇总),不整读进内存;`fs.writeRaw` 保留 16MB 单次写入上限;`fs.statRaw` 返回与 fs.list 条目同构字段。`fs.browse` 保持全盘逐层浏览不变(目录/文件选择 UX 必需,仅列名字清单、不返回内容) |
| `fs.revealInOs` | 在**运行 worker 的宿主机器**上打开系统文件管理器并选中目标(资源树右键「在系统文件管理器中显示」,对标 VSCode Reveal in File Explorer),**必带 workspace 参数**,路径经沙箱 `resolveExisting` 校验(防越界/符号链接逃逸);Windows `explorer.exe /select,<path>`(fire-and-forget,退出码不表征成败)、macOS `open -R`、Linux 优先 freedesktop FileManager1 `ShowItems` 选中目标、无 dbus/无注册实现退化 `xdg-open` 打开所在目录;argv 直传无 shell 解析;无桌面环境(无头 worker/无文件管理器)抛 IO 异常转 RPC 错误;远程访问场景窗口在 worker 所在电脑弹出;老前端不调用零影响 |
| `term.open` / `term.input` / `term.resize` / `term.close` | Web 内嵌终端会话(§7.18，真 PTY)：`term.open` 必带 `workspace`+`termId`(前端生成,先 sub 频道再 open 防丢首帧)+`path`(目录,沙箱 `resolveExisting` 校验,非目录拒收)+`cols`/`rows`+可选 `shell`，返回 `{termId, pid}`；`term.input` 入参 `{termId, data(base64)}`；`term.resize` 入参 `{termId, cols, rows}`；`term.close` 入参 `{termId}`。输出经 `u.<K>.term.<termId>.stream` 定向推送；老前端不调用零影响，老 worker 无此方法时前端按 must-ignore 降级提示 |
| `search` | **统一搜索入口(破坏性变更:取代已移除的 `fs.search` / `fs.find` / `task.search` 三方法,不保留兼容壳——老客户端调用旧名一律 `UNKNOWN_METHOD`,见 §5.6)**——**核心零类型感知**:不做任何硬编码搜索类型名/过滤字段名/分支,只「遍历 `SearchProviderRegistry`(可选按 `kinds` 过滤)→ 触发 provider → 聚合」,类型清单完全来自注册表;入参**仅** `workspace`(必带,沙箱 jailed 到工作区根) / `pattern`(搜索词,核心不解释,原样交给各 provider) / `kinds?`(string[],可选类型数据过滤;缺省 = 不传 = 全部已注册 provider 都跑) / `filters?`(**不透明参数袋**,`{ [key]: unknown }`,key 为 `${kind}.${field}` 命名空间,**核心绝不解释其内容**);**核心契约无** `isRegex` / `caseSensitive` / `wholeWord` / `include` / `exclude` / `scope` / `maxResults` 等具名字段——它们一律是各 provider(搜索类型)自己声明的过滤字段,值经 `filters` 袋透传;应答 `{matchCount, truncated, items:[{kind, providerId, score, ...}]}`(`truncated` = 各 provider 自身触顶的「或」,核心不再据此裁剪);`items` 为平铺命中项,按 `kind` 决定位置键去重与展示字段(内置 `file-content` / `file-name` / `task`,插件 kind 由 provider 自定义);**内置三引擎均为 `SearchProvider`**(`file-content` 工作区文本内容 rg `--json`、`file-name` 文件名 rg `--files` + basename 匹配、`task` 任务内容 rg + rounds.jsonl 后处理),在 worker 启动装配期注册进注册表(内置 `order` 在插件 provider 之前);**provider 自持职责**:①结果上限(`worker.search.file-max-results` / `task-max-results` 归 provider,核心契约无 `maxResults`);②文件类 provider 自持「默认排除 `.git`」;③`filters` 各字段语义自解释(文件类 `scope`(`type:'path'`) / `include` / `exclude` 若作 rg 搜索路径下推,须 provider 自行经 `Sandbox.resolveExisting` jailed);单个 provider 抛异常/超时仅 WARN 跳过,无 provider 时行为可读报错;大结果复用 `rpc.data` 分批 + 末帧 `ok` 汇总(§5.4);结果项可带统一可选增补字段 `kind` / `providerId` / `score`(§8.5;must-ignore,老客户端零影响,§5.6) |
| `git.status` / `git.log` / `git.diff` / `git.show` / `git.commit` / `git.pull` / `git.push` / `git.discard` / `git.init` / `git.clone` / `git.remote.add` / `git.remote.list` | 工作区 git 快操作(git 插件注册,必带 workspace);由 `NativeGit` 调宿主原生 git argv 直传执行(§7.12) |
| 大型迁移(批量 checkout / 大仓库迁移) | 建为 Task,进度走任务流 |
| `git.credential.save` | 保存 git 远端凭证(AES-GCM 加密落盘,§7.12;只写不读回) |
| `slash.list` / `slash.select` / `slash.cancel` / `slash.taskTokens.apply` / `slash.taskTokens.list` | 斜杠命令清单与选中/取消/任务级 token 应用/任务级 token 现值拉取(§7.16) |
| `mention.query` | `@` 文件搜索(后端子序列模糊匹配 + 隐藏规则 + 截断 10 条),**独立于统一 `search`**(不属搜索面板,@ 提及用,保持不动);插件经 `ctx.registerSearchProvider` 注册的 `SuggestionProvider` 的 `suggest` 结果按统一增补护栏聚合(§8.5:内置在前、按 `order()` 升序追加、按 `kind=file`+`path` 去重,总条数仍受截断约束;单个 provider 抛异常/超时仅 WARN 跳过) |
| `rpc.cancel` | 取消进行中的长 RPC(Future.cancel) |
| `plugin.list` / `plugin.install` / `plugin.uninstall` / `plugin.enable` / `plugin.disable` / `plugin.webSource` / `plugin.asset` | 插件管理(worker 核心注册,§8.5):目录(含禁用项)、`.eap` 安装/卸载、启停(install/uninstall/enable/disable 均**重启 worker 后生效**)、web 源码/静态资源读取(jail 校验 + 大小写不敏感回退);uninstall 目录被占用时先落墓碑 `.uninstall-pending`、错误附排查指引、worker 重启自愈(§8.5) |
| `sys.methods` / `sys.info` | 能力发现:本 worker 支持的方法清单与版本、workspace/模型/hub 元信息 |
| `worker.restart` | 重启 worker 进程(设置页「重启 Worker」按钮):应答 ok 后由独立非守护线程复走 `/admin/shutdown` 同款关闭路径(优雅关闭 ApplicationContext:断开 hub 连接、销毁插件),随后以**重建的启动命令**把本进程重新拉起——**自重启,不依赖 desktop/任务计划等外部 supervisor**。命令重建策略:优先 `ProcessHandle.current().info()` 的完整 argv(Linux 可用);Windows 上 `arguments()`/`commandLine()` 不可用(实测 JDK 25 返回空),按 `sun.java.command` + `java.class.path` 重建最小命令——`-jar` 形态 = 原 exe(`info.command()`,保留 javaw/java 区别)+ `-jar` + fat jar 路径(classpath 单条目即 jar 路径)+ 程序参数;classpath 形态(dev:`spring-boot:run`/IDE)= exe + `-cp java.class.path` + 主类/参数;原 JVM `-D`/`-X` 选项不保留。子进程继承 cwd(`user.dir`)、环境变量与 stdio(`inheritIO`,desktop/bat 启动时即继续写 worker.out.log),且在新 JVM 启动前旧 JVM 已释放 6102 端口(先 `context.close()` 返回再 spawn,无端口竞态)。进程级冷启动:运行中任务被中断,重启后 boot 扫描把非终态任务标 failed(§7.7),任务数据不丢;启动命令无法重建时拒绝执行并应答 err(worker 不重启) |

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

worker 的 agent 执行**复用 Spring AI 2 框架**,不手搓 agent 循环/工具循环/响应聚合。(红线:`AgentRunner` 等执行链只是很薄一层——把 `Prompt` 交给 `ChatClient`,`ToolCallingAdvisor` 接管工具循环,自定义 `Advisor` 注入技能/记忆/护栏等增强;新增 agent 能力优先做成 Advisor。)

主 agent advisor 链(每 run 新建实例,状态随实例隔离):

```
[外层 → 内层;HP = HIGHEST_PRECEDENCE,DEF = ToolCallingAdvisor.DEFAULT_ORDER(=HP+300);全部经 AdvisorProvider 注册、按 order 排序]
AgentStatusAdvisor(agent 生命周期事件,HP+5,最外层) → RoundIndexAdvisor(轮次索引+耗时,HP+10) → SystemInfoAdvisor(HP+50,system-info 插件) → AgentsMdAdvisor(HP+60,agents-md 插件) → SkillAdvisor(HP+100,skill 渐进式披露索引;per-run 装配取 workspaceRoot,默认全部 skill 进提示词、按当前工作区暴露配置(黑白名单并存,黑名单优先)过滤,§7.17) → GitAutoSyncAdvisor(HP+140,git 插件自动同步) → SlashTokenResolveAdvisor(HP+150,opaque token 解析) → FileAttachmentAdvisor(HP+160,@ 图片附件注入)
→ [工具循环 ToolCallingAdvisor 层] WorkerToolEventAdvisor(DEF,核心事件发射) → FileChangeAdvisor(DEF+1,file-change 插件,轮末收口;写侧记账在文件工具写锁内回调 FileWriteListener(FileChangeRecorder),§7.15.2)
→ [工具循环外侧重试/护栏层] DialogInsertAdvisor(队列项「插入到当前对话」,主 agent 专属,task-input-queue 插件,DEF+30) → EmptyResponseRetryAdvisor(空响应重调,独立插件,DEF+100)
→ TransientErrorRetryAdvisor(瞬时错误退避,独立插件,DEF+200) → AdaptiveMaxTokensAdvisor(自适应输出预算,独立插件,DEF+250,Guard 外侧)
→ ModelLengthGuardAdvisor(输出预算耗尽护栏,独立插件 model-length-guard,finish_reason=length,DEF+300)
→ ContextCompressionAdvisor(上下文压缩,独立插件,DEF+400) → RateLimitAdvisor(模型请求限流,独立插件,DEF+500) → TokenCalibrationAdvisor(token-estimator 插件 token 估算校准记账,order=0 绝对值=全链最内层)
```

- agent 生命周期事件由 `AgentStatusAdvisor`（全链最外层，`StreamAdvisor`）驱动 `AgentEntity` 的 per-run 状态机发射：`adviseStream` 入口 → `beginRun()`（`agent.started` + `agent.status{running}`）；`doOnComplete`/`doOnError`/`doOnCancel` → `claimTerminal(completed|error|stopped, 附言)`，终态发射顺序固定 `error? → agent.done → agent.status{终态}`。它**不继承** `ToolCallingAdvisor`——只需「整轮一次」的流生命周期信号，而工具循环递归只重入比自己更内层的 advisor，故挂在外侧恰好每次 `AgentRunner.run()` 进一次；最外层还保证终态晚于 message/usage 与 rounds 闭合落盘（前端以主 agent 终态 `agent.status` 为拉 rounds 的触发点）。详见 §7.20.1。
- 核心事件发射由 `WorkerToolEventAdvisor` 完成(继承 Spring AI `ToolCallingAdvisor`,重写受保护 hook 发射 delta/message/usage 等**模型流**事件,**不另起一层重复实现递归循环**)。工具执行侧事件(`tool.started`/`tool.result`)已迁 per-call 工具执行链(§7.14.3 `ToolEventInterceptor`,advisor 回归纯模型流事件——原 `doGetNextInstructionsForToolCallStream` 从 conversationHistory 回扫结果并按「名字+入参」查账的发射段已删)。
- 重试退避算法由 `worker.retry.strategy` 选择,空响应重试与瞬时错误重试共享:`fixed`(默认,固定 `backoff-base-ms` 间隔、`max-request-retries` 默认 30 次,适合网络抖动场景的稳定节奏恢复)或 `exponential`(`base × factor^(n-1)` 递增退避);未知取值回落 `exponential`(旧行为)。
- `ModelLengthGuardAdvisor`(独立 `model-length-guard` 插件,order=工具循环+300,瞬时重试内侧、上下文压缩外侧):识别「输出预算耗尽」这一确定性失败,三条路径殊途同归报同一错误。**帧+异常双信号改造**:检测到耗尽时先向下游下发合成 `finish_reason=length` 帧,再抛非重试异常 `ModelLengthExhaustedException`。①真实 length 帧:不在 doOnNext 就地抛(会把元素转成 error,帧到不了外层)——改为透传帧 + 记 flag,流 complete 时若 flag 置位再抛;②stall/③断流:onErrorResume 里先 concatWith 下发合成帧(`ChatGenerationMetadata.builder().finishReason("length")`)再 Flux.error(原判定异常),Reactor 保证 onNext 先于 onError 到达外层。三条路径的「输出已达上限」判定(无 tokenizer,CJK≈1 token、其余≈4 字符 1 token):模型配置了 maxTokens 时用 20%~40% 容差的 ≈maxTokens 比例判定;**未配置 maxTokens 时**(provider 用服务端默认预算,客户端不可见)用绝对阈值兜底——自估输出 ≥ `worker.limits.length-disconnect-min-tokens`(默认 32768)即判定,「断流+已输出数万 token」是预算耗尽强信号,重试代价极高(每次重放整段长思考,长思考模型一轮可耗数万 token、循环几十分钟)。错误为自定义非重试异常(穿透瞬时重试,避免放大瞬态事件风暴),信息含「精简输入/拆分任务/降低 reasoningEffort/调大 maxTokens」建议,经任务层 error 收口呈现给用户。**合成帧语义不可移除**——这是跨插件协议契约:AdaptiveMaxTokensAdvisor(§7.3.1)只认帧,不认异常类型/文案。
- `LoopRepeatGuardAdvisor` 已退役,死循环检测只保留一个装饰器:守卫逻辑在装饰 `ToolCallingManager` 的 `LoopRepeatGuardToolManager` 中(框架唯一允许「既阻止真实工具执行、又能注入合成工具结果回传模型」的扩展点是 `executeToolCalls`),装饰动作由 `AgentBuilder` 在装配工具循环时完成;比较本轮与上一轮工具调用签名(名称+参数集合,顺序无关),连续重复达 `worker.limits.max-repeated-tool-rounds`(默认 3)**不再直接中断**——而是把一条提醒文本作为该轮工具执行结果回传 AI,留一次纠正机会(本轮不真正执行工具,与 `MissingToolCallbackResolver` 同构:错误信息作为工具结果回传由 AI 自纠);若提醒后下一轮仍下发完全相同的工具调用,才中断任务(error 收口)。
- `DialogInsertAdvisor`(普通 StreamAdvisor,在主 agent 的工具循环下行阶段)把任务队列「插入到当前对话」的用户消息 drain 并追加给 AI + 发射 `user.message` 事件 + 追加 agent conversation;drain 时经核心 `UserInputMaterializer` 物化(与轮次路径同一条 @ 文件引用实现 → 正文改写 + 附件 Media),仅主 agent 装配(`DialogInsertAdvisorProvider.appliesTo` 比 agentId==mainAgentId,子 agent 对话是一次性嵌套,不接收任务队列输入,也不与主 agent 抢同一个插入队列)。
- 主 Agent 与子 Agent **共用同一执行入口与 Advisor 链**,仅 agentId 不同;链上 advisor 对主/子一视同仁装配(`AdvisorProvider.appliesTo` 缺省恒真)——子 agent 同样注入 skill 索引(其 `AdvisorContext.workspaceRoot()` 即所属任务的工作区,暴露过滤与主 agent 同一规则,§7.17),上下文压缩等同挂;仅明确按主体区分的能力才显式过滤(如 `DialogInsertAdvisorProvider.appliesTo` 限主 agent)。
- **advisor 取数统一走 `AgentContext.execution()`(ExecContext 槽位)**:taskId→`subjectId()`、模型配置→`snapshot()`(configId 经 `snapshot().configId()` 取)、事件→`emitter()`、终态判定→`terminal()`;agent 装配经 `ctx.agentFactory().create(agentId)`(预绑定工厂,静态代理)。worker 不再有 `properties` 黑盒 map 与 `get("taskEntry")` 强转(§7.20/§14.11)。**fileChanges 不进 ExecContext 也不进 TaskRuntime**(插件功能不占核心接口,§14.11 判据)——collector 由 file-change provider 维护为任务级共享实例(写侧记账在 per-call 链上行节点,§7.15.2),按轮落盘走 `RoundClosedListener` 回调,读侧走插件自己的 `task.fileChanges` RPC(§7.15.2)。

### 7.4 模型构建与容灾

worker 的模型构建经 `ChatModelFactory` 统一入口,遇到特殊 provider(如组合型模型)时经 `ChatModelEnhancer` SPI(§7.19.5)委托给匹配的插件构建。worker 核心 `ConfigStore` 原样存储 provider/model 字符串,不解析插件私有格式。容灾切换经 `EventEmitter.emit(EmitEvent.TraceData.of("model_failover", ...))` 发 `task.trace(kind=model_failover)`。

模型 HTTP 超时经 `StreamTimeoutReleaseInterceptor` 解除 okhttp `callTimeout`(流式长思考不限总时长);`FirstChunkWatchdogAdvisor`(advisor 链最内层)在首帧超时时以 `TimeoutException` 中断,交瞬时错误重试。

### 7.5 上下文管理

**双事实源分离**(红线):`Event`(不可变,磁盘 jsonl)是传输/回放/审计的事实源;`conversation: Message[]` 是 LLM 工作态,随运行销毁,再运行时由 ConversationLoader 重建。

**上下文压缩**(`ContextCompressionAdvisor`,主/子同挂、最内层):只改写**发送给模型的 instructions 视图**,内存 conversation 与磁盘事件日志始终全量。

- **offset 校准(双向)**:每轮现场重算常数修正项 = 上一轮 provider 实测 `inputTokens − 粗估算值`,抵消无 tokenizer 粗估与实测的系统性差异。**必须双向采纳**:offset > 0(粗估漏算工具定义/系统模板/分词差异)→ 抬升 precheck 防漏触发;offset < 0(粗估**高估**正文,`bytes/3` 对 ASCII/代码正文实测可达 1.5~3 倍)→ 同样压低 precheck,否则阈值退化为「粗估 × ratio」而非「实测 × ratio」,上下文远未真实占满即触发压缩(2026-10 t_ra74 实锤:估算 22.9 万、实测 8.4 万却反复压缩)。
- **冷启动校准种子(跨运行)**:加性 offset 的 `lastSent`/`lastRound` 是内存态、随运行销毁,再运行冷启动的首个请求无校准数据、退回固定 reserve——粗估高估会在窗口远未占满时误触发压缩(2026-10 t_ngj2 实锤:粗估 23~36 万、实测仅数千 token,每次再运行首轮都压一次,电池却显示个位数占用)。修复链路:压缩 advisor 每次模型请求且发送视图为**全量未压缩**时,把发送视图估算经 `AgentContext.fullViewEstTokens` 记账,事件 advisor 随本轮 `usage` 事件携带(`data.fullViewEstTokens`)落盘;task 层 usage 投影把「全量视图 实测 inputTokens + 估算 + 模型」三元组**粘性**写入 `meta.usageByAgent[agentId]`(压缩视图轮次不覆盖——其比率不表征全量历史的估算偏差),再运行经 `seedUsageRounds` 一并回灌,经 `ExecContext.compressionSeed(agentId)` 类型化槽位暴露给压缩 advisor(主/子同挂同享)。首个请求用**比率折算** `used = 估算 × clamp(实测/估算, 0.35, 2.0)` 替代加性 offset——跨运行历史持续增长时加性修正量固定、占比越来越小(修正不足仍会误触发),比率随内容规模自缩放,且把工具定义/系统模板等固定开销折算在内(旧视图更小、固定开销占比更大 → 比率偏高 → 方向保守不偏漏);本运行首个实测 usage 回来后自动回到每轮加性重算;换模型(种子模型 ≠ 当前模型)作废回退 reserve。受 `context-offset-enabled` 统一开关控制。
- **token 估算口径复用全局 `TokenEstimator`**:压缩预检走 `TokenEstimator.estimate`(单一估算口径),经 provider 延迟解析(同 model-length-guard 写法,`registerTokenEstimator` 换实现后自动跟随);估算器缺失时回退内置 UTF-8 bytes/3。**单一口径**——估算函数方向盲:同一段文本无论将来当输入还是当输出,分词完全相同,故无需分设输入与输出两套口径;输出侧系数在修掉「漏计工具调用参数」与「自反馈」后误差 ≈5~7%,对输入同样适用。**输入侧残余偏差由压缩自身的 offset 校准承担**(`overhead = 上一轮实测 inputTokens − 上一轮发送视图粗估`,加性、逐 agent、每轮重算、可正可负),不再需要独立的输入侧常数。
- **累积式裁剪**:per-run 状态化,首次压缩后持有「压缩基线 baseline + 已吸收源消息数 absorbed」,后续轮只把新增消息 delta 接到基线上做增量裁剪。
- **摘要压缩**:阶段 C 丢弃历史轮前,调用 `LlmContextSummarizer`(默认复用当前 agent 的 chatModel)生成要点摘要;失败退化为「保留该轮 user 截断简版」防永久失忆;单条超大工具结果做首尾保留的确定性截断。
- 配置:`worker.limits.context-compression-enabled`(关闭则整体关)、`context-offset-enabled`(默认 true)、`context-summary-enabled`(默认 true)、`context-summary-max-tokens`(默认 512)、`context-max-tool-result-chars`(默认 40000)、`context-trigger-ratio`(0.95)/`context-target-ratio`(0.50)。
- 压缩可见性:`task.trace(kind=context_compression)`,前端可展开查看「已自动压缩上下文(阶段, 消息 M→N, 约 X→Y token)」。

**用量**:每轮模型实测 token 用量由 `usage` 事件上报;task 层 usage 投影器(TaskEntry 订阅自身事件流)把任务下所有 agent 的「最近一轮上下文占用」聚合为 `TaskSummary.usage`(Σ inputTokens / Σ contextWindowTokens,每 agent 取最近一轮、已终局 agent 保留最后快照;前端任务列表与聊天页上下文电池同源同值,随 meta.json 持久化)并触发 `task.updated` 广播——横切层(advisor)不感知任务域操作,与 AgentLedger 台账投影同构(§5.2/§7.20)。**聚合口径的 per-agent 数据基随 meta.json 一并持久化(`usageByAgent`),再运行冷启动与 `usage` 聚合值成对回灌(`seedUsageMeta` + `seedUsageRounds`)**:只回灌聚合值时,续跑后第一条 usage 事件会把聚合重算成「仅本次运行出现过的 agent」,上一轮已终局的子 agent 快照被抹掉——电池从「Σ 任务下全部 agent」塌成「主 agent 自己」。per-agent 口径(主/子一视同仁)另经台账 agents.json 与流内 usage 事件供给前端 agent 卡片:主 agent 卡片只显示其自身用量,电池只显示任务级汇总。

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

- 工具实现(`ask_user` 工具由 ask-user 插件提供,主/子 agent 均可用):`askUser(...)` 发 `ask.create` 后 `pendingAsks.await(askId, timeout)` —— 虚拟线程挂起零开销。
- 多前端抢答:complete() 幂等,先到先得;ask.state 30s 重发 + 持久事件让重连/新上线前端必然看到挂起问题。
- 超时(默认 30 分钟):向模型返回"用户未响应",由模型自决;同时广播 `ask.state{status:"timeout"}`。
- 任务在等待期间转入 `waiting-user`;取消时 future 异常完成。
- 前端 ask 卡片生命周期(`askStore`):**终态事件按 seq 判定**——线程折叠到 `error`/`cancelled` 时只结算 seq 早于该事件的挂起 ask;懒加载翻到的**历史终态事件**(如任务曾失败后重跑再 ask,上翻分页折入旧错误轮)不得误清当前挂起 ask。已落定的 ask 不因回放帧(initial)复活;`ask.resolved`/超时/用户作答等 **worker 已确认落定**的 ask 对迟到实时帧(如 30s refresher 与作答复活的竞态尾帧)也不复活;仅本地失效清理(worker 连接集合变化等)误清的 ask,由 worker 实时 `ask.create`/`ask.state(pending)`(非回放帧)复活——worker 仍在等待即应可见可答(G4)。

#### 危险操作授权(PermissionGate)

ask 管道承载第二类阻塞请求:**危险操作授权**。`PermissionGate` 在工具执行前拦截,拦截面:

1. 文件工具目标在**工作区外**(realpath 判定,符号链接逃逸同判;read/list/stat → READ,write/move/remove → WRITE);
2. 命令串中的**危险动词**(del/erase/rmdir/Remove-Item/rm/shred 等,`worker.permissions.dangerous-patterns` 可配)——**仅当命令引用可能落在工作区外的路径时**才需授权;工作区内增删改查直接放行(授权护的是「工作区外」,不是删除动作本身;cwd 锁定 + 沙箱可写性契约兜底);
3. 命令串中引用的已存在工作区外路径(EXEC 权限)——落在该工作区**外部授权根**内者除外(用户 `@` 显式选择 = 已授权,§7.17)。

**权限责任链**(worker `tools/permission/`):节点接口产出三态 `ALLOW|DENY|SKIP`,链上顺序执行,任一 ALLOW/DENY 即短路;全链 SKIP 按拒绝兜底。

| 入口 | 链(顺序) |
|---|---|
| 文件路径 | `WorkspaceAllowCheck`(工作区内放行)→ `MissingPathCheck`(读不存在 NotFound)→ `SkillsReadAllowCheck`(skills 目录读写放行,§7.17)→ `ExternalRootAllowCheck`(工作区外部授权根放行环:realpath 落在该工作区 externalRoots 内即 ALLOW,§7.17)→ `OverBroadRootCheck`(盘根/工作区祖先拒收)→ `AuthorizeCheck`(委托授权决议) |
| 命令 | `CommandCheck`(危险动词 + 越界已存在路径逐项授权,系统目录同权;**工作区外部授权根内的引用视同已授权**,§7.17) |
| 提权 | `PrivilegeCheck`(提权动词 / seccomp setuid exec,§7.11) |

**用户显式选择=已授权(工作区外部授权根)**:经 `@` 弹窗 `+` 图标显式选择的工作区外路径由 worker 侧注册为**工作区外部授权根**(§7.17,语义 = 完全读写 READ+WRITE+EXEC)——该选择本身就是授权动作,后续工具访问经文件路径责任链的 `ExternalRootAllowCheck` 放行环直接放行,PermissionGate **不再弹 `kind=authorization` ask**。这是与人工弹窗/AI 审议并列的授权来源,不是绕过 gate:全链 SKIP 兜底、过宽根拒收、命令危险动词拦截面等语义不变。

**放行环两链同源(2026-10-14 修复)**:外部授权根放行谓词 `ExternalRootAllowCheck.grantingRoot(roots, real)` 是单点实现,除文件路径链外**命令链同样消费**(§7.17)——命令串中引用的路径落在该工作区 externalRoots 内 → 判为「不越界」,既不触发危险动词授权,也不触发 EXEC 路径授权(已存在目标按 realpath、待建目标按最深已存在祖先判定归属)。曾漏接:外部根只在文件路径链上,于是「用户 `@` 引用过的目录」文件工具读得进、而一句 `Get-ChildItem <该目录>` 照样弹授权(任务开着 AI 审议时又白烧一轮审议),与「用户显式选择 = 已授权」及沙箱侧把 externalRoots 并入 EXEC 根的口径自相矛盾。

**弹窗形态**:经 `ask.create` 发一条单题选择题(问题文案「是否授权?」),三选项 `label` = 拒绝 / 本轮运行内允许 / 本任务全程允许,`value` = 稳定 token `deny`/`run`/`task`(ask 载荷不带 `kind`:前端纯数据驱动渲染,不按语义特判)。前端把作答统一折成「题干：值」文本回传(radio 回传选项 `value`),故实际到达 worker 的答案形如「是否授权?：task」;`GrantRegistry.parseScope` **先折去「题干：」包装、只判值段**(兼容裸 token/文案:旧协议、无人值守拦截、磁盘回放),未识别/超时/取消一律按拒绝(安全缺省)。

**AskQuestion 结构化信息槽(§7.20)**:`AskQuestion(id, prompt, options, fields)` 第四字段为「标签→值」键值对——授权弹窗的机器可读字段(目录、授权类型等)与人类可读文案分离,前端在 prompt 下、options 上渲染「标签: 值」信息块;wire 序列化对空 map 省略该字段(must-ignore 双向兼容:老前端忽略未知字段,老 worker 不带该字段,前端判空跳过);仅展示增强,不改 ask 协议语义与回答解析。

**两档生效**:`run` 档纯内存,本轮输入处理完即清;`task` 档持久化 `workspaces/<workspaceId>/tasks/<taskId>/grants.json`,冷启动再运行恢复。**档位由决议节点随结论带出链外**(`AuthorizationDecision.Scope`:ALLOW+RUN / ALLOW+TASK),`GrantRegistry.record` 照档落盘——自动放行节点(AI 审议、第三方白名单)只能用默认 RUN(不得替用户做「本任务全程」的持久化承诺),只有真正问过用户的人工弹窗才可返回 TASK;`GrantRegistry` 不得把 ALLOW 一律收成 RUN(曾如此:弹窗「本任务全程允许」被静默降级为「本轮」,既不落盘也不跨轮保留)。

**授权粒度**:文件工具链按**授权单元 = 目标路径本身**(已存在前缀取 realpath;已存在文件不再提升到父目录)——`p::write::<目标文件>` 只覆盖该文件本身,「新建/覆写一个文件」的授权不会放大成该目录下其它文件的写权限(同目录兄弟文件 = 另一把 key = 另一次授权;同一路径重复访问才免弹);链节点与沙箱可达根仍按「最深已存在祖先目录」(授权判定始终在 gate 逐次执行,key 精确匹配,可达根只是路径解析边界、不构成授权)。命令链的 EXEC 根另按「已存在目标提升到父目录」(命令串只能静态看到已存在路径,按所在目录归并)。动词类按规范化动词。**弹窗文案与判定同源**(`PathSupport.scopeNote`,不得比实际宽——曾写「及其子目录」而判定是精确匹配,既误导人类授权者又让 AI 审议员按更宽的范围放行);同 key 并发只弹一张卡(inFlight future)。

**授权下发沙箱(沙箱可访问范围 = 授权范围)**:授权落定时把随附的**沙箱范围根**交给 `SandboxPathRegistry`(owner=`grants:<subjectId>`),由它跨主体聚合后**差量** `grant`/`revoke`(§7.10)。三条约束:
- **不放大(P5)**:只有**确实存在**的授权单元才下发(目录 → 目录;已存在文件 → 单文件)——「新建一个文件」需要父目录写权限,无法在请求粒度落地,**故不下发**(该路径仍可经 file 工具通道访问),绝不悄悄放宽到父目录。命令链的越界路径授权本就按「所在目录」归并(`grantRootOf`),随附该目录。
- **回收按主体生命周期**:run 档清空(新用户输入)/ 主体驱逐(`untrack`)→ 该 owner 集合收敛 → 无人期望的根被 `revoke`;**仍有主体期望的根不被回收**(聚合在账本层)。进程重启由 `grants.json`(v2,持久化 `sandboxRoots`)重放。
- **访问语义按 key**:`p::read::` → 只读,写 / EXEC → 读写。
- **回收的物理机制**(与令牌模型绑定,细节见 §7.10「授权根的回收语义」):**写**靠 cap SID 不再进新令牌(陈旧 ACE 刻意保留,因物理撤销会打断存活子进程);**读**无门控手段,靠 `ReadGrantState` 账本 + preflight 差量 `revokeAce` 物理撤销(当前写根 / deny-read 目标只遗忘不撤)。

**拒绝语义**:抛 `PermissionDeniedException` → 统一转「[工具执行失败]」文本回灌模型,agent 循环不中断。

**路径解析异常的可读化(2026-10-23)**:`toRealPath` 链上的 `AccessDeniedException` 原生 message 是**裸路径**(无原因、无类型),直接回灌会误导排查方向(本次事故中被长期误判为「文件不存在/工具损坏」,实为 `C:\` 根 ACL 损坏 + 安全软件拦截)。现于三处统一包装为可读错误(文案单点 `PathSupport.accessDeniedHint`):`PermissionGate.requirePath`(转 `PermissionDeniedException` 回灌模型)、`CommandCheck`(EXEC 路径检查的 IOException 分支特判)、`Sandbox.resolveExisting/resolveTarget/resolveLoose`(保留 cause)。

### 7.9 授权决议链

授权决议链(`PermissionGate.ensureGranted` 内、发起人工弹窗前短路)采用域中性契约:决议请求 `AuthorizationRequest(ExecContext context, agentId, grantKey, prompt)`——链节点只见 ExecContext 槽位(经 `WorkerServices.slashTokens()` 判任务级 slash token 开关、`interaction()` 弹窗(subjectId 已绑定)、`agentFactory()` 建审议 agent、`emitter()` 落审计),不接触 `TaskEntry/TaskRuntime/TaskInfo`;`GrantRegistry` 按 `subjectId()` 分区授权状态、`dataDir()` 落盘 grants.json。链节点可包括人工弹窗、AI 审议、无人值守等(由各自插件贡献)。人工弹窗是链终结节点(从不 PASS):用户选「本轮运行内允许」→ ALLOW+RUN;「本任务全程允许」→ **ALLOW+TASK**(落盘 grants.json);拒绝/超时/未识别 → DENY。

### 7.10 命令沙箱(插件化,多后端)

沙箱后端已从 worker 核心抽离为独立插件。`SandboxBackend` SPI 只表达**「对沙箱做了什么」+「路径在沙箱世界里长什么样」**,不执行命令、不涉及工具注册、不做授权策略判定;核心不感知任何后端细节。

**SandboxBackend SPI(效果 2 + 查询 2 + 标识):**
- `id()` — 后端标识(也是 `SandboxPathRegistry` 判「后端切换 → 整批重放」的键)。
- `grant(List<PathGrant>)` — 声明这些宿主路径在沙箱内**可访问**(建立权限 + 映射基准)。幂等、可重放。**best-effort 且不放大(§7.8 P5)**:无法在请求粒度落地时(典型:待建文件)跳过并记日志,绝不放大到父目录。
- `revoke(List<Path>)` — 撤销权限与映射。幂等;**只带路径,不带任务/工作区语义**(是否还有人需要该根由上层聚合判断)。
- `toSandbox(Path)` / `toHost(String)` — **纯查询、无副作用、默认恒等**;上层无条件调用一次,不需要判断「这个后端是否需要翻译」。翻译是沙箱世界的属性,且**由 grant 确立的映射基准派生**。

| 后端 | id | priority | 说明 |
|---|---|---|---|
| **WSL Ubuntu** | `wsl-ubuntu` | 10 | 独立插件;plugin.json 默认 enabled=false |
| **Windows Codex** | `codex` | Windows?8:0 | Windows 原生强隔离;默认启用,即 Windows 出厂默认后端 |
| **DIRECT(兜底沙箱)** | `direct` | — | OsSandbox 自身,无 Provider;auto 下无任何可用后端插件时兜底 |

> 各后端的具体实现细节(隔离模型、账户/令牌机制、挂载方式等)见各插件自身文档。

**效果与时机分离(§7.8)**:沙箱只表达效果,**何时授权/回收由上层决定**——`PermissionGate`/`GrantRegistry` 是唯一翻译点(§7.8),故沙箱插件**不订阅任何领域事件**,也不需要事件总线。

**命令门禁由插件主动调用(`ToolContext.commandGate()`)**:危险动词 / 工作区外路径引用的授权判定归 worker(`PermissionGate.requireCommand`),但**命令串只在插件自己的执行器里**——worker 无法在不侵入后端的前提下拦截。故经 `ToolContext.commandGate()` 下发一个窄接口(`CommandGate.authorize(command)`),后端自建执行器的插件**必须**在 spawn 前先过门禁。授权通过后 worker **同步把授权范围下发沙箱**(§7.8),插件无需自行落地权限;拒绝 → 抛异常(消息回灌模型),命令不执行。**不接门禁的后果**:该后端的命令永远不会触发授权 → 「授权 → 下发沙箱」链路根本不启动,表现为工作区外读写被 OS 直接拒绝且**从不弹窗**。现状:codex / wsl-ubuntu 自建执行器 → 已接门禁;DIRECT 同走 worker 的 `CommandExecutor`(门禁内建),无需重复包装。

**后端选择时机(时序红线)**:`SandboxProvider` 全部由插件在 `PluginLoader` 的 `@PostConstruct` 里注册,而 `PluginLoader → WorkerServices → OsSandbox` 的构造依赖链决定了 **OsSandbox 一定先于插件激活完成初始化**。因此后端**不得在 `@PostConstruct` 一次性定论**:
- `OsSandbox` 的 delegate 按 **`SandboxProviderRegistry` 代次(generation,每次注册/注销自增)惰性解析**:首次访问(`id()`/`grant()`/`revoke()`/`toSandbox()`/`toHost()`)或代次变化时重新 `select()`,解析结果(含「无可用后端」的 null)按代次缓存,不产生每次调用的重复探测;
- 选择规则不变:`select()` 先按 `isAvailable()` 过滤候选,再对胜出者 `create()`——重副作用只可能发生在胜出时刻,不因探测而提前;
- 启动期日志只陈述「配置 + 候选清单(不探测、不 create)」,后端**定论推迟**到首次真正访问 delegate 时;生效后打一条 INFO(后端切换时带原 id),解析不到时打 WARN 并附候选 id/priority;
- `grant()/revoke()/toSandbox()/toHost()` 由 OsSandbox **必须转发给 delegate**(delegate 为 null 才走 SPI 默认的 no-op/恒等)——门面自己吞掉会让路径翻译与权限下发整体失效。

**核心授权账本与下发适配(SandboxPathRegistry):** worker 核心内部 `@Component`,只做两件事——**聚合账本**(按 owner 分组登记「期望可访问的宿主路径」,跨 owner 去重、读写语义覆盖只读)+ **差量下发**(并集变化时只把差量交给 `grant`/`revoke`);**不再持有映射表**,`toSandboxPath`/`toHostPath` 直接转发沙箱纯查询。
- `register(owner, grants)` — 追加(幂等);`sync(owner, grants)` — **覆盖式对齐**(该 owner 的差集自动撤销);`unregister(owner, path)` / `unregisterOwner(owner)` — 撤销;`onWorkspaceRemoved(path)` — 从**全部** owner 移除该根,若再无 owner 期望则触发回收。
- **下发**只发差量(新增 / 权限提升 → `grant`;已无人期望 → `revoke`);**后端 id 变化**(插件晚注册 / 后端切换)即**整批重放**——同时用首次翻译查询兜底,覆盖「账本早已登记、但下发时后端尚未就绪」的启动期时序。下发失败只 WARN 并保留旧快照,下次记账 / 翻译时自然重试,**不阻断任务线程**。
- `toSandboxPath(Path)` / `toHostPath(String)` — 转发后端;后端异常时前者退化为原路径、后者返回 null(调用方原样交给 Java NIO 自然报错)。

**登记方(owner 分组,谁的路径谁登记):**
| owner | 登记方 | 内容 | 时机 |
|---|---|---|---|
| `workspaces` | `WorkspaceManager` | 全部在册工作区根 + 各工作区 `externalRoots`(READ_WRITE) | 注册表变更(`broadcastRegistry` 单一收口点:启动载入、新增/注册、外部授权根新增、移除、失效清理) |
| `skills` | `BuiltInSkillPacks` | 系统技能目录根(= `props.resolveSkillsDir()`,READ_WRITE,§7.17 读写挂入) | `@PostConstruct` 物化内置技能包时 |
| `grants:<subjectId>` | `GrantRegistry` | 该主体的**单次授权根**(§7.8:授权范围 = 沙箱可访问范围;P5 不放大) | 授权落定(run/task 档)、`beginRun` 清 run 档、`untrack` 主体终态、磁盘重载 |

**授权根的回收语义**:回收走账本差量——run 档清空(新用户输入)/ 主体驱逐 → 该 owner 的集合收敛 → 无人期望的根被 `revoke`;**跨主体共享的根在仍有主体期望时不被回收**(聚合在账本层完成,SPI 只带路径)。各后端的物理回收机制(令牌模型、ACE 撤销策略等)见各插件自身文档。

**路径翻译流程:**
1. 路径提供方登记 → `SandboxPathRegistry.register/sync()` → 账本差量经 `sandbox.grant()` 下发(建立权限 + 映射基准)。
2. 工具参数翻译:`FsToolSupport` 收到 AI 传的路径后调 `pathRegistry.toHostPath()` → 转发 `sandbox.toHost()`;无映射返回 null,原样保留让 Java NIO 自然报错。
3. 非工具路径翻译:`SkillAdvisor`(知识包路径)和 `ExternalFileTokenResolver`(@ 引用)直接调 `pathRegistry.toSandboxPath()` → 转发 `sandbox.toSandbox()`(默认恒等)。
4. 命令组装翻译:由各后端自持(wsl 用挂载表把已授权根及其子路径映射为 `/c/...`;codex 恒等,命令本就跑宿主路径)。

**`worker.sandbox.type` 配置项:** `auto`(默认)| `wsl-ubuntu` | `none`。归一化别名:旧值 `wsl-direct`/`direct` → `wsl-ubuntu`(静默兼容);`wsl-bwrap`/`wsl`/`bwrap` → 归一为 `auto` 并 WARN;`windows-mic`/`acl`/`mic`(sandbox-windows-mic 插件已移除)→ 归一为 `auto` 并 WARN;未知/空 → `auto`。各后端专属配置(distro/tarball/codex 账户等)由各插件通过 plugin.json `contributes.config` 自管。

**核心宿主访问工具与沙箱命令工具共存:**
- **核心的命令工具**(DIRECT 后端):核心自带,Windows 上 PowerShellTool 以 `powershell` 工具名注册;Linux 上 BashTool 以 `bash` 工具名注册。直接 ProcessBuilder 执行,走自己的授权链。
- **沙箱插件的命令工具**:沙箱插件不只提供 `SandboxBackend`,还提供 `ToolProvider`(命令工具)。沙箱完全自由:自己实现 CommandExecutor、自己扫描路径、自己决定授权策略。通过 `appliesTo(ToolContext)` 控制生效条件(如 `ctx.sandbox().id().equals("wsl-ubuntu")`)。
- 两者通过 `ToolProvider.appliesTo()` 各自控制生效条件,不冲突。核心 `DirectShellToolProvider` appliesTo = 生效后端 id == `direct`;各沙箱插件提供自己方言的命令工具。
- **只有某后端才做得到的隔离能力,其命令与状态一律归该插件**:如 wsl-ubuntu 的 `/禁用网络` 由 `sandbox-wsl-ubuntu` 自己注册 slash 命令与 token 解析器,worker 核心不持有该开关。

**网络策略(两级开关框架):** 默认放行——① 全局 `worker.sandbox.allow-network=false`(经 `SandboxConfig.networkDenied` 交给后端落地);② 任务级 `/禁用网络`(只有具备断网能力的后端才提供,整个能力归对应插件自带:插件自己注册 slash 命令提供者 + token 提交解析器,状态即任务级 slash token,核心不感知 kind)。worker 核心不持有任何任务级禁网状态。各后端的网络隔离实现细节见各插件自身文档。

**命令 stdin 契约:** AI 命令的 stdin 语义口径是**无输入可用、不可交互输入**(非交互/headless 工具的通用做法)。契约按**效果**表述,**实现手段各后端不同且不必相同**——各后端的 stdin 实现细节见各插件自身文档。「不可交互」的落地不依赖 stdin 形态,由**显式 env** 钉住(口径同 git 插件 `NativeGit` 的 `GIT_TERMINAL_PROMPT=0`)。

> **环境侧信道闸门(§7.17 凭据纪律)**:一切子进程环境构造点必须先过 `SecretPatterns.scrubEnv`(凭据指纹 + 凭据词族名双判,运行时关键变量走豁免表),审计只打被删变量名、绝不打值。文本内容输出掩码由 `secret-redaction` 插件承担,worker 与沙箱插件自身日志出口的凭据形态掩码走 `SecretPatterns.maskSecrets`(§7.17 ③)。

### 7.11 提权拦截

wsl-bwrap 后端的 seccomp 内核级提权拦截已随 bwrap 后端删除而移除。wsl-ubuntu 后端以 root 完整权限直连,无提权授权概念。codex / DIRECT 后端通过 Restricted Token(codex=WRITE_RESTRICTED + capability)+ PermissionGate 文本扫描拦截危险命令。

### 7.12 原生 git 执行与凭证

工作区 git 快操作(`git.*`)不自己复刻 git 语义,由 `NativeGit` 调宿主**原生 git 可执行文件 argv 直传**执行(不经 shell 字符串拼接),复用 `OsSandbox` 的超时/输出上限/env 清理;**不走命令沙箱的 wsl/mic 后端**——git 是前端按钮触发的受控操作(参数受控、路径被 `Sandbox` jail),降权/进发行版会引入路径映射与权限差异(与 JGit 曾有的 bug 同源)。

- **可执行文件定位**:启动探测一次并缓存——`worker.git.executable` 显式指定 > Windows 常见安装路径(`C:\Program Files\Git\bin\git.exe`、`C:\Program Files\Git\cmd\git.exe`、`C:\Program Files (x86)\Git\...`) > PATH 兜底;全部失败明确报错(「git 不可用,请安装 Git for Windows」),不静默回退。
- **稳定化参数**:每个命令预置 `git -C <workspace> -c color.ui=false -c core.quotepath=false --no-pager`,读命令加 `--no-optional-locks`(防 `.git/index.lock` 残留/竞争);env 设 `GIT_TERMINAL_PROMPT=0`(缺凭证 fail-fast,不卡死)、`LC_ALL=C.UTF-8`(输出编码稳定)。
- **路径沙箱**:复用 `Sandbox` realpath 前缀 jail(§7.10);用户 path 参数先经 `Sandbox` 校验再进 argv。
- **commit 路径过滤**:`git.commit` 显式 paths 先对全部路径做 jail 校验,再与当前变更集(status 7 类合集)求交集——已无变更的陈旧路径(如前端勾选后被删除的未跟踪文件,git status 中彻底不可见)对本次提交是 no-op,直接跳过,避免 `git add` 因 unmatched pathspec 整体失败;交集为空报 `BAD_PARAMS`(提示刷新),绝不静默回退成全量提交。
- **并发**:per-workspace 串行锁——写操作(`commit/pull/push/discard/init/clone/remote.add` 与自动同步)同 workspace 串行;读操作带 `--no-optional-locks` 可并发。
- **执行出口**:`OsSandbox.spawnNative(String[] argv, Path cwd, Map<String,String> env)`(宿主原生 argv 直传,非 wsl/mic);git 超时用 `worker.git.timeout-ms`(默认长于统一命令超时,clone/pull/push 大仓库可能较慢)。

**凭证**(`git.clone/pull/push` 共用四档解析链):

1. RPC 临时凭证(username/password,不落盘)→ 生成临时 askpass 脚本,凭证值经 `GIT_EA_USERNAME`/`GIT_EA_PASSWORD` env 注入,`GIT_ASKPASS`/`SSH_ASKPASS` 指向脚本(不拼 argv、不把密码写进脚本文件);
2. 本机默认凭证 → **不注入任何凭证**,git 自行走 `credential.helper` / credential manager / `ssh-agent` / `~/.ssh`(静默,原生 git 开箱即用);
3. 认证失败 → 读工作区 `.everyagent/.git-credentials.enc` 该 host 条目 → 解密后按 ① 注入重试;
4. 仍失败 → `rpc.err(AUTH_REQUIRED)` 弹凭证输入(判定 = 非零退出 **且** stderr 命中 `Authentication failed` / `could not read Username` / `could not read Password` 等关键字,避免网络错误/远端 404 误判)。

- **加密存储**:每工作区一把 32B AES-256 主密钥;算法 AES/GCM/NoPadding,随机 IV,AAD=host 绑定条目;密文 JSON `{version, entries:{host:{iv,cipher,ts}}}` 存工作区 `.everyagent/.git-credentials.enc`,明文永不落盘。主密钥**存放形态**(防"拷走工作区目录即同时拿到密文+钥匙"):
    - Windows(常规):主密钥经 DPAPI(当前用户作用域)包裹为 v2 信封 JSON(`{"v":2,"k":"dpapi-user",...}`)落盘 `<workspaceRoot>/.everyagent/.git-credential.key`(与密文同路径)——信封离开本机/本用户即不可解;
    - 非 Windows / DPAPI 不可用(回退):主密钥外置 `<home>/git-credential-keys/<sha256(workspace realpath)>.key`(工作区外),文件 600/目录 700;
    - 旧版"明文 key 与密文同级"布局在下次使用时**懒迁移**(包裹/外置前先做解密自校验,失败保留旧文件幂等重试),主密钥值不变、仅换存放形态。
- 前端 Git 面板捕获 `AUTH_REQUIRED(host)` → 凭证 Modal(账号/密码/「保存凭证到工作区(加密)」复选框)→ 先带临时凭证重试(克隆时根仍为空),成功后再 `git.credential.save` 落盘。
- 凭证仅存工作区加密文件与 worker 内存,不经 hub / 前端 localStorage;协议不提供"读取凭证"RPC(save 只进不出)。
- **多远端推送**:`git.push` 与自动同步(`syncRemote`)均推送到<b>所有</b>已配置远端(`git remote -v` 列出的每个 remote),而非仅 origin;自动同步仅从 origin 拉取、推送全远端;推送逐个远端执行,部分失败时仍尝试其余远端,最终汇总错误。推送始终显式指定当前分支名;分支无 upstream 时,首个远端自动带 `--set-upstream` 建立跟踪。
- 自动同步(git 自动提交)保持静默:只走本机凭证 + 加密凭证,不弹窗。

### 7.13 任务流传输(混合模型:定向推送 + 拉取)

**实时增量 = worker 定向推送**(DataPusher):前端 sub `u.K.worker.<id>.task.<taskId>.stream` → hub 按频道名的 worker 段向**那一台** worker 发 `subscriber.join{sessionId,taskId}`(无 worker 段时退化为投该命名空间全部在线 worker)→ DataPusherManager 校验归属后按 (sessionId,taskId) 建推送器;推送器虚拟线程把运行中任务内存 EventLog 增量(含瞬态 delta/thinking)经**单点出网投影器 `EgressProjector.projectEvent`** 投影后推到 stream 频道,`ext={target:sessionId, operate, initial}`(投影只作用于 payload 与是否出网,不碰 `ext`)。

- **归属自检(硬约束)**:worker 收到 join/leave 时,先比对频道名里的 worker 段与自身 `worker.worker-id`;**不相等一律丢弃,不建也不销推送器**。这条自检与 hub 的定向投递构成双保险:同 apiKey 两台 worker 下,非寻址那台绝不会凭空建起 DataPusher——否则它的推送器收不到前端 ack(ack 只发到寻址那台的 input 频道),credit 窗口(128)永不释放,`beginTurn` 永久阻塞,白占出站队列与虚拟线程。

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
- **出网投影 + 分页游标(4 出网口之一)**:`task.poll`/`task.roundTail`/`task.rounds` 与 stream 推送一样,客户端可见形态经单点 `EgressProjector` 投影后下发(§5.3);`task.poll` 应答新增字符串字段 **`nextSeq`**——**未过滤口径**的推进游标(批尾原始 seq),供客户端分页推进,避免「过滤后空批 = 取完」的误判导致轮详情静默丢内容;`hasMore`/`firstSeq`/`lastSeq` 仍为 raw 语义。

### 7.14 子 Agent(进程内,模型工具)

子 Agent 采用**进程内工具**方案,不采用"每个子 agent 一个独立后端程序 + hub 协调"。理由:同套底层 AI、同模型、同沙箱、零网络协调、运行时随意 spawn;跨机分工留给 v2 舰队/编排。

**工具面**(注册给模型的工具,与前端 RPC 无关):

| 工具 | 语义 |
|---|---|
| `run_agent(input, title, agentId?)` | 异步派发子 agent;无 agentId 新建(title 必填,agentId 动态生成);传 agentId 即续跑(复用其上下文,上一轮最终回答已在会话内——`SubAgentManager.appendFinalAnswerTurn` 于收口时回写 assistant 轮,维持 §7.9 同一条会话交替不变量,中断记占位);**续跑跨轮有效**(见下方「跨轮可见性与续跑」);立即返回 agentId,需用 wait_agents 等待结果 |
| `list_agents()` | 列出本主体下全部子 agent(agentId/title/createdAt/status/latestActivity,不回灌完整历史;**含此前轮次已收口的子 agent,跨轮可见**,见下方「跨轮可见性与续跑」) |
| `wait_agents(agentId?, timeoutMs?)` | 等待子 agent 完成/超时;对已收口的历史 agentId 直接返回终态摘要(waitStatus=completed) |
| `stop_agent(agentId)` | 停止指定子 agent;对已收口的历史 agentId 应答「已结束(终态),无需停止」 |

> **归属与前端**:`task.agents` **RPC 已收回 task 域**(常量 `RpcMethods.TASK_AGENTS`、由 `TaskManager` 注册实现,语义 = 任务下**全部** agent、无 creator 过滤,§5.5/§7.20.1)——subagent 插件**不再提供该 RPC,也不再提供 web 前端功能**(原 `ui.composer_above_panel` 的子 agent 面板退役);前端 agent 胶囊列表由 web 核心 `TaskChat` + `AgentListPanel` 直接渲染(§8.1)。上表四个 `run_agent`/`list_agents`/`wait_agents`/`stop_agent` **工具**仍属 subagent 插件(执行域能力,不迁移)。

**运行语义**:

- 子 agent = 同一 ChatModel + 收窄工具集 + 独立 system prompt 的嵌套循环;虚拟线程承载。
- **上下文隔离**:子 agent 只收到 input 文本与自身 system prompt,不继承父 conversation 任何历史;信息交换唯一通道 = 下行 input、上行 `agent.done` 结果。
- **递归禁用从根源做**:子的工具集剔除全部 agent 工具 → 结构上不可能派生孙 agent(深度上限 1)。
- **父停止级联**:父任务取消 → 全部子 agent 停止。
- **收口前自动等待**:父任务结束前自动 wait 全部子 agent 聚合回灌;安全超时(默认 5 min)——主 agent 未调 wait_agents 即收口时,运行中的子 agent 被等待到自然完成,不因收口立即强停(超过安全上限才级联停)。
- 并发守卫:运行中的 agentId 再次 run_agent 报错;`wait_agents` 报 completed 前必等运行体 future 落定(实体终态先于运行体 finally 置位,早退会让紧随的续跑撞「已在运行」守卫)。

**跨轮可见性与续跑(2026-12 修复,磁盘是唯一事实源)**:子 agent 是**主体级资产,不随单轮收口消失**——历史全部落盘,进程内存零依赖。`ctx.agents()` 是 per-run 注册表(主体收口即销毁,下一轮冷启动为空表),三个机制补齐跨轮:

1. **指令落盘**:`run_agent` 的每条指令(新建/复用/重建三路径)发 `user.message` 事件(带 agentId,**与主 agent 任务输入同一名事件——agent 层一视同仁**)→ `<agentId>.jsonl` 自包含(user → assistant 轮次完整,含中断轮的合成占位),是续跑重建的依据;
2. **读侧合并**:工具(list/wait/stop)按「本轮 live 实体(状态/活动最新;createdAt/title 沿用历史基线)→ 磁盘台账 `agents.json`(收口 persistFinal + 30s 快照)」两层合并;非在场实体的 running/waiting-user 一律归一 stopped(与 `AgentLedger` 冷启动归一同规则);
3. **中性重建**:`run_agent` 传历史 agentId 而实体不在场时,经 plugin-api `ExecContext.conversationArchive()` **会话档案口**(角色中性:主/子/审议/未来新增派生 agent 一视同仁,只认 agentId)从 `<agentId>.jsonl` 重建会话种子,再以 `AgentBuilder.conversation()` 注入重建实体续跑——[system, ...prior, user(新)] 形态,档案尾孤立 user 时补占位 assistant(§7.9 交替不变量);新 emitter/新 ChatClient 绑定**本轮** ExecContext(直接复用旧实体会把事件发进已销毁的上一轮日志)。`ConversationLoader` 同一套谓词服务主 agent 冷启动与派生 agent 续跑(user.message → UserMessage 等);worker 重启后历史照常恢复。磁盘亦无档案(凭空 id/jsonl 损坏)→ 同 id 全新会话。

**展示分工**(agent 层 vs 展示层):user.message 的「主线程用户气泡/轮次开合」是**主 agent 专属语义**,判据一律是 **task 层传入的固定 mainAgentId**;折叠窗口语义归 rounds.jsonl 唯一权威(§7.15.1),前端线程项无折叠标记(foldRole 体系已删)——轮次索引(RoundIndexStore)只认 agentId == mainAgentId 的 user.message 开轮,catchUpRuntime 只追回主 agent 事件;wire 出网(EgressProjector)所有事件必带 payload.agentId(主 = mainAgentId),前端(`eventRegistry`/`eventFolder`)经 `isMain(agentKey) === mainAgentId` 区分:主 agent 渲染 user 气泡,派生 agent 以 trace 形态展示(内容零丢失);旧事件缺 agentId 时前端归一到 mainAgentId。协议层不为此发明新事件名,也不用「空串=主」魔法。

**事件与持久化**:子 agent 不建独立 Task,事件与主 agent 同名、以 agentId 字段嵌套在父任务流(spawn 生命周期为 agent.started/agent.done);**每个子 agent 一个独立会话文件 `<subAgentId>.jsonl`**(含 user.message 指令轮,自包含可重建,见上方「跨轮可见性与续跑」);断线续播、ask(带 agentId)全部复用既有机制。子 agent 台账**独立落盘任务目录 `agents.json`**(形状 `{"agents":[...]}`,临时文件 + 原子 move 写入、空台账删文件)——从 meta.json 拆出,TaskSummary 不再携带 agents 数组,`tasks.list` 读 meta 的任务列表数据因此减负;台账项 = AgentLedger 事件投影(形状与 `AgentEntity.toSummary()` 对齐):agentId/title/createdAt/status/latestActivity/**usage(累计)**/lastText/**context(最近一轮上下文快照 `{inputTokens, contextWindowTokens, model}`,有数据才写)**/**metadata(含 creator,随 agent.started 事件投影)**,前端经 `task.agents` 拉取,subagent 插件工具读侧也作跨轮兜底(只读)。台账投影与 agents.json 读写由 worker core `AgentLedger` 承担(订阅 EventLog 的 agent.started/done/status/usage/message/error 事件,§7.20.1;原插件 `SubAgentLedger` 退役);agent.started/agent.done/agent.status/agent 级 error 由 advisor 链驱动、`AgentEntity` 一处发射(per-run 生命周期,主/子一视同仁,§7.20.1)——SubAgentManager 不再手动 put/emit agent.* 事件(复用续跑路径也不补发 started),只发 user.message(指令落盘,带 agentId,本插件职责)。usage 事件语义:WorkerToolEventAdvisor 每轮模型调用 usage 发射前先 `addUsage` 累计进 AgentEntity——usage 事件 total 载荷 = 含本轮累计;子 agent 每轮 usage 后由 AgentLedger 刷新内存台账并触发 agents.json 落盘(30s 定时 / 终态 persistFinal 两路径)。

**域中性(§7.20/§14.11)**:subagent 是**执行域能力插件**(spawn/wait/stop 子 agent 是执行域能力,不是任务域能力——没有 task 只有 workflow 的执行主体同样可用)。`SubAgentManager` 构造零服务依赖,方法收 `ExecContext`(run/list/waitFor/stop/awaitAllBeforeFinish/stopAll),工具入口绑 `ToolContext`(extends ExecContext,§7.20.5) 不再反查任务;per-subject 状态自持 `TaskSubState`(监视器内部化,对任务对象的锁依赖消失)。台账 agents.json 住主体 dataDir 下,IO 已由 worker core `AgentLedger` 收编(事件投影 + 原子读写 + 冷启动恢复,原插件 `SubAgentLedger` 退役,§7.20.1);生命周期节点(track/untrack/persist 由 AgentLedger 内置节点承担,§7.20.1)壳留 task 面、逻辑取 ExecContext 槽位,工作流落地时同一核心以同构生命周期壳挂载、零新逻辑。

### 7.14.1 任务生命周期洋葱模型（Phase 1）

任务开始/结束收口采用 **洋葱模型**（Servlet Filter 风格参与式链）：节点拿到运行上下文，调用 `result = next(context)` 得到后面全部节点+内核的执行结果。`next()` 之前 = 下行（开始）阶段，之后 = 上行（结束）阶段。

- **契约**：`TaskLifecycleNode`（plugin-api）、`TaskLifecycleContext`、`TaskOutcome`、`TaskKernel`——放 plugin-api `dev.everyagent.plugin.api.task` 包，插件可贡献节点；`TaskLifecycleContext extends ExecContext`（任务域成员保留 + default 桥接，§7.20.5）。
- **执行器**：`TaskLifecycleExecutor`（worker `task.lifecycle` 包）——按 order 升序稳定排序折叠为嵌套链，链尾接内核；`InterruptedException`→CANCELLED 兜底。
- **临界段**：order ∈ [420, 850] 的连续 `UpstreamNode` 段共享一次 `synchronized(taskLock)`，对外表现为 order=850 的单一链位置。段内上行执行序 = order 降序（与现状 finish 持锁段逐项一致）。
- **注册表**：`TaskLifecycleRegistry`（`plugin/registry/` 第 8 个注册表）——CopyOnWriteArrayList + float 稳定排序。850..420 区间拒绝插件节点插入。（注册表不感知插件禁用：禁用的插件根本不会被 `activate`，也就不会往这里注册节点，见 §8.5）
- **节点全集（31 个实测 = worker 内置 26 + 插件贡献 5）**：order 全表（升序 = 外→内）详见插件指南 `docs/plugin-guide/backend/task-and-rpc.md` §2.3——下行含 RPC 线程段（10~80）与 `persistence.track`(100) → `task.wires`(200) → `main.agent`(390)；早期清单中的 `status.start`(300)/`status.finalize`(850) 等旧编号已随演进移除，以插件指南全表为准。
  - **轮次循环段（每轮重入，临界段内侧）**：`queue.loop`(870，task-input-queue 插件) 包裹 `[ file.reference.process(875) → edit.resend(877，task-edit-resend 插件) → consume.input(880) → 内核 runner.run(main) 一次 ]`——每轮 poll 队列项后覆盖 ctx.input 再 proceed;队列 poll 空时先回收「插入对话」队列里未被 advisor drain 的项(回收成功就继续续跑),`cascade.stop`(900)/`spawned.await`(950) 亦在循环内侧(逐轮失败级联停/子 agent 等待)。**轮次循环必须在临界段 [420,850] 内侧**：若在 420 之外包裹(如 order=395),每轮上行段会把 status 终态/registry.remove/persistence.untrack/concurrency.release 等一次性收口节点逐轮执行——任务中途被移出注册表(实时推送断流、cancel/task.poll 失效)、writer 提前关闭(后续轮次事件不落盘)、并发计数重复扣减
  - 上行（概览，倒序收口）：`spawned.await`(950) → `cascade.stop`(900) → `ledger.persist`(860) → **[临界段]** `status`(840) → `concurrency.release`(800) → `log.flush`(750) → `status.persist`(650) → `disk.index`(550) → `persistence.untrack`(500) → `gate.evict`(450) → `registry.remove`(420) **[/临界段]** → `workspace.activity`(350) →（RPC 线程段收尾，逐项 order 见插件指南全表）
- **行为零变化**：事件发射顺序、seq 语义、落盘内容与重构前逐项一致（重构时以逐项基线表核对映射）。
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

worker 的两条运行期责任链迁移为与任务洋葱同一的 filter 形态（`result = next(ctx)`）：

| 链 | 旧 | 新 | 收益 |
|---|---|---|---|
| 工具执行拦截链 | `beforeToolExecution(...)` 返回 null/非null（仅下行） | `invoke(ctx, next)`：下行=检查/短路，上行=执行后审计 | 获得上行钩子 |
| 授权决议链 | `applies() + decide()` 两步 | `invoke(req, next)`：req=`AuthorizationRequest(ExecContext,agentId,grantKey,prompt)`(域中性,§14.11)；不处理=proceed，决议=短路 | 获得决议后包裹 |
| PermissionGate 内部三链 | `check(ctx)` 返回 ALLOW/DENY/SKIP | `invoke(ctx, next)`：SKIP=proceed，短路=ALLOW/DENY | 与洋葱同构 |

- **链组装器**：`ToolExecutionChainExecutor` / `AuthorizationChainExecutor` / `PermissionChainImpl`，均按 order 升序折叠为嵌套链，与 `TaskLifecycleExecutor` 同构。
- **工具执行链 per-call 化(2026 定案,替代原批级 `InterceptingToolCallingManager`——已退役删除)**:worker 持有 `PerCallToolCallingManager implements ToolCallingManager`(共享无状态单例 bean),**照抄** Spring AI 2.0.1 `DefaultToolCallingManager` 的 `executeToolCalls`/`executeToolCall`/`buildToolContext`/`buildConversationHistoryAfterToolExecution` 源码(自建 `InternalToolExecutionResult` 与 `ToolCallLimits` 等价物——后者框架侧是 package-private,包外不可引用),在工具调用 for 循环内插**两处切口**:下行(回调解析后、observation 前——ToolCall 全字段+ToolCallback 在手)与上行(observe 返回后、ToolResponse 构造前——结果串+配对 id 在手)。每次工具调用执行一遍插件链,链尾 = 框架原生 observation+call+异常处理器;链节点的下行段(`next.proceed` 之前)/上行段(`next.proceed` 返回后)恰好各落一个切口位置。
  - **切口语义 = 方案 B(严格,写入 `ToolExecutionInterceptor` javadoc)**:下行可改写参数(`ctx.arguments(...)`,改写对真实执行与 observation 生效)、可合成当前调用结果(`ctx.synthesize(串)` 哨兵 → 链尾跳过 observation+真实调用,合成串**沿框架 ToolResponse 构造路径**流经全部上行改写节点——secret-redaction 对合成答案照常生效,unattended 的 ask_user 自动作答即此形态;混合轮天然正确——逐调用判定,同批其他调用照常真执行)、**禁止 mid-batch break**(字节码实证框架循环无批内中断,returnDirect 是批级出口标志;自造「N 调用 M 结果」的模型配对义务不被采纳);上行可改写结果串(脱敏/hint)、可发事件,不可撤销已执行调用。拦截器也可直接 `return` 串不调 `next`(经典硬短路,绕过内层节点——仅限极端场景,合成一律走 `synthesize`)。
  - **per-run 数据通道(单例无状态的代价)**:per-call 链 ctx(`ToolExecutionContext`)的 ExecContext 槽位取自 **prompt options 的框架 `ToolContext` map**(`AgentRunner` 组装 prompt 时经 `options.toolContext(RUN_CONTEXT_KEY, RunContext)` 下行 agentId + agent 级 emitter + `ExecContext` + `ToolOutcomeLog` **四件套**——agentId 供链节点做 MAIN/SUB_AGENT 归属判定,file-change 一阶段记账消费,§7.15.2)——框架原生通道,`buildToolContext` 原样透传给工具回调;map 无该键(单测/裸用)时切口退化为纯框架路径,行为与 `DefaultToolCallingManager` 逐位一致。ThreadLocal 依旧禁用(reactive 流的工具执行可能切换到 boundedElastic 线程)。
  - **internal 依赖策略**:照抄块唯一抄不掉的硬依赖是 internal 包的 `ToolCallReactiveContextHolder`(observation parent 装配)——**不用反射**,照抄 import 收敛到唯一包装 `ParentObservations.reactiveContextOrNull()`(internal 类变动 = 编译红灯显性可见);当前不预置 parent 装配(全仓零 `ObservationRegistry` 消费/零 tracing 配置,NOOP registry 空转无意义),接 trace 时按需选型(tracing UI span 树 → 届时评估 internal 包装或官方 API;只要日志关联 → ctx 透传 `traceparent`,hub `ext.traceparent` 已有 W3C 约定 §5.6)。
  - **红线豁免记录(用户确认)**:照抄 DefaultToolCallingManager 循环体+插切口 = 自持循环的**轻量形态**(类顶部注释注明来源版本;Spring AI 升级时 diff 框架源码对齐,区别于自研行为重设计),豁免「不得手搓工具调用循环」红线;豁免范围仅限该照抄块。
  - **装配**:`WorkerBeanConfiguration.toolCallingManager()` 直接 `new PerCallToolCallingManager(...)`(原 builder 四槽位——异常处理器 `CANCELLING_PROCESSOR`/回调解析器 `MissingToolCallbackResolver`/limits(200,500,THROW)/resolutionFallback=true——显式传构造,行为逐位保真;内置 `ToolEventInterceptor` 同处注册);`AgentBuilder` 装配简化为 `LoopRepeatGuardToolManager(PerCall)` 两层装饰。
  - **现役链序**(order 升序 = 下行执行序,上行逆序):`rg-hint`(0,最外层,上行最后——看到全部改写后的最终文本)→ `unattended`(100,ask_user 合成)→ `file-change`(400,上行一阶段记账:update_file/create_file 成功调用写任务级共享 collector,§7.15.2;位于发射点外侧,上行段执行时 callId 账已写)→ `tool-events`(500,发射点:`tool.started` 下行瞬态 + `tool.result` 上行落盘/callId 写账;在 secret-redaction 外层故结果串已是掩码后文本,leak-guard `ctx.terminal()` 不发)→ `secret-redaction`(900,最内层结果改写,上行第一个拿到原始串)→ 链尾(框架执行/合成短路)。批级发射点全部退役:`WorkerToolEventAdvisor` 的 `doGetNextInstructionsForToolCallStream` 发射段删除(保留 super 转发),`tool.batch.started` 不做(message 事件已带 toolCalls 真实 id,冗余)。
- **`LoopRepeatGuardToolManager` 保持装饰器(零改动)**：核心守卫不是插件扩展点，且需在 `executeToolCalls` 处合成工具结果回传模型，与插件链职责不同;死循环检测语义单位 = 轮不是调用,批级挂载对其正确粒度(有意不做 per-call)。已知边界:守卫 WARN 轮的合成提醒不经 per-call 链 → 无 `tool.started`/`tool.result` 事件(提醒文本仍随 conversationHistory 回灌模型,前端调用行停留无结果态)。
- **行为零变化**：短路/放行/兜底语义逐项等价（§6.3 迁移映射表）。

### 7.14.4 任务队列插件（Phase 5）

任务队列插件将「并发上限即拒 ERR_BUSY」语义替换为「排队等待」语义。插件实现 `EveryAgentPlugin.activate(WorkerPluginContext)`，在 activate 里经 `ctx.register*` 注册（全仓 30 个内置插件源码零 `@Component`，插件由 `URLClassLoader` 加载、非 Spring 托管；git 插件同类先例是 `GitPlugin`）。

- **`QueueAdmissionNode`**（order=40，形态三 try/finally 成对节点）：落在洋葱 RPC 线程段 `queue.dispatch`(15) 之后不远处，介于 `taskid.generate`(30) 与 `taskentry.create`(50) 之间（31 节点全表见插件指南 `docs/plugin-guide/backend/task-and-rpc.md` §2.3）。下行段 `acquire(taskId)` 获取运行许可（`Semaphore` fair 模式，permits=maxConcurrentTasks），并发满时虚拟线程 park 阻塞（零线程开销）；finally 段 `release(taskId)` 释放许可并唤醒下一个等待者。下行抛异常时 release 不执行（未进入不收口语义）。
- **`TaskAdmissionPolicy` SPI**（plugin-api）：RPC 边缘预检扩展点。队列插件注册 `TaskQueueAdmissionPolicy`（always-admit）后，`TaskManager.rpcTaskRun` 不再硬拒绝 ERR_BUSY，而是放任务进入洋葱由 `QueueAdmissionNode` 排队处理。无注册策略时保持原有行为。
- **`task.queued` 事件**（tasks 频道）：任务因并发满而排队等待时广播队列状态（payload: `{queueLength, queue:[taskId...]}`），前端据此渲染排队状态。
- **`task.queueList` RPC**：返回当前队列快照 `{availablePermits, queueLength, queue:[...]}`。
- **`TaskQueue`**（插件内普通类，`activate` 里构造）：`Semaphore`(permits=maxConcurrentTasks, fair) + `ConcurrentLinkedQueue<String>` 跟踪排队任务；acquire/release 管理 Semaphore 许可并广播队列状态变化。
- **worker pom 挂载**：`every-agent-worker/pom.xml` 依赖 `task-queue` 模块（worker 自行 repackage 可执行 jar,mainClass=`WorkerApplication`）。

### 7.15 持久化与磁盘布局

```
~/.everyagent/                       # <home>(EVERYAGENT_HOME 可覆盖;docker 挂卷)
├─ defaultworkspace/                 # 默认工作区根(原 workspace/ 改名,自动注册 id=defaultworkspace)
│   └─ .everyagent/                  # 工作区级 git 凭证加密存储(密文 .git-credentials.enc + 主密钥信封 .git-credential.key:Windows 为 DPAPI 用户态信封,其余平台外置 <home>/git-credential-keys/,§7.12)
├─ workspaces/
│   ├─ workspaces.json               # 唯一工作区注册表 {id, root, addedTs, lastActivityTs?, externalRoots?}(默认工作区也在册)
│   ├─ defaultworkspace/tasks/<taskId>/      # 默认工作区任务目录;永久保留
│   │   ├─ meta.json                 # TaskSummary(含 workspaceId、最近一轮上下文用量、任务级开关)+ mainAgentId
│   │   ├─ agents.json               # 子 agent 台账 {agents:[...]}(从 meta.json 拆出独立落盘,减轻任务列表数据;含累计 usage 与最近一轮 context 快照 + metadata.creator;临时文件 + 原子 move 写入,空台账删文件;worker core AgentLedger 事件投影 + 自持 IO,§7.20.1)
│   │   ├─ <subAgentId>.jsonl        # 子 agent 独立会话(含 user.message 指令轮,自包含;续跑经 ExecContext.conversationArchive() 按 agentId 中性重建,§7.14)
│   │   ├─ grants.json               # task 档授权 {taskGrants, extraRoots}(§7.8,首次授权时原子写)
│   │   ├─ <mainAgentId>.jsonl       # 主 agent 会话 + 任务级事件
│   │   ├─ rounds.jsonl              # 轮次索引(§7.15.1)
│   │   └─ file-changes/<roundId>.json   # 单轮文件变更记录(经 task.fileChanges 拉取)
│   └─ w_xxxxx/tasks/<taskId>/       # 其它工作区任务目录(结构同默认工作区)
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

主 agent 侧生成轮次索引(每行一轮:用户输入 → 主 agent 最终回复):

- 行格式:`{index, roundId, startSeq, endSeq, user, finalReply, durationMs, startedAt, agentRanges, userMessage}`(**轮行不含任何插件业务字段**,文件变更等按轮旁路数据由插件自持文件 + 插件 RPC 提供,§7.15.2);seq 一律字符串;`endSeq=""` = 未闭合轮;`roundId` = 稳定主键(`ShortIds.next("round")` 开轮落盘即生成,闭合原位改写**沿用不重生成**;旧数据行缺失不写);`startedAt` = 开轮落盘时刻(epoch 毫秒,耗时从磁盘算的起点;`durationMs` = 闭合时当前时间 − startedAt);`userMessage` = 完整 user.message payload(渲染投影素材),**形状必须与 user.message 事件 payload 一致**(`{content, data:{rawContent?}}`)——开轮落盘(openRoundAtStart)与 scan 重建两条路径同形,前端画板按 `userMessage.data.rawContent` 合成 user 气泡回放 @文件胶囊(rounds 通道只做渲染投影,不进前端数据层,§8.3)。`task.rounds` 应答的 rounds 项与 **open 轮对象**(运行中实时扫描、不落盘)均携带 `roundId`:open 轮取自未闭合行,开轮即有、跨闭合稳定;未闭合行缺失/旧行无 roundId 时 open 不写该字段(must-ignore,§5.6)。
- 增量写:消费用户输入即 `openRoundAtStart` 落一行 `endSeq=""`(并把 `startedAt = System.currentTimeMillis()` 随行落盘);`RoundIndexAdvisor` 在主 agent 最终回复后 `rewriteRound` 原位改写闭合(临时文件 + 原子 move,与追加同锁串行)。**`durationMs` 随闭合行同一次落盘内联写入**——耗时不再内存中计算:闭合轮时 `applyRounds` 取当前时间减去磁盘行的 `startedAt`(开轮落盘时刻)得到;任务出错停止后继续(续跑改判闭合)也以最初开轮时刻计耗时,跨运行延续不失真。`round.closed` 通知在闭合行落盘**之后**推送——前端收到通知拉 `task.rounds` 时耗时必已就位。历史上「先闭合推送、后单独回填耗时」的两段写存在竞态:前端在回填完成前拉快照会拿到 `durationMs=0` 且无后续刷新触发,表现为本轮耗时不显示(重连才恢复)。旧行/scan 行无 `startedAt`(0)时闭合不计算耗时(保持 0,优雅降级)。
- 旧任务首次 `task.rounds` 惰性全量生成落盘;任务终态 do `finalizeRounds` 补写未闭合轮。中断/失败/取消的未闭合轮自然保留。
- 消息编辑重发(`truncateAfterSeq`):事件日志按 `seq >= editSeq` 截断重写;rounds.jsonl 同步截断为 `startSeq < editSeq` 的行(**编辑点之前的轮次保留**,新轮 index 顺延)。事件文件重写只针对 agent 事件日志(`<agentId>.jsonl`)——任务目录下的 `rounds.jsonl`/`queue.jsonl` 不属事件空间(行无 seq 字段),误入事件重写会被「seq<=0 丢弃」整文件清空,表现为编辑后历史轮次全丢、新轮 index 归 1。
- 前端"双击打开任务" = 拉 meta → 一次 `task.rounds` 渲染折叠轮次 → 展开按 seq 区间懒加载过程内容。

#### 7.15.2 文件变更(file changes)

每一轮 agent 执行中对工作区的文件写操作由插件自管(task 核心零 fileChanges 概念,§14.11)。worker 文件工具(`FileTools`)在写盘成功后回调 `FileWriteListener`(plugin-api SPI;插件经 `WorkerServices.addFileWriteListener` 注册),回调点在写锁内、落盘之后。落盘按 roundId 写 `file-changes/<roundId>.json`,读侧走插件自注册的 `task.fileChanges` RPC。直推走 `plugin.*` 前缀瞬态事件,宿主 `taskStream` 一律透传 `plugin-task-event` 通用领域事件,不折入线程。

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

**斜杠命令与任务级开关**:斜杠命令由 worker 动态注册(`slash.list`/`slash.select`/`slash.cancel`);任选中可返回多个结果(如 `/无人值守` 一次返回「无人值守」+「AI 审议」两个胶囊,由被联动条目声明 `chainIds`、worker 斜杠层按 id 取该条目自身 `insertText` 生成);任务级 token(模型池、AI 审议、无人值守、禁用网络等)的**唯一真相源**是任务目录下的 `slash-tokens.json`(斜杠层自管存储,恒以磁盘为准,运行中/终态/重启后一致),不再写任务 `metadata`;选中胶囊 = `slash.taskTokens.apply` 写入 token(开),✕ 取消 = `slash.cancel` 移除 token(关),插件回调不再写任何任务状态。所有判定方一律经只读查询口 `dev.everyagent.plugin.api.slash.SlashTokenService`(`tokensOf(taskId)`/`hasKind(taskId, kind)`,经 `WorkerServices.slashTokens()` 获取)查询。

**composer token(opaque token)双轨与 worker 解析**:输入框胶囊(斜杠命令、`@` 文件引用等)由前端构造为 inline opaque token(`[[[[agent-token::::<kind>||||label/summary/payload…]]]]`,4 连符号定界零转义);提交走**双轨**——`text` 为人类可读明文,原始 token 串随 `Input.rawContent` 上行(重开/回放按 rawContent 还原胶囊)。worker 侧 `SlashTokenResolveAdvisor` 在 user 消息进入模型前扫描正文、交 `SlashTokenHandler` 按 kind 分发解析为提交文本(未知 kind/解析失败保留原串);已注册 kind:技能命令 → 技能名、`git.auto_sync` → 空串、`system.workspace_file` → 工作区相对路径明文。

**`@` 弹窗外部文件引用(kind=`system.external_file`)**:payload `{absolutePath, fileName, kind:"file"|"directory"}`;前端不解析该 token,提交时原串上行(复用 slash token 通路),worker 统一解析:① realpath 不存在 → 替换为失效提示文本;② realpath 落在工作区内 → 退化为「工作区相对路径」明文(与 `system.workspace_file` 提交语义一致);③ 工作区外 → 注册为该工作区外部授权根(§7.17)并替换为「原生绝对路径 + 沙箱内路径」文本。前端入口:`@` 弹窗标题行 `+` 图标打开外部文件选择框(`fs.browse` `includeFiles=true` 数据源;默认目录=当前工作区根,面包屑+返回父目录,最顶层为盘符根列表;目录行可进入+可选,文件行可选;响应缺 `supportsFiles` 时降级仅目录);点击 `+` 先删除输入框中的 `@` 触发片段,选中后与 @ 搜索选中一致走 insertToken 插入胶囊。

**文件引用物化单点实现（`UserInputMaterializer`，核心服务，插件经 `WorkerServices.userInputMaterializer()` 取用）**：把一轮「正文 + rawContent」物化成投喂模型就绪的 `UserMessage` —— 解析 rawContent 里的文件引用 token → 按扩展名分发插件注册的 `FileReferenceHandler` → 用 `replacementText`（如「（附图：xxx）」）改写正文 → 附件合并进任务 `metadata.attachments`（随 meta.json 持久化，跨轮可读）→ 附件转 Spring AI `Media`。**凡「把一条用户输入投给模型」的入口都必须经本服务**：轮次路径由薄壳节点 `file.reference.process`(875) 调用（只用改写后的正文，消息由 `consume.input` 重建 + `FileAttachmentAdvisor`(HP+160) 从 metadata 注入媒体），「插入到当前对话」路径由 `DialogInsertAdvisor` 直接调用（模型不看该轮 metadata 注入时机早于插入，故由物化结果自带 media）。两条路径曾经各写一份 → 插入路径的 @ 图片退化成裸路径文本（图片不进模型，AI 只看到路径、转而去 `read_file` 图片失败），现已收敛为同一实现。

**队列输入与「插入到当前对话」**:任务运行中输入入队(pendingInputs 经 `task.updated` 广播外显、`task.queueSnapshot` RPC 拉取——队列面板数据源由插件自持,`ComposerPanelCtx` 等公共类型不携带插件专有字段;可 `task.queueRemove`/`task.queueMove` 管理,消费一项即广播刷新);「插入到当前对话」走 `task.run{taskId, input, metadata:{insert:true, index}}`(队列插件在 `queue.dispatch` 消费该 metadata;**先按 index 从输入队列摘掉该项**——摘不到再按正文匹配,插入即消费,不摘则面板原地不动且本轮跑完会被当新一轮输入重复提交,再把摘到的队列项——带 `rawContent`,非前端回传的裸文本——交给本轮主 agent 的插入队列),`DialogInsertAdvisor` 随下一轮工具结果以 role=user 提交给 AI(**先经核心 `UserInputMaterializer` 物化**:@ 文件引用 → 「（附图:xxx）」正文 + 附件 Media 随本条消息进本轮模型调用,附件并入 metadata 供后续轮次持续注入;直接投 `input()` 会让 AI 只看到裸路径)+ 发 `user.message`(payload 形状与 `consumeInput` 同构 `{content, data:{rawContent}}`,并进 agent conversation——会话内存只进正文,媒体由 metadata 经 `FileAttachmentAdvisor` 注入,与轮次路径同形态)——advisor **按 taskId 现取插入队列**,不在构造期缓存引用(队列是点击时懒建的,缓存会整个 run 持 null 静默失效);本 run 再无工具循环下行时机(AI 已收尾/取消/失败)而未 drain 的插入项由 `queue.loop` 回收进输入队列、随 `queue.jsonl` 落盘为悬空队列,下次运行作普通轮次消费——**用户输入不丢**。

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
| `application-worker.yaml` | 【可选】worker 用户配置覆盖(模型 `worker.models`、hub 连接、沙箱等;存在才生效) |
| `application-hub.yaml` | 【可选】hub 用户配置覆盖 |
| `application-dev.yaml` | 【可选】开发覆盖(IDEA 经 additional-location 显式指定) |
| `skills/` | skill 目录(**唯一真相源**:一目录一 skill,目录下必有带 frontmatter 的 `skill.md`);内置包在 worker 启动时经 plugin-api `SkillMaterializer.materializeAll` 从 classpath 资源根 `builtin-skill/` 物化,插件包由 plugin.json 声明 `skills` 字段、`PluginLoader.loadPlugin()` 加载时统一从插件根目录 `<pluginRoot>/skill/` 物化(均落盘为 `<id>/skill.md`,幂等、原子写、清理旧扁平 `<id>.md` 残留;禁用插件不贡献 skill);用户自制 skill 直接放置 `<目录名>/skill.md`(可附带 `scripts/` 等由正文引用、AI 用对应解释器执行——bash/python/node 等)。AI 经 read_file 读写访问;同时读写挂入 wsl 沙箱供 bash 工具操作,§7.10。详见下文「skill 目录结构与唯一真相源」 |
| `skill-exposure.json` | skill 按工作区暴露配置(黑白名单并存,黑名单优先) `{"<skillId>": {"blacklist": ["<workspaceRoot>", ...], "whitelist": ["<workspaceRoot>", ...]}}`(身份键 = 工作区根路径,normalize 后匹配;缺文件/解析失败回退空表 = 全部暴露;`AtomicFiles` 原子写,读写 `synchronized`)。**黑名单**:列出的工作区不暴露;**白名单**:非空时仅列出的工作区暴露;**黑名单优先**:同时命中黑白名单按黑名单处理;两个清单都空 = 恢复默认(删除键)。旧版 `skill-exclusions.json`(数组值)自动迁移为 blacklist;旧版单模式 `{"mode":"blacklist"/"whitelist","workspaces":[...]}` 自动迁移为对应清单。默认全部 skill 在全部工作区自动暴露,此文件只记暴露控制;`/` 菜单不受影响;详见下文「skill 目录结构与唯一真相源」 |
| `defaultworkspace/` | 默认工作区根(原 `workspace/` 改名,自动注册 id=defaultworkspace,始终在册;内含 `.everyagent/` 工作区级 git 凭证加密存储) |
| `workspaces/` | 唯一工作区注册表 `workspaces.json` + 按工作区归类的任务数据 `workspaces/<workspaceId>/tasks/<taskId>/` |
| `sandbox/` | 沙箱持久状态(home/opt/usr-local/resolv.conf/env;`worker.sandbox.persistent-root` 可覆盖);内含 `distro/` = WSL 托管发行版 rootfs(原 `wsl/distro` 迁入,运行期状态,可整体重装) |

**配置分层**:进程配置全部来自 jar 内 `application.yml` 默认 + `~/.everyagent/application-*.yaml` 用户覆盖(`spring.config.additional-location: optional:file:${EVERYAGENT_HOME:${user.home}/.everyagent}/application-worker.yaml`,自动加载,无自定义则零配置文件)。**模型配置由 `worker.models`(Spring 配置)承载**,默认在 jar 内(apiKey 占位符),真实 key 只写用户覆盖文件(机器级、不进工作区、不进 jar/git、不进事件日志)。**搜索限制由 `worker.search.*` 承载**(rg 进程超时、文件/任务 provider 自持上限、应答内联/切批阈值、provider 超时预算;默认值与既有行为一致,键清单见 §8.5)。

**凭据防护分三块**:①**内容输出掩码**由插件在 per-call 工具执行拦截链上行段实现,核心不持有内容脱敏逻辑;②**env 继承剔除**住在 `SecretPatterns`(plugin-api),一切子进程环境构造点先过 `scrubEnv`/`scrubInPlace`(值形态指纹 + 凭据词族判定,运行时关键变量走豁免表),不属于任何插件、不随插件装卸而失效;③**日志出口形态掩码**(`SecretPatterns.maskSecrets`)是常驻纪律,审计只打被删变量名、绝不打值,按调用方去重降 DEBUG。

**装载前置(易踩)**:内置插件不进根 reactor(根 `<modules>` = contract / plugin-parent / plugin-api / hub / worker),`deploy.py` 打 hub/worker 时也只 `-pl every-agent-hub,every-agent-worker -am package`(**插件不进 reactor**),但部署 **worker** 时会追加一步:先 `python scripts/build-plugins.py` 构建全部内置插件,再经 `every-agent-desktop/scripts/copy-plugins.mjs --out=<release>/worker/every-agent-plugins` 按与桌面安装包同一套规则 staging 后随 release 上传——worker 打包态 cwd 就是 `current/worker`,`BuiltInPluginScanner` 的默认扫描恰好命中,服务端 worker 因此才有内置插件(`--skip-plugins` 可关,关掉后该 worker 扫不到任何插件);**只部署 webapp 不碰任何插件**:`deploy.py webapp` 只传前端 dist,而插件 web 产物(如 `pdf-viewer/web/index.js`)由 worker 经 `plugin.webSource` 从**它自己磁盘上的插件目录**读,前端部署改不动插件行为,改插件后必须重新部署/重打包 worker 侧(桌面版即重跑 `npm run dist`);桌面发行版由 `npm run dist` 链中的 `build:plugins` + `copy:plugins` 构建并 staging(§9,安装包内落 `<resourcesPath>/every-agent-plugins/`);`BuiltInPluginScanner` 要求 `every-agent-plugins/<id>/target/classes/plugin.json` **且** `target/` 下存在非 sources/javadoc 的 jar,否则 WARN「内置插件未构建,请先 mvn package」并跳过(根 plugin.json 的 `enabled` 只是第二道门)。**重建全部插件的统一入口是 `python scripts/build-plugins.py`**(先跑 every-agent-web 的 esbuild bundle、再逐插件 `mvn clean package`;插件清单动态扫描 `every-agent-plugins/*/pom.xml` 与 `*/web/index.ts`,新增插件自动纳入,支持 `--only/--exclude/--java-only/--web-only/--list/--check/--dry-run`);单改一个插件也可 `mvn -f every-agent-plugins/secret-redaction/pom.xml clean package`。**另防一类静默失效**:`findTargetJars` 把 `target/` 下所有非 sources/javadoc 的 jar **按字典序全部**放进插件 `URLClassLoader`,故升级 artifact 版本后残留的旧版本 jar(如 `*-0.11.0.jar` 与 `*-1.0.0.jar` 并存)会因 `'0'<'1'` 排在前面而**遮蔽新类**——表现为"已 mvn package、已重启,改动却毫无效果",且 `RunnerMaterializer.pluginCodeSource()` 也可能解析到旧 jar 而把旧类物化给 runner。因此改内置插件后必须 `clean package`(clean 才能清掉旧 jar);若旧 jar 正被运行中的 worker 锁定而删不掉,则**必须先停止 worker 再构建**,单靠重启无效。


**程序附属文件**:核心附属文件放**程序根 `<程序根>/runtime/`**(程序根 = JVM 工作目录 user.dir;打包态 = resources 目录,IDE 态 = 仓库根),随安装包分发、运行时只读引用、以字面相对路径 `./runtime` 解析;不打进 jar、不写入系统目录。`worker.program-dir` 配置用于打包态显式指定。插件附属文件由各插件以**插件根 `runtime/` 子目录**声明,desktop 构建链把已启用插件的该目录合并进共享 `runtime/`;插件运行期经 `WorkerConfig.resolveRuntimeDir()` 以契约方式定位,插件目录内自带的同名资源优先于共享 runtime。

**多工作区并行**:注册表 `workspaces/workspaces.json` 条目 `{id, root, addedTs, lastActivityTs?, externalRoots?}`,引入**稳定 workspaceId**——默认工作区 id 恒为 `defaultworkspace`;其它工作区首次注册用 ShortIds 生成 `w_xxxxx` 短 id,落盘进注册表 `id` 字段,此后不变。默认工作区根默认 `<home>/defaultworkspace` 并自动注册进注册表(id=defaultworkspace),始终在册、不可移除。`fs.*`/`git.*`/`task.run`(新建)每次调用**必带 `workspace` 参数**(绝对路径),沙箱根在调用时按该参数解析;注册表变化广播 `workspaces.changed`(`workspaces.list` 与快照每项带 `id`、`addedAt`、`lastActivityAt`(任务收口刷新,旧条目回退注册时间),仍带 `defaultRoot`;缺失项含缺失标记 `missing`);写操作广播 `fs.changed{workspace,path,kind}`,前端按工作区分组刷新。**任务收口按「最后活动时间」倒序渲染**:收口路径(`TaskManager.finish`)经独立组件 `WorkspaceActivityTracker` 刷新任务挂靠工作区的 `lastActivityTs` 并广播(失败不阻塞收口);前端 `workspaceRegistry` 合并后按 `lastActivityAt ?? addedAt` 倒序,任务面板 / 文件管理器 / 源代码管理器渲染工作区顺序一致地对齐「最近活动的在最上面」。

**工作区外部授权根(externalRoots)**:工作区条目的 `externalRoots` 字段(realpath 规范化路径数组)承载用户经 `@` 弹窗 `+` 图标显式选择的工作区外路径(§7.16),授权语义 = **完全读写(READ+WRITE+EXEC)**——「用户显式选择=已授权」:文件路径责任链 `ExternalRootAllowCheck` 放行环直接放行、不弹授权 ask(§7.8);**命令链同源消费**(§7.8:命令串中引用的路径落在授权根内 → 判为不越界,危险动词与 EXEC 路径授权均不再触发),各沙箱后端按 §7.10 消费。**注册规则**:目录=自身、文件=父目录;去重与包含吸收(新根被已有根包含 → 跳过,已有根被新根包含 → 替换);复用 `OverBroadRootCheck` 语义拒收过宽根(盘根、工作区祖先/工作区自身)。**生命周期为工作区级**(跟工作区走,非任务级);`workspaces.remove` 删除工作区时级联清理:仅 wsl-direct 后端,对该工作区**独有**(其余工作区 externalRoots 的 realpath 均未引用)的根 best-effort umount——`wsl.exe -d EveryAgent -u root -e umount <挂载点>`(挂载点 = `WslPathMapper.toDirectMount(原生路径)`),失败 lazy umount 兜底,仍失败仅 WARN 不阻塞删除;bwrap 按次 bind 天然跟随,mic 零副作用天然跟随。

**启动自检(工作区被移动/删除)**:worker 启动时校验 `workspaces/workspaces.json` 载入的已注册目录,缺失者(用户移动/删除目录后重启)在注册表快照标记 `missing`并广播,前端弹窗要求二选一——`workspaces.resolveMissing {action:"delete"}` 删除注册并**直接删 `workspaces/<wsId>/` 整个目录(任务数据随删)**,或 `{action:"redirect",newRoot}` 纠正到移动后的新目录——**保留 `id`、只改 `root` 并迁移挂靠任务的 `meta.workspace`,任务目录不搬**;默认工作区不可删除、只可纠正(纠正后的根直接写回 `workspaces.json` 中 id=defaultworkspace 条目的 `root`,重启读回,不再需要 `workspace-default.json` 覆盖文件)。未落定的缺失工作区 `resolve` 拒绝,避免沙箱挂载失败或静默新建空目录掩盖数据丢失。

**skill 读写放行**:系统目录 `skills/` 是 AI 文件工具对系统路径的**唯一免授权**例外——``read_file`/`fs.write`/`fs.mkdir` 等经权限责任链节点 `SkillsReadAllowCheck` 直接放行(realpath 前缀判定,READ+WRITE 均放行);EXEC 不在此放行,仍走授权决议链;其余系统路径(workspaces/、sandbox/、runtime/ 等)与普通工作区外目录同权,一律走授权决议(弹窗/AI 审议)。`skills/` 同时读写挂入 wsl 系列沙箱(§7.10:wsl-direct drvfs、wsl-bwrap `--bind`),bash 工具在沙箱内同样可达,读写均放行;windows-mic 后端跑在宿主,Medium IL 读写用户文件本就放行,无需挂载。以上读写放行与沙箱读写挂载覆盖 `skillsDir` 下**全部** skill 目录(内置包、插件包、用户自制一律以目录为准,不再按来源区分);skill 携带的脚本经对应解释器执行(如 `bash script.sh`/`python3 run.py`/`node run.js`)。

**skill 知识包路径的沙箱注入**:`SkillAdvisor` 注入 system prompt 的知识包路径按当前沙箱后端解析(§7.17):WSL 系列沙箱下 `Skill.knowledgePath`(宿主 Windows 绝对路径)经 `WslPathMapper` 翻译为 AI 沙箱内可见的 `/` 开头 Linux 路径(wsl-direct `/c/...`、wsl-bwrap `/mnt/c/...`),使 AI 的 `bash`(`cat`/`grep`)与 `read_file`(经 `FsToolSupport.resolveWslPath` 反向翻译回宿主路径)均能直接使用同一路径;非 WSL 后端原样注入宿主路径。`Skill` record 仍存宿主绝对路径(物化/沙箱挂载均以此为准),路径翻译仅发生在注入提示词时。`SkillAdvisor` per-run 装配(注入集合 = 目录扫描结果按当前工作区暴露配置过滤,见下文),路径翻译逻辑与实例化时机无关。

**skill 目录结构与唯一真相源**:skill 的唯一真相源 = 系统技能目录 `skillsDir` 一路——每个 skill = 一个一级子目录 + 目录下的 `skill.md`;**不存在代码注册通道**(内置 Java 清单与插件 SPI 声明均已删除):内置包与插件包只是**安装方式不同**,经物化落盘后与用户自制 skill 完全同权,统一由目录扫描进入 prompt 与 `/` 菜单。

**安装(物化)**:worker 内置技能包住 classpath `builtin-skill/<id>/skill.md`,由 `BuiltInSkillPacks`(`@PostConstruct`)经 plugin-api 物化工具类 `SkillMaterializer.materializeAll(skillsDir, cl, BUILTIN_SKILL_ROOT)` 落盘为 `<skillsDir>/<id>/skill.md`;插件技能包**不再走 classpath**——插件在 plugin.json 声明 `"skills": "skill"`(值为相对插件根目录的 skill 目录路径),skill 文件放 `<pluginRoot>/skill/<id>/skill.md`(与 plugin.json 同级,每个一级子目录一个 skill,一插件可带多个),由 `PluginLoader.loadPlugin()` 在**禁用检查之后、入口类检查之前**统一物化(禁用插件不贡献 skill;不依赖 Java 入口类,**纯 web 插件也能带 skill**),物化实现为 `SkillMaterializer.materializeFromDirectory(skillsDir, pluginSkillDir)`。旧 API `materializeAllFromClasspath()` 与 `PLUGIN_SKILL_ROOT` 已删除——插件技能包改从文件系统目录读取后,原 classpath 父委派泄漏隐患(插件 classloader 经父委派看到 worker 的 `builtin-skill/` 资源、把内置包错当自己的代装)对插件侧不复存在;内置包仍在 classpath `builtin-skill/` 根,`BuiltInSkillPacks` 仍走 `materializeAll` + `BUILTIN_SKILL_ROOT`,不受影响。**两个资源根刻意不同**对内置包与历史 jar 形态插件仍适用(根名隔离让「每个包只物化自己的 skill」零运行时成本成立,谁放错根,启动日志的物化 id 清单立即可见)。物化语义不变:幂等(与源内容**逐字节一致**则零写入;不一致则覆盖——判据是内容而非文件大小,"大小恰好相同的"等长升级不会被漏掉)、原子写入(`AtomicFiles.replace` 临时文件 + 原子替换,杜绝扫描读到半截文件)、并清理旧扁平 `<id>.md` 残留;资源缺失/IO 失败抛 `IOException` 由调用方 WARN 降级、不阻断启动——**代价:该 skill 整体消失**(缓解:启动 WARN + 重启 worker 重试复原)。

**发现侧非法布局不静默(ERROR)**:`materializeAll` 扫 `classpath*:<root>/*/skill.md`、`materializeFromDirectory` 扫 `<pluginSkillDir>/*/skill.md`,命中的资源/目录若**目录名不合 id 规则**(如 `Plan`/`my_skill`/中文/带点),`SkillMaterializer` 打 **ERROR**(带推导出的 id 与资源描述,并附规则口径)后跳过、不落盘——「加了包却没出现」从此有一条可归因的日志;此前是**静默跳过**,加包者只能在物化 id 清单里数少了一项、无从归因。注意深嵌套 `<root>/a/b/skill.md` 是**压根扫不到**(一级 `*` 不匹配,不进这条日志),故加包的两个必要条件是「id 合规」+「层级恰一层」;`isValidId` 不通过而**不**报错的路径已不存在。

**frontmatter(仅展示元数据,只有两个键)**:`skill.md` 首部允许一个 `---` 定界的元数据块(允许 UTF-8 BOM;缺块/未闭合 → 视为无元数据)。行语法 = `key: value` 最小子集(键 `[A-Za-z][A-Za-z0-9_-]*`、大小写不敏感;值去首尾空白、剥离成对引号;整行 `#` 开头视为注释;未知键忽略记 debug、`key:` 空值 = 未提供;**不支持**多行折叠/嵌套/列表,**不引入 YAML 依赖**)。已知键只有两个、都**只影响展示**:`name` = 展示标题(`/` 菜单 title、prompt 条目标题、设置页表格 name 列),缺省或空值回落目录名——**不与目录名做一致性校验**(内置 id 是英文标识、`name` 是人读标题,如 `plan` → 「计划模式」,不一致是常态而非异常);`description` = 一句话「何时用」(渐进式披露索引、`/` 菜单副标题、设置页表格描述列),单行截断 200 字符,缺省回落「首个非空且非 `#` 的**正文**行」(必须跳过 frontmatter 块本身;兼容未迁移的第三方 skill),再缺省为空串。**暴露控制不在 frontmatter**:是否进 system prompt 由 worker 侧按工作区暴露配置(黑白名单)决定(见下),`skill.md` 不含此信息、也无法声明它;第三方 skill 落盘即默认全部工作区自动暴露 + 进 `/` 菜单,要收敛由用户在设置页按工作区配置。

**id 规则**:目录名即 skill id,仅允许 `[a-z0-9][a-z0-9-]*`;realpath 必须仍在 `skillsDir` 内(拒绝符号链接越界);`name` 只作展示、不参与去重——单目录扫描下目录名天然唯一,同名冲突只剩物理不可能的「同名目录」。

**目录扫描(首次懒加载缓存)**:`SkillCatalog` 扫 `skillsDir` 全部一级子目录(**不排除任何 id、不递归**)产出 `Skill`(id, title, description, knowledgePath)——记录无暴露字段(暴露是 skill × 工作区的关系,存排除配置,注入时组合)。**首次 `scan()` 时实扫目录并缓存,此后只读内存快照**;懒加载天然解决时序问题:`BuiltInSkillPacks` 的 `@PostConstruct` 物化内置包,而 `scan()` 的首次调用一定发生在 worker 完全启动之后(任务请求或菜单请求),那时物化早已完成。skill 目录变更(增删 skill 目录)需重启 worker 才能生效——skill 变更是低频运维操作,重启成本可接受。

**暴露控制(黑白名单并存,黑名单优先,默认全部暴露)**:默认「全部 skill 在全部工作区自动暴露(进 system prompt)」;用户在设置页按 skill 同时配置黑名单和白名单——**黑名单**:列出的工作区不自动暴露;**白名单**:非空时仅列出的工作区自动暴露,其余不暴露;**黑名单优先**:若工作区同时在黑白名单中,黑名单胜出(不暴露);**两个清单都空** = 恢复默认(删除键)。`/` 菜单不受此配置影响(永远全量)。存储 = worker 侧 `<homeDir>/skill-exposure.json`(见上表;每台 worker 各自一份——**配置随 worker 而非全局**,换 worker 需重配),身份键 = 工作区根路径(它是工作区的身份键;代价:工作区移动/改名后旧配置失效、该 skill 在新路径恢复暴露,残留项不清理、不生效但无害)。旧版 `skill-exclusions.json`(数组值格式)启动时自动迁移为 blacklist;旧版单模式 `{"mode":"blacklist"/"whitelist","workspaces":[...]}` 自动迁移为对应清单。RPC:`skill.list`(全量 skill + 各自 `blacklist`/`whitelist`,设置页表格数据源,按 worker 分组展示)/`skill.exposure.set`(校验与广播语义见 §5.5;已注册但后来被删的工作区留在配置里不报错,前端展示时过滤、保存时原样保留)。

**prompt 注入与菜单(同一份数据)**:`SkillAdvisor` **per-run 装配**——provider 的 `create(ctx)` 按每次运行的 `ctx.workspaceRoot()` 现取暴露配置实例化(SkillAdvisor 无状态,per-run 零成本;暴露过滤需要 workspaceRoot,共享单例拿不到)。注入集合 = `SkillCatalog.scan()` 按当前工作区的暴露配置过滤(**无配置 = 全部注入**,含全部用户自制 skill——默认全量暴露使每轮 prompt 变大,这正是本设计的语义,嫌大就按工作区配置黑名单/白名单;`workspaceRoot` 为 null/空白时放行注入并记 WARN,宁多勿漏),知识包路径按当前沙箱后端翻译注入(见上节)。`/` 菜单(`SkillSlashProvider`)同一份 `SkillCatalog.scan()` 全量列出——全部 skill 一律自动成为菜单项,不再按来源区分,选中走 `SkillSlashTokenResolver` 链路(token 解析为「请使用技能:`<title>`。知识包路径:`<skillPath>`」),AI 按需 `read_file` 知识包。**「一次目录扫描 = prompt 与菜单的同一份数据」是本节核心不变量**:不存在「对模型可见但对用户不可见」(或反之)的 skill,二者差别只剩暴露过滤。子 agent 同规则注入(与主 agent 共用执行入口,其 workspaceRoot 即所属任务的工作区)。

**不兼容变更**:skill 代码注册通道删除是破坏性变更;进一步,插件技能包物化从「`activate()` 里调 `SkillMaterializer.materializeAllFromClasspath` 扫 jar 内 classpath `skill/` 根」改为「plugin.json 声明 `skills` 字段 + skill 文件放插件根目录 `skill/<id>/skill.md`(与 plugin.json 同级),PluginLoader 统一物化」——`materializeAllFromClasspath()` 与 `PLUGIN_SKILL_ROOT` 已删除,第三方插件需把 skill 文件从 `src/main/resources/skill/` 搬到插件根目录 `skill/<id>/skill.md`、在 plugin.json 声明 `"skills": "skill"`、并删除 `activate()` 里的物化调用(纯 web 插件从此也能带 skill,无需 Java 代码)。

**明确不做**:目录监听/watch、脚本注册为独立 AI 工具(脚本一律由 AI 用对应解释器执行——bash/python/node 等不限语言,复用现有沙箱与 PermissionGate)、完整 YAML 语法支持(frontmatter 只取 `key: value` 最小子集)、跨 worker 全局暴露配置。

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
| `metadata()` | 持久数据槽（随 meta.json 落盘，如 `attachments`；**任务级开关**（AI 审议/无人值守/禁用网络）不走 metadata，其真相源是 `slash-tokens.json`，经 `WorkerServices.slashTokens()` 查询） | `t.metadata()` | 文件附件注入（UserInputMaterializer/FileAttachmentAdvisor） |
| `dataDir()` | 数据目录（grants.json/agents.json 落盘；今天=任务数据目录） | `t.taskDir()` | GrantRegistry、AgentLedger |
| `terminal()` | 主体是否已收口（leak-guard：终态后不再发射事件） | `t.status.terminal()` | WorkerToolEventAdvisor |
| `interaction()` | 已绑定主体的用户交互口（静态代理，ask 的 context map 自动填 subjectId；替代三处手动组装 `Map.of("taskId",...)`） | 手动组装 context map | Human 授权节点、ImageReferenceHandler、ask-user 插件（AskUserTool） |
| `agents()` | 主体活动 agent 注册表（可读写 Map：主 agent + 各插件派生——子 agent、审议 agent；**由 `AgentBuilder.build()` 自动填充（只 put；agent.* 生命周期事件由 advisor 链 + `AgentEntity` 统一发射，§7.20.1），插件不再手动 put/emit**；无子 agent 是正常形态） | `t.agents()` | SubAgentManager（复用判定）、AiAuthReviewer（复用判定）、AgentLedger（台账投影） |

**不进 ExecContext 的槽位**：fileChanges 曾是「留 TaskRuntime 的插件功能槽」，现已彻底退役出核心接口（连 TaskRuntime 也不留，§7.15.2）——collector 由 file-change provider 维护任务级共享实例（主/子 agent 经 per-call 链上行节点共写，§7.15.2），按轮落盘靠 `RoundClosedListener` 回调，读侧靠插件自注册的 `task.fileChanges` RPC；status 完整状态/touch 等任务操作同为任务域私有，横切层只需要 `terminal()`。

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
| `FileReferenceContext` | `taskId()`、`workspaceId()`、`workspaceRoot():Path`、`execution()` | （无领域专属成员） | 早期节点任务未创建时槽位可为 null，语义保留 |
| `ToolExecutionContext` | `execution()` | prompt/chatResponse/toolCalls（批级）+ per-call 专属：`toolCall()`（当前调用）/`toolCallback()`（当前回调）/`arguments()`+`arguments(String)`（可改写入参）/`synthesize(String)`+`synthesizedResult()`（下行合成哨兵）/`startedAt()`+`startedAt(long)`（切口计时）/`toolOutcomes()`（可空账本）+ per-run 归属 `agentId()`（RunContext 下行，MAIN/SUB_AGENT 判定查询键，§7.15.2） | per-call 链 ctx 由 `PerCallToolCallingManager` 切口逐调用构造（§7.14.3），ExecContext 槽位委托 RunContext（emitter 覆写为 agent 级 agentId 包装） |
| `TaskLifecycleContext` | — | `taskId()`/`workspaceRoot()`/`workspaceId()` 作为**任务域成员**保留（消费侧零改动） | 仿 TaskRuntime 范式：新增 default 桥接（`subjectId()`→`taskId()` 等），其余 ExecContext 槽位以 default 委托 `taskRuntime()`（早期 RPC 阶段任务未创建时返回 null/空，语义保留） |

**消费侧命名迁移规则**：横切层取主体 ID 一律 `subjectId()`；原 `ctx.taskId()` 调用点（ToolContext/AdvisorContext/FileReferenceContext/ToolExecutionContext 的消费侧）全部迁移到 `subjectId()`，`taskId()` 只保留在 TaskRuntime/TaskLifecycleContext 等任务域接口上作为任务域成员。

#### 7.20.6 消费者取数路径（properties 黑盒与 map 槽退役后）

- advisor：`AgentContext.execution()`（`AgentEntity` 字段替换为 execution）；`AdvisorContext extends ExecContext`，槽位委托 `agentEntity.execution()`（§7.20.5）。
- 工具 provider：`ToolContext`/`ToolExecutionContext` 本身即 ExecContext（`extends`，`execution()` 槽位已删、直读槽位；`ToolContextImpl.taskEntry()` 已删，§7.20.5）；`PerCallToolCallingManager` 是共享无状态单例，per-run ExecContext 经 prompt options 的框架 ToolContext map 下行（不依赖 ThreadLocal——reactive 流工具执行可能切 boundedElastic 线程，§7.14.3）。
- subagent 插件：全面中性化（§7.14）——Manager 零服务依赖收 ExecContext、**台账收编 worker core `AgentLedger`（`SubAgentLedger` 退役，agents.json 读写/事件投影/冷启动恢复全部由 worker agent 层承担）**、`TaskStoreService` agents 段退役、生命周期节点壳核分离、`task.agents` RPC **已收回 task 域**（常量 `RpcMethods.TASK_AGENTS`、由 `TaskManager` 注册实现，语义=任务下全部 agent、无 creator 过滤，主 agent 条目恒保留，§5.5/§7.20.1）、subagent 插件**不再提供该 RPC 与 web 前端功能**（原 `ui.composer_above_panel` 面板退役，agent 胶囊列表归 web 核心 `TaskChat` + `AgentListPanel`）；SubAgentManager 创建子 agent 时设 `creator("subagent")`、移除手动 put（由 `build()` 自动完成）与**全部 agent.* 事件手搓发射**（含复用路径的 `agent.started` 补发）；`TaskStore.truncateAfterSeq` 编辑重发截断只动 *.jsonl/rounds.jsonl，**不删任何插件数据文件**（agents.json/file-changes 残留陈旧条目被接受；后续可增加 `task.truncated` 截断事件通知由插件自清——开放项，task 核心永不知晓插件文件名）。
- AI 审议 agent：per-task 固定 agentId `review-<subjectId>` 注册进 `agents()` 跨请求复用会话（§7.9）——`agents().get(id)` 命中即续跑（复用路径不补发 `agent.started`——advisor 链每轮 run() 自动发）；未命中经 `ctx.agentFactory().create(id, reviewModel)` 创建，设 `creator("ai-review")` 后 `build()` 自动注册（台账记录其条目；`list_agents` **工具**按 `creator=subagent` 过滤故不可见，而 `task.agents` RPC 现返回全部 agent、包含审议 agent 条目）。
- `InteractionService` ask 的 context map `"taskId"` 键保留（值=subjectId，今天相等，前端兼容）；`SubjectBoundInteractionService` 静态代理在键缺失时自动补填，替代各处手动组装。

#### 7.20.7 工作流复用终态

未来工作流层 `WorkflowRuntime implements ExecContext`（subjectId=workflowId、dataDir=工作流数据目录、emitter=工作流事件口、agentFactory=WorkflowBoundAgentFactory）——同一条授权链、同一批 advisor、同一工具链、同一 subagent/审议能力**零改动复用**（§7.14.2 分层：工作区 → task 层/工作流层平级编排 → agent 层 → 基础设施层，下层不知道上层）；事件管道（§14.0）与执行上下文管道双通道同构。

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

对话按"轮次"折叠展示(每轮 = 用户消息 → AI 最终回复);**画板 = 纯 wire 折叠产物**——线程数据层(items)只由 wire 事件(task.poll 懒加载分页 / task.roundTail 尾段 / stream 定向推送)折叠而成,**rounds.jsonl 通道(task.rounds 快照)只做渲染投影与切片区间元数据,不进数据层**:不再有 foldRound 合成折入/metadata.synthetic 标记/骨架升级(user.message handler 对主/子 agent 同一路径插入真实消息);轮骨架(user 气泡/折叠条/final 摘要/耗时)由 TaskRoundsPanel 按 rounds 快照直接合成渲染(闭合轮与尾轮 user 气泡同一 `syntheticUserRecord` 口径——运行中尾轮用 open 轮 `userMessage`,终态尾轮用末行 round 的 `userMessage`),折叠窗口语义归 rounds.jsonl 唯一权威、线程项无折叠标记(foldRole 体系已删,TaskThread 渲染为平铺分组)。

- **条目契约(五字段 ThreadItem,步骤 8 定稿)**:items 的唯一条目形状 `{ seq, ts, agentId, kind, payload }`(定义于 `src/task/threadItem.ts`)。`seq`(string,wire 原始雪花大数串)是排序/去重/切片/懒加载游标的唯一键——**seq 即 id**,旧 `m-`/`t-` 前缀 messageId/traceId 体系废除;`ts`(number)事件时间戳;`agentId`(string)归属 agent(折叠器写入,事件缺 agentId 归一到 mainAgentId,极早期未灌入时可为空串——消费方按 `item.agentId || mainAgentId` 归一);`kind`(string,**开放集合**:`user.message`/`message`/`tool.result`/`error`/`cancelled`/`retry`/`model.failover`/… 及 defaultHandler 折入的任意未来 kind;瞬态 `round.opened`/`round.closed`/`tool.started` 与台账类 usage/agent.*/ask.* 不进 items);`payload` **形状所有权归 kind**——由该 kind 的折叠 handler 写、对应视图读,逐 kind 声明:`user.message`→`{content, rawContent?}`、`message`→`{role:'assistant', content, thinking?, toolCalls?, streaming?, reasoningDone?}`(thinking/delta 流式帧与 message 定稿共用同一条 message 项,streaming/reasoningDone 为折叠层维护的帧状态)、`tool.result`→`{callId, content, status?, startedAt?, durationMs?, toolName?}`、trace 族(retry/context.compression/model.failover/model.switch/auth.review/system.notice 与 defaultHandler,kind=事件名)→`{title, summary?, content?, data?}`、`error`→`{title, summary?, content?}`、`cancelled`→`{title, summary?}`;`KindPayloadMap` 为声明合并接口(kind → payload 类型),仅作**编译期** handler↔视图形状配对校验,运行时不存在、不消费。消息族 kind(`user.message`/`message`/`tool.result`)参与懒加载缓存覆盖判定(`roundRealFloor`/`roundRealCeiling`)与尾轮首事件定位;items 内不再有 AgentMessageRecord/TaskTraceRecord 双形状——渲染层的消息视图模型与 `AggregatedToolDetail` 等聚合形状由 ThreadItem 适配(渲染内部形状,步骤 10 分发切换时随视图迁移)。
- **切片一律按 seq 区间比较**(`startSeq < seq <= endSeq`,大整数精确比较):骨架出画板后端点锚点条目(起点 user / 终点 final)可能不在 items,禁止 findIndex 端点定位(其 from=0/to=length 兜底在 items 含其他轮条目时会误切);尾轮切片为 `(tailStartSeq, ∞)`(起点 user 气泡由快照合成渲染,不在尾轮 items 切片内,不再需要「剥起点 user」后处理——子 agent 过滤态首条 user.message 因 seq 严格大于起点天然保留)。
- **过程内容懒加载**:闭合轮展开 / 终态未闭合尾轮走前向分页(滚动到底部 forward sentinel 续拉 `LazyLoadSentinel`,拉到 endSeq 停);运行中尾轮 = 订阅实时流 + 初始「最后一页」(roundTail 200)+ 顶部 backward sentinel 上翻历史(到达 startSeq 停)。拉取层先查 items 缓存(`roundRealFloor`/`roundRealCeiling` 按 seq 区间求已覆盖边界),已覆盖不 RPC、部分覆盖从缺口续拉。
- **分页状态 key = roundId**(轮次级稳定主键):运行中尾轮用 open 轮 `roundId`(worker 开轮即生成、跨闭合稳定),老 worker 缺失时回退 `'open'` 魔法串(must-ignore 双向兼容);backward 游标语义仍是 seq,仅 key 名变(每轮独立游标,轮换新开不再复用上一轮旧游标)。
- **resync/编辑重发的 items 重建**:full 重载仍 reset items(编辑重发后旧轮次 seq 已过期,混排会顺序错乱),重建来源 = 尾段 wire 回放 + 实时推送 + **分页状态失效促重拉**——folder 暴露 `resetEpoch` 计数,轮面板观测到 epoch 变化即清空分页状态,已展开轮自动重新发起首页拉取(拉取层先查 items 缓存,缺才 RPC),避免 reset 误清已展开轮内容后无重拉路径。
- 渲染层任务线程按 **seq 键控增量 upsert**(多子 agent 并发/重连回放不丢事件;大整数 seq 用 BigInt 精确比较);`userControll` 自动滚动(默认 false = 收到数据贴底,用户手动滚动置 true,滚回底部复位)。

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

前端测试统一走 vitest:`every-agent-web` 内 `npm test` = `vitest run`(`vitest.config.ts` 的 resolve.alias 与 vite.config.ts 锁同一套别名;node 环境,不引 jsdom;include 收敛 `src/**/*.test.ts`),`npx tsc --noEmit` 覆盖测试文件类型(测试放被测模块同目录)。Phase 0 起 `src/task/eventFolder.test.ts` 为折叠器(eventFolder + eventRegistry)的**特征化测试**:锁定乱序 seq 归位、重连重放去重、thinking/delta 流式聚合与 message 定稿、截断、agent 台账镜像、isMain 判定与 defaultHandler 兜底等现状行为(foldRole 打标断言已随步骤 5「删除 foldRole 体系」同步移除,口径改为线程项无折叠标记;台账断言已随步骤 6「agentMeta/agentStates 拆出 eventFolder」改指向独立台账 store `folder.ledger`——事件仍经 fold() 同一入口喂入,锁的是 registry handler → 台账 store 的真实写路径,语义不变;foldRound 合成折入/upgradeSyntheticUser 骨架升级/metadata.synthetic 标记断言已随步骤 7「rounds 骨架出画板」删除——画板纯化为 wire 折叠产物,骨架行为不复存在,另补 roundRealFloor/roundRealCeiling 区间缓存判定用例),作为「任务线程统一条目与画板纯化」重构的行为护栏——先锁后删,重构步骤须同步更新断言而非绕过。步骤 2 起轮次视图纯函数(闭合轮切片 sliceRound、尾轮切片、agent 过滤键与谓词、seq 大数比较、合成 user/final 记录、闭合轮与终态尾轮筛选)从 `TaskRoundsPanel.tsx`/`RoundDetail.tsx` **原位提取**至 `src/components/task/roundsView.ts`(纯逻辑模块,不 import React 组件链——组件模块挂着 pluginDispatcher/bridge 等副作用导入,node 测试环境无法安全加载;组件侧改等价委托,提取不改行为),`src/components/task/TaskRoundsPanel.test.ts` 为其特征化测试(含 ec050499「按 agent 过滤时尾轮漏掉子 agent 首条 user.message」回归用例),同为重构护栏;步骤 7 起切片断言改为 **seq 区间比较口径**(`startSeq < seq <= endSeq`,端点条目不在场不误切、items 含其他轮条目不误切;尾轮切片 `(tailStartSeq, ∞)` 起点 user 剥离内建,tailItemsAfterUser 后处理已删,回归用例改为区间口径形态);步骤 8 起两份测试断言随条目形状统一更新为五字段 ThreadItem(seq/ts/agentId/kind/payload,seq 即 id,断言里 `m-`/`t-` 前缀 id 退役)——行为语义保持(乱序/去重/流式聚合/定稿/区间切片/过滤回归),taskQueryService 线程层旧形状 API(与 n 同形的 getTaskThread/getTaskAgentMessages/getTaskTraces/getTaskChatSnapshot)随之删除。步骤 9 起 kind 渲染基建(kindViewRegistry 查表/FallbackView 宽松读取/useThreadBoard context)有独立单测 `src/components/task/kindViews/registry.test.ts`(渲染断言用 react-dom/server 的 `renderToString` 在 node 环境跑——不引 jsdom/@testing-library,零新依赖,对齐既有 node 测试口径;宽松读取语义按纯函数 `readFallbackTraceShape` 直测)。步骤 10 起 tool 域共享模块有 `kindViews/toolDomain.test.ts`(resultsFor/absorbedIntoMessage/detailFromCall/终态信号判定纯函数直测)、内置 kind 视图有 `kindViews/builtinViews.test.ts`(renderToString:批壳批头/调用行摘要/状态点 class/批折叠 aria/批内同名不折叠/无正文纯下发轮无占位文案/user.message 回放胶囊段/孤儿 tool.result 兜底/error 色调 kind 直配/TaskThread 查表分发+FallbackView 兜底),tool.started 瞬态映射断言并入 eventFolder.test.ts(不进 items/运行时台账记录/reset 清理)。

### 8.7 kind 渲染注册表与内置视图(步骤 10 分发切换定稿)

渲染层对任务线程条目是**两级查表分发模型**(`src/components/task/kindViews/`):

- **第一级(kind 级,分发)** — TaskThread 是唯一分发点,`items.map(item => { const View = getKindView(item.kind) ?? FallbackView; return <View key={item.seq} item payload taskId mainAgentId/> })`,外层包 `ThreadBoardProvider`(value = 该次渲染的 items 切片)。查 `kindViewRegistry`(`kindViews/registry.tsx`):插件 `ui.kind_views` 注册的视图**优先**,核心注册三类(`user.message`/`message`/`tool.result`,`builtins.ts` 集中注册;`error`/`cancelled` 不再有第一层专视图——收敛进 FallbackView,由第二级表给定义);查表未注册返回 **`undefined`**,兜底选择 `?? FallbackView` 是**分发语义**,不埋进注册表。`key = item.seq`(seq 即 id,雪花大数原始串)。
- **第二级(呈现级,trace 形状)** — FallbackView(含 error/cancelled 等)的呈现均复用 `TaskTraceShell` 壳(`kindViews/TraceShell.tsx`):壳内按传入 kind **再查一次** `traceTypeRegistry`(插件 `ui.trace_types` 扩展点旁路写入,§8.5;**注册键 = wire kind**,点号形态事件名),命中用插件定义(getSummary/renderContent/canExpand/getIcon/hideTitle;**无 tone 字段**,色调不再是扩展点能力),未注册走通用降级 `getFallbackTraceType`——这一级查表**永不返回 undefined**(查不到给 fallback 定义)。两级兜底语义的差异是刻意的:第一级兜底决定「**由谁渲染整行**」,第二级兜底只决定「**壳内怎么呈现**」。
- **端到端保证** — 两级均未注册的新 kind 经 FallbackView → TaskTraceShell → 通用降级自动可见、**内容零丢失**(payload 宽松读取归一为 title/summary/content,`data` 字段原样透传给第二级,见下 FallbackView 条)。另注:工具调用行的 per-tool 美化视图(批壳调用行/孤儿 tool.result 按**工具名**查 `toolViews/registry.ts`,插件 `ui.tool_call_views` 同表)是消息/工具路径内部的又一处查表,只管展开详情,不属这两级主链。

旧 renderMessageGroups 分组渲染生态全部消亡(见下方消亡清单)。

- **kindViewRegistry(`kindViews/registry.tsx`)** — `registerKindView(kind, View)`(重复注册覆盖,空 kind 抛错)/ `getKindView(kind)`(查表顺序:插件 `ui.kind_views` 优先 > 核心注册表)/ `unregisterKindView(kind)`(返回是否确实移除)。**KindPayloadMap 编译期配对发生在注册点**:内置视图以 `KindViewProps<'message'>` 等泛型签名注册(`kindViews/builtins.ts` 集中注册,TaskThread 副作用 import),payload 类型随 kind 锁定;分发点 kind 动态,payload 直传 `item.payload`(类型化读取用 `payloadAs`,视图内部按需调用)。
- **插件 kind 视图扩展点 `ui.kind_views`(第 18 个 web 扩展点)** — 把第一级注册表向插件开放:`ctx.ui.registerKindView(kind, Component)`(双参;kind 空/纯空白抛错,Disposable dispose 即注销回退核心渲染),注册表项 `KindViewDefinition{kind, Component}`;`Component` 契约 = `KindViewProps` 最小集(见下),是**叶子视图**——props 不含兄弟行 items,需要 tool.result 配对/吸收判定/终态信号等兄弟行语义的接管不属于本扩展点(那是内置视图经 `useThreadBoard` 的职责,插件侧也无法引用该宿主 context);只想给 trace 形状条目换壳内呈现的仍走 `ui.trace_types`(第二级)。查表顺序:插件注册项优先于核心(与 `getToolView` 同款合并,`getKindView` 内先查 `pluginDispatcher.listRegisteredKindViews()` 再查核心 Map,单向依赖无环);插件可接管任意 kind(含内置三类与 message 族,接管即该类条目整行让位,慎用)。插件不注册任何东西时 FallbackView 兜底自动可见——扩展点只为「美化某个 kind」存在,不为「让某个 kind 可见」存在。
- **KindViewProps 最小集** — `{ item: ThreadItem; payload; taskId: string; mainAgentId: string }`,**不含 items、不含 toolJoin 回调**——视图需要兄弟行(tool.result 吸收判定/结果配对/终态信号等)一律经 `useThreadBoard()` context 取;工具配对归渲染层 tool 域共享模块,核心与画板零知识。缺省 Provider 时 hook 返回空 items 并 `console.warn` 一次(模块级 once,防误用不刷屏)。
- **FallbackView(registry 同文件)= TaskTraceShell 形状兜底(title/summary/content/pre)** — 从 item+payload 宽松读取 `{title, summary, content}`:payload 非对象时整体 stringify 为 content;`payload.title` 缺省回退 item.kind 作标题;content 为空而对象尚有未展示字段(如 trace 族 `data`)时整体 stringify 兜底——**内容零丢失**。trace 族(retry/model.failover/…)不注册专视图,自然落 FallbackView 呈现。**归一不得吃掉 `payload.data`**:第一级兜底只做「宽松读取补 title/summary/content」,归一后的 payload 必须原样透传 `data`(对象才透传)——`data` 是第二级 trace 类型(`ui.trace_types`,§8.5)的唯一 metadata 供体(`TaskTraceShell` 以 `metadata: payload.data` 合成 `PluginTraceRecord`);丢了它,插件 trace 视图(如 ai-review 的 `auth.review` 判断卡)拿到的 metadata 为空,结论徽标退化成「未知」、折叠行摘要退回 worker 原始 summary,而 `content` 的 stringify 兜底又让该行仍可展开——表现为「卡片渲染了但全是空字段」(2026-10 实测事故)。
- **trace 行与工具行是同一个东西:列几何 + 展开几何都住在共享壳** — 两类行都是「图标 + 标题 + 摘要 + 可展开详情」,故**折叠行列几何**与**展开几何**一律由 `EntryShell` 的 `nagent-entry__*` 基线单一提供,行族容器类(`.nagent-trace-shell` / `.nagent-tool`)不得再各自定义展开外观。
  - **折叠行列几何**:`--text-column-indent`(21px = 13px 图标 + 8px gap)是**文字列**,其左侧 0~13px 是**图标列**;工具行天然如此(容器不缩进、图标在文档流首位),trace 行由样式层实现同一几何:容器 `.nagent-trace-shell` 左缘留在 0,缩进挂在折叠行 `.nagent-entry__summary` 的 `padding-left`,图标槽 `.nagent-entry__icon` 宽 13px(与工具图标 `WrenchIcon`/`TerminalIcon` size=13 同尺寸)并以等量负 margin 挂进该 padding 区——**缩进必须挂在行内而非容器**,否则负 margin 一出容器边界就被其 `overflow:hidden` 裁掉。结果:图标与工具图标同列同尺寸,带图标行与无图标行(cancelled/system.notice)文字起点一致(旧写法容器 `margin-left: --text-column-indent` 使带图标的 trace 行整体比工具行深一缩进)。
  - **展开几何 = `.nagent-entry__content` 单一真相源**:扁平形态——无外框、无底色、无顶部分隔线,上间距 `--space-2`、详情左缘 = 统一文字栏 21px(与折叠行文字同一条竖线)。trace 行旧有的 `is-open` 边框圆角卡片形态**已废**(它与工具行展开观感不一致,而两类行本就同类);工具详情块 `.nagent-tool__detail` 也不再自带 `margin-left:21px`(改由壳提供,自带会与壳缩进叠加推到 42px)。行族真实的**折叠行**差异仍可覆盖:工具行 baseline 对齐/字重 500/行高 1.6/摘要禁选/无右 padding;悬停统一为「只变文字色不铺底色」(密集扁平列表里整行底色会随鼠标块状闪烁)。
  - **图标着色属插件领域语义**:宿主 trace 扩展点无 tone 字段,插件要按自身结论给图标着色,走 `getIcon` 的 ReactNode 通道自带 className + 插件 CSS(如 ai-review 的 `auth-review-glyph--allow/deny/escalate` = 绿/红/琥珀),宿主不代管配色。

- **tool 域共享模块(`kindViews/toolDomain.ts`,纯函数)** — 工具配对只在渲染层
:①`resultsFor(toolCalls, items)`:message 视图用,从画板 items 找 `kind='tool.result'` 的行按 `payload.callId` 配对(未到 → null,对位数组);②`absorbedIntoMessage(item, items)`:tool.result 视图用,存在含该 callId 的 message 下发 → true(视图 return null 不重复渲染),孤儿 → false(独立兜底渲染不丢);③`detailFromCall/detailFromOrphanResult`:ThreadItem 载荷 → `AggregatedToolDetail`(per-tool 美化视图的唯一输入);④`interruptedAfterMessage(item, items, mainAgentId)`:批中断的可见终态信号判定(画板内出现 seq 更晚的 `cancelled`/同 agent `error`)。
- **message 视图(批壳+调用行模型,`kindViews/messageView.tsx`)** — thinking(AssistantReasoningBlock:流式且未 reasoningDone 展开、完成后自动折叠)与正文(RichMessageContent)现状迁移;工具批壳 = 一次 message 的 `payload.toolCalls` 为一批:左侧竖线容器 + 批头「🔧 N 个调用」(可折叠,默认展开);调用行 = 状态点 + 等宽工具名 + per-tool 参数摘要(复用 toolViews 摘要规则 `buildCallInlinePreview`:path/command 首行注释/标量铺平)+ 行尾状态与耗时;点击展开参数与结果(per-tool 美化视图接管,长内容内部滚动 max-height)。**批内同名多调用不折叠**(10 个 read_file 10 行);**无正文纯下发轮不加占位文案**(批壳即该行全部内容);已定稿且无正文/无思考/无工具调用的空消息保持隐藏。
- **运行态与读秒(`src/task/toolStartedRuntime.ts`)** — `tool.started`(瞬态,不进 items)由 eventRegistry handler 维护模块级 `Map<taskId, Map<callId, {name, arguments, startedAt}>>`(返回 true 驱动重渲染;`folder.reset()` 清空该任务映射):message 视图批内按 callId 直配点亮 running,计时 = `now - startedAt`(批壳单一 setInterval 1s,卸载清理,仅存在 running 行时挂);对应 `tool.result` 到达 → 冻结权威 `durationMs`;历史回放无瞬态事件 → 直接展示 result 的 durationMs;批中断(agent/task 终态,从简只看可见信号:画板内 seq 更晚的 cancelled/同 agent error)→ 已 started 标「执行中被中断」、未 started 标「未执行」,灰显。不依赖框架执行顺序(started 是事实声明,并行多个 running 同样正确)。
- **user.message 视图(`kindViews/userMessageView.tsx`)** — CollapsibleUserBubble 全套迁入:回放胶囊段(opaque 串自包含切分回放 @文件胶囊)/子 agent 输入判定 `agentId !== mainAgentId`(低调样式 + 折叠阈值 26px;普通 220px)/用户消息动作扩展点 `ui.user_message_actions`(seq = item.seq;子 agent 输入不带)。TaskRoundsPanel 的合成 user 气泡(rounds 快照)复用同一共享组件 `UserBubbleMessage`,合成 final 回复复用 `FinalReplyMessage`(messageView 导出)。
- **tool.result 视图(`kindViews/toolResultView.tsx`)** — `absorbedIntoMessage` → return null(被下发块吸收);孤儿 → 独立兜底条目(per-tool 美化视图,无参数回填)。
- **error/cancelled 视图(收敛进第二级)** — error/cancelled 不再有第一层专视图(statusViews.tsx 已删除):自然落 FallbackView → TaskTraceShell,壳内按 wire kind 直查 traceTypeRegistry——`error` 注册缺省 alert 三角图标(getIcon `trace.icon ?? 'alert'`),`cancelled` 默认定义(payload 无 content 即静态摘要行)。TraceShell 只按传入 kind 查表。
- **renderMessageGroups 消亡清单(步骤 10 全删)** — ①连续 tool 合并 `mergedToolMessages`(连续 tool.result 分组包裹渲染)→ 吸收模型替代(message 批壳经 resultsFor 配对,孤儿走 tool.result 视图);②「被吸收跳过」的分组级判定 → tool.result 视图内部 `absorbedIntoMessage`;③role 分组包裹(`nagent-msg-group`/`isContinuation` 连续同角色分组)→ 删除(用户视角消息只有 用户/AI/工具/trace 四类,无按 role 连续分组语义);④`buildToolMaps` join 索引(callId → 名称/参数/结果消息三表)→ tool 域共享模块 + `folder.getToolName` 回填;⑤`messageViewOf`(ThreadItem → AgentMessageRecord 适配)→ 各视图直接消费 payload。**GroupedToolCallView「连续同名 ×N」聚合退役删除**(它表达的是名字相似性,压扁了批次语义),`renderAggregatedToolDetails` 按工具名切组加壳的分发逻辑一并消亡;`toolViews/registry.ts`(per-tool 美化视图注册表)不动,ToolCallView 的展开详情渲染能力由批壳调用行与孤儿视图直接消费 `getToolView`。旧 `AgentMessageThread.tsx` 独立分发路径删除(TaskRoundsPanel 合成记录改用上述共享视图组件)。

---

## 9. 桌面版(every-agent-desktop)

Electron 将 web + hub + worker **一体打包**为 Windows x64 便携(portable)与安装包(NSIS):

- **进程模型**:主进程 spawn 本地 hub 与 worker 两个 Spring Boot 子进程(`javaw.exe`,jlink 精简 JRE 随包);前端经本地静态服务加载(127.0.0.1 随机端口,保证 localhost 安全上下文),preload 以 contextBridge 注入开箱即用连接配置。**hub 始终跟随 desktop 启停**(desktop 独占管理,退出时一并停止);**worker 支持外部进程复用**——启动前调 `GET /admin/identify`(认证探测,携带 `X-Admin-Key` = workerApiKey)判断 worker(:6102)是否已在运行,已在运行则跳过启动直接复用;端口被别的程序占用则报错。
- **独立 worker 启动**:`resources/start-backend.bat` 可脱离 Desktop GUI 独立启动 worker(hub 仍由 desktop 管理,不在此启动);适配 Windows 任务计划程序"系统启动时"触发器(Session 0 无 GUI 场景);worker 启动后自动重试连接 hub,desktop 后续打开时自动检测到已有 worker,不重复启动。
- **配置注入**:生成 hub/worker yaml 经 `--spring.config.additional-location` 覆盖 jar 内默认(整表覆盖 `worker.hubs`,避免误连远端);数据目录复用 `EVERYAGENT_HOME`(缺省 `~/.everyagent`),与命令行/docker 共用同一批任务/工作区/模型。
- **运行时配置**:每次启动读 `<EVERYAGENT_HOME>/desktop-config.json`(hubKey/workerApiKey/workerId/端口),首次生成;日志统一落 `<EVERYAGENT_HOME>/logs/`。
- **管理端点(仅 worker)**:worker 提供 `GET /admin/identify`(认证后返回 worker 身份)与 `POST /admin/shutdown`(认证后触发 Spring 优雅关闭);认证用 `X-Admin-Key` 请求头(与 hubs[0].apiKey 明文比对);仅监听 127.0.0.1,POST + 自定义头防 CSRF。hub 无 admin 端点(hub 可能公网部署,暴露 shutdown 接口会被持有 hubKey 的人关掉)。
- **生命周期**:单实例锁、占位页/错误页(含日志目录)、托盘提供「退出桌面」(停 hub,worker 保留运行,下次启动自动复用)与「全部退出」(停 hub + 对所有 worker 发 `POST /admin/shutdown` 优雅关闭);`before-quit` 按 `quitScope` 决定停 hub 或停全部。
- **窗口不可见不后台化**:主窗口 `backgroundThrottling: false` + 启动开关 `--disable-backgrounding-occluded-windows`——窗口被遮挡/最小化时 Chromium 默认会挂起渲染进程、杀掉 WebSocket,导致每次回到前台必断连重连、弹「正在重新连接」模态框;连接生死唯一由前端应用层心跳判定(§5.1),渲染进程须持续运行(心跳与任务流推送不中断,同时 §7.13 的 visibility 降载在桌面端不触发——本地回环,无降载需求)。
- **desktop 不感知任何具体插件**:主进程只编排 hub/worker 进程(端口、健康检查、启停),不携带任何插件领域行为——WSL 发行版探测/自动导入等沙箱可用性保证一律由对应插件在 worker 侧惰性完成(§7.10)。曾有的启动期 WSL preflight(utilityProcess fork `wsl-check-entry`)硬编码了 wsl 插件领域知识、与插件启用开关脱节,已于 2026-10 移除。
- **构建流水线**:`build-backend.mjs`(mvn 打包 worker/hub)、`build:plugins`(跨模块调 `scripts/build-plugins.py` 重建全部内置插件:先 every-agent-web esbuild bundle、再逐插件 `mvn clean package`)、`copy:plugins`(`scripts/copy-plugins.mjs` 把插件产物 staging 到 `every-agent-desktop/resources/every-agent-plugins/`)、`build-web.mjs`(前端 dist)、`build-jre.ps1`(jlink)、`build:plugin-runtime`(插件 runtime 附属文件并入 `runtime/`);electron-builder `extraResources` 把 `runtime/` 与 staging 的 `every-agent-plugins/` 等打进安装包。
- **内置插件进安装包**:worker 打包态 cwd = `process.resourcesPath`,未传 `--worker.builtin-plugins-dir` 时 `BuiltInPluginScanner` 默认扫 `<resourcesPath>/every-agent-plugins/`;故 `copy-plugins.mjs` 按 Scanner 期待结构 staging(Java 插件:`<id>/plugin.json` + `target/classes/plugin.json` + `target/*.jar` 非 sources/javadoc;web 产物(java+web 与纯 web 插件均适用):`<id>/web/` 下构建产物——前端经 `plugin.webSource` RPC 从插件目录读 `web/index.js`/`index.css`,jar 内不含 web 产物;插件根 `README.md` 原文件名一并 staging——扩展详情页 README 区经 `plugin.webSource("readme.md")` 读取,worker 侧同目录大小写不敏感回退,缺 README 的插件跳过不报错),extraResources(from: resources/every-agent-plugins → to: every-agent-plugins)原样搬运。staging **每次整体清空重建**,`enabled=false` 插件(出厂为 sandbox-windows-mic / sandbox-wsl-ubuntu / secret-redaction)不复制也不残留——禁用插件的旧 jar 绝不进安装包(sandbox-windows-codex 默认启用、进包);插件根 **`bin/` 目录整体 staging**(如 `ripgrep-tool/bin/rg.exe`——`RgResolver` 第一档就看这里,历史上该脚本只搬清单/jar/web/README 而漏掉 `bin/`,导致安装包里插件自带 rg 静默缺席、只能靠程序根 `runtime/bin` 回退兜住,详见 §7.17「程序附属文件」);任一启用插件产物缺失(jar/plugin.json/bundle)则 fail-fast 退出,dist 中止。`copy-plugins.mjs` 支持 `--only a,b` 供本地小范围验证(dist 链不传,始终全量)。全新环境首次 dist 前需对根 reactor 跑过一次 `mvn install`(插件 pom 的 parent/contract 构件须在本地 .m2;`build-backend.mjs` 只 package 不 install)。

---

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
├── every-agent-plugins/           # 内置插件:model-rate-limit(限流) / token-estimator(token 估算与校准) / task-queue(队列) / subagent / git / ai-review / empty-response-retry(空响应重试) / transient-error-retry(瞬时错误重试) / context-compression(上下文压缩) / ...
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
| D29 | **统一执行上下文 ExecContext**:黑盒 properties 四件套(taskEntry/taskId/workspaceRoot/configId)显式类型化为 plugin-api 接口槽位(仅主体必然具备的核心属性与端口,含 agents 活动实体注册表=主+各插件派生 agent;configId 无独立槽);三预绑定端口 `emitter()/agentFactory()/interaction()`(静态代理)经 ctx 下传;`TaskInfo` 退役;授权请求收编为 `AuthorizationRequest(ExecContext,agentId,grantKey,prompt)`;fileChanges 等插件功能槽位留 TaskRuntime;subagent 作为执行域能力插件全面中性化(零服务依赖/生命周期壳核分离;**台账后续收编 worker core `AgentLedger`——原 `SubAgentLedger` IO 自持已退役,`AgentBuilder.build()` 自动注册+发 `agent.started`,见 §7.20.1**);task 核心去插件概念(截断不删插件数据文件);审议 agent per-task 固定 id 复用会话;各横切 Context 接口(ToolContext/AdvisorContext/FileReferenceContext/ToolExecutionContext/TaskLifecycleContext)收编为 `extends ExecContext`(重复字段与 execution() 槽位删除,消费侧直读槽位、主体 ID 一律 subjectId(),§7.20.5) | 授权链与全部横切层(advisor/工具/子 agent/审议)域中性,subagent 无 task 只有 workflow 亦可复用;未来工作流实现 ExecContext 即零改动复用;20+ 处强转消失;详见 docs/design-exec-context.md 与 §7.20 |
| D30 | **任务事件与任务流频道加 worker 段**（`u.<K>.worker.<id>.tasks` / `u.<K>.worker.<id>.task.<id>.stream`）+ 任务摘要 payload 带 `workerId`；**切换启用 worker 时 worker 级客户端状态整体失效并关闭全部 `task:*` 标签** | apiKey 即身份 ⇒ 同一 apiKey 下多台 worker 共用一个命名空间。此前 tasks/流频道只到 ownerKey、归属靠前端「按帧来源推断」，两个后果：① 一台的任务混进另一台的列表，点开按被启用那台的 RPC 寻址必报「任务不存在」；② 推断结果一旦落库就永不纠正，切换 worker 后详情按已断开的连接取数、失败还全静默（空白页，只有刷新才好）。归属改成 wire 事实 + 作用域化失效，见 §4.3/§5.2/§8.2/§14.12；协议 v3→4 |
| D31 | **出网投影单点(EgressProjector)**:客户端可见事件/轮次必须经单点投影器(worker `ship` 包,域中性,与 `DataPusher` 同域)转换;4 出网口(stream 推送 / `task.poll` / `task.roundTail` / `task.rounds`)共用同一投影器与 filter 链;pre-wire 出网过滤链可丢弃(返回 null)/改写 payload,**不改 `ext`**;`wireEvent` 不再是公共 API | 把「不可展示」当**出网(egress)问题**而非产生问题:事件照旧落盘(事实源与 seq 空间不变),出网收敛到单点即实现层无法绕过,从而落地「AI 审议过程不出网、但落盘保留」;「已落盘但不出网」的 seq 洞合法(与「瞬态占号不落盘」并列);详见 §5.3/§14.13 |
| D32 | **`task.agents` 收回 task 域 + subagent 插件去 web**:方法常量入 `RpcMethods.TASK_AGENTS`、`TaskManager` 注册实现,语义=任务下**全部** agent(主 + 各来源派生,含 `creator=ai-review`),删除按 `creator=subagent` 过滤;subagent 插件不再提供该 RPC 与前端的 `ui.composer_above_panel` 面板,agent 胶囊列表归 web 核心 `TaskChat` + `AgentListPanel` 渲染;`list_agents`/`wait_agents`/`run_agent`/`stop_agent` 工具仍属 subagent 插件 | `task.*` 前缀本就属 task 域;「任务下全部 agent」是任务概念,原按 `creator=subagent` 过滤会漏掉审议 agent 的归属;前端列表收口到核心、插件去 web 减少迁移中间态;详见 §5.5/§7.14/§7.20.1 |

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
   - **出网投影层(EgressProjector)**:task 层 wire 映射后、推送/拉取前,把落盘态 `EventRecord` 投影为客户端可见形态——pre-wire 出网过滤链可丢弃(返回 null)/改写 payload,**不改 `ext`**;4 个出网口(stream 推送 / `task.poll` / `task.roundTail` / `task.rounds`)共用同一投影器(§5.3/§14.13)。
   - **推送层(WebSocketEmitter)**:背压 + 定向推送到前端 websocket,不感知事件语义。
   - **前端(最外层)**:按 wire 事件名 + payload 字段映射为 trace/消息/状态等展示组件。
   - **改造纪律**:后续把 `AgentEventChannel` 的 30 个具体方法逐步迁移到 `EventEmitter.emit(EmitEvent)` 时,每迁一个方法:插件/底层只发强类型 `EmitEvent` 载荷,task 层用 `instanceof` 判断类型做映射,推送层和前端不变。以后工作流层实现自己的映射(task.trace → wf.trace,自己的包装格式),自己的推送管道,前端按工作流 wire 格式解析。**插件永远不感知 task 层语义(wire 事件名、traceId、seq 等)。**
1. **编码、时间与 ID**:帧为 UTF-8 JSON;ts 一律 epoch 毫秒(UTC);短 ID 规则 `{前缀}_{3位盐}{base36 序号}`,全局唯一从不复用;ownerKey = sha256(apiKey) 64 位小写 hex。
2. **频道与信封(hub 红线)**:频道名字符集 `[a-z0-9._-]` 长度 ≤160,必须以 `u.<ownerKey>.` 开头;hub 只解析 `type`/`channel`(及 hello 握手字段),`event`/`seq`/`payload`/`ext` 原样转发;不存在角色×频道权限矩阵;seq 只属于任务流事件空间,由 task.poll/stream 携带;error 分级(断开 vs 拒单帧);连接抢占(worker 同 clientId 新连关旧连)。
3. **RPC 生命周期**:reqId 连接内唯一,ok/err 已出则后续同 reqId 帧忽略;未知 method → UNKNOWN_METHOD;参数不合法 → BAD_PARAMS;超时是纯客户端语义(SDK 默认 30s),要中断须显式 rpc.cancel;task.run 新建支持 idempotencyKey(10 分钟窗口去重);task.delete 是任务唯一删除路径,无任何自动清理。
4. **错误码两个命名空间,勿混用**:hub `error` = NOT_AUTHENTICATED/VERSION_MISMATCH(断开)、ACL_DENIED/FRAME_TOO_LARGE/RATE_LIMITED(单帧拒绝);`rpc.err` = UNKNOWN_METHOD/BAD_PARAMS/NOT_FOUND/SANDBOX_DENIED/BUSY/INTERNAL/AUTH_REQUIRED。
5. **并发与上限**:maxConcurrentTasks(20)超限 task.run 新建 → BUSY(不排队);maxConcurrentSubs 超限 run_agent 返回错误文本由模型自决;maxEventsPerTask(50 万)超限抛 LogOverflow(磁盘 jsonl 全量不受影响);续跑放行不查并发上限。队列插件启用时超限任务排队等待（QueueAdmissionNode order=40, Semaphore fair）而非 BUSY 拒绝；无队列插件时保持 ERR_BUSY 硬拒绝。
6. **沙箱(插件化)**:SandboxBackend SPI = 效果 2(`grant`/`revoke`,路径级、无语义、幂等可重放)+ 查询 2(`toSandbox`/`toHost`,纯函数、默认恒等)+ `id`;**效果与时机分离——何时授权/回收由上层决定**(PermissionGate/GrantRegistry 是唯一翻译点,沙箱不感知任务/工作区、不订阅领域事件);授权下发遵循**不放大原则**(无法在请求粒度落地则不下发,绝不放宽到父目录);回收是路径级且按主体生命周期收敛,跨主体共享的根在仍有主体期望时保留;沙箱插件提供自己的 CommandExecutor 和 ToolProvider;PermissionGate 不暴露到 plugin-api(核心内部保留);路径必须先规范化(realpath)再校验 workspace 根前缀,拒绝 `..`、绝对路径逃逸与符号链接逃逸;授权护的是「工作区外」,不是删除动作本身;不得绕过 PermissionGate 直接放行越界 IO;fs.\*Raw 系列(`fs.readRaw`/`fs.writeRaw`/`fs.statRaw`)虽是机器绝对路径(无 workspace 参数)同样不得绕过——必须校验合法根集合(在册工作区根 ∪ 外部授权根 ∪ 技能目录 ∪ 在途授权根 ∪ 会话用户放行目录,§5.5),越界返回 `SANDBOX_DENIED` 交前端确认放行(`fs.grantRawRoot`,会话级内存态),放行只能来自该显式确认 RPC;各后端的具体隔离机制见各自插件文档;git 凭证只存 worker 本机加密文件,不经协议传输,注入走 env(askpass) 不经 shell 参数;
7. **生命周期**:终态任务收到 task.run{taskId} = 冷启动一次普通运行;worker 优雅停机(SIGTERM)受影响任务标 failed 再关连接;6102 仅绑定 127.0.0.1;worker 每条 hub 连接建立即 sub 该命名空间 cmd + input 两个频道,从不订阅 per-task 频道。
8. **复用 Spring AI,禁止重复造轮子**:agent 执行必须走 ChatClient + Advisor 生态,不得手搓 agent 循环、工具循环、响应聚合、system 拼接;执行链只能是很薄一层;新增 agent 能力优先做成 Advisor;一个 Advisor 只负责一个功能;事件发射等需挂钩工具循环的增强通过继承 ToolCallingAdvisor 并重写受保护 hook 实现;主/子 agent 共用同一运行入口与 Advisor 链,仅 agentId 不同;**任何「把一条用户输入投给模型」的入口必须经核心 `UserInputMaterializer` 物化**(@ 文件引用 → 正文改写 + 附件 Media;轮次路径由 `file.reference.process`(875) 薄壳调用、「插入到当前对话」由 `DialogInsertAdvisor` 调用),不得自行把 `input()` 裸文本塞给模型——历史上插入路径绕过物化,@ 图片退化成裸路径文本,AI 只看到路径、转而去 read_file 图片失败(§7.13)。
9. **插件零 worker 依赖**:插件的 pom 中不得出现对 `every-agent-worker` 的依赖,compile/provided/runtime/test 任何 scope 一律禁止;插件测试需要任务/agent/配置等桩时,在测试源码内自建实现 plugin-api 接口的等价桩类,不得把 worker 具体实现类(TaskEntry/AgentEntity/WorkerProperties/SlashCommandRegistry 等)当测试脚手架;类型确实需要跨 worker 与插件共享时,先下沉到 plugin-api(§1.1,文档先行)。
10. **文档**:本文档是唯一架构事实源;根目录 AGENTS.md 只写核心约束(每会话加载,保持精简),细节一律进 docs/。
11. **执行上下文管道(ExecContext,与 §14.0 事件管道同构;展开说明见 §7.20)**：执行数据沿 `task 层 → agent 层 → 工具执行链 → 授权链` 逐层传递,每层只消费自己层级的槽位,下层不知道上层是谁(task 还是未来 workflow):
    - **槽位判据**:ExecContext 槽位 = 任何执行主体都必然具备的核心属性与端口(subjectId/workspaceRoot/workspaceId/snapshot/emitter/agentFactory/interaction/metadata/dataDir/terminal/agents 活动实体注册表;configId 不设独立槽,经 snapshot().configId() 取);**插件功能与主体特有槽位不进核心接口**——file-change 插件的 collector 连 `TaskRuntime` 都不进(留 provider 任务级共享 collector——主/子 agent 同实例,§7.15.2——+ `RoundClosedListener` 落盘 + 插件自注册 RPC 读),metadata 承载随 meta.json 落盘的持久数据(如 attachments;**任务级开关**(AI 审议/无人值守/禁用网络)不走 metadata,其真相源是 `slash-tokens.json`,经 `WorkerServices.slashTokens()` 查询,不混入运行时瞬态数据);agents() 收纳主体上下文内全部 agent(主 agent + 各插件派生:子 agent、审议 agent),由 `AgentBuilder.build()` 自动注册(只 put,**不发事件**),插件不再手动 put/emit;`AgentContext.creator()` 槽位(task/subagent/ai-review 来源标记,**顶级字段**,非 ExecContext 槽位)随 `agent.started` 持久化进台账顶级 creator,消费方按 creator 决定展示范围;`agent.*` 生命周期事件的发射单点是 `AgentEntity`(per-run 状态机),触发源 = `AgentStatusAdvisor` 流生命周期 + 交互层 ask 生命周期(§7.20.1);无子 agent 是正常形态。
    - **task 层构造并预绑定**:`TaskEntry implements TaskRuntime extends ExecContext`(subjectId=taskId);三预绑定端口同范式(静态代理,worker 内部实现,可链式套娃)——`emitter()`(主体事件口)、`agentFactory()`(主体 agent 装配,`create(agentId)` 单参)、`interaction()`(主体交互口,ask 的 context map 自动填 subjectId)——**上层预绑定能力往下传,不传裸工厂/裸服务、不传任务域对象**。
    - **agent 层/工具链唯一取数口**:`AgentContext.execution()`;`ToolContext`/`ToolExecutionContext`/`AdvisorContext`/`FileReferenceContext`/`TaskLifecycleContext` 一律 `extends ExecContext`(重复字段与 `execution()` 槽位已删,消费侧直读槽位、横切层取主体 ID 一律 `subjectId()`,§7.20.5;`InterceptingToolCallingManager` per-run 构造参数直持 ExecContext,不依赖 ThreadLocal);`properties` 黑盒 map 与 `get("taskEntry")` 强转**禁止再现**。
    - **advisor 只取槽位**:`snapshot()`(模型配置,configId 经 snapshot().configId())、`subjectId()`(审计)、`emitter()`(事件)、`terminal()`(leak-guard)——不 import 任务域类型;**agent.生命周期事件发射单点(§7.20.1)**:`agent.started`/`agent.status`/`agent.done`/agent 级 `error` 一律由 `AgentEntity`(per-run 状态机 + CAS)发射,触发源是 `AgentStatusAdvisor` 的流生命周期信号与交互层的 ask 生命周期;task 层(StatusNode/TaskLifecycleContext.agentStatus)与插件(SubAgentManager/AiAuthReviewer)不得再手搓这四个事件的 EmitEvent——插件唯一允许的调用是兜底 `Agent.claimTerminal(...)`,CAS 保证与 advisor 终态互斥;**subagent 是执行域能力插件(非任务域),全面中性化**:SubAgentManager 零服务依赖(方法收 ExecContext、监视器内部化、`agents()` 槽位替代 task.agents)、创建子 agent 时设 `creator("subagent")` 交由 `AgentBuilder.build()` 自动注册(插件不再手动 put)、agent.* 生命周期事件由 advisor 链统一发射(插件不再 emit、只在运行体从未启动的兜底路径调 `claimTerminal`)、台账 agents.json 由 worker core `AgentLedger` 收编(`SubAgentLedger` 退役;事件投影+原子读写+冷启动恢复,§7.20.1)、生命周期节点壳留 task 面/逻辑取 ExecContext 槽位、`task.agents` RPC **已收回 task 域**(常量 `RpcMethods.TASK_AGENTS`、由 `TaskManager` 注册实现,语义=任务下全部 agent、无 creator 过滤,§5.5/§7.20.1);**task 核心不依赖插件**:编辑重发截断只动 *.jsonl/rounds.jsonl 不删插件数据文件(agents.json/file-changes 残留被接受,事件通知自清为开放项),`task.fileChanges` 方法名常量住插件侧;AI 审议 agent 以 per-task 固定 agentId(creator=ai-review)注册进 agents() 跨请求复用会话(既往授权决策留在审议员上下文),每轮审议同样走 advisor 链的 per-run 出生/终态事件;任务域插件(file-change/edit-resend/git)经 `TaskService`/`taskRuntime()` 取 `TaskRuntime` 是合法本职依赖。
    - **授权请求域中性**:`AuthorizationRequest(ExecContext, agentId, grantKey, prompt)`;GrantRegistry 按 `subjectId()` 分区、`dataDir()` 落盘;`InteractionService` ask 的 context map `"taskId"` 键保留(值=subjectId,前端兼容)。
    - **禁止**:横切层 import `TaskEntry/TaskRuntime/TaskInfo`;绕过 `ctx.agentFactory()` 手工组装四件套 map;绕过 `ctx.interaction()` 手动填 taskId context;worker 侧新增 properties 透传通道。工作流层实现 `WorkflowRuntime implements ExecContext` 后,同一条授权链、同一批 advisor、同一工具链零改动复用。
12. **worker 归属是 wire 事实,禁止客户端推断(与 §4.3/§5.2/§8.2 同口径)**:
    - **频道**:任务生命周期事件与任务流实时增量必须住在带 worker 段的频道里 —— `u.<K>.worker.<wid>.tasks`、`u.<K>.worker.<wid>.task.<taskId>.stream`。同一 apiKey(同 `K`)下多台 worker 各归各的频道;**禁止**把它们发回 ownerKey 级共享频道(那等于把 A 的任务混进 B 的前端列表,点开必报「任务不存在」)。
    - **payload**:`task.created/updated/deleted` 与 `tasks.list` 的每条任务摘要必须带 `workerId`(契约 `events.schema.json:tasksEvent` 早已把它列为必填)。
    - **客户端**:`workerIdOfFrame()` 只认频道名的 worker 段;**禁止**「按 ownerKey 前缀匹配首个连接」这类猜测回退 —— 判不出归属就丢帧,不许蒙一台。任务条目的 `workerId` 一律以**本次数据来源**为权威,不得让旧镜像压过新来源。
    - **worker**:收到 `subscriber.join` 时若频道 worker 段非本机、或该 taskId 本机不可见(内存/磁盘索引/任务目录均无),**一律不建定向推送器**。否则该推送器永远等不到前端 ack(ack 只发到被点名那台的 input 频道),背压窗口 128 永不释放 → 白占虚拟线程与出站队列。
    - **hub**:定向投递 join 只允许「按频道名解析出 worker 段 → 查现成的 `findWorker(ownerKey, workerId)` 索引」,仍属路由;不得新增任何订阅簿或状态(§6.1/§14.2 零状态红线不破)。
    - **客户端状态生命周期**:worker 数据连接是任务列表/详情/RPC/模型配置/插件的**作用域**;连接集合一变(`hubSession.onWorkerConnectionsChanged`),所有按 worker 缓存的状态必须由各自持有方失效,且全部 `task:*` 标签关闭 —— 禁止只换连接不清状态(那会留下指向已断开连接的陈旧句柄,表现为空白且不自愈)。
13. **出网投影单点(EgressProjector,与 §14.0 事件管道同构)**:客户端可见的事件与轮次**必须**经单点投影器 `EgressProjector`(worker `ship` 包,域中性,与 `DataPusher` 同域)转换后才出网,**不得绕过**。出网口共 **4 个**:stream 推送(`DataPusher.push`)、`task.poll`、`task.roundTail`、`task.rounds`,全部复用同一投影器与同一 filter 链;`wireEvent` 实现内聚进投影器、不再是公共 API(实现层无第二条出网路径)。
    - **过滤链**:投影器内跑可插拔出网过滤链(`EventEgressFilter`/`RoundEgressFilter`,按 `order` 排序);filter 作用于 **pre-wire 的 `EventRecord`**,可丢弃(返回 null,**事件仍已落盘**)或改写 payload;**不得改动 `ext`**(ext 是落盘态镜像),加密等改写须在 `payload` 内自成信封;filter 域中性,只带 `subjectId`(task→taskId,workflow→workflowId)。插件据此可实现「审议过程不出网」等出网过滤——会话内容事件被丢弃,而状态与用量保持可见。
    - **seq 洞合法**:「**已落盘但不出网**」造成的客户端所见 seq 洞合法,与既有「瞬态占号不落盘」并列(§5.4);事件落盘(事实源)与出网(可投影视图)解耦,落盘永不因出网过滤而变更。`task.poll` 另以字符串字段 `nextSeq`(未过滤口径推进游标)支持客户端分页(§7.13)。
    - **task.agents 归属**:`task.agents` RPC 属 task 域(常量 `RpcMethods.TASK_AGENTS`,`TaskManager` 注册实现),语义 = 任务下**全部** agent,**无 creator 过滤**;subagent 插件不再提供该 RPC 与 web 前端功能,`list_agents`/`wait_agents`/`run_agent`/`stop_agent` 工具仍属 subagent 插件(§7.14/§7.20.1)。
    - **授权决议解析容错**:自动审议路径的输出解析失败 → 重试一次;仍失败 → ESCALATE(交下一节点/人工),不再 DENY;fail-closed(DENY)仅保留给超时/异常路径(§7.9)。