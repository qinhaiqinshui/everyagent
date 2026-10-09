---
title: 内置插件范例索引
nav_order: 17
parent: reference
has_children: false
---

# 内置插件范例索引

**一句话定位**：`every-agent-plugins/` 下的 27 个内置插件是**活教材**——每种扩展点、每种形态、每种 order 档位在仓内都有真实可抄的范本。本篇告诉你「想学某个扩展点，去抄哪个插件」：先查总表（§2）定位插件，再用反查表（§3）按扩展点直达推荐范本，写代码前扫一眼 order 占用表（§4）避开已占档位。

所有路径相对仓库根；表格内为省篇幅，`every-agent-plugins/` 前缀在 §5 各小节统一省略为 `~plugins/`。全部数据来自源码逐文件取证（本篇成文前按行复核过关键行号，见文末口径说明）。

## 1. 怎么用这份索引

- **新手路线**：从 §3 反查表找到你想实现的扩展点 → 点进推荐插件的源码目录 → 对照 §5 该插件小节里「值得看的点」按图索骥。
- **order 选择**：写 Advisor / 生命周期节点 / 侧边栏项之前，**必须**先看 §4，新值要与相邻功能保持语义间距，不要复用已占用值。
- **形态选择**：不知道该做纯 Java、纯 Web 还是混合？看 §2「形态」列，然后读 [plugin.json 字段参考](../plugin-manifest.md) §3 的三形态实例。
- 本篇只做索引与点评，**不讲 API 细节**：注册方法的签名与生命周期见 [后端模型总览](../backend/overview.md)、[Advisor 指南](../backend/advisors.md)、[任务与 RPC](../backend/task-and-rpc.md)、[UI 扩展点](../web/ui-extensions.md)；已知偏差集中登记在 [已知问题与现状偏差](known-issues.md)。

## 2. 总表（27 个内置插件）

缩写：**DO** = `ToolCallingAdvisor.DEFAULT_ORDER`（−2147483348）；**HP** = `Ordered.HIGHEST_PRECEDENCE`（−2147483648）。「代码量级」= 入口文件（`main` 指向的 Java 类 / `web/index.ts`）行数，量级仅供参考。

| id | 中文名 | 形态 | 后端注册的 SPI / RPC | 前端注册的扩展点 | order 关键值 | 代码量级 |
|---|---|---|---|---|---|---|
| adaptive-max-tokens | 自适应输出预算 | java | AdvisorProvider | — | DO+250 | 入口 21 行 |
| agents-md | agents.md 约束注入 | java | AdvisorProvider | — | HP+60 | 入口 19 行 |
| ask-user | 用户提问 | java | ToolProvider | — | — | 入口 ≈20 行 |
| ai-review | AI 审议 | both | AuthorizationHandler + SlashProvider + SlashTokenResolver | `ui.trace_types` | — | 入口 33 行 + web 40 行 |
| context-compression | 上下文压缩 | java | AdvisorProvider | — | DO+400 | 入口 21 行 |
| empty-response-retry | 空响应重试 | java | AdvisorProvider | — | DO+100 | 入口 21 行 |
| file-change | 文件变更跟踪 | both | AdvisorProvider + `addRoundClosedListener`；RPC `task.fileChanges` | `ui.round_tail_panels` | HP+301 | 入口 132 行 + web 35 行 |
| git | Git 操作 | both | AdvisorProvider + SlashProvider + SlashTokenResolver；RPC ×13（`git.*`） | `ui.sidebar_items` + `ui.workspace_tab_types` + `ui.file_explorer_actions` | HP+140；侧边栏 5 | 入口 56 行 + web 57 行 |
| image-vision | 图片识别 | java | FileReferenceHandler | — | — | 入口 34 行 |
| mobile-keyboard | 移动端键盘增强 | web | —（无 Java） | —（自挂 DOM 悬浮球，不走 register\*） | — | 入口 30 行 + web 300 行 |
| model-length-guard | 模型输出预算耗尽护栏 | java | AdvisorProvider | — | DO+300 | 入口 23 行 |
| model-pool | 模型池容灾 | java | ChatModelEnhancer | — | — | 入口 29 行 |
| model-rate-limit | 模型限流 | java | TokenEstimator + AdvisorProvider ×2 | — | 校准 0；限流 DO+500 | 入口 47 行 |
| pdf-viewer | PDF 预览 | web | —（无 Java） | `ui.file_content_editors` | — | web 22 行 |
| plugin-manager | 插件管理 | web | —（无 Java；调 `plugin.list/enable/disable` RPC） | `ui.sidebar_items` | 侧边栏 9 | web 37 行 |
| sandbox-windows-codex | Windows Codex 沙箱 | java | SandboxProvider + ToolProvider | — | priority 8（非 Windows 为 0） | 入口 59 行 |
| sandbox-windows-mic | Windows MIC 沙箱 | java（`enabled:false`） | SandboxProvider + ToolProvider | — | priority 5 | 入口 34 行 |
| sandbox-wsl-ubuntu | WSL Ubuntu 沙箱 | java（`enabled:false`） | SandboxProvider + ToolProvider + SlashProvider + SlashTokenResolver | — | priority 10 | 入口 47 行 |
| secret-redaction | 凭据输出脱敏 | java | ToolExecutionInterceptor | — | — | 入口 24 行 |
| subagent | 子 Agent | java | ToolProvider + SkillContributor + TaskLifecycleNode | — | 节点 950 | 入口 47 行 |
| system-info | 系统环境信息注入 | java | AdvisorProvider | — | HP+50 | 入口 19 行 |
| task-edit-resend | 编辑重发 | both | TaskLifecycleNode | `ui.user_message_actions` + `task.submit_contributions` | 节点 877 | 入口 27 行 + web 26 行 |
| task-input-queue | 任务输入队列 | both | TaskLifecycleNode ×2 + AdvisorProvider；RPC ×3（`task.queue*`） | `ui.composer_above_panel` | 节点 15 / 870；Advisor DO+30 | 入口 38 行 + web 24 行 |
| task-queue | 任务队列 | java | TaskLifecycleNode + TaskAdmissionPolicy；RPC `task.queueList` | — | 节点 40 ⚠️ | 入口 31 行 |
| transient-error-retry | 瞬时错误退避重试 | java | AdvisorProvider | — | DO+200 | 入口 21 行 |
| unattended | 无人值守 | java | ToolExecutionInterceptor + AuthorizationHandler + SlashProvider + SlashTokenResolver | — | — | 入口 32 行 |
| update-file-view | 文件更新视图 | web | —（无 Java） | `ui.tool_call_views` | — | web 29 行 |

### 2.1 全体共性（读任何一个插件前先知道）

- 形态分布：java-only 18 / both 5 / web-only 3；**没有纯声明式插件**（每个都至少有一侧入口）。
- 清单字段全体一致：`id/name/version/description/author` + `enabled`，version 一律 `0.1.0`、author 一律 `everyagent`；含 Java 的 23 个另有 `main`，含前端的 9 个另有 `webMain`（值全部写 `web/index.ts`，宿主实际加载的是构建产物 `web/index.js`，见 [plugin.json 字段参考](../plugin-manifest.md) §5）。
- 用 `contributes.config` 的只有 2 个：image-vision（3 键）、sandbox-windows-codex（6 键）。
- `enabled:false` 的只有 2 个：sandbox-windows-mic、sandbox-wsl-ubuntu（内置扫描期整目录跳过）。
- 依赖红线干净：全部 23 个 pom **零 `every-agent-worker` 依赖**；`task-edit-resend`、`task-input-queue` 额外依赖 `every-agent-contract` 属共享契约层，合规（[架构文档](../../ARCHITECTURE.md) §14.9）。
- 全部插件源码零 Spring 容器注解：插件经 `URLClassLoader` 加载、不在 Spring 容器中，取服务只能 `ctx.services()` / `getService(Class)`。
- 每个 Java 入口都实现 `dev.everyagent.plugin.api.EveryAgentPlugin` 的 `activate(WorkerPluginContext ctx)`；改完 Java 要重跑 `mvn package`、改完 web 要重跑 `npm run build:plugins`（均需重启 worker，见 [构建与运行](../guides/build-and-run.md)）。

## 3. 「想写 X 就抄 Y」反查表

按扩展点组织。「推荐范本」优先选**体量最小、最典型**的插件；一行一句话说明为什么。

### 3.1 后端 SPI（`WorkerPluginContext` 10 个）

| 想实现 | 推荐范本 | 源码相对路径 | 为什么推荐 |
|---|---|---|---|
| ToolProvider（给模型加工具） | sandbox-windows-mic | `every-agent-plugins/sandbox-windows-mic/src/main/java/dev/everyagent/plugin/sandbox/mic/WindowsMicSandboxPlugin.java:31` | 入口仅 34 行，注册即用；更典型的工具型实现看 subagent（`SubAgentPlugin.java:31`） |
| AdvisorProvider（请求/响应链增强） | system-info | `every-agent-plugins/system-info/src/main/java/dev/everyagent/plugin/sysinfo/SystemInfoAdvisorProvider.java` | 全仓最小 Advisor 范例（入口 19 行），只做请求前 system prompt 注入 |
| SandboxProvider（自定义沙箱） | sandbox-windows-mic | `every-agent-plugins/sandbox-windows-mic/src/main/java/dev/everyagent/plugin/sandbox/mic/WindowsMicSandboxProvider.java:38-39` | 三实现中依赖最少（仅 jna）；优先级取值坐标参考 sandbox-wsl-ubuntu（priority=10） |
| SearchProvider | **无内置范例** | —（声明：`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerPluginContext.java:51`） | 内置插件零使用，别照抄空气；SPI 已接线（统一 `search` 聚合——内置 `file-content`/`file-name`/`task` 三引擎本身就是内置 provider、与插件 provider 同权，见 [advisors §6](../backend/advisors.md)） |
| AuthorizationHandler（接管授权闸门） | unattended | `every-agent-plugins/unattended/src/main/java/dev/everyagent/plugin/unattended/UnattendedPlugin.java:23` | 逻辑最短；带评审模型的进阶版看 ai-review（`AiReviewPlugin.java:24`） |
| ToolExecutionInterceptor（拦截工具调用） | secret-redaction | `every-agent-plugins/secret-redaction/src/main/java/dev/everyagent/plugin/secretredaction/SecretRedactionPlugin.java:22` | 唯一「单 SPI 极简」实现（入口 24 行），上行掩码单向拦截 |
| SkillContributor（贡献技能） | subagent | `every-agent-plugins/subagent/src/main/java/dev/everyagent/plugin/subagent/SubAgentPlugin.java:44` | 全仓唯一实现，别无分号 |
| TokenEstimator（token 估算） | model-rate-limit | `every-agent-plugins/model-rate-limit/src/main/java/dev/everyagent/plugin/modelratelimit/ModelRateLimitPlugin.java:35` | 全仓唯一实现（BuiltinTokenEstimator），同时示范估算器被 Advisor 消费的接线 |
| ChatModelEnhancer（模型增强/容灾） | model-pool | `every-agent-plugins/model-pool/src/main/java/dev/everyagent/plugin/modelpool/ModelPoolPlugin.java:26` | 全仓唯一实现：组合 ChatModel 做主备切换 |
| FileReferenceHandler（@ 文件引用处理） | image-vision | `every-agent-plugins/image-vision/src/main/java/dev/everyagent/plugin/imagevision/ImageVisionPlugin.java:30` | 全仓唯一实现，且是 `contributes.config` → `ctx.config()` 消费链的活样本 |

### 3.2 后端通用注册点（RPC + slash）

| 想实现 | 推荐范本 | 源码相对路径 | 为什么推荐 |
|---|---|---|---|
| `registerRpcMethod`（注册 1 个方法） | task-queue | `every-agent-plugins/task-queue/src/main/java/dev/everyagent/plugin/taskqueue/TaskQueuePlugin.java:29` | 一行注册 + 一个 handler，最小闭环；**批量**注册 13 个方法看 git（`GitPlugin.java:42-54`，方法名常量集中在 `GitRpcMethods.java:4-16`） |
| `registerSlashProvider`（slash 命令项） | unattended | `every-agent-plugins/unattended/src/main/java/dev/everyagent/plugin/unattended/UnattendedPlugin.java:26-27` | 4 个使用者中最小的一个；items 工厂 + 命令项 id 命名范式齐全 |
| `registerSlashTokenResolver`（slash 记录消解） | unattended | `every-agent-plugins/unattended/src/main/java/dev/everyagent/plugin/unattended/UnattendedPlugin.java:30` | 与 SlashProvider 成对出现（4 插件全部成对），配套看同一文件即可 |

### 3.3 后端任务侧注册点（`TaskPluginContext` 2 个）

| 想实现 | 推荐范本 | 源码相对路径 | 为什么推荐 |
|---|---|---|---|
| `registerTaskAdmissionPolicy`（任务准入策略） | task-queue | `every-agent-plugins/task-queue/src/main/java/dev/everyagent/plugin/taskqueue/TaskQueuePlugin.java:25` | 全仓唯一使用者 |
| `registerTaskLifecycleNode`（任务生命周期节点） | task-edit-resend | `every-agent-plugins/task-edit-resend/src/main/java/dev/everyagent/plugin/editresend/EditResendNode.java` | 单节点、职责单一（order=877）；多节点编排（15/870 双节点）看 task-input-queue，try/finally 成对形态看 task-queue 的 `QueueAdmissionNode` |

### 3.4 前端扩展点（13 个）

| 想实现 | 推荐范本 | 源码相对路径 | 为什么推荐 |
|---|---|---|---|
| `ui.sidebar_items`（活动栏项） | plugin-manager | `every-agent-plugins/plugin-manager/web/index.ts:18-25` | web-only 最小范本：一个 `registerSidebarItem` + `ctx.sdk.rpc` 调后端；带 Badge/图标/多面板的进阶版看 git |
| `ui.workspace_tab_types`（工作区标签类型） | git | `every-agent-plugins/git/web/index.ts:39` | 唯一使用者；配套 `ctx.ui.openPluginTab` / `openDiffTab` 动作方法可一并抄 |
| `ui.file_sidebar_panels` | **无内置范例** | — | 扩展点已声明但零使用，接线情况见 [已知问题](known-issues.md) |
| `ui.composer_above_panel`（输入框上方面板） | task-input-queue | `every-agent-plugins/task-input-queue/web/index.ts:17-20` | 三行注册 + 面板组件全交互（rpc 拉快照 / 广播刷新）齐全 |
| `ui.tool_call_views`（工具调用视图接管） | update-file-view | `every-agent-plugins/update-file-view/web/index.ts:20-25` | 唯一使用者，整体接管 `update_file` 工具的渲染 |
| `ui.user_message_actions`（用户消息动作） | task-edit-resend | `every-agent-plugins/task-edit-resend/web/index.ts:18-21` | 唯一使用者；配套 `setComposerRawContent` / `appendComposerText` 回写输入框 |
| `task.submit_contributions`（提交期贡献） | task-edit-resend | `every-agent-plugins/task-edit-resend/web/index.ts:22` | 唯一使用者，与后端节点配合完整链路 |
| `ui.trace_types`（trace 渲染类型） | ai-review | `every-agent-plugins/ai-review/web/index.ts:16-36` | 唯一使用者，descriptor 定义 + 注册一体 |
| `ui.output_blocks` | **无内置范例** | — | 扩展点已声明但零使用，见 [已知问题](known-issues.md) |
| `ui.file_content_editors`（文件内容编辑器） | pdf-viewer | `every-agent-plugins/pdf-viewer/web/index.ts:14-19` | 唯一使用者 + web-only 形态最小样本（无 pom 无 src） |
| `ui.file_explorer_actions`（文件树右键动作） | git | `every-agent-plugins/git/web/index.ts:42-53` | 唯一注册者；动作追加到文件树右键菜单内置项尾部（[UI §13](../web/ui-extensions.md)） |
| `ui.round_tail_panels`（轮次尾面板） | file-change | `every-agent-plugins/file-change/web/index.ts:22` | 唯一使用者；配套 `ctx.events.on('task-round-closed')` 缓存作废范式 |
| 扩展点之外的自绘全局 UI（悬浮球等） | mobile-keyboard | `every-agent-plugins/mobile-keyboard/web/floatball.ts` | 唯一直接操作 `document.body` 的插件：13 个扩展点没有全局覆盖层位时，挂自有根元素 + 自管生命周期与可见性 |

另有非 register\* 的服务通道 `WorkerServices.addRoundClosedListener`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerServices.java:80`），唯一使用者 file-change（`FileChangePlugin.java:33`）。

## 4. order 值占用速查

新插件选值请**避开已占用档位并留出语义间距**（重排 Advisor 链/生命周期链的相对位置才是 order 的全部意义）。HP = `Ordered.HIGHEST_PRECEDENCE`；DO = `ToolCallingAdvisor.DEFAULT_ORDER`（= HP+300，`spring-ai-client-chat` 2.0.1 实测）。

### 4.1 后端 Advisor（`AdvisorProvider.order()`，int）

| 值 | 表达式 | 占用者 | 来源 |
|---|---|---|---|
| −2147483598 | HP+50 | system-info | `every-agent-plugins/system-info/src/main/java/dev/everyagent/plugin/sysinfo/SystemInfoAdvisorProvider.java:38-39` |
| −2147483588 | HP+60 | agents-md | `every-agent-plugins/agents-md/src/main/java/dev/everyagent/plugin/agentsmd/AgentsMdAdvisorProvider.java:23-24` |
| −2147483508 | HP+140 | git（自动同步） | `every-agent-plugins/git/src/main/java/dev/everyagent/plugin/git/GitAutoSyncAdvisorProvider.java:28-29` |
| −2147483348 | — | （基准值）`ToolCallingAdvisor.DEFAULT_ORDER` | spring-ai-client-chat 2.0.1（javap 实测） |
| −2147483347 | HP+301 | file-change | `every-agent-plugins/file-change/src/main/java/dev/everyagent/plugin/filechange/FileChangeAdvisorProvider.java:45-46` |
| −2147483318 | DO+30 | task-input-queue（对话插入） | `every-agent-plugins/task-input-queue/src/main/java/dev/everyagent/plugin/inputqueue/DialogInsertAdvisorProvider.java:34` |
| −2147483248 | DO+100 | empty-response-retry | `every-agent-plugins/empty-response-retry/src/main/java/dev/everyagent/plugin/emptyretry/EmptyResponseRetryAdvisorProvider.java:30-31` |
| −2147483148 | DO+200 | transient-error-retry | `every-agent-plugins/transient-error-retry/src/main/java/dev/everyagent/plugin/transientretry/TransientErrorRetryAdvisorProvider.java:30-31` |
| −2147483098 | DO+250 | adaptive-max-tokens | `every-agent-plugins/adaptive-max-tokens/src/main/java/dev/everyagent/plugin/adaptivemaxtokens/AdaptiveMaxTokensAdvisorProvider.java:37-38` |
| −2147483048 | DO+300 | model-length-guard | `every-agent-plugins/model-length-guard/src/main/java/dev/everyagent/plugin/modellengthguard/ModelLengthGuardAdvisorProvider.java:38-39` |
| −2147482948 | DO+400 | context-compression | `every-agent-plugins/context-compression/src/main/java/dev/everyagent/plugin/contextcompression/ContextCompressionAdvisorProvider.java:30-31` |
| −2147482848 | DO+500 | model-rate-limit（限流） | `every-agent-plugins/model-rate-limit/src/main/java/dev/everyagent/plugin/modelratelimit/RateLimitAdvisorProvider.java:31-32` |
| 0 | — | model-rate-limit（token 校准） | `every-agent-plugins/model-rate-limit/src/main/java/dev/everyagent/plugin/modelratelimit/TokenCalibrationAdvisor.java:180-181` |

### 4.2 后端 TaskLifecycleNode（`order()` 为 float）

| 值 | 占用者 | 来源 |
|---|---|---|
| 15 | task-input-queue / QueueDispatchNode（RPC 线程阶段） | `every-agent-plugins/task-input-queue/src/main/java/dev/everyagent/plugin/inputqueue/QueueDispatchNode.java:46` |
| 40 | task-queue / QueueAdmissionNode ⚠️ 类注释写 250 | `every-agent-plugins/task-queue/src/main/java/dev/everyagent/plugin/taskqueue/QueueAdmissionNode.java:31`（陈旧注释 `:12`） |
| 870 | task-input-queue / QueueLoopNode（临界段内侧轮次循环） | `every-agent-plugins/task-input-queue/src/main/java/dev/everyagent/plugin/inputqueue/QueueLoopNode.java:54` |
| 877 | task-edit-resend / EditResendNode | `every-agent-plugins/task-edit-resend/src/main/java/dev/everyagent/plugin/editresend/EditResendNode.java:32` |
| 950 | subagent / SubAgentSpawnedAwaitNode（收口前等待） | `every-agent-plugins/subagent/src/main/java/dev/everyagent/plugin/subagent/SubAgentSpawnedAwaitNode.java:29` |

### 4.3 后端 SandboxProvider（`priority()`，int，大者优先）

| 值 | 占用者 | 来源 |
|---|---|---|
| 10 | sandbox-wsl-ubuntu | `every-agent-plugins/sandbox-wsl-ubuntu/src/main/java/dev/everyagent/plugin/sandbox/wslubuntu/WslUbuntuSandboxProvider.java:81-82` |
| 8（非 Windows 为 0） | sandbox-windows-codex | `every-agent-plugins/sandbox-windows-codex/src/main/java/dev/everyagent/plugin/sandbox/codex/CodexSandboxProvider.java:47,70-71` |
| 5 | sandbox-windows-mic | `every-agent-plugins/sandbox-windows-mic/src/main/java/dev/everyagent/plugin/sandbox/mic/WindowsMicSandboxProvider.java:38-39` |

### 4.4 前端侧边栏 order（float 升序，缺省 100）

| 值 | 占用者 | 来源 |
|---|---|---|
| 1 | 内置 tasks | `every-agent-web/src/components/app/Layout.tsx:1075` |
| 2 | 内置 files | `every-agent-web/src/components/app/Layout.tsx:1076` |
| 3 | 内置 search | `every-agent-web/src/components/app/Layout.tsx:1077` |
| 5 | git「源代码管理」 | `every-agent-plugins/git/web/index.ts:35` |
| 9 | plugin-manager「扩展」 | `every-agent-plugins/plugin-manager/web/index.ts:24` |
| 10 | 内置 settings | `every-agent-web/src/components/app/Layout.tsx:1078` |
| 100（缺省） | `DEFAULT_SIDEBAR_ORDER` | `every-agent-web/src/components/app/Layout.tsx:1064` |

其余前端扩展点不用 order（「插件在前」合并顺序）；composer 面板、trace 类型、工具视图等均无 order 字段。

## 5. 每插件速览

以下 `~plugins/` = `every-agent-plugins/`；⚠️ 标记 = 调研中发现的异常/陈旧点，一律**以代码为准**。每小节 = 一句话职责 + 关键文件 + 值得看的点。

### adaptive-max-tokens（自适应输出预算）

按上下文余量自适应改写输出预算（流式改写 ChatOptions 后重试）。
- 关键文件：`~plugins/adaptive-max-tokens/src/main/java/dev/everyagent/plugin/adaptivemaxtokens/AdaptiveMaxTokensAdvisorProvider.java:37-38`（order=DO+250）。
- 值得看：流式场景改写 ChatOptions 的重试型 Advisor。

### agents-md（agents.md 约束注入）

把工作区 `agents.md` 约束注入 system prompt。
- 关键文件：`~plugins/agents-md/src/main/java/dev/everyagent/plugin/agentsmd/AgentsMdAdvisorProvider.java:23-24`（order=HP+60）。
- 值得看：BaseAdvisor 请求前改 prompt 的最小写法。

### ask-user（用户提问）

提供 `ask_user` 工具：向用户提出单选题列表并阻塞等待回答，超时读 worker 配置 `worker.limits.ask-timeout-ms`。
- 关键文件：`~plugins/ask-user/src/main/java/dev/everyagent/plugin/askuser/AskUserPlugin.java`；工具本体 `AskUserTool.java`（自 worker 内置工具迁出，行为零变化）。
- 值得看：**纯 ToolProvider 型 java-only 插件**最小样本；主/子 agent 均注册（appliesTo 恒真）；工具内部经 `ctx.interaction().ask()` 发起提问、虚拟线程挂起等待。

### ai-review（AI 审议）

把授权询问升级为「AI 先审议再放行」，并提供 slash 开关与 trace 渲染。
- 关键文件：`~plugins/ai-review/src/main/java/dev/everyagent/plugin/aireview/AiReviewPlugin.java:24-31`；`~plugins/ai-review/web/index.ts:36`。
- ⚠️ `web/index.ts:4` 注释引用已删除的 `builtInPlugins.ts`（陈旧注释，全文共 4 处，见下）。
- 值得看：AuthorizationHandler + slash 全家桶 + `ui.trace_types` 三合一。

### context-compression（上下文压缩）

继承 `ToolCallingAdvisor` 的上下文压缩 Advisor。
- 关键文件：`~plugins/context-compression/src/main/java/dev/everyagent/plugin/contextcompression/ContextCompressionAdvisorProvider.java:30-31`（order=DO+400）。
- 值得看：按红线「继承 ToolCallingAdvisor 重写受保护 hook」实现的样板（不自建循环）。

### empty-response-retry（空响应重试）

空响应自动重试（Call+Stream 双链）。
- 关键文件：`~plugins/empty-response-retry/src/main/java/dev/everyagent/plugin/emptyretry/EmptyResponseRetryAdvisorProvider.java:30-31`（order=DO+100）。
- 值得看：同一 Advisor 兼容两种调用链的写法。

### file-change（文件变更跟踪）

跟踪轮次内文件变更并落盘插件私有数据，前端出轮次尾面板（入口 132 行，两侧都值得通读）。
- 关键文件：`~plugins/file-change/src/main/java/dev/everyagent/plugin/filechange/FileChangePlugin.java:30-36`；`~plugins/file-change/web/index.ts:22`。
- 值得看：`task.fileChanges` RPC 读插件私有数据（[持久化与插件私有状态](../backend/persistence-and-state.md)的 §7.15.2 范本）；`addRoundClosedListener` 非 register\* 服务通道的全仓唯一使用者；`ui.round_tail_panels` 唯一使用者。

### git（Git 操作）

13 个 `git.*` RPC + 自动同步 Advisor + 完整 Web 界面（侧边栏 / 历史标签 / diff），both 形态的集大成者。
- 关键文件：`~plugins/git/src/main/java/dev/everyagent/plugin/git/GitPlugin.java:33-54`；`~plugins/git/web/index.ts:28-53`。
- ⚠️ `web/index.ts:2` 注释自称「纯 Web 插件」，实际 plugin.json 有 `main`（both 形态）；⚠️ `web/index.ts:4` 引用已删除的 `builtInPlugins.ts`；`SidebarItem.Badge`（GitChangeBadge）与 `ui.file_explorer_actions`（git-show-history）两处贡献均已接线生效（known-issues #1/#2 已修复）。
- 值得看：`registerRpcMethod` 批量注册（方法名常量集中 `GitRpcMethods.java:4-16`）；前端 `ctx.fs.listDir/delete`、`ctx.sdk.workspace` 的用法。

### image-vision（图片识别）

`@` 引用图片 → 压缩 → base64 dataURL 注入视觉模型。
- 关键文件：`~plugins/image-vision/src/main/java/dev/everyagent/plugin/imagevision/ImageVisionPlugin.java:29-30`；`~plugins/image-vision/plugin.json:8-26`。
- 值得看：**唯一实现 FileReferenceHandler 的插件**；`contributes.config` 3 键 → `ctx.config()` 的消费链活样本。

### mobile-keyboard（移动端键盘增强）

移动端 + 终端界面（激活标签 `tabType === 'terminal'`）时，显示 AssistiveTouch 风格悬浮球，点开是方向键小键盘，按键以合成 KeyboardEvent 派发给**当前焦点元素**（交给浏览器事件流，不绑定任何具体组件）；web-only 形态。
- 关键文件：`~plugins/mobile-keyboard/web/index.ts`（入口）、`~plugins/mobile-keyboard/web/floatball.ts`（悬浮球控制器）、`~plugins/mobile-keyboard/web/floatball.css`（样式，经 esbuild 抽取注入）。
- 值得看：**扩展点之外的自绘全局 UI** 全仓唯一范例——悬浮球拖拽/边缘吸附/位置持久化（`ctx.storage`）、「`ctx.ui.getActiveTab()` 标签类型 + 视口 ≤768px」双条件显隐（`workspace-tab-activated`/`workspace-tab-closed` + resize，**不扫 DOM 猜组件**）；按键模拟是「**不管目标是什么组件**」的通用姿势——把带 legacy `keyCode` 的合成 `KeyboardEvent`（keydown + keyup）派发给 `document.activeElement`（无焦点落 `body`），监听方自行消费（xterm 持有焦点时其 textarea 即 activeElement，生成 `\x1b[A/B/C/D` 转义序列发往 PTY；xterm 不检查 `isTrusted`，已核实其 keydown 链路），没人消费就什么都不会发生。注意平台限制：合成事件不触发浏览器**默认行为**（往 input 插入字符等），将来扩展文本类按键需另配 `execCommand` 兜底。

### model-length-guard（模型输出预算耗尽护栏）

补帧后检测输出预算耗尽并抛异常护栏。
- 关键文件：`~plugins/model-length-guard/src/main/java/dev/everyagent/plugin/modellengthguard/ModelLengthGuardAdvisorProvider.java:38-39`（order=DO+300）。
- 值得看：护栏型 Advisor——失败也要把原因带回上层，而不是静默截断。

### model-pool（模型池容灾）

多模型池主备容灾。
- 关键文件：`~plugins/model-pool/src/main/java/dev/everyagent/plugin/modelpool/ModelPoolPlugin.java:26`。
- 值得看：**唯一实现 ChatModelEnhancer 的插件**，组合 ChatModel 的范本。

### model-rate-limit（模型限流）

token 估算 + 校准 Advisor + 限流 Advisor 三件套。
- 关键文件：`~plugins/model-rate-limit/src/main/java/dev/everyagent/plugin/modelratelimit/ModelRateLimitPlugin.java:35-44`；order 见 `TokenCalibrationAdvisor.java:180-181`（0）与 `RateLimitAdvisorProvider.java:31-32`（DO+500）。
- 值得看：**同插件注册多个 AdvisorProvider** 的全仓唯一范例；TokenEstimator 唯一实现，示范估算器被 Advisor 消费的接线。

### pdf-viewer（PDF 预览）

`.pdf` 文件内容编辑器；web-only 形态（无 pom、无 src）。
- 关键文件：`~plugins/pdf-viewer/web/index.ts:18`。
- ⚠️ `web/index.ts:4` 引用已删除的 `builtInPlugins.ts`。
- 值得看：web-only 形态 + `ui.file_content_editors` 双唯一样本。

### plugin-manager（插件管理）

「扩展」侧栏列表 + 扩展详情标签页；web-only 形态。
- 关键文件：`~plugins/plugin-manager/web/index.ts`（注册侧栏项 + `extension-detail` 标签类型）；列表 `PluginManagerPanel.tsx`、详情页 `ExtensionDetailPage.tsx`、共享 store `pluginStore.ts`。
- 值得看：web-only 插件经 `ctx.sdk.rpc` 调 `plugin.list/enable/disable` 的最小完整闭环；`ctx.ui.registerWorkspaceTabType` + `openPluginTab` 打开详情标签页（宿主按 `data.id` 去重聚焦）的完整样本。

### sandbox-windows-codex（Windows Codex 沙箱）

Windows Codex 沙箱 + bash 工具。
- 关键文件：`~plugins/sandbox-windows-codex/src/main/java/dev/everyagent/plugin/sandbox/codex/CodexSandboxPlugin.java:47,51`；priority 8/0 见 `CodexSandboxProvider.java:47,70-71`。
- 值得看：`contributes.config` 6 键的最大样本；内置插件里依赖最多的 pom（jna / jackson / slf4j 等共 7 项）。

### sandbox-windows-mic（Windows MIC 沙箱）

Windows 受限令牌沙箱 + shell 工具。
- 关键文件：`~plugins/sandbox-windows-mic/src/main/java/dev/everyagent/plugin/sandbox/mic/WindowsMicSandboxPlugin.java:25,31`；priority 5 见 `WindowsMicSandboxProvider.java:38-39`。
- 值得看：**SandboxProvider + ToolProvider 最小组合**；仓内两个 `enabled:false` 样本之一。

### sandbox-wsl-ubuntu（WSL Ubuntu 沙箱）

WSL Ubuntu 沙箱 + bash 工具 + network slash 开关。
- 关键文件：`~plugins/sandbox-wsl-ubuntu/src/main/java/dev/everyagent/plugin/sandbox/wslubuntu/WslUbuntuSandboxPlugin.java:37-45`；priority 10 见 `WslUbuntuSandboxProvider.java:81-82`。
- 值得看：priority 坐标最外侧的取值参照；沙箱插件里唯一带 slash 全家桶的。

### secret-redaction（凭据输出脱敏）

工具输出上行前掩码凭据。
- 关键文件：`~plugins/secret-redaction/src/main/java/dev/everyagent/plugin/secretredaction/SecretRedactionPlugin.java:22`（入口 24 行）。
- 值得看：**单 SPI 极简 ToolExecutionInterceptor**，学拦截器先抄它。

### subagent（子 Agent）

子 Agent 工具 + 技能贡献 + 收口等待节点。
- 关键文件：`~plugins/subagent/src/main/java/dev/everyagent/plugin/subagent/SubAgentPlugin.java:31-44`；节点 order=950 见 `SubAgentSpawnedAwaitNode.java:29`。
- 插件**已无 web 前端功能**：原 `ui.composer_above_panel` 子 agent 面板退役，agent 胶囊列表由 web 核心 `TaskChat` + `AgentListPanel` 渲染；`task.agents` RPC 已收回 task 域（`TaskManager` 注册），不再由本插件提供。
- 值得看：一插件多 SPI 组合；主/子 Agent 共用同一运行入口的对接点。

### system-info（系统环境信息注入）

注入 OS / JDK 等系统环境信息。
- 关键文件：`~plugins/system-info/src/main/java/dev/everyagent/plugin/sysinfo/SystemInfoAdvisorProvider.java:38-39`（order=HP+50；入口仅 19 行）。
- 值得看：**全仓最小 AdvisorProvider 范例**，最早位的 system prompt 注入。

### task-edit-resend（编辑重发）

编辑用户消息后重发任务。
- 关键文件：`~plugins/task-edit-resend/src/main/java/dev/everyagent/plugin/editresend/EditResendNode.java:32`（order=877）；`~plugins/task-edit-resend/web/index.ts:18-22`。
- 值得看：`ui.user_message_actions` + `task.submit_contributions` 双唯一使用者，前后端配合的最小完整链路。

### task-input-queue（任务输入队列）

输入框排队待发（多轮意图排成队列逐个派发）。
- 关键文件：`~plugins/task-input-queue/src/main/java/dev/everyagent/plugin/inputqueue/TaskInputQueuePlugin.java:27-35`；order 见 `QueueDispatchNode.java:46`（15）、`QueueLoopNode.java:54`（870）、`DialogInsertAdvisorProvider.java:34`（DO+30）。
- 值得看：**TaskLifecycleNode 多节点编排**；面板直连自家 3 个 RPC、以 `task.updated` 广播为刷新信号的写法。

### task-queue（任务队列）

任务级并发排队等待。
- 关键文件：`~plugins/task-queue/src/main/java/dev/everyagent/plugin/taskqueue/TaskQueuePlugin.java:24-29`。
- ⚠️ `QueueAdmissionNode.java:12` 类注释写 `order=250`，代码实际 `return 40`（`:31`），**以代码为准**（本表 §4.2 已按 40 登记）。
- 值得看：`TaskAdmissionPolicy` 全仓唯一使用者；try/finally 成对节点形态；java-only 最小全功能样本。

### transient-error-retry（瞬时错误退避重试）

瞬时错误指数退避重试。
- 关键文件：`~plugins/transient-error-retry/src/main/java/dev/everyagent/plugin/transientretry/TransientErrorRetryAdvisorProvider.java:30-31`（order=DO+200）。
- 值得看：最内层重试 Advisor 的退避节奏控制。

### unattended（无人值守）

无人值守模式（自动放行授权 + 拦截交互式工具）。
- 关键文件：`~plugins/unattended/src/main/java/dev/everyagent/plugin/unattended/UnattendedPlugin.java:20-30`。
- 值得看：**一插件注册 4 种扩展点**的组合示范；slash 全家桶最小实现（入口 32 行）。

### update-file-view（文件更新视图）

接管 `update_file` 工具调用的渲染；web-only 形态。
- 关键文件：`~plugins/update-file-view/web/index.ts:25`。
- ⚠️ `web/index.ts:4` 引用已删除的 `builtInPlugins.ts`。
- 值得看：`ui.tool_call_views` 唯一样本，含 `ctx.ui.openFileTab` 联动。

## 6. 下一步读

- 各注册方法与类型的完整清单：[API 一屏索引](api-index.md)
- 把范例跑起来（构建命令矩阵、cwd 陷阱、`build:plugins`）：[构建与运行](../guides/build-and-run.md)
- Advisor 链与 order 语义详解：[Advisor 指南](../backend/advisors.md)
- 前端 13 个扩展点的注册签名与渲染管线：[UI 扩展点](../web/ui-extensions.md)

---

**数据口径**：本篇所有注册调用、RPC 方法名、order/priority 数值、行号均于成文时对源码逐行复核（含 git、file-change、task-queue、model-rate-limit、subagent、pdf-viewer、plugin-manager、task-input-queue 等关键行抽查）；行数为入口文件当前值，会随后续提交漂移，定位以文件名 + 方法名为准。
