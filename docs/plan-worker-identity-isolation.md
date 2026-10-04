# 多 worker 身份隔离与切换失效

## 目标

修掉 bug1(切换 worker 后点开任务空白、F5 才好)与 bug2(同 apiKey 两台 worker 任务串到启用那台、点开报「任务不存在」),并按已确认口径落地:**tasks/stream 频道加 worker 段做物理隔离**、**切 worker 时关闭所有任务标签页并清理 worker 级缓存**、第 1–4 步一次交付。

---

## 根因(证据均为 文件:行)

两个 bug 共享一个结构性缺陷:**「这条数据属于哪台 worker」在前端是推断出来的,一旦推断出来永不纠正,且失败路径全静默。**

### bug1:切 worker → 点任务空白;F5 才好

1. 切换只断连接,不清上层状态:`web/src/hub/session.ts:287-311 setWorkerEnabled` → `:605-625 disconnectOtherWorkers`(close + 从 `workerClients` 摘除)→ `connectWorker(新)`。`session.ts:363` 注释写「切 worker 时统一清理旧 worker 缓存」,实际只清 `askStore.clearForTask`/`sessionMeta`/`terminals` —— **taskStore 与 TaskStreamManager 一个都没清**。
2. 任务镜像的 worker 归属粘在旧 worker:`web/src/task/taskStore.ts:155` `workerId: summary.workerId ?? existing?.workerId ?? ''`,而 worker 侧 `TaskDtos.TaskSummary`(grep `workerId` 零命中)**不下发 workerId** ⇒ 永远落到 `existing.workerId`;`refresh()` 的 `:310` 只在空串时回填,`:321` 的 `tasks.clear()` 又发生在读完旧镜像**之后** ⇒ 旧 workerId 原样写回。
3. 列表渲染不读 workerId(`query/taskQueryService.ts:78-85`),所以「列表能刷新」与「详情打不开」互不矛盾 —— 这正是现象的成因。
4. 详情三路径静默:`taskStream.ts:570-576 new ManagedStream(taskId, taskStore.get(taskId)?.workerId ?? '')` 冻结旧 id ⇒ `:501` 的 `if (!this.workerId)` 定向补齐分支被跳过(非空但错);`:151 ensureView()` 取不到 client 抛异常被 `:510` `catch` 成 `console.warn`;**`:365-366 loadRoundsIntoFolder()` 的 `if (!client) return` 不写 `roundsError`、不 notify(最致命)**;`:448-451 loadAgentsIntoFolder()` 把 `agentsSeeded=true` 写在取 client **之前** ⇒ 本周期永不重试。
5. UI 因此停在永久 spinner:`components/task/TaskRoundsPanel.tsx:72-73` `!roundsResult && !roundsError` ⇒ 「正在加载任务线程…」,`:262` 的重试按钮要求 `roundsError` 非空 ⇒ 永不出现。
6. 永不自愈:`TaskStreamManager.get()`(`:569-577`)只在缓存缺失时 open;标签 id 恒为 `task:${taskId}`(`workspaceShellState.ts:53`,不含 workerId)且面板常驻 `display:none` 不卸载 ⇒ 再点不重挂不重开;`:624-640 wireReconnect` 的 `stream.open()` 仍带旧 workerId。
7. F5 能修好的唯一原因:`tasks` 与 `streams` 双双归零 ⇒ `:310` 从当前已连 worker 回填正确 workerId ⇒ `:501` 走「未知归属定向补齐」⇒ 正常。
8. 回归引入点:`git show f77673f`「切 worker 改单连」只改 `hub/session.ts`(+33/-4)与文档,未给 taskStore/taskStream 加任何 per-worker 失效。

### bug2:同 apiKey 两台 worker 串味

1. 频道只到 ownerKey:`plugin-api/.../event/Channels.java:40 tasks(k)` = `u.K.tasks`;`TaskManager.java:982/1116/1247/1252`、`lifecycle/StatusNode.java:46/65`、`TaskWiresNode.java:39`、`ResponseAckNode.java:44` 全部 `fanout(k -> Channels.tasks(k), …)` ⇒ 同 K 两台 worker 写同一条频道。
2. payload 也不带归属:`contract/resources/schema/events.schema.json:128` 的 `tasksEvent.payload.required` **已声明 `workerId`**,实现没带 ⇒ 属「实现与文档冲突」(先改文档再改代码)。
3. hub 不区分:`hub/ws/HubConnection.java:307` 只校 `u.<K>.` 前缀;§4.3 明文「同命名空间内互信」。
4. 前端只能猜且猜错:`session.ts:377-401 workerIdOfFrame()` 对无 worker 段的 `u.K.tasks` 落到 `:397`「按 K 前缀匹配**首个**连接」⇒ A 的任务归给 B。
5. 点开报「任务不存在」:RPC 走 `u.K.worker.B.cmd`(RPC 频道有 worker 段,不串),B 的 `TaskManager.java:346 taskPoll`/`:161 taskRun` **只校验 taskId 存在、不做归属校验** ⇒ `NotFoundException`。
6. 同源第二处(更隐蔽):`Channels.taskStream(k, taskId)` = `u.K.task.<tid>.stream` 也无 worker 段 ⇒ `hub/reg/ChannelRegistry.java:36/:55` 的 `subscriber.join/leave` 目标是 `ConnectionRegistry.java:57 onlineWorkers(ownerKey)` = **该 K 下全部在线 worker**;`worker/ship/DataPusherManager.java:75-95` 不判归属照单建 DataPusher ⇒ 非寻址那台的推送器收不到前端 ack(ack 只发 `u.K.worker.B.input`)⇒ `WebSocketEmitter.java:45-57` 的 credit 窗口(128)永不释放 ⇒ 该推送器永久阻塞,白占连接出站队列与虚拟线程。
7. 磁盘仍是 ownerKey 级共享(`TaskManager.java:77` 类注释、D20):同 key 双 worker 共享同一份 `data/tasks`。隔离只发生在**频道层**,本方案不做目录迁移(见备注)。

### 写方案时新核出的 3 个隐藏点(必须一并处理)

- **H1 事件出口拿不到 workerId,而插件在用**:`plugin-api/.../event/StreamEmitter.java:27 fanout(Function<String,String> channelNamer, …)` 的 namer **只接收 ownerKey**。而插件侧 4 处直接构造 tasks/stream 频道:`task-queue/TaskQueue.java:100`、`task-input-queue/QueueBroadcast.java:36`、`QueueRpcHandler.java:169`、`task-edit-resend/EditTruncateProcessor.java:151`。插件红线(§14.9 插件零 worker 依赖)决定**不能**让插件去查 workerId ⇒ 频道加 worker 段必须同时改 `StreamEmitter` 的中立签名(见待确认 #1)。
- **H2 join 通知的唯一硬依赖(致命顺序约束)**:`hub/reg/ChannelRegistry.java:36` 的 `subscribe()` **只对 `StreamChannelParser.parse(channel) != null` 的频道发 `subscriber.join`**,投递目标是 `ConnectionRegistry.java:57 onlineWorkers(ref.ownerKey())`。因此:
  - **tasks 频道改名不影响 join**(join 从不按 tasks 频道派生,`ConnectionRegistry` 也没有按频道第 3 段解析的逻辑)——我此前担心的「改名打挂 join 投递」经核读不成立;
  - **stream 频道改名则必须与 `StreamChannelParser` 在同一次提交内原子改完**:漏改 parser ⇒ `parse` 返回 null ⇒ hub 不再发 join ⇒ worker 不建 DataPusher ⇒ **所有任务的实时推送静默消失**(比原 bug 更严重)。步骤 1 因此把两者绑成同一交付单元,并在步骤 3 把 `StreamRef` 扩出 `workerId`、让 hub 改用现成的 `ConnectionRegistry.findWorker(ownerKey, workerId)` 做定向投递(零新增状态、不存订阅簿,符合 §6.1/§14.2)。
  - 双保险:步骤 2 的 `DataPusherManager` 归属自检保证即使 hub 仍广播,非寻址 worker 也不会建推送器。
- **H3 上一轮我说错了一处**:`StreamChannelParser.java` 实际**只**支持 `u.<K>.task.<tid>.stream` 一种形态,没有裸 `task.<tid>.stream` 分支(此前引用有误)。且 `DataPusher` 直接复用 join 帧里的 channel 字符串推送(`DataPusher.java:71-78/254`、`WebSocketEmitter.java:51-56`)⇒ **前端订新频道名后,推送目标频道自动跟着变**,worker 侧无需再拼一次频道名。
- **H4 同机同 key 的磁盘共享(本次不改,只写清边界)**:`TaskManager.taskOwnerKey()` 现恒返回 null,类注释与 :77 明写「任务不做 owner 隔离(类注释 D17):只校验 taskId 存在」;`data/tasks` 按 apiKey 共享、不按 worker 分目录。**注**:此前子 Agent 报告的 `taskPoll → ensurePusher(k,…)` 不校验 ownerKey 一说,经我 grep 当前 worker 源码(`subscribeSession|ensurePusher`)**零命中,不成立**,已从方案剔除。真要按命名空间隔离任务读写,等于**反转 D20** 且需要 `data/tasks` 加 ownerKey 维度 + 数据迁移,不属于这两个 bug 的范围(bug2 的现场是不同机器,磁盘本就不共享)。本次只在 §4.3/§8.2 把这个边界写清,不动语义。

---

## 步骤

- [x] **步骤 0:文档先行**(agents.md:实现与文档冲突先改文档)
  - 状态:已完成
  - agent:-
  - 依赖:无
  - 验收标准:`docs/ARCHITECTURE.md` 完成下列改写,且不再出现「`u.<K>.tasks`」旧形态:
    - §5.2 频道表:`u.<K>.worker.<id>.tasks`、`u.<K>.worker.<id>.task.<taskId>.stream`;§4.3 补「同命名空间互信不变,但任务事件归属由频道 worker 段 + payload.workerId 携带,前端禁止按 ownerKey 前缀猜测」;§5.1/§10.1/§10.2 的 wire 示例与流程图同步。
    - §5.1 协议版本 3 → **4** 并写明「不兼容 v3:老前端+新 worker 会在握手期即 VERSION_MISMATCH 显式报错,而非静默丢事件」。
    - §8.2「多 worker 聚合」:改掉「任务列表按 worker 合并展示」「归属由前端按帧来源动态标注(TaskSummary 后端不含 workerId)」两处已失效描述;新增决策口径「**单连模式:任务列表与详情恒等于当前启用 worker;切换 worker 即 worker 级客户端状态整体失效 + 关闭全部任务标签**」。
    - §7.13 补 DataPusher 归属自检;§12 决策表加一条(频道 worker 段物理隔离);§14 加红线(禁止前端猜归属 / worker 不为非自身 taskId 建定向推送器)。
  - 产出:文档 diff + 本文件更新。

- [x] **步骤 1:契约与频道命名(三层 + 前端 SDK 同步)**
  - 状态:已完成
  - agent:-
  - 依赖:步骤 0
  - 验收标准:
    - `plugin-api/.../event/Channels.java`:新增 `tasks(k, workerId)`、`taskStream(k, workerId, taskId)` 为**唯一被使用的**形态(旧 1 参/2 参签名保留但标 `@Deprecated`,仅供已编译外部插件二进制兼容;仓库内不得再有非 deprecated 调用);
    - **H1 落地(A′,非破坏式)**:`plugin-api/.../event/StreamEmitter.java` **新增**重载 `fanout(BiFunction<String,String,String> channelNamer, …)`(namer 收 ownerKey + 发布者 workerId);**保留**旧 `fanout(Function<String,String>)` 与 `Channels.tasks(String k)` 并标 `@Deprecated`(已编译的外部插件 jar 按旧描述符调用不致 `NoSuchMethodError`);`Channels` 新增 `tasks(k, workerId)`、`taskStream(k, workerId, taskId)`。核心 7 处 + 内置插件 4 处**全部**改用新签名,内置功能零降级;
    - `contract/.../frame/StreamChannelParser.java`:解析 `u.<K>.worker.<wid>.task.<tid>.stream`,`StreamRef` 增 `workerId`,taskId 提取口径不变;
    - `contract/.../frame/Frames.java:41` 与 `web/src/sdk/frames.ts:3`:`PROTOCOL_VERSION = 4`;
    - `web/src/sdk/channels.ts:100-101` 与 Java **逐字对齐**;
    - `contract/resources/schema/events.schema.json`:改 line 26(stream)、124(tasks)、143 保持;补 `EventsSchemaTest` 用例锁死新 pattern(含缺 workerId 拒绝);
    - 单测:频道字符串三端一致性用例通过。
  - 风险:采 A′ 后 **plugin-api 不再破坏外部插件二进制兼容**(旧方法与旧频道构造器保留为 `@Deprecated`);残留风险仅是「未重编译的外部插件仍发旧频道 ⇒ 其事件在新前端不可见」,属可接受的局部降级,需在 §5.6/§13 写清。

- [x] **步骤 2:worker 侧按 worker 定向发布 + 归属自检 + 补 payload.workerId**
  - 状态:已完成
  - agent:-
  - 依赖:步骤 1
  - 验收标准:
    - 11 处 tasks 发布点(`TaskManager.java:982/1116/1247/1252`、`StatusNode.java:46/65`、`TaskWiresNode.java:39`、`ResponseAckNode.java:44`、`HubPool.java:151` + 插件 4 处)全部走 `Channels.tasks(<K>, <本 worker workerId>)`;`pubTaskStream`(`HubPool.java:166-167`)与 `EditTruncateProcessor.java:151` 同理;
    - `TaskDtos.TaskSummary` 与 `runtimeSummaryJson()`/meta.summary/`task.deleted` payload 补 `workerId`(兑现 schema 已声明字段;新增字段旧接收方 must-ignore);
    - `ship/DataPusherManager.java:75-95`:join/leave 按频道 `workerId` 与自身 `props.getWorkerId()` 比对,不匹配直接丢弃(不建/不销推送器)—— 与步骤 3 的 hub 定向形成双保险;
    - `MultiHubE2eTest.java:216/226` 的「不做 owner 隔离 ⇒ B 侧也收到 A 的任务通知」断言按新语义改写(跨**命名空间**仍广播,同命名空间**不同 worker** 不再互收);
    - `mvn -q test` 全绿。

- [x] **步骤 3:hub 精确投递 join/leave(零状态前提内)**
  - 状态:已完成
  - agent:-
  - 依赖:步骤 1
  - 验收标准:`hub/reg/ChannelRegistry.java:127-131 notifyWorkers(...)`,当 `StreamRef.workerId != null` 时改用现成的 `ConnectionRegistry.findWorker(ownerKey, workerId)` **只投那一台**;workerId 缺失(裸形态/未知)退化为现有 `onlineWorkers(ref.ownerKey())` 广播。不新增任何 Map、不存订阅簿。hub 集成测试:`u.K.worker.A.task.<t>.stream` 的 sub 只让 A 收到 join。

- [x] **步骤 4:前端归属权威化(修 bug2 可见症状)**
  - 状态:已完成
  - agent:-
  - 依赖:步骤 1、2
  - 验收标准:
    - `session.ts:377-401 workerIdOfFrame()`:优先频道 worker 段精确匹配 → 其次 `payload.workerId`;**删除**「按 K 前缀匹配首个连接」猜测分支(判不出归属即丢帧,不再蒙一台);
    - `taskStore.ts:231` 帧入口:`frame.channel` 必须等于 `channels.tasks(client.k, workerId)`;`:155/:310/:371/:398/:416` 的 `entry.workerId` 改为**以本次数据来源为权威**(不再让旧镜像压过新来源);
    - `task-packet-view.ts:113 streamCh` 与 `taskStore.subscribeTaskChannels()`(`:263-267`)改用带 worker 段的频道;
    - 端到端:禁用中的 worker 有数据推送时,启用 worker 的任务列表**不出现**其条目。

- [x] **步骤 5:切换 worker 的彻底失效收口(修 bug1 主体)+ 关闭任务标签**
  - 状态:已完成
  - agent:-
  - 依赖:步骤 4
  - 验收标准:
    - `hub/session.ts`:新增单一职责通知点 `onWorkerConnectionsChanged(fn)`(携带变更后的已连 workerId 集合),在 `connectWorker` 成功、`closeWorker`、`disconnectOtherWorkers`、`removeWorker`、`teardown` 处触发;hubSession 只通知、不含业务;
    - `taskStore` 订阅:剔除已断开 worker 的条目与 `pageStates` 游标,并对新连接全量校准;
    - `TaskStreamManager` 订阅:`invalidateWorker(workerId)` 关闭并重开受影响流;`ManagedStream.open()` 每次重解析归属(不再用陈旧 workerId 静默跳过);
    - `Layout.tsx`:worker 连接集合变化时关闭**全部 `task:*` 标签页**(走 `closeWorkspaceTabNow`,绕过 `taskChatTabType.onClose` 守卫;项目/文件/终端/设置标签保留),同时清 `taskChatDraft` 的 preset workerId;
    - `modelConfigs`(按 worker 清 `byWorker` 中已消失项)、`pluginLoader`(`createPluginSdk:189-192`/`loadPluginModule:326-356` 的 workerId **闭包捕获改为调用期解析** + 清 `loadedPlugins` 中已消失 worker 条目)、`askStore`(清已断开 worker 的 pending ask)各自订阅同一通知点做事;
    - 人工验收:切 worker 后**不刷新页面**,直接点新 worker 的任务 → 能拉到数据(与 F5 后一致)。

- [x] **步骤 6:消灭静默失败(修 bug1 的「不报错」)**
  - 状态:已完成
  - agent:-
  - 依赖:步骤 4
  - 验收标准:
    - `taskStream.ts:364-366`:取不到 client 时**写 `roundsError`**(「任务所属 worker <id> 未连接」)+ `notify()`,`TaskRoundsPanel.tsx:262` 错误条与「重试 RPC」出现;
    - `:447-451`:`agentsSeeded = true` 移到取到 client 且成功之后;
    - `sdk/hub-client.ts:299` RPC 超时在任务详情路径生效并可见化(僵尸 `reconnecting` 连接不再被当可用:`session.ts:238-241 workerClient()` 语义收窄);
    - 人工验收:kill 掉当前启用 worker 进程(不切开关)后打开其任务 → 显示错误态而非永久空白。

- [~] **步骤 7:回归验证 + 分次提交**
  - 状态:进行中(自动化验证与基线差分已完成;端到端人工矩阵待你执行)
  - agent:-
  - 依赖:步骤 0-6
  - 验收标准:`mvn -q test` 全绿;`cd every-agent-web && npm run typecheck` 通过;人工矩阵两台同 apiKey worker + 一个 hub 全过:①只启用 B、A 跑任务 → A 不进列表、无幽灵 DataPusher;②切到 A → 标签全关、列表只剩 A 任务、**直接点开能拉数据**;③切回 B 同上;④全程无需 F5,F5 前后行为一致。提交按 4 次:`fix:` 频道加 worker 段(含 schema/协议版本/目录校验)、`fix:` 前端归属权威化、`fix:` 切换 worker 全量失效+关标签、`fix:` 详情失败不静默。

---

## 决策记录(用户未答复超时,以下为我按最低风险自主采纳,可随时推翻)

1. **H1 解法 = A′(A 的彻底性 + C 的兼容性,非破坏式)**:
   - `StreamEmitter` **新增**重载 `fanout(BiFunction<String ownerKey, String workerId, String> channelNamer, …)`,worker 侧 `HubPool` 用 `conn.k()` + `props.getWorkerId()` 实现;
   - **保留**旧 `fanout(Function<String,String>)` 与 `Channels.tasks(String k)`(标 `@Deprecated`)⇒ 已编译的外部插件 jar 继续按描述符调旧方法,**不会 NoSuchMethodError**,只是其事件发到无人订阅的旧频道(局部降级,等同 C 的后果);
   - 核心 7 处 + 内置插件 4 处**全部**切到新签名 ⇒ 内置功能零降级。
   - 选它的理由:接口保持域中立(不违 §14.0/§14.9),又不把「旧外部插件崩溃」这种代价塞进本次修复。Java 上 `Function` 与 `BiFunction` 重载可靠 lambda 元数区分,既有字节码按精确描述符调用不受影响。
2. **协议版本 3 → 4 接受**:desktop 同包分发,公网 hub 需与浏览器端同批更新。选 A′ 后这不再牵连插件二进制兼容,风险已收敛。
3. **磁盘目录不加 worker 维度**(见 H4):反转 D20 需数据迁移,超出本次范围。
4. **提交时只 `git add` 我本次改的文件**:工作区另有 3 个与本次无关的未提交改动(`plugin-api/shell/ExecResults.java`、`worker/os/OsSandbox.java`、`worker/tools/CommandExecutor.java`,+275/-60),**不属于我的改动,一律不纳入我的提交**,保持其未提交状态。


## 执行结果（实际落地与计划的差异）

### 一处实现方式变更（比计划更好，已同步回代码与文档）

计划里 H1 的 A′ 方案是「给 `StreamEmitter` 加 `fanout(BiFunction)` 重载」。**实际落地改为：只给 `StreamEmitter` 加一个 `String workerId()` 方法**（发布者身份，仍然域中立），`fanout(Function<String,String>)` 签名一字未动：

- 核心与插件的 11 处发布点写成 `eventSink.fanout(k -> Channels.tasks(k, eventSink.workerId()), …)` —— 不需要新重载、不需要 `@Deprecated` 双轨，plugin-api 纯增量、外部插件二进制零影响；
- worker 侧再由 `TaskEventWire.fanoutTasks(...)` 收一层（同时保证 `payload.workerId` 必带，且缺字段时补**副本**、不污染磁盘 summary 的共享引用），task 层调用点收敛为 `fanoutTasks(event, payload)`；
- 插件不能依赖 worker（§14.9），故插件侧保持显式写法，语义等价。

### 新增的一处必要修复（计划外）

`every-agent-worker` 的测试替身 **`FakeHub.java` 自己硬编码了旧频道语法**来模拟 `subscriber.join`。频道改名后它认不出新形态 ⇒ 干脆不发订阅通知 ⇒ 所有推送相关用例集体失败，而 `nonOwnedTaskJoinIgnored` 会"假绿"（没 join 自然 0 个推送器）。已改为**委托 contract 的 `StreamChannelParser`** 并与真实 hub 同样按 worker 段定向，杜绝"替身替实现打折"。

### 落地清单

| 层 | 文件 | 改动 |
|---|---|---|
| 契约 | `contract/frame/StreamChannelParser.java` | 解析 `u.<K>.worker.<wid>.task.<tid>.stream`；`StreamRef` 增 `workerId`；从右往左解析（taskId 不含点、workerId 可含点）；保留旧形态（`workerId=null`）作兼容 |
| 契约 | `contract/frame/Frames.java` | `PROTOCOL_VERSION` 3→4 |
| 契约 | `schema/events.schema.json` | tasks/stream pattern 加 worker 段；顺带修正早已与实现脱节的 `inputEvent` pattern（D23 后 input 是 worker 级） |
| 插件 API | `event/Channels.java` | 新增 `tasks(k,workerId)`、`taskStream(k,workerId,taskId)`；旧签名保留标 `@Deprecated` |
| 插件 API | `event/StreamEmitter.java` | 新增 `String workerId()` |
| hub | `reg/ChannelRegistry.java` | join/leave 带 worker 段时 `findWorker(ownerKey,workerId)` 只投那一台；否则原广播。无新增状态 |
| worker | `hub/HubPool.java` | `workerId()` 实现；`pubAllTasks`/`pubTaskStream` 走带 worker 段频道 |
| worker | `task/TaskEventWire.java`(新) + `TaskManager` + `lifecycle/{StatusNode,TaskWiresNode,ResponseAckNode}` | 发布点收口；payload 必带 `workerId`；`tasks.list` 每条摘要盖 `workerId` |
| worker | `ship/{TaskOwnership(新),DataPusherManager}.java` | 双保险归属：频道 worker 段 + 任务是否本机可见（口径与 `task.poll` 存在性判定逐字一致）；不为非自身任务建推送器；解析改委托 contract（删本地手抄 `taskIdOf`） |
| web | `sdk/{channels,frames}.ts` | 与 Java 逐字对齐；`PROTOCOL_VERSION=4` |
| web | `hub/session.ts` | `workerIdOfFrame()` 删「按 K 前缀猜首连接」；新增 `onWorkerConnectionsChanged` 通知点（在 connect/close/disable/remove/teardown/致命错误处触发） |
| web | `task/{taskStore,taskStream,task-packet-view}.ts` | 归属以来源为权威；按 worker 订阅/校验频道；切 worker 剔除条目与游标；流句柄按 worker 丢弃；`open()` 重解析归属；**取不到 client 不再静默 return，写 `roundsError` 让错误条与重试出现**；`agentsSeeded` 移到拿到 client 之后 |
| web | `components/app/Layout.tsx`、`hub/modelConfigs.ts`、`plugin/pluginLoader.ts`、`main.tsx` | 订阅同一通知点：关闭全部 `task:*` 标签（含清草稿 preset）、按 worker 清模型缓存、插件 RPC 改**调用期**解析 workerId 并按新集合重载、ask 随流句柄清理 |

### 验证记录（沙箱环境，已做基线差分）

| 项 | 结果 |
|---|---|
| `mvn test -pl every-agent-contract` | ✅ 9/9（含 4 条新增：worker 段形态、缺 `workerId` 拒绝、**旧形态两频道必须被拒的回归锁**） |
| `mvn test -pl every-agent-hub` | ✅ 25/25，含新增 `workerSegmentChannelsAreRoutedToThatWorkerOnly`（同 K 两台 worker：只有被点名那台收到 join；点名的 worker 不在线则无人收到；订阅 A tasks 频道收不到 B 的事件） |
| worker 模块全量 vs **不含本次改动的基线**逐用例差分 | ✅ **0 条新增失败**；`WorkerDataPusherTest.nonOwnedTaskJoinIgnored` 由基线红 → 绿（且已确认是因归属校验生效，非因 FakeHub 漏发通知） |
| `npx tsc --noEmit`（web） | ✅ exit 0 |
| task-queue / task-input-queue 编译 | ✅（两模块不在根 reactor，用 `.everyagent/verify-plugins.xml` 临时聚合编译验证；该文件不入库） |

**未能在此环境验证的部分（须你在本机补做）**：worker 的 E2E 用例（`WorkerIntegrationTest`/`WorkerTaskPollTest`/`WorkerHubE2eTest`/`WorkerDataPusherTest` 其余 8 条等）在本沙箱基线即大面积失败，真因是 `NotFoundException: 模型配置不存在: null`（沙箱无模型配置 ⇒ `task.run` 起不来）与 `user.home=C:\` 导致 logback/`@TempDir` 不可写 —— 与本次改动无关，但意味着**流式推送链路只做了 hub 层验证，端到端需你在配好模型的机器上跑一次**。

### 人工验收矩阵（待你执行）

两台同 apiKey、不同 workerId 的 worker + 一个 hub：① 只启用 B、A 跑任务 → A 不进列表、A 侧无幽灵 DataPusher；② 切到 A → 任务标签全关、列表只剩 A、**不刷新页面直接点开能拉数据**；③ 切回 B 同上；④ 全程不需要 F5，F5 前后行为一致；⑤ kill 掉当前启用 worker 进程后打开其任务 → 显示错误条 + 重试，不再永久「正在加载任务线程…」。

---

## 备注

- **并行性**:步骤 0 → 1 串行(文档先行、契约是三层依赖底)。**步骤 2(worker)与步骤 3(hub)可并行**;步骤 4 依赖 1+2;步骤 5、6 都改 `taskStream.ts`,**必须串行**(5 先 6 后,避免同文件冲突)。步骤 7 收尾。
- **本次不做**:磁盘目录迁移;`term.*` 频道的 worker 段改造(termId 由前端生成、`term.open` RPC 已按 worker 定向,不构成串台,保持现状并在 §5.2 注明);混合模式多 worker 列表合并展示(与单连互斥,已在步骤 0 改为单连口径)。
- **已知取舍**:同机同 apiKey 双实例共享 `workspaces/<wsId>/tasks/` 时任务数据本身仍共享(D20 不变),本次只保证**可见性与定向操作按 worker 隔离**;此点写进 §4.3/§8.2,不做静默承诺。
- **回退**:步骤 1–3 协议层改动集中在两份 `Channels` + 一份 schema + 一处 hub 投递,单 commit 可整体 revert;步骤 5/6 为纯前端增量,可独立回退。无数据迁移 ⇒ 回滚无残留。
- **红线自查**:hub 仍零状态零业务(定向投递只读现成 `findWorker` 索引,不存订阅簿);worker 不做 ownerKey 白名单以外的新校验;三层只依赖 contract/plugin-api;不触碰 Spring AI 执行链与 Advisor 链;危险操作授权路径不受影响。
