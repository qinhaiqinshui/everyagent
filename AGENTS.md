# Every Agent — 核心约束(每会话加载,保持精简)

- 唯一架构事实源:[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。实现与文档冲突时,先改文档再改代码;§14 是红线清单。
- 三层解耦:hub / worker / client 只依赖 `every-agent-contract`,互相零依赖。
- hub 零状态、零缓冲、零业务逻辑、零 ack;一切补齐走 worker 的本地事件日志 + `task.poll` 拉取。stream 频道订阅通知(subscriber.join/leave)是无状态 fire-and-forget msg,不存订阅簿。
- worker:本地事件日志先行,发布/落盘永不阻塞任务线程;任务跑在虚拟线程(Java 25)。任务流混合模型:实时增量由 DataPusher 定向推送(stream 频道,ext.target=sessionId),历史/补齐走 `task.poll`(§7.13)。
- seq 是任务流事件空间(每任务从 1 单调递增、跨运行延续;瞬态占号不落盘 → 磁盘回放有洞合法),wire 上以**字符串**携带(task.poll rpc.data 事件项与 stream 推送帧,雪花 ID 超 2^53);运行中任务日志永不修剪。
- 频道即鉴权边界:`u.<ownerKey>.` 前缀 ACL;`fs.*`/`git.*`/`task.run`(新建)按调用必带 `workspace` 参数,jailed 到该工作区根(先 realpath 再校验前缀)。
- 危险操作必须经 `PermissionGate` 用户授权(§7.8):AI 工具的**工作区外**路径访问一律先弹 `kind=authorization` 的 ask(拒绝/本轮运行/本任务三档),拒绝抛异常回灌模型;命令中的危险动词**仅当命令引用可能落在工作区外的路径时**才需授权,工作区内增删改查直接放行(授权护的是「工作区外」,不是删除这个动作本身;cwd 锁定 + Low IL 可写性契约兜底);不得绕过 gate 直接放行越界 IO。
- 工作区在沙箱内可写(windows-mic 后端)是「完整性标注 + DACL 授权」两条腿(§7.10):Low IL 标注解决 MIC 拦截,`WindowsAcl` 给工作区树**追加**本地 Users 可写 ACE 解决 ACL 残缺;只对工作区/EXEC 授权根生效,工作区外仍被 OS 层拒写;不得为图省事把工作区 ACL 开放到 Everyone,也不得绕过 gate 直接放行越界 IO。
- 任务/对话数据由 worker 落盘 `data/tasks/<taskId>/`(多端同步真相源);前端不做任务数据 localStorage 持久化。
- 版本统一由根 pom 锁定(Spring Boot 4.1.x / Spring AI 2.0.x),三层不得各自升版本。
- 构建:`JAVA_HOME` 指向 JDK 25(如 Corretto 25);maven 在 PATH 中可用即可。
- **红线 · 复用 Spring AI 框架,禁止重复造轮子**:worker 的 agent 执行必须走 `ChatClient` + `Advisor` 生态,**不得手搓** agent 循环、工具调用循环、响应聚合、system 提示词拼接等 Spring AI 2 已有能力。Agent 执行链(`AgentRunner` 等)只能是**很薄的一层**——负责把 `Prompt` 交给 `ChatClient`、`ToolCallingAdvisor` 接管工具循环、自定义 `Advisor` 注入 skill/记忆/护栏等增强;所有可用 `ChatClient.advisors()` / `defaultAdvisors()` / `defaultTools()` / `ToolCallback` 表达的能力,一律复用,不允许自实现等价逻辑。新增 agent 能力优先做成 `Advisor`,而非改写执行核心。
- **红线 · 一个 Advisor 只负责一个功能**:能用新增 `Advisor` 实现的增强(注入 skill、发射 worker 事件、记忆、护栏等),绝不在执行核心或别处手搓等价逻辑;不得把多个不相关职责塞进同一个 `Advisor`。事件发射等需挂钩工具循环的增强,通过**继承** `ToolCallingAdvisor` 并重写其受保护 hook(`doAfterStream` / `doGetNextInstructionsForToolCallStream` 等)实现,不得另起一层包裹或重复实现递归循环。
- 主 Agent 与子 Agent **共用同一运行入口与 Advisor 链**,仅 `agentId` 不同(与 nagent 做法一致);禁止为子 agent 单独复制一套执行逻辑。
- 在完成开发/bug修复任务后提交本次修改。提交信息中文,一次一事。
