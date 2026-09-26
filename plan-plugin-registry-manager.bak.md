# 插件注册表接口化 + 插件管理面板(VSCode 风格)

## 目标

1. 把 web 前端插件注册表(PluginDispatcher 里每扩展点一个硬编码数组)抽象成**接口**;默认实现 = 普通列表,注册进来的都有效(现状行为)。
2. 新增内置插件 `plugin-manager`:提供**新的管控注册表实现** + **VSCode 扩展视图风格的管理面板**(左侧活动栏入口),支持查看/启用/禁用插件。
3. 启用/禁用采用 **Reload Required** 风格(切换后提示重新加载,`location.reload()` 生效),实现简单可靠。

## 关键概念澄清

- **插件是一个整体**:一个插件 = `every-agent-plugins/<id>/` 一个目录,内含可选的 worker 入口(plugin.json + Java 类)和可选的 web 入口(`web/index.ts`)。管理面板按"插件"为单位启用/禁用,**不存在"只禁用 web 端、保留 worker 端"**这种半禁用状态——一次 enable/disable 对整个插件生效。
- **禁用状态只有一个真相源**:worker 的插件注册表(PluginRegistry,持久化 `~/.everyagent/plugins/.disabled-plugins`)。web 前端每次启动从 worker 拉取(`plugin.list` 响应的 `disabledIds`),**worker 不可达时启用/禁用操作直接报错**,不做 localStorage 降级(避免双状态源不一致)。
- **禁用在注册时拦截**:worker 侧由 PluginRegistry 在注册入口统一过滤,禁用插件的贡献不进入任何 SPI 注册表(ToolProviderRegistry 等使用方不做二次过滤);web 侧由两阶段加载在 activate 前拦截。两侧统一为"重启/重载后生效"。
- **注册表抽象在哪**:本次抽象的是 **web 前端的扩展点注册表**(PluginDispatcher)。worker 侧不做接口抽象,只做 PluginStateStore → PluginRegistry 的"状态 + 注册时过滤 + 持久化"重构。

## 现状要点(勘探结论)

- `PluginDispatcher.ts`:13 个扩展点各一个模块级数组 + register/list 方法,无接口、无 pluginId 归属追踪(除少数定义自带 pluginId 字段)。
- **`ui.sidebar_items` 扩展点目前核心未消费**:`Layout.tsx` 的活动栏硬编码 tasks/files/search/settings,git 插件注册的「源代码管理」面板实际不显示。本方案必须补上这一消费,否则管理面板自己也无处显示。
- `builtInPlugins.ts`:`import.meta.glob('@plugins/*/web/index.ts')` 发现内置插件,逐个 activate;无清单(manifest)概念,插件无 id/name/version 自描述。
- worker 侧:`PluginRpcMethods` 已有 plugin.list/install/uninstall/enable/disable;`PluginStateStore` 持久化禁用集合。

## 设计

### 1. 注册表接口(web 核心,`src/plugin/`)

```ts
/** 单扩展点注册表接口。 */
export interface ExtensionRegistry<T> {
  register(pluginId: string, item: T): Disposable
  getAll(): T[]
  onDidChange?(listener: () => void): Disposable   // 预留,Reload 风格下可暂不实现
}

/** 注册表工厂:核心在构建 PluginDispatcher 前可替换一次。 */
export interface ExtensionRegistryFactory {
  create<T>(extensionPoint: string): ExtensionRegistry<T>
}
```

- **默认实现 `ListExtensionRegistry`**:内部普通数组,注册即有效(= 现状行为)。
- `PluginDispatcher` 重构:持有 `Map<extensionPoint, ExtensionRegistry>`,所有 `registerXxx`/`listRegisteredXxx` 对外签名不变,内部委托给对应注册表;register 时从 PluginContext 带入 pluginId。
- 核心暴露 `setExtensionRegistryFactory(factory)`:仅允许在插件激活前调用一次(之后调用直接抛错),供 plugin-manager 安装管控注册表。

### 2. 插件清单 manifest + 两阶段加载

- `PluginModule`(plugin-api)新增必填 `manifest: { id, name, version, description?, author? }`;各内置插件 `web/index.ts` 补齐。
- `builtInPlugins.ts` 重构为两阶段:
  - **Phase 0**:glob 仅 import 全部模块,收集 manifest 建 **PluginCatalog**(web 侧插件目录:谁装了、激活与否)。
  - **Phase 1**:扫描导出 `registryFactory` 的模块(plugin-manager),先激活它 → 安装管控注册表。plugin-manager 自身**永不可禁用**。
  - **Phase 2**:按禁用清单过滤后 activate 其余插件(禁用插件连 activate 都不执行)。

### 3. 核心补消费 `ui.sidebar_items`

- `Layout.tsx`:`buildSidebarActivityItems` 合并 `pluginDispatcher.listRegisteredSidebarItems()`(内置项在前,插件项在后);面板槽位按合并后的清单动态渲染 `SidebarPanelHost`,去掉硬编码 `validPanelIds` 白名单。
- 附带效果:git 插件的「源代码管理」面板恢复可见(现状是注册了但没渲染的死注册)。

### 4. worker 侧重构:注册时统一过滤(PluginStateStore → 插件注册表)

**核心原则:禁用在"注册"这一道闸拦截,使用方注册表不再各自过滤。**

- `PluginStateStore` 重构为 **worker 侧插件注册表/管理器**(`PluginRegistry`),职责:
  - 持有启用/禁用状态并**真正持久化**(`~/.everyagent/plugins/.disabled-plugins`,每行一个 id;`disable/enable` 落盘,启动时 `load`)——补上现有代码注释承诺但未实现的持久化。
  - **register 时过滤**:对外暴露统一注册入口;插件(内置 Spring 装配 + 外部 PluginLoader 加载)注册工具/Advisor/沙箱等贡献时,禁用的插件**根本不进入** ToolProviderRegistry 等使用方注册表。
- **删除各注册表的自过滤逻辑**:ToolProviderRegistry / AdvisorProviderRegistry / SandboxProviderRegistry / SearchProviderRegistry / SkillContributorRegistry / TaskLifecycleRegistry 中所有 `!isDisabled(...)` 过滤全部移除,回归"普通列表,注册进来的都有效"。
- **生效时机变化**:从"查询时过滤(运行时即时生效)"变为"注册时过滤(重启生效)"——与整体 Reload Required 风格一致。`plugin.enable/disable` RPC 落盘后返回"重启 worker 后生效"。
- `plugin.list` 响应增加 `disabledIds`(供 web 取 web-only 插件的禁用状态);`active` 字段仍由 PluginRegistry 状态计算。

### 5. 内置插件 `plugin-manager`

目录 `every-agent-plugins/plugin-manager/`,纯 web 插件:

- **管控注册表 `ManagedExtensionRegistry`**:装饰 List 实现,register 时记录 `pluginId → items` 映射,`getAll()` 过滤已禁用插件的贡献——双保险:即使某禁用插件因时序问题仍被激活,其贡献也不生效。
- **管理面板**(`registerSidebarItem({ id: 'plugins', title: '扩展', ... })`):
  - 列表 = PluginCatalog(web 发现) ∪ worker `plugin.list`(外部插件/版本/描述),按「内置 / 外部」分组,行内显示名称、版本、描述、启用状态。
  - 操作:启用/禁用开关 → `plugin.enable/disable` RPC;**worker 不可达时 RPC 报错,面板提示"无法连接 worker"且不切换状态**(不降级到 localStorage)。
  - 切换成功后行内出现「需要重新加载」提示 + 顶部全局「重新加载」按钮。
  - 搜索过滤框(对标 VSCode 扩展视图顶部搜索)。

### 6. 验证

- `pnpm build`(web)+ `mvn -pl every-agent-worker compile` 通过。
- 手测:禁用 git → 重载 → 活动栏「源代码管理」消失、git-history 标签类型缺失不报错;启用 → 重载 → 恢复。

## 步骤

- [ ] 步骤 1:web 注册表接口抽象(ExtensionRegistry/ListExtensionRegistry/Factory,PluginDispatcher 重构,行为不变)
    - 状态:待执行
    - agent:-
    - 依赖:无
    - 验收标准:web 构建通过;所有既有 registerXxx/listRegisteredXxx 调用方零改动;现有插件功能无损
- [ ] 步骤 2:manifest 契约 + PluginCatalog + builtInPlugins 两阶段加载;各内置插件补 manifest
    - 状态:待执行
    - agent:-
    - 依赖:依赖步骤 1
    - 验收标准:每个内置插件有 manifest;加载顺序 = registryFactory 插件优先;构建通过
- [ ] 步骤 3:核心消费 ui.sidebar_items(活动栏合并 + 面板槽动态化)
    - 状态:待执行
    - agent:-
    - 依赖:依赖步骤 1
    - 验收标准:git 插件的「源代码管理」入口出现在活动栏且面板可用;tasks/files/search/settings 行为不变
- [ ] 步骤 4:worker 侧重构 PluginStateStore → PluginRegistry(注册时统一过滤 + 补持久化;移除 6 个注册表的 isDisabled 自过滤;plugin.list 增加 disabledIds)
    - 状态:待执行
    - agent:-
    - 依赖:无(worker 侧独立)
    - 验收标准:禁用插件的贡献启动时不进入任何 SPI 注册表;各注册表内无 isDisabled 过滤残留;禁用状态重启后仍在;plugin.list 含 disabledIds;worker 编译与相关测试通过
- [ ] 步骤 5:plugin-manager 插件(ManagedExtensionRegistry + 管理面板 UI + 启停/重载流程,worker 不可达时报错不降级)
    - 状态:待执行
    - agent:-
    - 依赖:依赖步骤 2、3、4
    - 验收标准:活动栏出现「扩展」面板;列表完整;禁用/启用经 RPC 生效并提示重载;worker 不可达时启停操作报错且状态不变;plugin-manager 自身不可禁用
- [ ] 步骤 6:端到端验证 + 提交
    - 状态:待执行
    - agent:-
    - 依赖:依赖步骤 5
    - 验收标准:构建全绿;禁用→重载→消失、启用→重载→恢复全流程手测通过;按 AGENTS.md 规范提交(feat: 前缀)

## 备注

- 步骤 2、3、4 彼此无依赖,可在步骤 1 完成后并行派发。
- Reload Required 是刻意取舍:不做运行中插拔(那需要注册表变更事件 + 全部消费方响应式改造,工作量大)。`onDidChange` 留在接口里作演进预留。
- 已知遗留:`docs/design-git-plugin.md` 提到的"核心未消费 ui.sidebar_items"由步骤 3 顺带解决。
- 回退方案:每步独立可回滚;步骤 5 出问题可整目录删除 plugin-manager(glob 无匹配,核心自动回退 ListExtensionRegistry + 无管理面板)。
