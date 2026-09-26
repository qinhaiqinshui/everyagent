# 插件注册表接口化 + 统一插件管理面板

## 目标

1. **统一插件模型**：一个插件 = 一份 `plugin.json` 清单，声明 worker 入口（`main`）和/或 web 入口（`webMain`）。插件开发者只写一份声明，像 VSCode 的 `package.json`。
2. **注册表接口化**：web 前端扩展点注册表抽成接口，默认实现 = 普通列表，注册进来的都有效。
3. **禁用 = 不加载**：禁用的插件 worker 不调用 `activate()`、web 不 `import()`，插件代码根本不执行，自然不注册任何东西。使用方注册表（ToolProviderRegistry 等）不需要自己过滤。
4. **插件不再依赖 worker 内部类**：`TaskInfo` 扩展为插件可用的完整任务接口，插件经 `ctx.services().task().get(taskId)` 访问任务数据，不再 import `TaskManager`。
5. **内置插件与外部插件统一**：走完全相同的加载流程，唯一区别是代码位置（classpath vs ~/.everyagent/plugins/）。
6. **VSCode 风格管理面板**：左侧活动栏「扩展」面板，查看/启用/禁用插件，Reload Required 生效。

## 核心概念

### 插件是一个整体

```json
{
  "id": "git",
  "name": "Git",
  "version": "0.1.0",
  "description": "源代码管理",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.git.GitPlugin",
  "webMain": "web/index.js",
  "contributes": {
    "config": {}
  }
}
```

- `main` 有 → worker 入口（Java 类全限定名），可选
- `webMain` 有 → web 入口（模块相对路径），可选
- 禁用 = 不实例化 main 类、不 import webMain 模块

### 禁用在加载时拦截

| | 现状（有问题） | 目标 |
|---|---|---|
| worker | 6 个 SPI 注册表各自 filter(!isDisabled) | 禁用插件不调 activate()，注册表不过滤 |
| web | 无禁用机制 | 禁用插件不 import()，注册表不过滤 |
| PluginStateStore | 纯内存、无持久化 | → PluginRegistry：完整目录 + 持久化 + 加载时拦截 |

### TaskInfo：插件看到的任务接口

`TaskInfo` 是 plugin-api 中的接口，TaskEntry（worker 内部实现）implements 它。插件只依赖 TaskInfo，不依赖 TaskEntry。

**安全：TaskEntry 去掉 apiKey 字段。** 现状 apiKey 只有两个消费者（TaskEntryCreateNode 存入、AgentFactory 读取），它不需要挂在 TaskEntry 上随链上下文流动。apiKey 属于模型调用配置，AgentFactory 直接从 ResolvedConfig 拿。这样即使外部插件强转 TaskLifecycleContextImpl 也拿不到密钥。

```java
// TaskInfo（plugin-api）
public interface TaskInfo {
    String taskId();
    String status();                        // "created"/"running"/"waiting-user"/"done"/"failed"/"cancelled"
    boolean terminal();                     // 语法糖：status 是否为终态
    Map<String, Object> metadata();         // 任务级持久化数据（落盘到 meta.json）
    Path taskDir();                         // 任务数据目录
}

// TaskService（plugin-api）
public interface TaskService {
    TaskInfo get(String taskId);            // 按 id 拿任务
    void publishUpdated(String taskId);     // 广播任务更新
}

// TaskLifecycleContext（plugin-api）链上下文新增
public interface TaskLifecycleContext {
    // ... 现有方法 ...
    TaskInfo taskInfo();                    // 任务信息（含 metadata，与 TaskInfo 同源）
    Map<String, Object> runParams();        // 本轮运行参数（task.run 传入，临时，不落盘）
}
```

- 链节点：`TaskLifecycleContext` 新增 `taskInfo()` 返回 `TaskInfo`，插件不用绕道
- 非链场景（slash 回调、RPC 处理器）：`ctx.services().task().get(taskId)` → 返回 `TaskInfo`
- **TaskEntry 不含 apiKey**，根除链上下文泄露密钥风险
- **taskFlags 消失**：旧的 `Map<String,Boolean> taskFlags` 统一为 `Map<String,Object> metadata`
- **数据来源单一**：链上下文 `ctx.taskInfo().metadata()` 和 `ctx.services().task().get(id).metadata()` 拿到的是 TaskEntry 上同一个 map
- **落盘安全**：`metadata` 落盘到 meta.json；task.run 传入的临时参数（editSeq、insert）走 `runParams`（`Map<String,Object>`），不落盘
- **RerunRestoreNode 改造**：从 meta.json 恢复时写入 `metadata`（不再是 `taskFlags`）

## 现状问题清单

| # | 问题 | 证据 |
|---|---|---|
| 1 | 5 个插件无 `plugin.json` | ai-review / git / pdf-viewer / subagent / update-file-view |
| 2 | task-queue / unattended 无 web 入口也无 plugin.json | pom.xml 存在但无声明 |
| 3 | manifest 字段名与 loader 不一致 | plugin.json 写 `"entry"`，PluginLoader 读 `"provides"."spi"."EveryAgentPlugin"` |
| 4 | 内置 Java 插件靠 Spring @Component 自动注册 | 7 个插件约 20 个 @Component 类自注册，禁用拦不住 |
| 5 | PluginStateStore 无持久化 | `loadDisabled()` 全仓库无人调用；`.disabled-plugins` 文件无读写代码 |
| 6 | 6 个注册表各自过滤 isDisabled | ToolProviderRegistry 3 处、AdvisorProviderRegistry 3 处等 |
| 7 | web 发现靠 Vite 构建期 glob | `import.meta.glob('@plugins/*/web/index.ts')`，外部插件无法参与 |
| 8 | `ui.sidebar_items` 扩展点核心未消费 | Layout.tsx 活动栏硬编码 tasks/files/search/settings |
| 9 | 插件直接依赖 worker 内部类 | TaskManager、TaskStore、EventSink、SlashCommandRegistry 等被插件直接 import |

## 设计

### 1. 统一清单 manifest

每个插件目录必须有 `plugin.json`（`src/main/resources/plugin.json`，Maven 打包到 classpath）：

```json
{
  "id": "git",
  "name": "Git",
  "version": "0.1.0",
  "description": "源代码管理",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.git.GitPlugin",
  "webMain": "web/index.js"
}
```

- 纯 web 插件：不设 `main`
- 纯 worker 插件：不设 `webMain`
- 双端插件：两者都有
- **字段名统一**：`main`、`webMain`，废弃旧的 `entry`/`provides.spi`

### 2. worker：PluginStateStore → PluginRegistry

**a) 建完整目录（catalog）**
- 扫描 classpath 下所有 `plugin.json`（内置插件）+ `~/.everyagent/plugins/` 目录（外部插件）
- 解析 manifest，建立 `Map<pluginId, PluginManifest>`
- worker 认识所有插件，包括纯 web 插件

**b) 加载时拦截（禁用 = 不加载）**
- 启动时读 `.disabled-plugins` 建禁用集合
- 遍历 catalog：enabled → 加载入口类 + 实例化 + `activate(ctx)`；disabled → 跳过
- 内置和外部走完全相同的流程，唯一区别是类加载方式

**c) WorkerPluginContext 扩展**

```java
public interface WorkerPluginContext {
    String pluginId();
    // SPI 注册方法（已有，不变）
    void registerToolProvider(ToolProvider provider);
    // ... 其他 registerXxx ...

    // 服务访问
    WorkerServices services();
    PluginConfig config();
}
```

`WorkerServices` 扩展：

```java
public interface WorkerServices {
    SandboxBackend sandbox();
    PermissionGate gate();
    WorkspaceManager workspaces();
    TokenEstimator tokenEstimator();
    TaskService task();                     // 新增：任务域服务
}

public interface TaskService {
    TaskInfo get(String taskId);            // 按 id 拿任务
    void publishUpdated(String taskId);     // 广播任务更新
}
```

worker 内部实现：TaskService 委托给 TaskManager/TaskStore，返回 `TaskEntry`（向上转型为 `TaskInfo`）。

**d) 持久化**
- `disable(id)` / `enable(id)` 改内存 + 写 `.disabled-plugins`
- 启动时 `load` 恢复
- `plugin.enable/disable` RPC 返回「重启后生效」

**e) plugin.list RPC 返回完整目录**
- 全部插件（含 web-only），带 id/name/version/description/author/source/active/hasMain/hasWebMain
- 附带 `disabledIds: string[]`

**f) plugin.webSource RPC（新增）**
- 外部插件 web 入口 JS 源码由 worker 读取返回
- 内置插件 web 入口走 Vite 构建期解析

### 3. 内置插件统一改造

**所有插件 @Component 全部去掉**，由 `EveryAgentPlugin.activate()` 统一实例化。插件代码不再被 Spring 扫描自动创建。

**改造方式**：

| 插件 | 当前自注册类 | 改造后 |
|---|---|---|
| git | GitPluginRegistrar + GitService 等 6 个 @Component | GitPlugin.activate() 里实例化全部，经 ctx 注册 |
| subagent | SubAgentRegistrar + SubAgentSkillContributor | SubAgentPlugin.activate() |
| task-input-queue | TaskInputQueueRegistrar + QueueRpcHandler | TaskInputQueuePlugin.activate() |
| task-edit-resend | TaskEditResendRegistrar | TaskEditResendPlugin.activate() |
| task-queue | TaskQueueRegistrar + TaskQueueRpcHandler | TaskQueuePlugin.activate() |
| ai-review | AiReviewSlashProvider + AiReviewAuthHandler 等 | AiReviewPlugin.activate() |
| unattended | UnattendedToolInterceptor + UnattendedAuthHandler 等 | UnattendedPlugin.activate() |

**activate() 里需要什么**：
- `ctx.registerToolProvider()` / `registerAdvisorProvider()` / ...（已有）
- `ctx.registerRpcMethod()` / `registerSlashProvider()`（已有）
- `ctx.services().task().get(taskId)` → TaskInfo（新增，替代直接依赖 TaskManager）
- 插件内部业务类（GitService、NativeGit 等）由 activate() 里 `new` 实例化，需要的 worker 服务经 `ctx.services()` 或 `ctx.getService()` 获取

**不再需要的依赖**：
- `TaskManager` → `ctx.services().task().get(taskId)` 返回 TaskInfo
- `TaskStore` → `TaskInfo.taskDir()` 返回任务目录路径
- `EventSink` → `TaskInfo.publishUpdated()` 替代
- `SlashCommandRegistry` → `ctx.registerSlashProvider()` 已有
- `RpcDispatcher` → `ctx.registerRpcMethod()` 已有

### 4. 使用方注册表回归普通列表

删除以下所有 `isDisabled` 过滤：
- `ToolProviderRegistry`：3 处
- `AdvisorProviderRegistry`：3 处
- `SandboxProviderRegistry`：3 处
- `SearchProviderRegistry`：3 处
- `SkillContributorRegistry`：1 处
- `TaskLifecycleRegistry`：1 处

注册表变为：注册进来的都有效，查询直接返回全量。

### 5. web 注册表接口抽象

```ts
interface ExtensionRegistry<T> {
  register(pluginId: string, item: T): Disposable
  getAll(): T[]
}
interface ExtensionRegistryFactory {
  create<T>(extensionPoint: string): ExtensionRegistry<T>
}
```

- 默认实现 `ListExtensionRegistry`：普通数组，注册即有效
- `PluginDispatcher` 重构：内部委托给注册表，对外 API 不变
- `setExtensionRegistryFactory(factory)`：仅允许插件激活前调用一次

### 6. web 加载重构

- 发现职责上移到 worker：`plugin.list` RPC 返回完整目录
- 删除 `builtInPlugins.ts` 的 glob 发现逻辑
- Vite glob 降级为内部插件 lazy import 映射
- 外部插件：`plugin.webSource` RPC → blob URL → `import()`
- `pluginBootstrap.ts` 合并进新 loader

### 7. 核心消费 ui.sidebar_items

- `Layout.tsx`：`buildSidebarActivityItems()` 合并 `pluginDispatcher.listRegisteredSidebarItems()`
- 面板槽位动态渲染，去掉硬编码 `validPanelIds` 白名单
- git 插件「源代码管理」面板恢复可见

### 8. 内置插件 plugin-manager

- **管控注册表 `ManagedExtensionRegistry`**：装饰 List，双保险过滤
- **管理面板**：`registerSidebarItem({ id: 'plugins', title: '扩展', ... })`
  - 列表 = `plugin.list` 完整目录，按「内置 / 外部」分组
  - 启用/禁用开关 → `plugin.enable/disable` RPC；worker 不可达报错
  - 切换后「需要重新加载」提示 + 全局「重新加载」按钮
  - 搜索过滤框
  - plugin-manager 自身永不可禁用

## 步骤

- [x] 步骤 1：统一清单（9 个插件各补 plugin.json；统一字段 main + webMain；废弃旧字段）
    - 状态：已完成
    - agent：sub_hkl2q
    - 依赖：无
    - 验收标准：每个插件目录有 plugin.json；字段格式统一

- [ ] 步骤 2：worker PluginStateStore → PluginRegistry（完整目录 + 加载时拦截 + 持久化 + plugin.list 全量 + plugin.webSource RPC + TaskService.get() + TaskInfo 扩展 + TaskEntry 去 apiKey + taskFlags→metadata 统一）
    - 状态：进行中
    - agent：sub_hkl2s
    - 依赖：依赖步骤 1
    - 验收标准：worker 认识全部 9 个插件；禁用插件不调 activate；.disabled-plugins 持久化生效；plugin.list 含 disabledIds；TaskInfo 含 status/terminal/metadata/taskDir；TaskService 含 get/publishUpdated；TaskLifecycleContext 新增 taskInfo()/runParams()；ctx.services().task().get() 可用；TaskEntry 无 apiKey 字段；AgentFactory 从 ResolvedConfig 拿 apiKey；taskFlags 字段删除；metadata 落盘、runParams 不落盘

- [x] 步骤 3：内置插件统一改造（去掉所有 @Component 自注册；activate() 含全注册逻辑；消除 TaskManager/TaskStore/EventSink 依赖）
    - 状态：已完成
    - agent：sub_hkl2t
    - 依赖：依赖步骤 2
    - 验收标准：7 个插件的 activate() 非空且含全注册；不再 import TaskManager/TaskStore/EventSink；worker 编译通过；各注册表无 isDisabled 过滤残留

- [x] 步骤 4：web 注册表接口抽象（ExtensionRegistry / ListExtensionRegistry / Factory，PluginDispatcher 重构，行为不变）
    - 状态：已完成
    - agent：sub_hkl2r
    - 依赖：无
    - 验收标准：web 构建通过；所有既有调用方零改动；现有插件功能无损

- [x] 步骤 5：web 加载重构（plugin.list 驱动发现 + Vite glob 降级为 lazy import 映射 + 外部插件 blob URL 加载）
    - 状态：已完成
    - agent：sub_hkl2v
    - 依赖：依赖步骤 2、4
    - 验收标准：web 从 worker 获取插件目录；enabled 插件被加载；disabled 插件不加载；内置插件 web 功能无损

- [ ] 步骤 6：核心消费 ui.sidebar_items（活动栏合并 + 面板槽动态化）
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 4
    - 验收标准：git 插件「源代码管理」入口出现且面板可用；内置项行为不变

- [x] 步骤 7：plugin-manager 插件（ManagedExtensionRegistry + 管理面板 UI + 启停/重载流程）
    - 状态：已完成
    - agent：sub_hkl2w
    - 依赖：依赖步骤 5、6
    - 验收标准：活动栏「扩展」面板可见；列表完整；禁用/启用经 RPC 生效并提示重载；worker 不可达报错；plugin-manager 自身不可禁用

- [x] 步骤 8：端到端验证 + 提交
    - 状态：已完成
    - agent：-
    - 依赖：依赖步骤 7
    - 验收标准：构建全绿；禁用→重载→消失、启用→重载→恢复全流程通过；按 AGENTS.md 规范提交（feat: 前缀）

## 备注

- 步骤 1、4 可并行；步骤 2 依赖 1；步骤 3 依赖 2；步骤 5 依赖 2+4；步骤 6 依赖 4；步骤 7 依赖 5+6
- 步骤 3 工作量最大：7 个插件约 20 个类去掉 @Component，activate() 里统一实例化
- TaskInfo 是插件看到的任务全貌，TaskEntry 是 worker 内部实现细节
- taskFlags 消失：统一为 metadata（Map<String,Object>），数据来源单一（TaskEntry 上一个 map），插件从链上下文 ctx.taskInfo().metadata() 或 ctx.services().task().get(id).metadata() 拿到同一个
- metadata 落盘到 meta.json；runParams（task.run 传入的临时参数）不落盘
- 队列插件的 QueueRpcHandler 不再依赖 TaskManager：热路径用 ctx.services().task().get() 看 status；冷路径用 TaskInfo.taskDir() 读写 queue.jsonl；广播用 TaskInfo.publishUpdated()
- 回退方案：每步独立可回滚；plugin-manager 出问题可整目录删除
