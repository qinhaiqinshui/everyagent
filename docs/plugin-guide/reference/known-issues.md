---
title: 已知问题与开放项
nav_order: 19
parent: reference
has_children: false
---

**一句话定位**：本页是插件系统**现状偏差的集中登记簿**——23 条已核实的问题与开放项（**19 条已修复**：#1~#5、#8~#17、#19、#21~#23；#6/#7 已缓解；#18/#20 开放——前者需发布凭证、后者需真机实测），每条按「现象 / 影响 / 修复·规避 / 待办」成文，是判断「这是我的 bug 还是宿主的坑」的第一站。

## 如何读这张表

- **只登记事实，不评判设计**。每条「现象」都附证据（相对仓库根路径 + 行号，以登记时仓库快照为准；行号会随后续提交漂移，以内容定位为准）。外部网络类事实（npm / Maven 404）标注「调查实测」，无法静态取证。
- **不重复正文**。某问题若在其余 18 篇已详述（多数带 ⚠️ 标记），本页只给一句话现象 + 链接，不展开机理。
- **「待办」只是建议**（已修复条目改为「修复」记录，链接指向修复后口径的正文）。
- 编号 **#1~#23** 全站连续（`[reference/api-index](api-index.md)` 的 ⚠️ 标记即指向本页）；#1~#5、#8~#17、#19、#21~#23 已修复，#6/#7 已缓解（登记在文末[已缓解项](#已缓解项)）。
- 分组：**A 前端宿主**（#1~#9）、**B 后端 worker**（#10~#17）、**C 分发与生态**（#18~#21）、**D 文档站自身**（#22~#23）。

## A. 前端宿主

### #1 `ui.file_explorer_actions` 是死扩展点（✅ 已修复）

- **现象（修复前）**：注册 API 与列举 API 齐全（`every-agent-web/src/plugin/PluginDispatcher.ts` 的 `listRegisteredFileExplorerActions` / `registerFileExplorerAction`），但宿主 UI **零消费**——`listRegisteredFileExplorerActions` 全仓只在定义处出现，没有任何界面调它；git 注册的 `git-show-history` 白注册（`every-agent-plugins/git/web/index.ts:42-53`）。
- **影响（修复前）**：插件作者按文档注册后**右键菜单不会出现任何项**，且无报错、无日志。
- **修复**：文件树右键菜单消费点接线——`OpenFilesSidebarPanel.tsx` 的 `getFileActionItems` 在内置菜单项之后追加插件注册项（按 `isVisible` 过滤），面板订阅扩展点版本号，注册/注销即时刷新；git 的「显示 Git 历史」右键项已生效。
- **后续**：无需规避；详见 [UI 扩展点](../web/ui-extensions.md) §13（已更新为接线后口径）。

### #2 `UiSidebarItemDefinition.Badge` 是死字段（✅ 已修复）

- **现象（修复前）**：类型包声明 `Badge?: ComponentType`（`every-agent-plugin-api/js/index.ts`），但 `buildSidebarActivityItems` 只读 `badgeCount`（`every-agent-web/src/components/app/Layout.tsx`）。
- **影响（修复前）**：git 的 `GitChangeBadge` 组件永不渲染（死贡献）；照抄 git 插件写 Badge 的新插件同样静默无效。
- **修复**：实现 Badge 消费——web 内部 `UiSidebarItemDefinition` 补 `Badge` 字段，`buildSidebarActivityItems` 透传、`SidebarActivityBar` 在活动栏图标内渲染（组件自管订阅与刷新，无变更返回 null）；git 的变更角标已生效。`badgeCount`（数字角标）通道保持不变。
- **后续**：两种角标写法都可用；详见 [UI 扩展点](../web/ui-extensions.md) §2。

### #3 `registerOutputBlock` / `registerTraceType` 返回的是假 Disposable（✅ 已修复）

- **现象（修复前）**：`registerOutputBlock` 的 dispose 只清**无人读**的 `outputBlocksMap`，真实消费方 `RichMessageContent.tsx` 走的 `outputBlockRegistry` **不清**；`registerTraceType` 返回的 disposable 只清主 registry，不清 `TaskThread.tsx` 消费的 `traceTypeRegistry` 侧路。
- **影响（修复前）**：调了 dispose 之后 UI 仍可能继续用旧 handler 渲染，造成「以为注销了其实还在」的错觉。
- **修复**：dispose 补齐侧路 unregister——`outputBlockRegistry` 新增 `unregister(tag)`、`traceTypeRegistry` 新增 `unregisterTraceType(kind)`，`PluginDispatcher` 两个注册方法的 Disposable 同时清理真实消费方；无人读的 `outputBlocksMap` 已删除。
- **后续**：两个扩展点的 dispose 均可依赖；[UI 扩展点](../web/ui-extensions.md) §10/§11/§16 已按新口径更新。

### #4 24 个零 emit 死事件 + `task-deleted` 死订阅陷阱（✅ 已修复）

- **现象（修复前）**：`DOMAIN_EVENTS` 常量表 38 个事件中仅 14 个有真实 emit，**24 个零 emit**；plugin-api 具名 9 个中 4 个死（`file-content-saved`/`task-created`/`task-deleted`/`task-trace-changed`）。内置插件的真实死订阅：file-change 订阅 `task-deleted` 作废缓存，但任务删除实际走 `taskStore.remove()` **不发事件**，该作废路径永不触发。`plugins-loaded` 全仓零 emit；`agent-run-event` 零 emit，且其注释引用的 `src/task/agentRunEventBridge.ts` **在仓库中不存在**。
- **影响（修复前）**：订阅死事件「能编译、能注册、永远不响」——`(string & {})` 兜底不拦截，是最易踩的前端陷阱。
- **修复**（清理 + 补 emit 双管齐下）：
  - **补齐 emit（3 个）**：`taskStore.remove()` 删除镜像成功即 emit `task-deleted`（file-change 的缓存作废路径接活）；worker `task.created` 帧入库后 emit `task-created`（首拉列表/翻页不触发）；`loadPlugins()` 每轮流程末尾 emit `plugins-loaded`（count=已装载总数）。
  - **清理被取代条目（17 个）**：`agent-updated`/`agent-message-appended`/`agent-message-streaming`/`task-trace-changed`/`task-token-usage-changed`/`task-context-monitor-changed`/`task-protocol-state-changed`/`file-content-saved`/`task-turn-started`/`task-turn-completed`/`agent-run-event`/`settings-llm-profiles-patched`/`settings-guardrail-patched`/`settings-prompt-templates-patched`/`app-notification-added`/`app-notification-removed`/`workspace-open-ai-call-log-requested` 连同 `DomainEventMap` 载荷一并删除；`AgentRunEvent`/`AgentRunEventPayload` 类型与幽灵文件引用（`agentRunEventBridge.ts`）随之移除；`ContextBattery` 的 `task-context-monitor-changed` 死订阅同步摘除（改经 `monitor` prop 镜像更新）。
  - plugin-api 具名清单同步：删 `file-content-saved`/`task-trace-changed`、增 `plugins-loaded`，8 个具名全部存活。
- **现状**：常量表 21 个事件 = 17 个真实 emit + 4 个「宿主监听、插件可反向 emit」的请求通道（3 个 `*-requested` + `runtime-config-error`），死事件陷阱整类消除。详见 [事件](../web/events.md) §3（两张表已按新口径重写）。

### #5 `webMain` 的值不被消费（路径硬编码）（✅ 已修复）

- **现象（修复前）**：宿主一律请求 `plugin.webSource{path:'web/index.js'}`，`ctx.extensionPath` 恒为 `'web/index.js'`；`plugin.json` 的 `webMain` 值只决定 `hasWebMain` 布尔位——改成别的路径完全无效，构建产物必须正好落在 `<id>/web/index.js`。
- **影响（修复前）**：`webMain` 字段语义名存实亡，是文档与清单约定的「表面可用」陷阱。
- **修复**：宿主真正消费 `webMain`——worker `plugin.list` 增发 `webMain` 原始值；前端 `pluginLoader` 新增 `webEntryJsPath()`（源码路径去扩展名拼 `.js`，空值/旧 worker 回退 `web/index.js`），`plugin.webSource` 请求路径、`ctx.extensionPath`、同名 CSS 注入路径全部改用换算结果。按约定写 `"web/index.ts"` 的插件行为不变。
- **后续**：非约定源码路径的插件也能被加载（产物须落在换算路径上）；详见 [plugin.json 字段参考](../plugin-manifest.md) §5 与[前端总览与加载链路](../web/overview-and-loading.md)（均按新口径更新）。

### #8 前端从不 dispose / deactivate 插件（✅ 已修复）

- **现象（修复前）**：`loadedPlugins` 的 `disposables` 恒为空数组（`pluginLoader.ts`）；`PluginModule.deactivate?` 类型存在但前端零调用。
- **影响（修复前）**：禁用插件（`plugin.disable`）后前端 UI 不会卸载其贡献，必须刷新页面；插件无法在前端做任何卸载清理。
- **修复**：`pluginLoader` 给激活期 `ctx.ui`/`ctx.commands`/`ctx.events` 各包一层 `trackDisposables` 收集代理（注册方法返回的 Disposable 全部进该插件的 `disposables`）；新增 `unloadPlugin`/`unloadAllPlugins`——先 `await module.deactivate()`，再逆序 dispose 全部注册项、移除插件 CSS，单点异常只 WARN。挂点选 `pagehide`（涵盖刷新/关闭/跳转，即插件面板「重新加载」按钮触发的 `location.reload()`），与后端 `@PreDestroy → deactivate` 对齐；**未做**「禁用即热卸载」——worker 侧 Java 贡献要到下次启动才摘除，前端单独摘会两侧不同步（提交 `a225651b`）。web 四篇文档已按新口径改写。
- **后续**：禁用/卸载的生效边界仍是「重启 worker + 刷新页面」；前端清理逻辑可放 `deactivate()`（页面卸载时会被调用）。详见[前端总览与加载链路](../web/overview-and-loading.md)与[事件](../web/events.md)。

### #9 陈旧注释与未完成 TODO（✅ 已修复）

- **现象（修复前）**：① 4 个插件注释仍引用**已删除**的 `builtInPlugins.ts`；② `git/web/index.ts` 自称「纯 Web 插件」，实际 plugin.json 有 `main`（both 形态）；③ `subagent/web/SubAgentListPanel.tsx` 为 TODO，面板尚未消费自家 `task.agents` RPC（当前渲染 null）。
- **修复**：①② 已更正——4 处头注释统一改为真实链路口径「经 worker `plugin.list` 发现、`plugin.webSource` RPC 拉取 esbuild 预编译产物动态加载」，git 插件自述改为 both 形态；rg 复核 `builtInPlugins` 全仓零残留（提交 `b416c3ac`）。③ 经核对**与代码现状一致**（面板仍渲染 null、`task.agents` 确未消费），属真实功能待办而非陈旧注释，按「宁缺毋滥」原则未动。
- **后续**：注释债已清零；③ 的 subagent 面板消费 `task.agents` 属功能开发，单独排期（反查台账见 [内置插件](builtin-plugins.md) §5）。

## B. 后端 worker

### #10 `SearchProvider` SPI 未接线（✅ 已修复）

- **现象（修复前）**：注册方法存在，registry 的 `getDefault`/`getById`/`getProviders` 在 worker 主代码**零调用**；25 个内置插件零注册；`docs/ARCHITECTURE.md` 也零提及该 SPI。
- **影响（修复前）**：注册 SearchProvider **没有任何运行期效果**（比死扩展点更彻底：连消费候选点都没有）。
- **修复**：`fs.search` / `task.search` 两条既有 RPC **增补聚合**——内置 rg 结果之后按注册序追加各 provider 结果，按位置键去重（文件 `path+lineNumber+matchIndex` / 任务 `taskId+roundIndex+field+matchIndex`），仍受 `maxResults` 触顶；注册表为空 → 零行为变化，单个 provider 异常仅 WARN 跳过，rg 不可用但有 provider 时可独立供数（SPI 真正可单独供数的路径）。**取舍**：消费链路选既有 RPC 而非 Advisor——搜索结果是给用户的、不是给模型上下文的；零新 RPC，`ARCHITECTURE.md` §7/§8.5 与 [Advisor 与模型链](../backend/advisors.md) §6、api-index、builtin-plugins 状态行同步。附带修正 SPI `TaskSearchResult.Match.line` 类型（`int`→`String`，与 wire「干净文本」语义一致；此前零实现无兼容负担）。提交 `aa936bd9`；`FsSearchServiceTest` 32 + `TaskSearchServiceTest` 16 全过（含 rg 缺失回退用例）。
- **后续**：未新增注册 SearchProvider 的示例内置插件（会与内置 rg 重复供数、无真实价值），聚合语义由单测钉住；将来做 search-es / search-vector 类插件时 SPI 契约已就绪。

### #11 `SkillContributor` 半接线（✅ 已修复）

- **现象（修复前）**：registry 的 `getSkills()` 只被 `SkillAdvisor.mergedSkills()` 消费（注入 system prompt）；`/` 菜单不含该 registry——subagent 的 skill 能进菜单，靠的是知识包**物化成文件**进 skillsDir 再被 `ExternalSkillScanner` 捞取。
- **影响（修复前）**：照 SPI 语义「贡献技能」的插件，技能只对模型可见、对用户 `/` 菜单不可见——两条通道不等价。
- **修复**：`SkillSlashProvider.load()` 改「内置 → 插件 SPI → 外部扫描」**三路合并**，同 id 去重、优先级 内置 > SPI > 外部（subagent 物化的同名条目不再重复出菜单，改以 SPI 声明的完整标题呈现）；出网**复用既有 `slash.list` RPC**，前端零改动——插件条目与内置同 group(Skills)/icon/`system.skill` opaque token（选中执行路径完全一致），仅副标题带「插件 · 」来源前缀；无插件贡献时条目序列与合并前逐项一致（零回归测试钉住）。`ARCHITECTURE.md` §7.17 三路口径与 [Advisor 与模型链](../backend/advisors.md) §5、api-index、persistence-and-state、task-and-rpc 同步。提交 `944176d1`；`SkillSlashProviderTest` 9/9 过。
- **后续**：物化文件到 skillsDir 仍是菜单的第三条合法通道（优先级最低）；有意为之的行为变化——subagent 在 `/` 菜单的展示由「目录名形态」变为「子 Agent + 插件 · 标记」，选中执行路径不变。

### #12 `TokenCalibrationAdvisor` 的 order=0 是绝对值错位（✅ 注释侧已修复）

- **现象（修复前）**：`order()` 返回 `0`——不是 `HP+N` 体系，数值上**大于全链所有** `HIGHEST_PRECEDENCE+N`，实际排在 Advisor 链**最内层**；而类注释自称「核心基础设施层、最外层」。
- **影响（修复前）**：模仿该注释设计 order 的插件会得到与预期**相反**的链位（校准器其实能看到所有出参，却看不到最早的入参改写）。
- **修复**：以代码实际行为为准更正三处注释（类 Javadoc「位置」段、`getOrder()` 行注释、`Provider` Javadoc）——绝对值 0 实际位于链**最内层**（紧贴模型调用，比 RateLimitAdvisor 的 HP+800 更内），响应方向最先看到每轮原始 chunk、请求方向最晚进入，并加「插件勿模仿绝对值写法，应一律用 `HP+N`/`TCA+N`」警示（提交 `1f9ef7cd`）。**order 代码值未动。**
- **后续（仍开放的代码侧选项）**：把绝对值 0 改为 `HP+N` 表达式会变更现网链位，需回归评估后择期；届时本次注释需同步复核。全链真实顺序以 [Advisor 与模型链](../backend/advisors.md) §2.2 的 order 表为准。

### #13 `deactivate()` 声明存在、全链零调用（✅ 已修复）

- **现象（修复前）**：`EveryAgentPlugin.java:25` 声明 `default void deactivate() {}`（对标 VSCode），worker 侧零调用；前端同样不调（见 #8）。
- **影响（修复前）**：后端插件没有停机钩子——worker 关闭、插件禁用、卸载时都无法执行清理（线程池、临时文件、监听器）。
- **修复**：`PluginLoader` 以 `@PreDestroy` 为销毁挂点，遍历「已成功激活」的插件逐个调用 `deactivate()`——激活失败、禁用名单命中、声明式插件一律跳过；单个插件停用抛异常只 WARN，不影响其余。运行期禁用/卸载仍不触发（「名单变更对下一次 worker 启动生效」语义不变）。接口 Javadoc、`PluginStateStore` 类注释、`ARCHITECTURE.md` §8.5 与后端总览/持久化/打包安装/构建运行等文档同步改为新口径（提交 `1553fd4f` 及收口提交）。
- **后续**：清理逻辑可放 `deactivate()`，但要接受异常退出（强杀/崩溃）时不会被调用；前端宿主仍不调（#8 仍开放）。详见[后端总览](../backend/overview.md) §3.2。

### #14 `QueueAdmissionNode` 注释写 250、代码是 40（✅ 已修复）

- **现象（修复前）**：类 Javadoc 写 `order=250，落在洋葱下行空隙 100~400 之间`，代码实际 `return 40`；注释引用的 `status.start`(300) 节点也已不存在。
- **影响（修复前）**：按注释推断洋葱位置会得出错误结论（40 实际排在 `queue.dispatch`(15) 之后很近的位置）。
- **修复**：Javadoc 更正为 order=40 并按 31 节点实测表标注真实链位（介于 `taskid.generate`(30) 与 `taskentry.create`(50) 之间），清除 `status.start` 引用；连带更正 `invoke()` 行注释、`TaskQueueAdmissionPolicy` 注释里的 250，以及 `docs/ARCHITECTURE.md` §7.14.4/§14.5 两处残留 250（提交 `d838e51d`、`641a5099` 及收口提交）。代码 order 值 40 未动。
- **后续**：无需规避；节点顺序以 [任务生命周期与 RPC](../backend/task-and-rpc.md) §2.3 的 31 节点实测表为准（该表已按 40 登记）。

### #15 `plugin.list` 的 `active` 语义失真、`status` 不出网（✅ 已修复）

- **现象（修复前）**：`active` 只反映 `.disabled-plugins` 文件状态（**激活失败也报 true**）；`LoadedPlugin.status` 真实记录 active/failed 等状态，从不序列化上网。
- **影响（修复前）**：前端插件管理面板无法区分「在跑」与「加载失败」，用户以为插件生效了。
- **修复**：`status` 走既有单一聚合链出网——`LoadedPlugin.status → PluginManifest.status`（record 增字段）→ `plugin.list` 新增 `status` 项（已激活 / 激活失败: … / 已禁用(未激活)）；`active` 一字未动保持旧语义，既有前端零改动即可读到真实状态（提交 `81982b44`）。
- **后续**：plugin-manager 面板消费 `status` 展示失败标识属后续增强；字段口径见[持久化与状态](../backend/persistence-and-state.md) §4.2/§4.3。

### #16 `enabled:false` 对外部插件不生效（只有内置扫描器读）（✅ 已修复）

- **现象（修复前）**：`ExternalPluginScanner.java` 内 `enabled|isEnabled` 零命中；`plugin.json` 的 `enabled` 字段只有 `BuiltInPluginScanner` 消费。
- **影响（修复前）**：外部插件想「装上但默认禁用」做不到——写 `enabled:false` 会被照常扫描加载。
- **修复**：`ExternalPluginScanner` 补读 `enabled`，为 false 时打 INFO 并整目录跳过，判定语义与内置扫描器对齐（缺省/解析失败视为 true）；plugin-manifest.md / persistence-and-state.md / backend/overview.md 中「两机制差异」表述同步统一（提交 `572b62be`）。
- **后续**：内外扫描器口径一致，`enabled:false` 即「装上但默认禁用」；`plugin.disable` RPC（`.disabled-plugins`）仍是运行期开关的另一机制。

### #17 `docs/ARCHITECTURE.md` 三处过时（文档欠账）（✅ 已修复）

- **现象（修复前）**：① §7.14.4 称插件以「`@Component` + 构造器注入（git `GitPluginRegistrar` 先例）」注册；② §7.14.1 写「内置节点 17 个」并列 `status.start(300)`/`queue.persist(700)`；③ `PluginLoader.java` 的 Javadoc 引用旧 §7.2/§7.3 编号。
- **影响（修复前）**：按架构文档写插件会走错路（最典型是期待 Spring 容器注入）。
- **修复**：①② 经专项复核确认已在先前提交 `e9bc0c5c` 更正为现行口径（`activate` + `ctx.register*`、`URLClassLoader` 装载、31 节点 + 指向插件指南全表），并把该提交漏掉的两处 250 残留一并清掉（`641a5099`）；③ 两处 Javadoc 统一更正指向 §8.5 插件系统（`ef01ff94`）。
- **后续**：以后端四篇为准：[后端总览](../backend/overview.md) §10（差异框）、[任务生命周期与 RPC](../backend/task-and-rpc.md) §2.3（31 节点表）。

## C. 分发与生态

### #18 `@everyagent/plugin-api` 双侧未发布（npm 404 / Maven 无 dev.everyagent）

- **现象**：npm registry 查 `@everyagent/plugin-api` → 404；Maven Central 无 `dev/everyagent` group（2025 调查实测，见计划记录 `.everyagent/plan-plugin-scaffold-docs.md`「已核实事实」）。本地包版本：js 与 Java 两侧已对齐 `1.0.0`（#23 收口）。
- **影响**：**仓库外**开发插件拿不到类型包与 API jar——这是插件生态的硬阻塞项。
- **规避**：脚手架 `standalone` 模式自动生成 `vendor/everyagent-plugin-api.d.ts` 类型副本（[快速上手](../getting-started.md)）；Java 侧在仓库内 `mvn install` 到本地仓库后引用。
- **待办**：发布双包（npm + Maven Central）；版本口径已就绪（#23 已收口对齐 1.0.0）。**优先级最高**（见文末 Top5）。

### #19 `plugin.install` / `plugin.uninstall` 前端零调用点（✅ 安装入口已修复）

- **现象（修复前）**：`every-agent-web/src` 对这两个 RPC **零调用**；插件管理面板只用 `plugin.list`/`plugin.enable`/`plugin.disable`；且 `{path}` 要求 worker 机器本地路径，浏览器端难以直传。
- **影响（修复前）**：`.eap` 包安装没有产品化入口——RPC 在协议里活着，但用户够不着。
- **修复**：面板顶栏新增「安装…」按钮（隐藏 `<input type=file accept=".eap">`，上限 32MB）→ 读字节转 base64 → `fs.write` 落工作区暂存目录 `.everyagent/plugin-install/` → 以暂存文件的机器绝对路径调**既有** `plugin.install` RPC → 尽力 `fs.delete` 清理 → 刷新列表（零后端改动；「worker 机器路径」交互经 fs.write 上传通道解决，`pastedFileService` 同款先例）。同时消费 #15 的 `status` 字段：激活失败的插件行内显示「加载失败」Tag（tooltip 带完整原因）。typecheck/build/`build:plugins` 全过（提交 `099927d2`）；[打包与安装](../guides/packaging-and-install.md) 安装路线已更新。
- **后续**：`plugin.uninstall` 仍无前端入口（涉及内置/外部区分与误删防护，单独排期）；安装链路的浏览器端到端实测未跑（静态验证 + 与既有先例同款调用姿势为准）。

### #20 desktop 分发内置插件：链路存在，端到端未实测

- **现象**：桌面打包链含插件构建与拷贝（`every-agent-desktop/package.json:11` `build:plugins`（Python 包装）、`:12` `copy:plugins`（`copy-plugins.mjs`）、`:16` `build:assets` 串联）。
- **影响**：**未知**——最终安装包里是否正确包含 `every-agent-plugins` 产物、桌面版 worker 启动后内置插件是否激活，从未端到端验证过。本文档站所有「桌面版」表述只能标「未实测」。
- **规避**：不断言、不承诺；文档已统一按未实测口径书写（[构建与运行](../guides/build-and-run.md)）。
- **待办**：跑一次 `dist:dir` 产出目录版，检查包内插件文件与启动日志，把结论回填本页与 build-and-run。

### #21 `.eap` 无签名、无校验和（✅ 脚手架侧已修复）

- **现象（修复前）**：`extractEap` 有 zip-slip 防护，但**没有**任何签名或校验和验证；脚手架 `pack` 也不生成 checksum 旁文件。
- **影响（修复前）**：包被篡改 / 传输损坏无法察觉（zip 自带 CRC 只防意外损坏，不防篡改）。
- **修复**：脚手架 `pack` 写完 `.eap` 后用 `node:crypto` 对全文件算 sha256，产出 `<id>-<version>.eap.sha256` 旁文件（一行 sha256sum 兼容格式：64 位小写摘要 + 两个空格 + 文件名 + LF）；`--verify` 新增第 6 项——重读盘上 `.eap` 复算摘要与旁文件核对（顺带兜住写盘截断）。实测：真实工程 `pack --verify` 六项全过、`Get-FileHash` 独立复算一致（提交 `6db72d46`）；[打包与安装](../guides/packaging-and-install.md) 补旁文件格式与核对命令。
- **后续**：worker 安装侧（`plugin.install`/`extractEap`）自动消费 `.sha256` 校验或升级为签名属生态强化项暂缓——文档已明确「worker 不消费，仅人工核对」。

## D. 文档站自身

### #22 脚手架 P2：`kind=web` 的生成摘要仍展示 Java 入口类（✅ 已修复）

- **现象（修复前）**：`formatPlan` 无条件打印 `入口类 ${vars.mainClass}`，而 `kind=web` 的工程根本没有 Java 入口类，摘要却照常展示一行。
- **影响（修复前）**：纯展示层误导；生成的文件树本身正确、不受影响。
- **修复**：`formatPlan` 入口行按 `spec.kind` 分支——web 形态删去「入口类 / Java 包」两行，改展示 `前端入口 web/index.ts（webMain）`（与模板 webMain 约定一致）；java/full 两行原样保留（行为不回归）。三种 kind 实跑 `--dry-run` 验证与文档逐字一致（提交 `a469248a`；[快速上手](../getting-started.md) dry-run 示例同步）。
- **后续**：无。

### #23 js 包版本与 Java 不一致，且不受 bump 脚本管理（✅ 已修复）

- **现象（修复前）**：`every-agent-plugin-api/js/package.json` = `0.11.0`，Java pom = `1.0.0`；`bump-version.py` 的 SLOTS 只登记 pom，js 包不在版本联动里——全仓 bump 时 js 侧静默掉队。
- **修复**（三步收口）：① 版本审计确认模板**数字无漂移**（Spring Boot 4.1.1 / Java 25 / parent 1.0.0 / plugin-api 1.0.0；standalone 钉版经 `effective-pom` 实证为 spring-boot 管理值），真正的漂移是 vendor 类型副本落后于 `js/index.ts`，已逐字节重新复制（提交 `ca0211e7`）；② 新增 `scripts/sync-plugin-template-versions.ps1` 统一 bump 脚本——六槽位从根 pom 只读解析，一键同步四份 pom 模板与文档站版本字面量，`-DryRun` 预览、幂等与漂移修复均实测通过（提交 `3d59f738`）；③ js 包版本对齐 `1.0.0` 并入 `bump-version.py` SLOTS（锚点经 `1.0.1 --dry-run` 实证联动），文档站与 sync 脚本旧口径注释同步追平（提交 `4b65c8d4`）。
- **后续**：版本口径统一为 `1.0.0`（js 与 Java 同号，双脚本都在联动范围）；#18 双发布前无需再处理版本分裂。

## 已缓解项

以下两条在本计划执行期内已部分或全部缓解，保留登记以供追溯。

### #6 `build:plugins` 不在 web dev/build 流水线；esbuild 曾是隐性依赖（esbuild 已缓解）

- **原状**：web 的 `dev`/`build` 脚本不含 `build:plugins`，新克隆直接 `npm run dev` 会因 `web/index.js`（gitignored）不存在而**内置插件全部静默不加载**；esbuild 靠 vite 传递依赖（未显式声明）。
- **已缓解部分**：esbuild 已显式声明（`every-agent-web/package.json:48`，`0.21.5`，与 vite 传递版本一致）。
- **仍开放部分**：`build:plugins` 依旧不在 web `dev`/`build` 里（桌面链有：`every-agent-desktop/package.json:16` 经 `build:assets` 间接调用）；首个命令前需手工跑一次 `npm run build:plugins`（[快速上手](../getting-started.md) 与 [构建与运行](../guides/build-and-run.md) 已按此口径书写）。
- **待办**（建议）：把 `build:plugins` 挂进 `dev` 前置或首次 `dev` 时检测产物缺失给出提示；收益是消掉新克隆「插件全不出现」的第一坑。

### #7 无 watch 模式（已缓解）

- **原状**：`build-plugins.mjs` 只支持一次性全量构建，改前端源码须手工重跑。
- **已缓解**：脚本现支持 `--watch`（esbuild 原生 context，Ctrl+C 干净退出）与 `--only <id>`（只构建指定插件），两者可组合（`every-agent-web/scripts/build-plugins.mjs:20-22` 脚本头说明）；并新增 `watch:plugins` 脚本（`every-agent-web/package.json:10`）。用法见[调试与测试](../guides/debugging-and-testing.md)。
- **无剩余开放项**。

## 建议的后续变更优先级（Top 5）

以下为本页作者的判断，供排期参考；均为建议（除 #18/#20 外均已随修复关闭：#1/#4、#15、#17/#14、#8、#19、#9/#12、#21/#22/#23、#10/#11）。

1. **API 双发布（#18）**——npm 与 Maven Central 同时发布 `@everyagent/plugin-api`。这是解锁仓库外开发的唯一硬阻塞，其余生态问题（安装侧校验、卸载入口）都排在其后。~~版本口径统一（原 #23 前置项）~~ ——✅ #23 已修复（js/Java 对齐 1.0.0，双脚本联动），发布前置条件已就绪。
2. ~~**`ui.file_explorer_actions` 接或删（#1）**~~ ——✅ 已修复（宿主文件树右键菜单已接消费点，git 的「显示 Git 历史」生效）。
3. ~~**死事件常量表清理（#4）**~~ ——✅ 已修复（17 个被取代条目删除、3 个事件补齐 emit，常量表 21 条全部可用）。
4. ~~**`plugin.list` 增加 `status` + 插件管理安装入口（#15 + #19）**~~ ——✅ 均已修复：#15 `status` 经聚合链出网（`active` 保持旧语义）；#19 面板安装入口上线（`fs.write` 暂存 → 既有 `plugin.install` RPC，`status` 失败标识同步消费）。`plugin.uninstall` 前端入口另议。
5. ~~**架构文档对账 + 陈旧注释批量修（#17 + #14 + #9 + #12）**~~ ——✅ 均已修复：#17 ARCHITECTURE 对账完成、#14 的 250 注释清零、#9 的 builtInPlugins 引用与 git 自述更正、#12 注释对齐实际链位（order 代码值改 HP+N 属择期可选项）。

## 下一步读

- 想动手修其中某条？先读 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) 的红线清单——文档与实现冲突时，**先改文档再改代码**。
- 遇到的现象不在本页？去 [故障排查](../guides/troubleshooting.md) 按日志文案逐行对号。
- 想知道某扩展点「活没活」？查 [API 索引](api-index.md)（⚠️ 标记即指向本页对应条目）。
