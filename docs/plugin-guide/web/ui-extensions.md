---
title: 前端 UI 扩展点
nav_order: 11
parent: web
has_children: false
---

# 前端 UI 扩展点

**一句话定位**：`ctx.ui`（`UiRegistry`）是前端插件往宿主 UI 里「贡献界面」的唯一通道——13 个 `register*` 扩展点 + 5 个动作方法。本文逐扩展点给出 Definition 字段表（抄自纯类型包 `every-agent-plugin-api/js/index.ts`）、注册代码、宿主消费链与坑；每条断言附宿主源码行号。`ctx.ui` 的宿主实现是单例 `pluginDispatcher`（接入方式见[前端 ctx API](context-api.md) §8）。

## 0. 读前须知：所有扩展点共用的四条机制

1. **注册即入表，Disposable 真清理**：每个 `register*` 落到对应扩展点的 `ListExtensionRegistry`（普通数组 push，`every-agent-web/src/plugin/ExtensionRegistry.ts:45-56`），dispose 从数组 splice 并通知订阅者。注意 dispatcher 调 `register('', def)` 时**忽略 pluginId 形参**、一律传空串（`every-agent-web/src/plugin/PluginDispatcher.ts:241-242` 等 13 处）。
2. **注册晚于首屏，宿主靠版本号重渲染**：插件 `activate()` 是异步的。宿主用 `React.useSyncExternalStore(pluginDispatcher.subscribeExtensionsChanged, pluginDispatcher.getExtensionsVersion)` 感知注册（`every-agent-web/src/components/app/Layout.tsx:139-141`、`every-agent-web/src/components/task/TaskChat.tsx:818-821`），注册后贡献立即上屏，**无需刷新页面**。
3. **两条读取通道**：异步 `dispatch('ui.xxx')` / `get*()`（仅文件页侧栏在用，`FileTabPage.tsx:166`）与同步 `listRegistered*()`（其余消费点的实际用法，下文逐个给出）。
4. **示例代码约束**（详见[加载链路](overview-and-loading.md)）：入口 `web/index.ts` 只能 `import type ... from '@everyagent/plugin-api'` + 白名单 bare import（react / react-dom / react/jsx-runtime / antd / @ant-design/icons）；`.ts` 文件里用 `React.createElement`，组件放 `.tsx` 可用 JSX。

## 1. 十三个扩展点总览

| 扩展点（dispatch 名） | register 方法 | 一句话 | 内置范例 |
|---|---|---|---|
| `ui.sidebar_items` | `registerSidebarItem` | 左侧活动栏入口 + 面板 | git、plugin-manager |
| `ui.workspace_tab_types` | `registerWorkspaceTabType` | 自定义工作区标签类型 | git（git-history） |
| `ui.file_sidebar_panels` | `registerFileSidebarPanel` | 文件页右侧栏面板 | **无** |
| `ui.composer_above_panel`（**单数**） | `registerComposerAbovePanel` | 输入框上方面板 | task-input-queue |
| `ui.tool_call_views` | `registerToolCallView` | 按工具名整体接管工具调用渲染 | update-file-view |
| `ui.user_message_actions` | `registerUserMessageAction` | 用户消息气泡旁的动作按钮 | task-edit-resend |
| `task.submit_contributions` | `registerTaskRunSubmitContributionProvider` | 提交前向 task.run 追加 metadata/改按钮 | task-edit-resend |
| `ui.trace_types` | `registerTraceType` | 按 kind 接管 trace 条目渲染 | ai-review（auth.review） |
| `ui.output_blocks` | `registerOutputBlock` | 渲染消息里的 `<tag>…</tag>` 输出块 | **无** |
| `ui.file_content_editors` | `registerFileContentEditor` | 按扩展名注册文件编辑器 | pdf-viewer（.pdf） |
| `ui.file_explorer_actions` | `registerFileExplorerAction` | 文件树右键菜单项 | git（「显示 Git 历史」，追加在内置项尾部，见 §13） |
| `ui.round_tail_panels` | `registerRoundTailPanel` | 任务轮末展示区 | file-change |
| `ui.search_types` | `registerSearchType` | 注册搜索类型（id/label/kinds/filters/ResultView…） | ⚠️ 无内置范例（内置三类型不走此扩展点） |

## 2. `ui.sidebar_items` —— 侧边栏活动栏项

### 2.1 Definition 字段表（`UiSidebarItemDefinition`，`js/index.ts:219-239`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `id` | `string` | ✅ | `Layout.tsx:1081` | 同时作为 `SidebarPanelId`：面板显隐互斥（`Layout.tsx:855-858`）、面板 id 合法性兜底（`Layout.tsx:276-284`）都用它 |
| `title` | `string` | ✅ | `Layout.tsx:1082` | 活动栏 tooltip / 面板标题 |
| `icon` | `ReactNode` | ✅ | `Layout.tsx:1083` | 活动栏图标（React 节点，须自带组件） |
| `Panel` | `ComponentType` | ✅ | `Layout.tsx:901-902` | 点击入口后的面板组件；面板常驻 DOM、非激活态 `display:none`（`Layout.tsx:1091-1096` 注释） |
| `badgeCount` | `number?` | — | `Layout.tsx:1084` | `>0` 时图标显示角标数字 |
| `Badge` | `ComponentType?` | — | `SidebarActivityBar.tsx`（活动栏图标内渲染） | 角标渲染组件（插件自管订阅与刷新，无变更返回 null 即不显示）；与 `badgeCount` 二选一，同时给出时都渲染 |
| `order` | `number?` | — | `Layout.tsx:1085` | float 升序混排，缺省 100。坐标系见 §3 |

### 2.2 注册示例（照抄 plugin-manager，`every-agent-plugins/plugin-manager/web/index.ts:17-25`）

```ts
import React from 'react'
import type { PluginModule } from '@everyagent/plugin-api'
import MyPanel from './MyPanel'
import { MyIcon } from './icons'

const plugin: PluginModule = {
  activate(ctx) {
    ctx.ui.registerSidebarItem({
      id: 'my-plugin',            // 全局唯一，即面板 id
      title: '我的面板',
      icon: React.createElement(MyIcon),   // .ts 入口用 createElement；组件可放 .tsx 用 JSX
      Panel: MyPanel,             // 无 props
      order: 6,                   // 见 §3 坐标系；不写则落 100（内置项之后）
      // badgeCount: 3,           // 数字角标（由插件自己维护刷新）
      // Badge: MyBadge,          // 或自定义角标组件（自管订阅，无变更返回 null；git 的 GitChangeBadge 先例）
    })
  },
}
export default plugin
```

### 2.3 宿主消费链与坑

- **消费点 4 处**：活动栏图标条目 `buildSidebarActivityItems`（`Layout.tsx:1069-1086`）、面板槽位数组 `sidebarPanels`（`Layout.tsx:899-903`）、面板互斥高亮 `activeActivityItemIds`（`Layout.tsx:853-868`）、面板 id 合法性兜底（`Layout.tsx:276-284`，插件注销后回落到「任务」）。
- **角标两条通道**：`badgeCount`（数字）与 `Badge`（组件）都经 `buildSidebarActivityItems` 透传、由 `SidebarActivityBar` 在图标内渲染（Badge 历史上是死字段，known-issues #2 已修复接线）。
- **坑**：① `order` 缺省 100 会排在所有内置项之后；② 内置范例：git（`every-agent-plugins/git/web/index.ts:28-37`，order=5 + GitChangeBadge）、plugin-manager（`every-agent-plugins/plugin-manager/web/index.ts:17-25`，order=9）。

## 3. 侧边栏 `order` 坐标系（专节）

float 升序**统一混排**，不再有「内置在前、插件在后」的注册顺序假设（`Layout.tsx:1069-1071` 注释）；同值稳定排序（ES2019 稳定 sort，`Layout.tsx:1072`）。支持小数插队（`js/index.ts:233-237` 文档注释，如 `4.5`）。

| order | 项 | 来源 |
|---|---|---|
| 1 | 内置 tasks「任务」 | `Layout.tsx:1075` |
| 2 | 内置 files「文件」 | `Layout.tsx:1076` |
| 3 | 内置 search「搜索」 | `Layout.tsx:1077` |
| 5 | git「源代码管理」 | `every-agent-plugins/git/web/index.ts:35` |
| 9 | plugin-manager「扩展」 | `every-agent-plugins/plugin-manager/web/index.ts:24` |
| 10 | 内置 settings「设置」 | `Layout.tsx:1078` |
| 100 | 缺省 `DEFAULT_SIDEBAR_ORDER` | `Layout.tsx:1064` |

注意 5 与 9 是**插件**取值不是内置位——插手别处请避开 1/2/3/10 四个内置占用；4（3 与 5 之间）、6~8、11~99 都是空位。其余 12 个扩展点**没有 order 字段**，一律「插件在前、按注册顺序」合并（§4/§7/§12 各自证据）。

## 4. `ui.workspace_tab_types` —— 工作区标签类型

### 4.1 Definition 字段表（`UiWorkspaceTabTypeDefinition`，`js/index.ts:258-285`；`WorkspaceTabRenderContext` `js/index.ts:241-255`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `tabTypeKey` | `string` | ✅ | `workspaceTabTypes.ts:57-59` | 查表 key；也是 `ctx.ui.openPluginTab(type,…)` 的 `type`（`Layout.tsx:525`） |
| `pluginId` | `string` | ✅ | `Layout.tsx:526` | 插件填插件 id；仅用于标签归属展示 |
| `renderTab` | `(tab, ctx) => ReactNode` | ✅ | `Layout.tsx:1054` | 渲染标签主体（工作区主区域） |
| `renderIcon` | `(tab) => ReactNode` | ✅ | `TitleBar.tsx:101` | 标签条图标 |
| `getLabel` | `(tab) => string` | ✅ | `TitleBar.tsx:103` | 标签条短文字 |
| `renderLabel` | `(tab) => ReactNode?` | — | `TitleBar.tsx:102` | 自定义标签内容，优先于 `getLabel` |
| `getTitle` | `(tab) => string` | ✅ | `TitleBar.tsx:104` | tooltip |
| `getCloseAriaLabel` | `(tab) => string` | ✅ | `TitleBar.tsx:105` | 关闭按钮无障碍文案 |
| `getSidebarActivityId` | `(tab) => SidebarPanelId \| null?` | — | `Layout.tsx:858-866` | 激活该标签时点亮哪个活动栏图标（git 返回 null，git-history 标签不点亮「源代码管理」避免破坏面板互斥） |
| `onActivate` | `(tab, ctx) => void?` | — | `Layout.tsx:362,399,735` | 标签成为激活态的副作用 |
| `onClose` | `(tab, ctx) => void?` | — | `Layout.tsx:678-679` | 关闭标签时的清理 |

### 4.2 注册示例与消费链

最小示例（完整范例 `every-agent-plugins/git/web/GitHistoryTabType.tsx:24-45`）：

```tsx
import type { UiWorkspaceTabTypeDefinition, PluginWorkspaceTab } from '@everyagent/plugin-api'
import { MyIcon } from './icons'
import MyPanel from './MyPanel'

export const myTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: 'my-view',
  pluginId: 'my-plugin',
  renderTab: (tab: PluginWorkspaceTab) => <MyPanel data={(tab as { data?: Record<string, string> }).data ?? {}} />,
  renderIcon: () => <MyIcon />,
  getLabel: (tab) => tab.title ?? '',
  getTitle: (tab) => '我的视图',
  getCloseAriaLabel: (tab) => `关闭我的视图 ${tab.title ?? ''}`,
  getSidebarActivityId: () => null,
}
// activate 里：ctx.ui.registerWorkspaceTabType(myTabType)
```

消费链：`allDefinitions()` 合并时**插件在前**——`[...pluginDispatcher.listRegisteredWorkspaceTabTypes(), ...builtinDefinitions]`（`every-agent-web/src/plugin/workspaceTabTypes.ts:25-27`），`getWorkspaceTabTypeDefinition` 用 `find` 查表（`:57-59`）⇒ 同名 `tabTypeKey` 插件定义**覆盖内置**（内置清单见 `:15-24`，git-history 已从内置迁出、由插件注册）。

- **坑**：① `renderTab` 里先做 `tabType` 判别、不匹配返回 `null`（`GitHistoryTabType.tsx:28-29` 同款兜底）；② `openPluginTab` 的 `type` 必须与 `tabTypeKey` 一致，查不到定义时 `pluginId` 会回退成 type 本身（`Layout.tsx:525-527`）；③ `PluginWorkspaceTab` 是最小化替身类型，`pluginTabType` 等字段需强转访问（`GitHistoryTabType.tsx:28`）。
- **内置范例**：git（`every-agent-plugins/git/web/index.ts:39`）。

## 5. `ui.file_sidebar_panels` —— 文件页侧栏面板

### 5.1 Definition 字段表（`UiFileSidebarPanelDefinition`，`js/index.ts:288-303`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `id` | `string` | ✅ | `FileTabPage.tsx:208-210,236-239` | 面板 id；当前选中侧栏面板失配时自动收起 |
| `title` | `string` | ✅ | `FileTabPage.tsx:446` | 出现在文件页「更多」菜单里（`显示/隐藏<title>`） |
| `isAvailable` | `(file) => boolean?` | — | `FileTabPage.tsx:204-205` | 当前文件是否可展示；缺省可展示 |
| `Panel` | `ComponentType<{file, requestRefresh}>` | ✅ | `FileTabPage.tsx:638-644` | 渲染在文件内容右侧 `<aside>`；`requestRefresh` 只是把内部刷新计数 +1（`:641-642`） |

### 5.2 注册示例与消费链

```ts
ctx.ui.registerFileSidebarPanel({
  id: 'my-notes-outline',
  title: '笔记大纲',
  isAvailable: (file) => file.filePath.endsWith('.md'),
  Panel: ({ file, requestRefresh }) => { /* 依据 file.filePath 渲染；完成后 requestRefresh() */ return null },
})
```

消费链：文件标签页挂载时异步 `dispatch('ui.file_sidebar_panels')` 拉快照（`FileTabPage.tsx:164-177`，**全宿主唯一的 dispatch 消费点**），`isAvailable` 过滤（`:204-205`）后进「更多」菜单（`:443-451`），选中后 `<aside>` 渲染（`:638-644`）。

- **坑**：① **无内置范例**——`every-agent-plugins` 全目录 rg `registerFileSidebarPanel` 零命中，本扩展点从无实战样本；② `Panel` 拿不到编辑器实例，只能靠 `requestRefresh` 触发整页刷新计数。
- **内置范例**：无（上表导航表如实标注）。

## 6. `ui.composer_above_panel` —— 输入框上方面板（注意单数！）

### 6.1 Definition 字段表（`UiComposerAbovePanelDefinition`，`js/index.ts:364-370`；`ComposerPanelCtx` `js/index.ts:348-361`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `id` | `string` | ✅ | `TaskChat.tsx:945` | 渲染 key |
| `Component` | `ComponentType<ComposerPanelCtx>` | ✅ | `TaskChat.tsx:946` | 接收下表 ctx 作为 props |

`ComposerPanelCtx` 各字段（宿主逐字段构造于 `TaskChat.tsx:832-860`）：

| ctx 字段 | 类型 | 宿主实现 | 说明 |
|---|---|---|---|
| `taskId` | `string \| undefined` | `:833` | 草稿任务为 `undefined` |
| `draft` | `PluginComposerDraftState` | `:834` | `{text, rawContent, tokens}`，引用每次渲染最新 |
| `isRunning` | `boolean` | `:835` | 任务是否运行中 |
| `selectAgent` | `(agentId \| null) => void` | `:836-838` | 联动「只看该 agent」过滤 |
| `rpc` | `(method, params) => Promise<unknown>` | `:840-843` | 路由到**任务归属 worker**（`hubSession.rpcTo`），不是 `ctx.sdk.rpc` |
| `subscribeTaskEvents` | `(handler) => () => void` | `:844-856` | 只回发 `'task.updated'` / `'task.stream'` 两个信号名（`agentId`/`payload` 恒 null），当刷新信号用 |

### 6.2 注册示例与消费链

```ts
ctx.ui.registerComposerAbovePanel({ id: 'my-plugin-toolbar', Component: MyToolbar })
```

消费链：`TaskChat` 用扩展点版本号订阅后同步 `listRegisteredComposerAbovePanels()`（`TaskChat.tsx:818-829`），渲染在输入框上方堆栈、内置 `AgentListPanel` 之后（`:944-947`）。

- **坑**：① **常量名单数** `'ui.composer_above_panel'`（`PluginDispatcher.ts:45`），其余扩展点全是复数——dispatch 字符串写复数会静默返回空数组（`PluginDispatcher.ts:197-220` switch 无此 case）；② 无 `order` 字段，多面板按注册顺序渲染；③ 公共 ctx **不带插件专有数据**（`TaskChat.tsx:830-831` 注释），面板要自持数据源（RPC 拉取 + 信号刷新，见 task-input-queue 范例）。
- **内置范例**：task-input-queue（`every-agent-plugins/task-input-queue/web/index.ts:15-21`，自持快照数据源的正面教材）。（subagent 的原同名注册已随插件去 web 退役，agent 胶囊列表由 web 核心 `TaskChat` + `AgentListPanel` 渲染。）

## 7. `ui.tool_call_views` —— 工具调用视图接管

### 7.1 Definition 字段表（`ToolCallViewDefinition`，`js/index.ts:336-345`；props 见 `PluginToolCallViewProps` `js/index.ts:324-331`、`PluginToolCallDetail` `js/index.ts:308-321`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `pluginId` | `string` | ✅ | 仅登记展示 | 提供方插件 id |
| `toolName` | `string` | ✅ | `toolViews/registry.ts:44` | 以**工具名**为 key 匹配（如 `update_file`） |
| `Component` | `ComponentType<PluginToolCallViewProps>` | ✅ | `toolViews/registry.ts:47-48` | **整体接管折叠态 + 展开态**，组件完全自治 |

`details[]` 每元素：`type:'tool_call'`、`toolName`、`toolCallId?`、`status?`、`arguments?`、`result?`——同工具多次调用会聚合后一起传入（`ToolCallView.tsx:85-104` 按 run 分组）。

### 7.2 注册示例与消费链

```ts
import type { ToolCallViewDefinition } from '@everyagent/plugin-api'
import { MyToolView } from './MyToolView'

const def: ToolCallViewDefinition = { pluginId: 'my-plugin', toolName: 'my_tool', Component: MyToolView }
ctx.ui.registerToolCallView(def)
```

消费链：消息线程渲染工具调用时 `getToolView(toolName)`（`ToolCallView.tsx:98`）按优先级解析——**插件注册的视图 > 内置目录注册表（import.meta.glob）> DefaultToolView**（`every-agent-web/src/components/task/toolViews/registry.ts:42-50`，注释 `:37-41`）。同名 `toolName` 多个插件注册时**先注册者胜**（for 循环首个命中，`:43-48`）。

- **坑**：接管是全量的——折叠摘要、展开详情、错误态都要自己画；`plugin-api` 的最小化 props 与核心 `ToolViewProps` 运行时同形，注册侧安全强转（`registry.ts:45-47` 注释）。
- **坑**：视图里点击路径打开**任务改动的文件**时，别拿 `sdk.workspace.rootPath` 当该文件的工作区根——它是插件加载时 `sys.info` 回填的 worker **默认工作区根**（见 [context-api §sdk.workspace](context-api.md)），任务文件绝大多数不在默认工作区下，用它打开必得 `[NOT_FOUND] 路径不存在`。正确姿势：`sdk.workspace.list()` 拿注册表全部工作区根 + `fs.listDir` 逐个探测定位文件实际所属根（update-file-view `UpdateFileToolView.tsx` 的 `resolveFileWorkspaceRoot` 同款；核心内置视图 `FileToolEntry` 同口径）。
- **内置范例**：update-file-view（`every-agent-plugins/update-file-view/web/index.ts:19-26`，接管 `update_file`：折叠态文件名+变更统计、展开态行级 diff）。

## 8. `ui.user_message_actions` —— 用户消息动作

### 8.1 Definition 字段表（`UiUserMessageActionDefinition`，`js/index.ts:415-424`；props 见 `UserMessageActionProps` `js/index.ts:398-411`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `id` | `string` | ✅ | `AgentMessageThread.tsx:139` | 渲染 key |
| `Component` | `ComponentType<UserMessageActionProps>` | ✅ | `AgentMessageThread.tsx:140-145` | 渲染在用户消息气泡上方 |

`UserMessageActionProps`：`taskId?`、`seq`（字符串雪花 ID，从 messageId `m-<seq>` 提取，`AgentMessageThread.tsx:131-132`）、`content`（纯文本）、`rawContent?`（含 opaque token 串）。

### 8.2 注册示例与消费链

```ts
ctx.ui.registerUserMessageAction({ id: 'my-plugin.copy-button', Component: MyButton })
```

消费链：每条用户消息渲染时同步 `listRegisteredUserMessageActions()` 并 map 渲染全部已注册动作（`AgentMessageThread.tsx:130-145`）；**只在能提取出 seq 时渲染**（messageId 不带 `m-` 前缀则空数组，`:132-133`）。

- **坑**：`seq` 是字符串，别 `Number()` 转（超 2^53 丢精度）；动作组件自己管交互态（编辑模式等），与 §9 的提交贡献配合才完整。
- **内置范例**：task-edit-resend（`every-agent-plugins/task-edit-resend/web/index.ts:17-20`，编辑重发按钮；按钮内部用 `ctx.ui.setComposerRawContent/appendComposerText` 回填草稿，`EditMessageButton.tsx:60,62`）。

## 9. `task.submit_contributions` —— task.run 提交贡献

### 9.1 Provider 字段表（`TaskRunSubmitContributionProvider`，`js/index.ts:446-457`；贡献对象 `TaskRunSubmitContribution` `js/index.ts:428-443`）

| 成员 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `id` | `string` | ✅ | 登记用 | 全局唯一 provider id |
| `getContribution` | `(taskId) => Contribution \| null` | ✅ | `TaskChat.tsx:258,1105-1109` | **提交时实时读取**；null 表示无贡献 |
| `subscribe` | `(listener) => () => void` | ✅ | `TaskChat.tsx:245-248` | 贡献变化通知核心重渲染（如进/出编辑模式改按钮文案） |
| `onSubmitted` | `(taskId) => void?` | — | `TaskChat.tsx:665` | 提交成功后回调（清理编辑目标等） |

贡献对象四字段：`metadata?`（合并进 task.run 的 metadata，浅 `Object.assign` 合并，`TaskChat.tsx:605-609`）、`submitLabel?` / `submittingLabel?`（覆盖提交按钮文案）、`submitDanger?`（danger 样式）——按钮展示覆盖取**首个**声明了 `submitLabel/submitDanger` 的贡献（`TaskChat.tsx:254-263`）。

### 9.2 注册示例与消费链

```ts
let editing = false
ctx.ui.registerTaskRunSubmitContributionProvider({
  id: 'my-plugin.contribution',
  getContribution: (taskId) => editing ? { metadata: { myFlag: taskId }, submitLabel: '重新发送' } : null,
  subscribe: (listener) => { listeners.add(listener); return () => listeners.delete(listener) },
  onSubmitted: () => { editing = false },
})
```

消费链：提交动作里 `collectTaskRunSubmitContributions` 收集全部非 null 贡献并合并 metadata 透传 task.run（`TaskChat.tsx:604-609`，核心不解释）；收集函数定义于 `:1098-1114`。

- **坑**：metadata 是浅合并，多插件同名键后者覆盖前者；`getContribution` 每次提交现调，别在里面做重活。
- **内置范例**：task-edit-resend（`every-agent-plugins/task-edit-resend/web/index.ts:21-23`，`metadata.editSeq` 由后端 `EditResendNode`(order=877) 消费截断）。

## 10. `ui.trace_types` —— trace 类型渲染

### 10.1 Definition 字段表（`TraceTypeDefinition`，`js/index.ts:471-486`；ctx `TraceContentRenderContext` `js/index.ts:460-468`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `kind` | `string` | ✅ | `traceTypeRegistry.tsx:95-96` | trace 类型 key；空串抛错（`:91-93`） |
| `renderContent` | `(trace, ctx) => ReactNode?` | — | `TaskThread.tsx:452` | 展开态内容；缺省回退通用 `<pre>` 渲染（`traceTypeRegistry.tsx:98-104`） |
| `getSummary` | `(trace) => string \| undefined?` | — | `TaskThread.tsx:448` | 收起态摘要适配器 |
| `getIcon` | `(trace) => string \| undefined?` | — | `TaskThread.tsx:445` | 返回**图标 key 字符串**，仅 `files`/`shield` 已登记映射，未知 key 原样渲染文本（`traceTypeRegistry.tsx:18-33`） |
| `hideTitle` | `boolean?` | — | `TaskThread.tsx:447` | 收起态不渲染 title |
| `canExpand` | `(trace) => boolean?` | — | `TaskThread.tsx:450` | 数据在 `metadata`（无 content）时用它放开展开 |

### 10.2 注册示例与消费链

```ts
ctx.ui.registerTraceType({
  kind: 'my-plugin.event',
  getIcon: () => 'files',
  hideTitle: true,
  canExpand: (trace) => Boolean(trace.metadata && Object.keys(trace.metadata).length > 0),
  getSummary: (trace) => (trace.metadata as { msg?: string })?.msg,
  renderContent: (trace) => React.createElement(MyTraceView, { trace }),
})
```

消费链：本扩展点**不在 `dispatch()` switch 内**（`PluginDispatcher.ts:197-220` 无此 case），注册时同步旁路写入 `traceTypeRegistry` 的 `Map<kind, def>`（`PluginDispatcher.ts:268-273` → `traceTypeRegistry.tsx:90-96`）；渲染时 `TaskTraceShell` 按 `trace.kind` 查表（`TaskThread.tsx:440-453`），查不到走通用降级（`getFallbackTraceType`，`traceTypeRegistry.tsx:73-80`）。

- **坑**：① `Map.set` 语义——**重复注册同 kind 覆盖**，且能覆盖内置 core kinds（`system_notice`/`run_error` 等，`traceTypeRegistry.tsx:109-136`），慎用同名；② `getIcon` 返回字符串不是组件。
- **内置范例**：ai-review（`every-agent-plugins/ai-review/web/index.ts:16-32,36`，`kind='auth.review'` + `shield` 图标）。

## 11. `ui.output_blocks` —— 输出块渲染

### 11.1 签名与字段

注册方法签名不同（不是 def 对象）：`registerOutputBlock(tag: string, handler: OutputBlockHandler)`（`js/index.ts:616`）。`OutputBlockHandler = (content: string, context: {taskId?, messageId?}) => ReactNode`（`js/index.ts:489-498`）。

### 11.2 注册示例与消费链

```ts
ctx.ui.registerOutputBlock('my-report', (content, { taskId }) =>
  React.createElement(MyReportView, { xml: content, taskId }))
```

消费链：同样**不在 `dispatch()` switch 内**，旁路写入 `outputBlockRegistry`（`PluginDispatcher.ts:274-284`）；消息渲染时 `RichMessageContent` 把内容按**成对** `<tag>…</tag>` 切段（`RichMessageContent.tsx:30-58`，只识别字母开头、成对闭合的标签，不成对当普通文本），命中已注册 tag 交给 handler，未命中降级为通用代码块（`:83-90`）。

- **坑**：① **重复注册同 tag 直接抛错**（`outputBlockRegistry.ts`），会炸掉整个 `activate()`——与 trace 的覆盖语义相反；② dispose 现已真清理（`outputBlockRegistry.unregister`，known-issues #3 修复前曾只清无人读的旁路 Map，注销后渲染照旧）；③ tag 大小写不敏感（两端都 toLowerCase）。
- **内置范例**：**无**（`every-agent-plugins` 全目录 rg `registerOutputBlock` 零命中；注册表注释自证「保持空，未注册标签走纯文本降级」，`outputBlockRegistry.ts:1-5`）。

## 12. `ui.file_content_editors` —— 文件内容编辑器

### 12.1 Descriptor 字段表（`PluginFileContentEditorDescriptor`，`js/index.ts:556-575`；props `PluginFileContentEditorProps` `js/index.ts:523-552`、标题栏动作 `PluginFileContentHeaderAction` `js/index.ts:505-520`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `kind` | `string` | ✅ | `editors/registry.ts:61` | 编辑器类型 ID |
| `label` | `string` | ✅ | 展示用 | 用户可见名称 |
| `extensions` | `string[]` | ✅ | `editors/registry.ts:66` | 扩展名列表，**全小写、含点**（如 `['.pdf']`） |
| `isFallback` | `boolean?` | — | `editors/registry.ts:44-50` | 未知扩展名兜底；插件不建议开启 |
| `readonly` | `boolean?` | — | 外壳屏蔽编辑入口 | 二进制只读类：`content` 变 data URL、屏蔽编辑/保存/查找 |
| `Component` | `ComponentType<PluginFileContentEditorProps>` | ✅ | `FileTabPage.tsx:621-635` | 编辑器主体 |

props 要点：`mode`（`'readonly'|'readwrite'`）、`content`（文本或 data URL）、`draftContent` + `onDraftChange`（草稿读写）、`onHeaderActionsChange`（往标题栏塞动作按钮，`placement:'default'|'save-adjacent'`）、`onRequestEditMode`（只读态请求转编辑）。

### 12.2 注册示例与消费链

```ts
import type { PluginFileContentEditorDescriptor } from '@everyagent/plugin-api'
const descriptor: PluginFileContentEditorDescriptor = {
  kind: 'my-preview', label: '我的预览', extensions: ['.xyz'], readonly: true,
  Component: ({ content }) => React.createElement('iframe', { src: content }),
}
ctx.ui.registerFileContentEditor(descriptor)
```

消费链：编辑器注册表合并**插件在前**——`[...pluginDispatcher.listRegisteredFileContentEditors(), ...内置]`（`editors/registry.ts:25-27`），按扩展名 `find` 首个命中（`:57`）⇒ **同名扩展插件覆盖内置**；文件标签页打开时 `resolveFileContentEditorByPath` 解析（`FileTabPage.tsx:88`）。

- **坑**：`extensions` 写大写或漏点号永远匹配不上（`normalizeExtension` 强制小写取点后缀，`editors/registry.ts:29-38`）。
- **内置范例**：pdf-viewer（`every-agent-plugins/pdf-viewer/web/index.ts:14-19`，`.pdf` 原生 iframe 预览，descriptor 定义于 `PdfFileEditor.tsx`）。

## 13. `ui.file_explorer_actions` —— 文件树右键菜单

### 13.1 Definition 字段表（`FileExplorerAction`，`js/index.ts:592-604`；ctx `FileExplorerActionContext`，`js/index.ts:577-590`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `id` | `string` | ✅ | `OpenFilesSidebarPanel.tsx` `getFileActionItems` | 全局唯一动作 id（菜单 key 冠以 `plugin:` 前缀防撞） |
| `label` | `string` | ✅ | 同上 | 菜单文案 |
| `icon` | `ReactNode?` | — | 同上 | 可选图标 |
| `isVisible` | `(ctx) => boolean?` | — | 同上 | 缺省恒显示；返回 false 该上下文不出现 |
| `invoke` | `(ctx) => void?` | — | 同上 | 点击回调 |

`FileExplorerActionContext`：`{workspaceRoot, path, name, type:'file'|'directory'}`。

### 13.2 消费链（已接线）

- 注册与读取 API：`registerFileExplorerAction` / `listRegisteredFileExplorerActions`（`PluginDispatcher.ts`），扩展点名 `ui.file_explorer_actions`。
- **消费点**：文件树右键/长按菜单构建处 `OpenFilesSidebarPanel.tsx` 的 `getFileActionItems`——先收集内置项（打开/新建/上传/属性/重命名/移动/搜索/下载/终端/系统文件管理器），再把插件注册项按 `isVisible(ctx)` 过滤后**追加到内置菜单项尾部**（与 `FileExplorerAction` 类型注释的约定一致）；面板订阅 `subscribeExtensionsChanged`，插件注册/注销后菜单即时刷新。
- **内置范例**：git 的「显示 Git 历史」（`every-agent-plugins/git/web/index.ts:42-53`，invoke 里 `ctx.ui.openPluginTab` 打开 git-history 标签）——右键文件树任意节点即可见。
- 历史欠账：该扩展点曾长期无宿主消费点（known-issues #1），已接线修复。

## 14. `ui.round_tail_panels` —— 轮末展示区

### 14.1 Definition 字段表（`UiRoundTailPanelDefinition`，`js/index.ts:388-394`；props `RoundTailPanelProps` `js/index.ts:374-385`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `pluginId` | `string` | ✅ | `TaskRoundsPanel.tsx:439` | 渲染 key（也用于归属） |
| `Component` | `ComponentType<RoundTailPanelProps>` | ✅ | `TaskRoundsPanel.tsx:438-445` | 渲染在该轮最后 |

props：`taskId`、`roundId`、`workerId?`、`workspaceRoot?`、`round: unknown`（轮次数据，核心不感知结构，插件自行强转取字段）。

### 14.2 注册示例与消费链

```ts
import type { UiRoundTailPanelDefinition } from '@everyagent/plugin-api'
const def: UiRoundTailPanelDefinition = { pluginId: 'my-plugin', Component: MyRoundTail }
ctx.ui.registerRoundTailPanel(def)
```

消费链：每轮渲染时同步 `listRegisteredRoundTailPanels()`（`TaskRoundsPanel.tsx:381`），**折叠/展开两态共用、恒在该轮末尾**渲染（`:433-446`）。

- **坑**：渲染 key 是 `pluginId`（`:437`）——同一插件注册两个轮末面板会 React key 冲突，一个插件只应注册一个。
- **内置范例**：file-change（`every-agent-plugins/file-change/web/index.ts:15-26`，轮末文件变更视图 + 订阅 `task-round-closed`/`task-deleted` 作废缓存）。

## 15. `ui.search_types` —— 搜索类型注册

### 15.1 Definition 字段表（`SearchTypeDefinition`，`every-agent-plugin-api/js/index.ts:716-733`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `id` | `string` | ✅ | 搜索类型注册表 key | 类型 id（内置 `file-content` / `file-name` / `task`；插件自定，如 `image`） |
| `label` | `string` | ✅ | 类型选择器（侧边栏下拉 / 双击 Shift 弹窗按钮行） | 类型文案 |
| `pluginId` | `string` | ✅ | 归属展示 | 插件填插件 id；内置填 `core` |
| `order` | `number?` | — | 类型列表合并排序 | 缺省 100（内置占用小值），与 sidebar order 同款升序混排口径 |
| `kinds` | `string[]` | ✅ | 组装统一 `search` 的入参 | 该类型要向统一 `search` 要搜的 kinds（「全部」伪类型不传 `kinds`） |
| `filters` | `SearchFilterField[]?` | — | 过滤区默认渲染器 | 声明式过滤字段 schema（见下） |
| `FilterView` | `ComponentType<SearchFilterViewProps>?` | — | 过滤区 | 整块自定义过滤区渲染；不提供则用默认渲染器 |
| `ResultView` | `ComponentType<SearchTypeResultViewProps>?` | — | 结果区 | 自定义结果渲染；不提供则用默认结果树 `SearchResultTreeView` |

`SearchFilterField = { key, label, type:'text'|'textarea'|'boolean'|'select'|'radio'|'number'|'path', options?: Array<{label, value}>, default?, placeholder?, help?, section?:'inline'|'more' }`——核心按 `type` 用默认渲染器出控件（文本框/下拉/单选/开关/数字/路径选择），收集到的值进 `filters[`${kind}.${key}`]`；`section:'more'` 的字段收在「更多」菜单。自定义渲染组件的 props：`SearchFilterViewProps{fields, values, setValue, workspaceRoot}`、`SearchTypeResultViewProps{items, pattern, workspaceRoot, openFile, openTask}`；命中项类型为 `PluginSearchItem`（`kind` + 随 kind 而变的平铺字段袋，核心不解释）。

### 15.2 注册示例

```ts
import type { SearchTypeDefinition } from '@everyagent/plugin-api'
import { ImageResultView } from './ImageResultView'

const imageType: SearchTypeDefinition = {
  id: 'image',
  label: '图片',
  pluginId: 'my-plugin',
  order: 20,
  kinds: ['image'],                 // 后端 provider 需声明同 kind
  filters: [
    { key: 'ext', label: '格式', type: 'select', options: [{ label: 'PNG', value: 'png' }, { label: 'JPG', value: 'jpg' }, { label: 'WebP', value: 'webp' }], section: 'inline' },
    { key: 'minWidth', label: '最小宽度', type: 'number', section: 'more' },
  ],
  ResultView: ImageResultView,       // 可选：自定义结果渲染（不写则用默认结果树）
}
// activate 里：
ctx.ui.registerSearchType(imageType)
```

### 15.3 消费链与坑

- 注册经 `pluginDispatcher.registerSearchType` / `listRegisteredSearchTypes`（扩展点常量 `ui.search_types`，契约见 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §8.5）；宿主搜索面板（侧边栏下拉 + 双击 Shift 弹窗按钮行）用 `useSyncExternalStore(pluginDispatcher.subscribeExtensionsChanged, getExtensionsVersion)` 感知，注册即上屏（同 §0 第 2 条）。
- 内置 `file-content` / `file-name` / `task` 三类型与插件类型按 `order` 升序**混排**；「全部」为**不带 `kinds`** 的伪类型（含任务，结果平铺聚合、靠组头 `providerId` 来源标记区分）。
- **过滤字段两侧 key 要人工对齐**：`filters` 的 `field.key` 是前端声明，worker 侧 provider 按运行时键 `${kind}.${field.key}` 从 `filters` 袋读值（见 `FilterBag.raw`）——核心不解释字段名、无法替你校验（两侧不一致 = provider 永远读不到该值）；「全部」模式按类型分组展示、`key` 以 `${kind}.${key}` 命名空间隔离避免互相污染。
- **`kinds` 必须有后端 provider 落地**：类型声明的 `kinds` 需有声明同 kind 的后端 `SearchProvider` 才有结果——只注册前端类型、后端无 provider 就永远空结果（本轮**不做**纯前端索引，`execute` 钩子列为后续预留）。
- `FilterView` / `ResultView` 是**整块替换**（不是单字段替换）；组件只能依赖 plugin-api 类型 + `ctx` 能力，**不引宿主 `@/` 模块**（§0 第 4 条）。`ResultView` 里若要打开结果对应的文件，别拿 `sdk.workspace.rootPath` 当工作区根（见 §7 同坑）。
- 搜索面板**没有** worker 选择器——worker 由「当前工作区根」经 `sdk.workspace.workerIdOfRoot(root)` 反查；无归属时报可读错误。

## 16. UiRegistry 动作方法（5 个）

动作方法不注册 UI，而是**驱动宿主**。实现链统一：`pluginDispatcher` 同步委托模块级 holder 桥（`pluginRuntimeBridge.ts`），宿主组件挂载时注入、未注入时静默降级（`:11-15` 注释）。

| 方法 | 签名（`js/index.ts:621-631`） | 宿主实现链 | 行为 |
|---|---|---|---|
| `openPluginTab` | `(type, data: Record<string,string>, title?) => void` | `PluginDispatcher.ts:312-314` → `Layout.tsx:779-790` 注入 → `Layout.tsx:524-538` 实现 | 打开 `tabType=type` 的插件标签：查 `getWorkspaceTabTypeDefinition(type)` 定 pluginId，`data.id ?? 首个值 ?? type` 生成标签 id，`openWorkspaceTab` 入栈。`data` 原样存进 `tab.data`，由 §4 的 `renderTab` 读回（范例 `GitHistoryTabType.tsx:19-22,27-33` 的 `getData`） |
| `openFileTab` | `(workspaceRoot, filePath, {mode?}) => void` | `PluginDispatcher.ts:315-317` → `Layout.tsx:782-787` | 打开顶层文件标签；`mode:'readwrite'|'readonly'` 透传 |
| `openDiffTab` | `(input: PluginDiffTabInput) => void` | `PluginDispatcher.ts:318-320` → `Layout.tsx:788` | 打开 diff 对比标签；`input` 字段见 `js/index.ts:178-199`（`filePath/fileName/changeType/beforeContent/afterContent/workspaceRoot?/title?/binary?/allowRestore?`） |
| `appendComposerText` | `(text) => void` | `PluginDispatcher.ts:321-323` → `TaskChat.tsx:698-705` 实现、`:719-721` 注入 | 向**当前激活**任务标签的草稿末尾追加纯文本 |
| `setComposerRawContent` | `(raw) => void` | `PluginDispatcher.ts:324-326` → `TaskChat.tsx:706-710` | 用 rawContent（可含 opaque token 串）**整体替换**草稿（编辑重发回填用） |

```ts
// 典型组合：点击工具视图里的文件名打开文件标签（update-file-view 同款，UpdateFileToolView.tsx:85）
ctx.ui.openFileTab(workspaceRoot, filePath, { mode: 'readonly' })
// 打开本插件注册的 git-history 式标签（git/web/index.ts:44-49 同款）
ctx.ui.openPluginTab('my-view', { workspaceRoot, path }, `我的视图：${name}`)
// 打开 diff（GitSidebarPanel.tsx:400 同款）
ctx.ui.openDiffTab({ filePath, fileName, changeType: 'updated', beforeContent, afterContent, workspaceRoot })
// 编辑重发回填（EditMessageButton.tsx:60,62 同款）
ctx.ui.setComposerRawContent(rawContent)
ctx.ui.appendComposerText('\n\n（追加一段）')
```

注意两个时序坑：① 页面刚加载、`Layout`/`TaskChat` 尚未挂载时桥为 null，动作**静默丢失**（不抛错）；② 草稿桥只在**激活标签页**持有写权（`TaskChat.tsx:710-718` 注释解释竞态），多任务标签并存时写入的是当前激活的那个。

## 17. Disposable 与刷新

- **dispose 语义是真的**：`ListExtensionRegistry.register` 返回的 Disposable 从数组 splice 并通知宿主重渲染（`ExtensionRegistry.ts:45-56`）——`ctx.events.on`、`ctx.commands.registerCommand` 同理（见[前端 ctx API](context-api.md) §9）。`registerTraceType` / `registerOutputBlock` 的 dispose 也已补齐侧路注销（`traceTypeRegistry.unregisterTraceType` / `outputBlockRegistry.unregister`，known-issues #3 修复前曾是不清真实消费方的假 Disposable）。
- **宿主收集并在页面卸载时统一 dispose**：激活时 `ctx.ui` 等注册表都包了收集代理（`pluginLoader.ts` 的 `trackDisposables`），注册返回的 Disposable 全部进该插件的 `disposables`；页面卸载（`pagehide`）时宿主先调 `deactivate` 再逆序 dispose 全部注册项并移除插件 CSS（known-issues #8 修复前 disposables 恒为空数组、`deactivate` 零调用）。运行期没有热卸载——中途注销仍需自己持有 Disposable 调 dispose。
- **刷新即丢**：注册表、订阅、blob 模块全是内存态，页面刷新全部清零并重新走加载链路（[加载链路](overview-and-loading.md)）；跨刷新要保留的状态用 `ctx.storage`（localStorage）。
- **同一会话内的「更新」= 重新加载页面**：改了插件 web 代码要重跑 `npm run build:plugins` 再刷新（该脚本不在任何流水线内）；后端启停/装卸一律重启 worker（[plugin.json 字段参考](../plugin-manifest.md) §6；扩展面板「重新加载」按钮**仅在有待生效变更时显示**，待生效变更涉及含 `main` 的插件时会弹确认并自动完成重启 + 刷新）。

## 18. 下一步读

- `ctx.ui` 之外的能力（rpc/storage/events/fs/commands）：[前端 ctx API](context-api.md)
- 加载链路与 bare import 白名单：[加载链路](overview-and-loading.md)
- 面板刷新信号用的宿主事件全集：[事件](events.md)
- `task.submit_contributions` 的 metadata 谁在后端消费：[后端任务与 RPC](../backend/task-and-rpc.md)
- 全部内置插件一张表：[内置插件索引](../reference/builtin-plugins.md)
