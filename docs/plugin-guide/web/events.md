---
title: 前端事件
nav_order: 12
parent: web
has_children: false
---

# 前端事件

**一句话定位**：`ctx.events` 是前端插件感知（和驱动）宿主 UI 的唯一事件通道——它是宿主进程内一条模块级事件总线的最薄委托。本文给出**全部 38 个宿主事件的逐个取证表**（载荷、触发时机、来源文件：行号、监听价值），并如实标注其中 **24 个当前没有任何 emit 调用点**（含 plugin-api 具名 9 个中的 4 个）——订阅它们等于订阅一个永远不会响的铃。事件名与载荷类型的唯一登记处是 `every-agent-web/src/events/domainEvents.ts`（常量表 `:91-130`，载荷类型 `DomainEventMap` `:132-362`）。

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

事件本身是 fire-and-forget：**没有重放、没有「补齐」**——插件激活晚于某次 emit 就永远错过它（这正是 §5.1 `plugins-loaded` 即便补上 emit 也帮不了晚加载插件的原因）。

## 2. plugin-api 的 9 个具名事件

`PluginDomainEvent`（`every-agent-plugin-api/js/index.ts:149-159`）只具名列 9 个事件名 + `(string & {})` 兜底（兜底写法让 IDE 补全优先提示具名、同时放行任意自定义字符串）：

| 具名事件 | 当前真实会发生？ | 详见 |
|---|---|---|
| `workspace-file-changed` | ✅（高频） | §3.1 |
| `workspace-registry-changed` | ✅ | §3.1 |
| `sidebar-panel-shown` | ✅ | §3.1、§5.4 |
| `task-status-changed` | ✅ | §3.1 |
| `task-round-closed` | ✅ | §3.1 |
| `task-created` | ❌ 零 emit | §3.2 |
| `task-deleted` | ❌ 零 emit（但有插件在订阅，见 §5.3） | §3.2 |
| `task-trace-changed` | ❌ 零 emit | §3.2 |
| `file-content-saved` | ❌ 零 emit | §3.2 |

⚠️ **「进了具名清单」≠「事件存在」**：具名清单是手维护的子集（9/38），且其中 4 个当前不会发生（§3.2）。写订阅前先查 §3 的两张表。类型原文如下（`every-agent-plugin-api/js/index.ts:148-167` 摘录）：

```ts
/** 领域事件名（与宿主 DOMAIN_EVENTS 同名值，字符串字面量联合）。 */
export type PluginDomainEvent =
  | 'workspace-file-changed'
  | 'workspace-registry-changed'
  | 'sidebar-panel-shown'
  | 'file-content-saved'
  | 'task-created'
  | 'task-deleted'
  | 'task-status-changed'
  | 'task-trace-changed'
  | 'task-round-closed'
  | (string & {})    // 兜底：放行任意自定义字符串，补全仍优先提示上面 9 个

export interface PluginEvents {
  on(eventName: PluginDomainEvent, handler: (payload: unknown) => void): Disposable
  emit(eventName: PluginDomainEvent, payload: unknown): void
}
```

## 3. 宿主事件总表（38 个，逐一回源码复核）

统计口径（rg 实测）：`DOMAIN_EVENTS` 常量表声明 **38** 个事件名（`domainEvents.ts:91-130`）；全仓（`every-agent-web/src` + `every-agent-plugins` + `every-agent-desktop`）`.emit(` 调用点合计仅命中 **14** 个事件名。下列两张表即按这个差集切分。

### 3.1 真实会发生的事件（14 个）

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
| `user-interaction-requested` | `{taskId, agentId, request}`（`:287-294`） | ask 等待用户作答：实时 `ask.create`（`askStore.ts:285`）、静默注册后补发（`:263`）、回放 debounce flush（`:230`，50ms 内被 settle 则跳过防闪烁） | 中。宿主已完整消费（弹窗 `UserInteractionHost.tsx:108`、系统通知 `BrowserNotificationHost.tsx:71`、角标 `PendingUserInteractionIndicator.tsx:43`）；插件一般无需再听 |
| `user-interaction-resolved` | `{taskId, agentId, result}`（`:295-302`） | 用户提交交互结果时（`askStore.ts:355`） | 中低。同上，多为补充感知 |
| `user-interaction-cleared` | `{taskId, agentId, interactionId}`（`:303-310`） | ask 落定（提交/超时/取消）移除卡片时（`askStore.ts:306`；回放期间从未广播过的跳过） | 中低。同上 |
| `workspace-open-user-interaction-requested` | `{interactionId}`（`:311-314`） | 通知卡片/悬浮菜单被点击，请求把交互请求带到前台（`askStore.ts:203`、`BrowserNotificationHost.tsx:43`、`PendingUserInteractionIndicator.tsx:73`；消费方 `UserInteractionHost.tsx:111`） | 低。宿主内部导航协议 |
| `workspace-focus-task-requested` | `{taskId}`（`:212-214`） | 浏览器系统通知被点击时（`BrowserNotificationHost.tsx:65`；消费方 `Layout.tsx:743`） | 中。**插件也可以 emit 它**让宿主聚焦某任务标签——这是单边接线事件里反向可用的一类（§3.2 说明） |
| `workspace-search-panel-requested` | `{workerId, workspaceRoot, rootPath, label, target?}`（`:259-270`） | 文件树右键「搜索」/任务列表工作区组「搜索」（`OpenFilesSidebarPanel.tsx:681-688`、`TasksPanel.tsx:381-388`；消费方内置 `SearchPanel.tsx:510-511`） | 低。宿主内部跳转协议，插件没有搜索面板可接 |

### 3.2 声明了但全仓零 emit 的事件（24 个，⚠️ 订阅无效）

以下事件名在 `DOMAIN_EVENTS` 有登记、`DomainEventMap` 有载荷类型，但 rg 全仓（含 `every-agent-plugins`、`every-agent-desktop`）**没有任何 `.emit(` 调用点**——运行中永远不会发生。表中「等待方」列标出仍在 `subscribe` 的宿主/插件代码（单边接线）。

| 事件名 | 载荷（声明） | 等待方（订阅但等不到） | 结论 |
|---|---|---|---|
| `workspace-open-file-requested` | `{filePath, workspaceRoot?, startNameEditing?, mode?, lineNumber?}`（`:215-226`） | `Layout.tsx:689`（打开文件标签） | 宿主在等、宿主从不发；**插件可以 emit 它驱动宿主打开文件**（等效 `ctx.ui.openFileTab`，见 [UI 扩展点](ui-extensions.md) §15） |
| `workspace-close-file-requested` | `{filePath?, fileTabId?, force?}`（`:227-231`） | `Layout.tsx:706` | 同上：可作插件关标签的请求通道 |
| `workspace-reload-all-files-requested` | `{force?}`（`:232-235`） | `Layout.tsx:746`、`OpenFilesSidebarPanel.tsx:260` | 同上：请求宿主重载全部文件标签 |
| `runtime-config-error` | `{message}`（`:209-211`） | `Layout.tsx:686`（弹错误提示） | 宿主在等、无人发；插件 emit 会让宿主弹一条错误横幅（慎用） |
| `task-context-monitor-changed` | `{taskId, agentId, snapshot}`（`:184-191`） | `ContextBattery.tsx:49`（上下文电量条） | 单边接线：UI 在等，事件源从未接上（上下文监控数据走组件自取，不经总线） |
| `task-deleted` | `{taskId}`（`:164-166`） | **file-change 插件**（`every-agent-plugins/file-change/web/index.ts:28-31`，作废缓存） | ⚠️ 内置插件的真实死订阅：任务删除实际走 `taskStore.remove()`（`taskStore.ts:515-521`）**不发事件**——该作废路径永不触发，见 §5.3 |
| `task-created` | `{taskId}`（`:161-163`） | 无 | 任务创建经 `taskStore.trackCreated/upsert` 直接入库，不发事件 |
| `task-trace-changed` | `{taskId, action: 'append'\|'replace'\|'remove', trace?}`（`:175-179`） | 无 | trace 增量走 taskStream 自有 notify 通道（监听者模式，`taskStream.ts` 内部），不经总线 |
| `task-token-usage-changed` | `{taskId, tokenUsage}`（`:180-183`） | 无 | token 用量随任务频道帧更新，不发事件 |
| `task-protocol-state-changed` | `{taskId, protocolId?, data}`（`:192-196`） | 无 | 无 emit |
| `task-turn-started` | `{taskId, mainAgentId, ts}`（`:331-338`） | 无 | 无 emit |
| `task-turn-completed` | `{taskId, mainAgentId, ts}`（`:339-346`） | 无 | 无 emit |
| `file-content-saved` | `{taskId?, agentId?, filePath, before, after, changeType, ts}`（`:315-330`） | 无 | 文件保存经 `workspace-file-changed` 表达；这个「带内容前后文」的版本从未接线（**未实测**运行期行为，结论基于全仓 emit 零命中） |
| `agent-updated` | `{taskId, agentId, agent?}`（`:133-140`） | 无 | agent 台账变化不经总线 |
| `agent-message-appended` | `{taskId, agentId, message?}`（`:141-148`） | 无 | 消息追加经 taskStream 折叠器，不发事件 |
| `agent-message-streaming` | `{taskId, agentId, messageId, action, message?}`（`:149-160`） | 无 | 流式增量经 taskStream 折叠器，不发事件 |
| `settings-llm-profiles-patched` | `{changedAt}`（`:200-203`） | 无 | 设置页自己重查，不发事件 |
| `settings-guardrail-patched` | `{changedAt}`（`:204-207`） | 无 | 无 emit |
| `settings-prompt-templates-patched` | `{changedAt}`（`:208`） | 无 | 无 emit |
| `app-notification-added` | `{notification}`（`:277-279`） | 无 | 通知外壳 `notifyApp` 直连 antd（`every-agent-web/src/utils/appNotifications.ts:12-37`），不发事件 |
| `app-notification-removed` | `{notificationId}`（`:280-282`） | 无 | 同上（`appNotifications.ts:39-42`） |
| `workspace-open-ai-call-log-requested` | `{taskId?, callId}`（`:283-286`） | 无 | 无 emit、无订阅，彻底闲置 |
| `agent-run-event` | `AgentRunEvent` 联合（5 种 kind，`:17-50,361`） | 无 | 死事件；类型注释引用的 `src/task/agentRunEventBridge.ts`（`domainEvents.ts:61`）**在仓库中不存在**，见 §5.2 |
| `plugins-loaded` | `{count}`（`:355-358`） | 无 | 死事件，见 §5.1 |

用法建议浓缩成三句：**「要感知任务/文件变化，先查 §3.1 有没有现成事件；§3.2 里带 `*-requested` 后缀的可以反过来被插件 emit 去驱动宿主；其余 24 个中的非请求类不要订阅**（写了也能编译通过——这正是陷阱所在，`PluginDomainEvent` 的 `(string & {})` 兜底不拦截）。

### 3.3 为什么一半事件是死的：前端五条通知通道的分工

38 声明 vs 14 实发不是单纯的烂尾，而是**通知通道分工**的结果——任务/消息的细粒度变化走了更高效的专用通道，领域总线只保留「跨模块、低频、边沿触发」的那部分：

| 通知通道 | 承载的数据 | 消费方式 | 与插件的关系 |
|---|---|---|---|
| `domainEventBus`（本文） | 跨模块边沿事件（状态翻转、面板点亮、轮闭合、文件变化） | `ctx.events.on` | **唯一开放给插件的通道** |
| `taskStore.subscribe`（`taskStore.ts:532-537`，回调立即喂全量列表） | 任务列表镜像（worker tasks 频道 upsert 而来） | 回调取全量 | 插件不可直接订阅；经 `task-status-changed` 事件间接感知 |
| taskStream 折叠器 notify（`taskStream.ts` 内部监听者集合） | 消息/trace 的流式增量 | 宿主线程组件专用 | 插件侧接 UI 扩展点（tool_call_views、round_tail_panels 等，见[UI 扩展点](ui-extensions.md)）让宿主替你渲染 |
| `subscribeExtensionsChanged` + `getExtensionsVersion`（§4.3） | 扩展点注册表变化 | `useSyncExternalStore` | 插件注册即自动触发，无需自己发事件 |
| Composer 面板 ctx 的 `subscribeTaskEvents`（[UI 扩展点](ui-extensions.md) §6） | `'task.updated'` / `'task.stream'` 刷新信号 | 面板组件 props 回调 | 只回发两个信号名、`agentId`/`payload` 恒 null，当刷新脉冲用 |

一个迁移实例可以直接读到这种分工：`TaskChatTabLabel` 的头注释自述「旧版按激活任务订阅 domainEventBus 的 `TASK_STATUS_CHANGED`；hub 版任务真相源在 worker，前端镜像 taskStore……直接订阅 taskStore」（`every-agent-web/src/components/task/TaskChatTabLabel.tsx:6-9`，现行代码只订 taskStore、零事件调用）。因此 §3.2 里 agent/task 细粒度类死事件的合理解读是：**它们的设计位被折叠器/镜像订阅取代了，只是 `DOMAIN_EVENTS` 常量表没有随之清理**。

## 4. 高频事件、性能与「稳定快照」机制

### 4.1 真正的高频源

实测会高频触发的基本只有 `workspace-file-changed`：流式任务里每个写文件工具调用都会产生一条 worker `fs.changed` 广播（`workspaceGateway.ts:186-194`），批量编辑时连续多发。其余 13 个活事件都是低频边沿（状态翻转、面板点亮、轮闭合）。注意方向：**流式文本增量完全不走这条总线**（`agent-message-streaming`/`task-trace-changed` 均为 §3.2 死事件）——不要为了追流式输出来订阅事件，那是 taskStream 内部通道，插件侧的正确姿势是接 UI 扩展点（如 [UI 扩展点](ui-extensions.md) §7/§14）让宿主替你渲染。

### 4.2 监听高频事件的纪律

1. **handler 里只做标记，不做重活**：派发在微任务里同步串行（`eventBus.ts:13-17`），一个慢 handler 会推迟同事件的所有后续 handler。
2. **按载荷过滤早退**：`workspace-file-changed` 先比 `workspaceRoot`/扩展名再决定是否动作（`FileTabPage.tsx:135` 的宿主自用范例同款思路）。
3. **不要在 handler 里同步 emit 同名事件**：会形成异步自激循环（微任务链条不会爆栈，但会空转）。

### 4.3 `subscribe` + `getExtensionsVersion`：另一条别混淆的订阅线

「扩展点贡献变化」**不是**领域事件，而是 `pluginDispatcher` 自己的一对快照接口（`every-agent-web/src/plugin/PluginDispatcher.ts:70-95`）：任一扩展点注册/注销都会 `notifyExtensionsChanged`（内部自增 `extensionsVersion`，`:70-75`），宿主组件用 `React.useSyncExternalStore(subscribeExtensionsChanged, getExtensionsVersion)` 感知并重渲染（用法见 [UI 扩展点](ui-extensions.md) §0 第 2 条）。**扩展点贡献必须可 `subscribe`、版本号必须是稳定快照**（同一版本内 `get` 返回恒定）——这是 `useSyncExternalStore` 的硬契约，否则 React 会无限重渲染。插件自己不直接碰这对接口；知道它的存在是为了别把「注册了个扩展点」误当成一条领域事件去 `ctx.events.on` 等——那样的信号不存在。

## 5. 死事件与陷阱逐条核实

### 5.1 `plugins-loaded`：有声明、无 emit（核实为死）

声明于 `domainEvents.ts:127`（名）与 `:355-358`（载荷 `{count}`）；全仓 rg `'plugins-loaded'` 与 `PLUGINS_LOADED` 除定义外零命中——`loadPlugins()` 完成后**静默返回**，不广播。插件想要「宿主插件都就绪了」的信号目前**不存在**；且如 §1.3 所述，事件无重放，晚激活的插件本来也听不到早于自己激活的 emit。别订阅它。

### 5.2 `agent-run-event`：死事件，注释还引用了不存在的文件

声明于 `domainEvents.ts:126,361`，载荷 `AgentRunEvent`（5 种 kind 的联合，`:17-50`）与 `AgentRunEventPayload`（`:52-89`）类型完备，但全仓零 emit、零订阅。更实的证据：`domainEvents.ts:61` 注释里「上行能力全部收口在 task 层注入的链节点 / 事件桥（见 src/task/agentRunEventBridge.ts）」——`every-agent-web/src/task/` 目录下**没有这个文件**（目录实存：eventFolder/eventRegistry/task-packet-buffer/task-packet-view/task-poll/taskStatusPresentation/taskStore/taskStream/types）。结论：这是一套规划中未接线（或已删除实现）的事件面，连同两个载荷类型一起闲置。

### 5.3 `task-deleted`：内置插件踩中的真实死订阅

file-change 插件订阅它作废轮次缓存（`every-agent-plugins/file-change/web/index.ts:28-31`），但任务删除的真实路径是 `TasksPanel` → `task.delete` RPC → worker 频道 `task.deleted` 帧 → `taskStore.remove()`（`taskStore.ts:262,515-521`）——**整条链路不发领域事件**。后果：任务删除后 file-change 的缓存不会失效（下次同 id 任务不复存在，实际影响有限，但逻辑上是漏的）。给你的教训：**订阅前先在 §3.1 确认事件真的会响**，不要看名字合理就挂监听；同插件里的 `task-round-closed`（`:24-27`）就是活的，一死一活正好对照。

### 5.4 `sidebar-panel-shown` 的正确用法

- **触发时机**：面板 `visible` 从 false→true 的边沿（`Layout.tsx:1107-1113`）——不是「面板已显示」的状态，而是「刚被点亮」的脉冲。面板常驻 DOM、非激活态仅 `display:none`（[UI 扩展点](ui-extensions.md) §2），所以「面板重新可见」每次都会发。
- **先过滤 panelId**：事件发给全体订阅者，git 的姿势是第一行就 `if (payload.panelId !== 'git') return`（`GitSidebarPanel.tsx:334-337`）。内置面板 id 也会出现（`tasks`/`files`/`search`/`settings`），别误把别人的脉冲当自己的。
- **正确用途**：面板懒刷新——用户点亮面板时拉最新状态（git 同款）；**错误用途**：当「页面加载完成」或「插件激活完成」信号用。

### 5.4.b 具名 9 中的 4 个死事件

`file-content-saved`、`task-created`、`task-deleted`、`task-trace-changed` 详见 §3.2 对应行。plugin-api 的具名清单是**手维护快照**，落后于宿主实况——以 §3 两张表为准。

顺带解释「为什么 TypeScript 拦不住死事件订阅」：`PluginDomainEvent` 的兜底 `(string & {})`（`index.ts:159`）让任何字符串字面量都是合法事件名——类型系统只校验「这是个 string」，不校验「宿主真的会 emit 它」。死事件的唯一可靠判据就是本文 §3 的两张表（rg emit 调用点实测）；后续宿主演进若接活了某个事件，以 `DOMAIN_EVENTS` 常量表 + 全仓 emit 复查为准，不要依赖本文快照的永久性。

## 6. 插件间通信模式

### 6.1 松耦合的正确姿势：约定事件名 + 无监听者静默

`emit` 无监听者静默（§1.1）天然支持「发送方不关心有没有人听」的松耦合广播。**内置 25 个插件中没有任何一个调用过 `ctx.events.emit`**（rg `events.emit(` 于 `every-agent-plugins` 零命中——插件侧 6 处调用全是 `on`），所以**没有内置先例**；下例是按机制推导的推荐写法：

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

插件没有组件树、也没有卸载（宿主永不调 `deactivate`、`disposables` 恒空，见[前端 ctx API](context-api.md) §9）——「清理」的真正语义只有两个：**React 组件卸载**（useEffect return）和**你自己想停止监听**。完整骨架：

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
