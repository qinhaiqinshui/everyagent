---
title: 前端插件总览与加载链路
nav_order: 9
parent: web
has_children: false
---

# 前端插件总览与加载链路

**一句话定位**：本文讲清「一个前端插件从 `web/index.ts` 源码到出现在屏幕上」的完整链路——esbuild 预编译 → `plugin.webSource` RPC 拉取 → bare import 改写 → blob URL 动态 `import()` → `activate(ctx)` 注册贡献 → 宿主订阅重渲染，并给出这条链上每一步的失败症状与六条硬约定。`ctx` 各成员的 API 细节见 [前端 ctx API](context-api.md)，12 个扩展点的字段表见 [UI 扩展点](ui-extensions.md)。

## 1. 一图流：从 `web/index.ts` 到上屏

```text
── 构建期（手工触发，无人替你跑）──────────────────────────────────────
every-agent-plugins/<id>/web/index.ts   （入口；JSX 组件放同级 .tsx）
      │  npm.cmd run build:plugins      every-agent-web/scripts/build-plugins.mjs
      │  esbuild bundle：format=esm / target=es2022 / jsx=automatic / sourcemap
      │  external 5 项白名单；CSS 抽到同名 web/index.css
      ▼
web/index.js（+ index.css + map）       产物，.gitignore:25-28 排除，不入库

── 运行期（浏览器内，pluginLoader.ts 驱动）──────────────────────────
loadPlugins()                           main.tsx:46 启动、:63 重连、:69 切换 worker
      │  Promise 锁防并发重入           pluginLoader.ts:286,315-321
      │ ① 取第一个在线 worker           :328-334（无 → 静默 return，零插件零报错）
      │ ② sys.info 拿默认工作区根       :338-345（失败不阻塞）
      │ ③ plugin.list → 过滤            :347-367
      │      active && hasWebMain && !disabledSet.has(id)
      │ ④ 幂等：loadedPlugins 已有 id 跳过 :371-374
      │ ⑤ plugin.webSource{pluginId, path: webEntryJsPath(webMain)}
      │      （webMain 去扩展名拼 .js；缺省回退 web/index.js）
      │      worker 侧 jail 到插件目录   PluginRpcMethods.java:187-197
      │      Files.readString → 纯文本   :201
      │ ⑥ rewriteBareImports            :432（白名单 BARE_IMPORT_MAP :69-75）
      │      5 项 bare import → window.__EA_*（宿主注入 :54-58）
      │ ⑦ Blob + createObjectURL → import() → revoke   :434-440
      │ ⑧ mod.default ?? mod；activate 必须是函数       :375-379
      │ ⑨ 注入 CSS：<style id="plugin-css:<id>"> → document.head   :448-465
      ▼
activate(ctx)                           :404（ctx 构造 :381-395，详见 context-api.md）
      │  ctx.ui.register* → PluginDispatcher → ExtensionRegistry.register
      │      push + notify              ExtensionRegistry.ts:45-56
      ▼
extensionsVersion++                     PluginDispatcher.ts:69-79
      → useSyncExternalStore 重渲染（Layout.tsx:139-142 等）→ 贡献上屏
```

### 每步的失败症状（排查入口见 [故障排查](../guides/troubleshooting.md)）

| 断点 | 症状（以源码为准） | 证据 |
|---|---|---|
| 没跑 `build:plugins`，worker 读不到文件 | `plugin.webSource` 报 `NOT_FOUND`，前端 console：`[plugins] 插件 <id> 加载失败: 插件 <id> 无 <产物路径> 源码`（产物路径 = `webMain` 换算结果，约定即 `web/index.js`） | `pluginLoader.ts`（`loadPluginModule` 空内容抛错）；worker 侧 `PluginRpcMethods.java:194-197` |
| `webMain` 缺失或为空 | `hasWebMain=false` → 第 ③ 步被过滤，**插件完全不出现，无任何报错** | `PluginRpcMethods.java:70`、`pluginLoader.ts:363-367` |
| 插件被禁用（`active=false` 或在 `disabledIds`） | 同上，静默消失 | `pluginLoader.ts:363-367` |
| 用了白名单外的 bare import / 动态 `import('antd')` / `export {X} from 'antd'` | 改写器不动它 → 残留 import 语句进 blob → `import()` 抛模块解析错误 → 加载失败 warn | `pluginLoader.ts:69-75`（仅 5 项映射） |
| 入口没 `export default`、或 default 不是 `{ activate }` | **静默 `continue`，连 warn 都没有** | `pluginLoader.ts:375-379` |
| `web/index.css` 缺失或读取失败 | 无样式，但不阻塞激活 | `pluginLoader.ts:398-402` |
| worker 离线 / `plugin.list` 失败 | 整体静默降级，零插件零报错，重连后自动重试 | `pluginLoader.ts:332-334`、`:357-359`；`main.tsx:62-64` |

两个容易误判的点：

- **重连与切换 worker 会重试**：`main.tsx:46,63,69` 三处触发 `loadPlugins()`；幂等靠 `loadedPlugins.has(id)`（`pluginLoader.ts:371-374`）+ 加载 Promise 锁（`:286`、`:315-321`，`273-285` 注释解释了为什么需要——否则同一插件会被 `activate()` 两次、侧边栏出双份图标）。切换 worker 只**补**新 worker 独有的插件，旧插件不卸载（`main.tsx:65-67` 注释）。
- **`ctx.extensionPath` = `webMain` 换算出的产物路径**（`pluginLoader.ts` 的 `webEntryJsPath`；约定值 `"web/index.ts"` 换算即 `'web/index.js'`）——与 `plugin.webSource` 请求的 path 一致；它不是 blob URL，别拿它拼 `../` 之类的相对路径。

## 2. 一个 web 插件的组成

```text
every-agent-plugins/<id>/
├─ plugin.json            # 必须有非空 webMain（约定值 "web/index.ts"，见下）
└─ web/
   ├─ index.ts            # 入口：export default PluginModule
   │                      # ⚠️ 入口是 .ts：esbuild 的 ts loader 不解析 JSX，
   │                      # JSX 组件放同级 .tsx（仓内 9 个含 web 的插件全部如此）
   ├─ SomePanel.tsx       # 组件 / 图标 / 工具模块（jsx:automatic）
   ├─ index.js|index.css  # esbuild 产物（gitignored），构建后才存在
   └─ *.map               # sourcemap（同样 gitignored）
```

配套 `plugin.json`（字段全景见 [plugin.json 字段参考](../plugin-manifest.md)）：

```json
{
  "id": "my-notes",
  "name": "我的笔记",
  "version": "0.1.0",
  "author": "you",
  "webMain": "web/index.ts"
}
```

最小完整 `web/index.ts`（只用允许的来源：`import type` 取类型、白名单内 bare import 取运行时；零 `@/` 引用）：

```ts
// web/index.ts —— 最小完整入口
import type { PluginContext, PluginModule } from '@everyagent/plugin-api'
import React from 'react'          // 白名单项，改写为 window.__EA_REACT__
import { Badge } from 'antd'       // 白名单项，改写为 window.__EA_antd__ 解构

const Panel: React.FC = () => React.createElement(Badge, { count: 42 })

const plugin: PluginModule = {
  activate(ctx: PluginContext) {
    // 插件的一切宿主能力都从 ctx 走；icon 是 ReactNode，自己带
    ctx.ui.registerSidebarItem({
      id: 'my-notes',
      title: '我的笔记',
      icon: React.createElement('span', null, '📓'),
      Panel,
      order: 50, // float；缺省 100，坐标系见 §5
    })
  },
  // deactivate 写了也永远不会被调（见 §3 约定 6），省略
}

export default plugin
```

真实成品可抄：`every-agent-plugins/pdf-viewer/web/index.ts:12-21`（最短：import descriptor 后一行 `ctx.ui.registerFileContentEditor(descriptor)`）、`every-agent-plugins/git/web/`（最全：侧边栏 + 标签类型 + 右键动作 + 轮末面板）。

## 3. 六条硬约定

每条按「为什么 → 怎么做 → 违反症状」给出。这六条是前端插件全部的「看不见的契约」，全部可回源码验证。

### 约定 1：入口契约 —— `webMain: "web/index.ts"` 非空 + `export default { activate(ctx) }`

- **为什么**：`webMain` 非空决定 `hasWebMain` 真假（`PluginRpcMethods.java`），其**值也被前端消费**——产物路径 = `webMain` 去扩展名拼 `.js`（`pluginLoader.ts` 的 `webEntryJsPath`，空值/旧 worker 回退 `web/index.js`；known-issues #5 修复前该值不被消费、路径硬编码）。加载后取 `mod.default ?? mod`，所以入口必须 default 导出一个带 `activate` 的对象（类型声明 `every-agent-plugin-api/js/index.ts`）。
- **怎么做**：`plugin.json` 写 `"webMain": "web/index.ts"`（仓内 9 个含 web 的插件全是这个值，与 `build-plugins.mjs` 的产物位换算一致）；入口 `export default plugin`，`activate(ctx)` 里注册全部贡献。
- **违反症状**：`webMain` 空 → 插件在前端**静默消失**；没 default 导出 / default 无 `activate` → **静默跳过，控制台无输出**；`webMain` 写了非约定路径而产物仍落在 `web/index.js` → `plugin.webSource` 读不到文件，加载失败。

### 约定 2：类型只能 `import type`

- **为什么**：`@everyagent/plugin-api` 是**纯类型包**——`every-agent-plugin-api/js/package.json` 只有 `"types": "index.ts"`，无 `main`/`exports`，全部导出是 interface/type。esbuild 会把纯类型位置的 import 整体擦除（所以忘写 `type` 「错但不炸」）；一旦当**值**用（`export { X } from '@everyagent/plugin-api'`、运行时访问），构建期就要解析模块名，而插件目录之上没有任何 `node_modules`（仓库根与 `every-agent-plugins/` 都没有；`file:` 符号链接只装在 `every-agent-web/node_modules`，见 `every-agent-web/package.json:42`）⇒ `build:plugins` 直接失败 `Could not resolve "@everyagent/plugin-api"`。另 `every-agent-web/tsconfig.json:11` 开了 `isolatedModules` ⇒ 再导出类型必须 `export type`。
- **怎么做**：一律 `import type { PluginContext, PluginModule } from '@everyagent/plugin-api'`；需要运行时对象时只用 `ctx`。
- **违反症状**：纯类型位置忘写 `type` → 无症状（但别依赖）；值位置 → 构建期报错，产物不更新。

### 约定 3：bare import 白名单只有 5 项

- **为什么**：blob URL 里的 `import()` 解析不了任何裸模块名，宿主只改写这 5 项（`BARE_IMPORT_MAP`，`pluginLoader.ts:69-75`）到 5 个 window 全局——`window.__EA_REACT__ / __EA_REACT_DOM__ / __EA_REACT_JSX__ / __EA_antd__ / __EA_ICONS__`，由宿主在模块加载时注入（`pluginLoader.ts:54-58`），保证插件与宿主共用**同一个 React 实例**（多实例会 hooks 报错）。构建侧 `esbuild external` 同样只有这 5 项（`every-agent-web/scripts/build-plugins.mjs:63-69`）。
- **怎么做**：第三方库要么不用，要么让 esbuild 打进 bundle（非 external 的依赖会被打包，前提是解析得到）；改写器认 5 种 esbuild 产出形态：默认导入、命名导入（含 `as` 重命名）、命名空间、默认+命名混合、副作用导入（`pluginLoader.ts:103-141`）。
- **违反症状**：`import _ from 'lodash'`、动态 `import('antd')`、`export { Badge } from 'antd'` 都**不在改写范围** → 残留 import 进 blob → 激活时抛模块解析错误，console 出 `[plugins] 插件 <id> 加载失败:`。

### 约定 4：禁 `@/` 宿主内部引用，UI 与图标自带

- **为什么**：`@/` 是宿主 vite alias（`every-agent-web/src/*`），宿主模块不在插件 bundle 里、也不暴露任何全局；blob 模块里 `@/...` 必然解析失败。核实：全仓 `every-agent-plugins/**/*.ts(x)` 对 `from '@/` / `from "@/` 的引用**零命中**（rg 单双引号两种均零）——这不是软建议，是仓内既有事实。
- **怎么做**：一切宿主能力经 `ctx`（`sdk`/`ui`/`events`/`fs`/`storage`/`commands`，逐成员见 [前端 ctx API](context-api.md)）；图标用 ReactNode 自带（emoji、SVG、`@ant-design/icons` 白名单组件均可）；需要宿主动作（开标签页、写输入框）用 `ctx.ui.openPluginTab` 等 5 个动作方法（§4）。
- **违反症状**：构建期 `Could not resolve "@/..."`，或产物里残留不可解析 import → 激活失败。

### 约定 5：产物手工构建、不入库，且不在 web dev/build 流水线里

- **为什么**：`every-agent-web/package.json` 的 `dev`（`:7` vite）、`build`（`:8` tsc && vite build）都**不含** `build:plugins`（`:9`）；esbuild 也没被声明为依赖（靠 vite 传递引入，`package.json` 依赖表 rg 确认零命中）。桌面打包链会经 Python 包装间接调它（`every-agent-desktop/package.json:11,16` 的 `build:assets` → `scripts/build-plugins.py:219` 以 `node build-plugins.mjs` 执行），但日常 web 开发循环没人替你跑。脚本无 watch、无 `--only`，每次全量（`scripts/build-plugins.py:216` 注释自证）。产物被根 `.gitignore:25-28` 排除。
- **怎么做**：改任何插件前端代码后：`cd every-agent-web ; npm.cmd run build:plugins`（PowerShell 下 `npm` 会被执行策略拦截，用 `npm.cmd`），然后刷新页面。
- **违反症状**：新克隆仓库直接 `npm run dev` → **内置 web 插件全部静默不出现**（worker 读不到 `web/index.js`）；忘了 `npm install` → esbuild 缺失，构建脚本报错（`scripts/build-plugins.py:213-214` 显式检查）。

### 约定 6：生效边界 —— 刷新页面 / 重启 worker；前端从不卸载插件

- **为什么**：启停/装卸走 worker 的 `plugin.*` RPC，文案自证「重启 worker 后生效」；前端侧 `loadedPlugins.set(id, { module, disposables: [] })` 的 `disposables` **恒为空数组**（`pluginLoader.ts:405`），没有任何代码往里放东西，也没有 unload 路径——`every-agent-web/src` 全目录 `deactivate` 零命中（rg 确认）。页面刷新即内存态全部清零（blob 模块、注册表、事件订阅），活下来的只有 `ctx.storage`（localStorage）与 worker 侧数据。注册后的**上屏**不靠刷新，靠订阅：`ExtensionRegistry.register/dispose` 都会 notify（`ExtensionRegistry.ts:45-56`），`PluginDispatcher` 聚合成 `subscribeExtensionsChanged` + 自增 int `getExtensionsVersion()`（`PluginDispatcher.ts:69-92`），宿主用 `React.useSyncExternalStore` 消费（`Layout.tsx:139-142`、`TaskChat.tsx:818`）——版本号而非数组作快照，正是为了避免「新数组引用导致无限重渲染」（`PluginDispatcher.ts:66-68` 注释）。
- **怎么做**：改前端 = `build:plugins` + 刷新页面；启停/装卸 = 对应 RPC + 重启 worker；自己的 Disposable 自己持有（宿主不代管）。
- **违反症状**：改了 `web/index.ts` 不重跑构建 → 刷新也看不到变化（浏览器执行的是旧 `index.js`）；`plugin.disable` 后不重启 worker → 插件照常运行。

## 4. 扩展点清单（导航枢纽）

前端插件能注册/调用的一切。常量名定义在 `every-agent-web/src/plugin/PluginDispatcher.ts:33-44`，类型声明在 `every-agent-plugin-api/js/index.ts:607-634`；逐扩展点字段表与注册代码见 [UI 扩展点](ui-extensions.md)。

### 4.1 12 个 `ctx.ui.register*`（全部返回真清理的 Disposable）

| 扩展点常量名 | register 方法 | 一句话 | 状态 |
|---|---|---|---|
| `ui.sidebar_items` | `registerSidebarItem` | 侧边栏活动栏项（唯一有 `order` 的扩展点，§5） | 正常 |
| `ui.workspace_tab_types` | `registerWorkspaceTabType` | 顶层工作区标签页类型 | 正常 |
| `ui.file_sidebar_panels` | `registerFileSidebarPanel` | 文件页侧栏面板（按文件可用性过滤） | 正常 |
| `ui.composer_above_panel` | `registerComposerAbovePanel` | 输入框上方面板（**单数**命名，其余皆复数） | 正常 |
| `ui.tool_call_views` | `registerToolCallView` | 按 `toolName` 整体接管工具调用折叠/展开视图 | 正常 |
| `ui.user_message_actions` | `registerUserMessageAction` | 用户消息上的动作组件 | 正常 |
| `task.submit_contributions` | `registerTaskRunSubmitContributionProvider` | task.run 提交时的元数据/按钮文案贡献 | 正常 |
| `ui.trace_types` | `registerTraceType` | trace 记录渲染类型（侧路同步进 traceTypeRegistry，`PluginDispatcher.ts:267-272`） | 正常 |
| `ui.output_blocks` | `registerOutputBlock` | 按 tag 渲染输出块（侧路 outputBlockRegistry，`:273-284`） | 正常 |
| `ui.file_content_editors` | `registerFileContentEditor` | 按扩展名注册文件编辑器（可覆盖内置） | 正常 |
| `ui.file_explorer_actions` | `registerFileExplorerAction` | 文件树右键动作 | **死扩展点：注册了也不显示**（§6） |
| `ui.round_tail_panels` | `registerRoundTailPanel` | 任务轮末展示区组件 | 正常 |

注：`dispatch()` 的 switch 只列前述 10 个 `ui.*`/`task.*` 常量（`PluginDispatcher.ts:197-226`）；`ui.trace_types` / `ui.output_blocks` 走侧路注册表，不影响使用。

### 4.2 `ctx.ui` 的 5 个动作方法（触发宿主动作，不注册东西）

`openPluginTab(type, data, title?)` / `openFileTab(workspaceRoot, filePath, {mode?})` / `openDiffTab(input)` / `appendComposerText(text)` / `setComposerRawContent(raw)`（`index.ts:622-630`）。实现经模块级 holder 桥接（`every-agent-web/src/plugin/pluginRuntimeBridge.ts`）：这些能力原本只挂在 React Context 上，插件是非 React 模块拿不到 hook 结果，故由宿主组件挂载时注入——`Layout.tsx:780`（`setShellBridge`，卸载清 null `:790`）、`TaskChat.tsx:721`（`setComposerBridge`）。**未注入时静默降级**（dispatcher 里全是 `getShellBridge()?.xxx` 可选链，`pluginRuntimeBridge.ts:1-10` 注释自证「零状态、零缓冲……未注入时静默降级」）；时序上安全，因为 `activate` 总在 RPC 往返之后才执行，宿主组件通常已挂载。

### 4.3 `ctx` 其余成员

| 成员 | 是什么 | 详解 |
|---|---|---|
| `ctx.sdk` | `rpc(workerId, method, params)` + 工作区注册表快照 + 当前 worker id | [前端 ctx API](context-api.md) §3 |
| `ctx.storage` | localStorage，键前缀 `plugin:<id>:` | 同上 §4 |
| `ctx.commands` | 插件私有命令表（不跨插件、无内置命令） | 同上 §5 |
| `ctx.events` | 宿主进程内领域事件总线的最薄委托 | [事件](events.md) |
| `ctx.fs` | `listDir` / `delete`（jail 到工作区根，非插件目录） | [前端 ctx API](context-api.md) §6 |

## 5. 排序与覆盖规则

### 5.1 只有侧边栏有 `order`：float 坐标系，统一升序混排

宿主把内置项与插件项**合并成一个数组按 `order` 升序排**（`Layout.tsx:1073-1091`，`:1090` 的 sort；同 order 稳定排序——内置在前、插件按注册先后，`:1070` 注释）。缺省 100（`DEFAULT_SIDEBAR_ORDER`，`Layout.tsx:1064`）。实测坐标：

| order | 归属 | 来源 |
|---|---|---|
| 1 | 任务（内置） | `Layout.tsx:1075` |
| 2 | 文件（内置） | `Layout.tsx:1076` |
| 3 | 搜索（内置） | `Layout.tsx:1077` |
| 5 | git 插件「源代码管理」 | `every-agent-plugins/git/web/index.ts:35` |
| 9 | 扩展管理（plugin-manager 插件） | `every-agent-plugins/plugin-manager/web/index.ts:24` |
| 10 | 设置（内置） | `Layout.tsx:1078` |
| 100 | 未声明 `order` 的插件项 | `Layout.tsx:1064,1085` |

`order` 是 number（float）：`4.5`、`7.25` 这类小数合法，可精确插进任意空档；负数也会照常参与排序（未实测界面表现，不推荐）。

### 5.2 其余扩展点没有 order，靠「插件在前」的合并顺序覆盖内置

- 文件编辑器：`[...插件注册, ...内置 glob 扫描]`——同名扩展名**插件优先覆盖内置**（`every-agent-web/src/components/files/editors/registry.ts:26`，`21-25` 注释明说）。pdf-viewer 覆盖 `.pdf` 即走此路。
- 工作区标签类型：`[...插件注册, ...内置定义]`（`every-agent-web/src/plugin/workspaceTabTypes.ts:32`）。
- 工具调用视图：注册了对应 `toolName` 即**整体接管**该工具的折叠+展开渲染（`every-agent-web/src/components/task/toolViews/registry.ts:43,54`）。

推论：想覆盖某个内置行为，注册同 key/同扩展名的插件贡献即可，无需（也没有）优先级参数。

## 6. 与架构文档 §8.5 的差异与已知限制

[../../ARCHITECTURE.md](../../ARCHITECTURE.md) §8.5（`docs/ARCHITECTURE.md:1128-1139`）描述的统一加载架构与代码主体一致，但有以下已核实的偏差/限制（完整登记见 [已知问题与现状偏差](../reference/known-issues.md)）：

1. **两套扩展机制并存**：§8.5 写「不再有 `import.meta.glob` / `builtInPlugins.ts`」（`docs/ARCHITECTURE.md:1137`），这只对**插件加载**成立；宿主内部注册表仍在用 glob——实际用 glob 的文件仅两个：`every-agent-web/src/components/files/editors/registry.ts:11`（模式含 `../../../plugins/*/editors/*FileEditor.tsx`，顺带发现 `src/plugins/{image,markdown}/editors/` 下的内置编辑器）与 `every-agent-web/src/components/task/toolViews/registry.ts:15`。⇒ 宿主内置编辑器是 `@/` 内部代码**不是插件**，不受本文约定约束。
2. **§8.5 的 external 清单漏了 `react/jsx-runtime`**（`docs/ARCHITECTURE.md:1137` 列 4 项；代码是 5 项：`pluginLoader.ts:69-75`、`build-plugins.mjs:63-69`）。
3. **4 个插件的头注释仍写「经 builtInPlugins.ts 自动发现加载」**（该文件已删，真实加载走 `plugin.list` RPC）：`every-agent-plugins/pdf-viewer/web/index.ts:4`、`update-file-view/web/index.ts:4`、`ai-review/web/index.ts:4`、`git/web/index.ts:4`。
4. **`git/web/index.ts:2` 自称「纯 Web 插件」**，实际其 `plugin.json` 同时有 `main`（java+web 混合形态），注释失真。
5. **`plugins-loaded` 是死事件**：`every-agent-web/src/events/domainEvents.ts:127` 声明、`:355-357` 定义载荷，全仓**零 emit**——别订阅它感知加载完成，用 console 日志或 `plugin.list`。
6. **两个「注册了也不显示」的死贡献**：`ui.file_explorer_actions`——`registerFileExplorerAction` 可调（`PluginDispatcher.ts:300-302`）、`listRegisteredFileExplorerActions` 也存在（`:296-298`），但宿主 UI **零消费**（rg：`every-agent-web/src` 中相关符号只出现在 `PluginDispatcher.ts`）；`UiSidebarItemDefinition.Badge`（`index.ts:231`）——宿主合并时只读 `badgeCount`（`Layout.tsx:1084`），git 的 `GitChangeBadge` 组件当前不渲染。
7. **`register(pluginId, …)` 的 pluginId 形参被忽略**：接口带该参数（`ExtensionRegistry.ts:18,45`）但实现只 `items.push(item)`（`:46`），`PluginDispatcher` 12 个 register 一律传 `''`（如 `:242,245,248`）——注册项无法按插件归属过滤。
8. **`build:plugins` 流水线口径**：web 侧 `dev`/`build` 确实不含它；但桌面 `build:assets` **会**经 `scripts/build-plugins.py` 间接调用（`every-agent-desktop/package.json:11,16` → `scripts/build-plugins.py:219`），与「不在任何流水线」的旧说法不符——本文以代码为准。

## 7. 验证：确认插件真的被加载

```powershell
# 1. 构建产物（PowerShell 下 npm 被执行策略拦截，用 npm.cmd）
cd every-agent-web
npm.cmd run build:plugins
# 预期：[build-plugins] 发现 N 个插件入口: …（逐个列出 id）
#       [build-plugins] 完成，共构建 N 个插件。
# 产物写回 every-agent-plugins/<id>/web/index.js(+.css/.map)

# 2. 类型检查（宿主 tsconfig 的 include 已覆盖插件 web/：
#    every-agent-web/tsconfig.json:32 "include": ["src", "../every-agent-plugins/*/web"]）
npm.cmd run typecheck

# 3. 刷新浏览器页面（dev 或 build 后的页面均可）
```

浏览器 DevTools Console 里按前缀 `[plugins]` 过滤，日志文案以源码为准（`pluginLoader.ts`）：

- 成功：`[plugins] 插件已激活: <id> (<name>)`（`:406`）
- 失败：`[plugins] 插件 <id> 加载失败: <原因>`（`:408`）

注意两个「无日志」的静默路径：入口无 `activate` 时直接 `continue`（`:375-379`）；worker 离线时整体静默（`:332-334`）。这两种情况用「产物文件是否存在 + `plugin.list` 是否列出该插件（active/hasWebMain）」排查，完整决策树见 [故障排查](../guides/troubleshooting.md)，命令矩阵见 [构建与运行](../guides/build-and-run.md)。

## 8. 下一步读

- `ctx` 各成员（sdk/storage/commands/events/fs）的签名与坑：[前端 ctx API](context-api.md)
- 12 个扩展点逐个字段表 + 注册代码：[UI 扩展点](ui-extensions.md)
- 36 个宿主事件与 9 个具名事件：[事件](events.md)
- `webMain`/`hasWebMain`/禁用机制在清单侧的口径：[plugin.json 字段参考](../plugin-manifest.md)
- 三形态构建矩阵与 cwd 陷阱：[构建与运行](../guides/build-and-run.md)
- 现象对不上本文时的逐条排查：[故障排查](../guides/troubleshooting.md)、[已知问题与现状偏差](../reference/known-issues.md)
