# 设计方案：Git 模块插件化

> 状态：草案，待确认后实施。
> 范围：将当前散落在 `every-agent-worker` / `every-agent-web` 核心中的 git 功能收敛为一个**内置插件模块**（Maven 子模块 + Web 插件入口），核心不再硬编码 git 面板/RPC/标签/Advisor。
> 约束：遵循 `docs/ARCHITECTURE.md` 唯一事实源；实现与文档冲突时先改文档再改代码。本文档落地前为提案，落地后其事实性结论回填进 ARCHITECTURE.md。

## 1. 背景与目标

当前 git 能力以「核心模块」形态散落在两侧，未走插件 SPI：

| 层 | 现状 | 问题 |
|---|---|---|
| worker | `modules/GitService.java`（13 个 `git.*` RPC 在构造器里直接 `dispatcher.register(...)`）；`git/NativeGit.java`、`modules/GitCredentialStore.java`；`task/GitAutoSyncAdvisor.java` + `plugin/adapters/GitAutoSyncAdvisorProvider.java`；`git/GitAutoSync{Token,SlashProvider,SlashResolver}.java` | 与核心耦合：`BuiltInAdvisorProviders` 显式 `new GitAutoSyncAdvisorProvider(gitService)`、`GitService` 直接持有 `RpcDispatcher` |
| web | `components/git/GitSidebarPanel.tsx`（1631 行）、`platform/git/gitGateway.ts`、`components/files/GitHistory{Panel,TabType}.tsx` 在核心目录；`Layout.tsx` 硬编码 `'git'` 活动栏项 + 4 个 `SidebarPanelHost` 之一 + `gitChangeCount` 角标 + `SIDEBAR_PANEL_ACTIVITY_IDS`/`validPanelIds` 常量集合 | 已有 `ui.sidebar_items` / `ui.workspace_tab_types` 扩展点但**核心未消费**（`workspaceTabTypes.ts` 注释「插件标签收集随插件底座裁剪,只保留内置定义」） |

目标：把上述全部 git 代码搬入 `every-agent-plugins/git/`，核心仅保留（a）通用扩展点机制（已存在，需补「核心消费」）与（b）与 git 无关但被 git 复用的共享原语（diff 标签页、`openDiffTab`、`openPluginTab`、`FileChangesCollector`/`FileChangeAdvisor`）。插件缺失时核心零 git 残留、不报错、相关 UI 入口自动隐藏。

## 2. 边界：什么搬走、什么留下

### 2.1 搬入 git 插件

**worker（Java，迁入 `every-agent-plugins/git/src/main/java/dev/everyagent/plugin/git/`）**
- `modules/GitService.java` → `GitService.java`（13 个 `git.*` RPC 仍由自身在构造器内 `dispatcher.register(...)`，但 `dispatcher` 现经插件模块的 Spring DI 注入——与 `auth-review` 的 `AiReviewSlashProvider` 直接注入 `SlashCommandRegistry` 同模式）
- `git/NativeGit.java` → `plugin/git/NativeGit.java`
- `modules/GitCredentialStore.java` → `plugin/git/GitCredentialStore.java`
- `task/GitAutoSyncAdvisor.java` → `plugin/git/GitAutoSyncAdvisor.java`
- `plugin/adapters/GitAutoSyncAdvisorProvider.java` → `plugin/git/GitAutoSyncAdvisorProvider.java`
- `git/GitAutoSyncToken.java` / `GitAutoSyncSlashProvider.java` / `GitAutoSyncSlashResolver.java` → 同包

**web（迁入 `every-agent-plugins/git/web/`）**
- `components/git/GitSidebarPanel.tsx`
- `platform/git/gitGateway.ts`
- `components/files/GitHistoryPanel.tsx` + `GitHistoryTabType.tsx`（注册到 `ui.workspace_tab_types`）

### 2.2 留在核心（共享原语，非 git 专属）

| 留下 | 理由 |
|---|---|
| `components/files/FileDiffPanel.tsx` + `FileDiffTabType.tsx` + `types.WorkspaceDiffTab` + `createWorkspaceDiffTab` + `WorkspaceShellActions.openDiffTab` | diff 标签是通用原语：`plugins/task-file-changes/ThreadFileChangesTraceView.tsx` 已用 `openDiffTab` 展示任务文件变更；git 仅是来源之一。搬走会破坏 task-file-changes 插件 |
| `task/FileChangesCollector.java` + `task/FileChangeAdvisor.java` + `task/FileChangeAdvisorProvider`（`BuiltInAdvisorProviders` 注册项保留） | 任务文件变更跟踪，与 git 无关（AI 工具写文件即记录） |
| `types.WorkspaceFileTab` 等非 git 类型 | — |
| `SlashTokenHandler` 对 `git.auto_sync` kind 的分发路径 | 通用 token 分发器；具体 `SlashTokenResolver`（GitAutoSyncSlashResolver）搬走，`SlashTokenHandler` 仍按 kind 查注册表（注册项缺失即静默保留原串，符合既有降级语义） |
| ~~`proto/RpcMethods.java` 的 `GIT_*` 常量~~ | ~~保留~~ **已修改：迁入插件**（见 §3.5） |

### 2.3 边界判据

- **diff 标签归核心**：跨 git 与非 git（任务变更）复用 → 不搬。
- **git-history 标签归插件**：仅 git 使用 → 搬，改用 `WorkspacePluginTab`（`pluginTabType='git-history'`）经 `openPluginTab` 打开，移除核心 `WorkspaceGitHistoryTab` 类型与 `openGitHistoryTab` 壳层 API。
- **侧边栏「源代码管理」面板归插件**：仅 git → 经 `ui.sidebar_items` 注册。
- **活动栏角标归插件**：经增强的 sidebar item `Badge` 组件自管（见 §5）。

## 3. Worker 侧改动

### 3.1 新建 Maven 模块 `every-agent-plugins/git`

```
every-agent-plugins/git/
├── pom.xml                       # parent=every-agent-plugins, 依赖 every-agent-worker
└── src/main/java/dev/everyagent/plugin/git/
    ├── GitRpcMethods.java        # NEW: git.* RPC 方法名常量（从 worker proto/RpcMethods.java 迁出）
    ├── GitService.java           # 13 个 git.* RPC（构造器注入 RpcDispatcher 等）
    ├── NativeGit.java
    ├── GitCredentialStore.java
    ├── GitAutoSyncToken.java
    ├── GitAutoSyncSlashProvider.java
    ├── GitAutoSyncSlashResolver.java
    ├── GitAutoSyncAdvisor.java
    └── GitAutoSyncAdvisorProvider.java
```

- `pom.xml` 与 `auth-review/pom.xml` 同形：`<parent>` 指向 `every-agent-plugins`，依赖 `every-agent-worker`。
- `every-agent-plugins/pom.xml` 的 `<modules>` 增 `<module>git</module>`。
- 包名 `dev.everyagent.plugin.git` 已在 `WorkerApplication` 的 `scanBasePackages = {"dev.everyagent.worker", "dev.everyagent.plugin"}` 覆盖范围内 → `@Component` 类自动被扫描，无需改启动配置（与 `auth-review` 一致）。

### 3.2 注册方式（沿用 auth-review 模式，无需引入新 SPI）

git 插件的 `@Component` 类在构造器中直接注入核心注册表并登记：

| 类 | 注入 | 构造器内注册 |
|---|---|---|
| `GitService` | `RpcDispatcher`、`WorkspaceManager`、`GitCredentialStore`、`NativeGit` | `dispatcher.register(GitRpcMethods.GIT_STATUS, this::status)` … 13 项（引用插件自有常量） |
| `GitAutoSyncSlashProvider` | `SlashCommandRegistry` | `registry.registerProvider("git-auto-sync", ...)` |
| `GitAutoSyncSlashResolver` | `SlashTokenHandler` | `handler.registerResolver(GitAutoSyncToken.KIND, ...)` |
| `GitAutoSyncAdvisorProvider` | （无；由 §3.3 注册到 `AdvisorProviderRegistry`） | — |

> 说明：auth-review 的 `AiReviewSlashProvider` 即此模式（构造器注入 `SlashCommandRegistry` 并 `registerProvider`）。不走 `WorkerPluginContext.registerRpcMethod`（那是 `~/.everyagent/plugins/` 外部 jar 插件的入口；内置 Maven 模块用 Spring DI 直连注册表更自然、与既有内置插件一致）。

### 3.3 核心瘦身（`every-agent-worker` 内删除/修改）

| 文件 | 动作 |
|---|---|
| `modules/GitService.java` | **删除**（迁入插件） |
| `git/NativeGit.java` | **删除**（迁入插件） |
| `modules/GitCredentialStore.java` | **删除**（迁入插件） |
| `task/GitAutoSyncAdvisor.java` | **删除**（迁入插件） |
| `plugin/adapters/GitAutoSyncAdvisorProvider.java` | **删除**（迁入插件） |
| `git/GitAutoSyncToken.java`、`GitAutoSyncSlashProvider.java`、`GitAutoSyncSlashResolver.java` | **删除**（迁入插件） |
| `plugin/adapters/BuiltInAdvisorProviders.java` | 删除 `GitService` 字段 + 构造器参数 + `registry.register(new GitAutoSyncAdvisorProvider(gitService))` 行 + 相关 import；其余 12 个 Advisor 不动 |
| `proto/RpcMethods.java` | **删除全部 14 个 `GIT_*` 常量**（`GIT_STATUS` … `GIT_CREDENTIAL_SAVE`）——核心不再知道 git RPC 方法名 |
| `task/TaskEntry.java` | 若有 `GitAutoSyncAdvisor` import 仅用于注释 → 清理 import；`fileChanges` 字段保留（非 git） |

### 3.4 依赖审计（确认无残留）

已验证核心对 git 类的引用仅两处，均在上文清理范围内：
- `task/GitAutoSyncAdvisor.java` import `GitService`（迁入插件，自洽）
- `plugin/adapters/BuiltInAdvisorProviders.java` + `GitAutoSyncAdvisorProvider.java`（已删/迁）

`RpcMethods.GIT_*` 常量仅被 `GitService.java` 引用（已 grep 确认：13 处 `dispatcher.register(RpcMethods.GIT_*, ...)` 全在 `GitService` 构造器内）——迁入插件后核心 `RpcMethods.java` 零残留。

`FsSearchService`/`WorkspaceManager`/沙箱类对 "git" 的命中均为注释或 `.gitignore` 通用支持，不依赖 `GitService`。`NativeGit` 经 `OsSandbox.spawnNative` 执行——插件模块依赖 worker 即可调用。

### 3.5 RPC 协议常量迁入插件

核心 `RpcDispatcher.register(String method, Method handler)` 接受纯字符串方法名——无需改核心 RPC 基础设施。

插件模块新建 `GitRpcMethods.java`：

```java
package dev.everyagent.plugin.git;

/** git 插件 RPC 方法名常量（从 worker proto/RpcMethods.java 迁出）。 */
public final class GitRpcMethods {
    public static final String GIT_STATUS         = "git.status";
    public static final String GIT_LOG           = "git.log";
    public static final String GIT_DIFF           = "git.diff";
    public static final String GIT_SHOW          = "git.show";
    public static final String GIT_COMMIT        = "git.commit";
    public static final String GIT_PULL           = "git.pull";
    public static final String GIT_PUSH          = "git.push";
    public static final String GIT_DISCARD       = "git.discard";
    public static final String GIT_CLONE         = "git.clone";
    public static final String GIT_INIT          = "git.init";
    public static final String GIT_REMOTE_ADD    = "git.remote.add";
    public static final String GIT_REMOTE_LIST   = "git.remote.list";
    public static final String GIT_CREDENTIAL_SAVE = "git.credential.save";
    private GitRpcMethods() {}
}
```

`GitService` 构造器内改用 `GitRpcMethods.GIT_STATUS` 等自有常量。核心 `RpcMethods.java` 删掉对应 14 行，不再知道 `git.*` 方法名的存在。

> 前端 `gitGateway.ts` 本就用字符串字面量 `'git.status'` 等调 RPC（不引用 Java 常量），无需改动。

## 4. Web 侧改动

### 4.1 新建 Web 插件 `every-agent-plugins/git/web/`

```
every-agent-plugins/git/web/
├── index.ts                 # PluginModule: 注册 sidebar item + git-history tab 类型
├── gitGateway.ts            # 从 platform/git/ 迁入
├── GitSidebarPanel.tsx      # 从 components/git/ 迁入
├── GitHistoryPanel.tsx      # 从 components/files/ 迁入
└── GitHistoryTabType.tsx    # 从 components/files/ 迁入；构造 UiWorkspaceTabTypeDefinition 注册到 ui.workspace_tab_types
```

`builtInPlugins.ts` 的 `import.meta.glob('@plugins/*/web/index.ts')` 自动发现 → 调用 `activate(ctx)` → 注册扩展点。零改启动代码。

### 4.2 插件入口（`web/index.ts`）

```ts
activate(ctx) {
  ctx.ui.registerSidebarItem({
    id: 'git',
    title: '源代码管理',
    icon: <GitIcon />,
    Panel: GitSidebarPanel,
    Badge: GitChangeBadge,        // 新增扩展点字段（见 §5.1）
  })
  ctx.ui.registerWorkspaceTabType(gitHistoryTabDef)
}
```

### 4.3 核心消费扩展点（关键增强）

| 核心 | 现状 | 改造 |
|---|---|---|
| `plugin/workspaceTabTypes.ts` | 「插件标签收集随插件底座裁剪,只保留内置定义」 | **恢复**合并：`cachedDefinitions` ∪ `pluginDispatcher.listRegisteredWorkspaceTabTypes()`（新增同步获取器，同 PDF 编辑器合并模式）；`getWorkspaceTabTypeDefinition` 同步查合并表 |
| `components/app/Layout.tsx` | 硬编码 `buildSidebarActivityItems(...)` 含 `{ id:'git', ... }`；4 个 `SidebarPanelHost` 含 `panelId="git"`；`SIDEBAR_PANEL_ACTIVITY_IDS`/`validPanelIds` 含 `'git'`；`gitChangeCount` 角标 effect（**当前实际未传给 SidebarActivityBar，为死代码**） | 活动栏项由 `coreActivityItems` ∪ `pluginDispatcher.listRegisteredSidebarItems()` 合并；`SidebarPanelHost` 槽位由「内置面板 + 插件 sidebar items 的 `Panel`」动态渲染（见 §5.2）；删除 `gitChangeCount` 死代码 effect 与 `'git'` 硬编码项；`SIDEBAR_PANEL_ACTIVITY_IDS` 改为「内置面板 id 集 ∪ 插件 sidebar item id 集」动态构造 |

### 4.4 `GitSidebarPanel` 内部调整

- 仍 `useWorkspaceShell()` 取 `openDiffTab`（diff 为核心共享 API，保留）。
- 原先「打开 Git 历史」改用核心 `openPluginTab('git-history', { workspaceRoot, path, name }, title)`（见 §4.5），不再依赖 `openGitHistoryTab`（删除）。
- 文件树「显示 Git 历史」右键菜单项不再硬编码在核心，改经新增扩展点 `ui.file_explorer_actions` 由 git 插件注册（见 §5.3）。无 git 插件时菜单项自动消失，核心零感知。

### 4.5 `git-history` 标签类型迁移

- 删除 `types/index.ts` 的 `WorkspaceGitHistoryTab`、`workspaceShellState.ts` 的 `createWorkspaceGitHistoryTab`、`WorkspaceShellActions.openGitHistoryTab`、`Layout` 内 `openGitHistoryTab` 与 `fileTabType` 列表里的 `gitHistoryTabType`。
- 改用现有 `WorkspacePluginTab`：`pluginTabType='git-history'`，`data: { workspaceRoot, path, name }`，`title`。`GitHistoryTabType.tsx` 导出 `UiWorkspaceTabTypeDefinition`（`tabTypeKey='git-history'`，从 `tab.data` 读字段渲染 `<GitHistoryPanel>`）。
- 无 localStorage 持久化 workspace 标签（已验证），无迁移问题。
- `GitHistoryPanel` 内部从 `tab.data` 取 `workspaceRoot/path/name`；仍用 `openDiffTab`（共享）。

### 4.6 核心瘦身清单

| 文件 | 动作 |
|---|---|
| `components/git/GitSidebarPanel.tsx` | 删除（迁插件） |
| `platform/git/gitGateway.ts` | 删除（迁插件） |
| `components/files/GitHistoryPanel.tsx`、`GitHistoryTabType.tsx` | 删除（迁插件） |
| `plugin/workspaceTabTypes.ts` | 恢复合并插件注册项；从 `cachedDefinitions` 删 `gitHistoryTabType` 项 |
| `components/app/Layout.tsx` | 删 git 硬编码项/面板槽位/badge 死代码；活动栏与面板槽位改动态合并插件 sidebar items |
| `components/app/WorkspaceShellContext.tsx` | 删 `openGitHistoryTab` |
| `components/app/workspaceShellState.ts` | 删 `createWorkspaceGitHistoryTab` + 相关类型 import |
| `types/index.ts` | 删 `WorkspaceGitHistoryTab` |
| `components/files/OpenFilesSidebarPanel.tsx` | 删除「显示 Git 历史」硬编码菜单项（第 794–799 行）+ `handleRequestGitHistory` 回调（第 705–714 行）+ 相关 import；右键菜单由内置项 + `ui.file_explorer_actions` 扩展点收集的插件项合并（见 §5.3） |
| `composerToken/composerOpaqueToken.ts` | 删除 `git.auto_sync` 相关注释（仅注释，无代码） |

## 5. 插件 API 增强（`every-agent-plugin-api/js/index.ts`）

### 5.1 侧边栏角标：`Badge` 组件

`UiSidebarItemDefinition` 新增可选字段：

```ts
/** 可选：活动栏角标渲染组件（插件自管订阅与刷新）。缺省不渲染角标。 */
Badge?: ComponentType
```

- 核心 `SidebarActivityBar` 在 item 有 `Badge` 时渲染 `<item.Badge />`（绝对定位右上角，与现有静态 `badgeCount` 渐进并存——静态字段保留兼容）。
- git 插件提供 `GitChangeBadge` 组件：内部 `useEffect` 轮询 `gitGateway.status(root)` 求和、订阅 `WORKSPACE_REGISTRY_CHANGED`，渲染计数或 null。恢复原 `gitChangeCount` 的设计意图（当前死代码从未真正显示）。
- 零核心业务分支：核心只问「有没有 Badge 组件」，不感知 git。

### 5.2 工作区标签类型合并

`PluginDispatcher` 新增同步获取器（详见 §5.4 汇总），供 `workspaceTabTypes.ts` / `Layout.tsx` 在同步渲染路径合并插件贡献。

### 5.3 文件树右键菜单扩展点：`ui.file_explorer_actions`

**新增动机**：当前 `OpenFilesSidebarPanel.tsx` 在右键菜单里硬编码了 `{ key: 'git-history', label: '显示 Git 历史', onSelect: handleRequestGitHistory }`——核心知道 git 存在。需要让插件可以贡献文件树右键菜单项，核心零感知。

**新增类型**（`plugin-api/js/index.ts`）：

```ts
/** 文件树右键菜单上下文（与核心 WorkspaceExplorerContextTarget 对齐）。 */
export interface FileExplorerActionContext {
  /** 所属工作区根(worker 机器绝对路径)。 */
  workspaceRoot: string
  /** 节点路径(工作区相对)。 */
  path: string
  /** 节点名称。 */
  name: string
  /** 节点类型。 */
  type: 'file' | 'directory'
}

/**
 * 文件树右键菜单动作（由 `ui.file_explorer_actions` 扩展点产出）。
 * 核心在构建右键菜单时收集所有注册项，按 isVisible 过滤后追加到内置菜单项尾部。
 */
export interface FileExplorerAction {
  /** 全局唯一动作 id。 */
  id: string
  /** 菜单展示文案。 */
  label: string
  /** 可选图标（React 节点）。 */
  icon?: ReactNode
  /** 当前上下文下是否显示；缺省恒显示。 */
  isVisible?: (ctx: FileExplorerActionContext) => boolean
  /** 点击回调。 */
  invoke?: (ctx: FileExplorerActionContext) => void
}
```

`UiRegistry` 新增：
```ts
registerFileExplorerAction(action: FileExplorerAction): Disposable
```

**核心消费**（`OpenFilesSidebarPanel.tsx`）：

```ts
// 构建右键菜单时：
const pluginActions = pluginDispatcher.listRegisteredFileExplorerActions()
const pluginItems: MoreActionItem[] = pluginActions
  .filter(a => !a.isVisible || a.isVisible(ctx))
  .map(a => ({
    key: a.id,
    label: a.label,
    icon: a.icon,
    onSelect: () => a.invoke?.(ctx),
  }))
items.push(...pluginItems)
```

内置菜单项（打开/上传/属性/重命名/移动/搜索/下载/终端/在系统文件管理器中显示）保持不变；插件项追加在尾部。无插件注册时 `pluginActions` 为空数组——零影响。

**git 插件注册**（`web/index.ts`）：

```ts
ctx.ui.registerFileExplorerAction({
  id: 'git-show-history',
  label: '显示 Git 历史',
  icon: <GitHistoryIcon />,
  invoke: (ctx) => {
    // 调 openPluginTab 打开 git-history 标签
  },
})
```

**`PluginDispatcher` 新增**：
```ts
listRegisteredFileExplorerActions(): FileExplorerAction[]
registerFileExplorerAction(action: FileExplorerAction): Disposable
```

### 5.4 同步获取器汇总

`PluginDispatcher` 新增以下同步获取器（与 PDF 编辑器合并同模式），供核心在同步渲染路径合并插件贡献：

```ts
listRegisteredWorkspaceTabTypes(): UiWorkspaceTabTypeDefinition[]
listRegisteredSidebarItems(): UiSidebarItemDefinition[]
listRegisteredFileExplorerActions(): FileExplorerAction[]
```

`workspaceTabTypes.ts` / `Layout.tsx` / `OpenFilesSidebarPanel.tsx` 用同步获取器合并，避免异步 await 在同步渲染路径引入复杂度。

## 6. ARCHITECTURE.md 文档更新（先改文档）

> 红线：实现与文档冲突先改文档。本节为本次落地的文档修订点。

| 位置 | 修订 |
|---|---|
| §5.4 RPC 契约表（line 236/238） | `git.*` 行注明「由 git 插件提供（every-agent-plugins/git）；RPC 方法名常量由插件 `GitRpcMethods` 定义，核心 `RpcMethods` 不含 git 条目」 |
| §6.4 worker 模块清单（line 311） | 从核心清单移除 `GitService`/`NativeGit`/`GitCredentialStore`，移至「内置插件模块」小节新增 `git` 条目 |
| §7.12 原生 git 执行与凭证（line 507–529） | 加一句「上述模块以内置插件 `every-agent-plugins/git` 形态提供；核心 worker 不内嵌 git 能力，`RpcMethods` 不含 `GIT_*` 常量，插件缺失时 `git.*` RPC 不注册」 |
| 新增 §13.x（或 §7.x） | 简述插件化侧边栏/工作区标签/文件树右键菜单扩展点的核心消费机制（合并同步获取器） |
| §6.x/§10 前端模块 | 「源代码管理面板/Git 历史/文件树 Git 历史菜单」标注为 git 插件提供；diff 标签保留为核心共享原语 |

## 7. 实施顺序

1. **文档先行**：改 ARCHITECTURE.md（§6）。
2. **插件 API 增强**：`plugin-api` 加 `Badge` 字段（`UiSidebarItemDefinition`）；`FileExplorerAction` / `FileExplorerActionContext` 类型 + `registerFileExplorerAction` 方法（`UiRegistry`）；`PluginDispatcher` 加 4 个同步获取器 + `fileExplorerActions` 注册表。
3. **核心消费扩展点**：`workspaceTabTypes.ts` 恢复合并 + `Layout.tsx` 动态活动栏/面板槽位 + `SidebarActivityBar` 支持 `Badge` + `OpenFilesSidebarPanel.tsx` 右键菜单合并 `ui.file_explorer_actions`。
4. **worker 迁移**：新建 `every-agent-plugins/git` Maven 模块，搬入 Java 类；改 `BuiltInAdvisorProviders`；`mvn -pl every-agent-plugins/git -am compile` 验证。
5. **web 迁移**：新建 `every-agent-plugins/git/web/`，搬入 tsx/ts；改 `Layout`/`WorkspaceShellContext`/`workspaceShellState`/`types`/`OpenFilesSidebarPanel`；`tsc --noEmit` + `vite build` 验证。
6. **端到端**：启动 worker + web，验证 SCM 面板/Git 历史/AutoSync `/自动同步`/clone/pull/push/credential 全链路；卸载 git 插件（移除模块）后核心零报错、相关入口隐藏。

## 8. 验收清单

- [ ] `git status`/`log`/`diff`/`commit`/`pull`/`push`/`discard`/`init`/`clone`/`remote`/`credential.save` 13 个 RPC 全部经插件注册可用（方法名常量由插件 `GitRpcMethods` 定义）。
- [ ] 核心 `RpcMethods.java` 不含任何 `GIT_*` 常量；`grep -r GIT_` 在 worker 核心（不含插件模块）零命中。
- [ ] 源代码管理面板（GitSidebarPanel）经 `ui.sidebar_items` 出现在活动栏；点击切换面板。
- [ ] 活动栏角标显示变更数（`Badge` 组件生效，恢复原设计意图）。
- [ ] Git 历史标签经 `ui.workspace_tab_types` 注册。
- [ ] 文件树右键「显示 Git 历史」经 `ui.file_explorer_actions` 注册；无 git 插件时菜单项消失、核心零感知。
- [ ] `/自动同步` 斜杠命令 + 任务完成后自动同步 advisor 链正常。
- [ ] diff 标签（`openDiffTab`）仍可被 `task-file-changes` 插件与 git 插件共用。
- [ ] 移除 git 插件模块后：worker 启动不报错、`git.*` RPC 不存在（调用返回未知方法）、活动栏无「源代码管理」、文件树无「Git 历史」菜单、`/自动同步` 不在斜杠列表。
- [ ] `tsc --noEmit` 与 `mvn compile` 通过；ARCHITECTURE.md 已更新。

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| `Layout` 动态面板槽位改动面大，影响 tasks/files/search 三个内置面板 | 内置面板仍走静态 `SidebarPanelHost`；仅插件 sidebar items 走动态槽位。改动收敛在「合并列表 + 额外槽位」 |
| `git-history` 改 `WorkspacePluginTab` 后 `getTabDefinition` 查不到时返回 undefined → 渲染 null | `Layout.renderWorkspaceTabContent` 已有 `if (!def) return null` 兜底；文件树菜单按定义存在性条件渲染，不会创建空标签 |
| `GitSidebarPanel` 1631 行体量大，搬迁易遗漏 import 路径 | 搬迁后 `tsc --noEmit` 全量类型检查兜底；逐项修复 `@/` 导入 |
| 内置 Maven 插件与外部 jar 插件注册路径不同（Spring DI vs `WorkerPluginContext`） | 本方案沿用 auth-review 既有 Spring DI 模式，不引入新机制；文档注明「内置插件模块用 Spring DI，外部 jar 插件走 WorkerPluginContext」 |
| `gitChangeCount` 当前为死代码（未显示） | 本方案顺手修复：改为 `Badge` 组件，恢复设计意图；若担心范围扩大，可降级为「仅搬走、不修角标」，留待后续 |

## 10. 非目标（明确不做）

- 不把 diff 标签 / `FileChangesCollector` / `FileChangeAdvisor` 搬走（共享原语）。
- 不把 git 改为「外部 jar 插件」（`~/.everyagent/plugins/`）；保持内置 Maven 模块形态，与 auth-review 一致。
- 不重构 `NativeGit` 的 argv 执行机制（§7.12 不变）。
- ~~不改 `proto/RpcMethods.GIT_*` 线协议常量。~~ **已修改：常量迁入插件**（见 §3.5）。
- 不新增 `WorkerPluginContext` 能力（内置模块用 Spring DI 即可）。
