---
title: 前端事件
nav_order: 12
parent: web
has_children: false
---

# 前端事件

**一句话定位**：`ctx.events` 是前端插件感知（和驱动）宿主 UI 的唯一事件通道——它是宿主进程内一条模块级事件总线的最薄委托。本文给出**全部 22 个宿主事件的逐个取证表**（载荷、触发时机、来源文件、监听价值）——known-issues #4 清理后，常量表里**不再有零 emit 的死事件**：18 个有真实 emit 调用点，其余 4 个是「宿主监听、插件可反向 emit 驱动宿主」的请求通道。事件名与载荷类型的唯一登记处是 `every-agent-web/src/events/domainEvents.ts`（常量表 `DOMAIN_EVENTS` + 载荷类型 `DomainEventMap`）。

## 1. 事件系统架构：一个模块级单例总线

### 1.1 总线实现（`every-agent-web/src/events/eventBus.ts`）

整条总线就是一个 `EventBus` 类的模块级单例 `domainEventBus`（`eventBus.ts:6,35`），没有主题树、没有通配符、没有优先级。四条硬行为全部出自这 36 行源码：

| 行为 | 证据 | 对插件的含义 |
|---|---|---|
| **无监听者静默** | `emit` 开头 `if (!listeners || listeners.size === 0) return`（`eventBus.ts:10-11`） | emit 永不报错、无日志——拼错事件名 = 石沉大海（见 §6.1） |
| **queueMicrotask 异步派发** | `queueMicrotask(() => callbacks.forEach(...))`（`eventBus.ts:13-17`） | emit 立即返回；handler 在当前同步代码跑完后才执行。handler 里再 emit 不会同步递归爆栈 |
| **派发前快照监听者** | `Array.from(listeners)`（`eventBus.ts:12`） | emit 之后、微任务执行前 `dispose` 的 handler **仍会被调一次**；此后新订阅的 handler 收不到这一发 |
| **subscribe 返回清理函数** | 返回 `() => { current.delete(handler); 空 Set 连键一起删 }`（`eventBus.ts:24-31`） | 清理是真清理；最后一个监听者退订后该事件名从 Map 消失 |

两条额外陷阱（同一段源码可证）：① **handler 异常不隔离**——`callbacks.forEach` 外层没有 try/catch（`eventBus.ts:14-16`），一个 handler 抛错会中断**同事件名**的后续 handler，异常冒成未处理的微任务错误；② **payload 是共享引用**——同一对象原样传给所有 handler（无克隆），任何 handler 都不要改它。

总线全量源码仅 36 行，值得整读一遍（编号对应上表）：

```ts
// every-agent-web/src/events/eventBus.ts:6-33（全文摘录，注释为本篇所加）
class EventBus {
  private handlers = new Map<DomainEventName, Set<EventHandler<any>>>()

  emit<TEventName extends DomainEventName>(eventName: TEventName, payload: DomainEventMap[TEventName]): void {
    const listeners = this.handlers.get(eventName)
    if (!listeners || listeners.size === 0) return        // ① 无监听者静默
    const callbacks = Array.from(listeners)               // ② 派发前快照
    queueMicrotask(() => {                                // ③ 微任务异步派发
      callbacks.forEach((handler) => {
        handler(payload)                                  // ④ 无 try/catch，payload 共享引用
      })
    })
  }

  subscribe<TEventName extends DomainEventName>(eventName: TEventName, handler: EventHandler<TEventName>): () => void {
    const listeners = this.handlers.get(eventName) ?? new Set<EventHandler<TEventName>>()
    listeners.add(handler)
    this.handlers.set(eventName, listeners as Set<EventHandler<any>>)
    return () => {                                        // ⑤ 清理函数：真删
      const current = this.handlers.get(eventName)
      if (!current) return
      current.delete(handler)
      if (current.size === 0) {
        this.handlers.delete(eventName)                   //    空 Set 连键一起删
      }
    }
  }
}
```

### 1.2 `ctx.events` 是最薄委托

`ctx.events` 的宿主实现 `createPluginEvents()` 只有两段转发（`every-agent-web/src/plugin/pluginLoader.ts:232-250`）：`on` → `domainEventBus.subscribe`（返回值包成 `{ dispose: unsubscribe }`），`emit` → `domainEventBus.emit`，**无任何过滤、改写、前缀注入**。类型断言 `eventName as Parameters<...>`（`:236,243`）意味着运行时**任意字符串**都能订阅/发布——`PluginDomainEvent` 的类型约束只挡编译期。

### 1.3 emit 的受众边界（最重要的一节）

`ctx.events.emit(...)` 的受众 = **同一浏览器页面内 `domainEventBus` 上的全部订阅者**，具体分四档：

| 受众 | 可达性 | 证据 |
|---|---|---|
| 宿主 UI 代码（Layout、taskStore、taskStream、文件树……） | ✅ | 20 个宿主模块直接 `domainEventBus.subscribe(...)`（rg `from '@/events/eventBus'` 共 21 处 import，其中 1 处是插件委托 `pluginLoader.ts:32`；示例 `every-agent-web/src/components/app/Layout.tsx:45`） |
| 其他前端插件 | ✅ | 它们的 `ctx.events.on` 也挂同一条总线（`pluginLoader.ts:235-239`） |
| worker / hub | ❌ | emit 路径上没有任何上行桥接（`pluginLoader.ts:241-246` 之后即终止）；要让后端知道什么走 `ctx.sdk.rpc`（见[前端 ctx API](context-api.md) §3） |
| 其他浏览器标签页 / 持久化 | ❌ | 总线是页面内存对象，刷新即清零；无 BroadcastChannel、无 localStorage 事件联动（rg `addEventListener('storage'` 全仓零命中，§6.3） |

事件本身是 fire-and-forget：**没有重放、没有「补齐」**——插件激活晚于某次 emit 就永远错过它（这正是 §5.1 `plugins-loaded` 虽已接线 emit、仍帮不了晚加载插件的原因）。

## 2. plugin-api 的具名事件

`PluginDomainEvent`（`every-agent-plugin-api/js/index.ts`）具名列 8 个事件名 + `(string & {})` 兜底（兜底写法让 IDE 补全优先提示具名、同时放行任意自定义字符串）：

| 具名事件 | 当前真实会发生？ | 详见 |
|---|---|---|
| `workspace-file-changed` | ✅（高频） | §3.1 |
| `workspace-registry-changed` | ✅ | §3.1 |
| `sidebar-panel-shown` | ✅ | §3.1、§5.4 |
| `task-status-changed` | ✅ | §3.1 |
| `task-round-closed` | ✅ | §3.1 |
| `task-created` | ✅（known-issues #4 修复后接线） | §3.1 |
| `task-deleted` | ✅（known-issues #4 修复后接线） | §3.1 |
| `plugins-loaded` | ✅（known-issues #4 修复后接线） | §3.1 |

⚠️ 具名清单是手维护的子集（8/21）——写订阅前仍先查 §3 的两张表。类型原文如下（`every-agent-plugin-api/js/index.ts` 摘录）：

```ts
/** 领域事件名（与宿主 DOMAIN_EVENTS 同名值，字符串字面量联合；全部有真实 emit）。 */
export type PluginDomainEvent =
  | 'workspace-file-changed'
  | 'workspace-registry-changed'
  | 'sidebar-panel-shown'
  | 'task-created'
  | 'task-deleted'
  | 'task-status-changed'
  | 'task-round-closed'
  | 'plugins-loaded'
  | (string & {})    // 兜底：放行任意自定义字符串，补全仍优先提示上面 8 个

export interface PluginEvents {
  on(eventName: PluginDomainEvent, handler: (payload: unknown) => void): Disposable
  emit(eventName: PluginDomainEvent, payload: unknown): void
}
```

## 3. 宿主事件总表（21 个，逐一回源码复核）

统计口径（rg 实测）：`DOMAIN_EVENTS` 常量表声明 **22** 个事件名；全仓（`every-agent-web/src` + `every-agent-plugins` + `every-agent-desktop`）`.emit(` 调用点命中其中 **18** 个，其余 4 个是宿主监听的请求通道。known-issues #4 清理前本表曾有 38 个声明、24 个零 emit——被折叠器/镜像订阅取代设计位的 17 个条目已连同载荷类型一起删除（`agent-run-event` 的 `AgentRunEvent`/`AgentRunEventPayload` 类型一并移除，其注释引用的幽灵文件 `src/task/agentRunEventBridge.ts` 随之消失）。

### 3.1 真实会发生的事件（18 个）

| 事件名 | 载荷（`DomainEventMap` 摘要） | 触发时机（谁、何时 emit） | 插件监听价值 |
|---|---|---|---|
| `workspace-file-changed` | `{filePath, workspaceRoot, operation: 'create'\|'modify'\|'delete'\|'rename', isDirectory, oldFilePath?}`（`domainEvents.ts:236-254`） | 两路：① worker 侧 `fs.changed` 广播帧（**AI 工具写文件**也会走这条）→ 前端 `wireFsChanged` 桥接 emit（`every-agent-web/src/platform/fs/workspaceGateway.ts:175-197`，调用点 `:190,192,194`；启动接线 `every-agent-web/src/main.tsx:49`）；② 前端网关自身写操作 `ensureDir :222`、`writeBytes :312`、`deletePath :337`、`rename :353-360`（统一走 `emitFileChanged` `:158-165`） | **高**。文件树/编辑器刷新、按文件路径失效缓存（宿主自用：`FileTabPage.tsx:135`、`OpenFilesSidebarPanel.tsx:264`）。流式任务期间高频，见 §4 |
| `workspace-registry-changed` | `{defaultRoot, workspaces: Array<{root, addedAt}>}`（`:271-275`） | 工作区注册表 `rebuild()` 后、根集合实际变化时（`every-agent-web/src/hub/workspaceRegistry.ts:264-272`，emit 于 `:271`） | **高**。多工作区增删感知；git 插件实战在用（`every-agent-plugins/git/web/usePluginWorkspaces.ts:31`、`GitChangeBadge.tsx:31`）。宿主自身无订阅者——这条目前主要就是发给插件听的 |
| `sidebar-panel-shown` | `{panelId: string}`（`:255-257`） | 侧边栏面板从隐藏→显示的边沿（`Layout.tsx:1107-1113` 的 `SidebarPanelHost` useEffect，emit 于 `:1112`）；`panelId` 即侧边栏项 `id`（内置 `tasks/files/search/settings` + 插件自己的 id） | **高**。「面板被用户点亮时懒刷新」标准姿势（git 在用：`GitSidebarPanel.tsx:334-339` 过滤 `panelId === 'git'`），见 §5.4 |
| `task-status-changed` | `{taskId, status, error?, endTime?, task?, displayTitle?}`（`:167-174`） | 任务列表条目状态翻转时（`every-agent-web/src/task/taskStore.ts:497-513` 的 `upsert`，emit 于 `:505-512`；仅 `prevStatus !== entry.status` 才发）。来源是 worker 任务频道帧 | **高**。任务终态/出错感知（宿主自用：浏览器系统通知 `BrowserNotificationHost.tsx:53`） |
| `task-round-closed` | `{taskId, startSeq, endSeq}`（seq 为字符串）（`:347-353`） | 任务流消费到 `round.closed` 信号帧时（`every-agent-web/src/task/taskStream.ts:212-226`，emit 于 `:219-224`） | **高**。轮末缓存作废的标准信号（file-change 在用：`every-agent-plugins/file-change/web/index.ts:24-27`）。⚠️ 历史回放同样会补发：`task.poll` 回放的 `round.closed` 帧走同一 `onEvent`（`task-packet-view.ts:504-522` 不区分 `initial`，`taskStream.ts:212` 无 initial 分支）——打开旧任务会按历史轮次逐轮触发，作废缓存无害，但别拿它当「刚刚有新输出」信号 |
| `settings-theme-patched` | `{themeMode: 'light'\|'dark'}`（`:197-199`） | 用户切换主题落 localStorage 时（`every-agent-web/src/settings/localSettings.ts:14-19`，emit 于 `:18`） | 中。自带 UI 想跟随宿主主题可订阅（宿主自用：`useThemeMode.ts:15`、`TerminalPage.tsx:152`） |
| `worker-data-changed` | `Record<string, never>`（空对象）（`:359`） | worker 启用/停用/移除/重连 hub 配置后（`WorkerList.tsx:87,155,169`、`SettingsPanel.tsx:70`） | 中。worker 集合变动后提醒自家数据可能过期（taskStore 全量 refresh 的触发器，`taskStore.ts:235`） |
| `workspace-tab-closed` | `{tabId: string}`（`:360`） | 关闭工作区标签页的瞬间（`Layout.tsx:388-390`；taskStream 据此退订任务流频道，`taskStream.ts:739`） | 中。插件若按 tabId 持有 per-tab 资源可监听清理 |
| `workspace-tab-activated` | `{tabId: string, tabType: string}`（`:172-177`） | 壳层激活标签变化时（`Layout.tsx` 的 activeWorkspaceTab effect：先同步 `activeTabMirror` 再 emit；所有标签关闭时只清镜像不 emit，用上一行 closed 兜底感知） | **高**。「按当前标签类型显隐/刷新 UI」的标准信号（mobile-keyboard 在用：切到 terminal 标签才显示悬浮球）；配 `ctx.ui.getActiveTab()` 同步查询 |
| `user-interaction-requested` | `{taskId, agentId, request}`（`:287-294`） | ask 等待用户作答：实时 `ask.create`（`askStore.ts:285`）、静默注册后补发（`:263`）、回放 debounce flush（`:230`，50ms 内被 settle 则跳过防闪烁） | 中。宿主已完整消费（弹窗 `UserInteractionHost.tsx:108`、系统通知 `BrowserNotificationHost.tsx:71`、角标 `PendingUserInteractionIndicator.tsx:43`）；插件一般无需再听 |
| `user-interaction-resolved` | `{taskId, agentId, result}`（`:295-302`） | 用户提交交互结果时（`askStore.ts:355`） | 中低。同上，多为补充感知 |
| `user-interaction-cleared` | `{taskId, agentId, interactionId}`（`:303-310`） | ask 落定（提交/超时/取消）移除卡片时（`askStore.ts:306`；回放期间从未广播过的跳过） | 中低。同上 |
| `workspace-open-user-interaction-requested` | `{interactionId}`（`:311-314`） | 通知卡片/悬浮菜单被点击，请求把交互请求带到前台（`askStore.ts:203`、`BrowserNotificationHost.tsx:43`、`PendingUserInteractionIndicator.tsx:73`；消费方 `UserInteractionHost.tsx:111`） | 低。宿主内部导航协议 |
| `workspace-focus-task-requested` | `{taskId}`（`:212-214`） | 浏览器系统通知被点击时（`BrowserNotificationHost.tsx:65`；消费方 `Layout.tsx:743`） | 中。**插件也可以 emit 它**让宿主聚焦某任务标签——这是单边接线事件里反向可用的一类（§3.2 说明） |
| `workspace-search-panel-requested` | `{workerId, workspaceRoot, rootPath, label, target?}`（`:259-270`） | 文件树右键「搜索」/任务列表工作区组「搜索」（`OpenFilesSidebarPanel.tsx:681-688`、`TasksPanel.tsx:381-388`；消费方内置 `SearchPanel.tsx:510-511`） | 低。宿主内部跳转协议，插件没有搜索面板可接 |
| `task-created` | `{taskId}`（`domainEvents.ts` `TASK_CREATED`） | worker 任务频道 `task.created` 帧入库时（`taskStore.ts` onFrame 处理，emit 于 upsert 之后；本端与其它端创建同权——**首拉列表/翻页不触发**） | 中。新任务感知/按 taskId 预取数据（known-issues #4 修复后接线） |
| `task-deleted` | `{taskId}`（`domainEvents.ts` `TASK_DELETED`） | 任务删除唯一路径 `taskStore.remove()`（worker `task.deleted` 帧 → 删除镜像成功即 emit） | 中。作废该任务相关缓存的标准信号——file-change 插件的死订阅陷阱已随本条接线修复（known-issues #4） |
| `plugins-loaded` | `{count}`（`domainEvents.ts` `PLUGINS_LOADED`） | 每轮 `loadPlugins()` 流程结束时（`pluginLoader.ts` doLoadPlugins 末尾，count 为当前已装载总数） | 低。宿主插件就绪脉冲；⚠️ fire-and-forget 无重放——晚于本轮 emit 才激活的插件收不到它（known-issues #4 修复后接线） |

### 3.2 宿主从不 emit、但插件可反向驱动的请求通道（4 个）

以下事件名在 `DOMAIN_EVENTS` 有登记、`DomainEventMap` 有载荷类型，宿主**没有任何 emit 调用点**——但与已删除的死事件不同，宿主侧有**活的订阅者**在等：它们是「插件 emit → 宿主响应」的反向请求通道，emit 即生效。

| 事件名 | 载荷（声明） | 等待方（订阅者） | 用法 |
|---|---|---|---|
| `workspace-open-file-requested` | `{filePath, workspaceRoot?, startNameEditing?, mode?, lineNumber?}` | `Layout.tsx:689`（打开文件标签） | 插件 emit 它驱动宿主打开文件标签（等效 `ctx.ui.openFileTab`，见 [UI 扩展点](ui-extensions.md) §15） |
| `workspace-close-file-requested` | `{filePath?, fileTabId?, force?}` | `Layout.tsx:706` | 插件关标签的请求通道 |
| `workspace-reload-all-files-requested` | `{force?}` | `Layout.tsx:746`、`OpenFilesSidebarPanel.tsx:260` | 请求宿主重载全部文件标签 |
| `runtime-config-error` | `{message}` | `Layout.tsx:686`（弹错误提示） | 插件 emit 会让宿主弹一条错误横幅（慎用） |

用法建议浓缩成三句：**「要感知任务/文件变化，先查 §3.1 有没有现成事件；§3.2 的 4 个请求通道可以反过来被插件 emit 去驱动宿主；除此之外的名字不要订阅也不要 emit**——曾经的 24 个零 emit 死事件已随 known-issues #4 清理出常量表，`(string & {})` 兜底仍不拦拼写错误，自定义事件请按 §6.1 用 `<pluginId>:<verb>` 前缀。

### 3.3 为什么曾有一半事件是死的：前端五条通知通道的分工

known-issues #4 清理前 38 声明 vs 14 实发不是单纯的烂尾，而是**通知通道分工**的结果——任务/消息的细粒度变化走了更高效的专用通道，领域总线只保留「跨模块、低频、边沿触发」的那部分：

| 通知通道 | 承载的数据 | 消费方式 | 与插件的关系 |
|---|---|---|---|
| `domainEventBus`（本文） | 跨模块边沿事件（状态翻转、面板点亮、轮闭合、文件变化） | `ctx.events.on` | **唯一开放给插件的通道** |
| `taskStore.subscribe`（`taskStore.ts:532-537`，回调立即喂全量列表） | 任务列表镜像（worker tasks 频道 upsert 而来） | 回调取全量 | 插件不可直接订阅；经 `task-status-changed` 事件间接感知 |
| taskStream 折叠器 notify（`taskStream.ts` 内部监听者集合） | 消息/trace 的流式增量 | 宿主线程组件专用 | 插件侧接 UI 扩展点（tool_call_views、round_tail_panels 等，见[UI 扩展点](ui-extensions.md)）让宿主替你渲染 |
| `subscribeExtensionsChanged` + `getExtensionsVersion`（§4.3） | 扩展点注册表变化 | `useSyncExternalStore` | 插件注册即自动触发，无需自己发事件 |
| Composer 面板 ctx 的 `subscribeTaskEvents`（[UI 扩展点](ui-extensions.md) §6） | `'task.updated'` / `'task.stream'` 刷新信号 | 面板组件 props 回调 | 只回发两个信号名、`agentId`/`payload` 恒 null，当刷新脉冲用 |

一个迁移实例可以直接读到这种分工：`TaskChatTabLabel` 的头注释自述「旧版按激活任务订阅 domainEventBus 的 `TASK_STATUS_CHANGED`；hub 版任务真相源在 worker，前端镜像 taskStore……直接订阅 taskStore」（`every-agent-web/src/components/task/TaskChatTabLabel.tsx:6-9`，现行代码只订 taskStore、零事件调用）。因此 §3.2 之外那些 agent/task 细粒度类旧事件的合理解读是：**它们的设计位被折叠器/镜像订阅取代了**——known-issues #4 清理已把这 17 个被取代的条目连同载荷类型从 `DOMAIN_EVENTS` 常量表删除（含 `ContextBattery` 的 `task-context-monitor-changed` 死订阅，该组件改经 `monitor` prop 镜像更新）。

## 4. 高频事件、性能与「稳定快照」机制

### 4.1 真正的高频源

实测会高频触发的基本只有 `workspace-file-changed`：流式任务里每个写文件工具调用都会产生一条 worker `fs.changed` 广播（`workspaceGateway.ts:186-194`），批量编辑时连续多发。其余活事件都是低频边沿（状态翻转、面板点亮、轮闭合）。注意方向：**流式文本增量完全不走这条总线**（旧 `agent-message-streaming`/`task-trace-changed` 等条目已随 known-issues #4 清理删除）——不要为了追流式输出来订阅事件，那是 taskStream 内部通道，插件侧的正确姿势是接 UI 扩展点（如 [UI 扩展点](ui-extensions.md) §7/§14）让宿主替你渲染。

### 4.2 监听高频事件的纪律

1. **handler 里只做标记，不做重活**：派发在微任务里同步串行（`eventBus.ts:13-17`），一个慢 handler 会推迟同事件的所有后续 handler。
2. **按载荷过滤早退**：`workspace-file-changed` 先比 `workspaceRoot`/扩展名再决定是否动作（`FileTabPage.tsx:135` 的宿主自用范例同款思路）。
3. **不要在 handler 里同步 emit 同名事件**：会形成异步自激循环（微任务链条不会爆栈，但会空转）。

### 4.3 `subscribe` + `getExtensionsVersion`：另一条别混淆的订阅线

「扩展点贡献变化」**不是**领域事件，而是 `pluginDispatcher` 自己的一对快照接口（`every-agent-web/src/plugin/PluginDispatcher.ts:70-95`）：任一扩展点注册/注销都会 `notifyExtensionsChanged`（内部自增 `extensionsVersion`，`:70-75`），宿主组件用 `React.useSyncExternalStore(subscribeExtensionsChanged, getExtensionsVersion)` 感知并重渲染（用法见 [UI 扩展点](ui-extensions.md) §0 第 2 条）。**扩展点贡献必须可 `subscribe`、版本号必须是稳定快照**（同一版本内 `get` 返回恒定）——这是 `useSyncExternalStore` 的硬契约，否则 React 会无限重渲染。插件自己不直接碰这对接口；知道它的存在是为了别把「注册了个扩展点」误当成一条领域事件去 `ctx.events.on` 等——那样的信号不存在。

## 5. 死事件与陷阱逐条核实

### 5.1 `plugins-loaded`：已接线（known-issues #4 修复）

`loadPlugins()` 每轮流程结束时 emit（`pluginLoader.ts` `doLoadPlugins` 末尾），载荷 `{count}`（当前已装载插件总数，含此前轮次）。两个注意点：① fire-and-forget 无重放——晚于本轮 emit 才激活的插件收不到它（§1.3），所以别把它当「所有插件就绪」的屏障用；② worker 不可达 / `plugin.list` 失败等静默降级路径**不 emit**（本轮根本没有装载动作）。

### 5.2 `agent-run-event`：已随 #4 清理删除（历史登记）

该事件连同 `AgentRunEvent`（5 种 kind 联合）/`AgentRunEventPayload` 载荷类型曾长期零 emit、零订阅，其注释还引用了仓库中不存在的 `src/task/agentRunEventBridge.ts`（幽灵文件）。known-issues #4 清理时整组删除——任务/消息细粒度增量由 taskStream 折叠器内部通道承载，不需要总线形态的镜像事件；后续宿主若需要 agent 运行事件的对外发布，应先在 `DOMAIN_EVENTS` 落 emit 调用点再登记事件名。

### 5.3 `task-deleted`：已接线（known-issues #4 修复，曾经的内置插件死订阅）

file-change 插件订阅它作废轮次缓存（`every-agent-plugins/file-change/web/index.ts:28-31`）。修复前任务删除的真实路径 `TasksPanel` → `task.delete` RPC → worker 频道 `task.deleted` 帧 → `taskStore.remove()` **不发领域事件**，该作废路径永不触发；修复后 `remove()` 删除镜像成功即 emit `{taskId}`——同插件里 `task-round-closed`（活）与 `task-deleted`（修复前死、现活）的对照仍是最好的教训：**订阅前先在 §3.1 确认事件真的会响**，不要看名字合理就挂监听。

### 5.4 `sidebar-panel-shown` 的正确用法

- **触发时机**：面板 `visible` 从 false→true 的边沿（`Layout.tsx:1107-1113`）——不是「面板已显示」的状态，而是「刚被点亮」的脉冲。面板常驻 DOM、非激活态仅 `display:none`（[UI 扩展点](ui-extensions.md) §2），所以「面板重新可见」每次都会发。
- **先过滤 panelId**：事件发给全体订阅者，git 的姿势是第一行就 `if (payload.panelId !== 'git') return`（`GitSidebarPanel.tsx:334-337`）。内置面板 id 也会出现（`tasks`/`files`/`search`/`settings`），别误把别人的脉冲当自己的。
- **正确用途**：面板懒刷新——用户点亮面板时拉最新状态（git 同款）；**错误用途**：当「页面加载完成」或「插件激活完成」信号用。

### 5.4.b 具名清单已对齐宿主实况（known-issues #4 修复后）

`PluginDomainEvent` 具名 8 个**全部有真实 emit**（§2 表）；曾具名却零 emit 的 `file-content-saved`、`task-trace-changed` 已从具名清单与宿主常量表同步删除。plugin-api 的具名清单仍是手维护快照——以 §3 两张表为准的习惯保持不变。

顺带解释「为什么 TypeScript 拦不住死事件订阅」：`PluginDomainEvent` 的兜底 `(string & {})` 让任何字符串字面量都是合法事件名——类型系统只校验「这是个 string」，不校验「宿主真的会 emit 它」。#4 清理后常量表本身已无死事件，但**自定义事件名**（§6.1）的拼写仍只能靠你自己保证；死事件的唯一可靠判据就是本文 §3 的两张表（rg emit 调用点实测），后续宿主演进若接活了某个事件，以 `DOMAIN_EVENTS` 常量表 + 全仓 emit 复查为准，不要依赖本文快照的永久性。

## 6. 插件间通信模式

### 6.1 松耦合的正确姿势：约定事件名 + 无监听者静默

`emit` 无监听者静默（§1.1）天然支持「发送方不关心有没有人听」的松耦合广播。**内置 27 个插件中没有任何一个调用过 `ctx.events.emit`**（rg `events.emit(` 于 `every-agent-plugins` 零命中——插件侧调用全是 `on`），所以**没有内置先例**；下例是按机制推导的推荐写法：

```ts
// 发送方插件 A：状态变更广播。事件名带插件 id 前缀，避免与宿主/其他插件撞名
ctx.events.emit('my-plugin:index-updated', { taskId, updatedAt: Date.now() })

// 接收方插件 B：按同一约定订阅，载荷自带类型强转（总线载荷是 unknown）
ctx.events.on('my-plugin:index-updated', (payload) => {
  const p = payload as { taskId?: string; updatedAt?: number }
  if (p.taskId) onIndexChanged(p.taskId)
})
```

纪律三条：① 事件名一律 `<pluginId>:<verb>` 前缀——宿主具名事件都没有冒号，天然避让；② 载荷类型没有登记处（`DomainEventMap` 不对自定义事件开放），双端各自维护类型 + `as` 强转，建议在仓库里共享一个字面量常量；③ 调试时先 `ctx.events.on(name, console.log)` 挂探针确认拼写（§1.1：拼错不报错）。

完整的收发对（含共享常量，可直接抄）：

```ts
// shared/pluginEvents.ts —— 双端共享的事件契约（普通常量 + 手写载荷类型）
export const EVT_INDEX_UPDATED = 'a-plugin:index-updated' as const
export interface IndexUpdatedPayload {
  taskId: string
  version: number
}

// a-plugin/web/index.ts —— 发送方：广播 + 不关心有没有人听
ctx.events.emit(EVT_INDEX_UPDATED, { taskId, version: ++localVersion })

// b-plugin/web/index.ts —— 接收方：订阅 + 强转
ctx.events.on(EVT_INDEX_UPDATED, (payload) => {
  const p = payload as IndexUpdatedPayload
  if (p.taskId === currentTaskId) reindex(p.version)
})
```

注意双向的静默性：B 不存在时 A 照常工作（emit 静默），A 不存在时 B 永远等不到（订阅也静默）——**这对通信没有任何存活检测**，接收方必须能容忍「事件从未到来」（初值自取，见 §6.2 分工）。

### 6.2 配 `ctx.storage` 做状态同步的局限

`ctx.storage` 是 localStorage（键前缀 `plugin:<id>:`，见[前端 ctx API](context-api.md) §4）。它**没有变更通知**：全仓无 `addEventListener('storage')`（rg 零命中），`set` 也不派发任何领域事件。因此「A 插件写 storage → B 插件自动刷新」没有现成通道——要么 A 同时 `ctx.events.emit` 一条 §6.1 的通知，要么 B 只能轮询/在交互时重读。跨标签页同理：物理上共享 localStorage，但两个页面的插件实例互不知晓（浏览器原生 `storage` 事件未被任何代码使用，**未实测**跨标签行为）。

还有一层更硬的局限：**B 根本读不到 A 的键**。storage 键带 `plugin:<插件id>:` 前缀且实现为每插件独立闭包（`pluginLoader.ts:142-165`），跨插件没有共享桶、也没有 `keys()` 之类的枚举 API。所以「用 storage 做插件间共享状态」在当前实现下不成立；完整的分工应该是：

- **「今后」的实时性** → §6.1 的事件广播（同页面、fire-and-forget）；
- **「到场时」的一致性** → 数据落 worker 侧（后端插件自持 `pluginDir()` 文件 + 自注册 RPC，见[后端持久化与状态](../backend/persistence-and-state.md)），前端插件激活后主动拉取；
- storage 只放**本插件自己的** UI 偏好（折叠态、最近选择等）。

## 7. 完整监听示例（订阅、防重复、清理）

插件没有组件树，运行期也没有卸载时机（宿主只在页面卸载时统一调 `deactivate` 并 dispose 全部收集的注册项，见[前端 ctx API](context-api.md) §9）——运行期「清理」的真正语义只有两个：**React 组件卸载**（useEffect return）和**你自己想停止监听**。完整骨架：

```ts
// web/index.ts —— 入口：模块级收集 Disposable + 防重复绑定
import type { PluginContext, PluginModule, Disposable } from '@everyagent/plugin-api'
import { refresh } from './state'

let pluginCtx: PluginContext | null = null
const bound: Disposable[] = []

// 可选：集中收集器——便于调试时 dump 全部订阅，或将来做「停听」开关
function track(d: Disposable): Disposable {
  bound.push(d)
  return d
}

function bindEvents(ctx: PluginContext): void {
  // 防重复订阅：activate 理论上每会话只跑一次（loadedPlugins 幂等），这层是保险
  if (bound.length > 0) return
  track(
    // ① 高频事件：先过滤再动作（§4.2）
    ctx.events.on('workspace-file-changed', (payload) => {
      const p = payload as { workspaceRoot?: string; filePath?: string }
      if (p.filePath?.endsWith('.md')) refresh(p.workspaceRoot ?? '')
    }),
  )
  track(
    // ② 低频边沿：面板点亮懒刷新（§5.4 姿势）
    ctx.events.on('sidebar-panel-shown', (payload) => {
      if ((payload as { panelId?: string })?.panelId !== 'my-plugin') return
      refresh(pluginCtx?.sdk.workspace.rootPath ?? '')
    }),
  )
  track(
    // ③ 轮闭合作废缓存（file-change 同款，见 §3.1）
    ctx.events.on('task-round-closed', (payload) => {
      const taskId = (payload as { taskId?: string })?.taskId
      if (taskId) invalidateCache(taskId)
    }),
  )
}

const plugin: PluginModule = {
  activate(ctx) { pluginCtx = ctx; bindEvents(ctx) },
}
export default plugin

function invalidateCache(_taskId: string): void { /* ... */ }
```

```tsx
// web/MyPanel.tsx —— 组件级订阅：卸载语义 = useEffect 清理
import React from 'react'

export default function MyPanel(): React.ReactElement {
  React.useEffect(() => {
    // 组件挂载期间才需要的事件，挂组件上而不是 activate 上
    const d = pluginCtx?.events.on('workspace-registry-changed', () => refresh(''))
    return () => d?.dispose()   // 卸载即退订；面板隐藏期间不再收事件
  }, [])
  return <div>...</div>
}
```

分工原则：**与插件同寿命的监听放 `activate`（无需持有 Disposable，页面刷新自然清零）；与组件同寿命的监倾听生命周期**。git 的实战代码两处都有（激活级无 / 组件级 `GitSidebarPanel.tsx:334-339`、`usePluginWorkspaces.ts:31-37`，后者把 `dispose` 放进 useEffect return）。

## 8. 下一步读

- 事件挂探针、typecheck 与构建自检的调试流程：[调试与测试](../guides/debugging-and-testing.md)
- 用事件做面板懒刷新的另一半——12 个 UI 扩展点：[前端 UI 扩展点](ui-extensions.md)
- `ctx.events` 在 ctx 全家桶里的位置与 `storage`/`sdk` 细节：[前端 ctx API](context-api.md)
- 事件名为什么必须「不含业务语义」（§7.15.2 轮末旁路数据约定）：[后端持久化与状态](../backend/persistence-and-state.md)
