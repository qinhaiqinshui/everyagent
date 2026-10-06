---
title: 已知问题与开放项
nav_order: 19
parent: reference
has_children: false
---

**一句话定位**：本页是插件系统**现状偏差的集中登记簿**——23 条已核实的问题与开放项，每条按「现象 / 影响 / 规避 / 待办」四段成文，是判断「这是我的 bug 还是宿主的坑」的第一站。

## 如何读这张表

- **只登记事实，不评判设计**。每条「现象」都附证据（相对仓库根路径 + 行号，以当前仓库快照为准；行号会随后续提交漂移，以内容定位为准）。外部网络类事实（npm / Maven 404）标注「调查实测」，无法静态取证。
- **不重复正文**。某问题若在其余 18 篇已详述（多数带 ⚠️ 标记），本页只给一句话现象 + 链接，不展开机理。
- **「待办」只是建议**，本文档任务**不改插件与宿主代码**——按架构纪律，先改文档再改码（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)）。
- 编号 **#1~#23** 全站连续（`[reference/api-index](api-index.md)` 的 ⚠️ 标记即指向本页）；#6/#7 已缓解，登记在文末[已缓解项](#已缓解项)。
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

### #8 前端从不 dispose / deactivate 插件

- **现象**：`loadedPlugins` 的 `disposables` 恒为空数组（`every-agent-web/src/plugin/pluginLoader.ts:270,405`）；`PluginModule.deactivate?` 类型存在（`every-agent-plugin-api/js/index.ts:652`）但前端零调用。
- **影响**：禁用插件（`plugin.disable`）后前端 UI 不会卸载其贡献，必须刷新页面；插件无法在前端做任何卸载清理。
- **规避**：`deactivate` 里别放关键清理逻辑；接受「重启 worker + 刷新页面」的生效边界（[打包与安装](../guides/packaging-and-install.md) 的统一口径）。
- **待办**：若将来做前端热卸载，需补 dispose 收集循环；当前无消费场景，优先级低。

### #9 陈旧注释与未完成 TODO（纯注释债）

- **现象**：① 4 个插件注释仍引用**已删除**的 `builtInPlugins.ts`：`ai-review/web/index.ts:4`、`pdf-viewer/web/index.ts:4`、`update-file-view/web/index.ts:4`、`git/web/index.ts:4`；② `git/web/index.ts:2` 自称「纯 Web 插件」，实际 plugin.json 有 `main`（both 形态）；③ `subagent/web/SubAgentListPanel.tsx:13` 为 TODO，面板尚未消费自家 `task.agents` RPC（当前渲染 null）。
- **影响**：误导读者与抄注释的新插件作者；subagent 面板功能名存实亡（后端 RPC 活着）。
- **规避**：一律以代码为准；反查台账见 [内置插件](builtin-plugins.md) §5（已逐处标 ⚠️）。
- **待办**：批量修注释（低风险）；subagent 面板补 `task.agents` 消费属功能开发，单独排期。

## B. 后端 worker

### #10 `SearchProvider` SPI 未接线

- **现象**：注册方法存在（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerPluginContext.java:51`），registry 的 `getDefault`/`getById`/`getProviders`（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/SearchProviderRegistry.java:22,28,35`）在 worker 主代码**零调用**；25 个内置插件零注册；`docs/ARCHITECTURE.md` 也零提及该 SPI。
- **影响**：注册 SearchProvider **没有任何运行期效果**（比死扩展点更彻底：连消费候选点都没有）。
- **规避**：搜索类能力不要走该 SPI；文档已如实登记为「未接线」，见 [Advisor 与模型链](../backend/advisors.md) §6。
- **待办**：接线（worker 搜索入口改查 registry，或按 Javadoc 设想的 search-ripgrep 插件落地），或从公共 API 移除该 SPI——保留一个永远不生效的注册点是最大的误导源。

### #11 `SkillContributor` 半接线（只进 system prompt，不进 / 菜单）

- **现象**：registry 的 `getSkills()` 只被 `SkillAdvisor.mergedSkills()` 消费（`every-agent-worker/src/main/java/dev/everyagent/worker/skill/SkillAdvisor.java:67`，注入 system prompt）；`/` 菜单不含该 registry。subagent 的 skill 能进菜单，靠的是知识包**物化成文件**进 skillsDir 再被 `ExternalSkillScanner` 捞取，而非走此 SPI。
- **影响**：照 SPI 语义「贡献技能」的插件，技能只对模型可见、对用户 `/` 菜单不可见——两条通道不等价。
- **规避**：skill 需要进菜单的，物化文件到 skillsDir（subagent 先例）；只影响模型行为的才用 SPI。详见 [Advisor 与模型链](../backend/advisors.md) §5。
- **待办**：菜单并入 registry，或文档固化「物化文件是菜单唯一通道」的现状。

### #12 `TokenCalibrationAdvisor` 的 order=0 是绝对值错位

- **现象**：`order()` 返回 `0`（`every-agent-plugins/model-rate-limit/src/main/java/dev/everyagent/plugin/modelratelimit/TokenCalibrationAdvisor.java:180-181`）——不是 `HP+N` 体系，数值上**大于全链所有** `HIGHEST_PRECEDENCE+N`，实际排在 Advisor 链**最内层**；而类注释（`:29,57`）自称「核心基础设施层、最外层」。
- **影响**：模仿该注释设计 order 的插件会得到与预期**相反**的链位（校准器其实能看到所有出参，却看不到最早的入参改写）。
- **规避**：插件一律用 `HIGHEST_PRECEDENCE+N` 或 `TCA+N` 表达式，勿用绝对值小整数；全链真实顺序以 [Advisor 与模型链](../backend/advisors.md) §2.2 的 18 项 order 表为准。
- **待办**：改 order 会变更现网链位（需回归评估），最小动作是先修注释；二选一择期执行。

### #13 `deactivate()` 声明存在、全链零调用（死接口）

- **现象**：`EveryAgentPlugin.java:25` 声明 `default void deactivate() {}`（对标 VSCode），worker 侧零调用；前端同样不调（见 #8）。
- **影响**：后端插件没有停机钩子——worker 关闭、插件禁用、卸载时都无法执行清理（线程池、临时文件、监听器）。
- **规避**：需要清理的资源，在 `activate()` 里自持引用并接受「随进程消亡」语义；或自行注册 JVM shutdown hook。详见[后端总览](../backend/overview.md)。
- **待办**：`PluginLoader` 销毁阶段遍历调用 `deactivate()`（注意激活失败的插件要跳过），或删掉声明避免误导。

### #14 `QueueAdmissionNode` 注释写 250、代码是 40

- **现象**：类 Javadoc 写 `order=250，落在洋葱下行空隙 100~400 之间`（`every-agent-plugins/task-queue/src/main/java/dev/everyagent/plugin/taskqueue/QueueAdmissionNode.java:12`），代码实际 `return 40`（`:31`）。
- **影响**：按注释推断洋葱位置会得出错误结论（40 实际排在 `queue.dispatch`(15) 之后很近的位置；注释提到的 `status.start`(300) 节点也已不存在）。
- **规避**：以 [任务生命周期与 RPC](../backend/task-and-rpc.md) §1.4 的 31 节点实测表为准（该表已按 40 登记）。
- **待办**：修注释（连带 [`docs/ARCHITECTURE.md`](../../ARCHITECTURE.md):707 的 250 同为旧口径，随 #17 一并更正）。

### #15 `plugin.list` 的 `active` 语义失真、`status` 不出网

- **现象**：`active` 序列化为 `!pluginRegistry.isDisabled(id)`（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginRpcMethods.java:68`）——只反映 `.disabled-plugins` 文件状态，**激活失败也报 true**；而 `LoadedPlugin.status` 字段真实记录 active/failed 等状态（`PluginLoader.java:357-358`），从不序列化上网。
- **影响**：前端插件管理面板无法区分「在跑」与「加载失败」，用户以为插件生效了。
- **规避**：排查激活问题看 worker 日志（`插件清单解析失败`/`插件已激活` 等 15 条日志逐字对照表见[故障排查](../guides/troubleshooting.md)）。
- **待办**：`plugin.list` 增加 `status` 字段（数据已存在，只差序列化）。

### #16 `enabled:false` 对外部插件不生效（只有内置扫描器读）

- **现象**：`ExternalPluginScanner.java` 内 `enabled|isEnabled` **零命中**；`plugin.json` 的 `enabled` 字段只有 `BuiltInPluginScanner` 消费（内置插件 `sandbox-windows-mic`/`sandbox-wsl-ubuntu` 即靠它整目录跳过）。
- **影响**：外部插件想「装上但默认禁用」做不到——写 `enabled:false` 会被照常扫描加载。
- **规避**：外部插件的禁用走 `plugin.disable` RPC（写 `.disabled-plugins`）或干脆不放入 plugins 目录；两机制差异详见[持久化与状态](../backend/persistence-and-state.md)。
- **待办**：`ExternalPluginScanner` 补读 `enabled`（与内置行为对齐），或文档固化差异（现状已固化，改码属可选）。

### #17 `docs/ARCHITECTURE.md` 三处过时（文档欠账）

- **现象**：① §7.14.4（`docs/ARCHITECTURE.md:705`）称插件以「`@Component` + 构造器注入（git `GitPluginRegistrar` 先例）」注册——实测 `every-agent-plugins/**` **零 `@Component`**（插件走 `URLClassLoader`，依赖经 `ctx.services()` 手工取）；② §7.14.1（`:666-669`）写「内置节点 17 个」并列 `status.start(300)`/`queue.persist(700)` 等——实测 **31 节点**、300/700 已不存在；③ `PluginLoader.java:50,75` 的 Javadoc 引用旧 §7.2/§7.3 编号，对不上现行文档结构。
- **影响**：按架构文档写插件会走错路（最典型是期待 Spring 容器注入）。
- **规避**：以后端四篇为准：[后端总览](../backend/overview.md) §10（差异框）、[任务生命周期与 RPC](../backend/task-and-rpc.md)（31 节点表）。
- **待办**：①② 由本计划的收口步骤统一更正（步骤 30，改 `docs/ARCHITECTURE.md`，不改代码）；③ 属代码注释，待后续单独修。

## C. 分发与生态

### #18 `@everyagent/plugin-api` 双侧未发布（npm 404 / Maven 无 dev.everyagent）

- **现象**：npm registry 查 `@everyagent/plugin-api` → 404；Maven Central 无 `dev/everyagent` group（2025 调查实测，见计划记录 `.everyagent/plan-plugin-scaffold-docs.md`「已核实事实」）。本地包版本见 `every-agent-plugin-api/js/package.json:3`（0.11.0）与 `every-agent-plugin-api/pom.xml:12`（1.0.0）。
- **影响**：**仓库外**开发插件拿不到类型包与 API jar——这是插件生态的硬阻塞项。
- **规避**：脚手架 `standalone` 模式自动生成 `vendor/everyagent-plugin-api.d.ts` 类型副本（[快速上手](../getting-started.md)）；Java 侧在仓库内 `mvn install` 到本地仓库后引用。
- **待办**：发布双包（npm + Maven Central），发布前先解决 #23 的版本口径。**优先级最高**（见文末 Top5）。

### #19 `plugin.install` / `plugin.uninstall` 前端零调用点

- **现象**：`every-agent-web/src` 全仓对这两个 RPC **零调用**；插件管理面板只用 `plugin.list`/`plugin.enable`/`plugin.disable`（`every-agent-plugins/plugin-manager/web/PluginManagerPanel.tsx:199,222,226`）。
- **影响**：`.eap` 包安装没有产品化入口——RPC 在协议里活着，但用户够不着（且 `{path}` 要求 worker 机器本地路径，浏览器端本也难以直传）。
- **规避**：当前唯一实际路径是**手工解压**到 `~/.everyagent/plugins/<id>/`（布局与坑见[打包与安装](../guides/packaging-and-install.md)，含 wire 帧示例）；`.eap` 本身用脚手架 `pack` 子命令产出。
- **待办**：插件管理面板加安装 UI（需先解决「worker 机器路径」的上传/选路径交互），或提供 CLI 安装命令。

### #20 desktop 分发内置插件：链路存在，端到端未实测

- **现象**：桌面打包链含插件构建与拷贝（`every-agent-desktop/package.json:11` `build:plugins`（Python 包装）、`:12` `copy:plugins`（`copy-plugins.mjs`）、`:16` `build:assets` 串联）。
- **影响**：**未知**——最终安装包里是否正确包含 `every-agent-plugins` 产物、桌面版 worker 启动后内置插件是否激活，从未端到端验证过。本文档站所有「桌面版」表述只能标「未实测」。
- **规避**：不断言、不承诺；文档已统一按未实测口径书写（[构建与运行](../guides/build-and-run.md)）。
- **待办**：跑一次 `dist:dir` 产出目录版，检查包内插件文件与启动日志，把结论回填本页与 build-and-run。

### #21 `.eap` 无签名、无校验和（完整性无保障）

- **现象**：`extractEap` 有 zip-slip 防护（`PluginRpcMethods.java:226-227` normalize+startsWith 前缀校验、`:218` 过滤 `__MACOSX`/`.DS_Store`），但**没有**任何签名或校验和验证；脚手架 `pack` 也不生成 checksum 旁文件。
- **影响**：包被篡改 / 传输损坏无法察觉（zip 自带 CRC 只防意外损坏，不防篡改）。
- **规避**：自行分发 `.eap` 时附带 SHA256 并在安装前人工核对；只从可信来源取包。
- **待办**：生态成熟前可后置；最小方案是 `pack` 顺手产出 `<id>-<version>.eap.sha256`。

## D. 文档站自身

### #22 脚手架 P2：`kind=web` 的生成摘要仍展示 Java 入口类

- **现象**：`formatPlan` 无条件打印 `入口类 ${vars.mainClass}`（`create-everyagent-plugin/lib/tree.mjs:159`），而 `mainClass` 对所有 kind 一律计算（`lib/naming.mjs:232`）——`kind=web` 的工程根本没有 Java 入口类，摘要却照常展示一行。
- **影响**：纯展示层误导（真机验证步骤 9 记录的 P2）；生成的文件树本身正确、不受影响。
- **规避**：忽略摘要里的「入口类 / Java 包」两行即可，工程可用。
- **待办**：`formatPlan` 按 `spec.kind` 分支展示（web 形态展示 `webMain` 代替）。

### #23 js 包版本 0.11.0 与 Java 1.0.0 不一致，且不受 bump-version.py 管理

- **现象**：`every-agent-plugin-api/js/package.json:3` = `0.11.0`；`every-agent-plugin-api/pom.xml:12` = `1.0.0`；`scripts/bump-version.py` 的 SLOTS 只登记了 pom（`scripts/bump-version.py:65-70` 区段），js 包不在版本联动里。
- **影响**：同一 API 双语言版本口径分裂；全仓 bump 版本时 js 包会静默掉队，将来 npm 发布（#18）时版本语义不明。
- **规避**：当前以 Java `1.0.0` 为准口径（本站引用插件 API 版本处均指它）。
- **待办**：SLOTS 补 `js/package.json`，并把两侧版本对齐为同一号。

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

以下为本页作者的判断，供排期参考；均为建议，本文档任务不动代码。

1. **API 双发布（#18 + #23）**——npm 与 Maven Central 同时发布 `@everyagent/plugin-api`，发布前先统一版本号并纳入 bump 脚本。这是解锁仓库外开发的唯一硬阻塞，其余生态问题（安装 UI、签名）都排在其后。
2. **`ui.file_explorer_actions` 接或删（#1）**——API 面上的死扩展点是文档与代码最大的「表面可用」陷阱；接消费点（文件树右键菜单约一个组件的改动）或删 API（连带 manifest 贡献项），不要维持现状。
3. **死事件常量表清理（#4）**——24 个零 emit 事件 + `task-deleted` 死订阅陷阱是前端插件作者最容易踩的坑；清掉常量表中被取代的条目（或补 emit），一次性消除整类陷阱。
4. **`plugin.list` 增加 `status` + 插件管理安装入口（#15 + #19）**——数据已存在只差序列化（#15 是小改动大收益）；安装入口产品化后 `.eap` 生态才算闭环。
5. **架构文档对账 + 陈旧注释批量修（#17 + #14 + #9）**——`docs/ARCHITECTURE.md` ①② 由本计划收口步骤更正；`QueueAdmissionNode`/`TokenCalibrationAdvisor` 注释与 4 处 builtInPlugins 引用、git「纯 Web」自述同批修掉，一次 PR 消灭全部注释债。

## 下一步读

- 想动手修其中某条？先读 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) 的红线清单——文档与实现冲突时，**先改文档再改代码**。
- 遇到的现象不在本页？去 [故障排查](../guides/troubleshooting.md) 按日志文案逐行对号。
- 想知道某扩展点「活没活」？查 [API 索引](api-index.md)（⚠️ 标记即指向本页对应条目）。
