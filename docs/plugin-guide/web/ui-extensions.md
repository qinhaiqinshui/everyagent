---
title: 前端 UI 扩展点
nav_order: 11
parent: 前端插件开发
has_children: false
---

# 前端 UI 扩展点

**一句话定位**：`ctx.ui`（`UiRegistry`）是前端插件往宿主 UI 里「贡献界面」的唯一通道——15 个 `register*` 扩展点 + 6 个动作方法。本文逐扩展点给出 Definition 字段表（抄自纯类型包 `every-agent-plugin-api/js/index.ts`）、注册代码、宿主消费链与坑；每条断言附宿主源码行号。`ctx.ui` 的宿主实现是单例 `pluginDispatcher`（接入方式见[前端 ctx API](context-api.md) §8）。

## 0. 读前须知：所有扩展点共用的四条机制

1. **注册即入表，Disposable 真清理**：每个 `register*` 落到对应扩展点的 `ListExtensionRegistry`（普通数组 push，`every-agent-web/src/plugin/ExtensionRegistry.ts:45-56`），dispose 从数组 splice 并通知订阅者。注意 dispatcher 调 `register('', def)` 时**忽略 pluginId 形参**、一律传空串（`every-agent-web/src/plugin/PluginDispatcher.ts:241-242` 等 15 处）。
2. **注册晚于首屏，宿主靠版本号重渲染**：插件 `activate()` 是异步的。宿主用 `React.useSyncExternalStore(pluginDispatcher.subscribeExtensionsChanged, pluginDispatcher.getExtensionsVersion)` 感知注册（`every-agent-web/src/components/app/Layout.tsx:139-141`、`every-agent-web/src/components/task/TaskChat.tsx:818-821`），注册后贡献立即上屏，**无需刷新页面**。
3. **两条读取通道**：异步 `dispatch('ui.xxx')` / `get*()`（仅文件页侧栏在用，`FileTabPage.tsx:166`）与同步 `listRegistered*()`（其余消费点的实际用法，下文逐个给出）。
4. **示例代码约束**（详见[加载链路](overview-and-loading.md)）：入口 `web/index.ts` 只能 `import type ... from '@everyagent/plugin-api'` + 白名单 bare import（react / react-dom / react/jsx-runtime / antd / @ant-design/icons）；`.ts` 文件里用 `React.createElement`，组件放 `.tsx` 可用 JSX。

## 1. 十五个扩展点总览

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
| `ui.editor_context_menu_actions` | `registerEditorContextMenuAction` | 文件内容区（编辑器）右键菜单项 | selection-reference；核心内置「复制」项（`core.copy-selection`，见 §16.1） |
| `ui.composer_chip_actions` | `registerComposerChipAction` | 按 kind 接管输入框/消息回放胶囊的点击行为 | selection-reference（核心 `system.workspace_file` 亦经此扩展点注册，见 §17） |

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

注意 5 与 9 是**插件**取值不是内置位——插手别处请避开 1/2/3/10 四个内置占用；4（3 与 5 之间）、6~8、11~99 都是空位。其余 13 个扩展点**没有 order 字段**，一律「插件在前、按注册顺序」合并（§4/§7/§12 各自证据）。

## 4. `ui.workspace_tab_types` —— 工作区标签类型

### 4.1 Definition 字段表（`UiWorkspaceTabTypeDefinition`，`js/index.ts:258-285`；`WorkspaceTabRenderContext` `js/index.ts:241-255`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `tabTypeKey` | `string` | ✅ | `workspaceTabTypes.ts:57-59` | 查表 key；也是 `ctx.ui.openTab(type,…)` 的 `type`（`Layout.tsx:573`） |
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

- **坑**：① `renderTab` 里先做 `tabType` 判别、不匹配返回 `null`（`GitHistoryTabType.tsx:28-29` 同款兜底）；② `openTab` 的 `type` 必须与 `tabTypeKey` 一致，查不到定义时 `pluginId` 会回退成 type 本身（`workspaceTabTypes.ts:68-77` 的 `createPluginTabFallback`）；③ `PluginWorkspaceTab` 是最小化替身类型，`pluginTabType` 等字段需强转访问（`GitHistoryTabType.tsx:28`）。
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
- **内置范例**：git 的「显示 Git 历史」（`every-agent-plugins/git/web/index.ts:42-53`，invoke 里 `ctx.ui.openTab` 打开 git-history 标签）——右键文件树任意节点即可见。
- 历史欠账：该扩展点曾长期无宿主消费点（known-issues #1），已接线修复。

## 14. `ui.round_tail_panels` —— 轮末展示区

### 14.1 Definition 字段表（`UiRoundTailPanelDefinition`，`js/index.ts:388-394`；props `RoundTailPanelProps` `js/index.ts:374-385`）

| 字段 | 类型 | 必填 | 消费位置（宿主） | 说明 |
|---|---|---|---|---|
| `pluginId` | `string` | ✅ | `TaskRoundsPanel.tsx:445` | 渲染 key（也用于归属判定） |
| `Component` | `ComponentType<RoundTailPanelProps>` | ✅ | `TaskRoundsPanel.tsx:444-451` | 渲染在该轮最末的**轮末工具栏行内**，其根元素直接是该行（wrap flex 行）的 flex 项，与同行其它贡献者并排 |

props：`taskId`、`roundId`、`workerId?`、`workspaceRoot?`、`round: unknown`（轮次数据，核心不感知结构，插件自行强转取字段）。

### 14.2 注册示例与消费链

```ts
import type { UiRoundTailPanelDefinition } from '@everyagent/plugin-api'
const def: UiRoundTailPanelDefinition = { pluginId: 'my-plugin', Component: MyRoundTail }
ctx.ui.registerRoundTailPanel(def)
```

消费链：每轮渲染时同步 `listRegisteredRoundTailPanels()`（`TaskRoundsPanel.tsx:381`），**折叠/展开两态共用、恒在该轮末尾**渲染（`:433-454`）——渲染在轮末工具栏 `<div.nagent-round-collapse__tail>`（一条 `flex-wrap: wrap` 行）内，**贡献者组件的根元素直接落进这一行**，宿主不包任何中间层；宿主只兜底 `.nagent-round-collapse__tail > * { min-width: 0 }`，防 nowrap 长内容（如完整文件路径）按 max-content 撑破行宽。

- **核心自身也是这个扩展点的贡献者**：内置「复制本轮结果」按钮不在 `TaskRoundsPanel` 里硬编码，而是由 `initializeCoreRoundTailPanels()`（`every-agent-web/src/plugin/coreRoundTailPanels.ts`，在 `main.tsx` 里随 `initializeTraceTypes()` 之后、`loadPlugins()` 之前同步调用）以 `pluginId: 'core.round-copy'` 注册进**同一**扩展点（组件 `components/task/RoundCopyPanel.tsx`）。与 `traceTypeRegistry` 同一范式：核心内置贡献 = 显式初始化 + 注册进插件同款扩展点，因此核心按钮与插件面板走完全相同的渲染路径与栅格。
  - **顺序**：注册表是普通列表、按注册顺序渲染。核心注册在首屏同步完成，插件注册发生在异步的 `activate()`，故核心面板**恒在最左**（先「复制」后「文件变更」）；插件之间按 activate 先后。
  - **占宽策略在面板自己**：宿主不替面板定 flex，各面板在自己的根元素上声明。核心「复制」按钮的 `.nagent-round-collapse__copy` 写 `flex: 0 0 auto`（hug，不参与拉伸/压缩）——免得被同排插件面板由长路径撑出的 max-content 挤变形，也不会反过来占掉面板宽度。
- 面板的**排版职责分工**：host 负责这一行的行间距（`.nagent-round-collapse__tail` 提供 `margin-top`/`gap`）与**统一高度约束**，插件负责自己占多宽、要不要另起一行。因此插件面板**不要自带顶层上间距**（`padding-top`/`margin-top`），否则会比同一行的核心按钮低一档、视觉上不齐（file-change 的汇总头即按此约定不带 `padding-top`）。
  - **统一高度约束**：这一行并排的 pill 高度由核心 Token 单点决定 —— `--round-tail-pill-height`（`assets/styles/ui-tokens.css`，`:root`，值 = 文本行盒 `--text-xs × 1.5` + 上下内边距 `2 × --space-1` = 26px）。核心「复制」按钮与 file-change 汇总头都用 `height: var(--round-tail-pill-height); box-sizing: border-box; padding: 0 9px`（纵向不吃 padding，内容靠 `align-items: center` 居中），故两枚 pill **等高由构造保证**，不再靠内边距/行高/图标尺寸「碰巧」凑成一样高（历史上正是此处差一点点而顶不齐）。**插件面板的汇总头 pill 请照此写法锚定该 token**（`var()` 经 `:root` 继承到此，无需 import）。
- **坑（实测踩过）**：`web/*.css` 只是源码，宿主加载的是 esbuild 预编译产物 `web/index.css`（`plugin.webSource` RPC 从 worker 磁盘读，产物被 `.gitignore` 排除）。**只改 `roundFileChanges.css` 这类源文件而不重跑 `npm run build:plugins`，页面用的仍是旧 CSS**——表现为「改了样式毫无效果」，或「与核心按钮差 4px 对齐不上」。改完插件样式务必 `npm run build:plugins`（单插件可 `-- --only <id>`）再刷新；改后端 / `plugin.json` 才需重启 worker。
- **展开详情要通栏占满整行（标签页式）**：面板把「汇总头」与「展开详情」渲染成**两个并列的 flex 项**（组件直接返回 Fragment、不包根容器），详情写 `flex: 1 1 100%` —— 在 wrap 行里必然另起一行并**通栏占满整行宽度**（左边缘与「复制」按钮对齐），效果即「点标签行里的汇总头 = 切到下方那块通栏详情区」；汇总头则写 `flex: 0 0 auto` 留在标签行、原地不动。file-change 即此形态（`RoundFileChangesView.tsx` 返回 Fragment + `.task-file-changes__list { flex: 1 1 100% }`）。
- 无插件面板时（如某轮无文件变更），该行只剩核心复制按钮，左对齐。

- **坑**：渲染 key 是 `pluginId`（`:445`）——同一 `pluginId` 注册两个轮末面板会 React key 冲突，一个 id 只应注册一个。
- **内置范例**：核心 `core.round-copy`（复制本轮结果）；插件 file-change（`every-agent-plugins/file-change/web/index.ts:15-26`，轮末文件变更视图 + 订阅 `task-round-closed`/`task-deleted` 作废缓存）。

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

## 16. `ui.editor_context_menu_actions` —— 编辑器内容区右键菜单

### 16.1 Definition 字段表（`UiEditorContextMenuActionDefinition`，`every-agent-plugin-api/js/index.ts:676-686`；上下文 `EditorActionContext` `:659-670`、选区快照 `EditorActionSelection` `:650-656`）

| 字段 | 类型 | 必填 | 消费规则（宿主） | 说明 |
|---|---|---|---|---|
| `id` | `string` | ✅ | 菜单项 key（宿主统一加 `plugin:` 前缀防撞） | 全局唯一动作 id |
| `label` | `string` | ✅ | 菜单项文案 | 用户可见名称 |
| `icon` | `ReactNode?` | — | 菜单项图标 | 可选图标节点（须插件自带） |
| `group` | `string?` | — | 同名项由宿主收进以组名为标题的**子菜单**；缺省平铺在顶层 | 分组标题 |
| `order` | `number?` | — | float 升序；缺省 100，同值按注册先后 | 组内 / 顶层排序 |
| `isVisible` | `(ctx: EditorActionContext) => boolean` | — | 右键过滤时调用；返回 false 则该上下文不出现 | 缺省恒显示 |
| `invoke` | `(ctx: EditorActionContext) => void` | ✅ | 点击菜单项时调用；抛错由宿主 try/catch 吞并 `console.warn` | 动作实现 |

**核心内置「复制」项**：`core.copy-selection`（`every-agent-web/src/plugin/coreEditorContextMenuActions.tsx`，main.tsx 在 `loadPlugins()` 之前同步注册）——以**贡献者**身份注册进本扩展点（与核心 `system.workspace_file` 胶囊同范式，宿主组件零特判），`order=10` 恒排在插件项（缺省 100）之前。动机：① 桌面右键被接管（preventDefault + 统一菜单）后原生「复制」随之丢失；② 触屏原生 callout 在滚动/交互后消失，统一菜单成为唯一入口——没有复制项移动端将无法复制选中内容。剪贴板写入优先 `navigator.clipboard.writeText`（仅安全上下文可用），非安全上下文（局域网 http 访问，手机访问开发机的典型形态）降级「临时 textarea + `execCommand('copy')`」（菜单项 onSelect 即点击手势内，iOS Safari 有效）；结果经 antd message 反馈（成功「已复制选中内容」/失败提示）。**注册后实际效果**：菜单在有选区时恒有可见项（此前依赖插件 isVisible），「过滤后无可见项 → 放行原生菜单」分支在实践中仅剩「无选区」一种触发。

右键上下文 `EditorActionContext`（右键时点在编辑器容器上采集）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `workspaceRoot` | `string` | 所属工作区根（身份键） |
| `filePath` | `string` | 标签身份里的**原样路径**（插件回切标签时应原样回传，保证命中同一标签） |
| `relativePath` | `string` | 归一化工作区相对路径（交给 AI 的引用口径） |
| `fullPath` | `string` | 业务绝对路径（带前导 `/`） |
| `fileName` | `string` | 文件名 |
| `mode` | `'readonly' \| 'readwrite'` | 当前标签读写态 |
| `editorKind` | `string` | 当前内容编辑器 kind（如 `'universal'`） |
| `content` | `string` | 当前内容快照（编辑态 = 草稿） |
| `selection` | `EditorActionSelection` | 右键时点采集的选区快照（见下） |
| `anchor` | `HTMLElement` | 触发容器（供插件定位自己的浮层） |

选区快照 `EditorActionSelection`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `text` | `string` | 选中原文 |
| `startLine` | `number \| null` | 起始行（1-based）；无法确定（非 CodeMirror 等）为 null |
| `endLine` | `number \| null` | 结束行（1-based）；无法确定为 null |
| `charCount` | `number` | 选中字符数 |
| `lineCount` | `number` | 选中行数 |

### 16.2 注册示例（`selection-reference` 插件）

```ts
// web/index.ts —— 入口只能 import type + 白名单 bare import；.ts 里用 React.createElement
import type { PluginModule, UiEditorContextMenuActionDefinition } from '@everyagent/plugin-api'
import { SELECTION_KIND, buildSelectionLabel, type SelectionPayload } from './token'

const referenceAction: UiEditorContextMenuActionDefinition = {
  id: 'selection-reference.reference-to-ai',
  label: '引用到AI对话框',
  group: 'AI',
  order: 100,
  isVisible: (c) => c.selection.text.length > 0,   // 选中内容为空 → 该项不出现
  invoke: (c) => {
    const payload: SelectionPayload = {
      workspaceRoot: c.workspaceRoot,
      filePath: c.filePath,          // 原样路径：回切标签时命中同一标签
      relativePath: c.relativePath,  // 交给 AI 的引用口径
      fileName: c.fileName,
      startLine: c.selection.startLine,
      endLine: c.selection.endLine,
      text: c.selection.text,        // 原文不截断
    }
    // 结构化插入胶囊：opaque token 编码由宿主单点负责，插件不碰线格式。
    // payload 必须展开成新对象字面量：appendComposerToken 形参是 Record<string, unknown>，
    // 插件侧 interface 无索引签名，直接传 interface 会被 TS 判为不可赋值（TS2322）。
    ctx.ui.appendComposerToken({ kind: SELECTION_KIND, label: buildSelectionLabel(c.selection.text), payload: { ...payload } })
  },
}

const plugin: PluginModule = {
  activate(ctx) {
    ctx.ui.registerEditorContextMenuAction(referenceAction)
  },
}
export default plugin
```

### 16.3 宿主消费链与坑

- **消费点**：文件标签页编辑器内容容器 `editorContainerRef` 挂 `onContextMenu={handleEditorContextMenu}`（`every-agent-web/src/components/files/FileTabPage.tsx:741`）→ 菜单采集器 `collectEditorActionMenu`（`FileTabPage.tsx:414`，**事件时点**同步 `pluginDispatcher.listRegisteredEditorContextMenuActions()`（`:417`，**不缓存快照**——插件 `activate()` 晚于首屏；采集时把 `ctx`（含选区快照）闭包进菜单项，此后 DOM 选区被清空也不影响 `invoke`）→ 菜单项构建器 `buildEditorContextMenuItems`（`FileTabPage.tsx:920`）→ 渲染宿主既有 `MenuList`（`FileTabPage.tsx:778-785`）。**两个触发源共用同一采集器**：桌面右键 `handleEditorContextMenu`（`:454`，`anchorPointMode='top-start'`，左上角对齐鼠标点）与触屏选区触发（见下条）。
- **触屏触发（iOS/Android Safari 必读）**：移动端浏览器长按选中文本由系统原生 callout（拷贝/查询…）接管，**iOS Safari 不派发 `contextmenu` 事件**，右键处理器无从触发。宿主在「无 hover + 粗指针」设备（`(hover: none) and (pointer: coarse)`，覆盖手机与平板触屏、排除触屏笔记本；注意**不是** `isMobile` 宽度判据——iPad 宽屏触屏同样需要兜底）上监听 `document.selectionchange`（`FileTabPage.tsx:466-509`）：选区在编辑器内**稳定 350ms** 后按同一采集管线弹菜单，锚在选区下缘中点（`anchorPointMode='center'`，菜单落在选区下方、避开 iOS 原生 callout）。两个关键设计：① 菜单项构建时已闭包捕获选区快照——iOS 点击菜单项会先清空选区再派发 click，快照不受影响；② 选区消失时**不**主动关菜单（会吞掉点击），关闭交由 `MenuList` 既有的「外部 pointerdown / Escape」兜底。插件侧零改动，注册的编辑器动作在移动端自动获得入口。
- **接管条件（通用、零业务）**：**仅当**「`selection.text.length === 0` 不成立（`FileTabPage.tsx:431`）**且** `isVisible` 过滤后至少有一项（`:433-443`）」时才有菜单（右键路径额外 `preventDefault`，`:460`）；否则三情况（无选区 / 非文本编辑器 / 过滤后无项）一律无动作，**完全放行浏览器原生右键菜单**（零回归）。
- **`EditorActionContext` 各字段真实来源**（宿主逐字段构造于 `collectEditorActionMenu`，`FileTabPage.tsx:419-431`）：`filePath` 取 `file.filePath` **原样回传**（`:422`，它是标签身份的一部分，归一化会导致回切标签取不到同一标签）；`relativePath` 走 `normalizeWorkspaceRelativePath(...)` 归一化（`:423`，交给 AI 的引用口径）；`fullPath` 走 `toBusinessAbsolutePath(...)` 业务绝对路径（`:424`）；`editorKind` 取 `selectedEditorDescriptor.kind`（`:427`）；`content` 编辑态取草稿 `openMode === 'readwrite' ? draftContent : content`（`:428`）；`anchor` 即 `editorContainerRef.current` 触发容器（`:415,430`）。
- **菜单渲染**：复用宿主既有 `MenuList`（portal + 视口夹紧 + Esc/外部点击关闭 + 子菜单支持），插件侧零菜单代码；`anchor` = 编辑器容器（`FileTabPage.tsx:780`）、`anchorPoint` = 触发坐标（桌面右键 `{x:event.clientX, y:event.clientY}`（`:461`）、触屏选区下缘中点（`:494`））、`anchorPointMode` 桌面 `'top-start'` / 触屏 `'center'`（`:462,495`，渲染处取自浮层状态 `:782`）。
- **排序 / 分组规则**（`buildEditorContextMenuItems`，`FileTabPage.tsx:920-956`）：按 `order ?? 100` float 升序 `[...actions].sort((a,b)=>(a.order ?? 100)-(b.order ?? 100))`（`:924`，ES2019 起稳定 sort ⇒ 同值保持注册先后）；顶层项 key = `` `plugin:${action.id}` ``（`:927`）；同 `group` 收进以组名为标题的子菜单（key = `` `plugin:group:${group}` ``，`:946`），**同一 group 只出现一次**（`groupItemByKey` Map 去重，`:943-948`），且**在该组首个成员的位置插入**（遍历有序列表、首次遇到该组时 `items.push(groupItem)`，`:944-950`），组内成员按 order 顺序（遍历本身有序）。
- **错误处理**：`isVisible` 抛错 → `console.warn('[plugin] editor context menu isVisible failed', ...)` 且视为**不可见**（`FileTabPage.tsx:437-441`）；`invoke` 抛错 → `console.warn('[plugin] editor context menu action failed', ...)` **吞掉、不炸 UI**（`:931-935`）。
- **选区 / 行号实现**（`every-agent-web/src/components/files/editorSelection.ts`）：`readEditorSelection(root)`（`:33`）的**保护闸**——无选区 / `selection.isCollapsed` / `rangeCount === 0`（`:37`）、或选区端点不在容器内（`root.contains(range.startContainer/endContainer)` 任一不成立，`:45`）→ 返回空快照 `EMPTY_EDITOR_SELECTION`（`:17`）。行号解析**两路**（`readLineRange`，`:60`）：① 精确路径 `EditorView.findFromDOM` + `view.posAtDOM` + `doc.lineAt`（`:65-69`，不受行 DOM 是否在视口内影响）；② 兜底 `.cm-content .cm-line` 与 `.cm-lineNumbers .cm-gutterElement` 下标对齐（`:81-83`，**仅在两者等长时启用**，`range.intersectsNode` 求首末行）。两条都失败 → 行号 null（`:106`）。`lineCount` 两分支口径（`:52-54`）：行号齐 → `endLine - startLine + 1`，否则按换行计 `text.split(/\r\n|\r|\n/).length`。
- **非 CodeMirror 编辑器不接管**：pdf iframe / 图片等编辑器里，容器内根本拿不到选区（`window.getSelection()` 端点不在容器内或 `isCollapsed`）⇒ `readEditorSelection` 返回空快照 ⇒ `selection.text.length === 0` ⇒ 自然放行原生右键菜单（零回归）。
- **坑**：① `isVisible` 是唯一的内容级过滤闸——想只在特定文件类型出现（如仅 `.ts`）就在它里判断，别拖到 `invoke` 才拦；② `invoke` 里抛错不会炸 UI（宿主吞并 warn），但也意味着**错误静默**，需要可见反馈时自己弹提示；③ `anchor` 是右键触发容器，插件浮层定位应相对它、别假设它一定是编辑器根；④ 路径三口径别混用：回切标签用 `filePath`（原样），交给 AI 用 `relativePath`，业务绝对路径用 `fullPath`；⑤ 行号依赖 CodeMirror DOM 类名（`.cm-line`/`.cm-lineNumbers`），换编辑器则行号降级为 null、引用仍带路径与原文（功能降级不失效）。
- **坑（`MenuList` 定位必读）**：`MenuList` 的定位 effect 首行是 `if (!anchor) return`（`every-agent-web/src/components/shared/ui/Menu.tsx:255-256`）——`anchor` **必须**传真实触发容器（宿主传编辑器容器）；传 `null` 会让 `menuPosition` 停在 `null`，菜单被渲染到 `top/left: -9999`（屏外，`Menu.tsx:462-463`）。`anchorPoint` 只提供右键坐标，**不能替代** `anchor`。
- **内置范例**：`selection-reference`（编辑器选区右键「引用到AI对话框」，`invoke` 先 `openTab('task', { taskId:'__draft__', workspace:c.workspaceRoot })` 打开草稿标签——`workspace` 预设 = 选区所属工作区根，新任务挂靠该工作区（与任务页组「+」同范式，缺省该参数宿主会清空预设、草稿回退注册表首选根），再经 `appendComposerToken` 插入 `kind='file.selection'` 胶囊）。注意：菜单里排在它之前的核心「复制」项（§16.1）不是插件范例，但它与插件项走完全相同的注册/排序/过滤路径。

## 17. `ui.composer_chip_actions` —— 胶囊激活行为

### 17.1 Definition 字段表（`ComposerChipActionDefinition`，`every-agent-plugin-api/js/index.ts:407-411`；激活上下文 `ComposerChipActivationContext` `:395-401`、胶囊视图 `ComposerChipTokenView` `:386-392`）

| 字段 | 类型 | 必填 | 消费规则（宿主） | 说明 |
|---|---|---|---|---|
| `kind` | `string` | ✅ | 按 `kind` 查表，**先注册者胜** | 胶囊种类 key；核心在首屏同步注册，故核心 kind 恒优先、插件不能顶替 |
| `pluginId` | `string` | ✅ | 归属展示 | 提供方插件 id；核心自身填 `'core'` |
| `onActivate` | `(ctx: ComposerChipActivationContext) => void` | ✅ | 点击胶囊时调用；未注册的 kind 保持现状（弹只读详情层） | 点击语义实现 |

激活上下文 `ComposerChipActivationContext`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `token` | `ComposerChipTokenView` | 宿主解码后的胶囊视图（见下） |
| `anchor` | `HTMLElement` | 触发胶囊元素（供插件定位自身浮层） |
| `source` | `'composer' \| 'replay'` | 消费点：输入框草稿 / 消息回放 |
| `workspaceRoot` | `string` | 消费侧解析好的工作区根（任务工作区优先、注册表首选兜底） |
| `taskId` | `string?` | 任务 id（草稿态可能无） |

胶囊视图 `ComposerChipTokenView`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `kind` | `string` | 胶囊种类 key |
| `label` | `string` | 胶囊显示文字 |
| `summary` | `string?` | 可选摘要（悬浮 / 详情用） |
| `opaqueText` | `string` | 原始 opaque token 串（自包含渲染用） |
| `payload` | `Record<string, unknown>` | 宿主解码后的 payload（宿主只解码不解释，插件自行校验字段） |

### 17.2 注册示例（`selection-reference` 插件）

```ts
// web/index.ts
import type { PluginModule, ComposerChipActionDefinition } from '@everyagent/plugin-api'
import { SELECTION_KIND } from './token'

const chipAction: ComposerChipActionDefinition = {
  kind: SELECTION_KIND,        // 'file.selection'
  pluginId: 'selection-reference',
  onActivate: (c) => {
    // 点胶囊 → 切回原文件标签并定位选区起始行（缺行号则仅打开文件）
    const { workspaceRoot, filePath, startLine } = c.token.payload as {
      workspaceRoot: string
      filePath: string
      startLine: number | null
    }
    ctx.ui.openFileTab(workspaceRoot, filePath, { lineNumber: startLine ?? undefined })
  },
}

const plugin: PluginModule = {
  activate(ctx) {
    ctx.ui.registerComposerChipAction(chipAction)
  },
}
export default plugin
```

### 17.3 宿主消费链与坑

- **两个消费点**：① 输入框草稿 `InlineComposer.handleRootClick`（`every-agent-web/src/components/taskComposer/InlineComposer.tsx:685`）——点击胶囊从 opaque 串自包含解析后按 `pluginDispatcher.findComposerChipAction(parsed.kind)`（`InlineComposer.tsx:728`）查表，命中即以 `source:'composer'`（`:739`）、`workspaceRoot` 取自组件 `workspaceRoot` prop 链路（`:740` 的 `workspaceRootRef`；prop 由 `TaskComposerSurface.tsx:819` 从 `workspace || workspaceRegistry.primaryRoot() || ''`（`TaskComposerSurface.tsx:244`）传入）回调；② 消息回放 `AgentMessageThread.UserMessageReplay.handleChipActivate`（`every-agent-web/src/components/task/AgentMessageThread.tsx:276`）——`findComposerChipAction(token.kind)`（`:277`），命中即以 `source:'replay'`（`:288`）、`workspaceRoot = (taskId ? taskStore.get(taskId)?.workspace : undefined) || workspaceRegistry.primaryRoot() || ''`（`:290`，任务工作区优先、注册表首选兜底）回调。两处**未命中 → 保持现状：弹只读详情层 `openSlashItemDetail`**（`InlineComposer.tsx:746`、`AgentMessageThread.tsx:296`）。
- **查表规则（`findComposerChipAction`）**：注册表内按 `kind` **首个命中**（`every-agent-web/src/plugin/PluginDispatcher.ts:369-371`，`.getAll().find((item) => item.kind === kind) ?? null`）⇒ **先注册者胜**。核心的 `system.workspace_file` 行为在首屏**同步**注册（先于异步的 `loadPlugins()`），故核心恒优先——**插件不能顶替核心 kind**，同 kind 后注册的插件动作会被静默忽略（查表永远先命中核心那条）。
- **核心已迁入**：核心自身的 `system.workspace_file` 胶囊点击行为已从两处宿主组件硬编码迁到本扩展点——`every-agent-web/src/plugin/coreComposerChipActions.ts`（`pluginId:'core'`，与 `coreRoundTailPanels` 同范式），在 `main.tsx` 里 `initializeCoreRoundTailPanels()` 之后、`void loadPlugins()` **之前**同步调用 `initializeCoreComposerChipActions()`（`every-agent-web/src/main.tsx:53`，`loadPlugins()` 在 `:55`）；`InlineComposer`/`AgentMessageThread` 里已无 kind 字面量分支。核心 action 语义：`readWorkspaceFileToken` 解析 payload（`coreComposerChipActions.ts:48`）+ `ctx.workspaceRoot` 非空 → `pluginDispatcher.openFileTab(workspaceRoot, target.filePath)`（`:50-54`）；缺任一条件则回落 `openSlashItemDetail(ctx.anchor, { token })`（`:58`）。行为与迁移前一致。
- **降级**：未命中（无插件为该 kind 注册动作）→ 保持现状（弹只读详情层），不报错。
- **同一 kind 两处走同一注册表**：插件用上下文里的 `source` 区分场景。
- **坑**：① `payload` 是不透明袋——宿主只解码不解释，字段缺失 / 类型错要插件自己兜底降级（建议 payload 里自带定位所需的 `workspaceRoot`/`filePath`，别假设宿主侧解析出的工作区根一定等于原文件的工作区根）；② `source:'replay'` 时没有输入框草稿桥，只能靠 `openFileTab` 等宿主动作响应，别假设能写草稿；③ 往输入框插胶囊用 `appendComposerToken`（见 §18），别自实现 opaque 线格式。
- **坑（`appendComposerToken` 三则）**：① `appendComposerToken` 的 `payload` 形参是 `Record<string, unknown>`，插件侧 `interface`（如 `SelectionPayload`）**无索引签名** ⇒ 直接传会被 TS 判为不可赋值（TS2322），需展开成新对象字面量 `payload: { ...payload }`（见 §16.2 示例）；② 草稿桥（`setComposerBridge`）只在**激活**任务标签注入——`TaskChat.tsx` 的注入 effect 首行有 `if (!isActive) return` 守卫（`every-agent-web/src/components/task/TaskChat.tsx:711-713`），故没有激活任务标签时（或首个 `TaskChat` 尚未挂载）`appendComposerToken`/`appendComposerText` **静默丢弃**（同 `appendComposerText` 的时序坑）；③ `kind`/`label` 为空时由宿主 `buildOpaqueTokenText` **抛错**（`every-agent-web/src/composerToken/composerOpaqueToken.ts:52-56`，插件错误立刻可见），`appendComposerToken` 自身不做 kind 业务分支。
- **内置范例**：`selection-reference`（`kind='file.selection'`，点击经 `openFileTab(..., { lineNumber })` 切回原文件标签并定位起始行）；核心 `system.workspace_file` 亦经此扩展点注册。

## 18. UiRegistry 动作方法（6 个）

动作方法不注册 UI，而是**驱动宿主**。实现链统一：`pluginDispatcher` 同步委托模块级 holder 桥（`pluginRuntimeBridge.ts`），宿主组件挂载时注入、未注入时静默降级（`pluginRuntimeBridge.ts:1-10` 注释）。

| 方法 | 签名（`js/index.ts:834-852`） | 宿主实现链 | 行为 |
|---|---|---|---|
| `openTab` | `(type: string, data?: Record<string,string>, title?: string) => void` | `PluginDispatcher.ts:377-379` → `Layout.tsx:803-813` 注入（`setShellBridge`）→ `Layout.tsx:573` 实现 | 内置/插件统一的通用标签打开入口：按 `tabTypeKey` 查注册表取创建器（内置型自带 `createTab`，插件型/未注册型走通用兜底）创建标签，`openWorkspaceTab` 入栈；`data` 可选，原样存进 `tab.data`，由 §4 的 `renderTab` 读回（范例 `GitHistoryTabType.tsx:19-22,27-33` 的 `getData`） |
| `openFileTab` | `(workspaceRoot, filePath, {mode?, lineNumber?}) => void` | `PluginDispatcher.ts:379-381` → `Layout.tsx:819-825` 注入 → `Layout.tsx:480-504` 实现（`openGlobalFileTab`） | 打开顶层文件标签；`mode:'readwrite'|'readonly'` 与 `lineNumber`（打开到指定行）透传。**`lineNumber` 透传链**：`PluginDispatcher.openFileTab`（`PluginDispatcher.ts:379-381`）→ `pluginRuntimeBridge.ShellBridge.openFileTab`（`pluginRuntimeBridge.ts:16`）→ `Layout` 注入的 `setShellBridge.openFileTab`（`Layout.tsx:819-825`）→ `openGlobalFileTab({ mode, lineNumber })`（`Layout.tsx:480-504`）；标签已存在时保留原 `reloadKey`（仅更新行定位字段 `lineNumber`/`lineLocateRequestedAt`，`Layout.tsx:490-500`），不重新读盘、编辑器不卸载重挂 |
| `openDiffTab` | `(input: PluginDiffTabInput) => void` | `PluginDispatcher.ts:382-384` → `Layout.tsx:825` | 打开 diff 对比标签；`input` 字段见 `js/index.ts:189-209`（`filePath/fileName/changeType/beforeContent/afterContent/workspaceRoot?/title?/binary?/allowRestore?`） |
| `appendComposerText` | `(text) => void` | `PluginDispatcher.ts:385-387` → `TaskChat.tsx:690-696` 实现、`:711-713` 注入 | 向**当前激活**任务标签的草稿末尾追加纯文本 |
| `setComposerRawContent` | `(raw) => void` | `PluginDispatcher.ts:398-400` → `TaskChat.tsx:697-703` | 用 rawContent（可含 opaque token 串）**整体替换**草稿（编辑重发回填用） |
| `appendComposerToken` | `(input: {kind, label, summary?, payload?, spaced?}) => void` | `PluginDispatcher.ts:388-397` → `buildOpaqueTokenText`（`composerToken/composerOpaqueToken.ts:46`）→ `getComposerBridge()?.appendText`（`pluginRuntimeBridge.ts:45`） | 结构化插入一枚胶囊（宿主单点负责 opaque token 编码，插件不碰线格式）；`spaced ?? true` 时补两侧空格（`PluginDispatcher.ts:395-396`）；`kind`/`label` 为空由 `buildOpaqueTokenText` **抛错**（`composerOpaqueToken.ts:52-56`）；未绑定草稿（桥 null）**静默丢弃**；自身不做 kind 业务分支 |

```ts
// 典型组合：点击工具视图里的文件名打开文件标签（update-file-view 同款，UpdateFileToolView.tsx:85）
ctx.ui.openFileTab(workspaceRoot, filePath, { mode: 'readonly' })
// 打开本插件注册的 git-history 式标签（git/web/index.ts:44-49 同款）
ctx.ui.openTab('my-view', { workspaceRoot, path }, `我的视图：${name}`)
// 打开 diff（GitSidebarPanel.tsx:400 同款）
ctx.ui.openDiffTab({ filePath, fileName, changeType: 'updated', beforeContent, afterContent, workspaceRoot })
// 编辑重发回填（EditMessageButton.tsx:60,62 同款）
ctx.ui.setComposerRawContent(rawContent)
ctx.ui.appendComposerText('\n\n（追加一段）')
// 打开文件到指定行（选区引用回跳同款）
ctx.ui.openFileTab(workspaceRoot, filePath, { lineNumber: 42 })
// 结构化插入一枚胶囊（宿主单点编码 opaque token；payload 需展开成对象字面量，见 §17 坑）
ctx.ui.appendComposerToken({ kind: 'file.selection', label: 'foo.ts:3-5', payload: { ...payload } })
```

注意两个时序坑：① 页面刚加载、`Layout`/`TaskChat` 尚未挂载时桥为 null，动作**静默丢失**（不抛错）；② 草稿桥只在**激活标签页**持有写权（`TaskChat.tsx:703-713` 注释解释竞态），多任务标签并存时写入的是当前激活的那个。

## 19. Disposable 与刷新

- **dispose 语义是真的**：`ListExtensionRegistry.register` 返回的 Disposable 从数组 splice 并通知宿主重渲染（`ExtensionRegistry.ts:45-56`）——`ctx.events.on`、`ctx.commands.registerCommand` 同理（见[前端 ctx API](context-api.md) §9）。`registerTraceType` / `registerOutputBlock` 的 dispose 也已补齐侧路注销（`traceTypeRegistry.unregisterTraceType` / `outputBlockRegistry.unregister`，known-issues #3 修复前曾是不清真实消费方的假 Disposable）。
- **宿主收集并在页面卸载时统一 dispose**：激活时 `ctx.ui` 等注册表都包了收集代理（`pluginLoader.ts` 的 `trackDisposables`），注册返回的 Disposable 全部进该插件的 `disposables`；页面卸载（`pagehide`）时宿主先调 `deactivate` 再逆序 dispose 全部注册项并移除插件 CSS（known-issues #8 修复前 disposables 恒为空数组、`deactivate` 零调用）。运行期没有热卸载——中途注销仍需自己持有 Disposable 调 dispose。
- **刷新即丢**：注册表、订阅、blob 模块全是内存态，页面刷新全部清零并重新走加载链路（[加载链路](overview-and-loading.md)）；跨刷新要保留的状态用 `ctx.storage`（localStorage）。
- **同一会话内的「更新」= 重新加载页面**：改了插件 web 代码要重跑 `npm run build:plugins` 再刷新（该脚本不在任何流水线内）；后端启停/装卸一律重启 worker（[plugin.json 字段参考](../plugin-manifest.md) §6；扩展面板「重新加载」按钮**仅在有待生效变更时显示**，待生效变更涉及含 `main` 的插件时会弹确认并自动完成重启 + 刷新）。

## 20. 下一步读

- `ctx.ui` 之外的能力（rpc/storage/events/fs/commands）：[前端 ctx API](context-api.md)
- 加载链路与 bare import 白名单：[加载链路](overview-and-loading.md)
- 面板刷新信号用的宿主事件全集：[事件](events.md)
- `task.submit_contributions` 的 metadata 谁在后端消费：[后端任务与 RPC](../backend/task-and-rpc.md)
- 全部内置插件一张表：[内置插件索引](../reference/builtin-plugins.md)
