---
title: API 索引
nav_order: 18
parent: reference
has_children: false
---

# API 索引

一屏速查全部插件 API（对标 VS Code API reference）：先在这页按方法名 / 字段名定位要用的东西，再顺着「详解」列的锚点跳到对应文档读细节。所有签名、行号、order 值均出自本站已成各篇的源码复核（共享事实基线 `.everyagent/doc-authoring-rules.md` §5）。

阅读约定：

- 详解列全部为站内相对链接（`.md` + 小节锚点）；「§n」指目标文档小节。
- ⚠️ = 写了不生效 / 全仓零使用的扩展点或字段（集中登记在 [known-issues](known-issues.md)，撰写中）。
- 范例列的插件 id 均可在[内置插件范例索引 §2](builtin-plugins.md#2-总表25-个内置插件) 查到逐行解读；「想写 X 就抄 Y」反查见[同篇 §3](builtin-plugins.md#3-想写-x-就抄-y反查表)。

## 1. 后端 API 索引

### 1.1 `EveryAgentPlugin` —— Java 入口契约（3 方法）

声明：`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/EveryAgentPlugin.java`；详解 [后端总览 §5](../backend/overview.md#5-everyagentplugin-契约与最小入口类)。最小全功能范例：task-queue（java-only）。

| 方法 | 一句话 | 详解 | 范例 |
|---|---|---|---|
| `String id()` | 必须与 plugin.json 的 `id` 一致（仅 `[a-z0-9-]`） | [总览 §5](../backend/overview.md#5-everyagentplugin-契约与最小入口类) | 全部 25 个 |
| `void activate(WorkerPluginContext ctx)` | 一切注册发生在此；抛异常只废自己一个插件 | 同上 | 同上 |
| `default void deactivate()` | 预留钩子，worker 从不调用 | [总览 §3.2](../backend/overview.md#32-无-deactivate-钩子改动--重启-worker) | — |

### 1.2 `WorkerPluginContext` —— 15 个注册方法

13 个声明于 `every-agent-plugin-api/.../WorkerPluginContext.java:42-94`，末 2 个在父接口 `TaskPluginContext`（表中 #14/#15）。注册即生效：worker 侧只往注册表 `add` 一行，无校验、无过滤（[总览 §6.1](../backend/overview.md#61-注册方法15-个)）。

| # | 注册方法（一行签名） | 详解 | 内置范例 |
|---|---|---|---|
| 1 | `void registerToolProvider(ToolProvider p)` | [工具 §1](../backend/tools-and-sandbox.md#1-toolprovider--给-ai-注入工具) | subagent、sandbox-windows-codex/mic、sandbox-wsl-ubuntu |
| 2 | `void registerToolExecutionInterceptor(ToolExecutionInterceptor i)` | [工具 §2](../backend/tools-and-sandbox.md#2-toolexecutioninterceptor--工具执行拦截链) | secret-redaction(900)、unattended(100) |
| 3 | `void registerSandboxProvider(SandboxProvider p)` | [工具 §3](../backend/tools-and-sandbox.md#3-sandboxprovider--沙箱后端) | sandbox-wsl-ubuntu(priority 10)、codex(8)、mic(5) |
| 4 | `void registerFileReferenceHandler(FileReferenceHandler h)` | [工具 §4](../backend/tools-and-sandbox.md#4-filereferencehandler--用户输入里的文件引用) | image-vision（唯一） |
| 5 | `void registerAdvisorProvider(AdvisorProvider p)` | [advisors §1](../backend/advisors.md#1-advisorprovider--向-agent-链注入-advisor) | 12 插件 13 provider（system-info、git、context-compression…） |
| 6 | `void registerSearchProvider(SearchProvider p)` ⚠️ | [advisors §6](../backend/advisors.md#6-searchprovider--搜索后端未接线如实登记) | 零使用：registry 无人查询，未接线 |
| 7 | `void registerAuthorizationHandler(AuthorizationHandler h)` | [advisors §7](../backend/advisors.md#7-authorizationhandler--授权决议链节点) | ai-review(100f)、unattended(200f)；内置 human=300f 终结 |
| 8 | `void registerSkillContributor(SkillContributor c)` | [advisors §5](../backend/advisors.md#5-skillcontributor--贡献-skill) | subagent（半接线：只进 system prompt，不进 `/` 菜单） |
| 9 | `void registerTokenEstimator(TokenEstimator e)` | [advisors §4](../backend/advisors.md#4-tokenestimator--替换-token-估算器) | model-rate-limit |
| 10 | `void registerChatModelEnhancer(ChatModelEnhancer e)` | [advisors §3](../backend/advisors.md#3-chatmodelenhancer--模型构建期介入) | model-pool |
| 11 | `void registerRpcMethod(String method, RpcMethod h)` | [任务 §3](../backend/task-and-rpc.md#3-registerrpcmethod--自注册-rpc) | git×13、task-input-queue×3、task-queue/subagent/file-change 各 1 |
| 12 | `void registerSlashProvider(String id, SlashProvider p)` | [任务 §4.1](../backend/task-and-rpc.md#41-接口签名) | unattended、git、ai-review、sandbox-wsl-ubuntu |
| 13 | `void registerSlashTokenResolver(SlashTokenResolver r)` | [任务 §4.1](../backend/task-and-rpc.md#41-接口签名) | 同上四家 |
| 14 | `void registerTaskAdmissionPolicy(TaskAdmissionPolicy p)`（`TaskPluginContext.java:17`） | [任务 §1](../backend/task-and-rpc.md#1-taskadmissionpolicy--任务创建准入预检) | task-queue（全仓唯一） |
| 15 | `void registerTaskLifecycleNode(TaskLifecycleNode n)`（`TaskPluginContext.java:24`） | [任务 §2](../backend/task-and-rpc.md#2-tasklifecyclenode--洋葱模型) | 4 插件 5 节点（order 15/40/870/877/950） |

### 1.3 `WorkerPluginContext` —— 5 个辅助方法（不注册、只取数）

| 方法 | 一句话 | 详解 |
|---|---|---|
| `String pluginId()` | 本插件 id | [总览 §6.2](../backend/overview.md#62-辅助方法5-个不注册只取数) |
| `Path pluginDir()` | 插件根目录，**插件私有数据唯一合法落点** | 同上；深读 [持久化与状态](../backend/persistence-and-state.md)（撰写中，文件级链接） |
| `WorkerServices services()` | 只读服务门面（§1.5 清单） | [总览 §6.3](../backend/overview.md#63-workerservices能取到什么逐个列) |
| `PluginConfig config()` | 恒等于 plugin.json 的 `contributes.config.*.default`，无用户覆盖、无写盘 | [清单 §2.2](../plugin-manifest.md#22-contributesconfig-的实际链路比想象短) |
| `<T> T getService(Class<T> type)` | 直取 Spring bean 的后门；外部插件勿依赖未公开服务 | [总览 §6.2](../backend/overview.md#62-辅助方法5-个不注册只取数) |

### 1.4 `TaskPluginContext` —— 父接口（2 方法）

`WorkerPluginContext extends TaskPluginContext`，该父接口只声明上表 #14/#15 两个注册方法（`every-agent-plugin-api/.../task/TaskPluginContext.java:17,24`）。详解：[准入预检](../backend/task-and-rpc.md#1-taskadmissionpolicy--任务创建准入预检)、[洋葱节点](../backend/task-and-rpc.md#2-tasklifecyclenode--洋葱模型)。

### 1.5 `WorkerServices` —— 服务清单（13 项）

> 本表摘自 [后端总览 §6.3](../backend/overview.md#63-workerservices能取到什么逐个列)（逐项声明行号见该篇）；声明：`every-agent-plugin-api/.../WorkerServices.java`。插件只面向此接口编程，不依赖 worker 实现类。

| 方法 | 一句话 | 消费范例 |
|---|---|---|
| `SandboxBackend sandbox()` | 沙箱门面：查后端 id、委托挂载 | sandbox 三家 |
| `nativeExec()` | 宿主原生进程执行器（argv 直传 + 超时 + 输出上限） | git |
| `workspaces()` | 多工作区注册表、jailed 路径解析 | git（每方法首行 `sandboxFor`） |
| `tokenEstimator()` | token 估算器（内置或插件注册版） | model-rate-limit |
| `interaction()` | 用户交互服务（ask 提问 / 授权弹窗） | ai-review、image-vision |
| `config()` | worker 全局 `worker.*` 只读视图（≠ `ctx.config()`） | task-queue |
| `ids()` | ID 生成器（单调递增 long + 短 ID） | — |
| `stream()` | 事件扇出口（向 hub 连接广播，如 task.updated） | — |
| `Path dataDirOf(String subjectId)` | 任务数据目录；任务不存在/已清理返回 null | file-change |
| `EventEmitter emitterOf(String subjectId)` | 任务事件发射器；终态返回 null，应跳过 emit | file-change |
| `void addRoundClosedListener(RoundClosedListener l)` | 轮闭合监听（唯一非 register* 服务通道） | file-change |
| `String toSandboxPath(String hostPath)` | 宿主路径 → 沙箱内路径（WSL 后端 `C:\...` → `/c/...`） | sandbox-wsl-ubuntu |
| `task()` / `store()` | 任务信息服务 / 任务落盘服务（继承自 `TaskServices` 子接口） | task-queue |

### 1.6 `ExecContext` —— 上下文槽位（11 槽 + 1 常量）

四个 SPI 的上下文（`ToolContext`/`ToolExecutionContext`/`FileReferenceContext`）全部 `extends ExecContext`，插件只准从这些槽位取执行数据（§14.11 红线）。逐字段表：[工具 §5.2](../backend/tools-and-sandbox.md#52-逐字段表)；红线原文与方向判据：[工具 §5](../backend/tools-and-sandbox.md#5-execcontext--槽位判据与字段表)。

| 槽位 | 一句话 | 消费范例 |
|---|---|---|
| `String subjectId()` | 执行主体 ID（今天=taskId，未来=workflowId） | 授权状态分区、审计 |
| `String workspaceRoot()` | 工作区根（可能 null）；**决定命令 cwd** | CommandExecutor |
| `String workspaceId()` | 工作区稳定 ID | 数据目录推导 |
| `ModelConfig snapshot()` | 完整模型配置快照；configId 经它取，无独立槽 | advisor 装配 |
| `EventEmitter emitter()` | 已绑定本主体的事件口（trace/审计） | secret-redaction |
| `AgentFactory agentFactory()` | 预绑定 Agent 工厂（`create(agentId)`） | subagent |
| `Map<String,Object> metadata()` | 持久策略标记（随 meta.json 落盘，不混瞬态） | unattended、attachments |
| `Path dataDir()` | 主体数据目录（grants.json 等落盘） | 授权链 |
| `boolean terminal()` | 主体是否已收口（终态后不再发射） | leak-guard |
| `InteractionService interaction()` | 预绑定交互口（ask 自动补 subjectId） | image-vision |
| `Map<String,AgentContext> agents()` | 本主体活动 agent 注册表（自动维护，插件不再手动 put） | AgentBuilder |
| 常量 `METADATA_ATTACHMENTS_KEY` | metadata 中附件列表键名（`"attachments"`） | FileReferenceProcessNode |

### 1.7 四个支撑类型

**`RpcContext`**（`plugin-api/.../rpc/RpcContext.java:11-45`；详解 [任务 §3.1](../backend/task-and-rpc.md#31-方法签名与-rpccontext)；范例 git.status）：

| 方法 | 一句话 |
|---|---|
| `String strParam(String name)` | 必填字符串，缺失/空 → `BAD_PARAMS` |
| `String optStrParam(String, String def)` / `long optLongParam(String, long def)` | 可选参数带默认值 |
| `JsonNode params()` | 原始参数（复杂结构自行解析） |
| `void ok(JsonNode result)` / `void err(String code, String message)` | 应答；同一 reqId **至多一次** |

**`SlashProvider` / `SlashTokenResolver`**（详解 [任务 §4.1](../backend/task-and-rpc.md#41-接口签名)；范例 unattended / git）：

| 接口 | 签名 | 一句话 |
|---|---|---|
| `SlashProvider` | `List<SlashCommandItem> load()` | 加载该来源全部 `/` 菜单条目 |
| `SlashTokenResolver` | `String kind()` | 固定 kind，与 opaque token 构造时一致 |
| | `String resolveSubmissionText(JsonNode payload)` | 替换文本；空串 = 从模型上下文剥离该 token |

**`EmitEvent`**（record，`plugin-api/.../model/EmitEvent.java:21-31`；详解 [任务 §5](../backend/task-and-rpc.md#5-事件发射红线140)）：字段 `id/kind/agentId/title/summary/content/status/data/persist/mode`；工厂 `EmitEvent.of(...)`（持久）与 `EmitEvent.transientOf(...)`（瞬态）。插件只发语义 `EmitEvent`、不感知 wire 事件名/seq（§14.0 红线）；`agent.started/status/done` 等四个事件由 `AgentEntity` 单点发射，插件不得手搓。范例：model-rate-limit。

## 2. 前端 API 索引

### 2.1 `PluginModule` / `PluginContext`

入口契约（`webMain` + `export default { activate }`）与「类型只能 `import type`」见 [加载链路 §3](../web/overview-and-loading.md#3-六条硬约定)；字段逐个详解见 [ctx API §1](../web/context-api.md#1-成员总览)。

| 名称 | 签名 / 类型 | 一句话 | 详解 |
|---|---|---|---|
| `PluginModule.activate` | `activate(ctx: PluginContext): void \| Promise<void>` | 唯一必需入口（default export） | [加载 §3](../web/overview-and-loading.md#3-六条硬约定) |
| `PluginModule.deactivate?` | `deactivate?(): void \| Promise<void>` | 预留；宿主从不调用 | 同上 |
| `ctx.pluginId` | `readonly string` | 等于 plugin.json 的 `id` | [ctx §2](../web/context-api.md#2-ctxpluginid-与-ctxextensionpath) |
| `ctx.extensionPath` | `readonly string` | 恒为字面量 `'web/index.js'`，别做拼接 | 同上 |
| `ctx.sdk` | `PluginSdk` | RPC + 工作区快照 + workerId（§2.3） | [ctx §3](../web/context-api.md#3-ctxsdk--rpc-与工作区) |
| `ctx.storage` | `PluginStorage`（§2.5） | localStorage，键前缀 `plugin:<id>:` | [ctx §4](../web/context-api.md#4-ctxstorage--本地键值存储) |
| `ctx.commands` | `CommandRegistry`（§2.5） | 本插件私有命令表，不跨插件 | [ctx §5](../web/context-api.md#5-ctxcommands--命令注册表) |
| `ctx.ui` | `UiRegistry`（§2.2） | 12 register + 5 动作 | [UI §1](../web/ui-extensions.md#1-十二个扩展点总览) |
| `ctx.events` | `PluginEvents`（§2.4） | 宿主进程内事件总线的最薄委托 | [ctx §7](../web/context-api.md#7-ctxevents--领域事件总线) |
| `ctx.fs` | `PluginFs`（§2.5） | `listDir`/`delete`，jail 到工作区根 | [ctx §6](../web/context-api.md#6-ctxfs--工作区文件系统网关) |

### 2.2 `UiRegistry` —— 12 个 register + 5 个动作

两张总览：[加载链路 §4.1](../web/overview-and-loading.md#41-12-个-ctxuiregister全部返回真清理的-disposable)、[UI 扩展点 §1](../web/ui-extensions.md#1-十二个扩展点总览)。所有 register 返回 Disposable（`registerOutputBlock`/`registerTraceType` 两个假 dispose 例外，见 [UI §16](../web/ui-extensions.md#16-disposable-与刷新)）。

| 扩展点 | 方法（一行签名） | 详解 | 范例 |
|---|---|---|---|
| `ui.sidebar_items` | `registerSidebarItem(def: UiSidebarItemDefinition)` | [UI §2](../web/ui-extensions.md#2-uisidebar_items--侧边栏活动栏项) | git(order 5)、plugin-manager(9) |
| `ui.workspace_tab_types` | `registerWorkspaceTabType(def)` | [UI §4](../web/ui-extensions.md#4-uiworkspace_tab_types--工作区标签类型) | git（git-history） |
| `ui.file_sidebar_panels` | `registerFileSidebarPanel(def)` | [UI §5](../web/ui-extensions.md#5-uifile_sidebar_panels--文件页侧栏面板) | ⚠️ 无内置范例（仅脚手架模板用） |
| `ui.composer_above_panel` | `registerComposerAbovePanel(def)`（单数） | [UI §6](../web/ui-extensions.md#6-uicomposer_above_panel--输入框上方面板注意单数) | subagent、task-input-queue |
| `ui.tool_call_views` | `registerToolCallView(def)` | [UI §7](../web/ui-extensions.md#7-uitool_call_views--工具调用视图接管) | update-file-view（update_file） |
| `ui.user_message_actions` | `registerUserMessageAction(def)` | [UI §8](../web/ui-extensions.md#8-uiuser_message_actions--用户消息动作) | task-edit-resend |
| `task.submit_contributions` | `registerTaskRunSubmitContributionProvider(p)` | [UI §9](../web/ui-extensions.md#9-tasksubmit_contributions--taskrun-提交贡献) | task-edit-resend |
| `ui.trace_types` | `registerTraceType(def)` | [UI §10](../web/ui-extensions.md#10-uitrace_types--trace-类型渲染) | ai-review（auth.review）；⚠️ dispose 假 |
| `ui.output_blocks` | `registerOutputBlock(def)` | [UI §11](../web/ui-extensions.md#11-uioutput_blocks--输出块渲染) | ⚠️ 无内置范例；dispose 假 |
| `ui.file_content_editors` | `registerFileContentEditor(desc)` | [UI §12](../web/ui-extensions.md#12-uifile_content_editors--文件内容编辑器) | pdf-viewer（.pdf） |
| `ui.file_explorer_actions` | `registerFileExplorerAction(action)` ⚠️ | [UI §13](../web/ui-extensions.md#13-uifile_explorer_actions--文件树右键菜单-死扩展点) | git 注册但宿主无消费点（死扩展点） |
| `ui.round_tail_panels` | `registerRoundTailPanel(def)` | [UI §14](../web/ui-extensions.md#14-uiround_tail_panels--轮末展示区) | file-change |

动作方法（不注册、只驱动宿主；桥未注入时静默降级）：详解全部在 [UI §15](../web/ui-extensions.md#15-uiregistry-动作方法5-个)。

| 方法 | 一行签名 | 范例 |
|---|---|---|
| `openPluginTab` | `(type: string, data: Record<string,string>, title?: string) => void` | git |
| `openFileTab` | `(workspaceRoot: string, filePath: string, opts?: { mode?: 'readwrite' \| 'readonly' }) => void` | update-file-view |
| `openDiffTab` | `(input: PluginDiffTabInput) => void` | git |
| `appendComposerText` | `(text: string) => void` | task-edit-resend |
| `setComposerRawContent` | `(raw: string) => void` | task-edit-resend |

### 2.3 `PluginSdk`

详解 [ctx §3.1](../web/context-api.md#31-方法表)；错误形态（RpcError/未连接/超时）见 [ctx §3.2](../web/context-api.md#3-ctxsdk--rpc-与工作区)。

| 成员 | 一行签名 | 一句话 |
|---|---|---|
| `sdk.rpc` | `rpc(workerId: string, method: string, params?: unknown): Promise<unknown>` | workerId 传空串回退在线连接解析 |
| `sdk.workerId` | `readonly string`（getter） | 读取期解析，切 worker 后自动跟随 |
| `sdk.workspace.id` | `readonly string` | 恒 `'defaultworkspace'`，别当唯一键 |
| `sdk.workspace.rootPath` | `readonly string` | worker 默认工作区根（sys.info 回填） |
| `sdk.workspace.list()` | `() => Promise<PluginWorkspaceEntry[]>` | 前端工作区注册表合并快照 |
| `sdk.workspace.workerIdOfRoot(root)` | `(root: string) => string \| undefined` | 按工作区根反查来源 worker |

### 2.4 `PluginEvents` —— on/emit 与 38 个事件的生死

总线实现与「emit 到不了 worker」结论：[事件 §1](../web/events.md#1-事件系统架构一个模块级单例总线)。

| 方法 | 一行签名 | 详解 |
|---|---|---|
| `on` | `on(name: PluginDomainEvent, h: (payload: unknown) => void): Disposable` | [ctx §7](../web/context-api.md#7-ctxevents--领域事件总线) |
| `emit` | `emit(name: PluginDomainEvent, payload: unknown): void` | 无监听者静默；`*-requested` 类可反向驱动宿主（[事件 §3.2](../web/events.md#32-声明了但全仓零-emit-的事件24-个-订阅无效)） |

宿主 `DOMAIN_EVENTS` 共 **38** 个：**14 个真实会发生**（[事件 §3.1](../web/events.md#31-真实会发生的事件14-个)）、**24 个零 emit 死事件**（[事件 §3.2](../web/events.md#32-声明了但全仓零-emit-的事件24-个-订阅无效)）。类型包具名 9 个（[事件 §2](../web/events.md#2-plugin-api-的-9-个具名事件)）生死如下：

| 具名事件 | 生死 | 具名事件 | 生死 |
|---|---|---|---|
| `workspace-file-changed` | ✅ 活（高频） | `task-created` | ❌ 零 emit |
| `workspace-registry-changed` | ✅ 活 | `task-deleted` | ❌ 零 emit（file-change 踩中死订阅） |
| `sidebar-panel-shown` | ✅ 活 | `task-trace-changed` | ❌ 零 emit |
| `task-status-changed` | ✅ 活 | `file-content-saved` | ❌ 零 emit |
| `task-round-closed` | ✅ 活（历史回放会补发） | （其余 29 个不在具名清单） | 9 活 + 20 死，见 §3.1/§3.2 |

插件间通信无先例：25 个内置插件 events 调用 6 处全是 `on`、零 `emit`；约定事件名用 `<pluginId>:<verb>` 前缀（[事件 §6](../web/events.md#6-插件间通信模式)）。

### 2.5 `PluginStorage` / `PluginFs` / `CommandRegistry` / `Disposable`

| 类型 | 一行签名 | 详解 | 范例 |
|---|---|---|---|
| `PluginStorage` | `get<T>(key: string, defaultValue?: T): T \| undefined`；`set(key, value)`；`delete(key)` | [ctx §4](../web/context-api.md#4-ctxstorage--本地键值存储) | 内置插件零调用（残留坑见同篇 §11） |
| `PluginFs` | `listDir(workspaceRoot, dir): Promise<PluginFileStat[]>`；`delete(workspaceRoot, path): Promise<void>` | [ctx §6](../web/context-api.md#6-ctxfs--工作区文件系统网关) | git（唯一使用者） |
| `CommandRegistry` | `registerCommand(id, handler): Disposable`；`executeCommand(id, ...args): Promise<unknown>` | [ctx §5](../web/context-api.md#5-ctxcommands--命令注册表) | 内置插件零调用 |
| `Disposable` | `dispose(): void` | [UI §16](../web/ui-extensions.md#16-disposable-与刷新)、[ctx §9](../web/context-api.md#9-寿命与清理) | 刷新即全丢；跨刷新用 storage |

## 3. plugin.json 字段速查

全字段表（含消费点行号与缺省值）：[plugin.json 字段参考 §2](../plugin-manifest.md#2-全字段表)。解析对未知键静默忽略，多写不报错。

**生效字段**（[清单 §2.1](../plugin-manifest.md#21-worker-真正消费的字段)）：

| 字段 | 必填 | 一句话 | 详解 |
|---|---|---|---|
| `id` | 实践必填 | 三重身份；缺失回退目录名 | [§4](../plugin-manifest.md#4-id-的三重身份最容易踩坑处) |
| `name` / `description` / `author` | 否 | 纯展示（plugin.list 与扩展面板） | [§2.1](../plugin-manifest.md#21-worker-真正消费的字段) |
| `version` | 否 | 纯展示字符串，不校验 semver、不与 pom 比对 | 同上 |
| `main` | java/full 必填 | 入口类 FQN，须与 `src/main/java` 路径逐段一致 | [§8](../plugin-manifest.md#8-与-pomxml-的对应关系) |
| `webMain` | web/full 必填 | 只决定 `hasWebMain` 真假，值不被前端消费 | [§5](../plugin-manifest.md#5-webmain-陷阱专节) |
| `enabled` | 否 | **仅内置扫描器读**；外部插件写 false 不生效 | [§6](../plugin-manifest.md#6-enabled-与运行期禁用机制的分工) |
| `contributes.config.<key>.default` | 否 | 唯一被读的子键（`type`/`description` 无人消费） | [§2.2](../plugin-manifest.md#22-contributesconfig-的实际链路比想象短) |

**⚠️ 未接线字段**（[清单 §2.3](../plugin-manifest.md#23-存在于-plugindescriptor但当前不生效的字段)，写了没有运行期效果）：

| 字段 | 状态 |
|---|---|
| `minAppVersion` | 无任何版本比较 |
| `requires.spi` / `requires["every-agent"]` | 依赖不校验、不解析、不装配 |
| `provides.spi.EveryAgentPlugin` | **例外生效**：旧格式入口类回退（provides 里唯一被读的键） |
| `provides.rpc` / `provides.slash` / `provides.web` | 不生效；必须在 activate() 里真实注册 |
| `activationEvents` | 无懒激活，所有启用插件启动期一次性 activate |
| `contributes.config.*.type` / `.description` | 运行期无人消费（仅 default 有用） |

## 4. 命名与 order 速查

### 4.1 插件 id 规则

`^[a-z0-9][a-z0-9-]{1,38}$` 且首尾不能是连字符（2~39 字符；脚手架校验 `create-everyagent-plugin/index.mjs:52`），25 个内置全部合规。id 的三重身份（目录名 / `.eap` 顶层目录 / localStorage 前缀）：[清单 §4](../plugin-manifest.md#4-id-的三重身份最容易踩坑处)。

### 4.2 包名 / 入口类推导（脚手架约定）

`create-everyagent-plugin/lib/naming.mjs`（唯一事实源）：包名 = `dev.everyagent.plugin.<camelCase(id)>`；入口类简名 = `PascalCase(id) + "Plugin"`；`main` = 二者拼接。例：`pdf-viewer` → `dev.everyagent.plugin.pdfViewer.PdfViewerPlugin`。⚠️ 仓内既有插件的包名并不全按此式（如 `dev.everyagent.plugin.sandbox.codex`）；硬约束只有一条——`main` 的 FQN 必须与 `src/main/java` 路径逐段一致（[清单 §8](../plugin-manifest.md#8-与-pomxml-的对应关系)）。

### 4.3 RPC 方法名前缀现状

内置插件注册的 19 个方法全在两个前缀下：`git.`×13、`task.`×6。`task.*` 与 worker 内置方法（`task.run`/`task.poll`…）共用一张方法表、无按插件 id 隔离的强制规则；新插件建议用自己独有的域前缀（如 `myplugin.action`）避免撞名。详解：[任务 §3.3](../backend/task-and-rpc.md#33-方法名命名空间现状19-个插件方法归纳)。

### 4.4 后端三套 order 坐标系（互不相干，勿混用）

| 坐标系 | 类型 / 方向 | 关键占用（实测） | 详解 |
|---|---|---|---|
| AdvisorProvider | int，升序=外→内；锚点 `TCA = ToolCallingAdvisor.DEFAULT_ORDER = HP+300 = -2147483348` | HP+50 system-info、HP+60 agents-md、HP+140 git、HP+150 SlashTokenResolve、HP+160 FileAttachment、**TCA 工具循环本体**、TCA+30~+500 重试/护栏/压缩/限流、`0`=TokenCalibration（⚠️ 绝对值，实际最内层） | [advisors §2.2](../backend/advisors.md#22-内置-advisor-链完整-order-表)、[§2.4](../backend/advisors.md#24-420-850-的源码验证结论与计划口径不符如实更正) |
| TaskLifecycleNode | float 洋葱；**临界段 [420,850] 落入即 WARN 拒绝** | 插件节点 15 / 40 / 870 / 877 / 950 | [任务 §2.3](../backend/task-and-rpc.md#23-完整节点-order-占用表worker-内置-26--插件-5共-31)、[§2.5](../backend/task-and-rpc.md#25-临界段-420850-专节) |
| ToolExecutionInterceptor | int | unattended=100、secret-redaction=900 | [工具 §2](../backend/tools-and-sandbox.md#2-toolexecutioninterceptor--工具执行拦截链) |

另两套小坐标系：SandboxProvider priority（wsl 10 / codex 8(Windows) / mic 5，[工具 §3](../backend/tools-and-sandbox.md#3-sandboxprovider--沙箱后端)）；AuthorizationHandler float 决议链（ai-review 100f → unattended 200f → 内置 human 300f 终结，[advisors §7](../backend/advisors.md#7-authorizationhandler--授权决议链节点)）。

### 4.5 前端侧边栏 order

唯一有 order 的前端扩展点：float 升序与内置项混排，缺省 100（`DEFAULT_SIDEBAR_ORDER`）。当前占用：1 内置 tasks、2 内置 files、3 内置 search、**5 git「源代码管理」、9 plugin-manager「扩展」**、10 内置 settings；其余前端扩展点无 order，靠「插件在前」合并顺序覆盖。详解：[UI §3](../web/ui-extensions.md#3-侧边栏-order-坐标系专节)、[加载 §5.1](../web/overview-and-loading.md#51-只有侧边栏有-orderfloat-坐标系统一升序混排)。

## 下一步读

- 每个扩展点的逐行范例与 order 值占用：[内置插件范例索引](builtin-plugins.md)（order 速查在其 [§4](builtin-plugins.md#4-order-值占用速查)）
- 死扩展点 / 未接线字段 / API 未发布等现状偏差：[known-issues](known-issues.md)（撰写中）
- 起步走读：[快速上手](../getting-started.md)（撰写中）
