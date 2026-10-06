---
title: 前端 ctx API
nav_order: 10
parent: web
has_children: false
---

# 前端 ctx API

**一句话定位**：`ctx`（`PluginContext`）是前端插件 `activate(ctx)` 拿到的唯一入口对象——身份信息、RPC 通道、本地存储、命令表、UI 扩展点、领域事件、文件系统网关全部挂在它身上。本文逐成员给出签名、宿主实现位置与真实行为（含与直觉不符的坑）；类型声明全部来自纯类型包 `every-agent-plugin-api/js/index.ts`，宿主实现集中在 `every-agent-web/src/plugin/pluginLoader.ts`。

先记住一个总事实：**`ctx` 是宿主在加载每个插件时现场构造的普通对象字面量**（`every-agent-web/src/plugin/pluginLoader.ts:383-395`），没有任何魔法代理——每个成员的实现都是一小段闭包，下文逐个拆开。宿主源码原文即：

```ts
// every-agent-web/src/plugin/pluginLoader.ts（摘录，省略 3 行 ui 强转注释）
const ctx: PluginContext = {
  pluginId: plugin.id,
  extensionPath: webEntryJsPath(plugin.webMain),
  sdk: createPluginSdk(workerId, workspaceId, workspaceRoot),
  storage: createPluginStorage(plugin.id),
  commands: createCommandRegistry(),
  events: createPluginEvents(),
  fs: createPluginFs(),
  ui: pluginDispatcher as unknown as PluginContext['ui'],  // 单例强转接入
}
```

由此可直接读出几个后文反复引用的事实：`storage`/`commands` 是**每个插件各一份**的闭包工厂产物（§4/§5），`events`/`fs` 是无状态转发（§6/§7），`ui` 是全插件共享的单例 dispatcher（§8）。

## 1. 成员总览

| 成员 | 类型（声明处） | 宿主实现 | 一句话行为 |
|---|---|---|---|
| `ctx.pluginId` | `readonly string`（`index.ts:637`） | `pluginLoader.ts:384` | 等于 `plugin.list` 返回的 `id`（即 plugin.json 的 `id`） |
| `ctx.extensionPath` | `readonly string`（`index.ts:638`） | `pluginLoader.ts`（`webEntryJsPath`） | `webMain` 换算出的产物路径（约定 `"web/index.ts"` → `'web/index.js'`；known-issues #5 修复前恒为字面量），不是 URL、不是磁盘路径 |
| `ctx.sdk` | `PluginSdk`（`index.ts:130-146`） | `pluginLoader.ts:205-229` | RPC 通道 + 工作区注册表快照 + 当前 worker id |
| `ctx.storage` | `PluginStorage`（`index.ts:200-204`） | `pluginLoader.ts:142-165` | localStorage，键前缀 `plugin:<pluginId>:`，值 JSON 序列化 |
| `ctx.commands` | `CommandRegistry`（`index.ts:206-209`） | `pluginLoader.ts:167-189` | **本插件私有**的命令表（普通 `Map`），不跨插件、无宿主内置命令 |
| `ctx.ui` | `UiRegistry`（`index.ts:607-634`） | `every-agent-web/src/plugin/PluginDispatcher.ts:242-311` | 12 个 `register*` + 5 个动作方法，详见 [UI 扩展点](ui-extensions.md) |
| `ctx.events` | `PluginEvents`（`index.ts:162-167`） | `pluginLoader.ts:232-249` | 最薄委托宿主 `domainEventBus`（进程内事件总线） |
| `ctx.fs` | `PluginFs`（`index.ts:170-175`） | `pluginLoader.ts:251-265` | 委托宿主 `workspaceGateway` → worker `fs.list` / `fs.delete` RPC |

类型导入只有一种正确写法（纯类型包，`package.json` 只有 `types` 无 `main`/`exports`）：

```ts
// ✅ 唯一合法姿势：import type
import type { PluginContext, PluginModule } from '@everyagent/plugin-api'
```

原因与错误形态见 §11 坑 1。

## 2. `ctx.pluginId` 与 `ctx.extensionPath`

| 字段 | 赋值处 | 实际值 |
|---|---|---|
| `pluginId` | `pluginLoader.ts:384`（`pluginId: plugin.id`） | `plugin.list` RPC 返回的 `id`；它与目录名、`.eap` 顶层目录、localStorage 前缀共用同一字符串（三重身份详见 [plugin.json 字段参考](../plugin-manifest.md) §4） |
| `extensionPath` | `pluginLoader.ts`（`webEntryJsPath`） | `webMain` 换算出的产物路径（与 `plugin.webSource` 请求的 path 一致；约定即 `'web/index.js'`，known-issues #5 修复前硬编码） |

注意 `extensionPath` **不是**插件源码路径 `web/index.ts`（那是 `webMain` 的约定值），也不是 blob URL。别拿它做任何字符串拼接。

## 3. `ctx.sdk` —— RPC 与工作区

### 3.1 方法表

| 成员 | 签名（`index.ts:130-146`） | 宿主实现 | 行为细节 |
|---|---|---|---|
| `sdk.rpc` | `rpc(workerId: string, method: string, params?: unknown): Promise<unknown>` | `pluginLoader.ts:207-209` | `workerId` 传空串时回退 `resolvePluginWorkerId(加载期worker)`：加载期 worker 仍在线则用之，否则取第一个 `state === 'open'` 的连接（`pluginLoader.ts:196-203`）。为什么读取期才解析：用户切换 worker 后闭包里捕获的旧 id 会指向已关闭连接，之后每次 RPC 都打到不存在的 worker 上（`pluginLoader.ts:192` 注释） |
| `sdk.workspace.id` | `readonly string` | `pluginLoader.ts:211` | **恒为 `'defaultworkspace'`**：加载期初始化（`pluginLoader.ts:338`）后从未被 `sys.info` 结果覆盖——`sys.info` 只回填 `workspaceRoot`（`:341-343`）。别把它当工作区唯一键，用 `rootPath` 或 `workspace.list()` 的条目 |
| `sdk.workspace.rootPath` | `readonly string` | `pluginLoader.ts:212` | 加载那一刻 `sys.info` 返回的 `workspaceRoot`（worker **默认工作区根**，`every-agent-worker/.../modules/SysMethods.java:31`）；`sys.info` 失败则为空串，不阻塞加载（`pluginLoader.ts:344-345`） |
| `sdk.workspace.list()` | `() => Promise<PluginWorkspaceEntry[]>` | `pluginLoader.ts:213-221` | 返回前端 `workspaceRegistry.current` 的合并快照（全部已连 worker 的 `workspaces.list` 合并、按最后活动时间倒序；无连接时空数组）。条目字段 `root/workerId/id?/addedAt?/lastActivityAt?/missing?`（`every-agent-web/src/hub/workspaceRegistry.ts:18-30`、合并逻辑 `rebuild()` 同文件） |
| `sdk.workspace.workerIdOfRoot(root)` | `(root) => string \| undefined` | `pluginLoader.ts:222` | 按工作区根反查来源 worker；未注册返回 `undefined` |
| `sdk.workerId` | `readonly string`（getter） | `pluginLoader.ts:225-227` | **getter，读取期解析**：切 worker 后插件看到的「自身宿主 worker」随之更新（同 `resolvePluginWorkerId`） |

### 3.2 `sdk.rpc` 的错误形态

Promise reject 的 Error 有三种来源，写 catch 时要都想到：

| 来源 | 抛出的东西 | 证据 |
|---|---|---|
| worker 返回 `rpc.err` 帧 | `RpcError extends Error`：`message` 形如 `"[CODE] 详情"`，`code` 属性携带错误码 | `every-agent-web/src/sdk/frames.ts:123-127`，reject 于 `every-agent-web/src/sdk/hub-client.ts:356-361` |
| 目标 worker 未连接 | 普通 `Error('worker <id> 未连接')` | `every-agent-web/src/hub/session.ts:371-383` |
| RPC 超时 | 普通 `Error('rpc <method> 超时(<ms>ms)')` | `hub-client.ts:308-314` |

worker 侧错误码全集（`every-agent-contract/src/main/java/dev/everyagent/contract/rpc/Rpc.java:18-25`）与异常映射（`every-agent-worker/.../rpc/RpcDispatcher.java:93-108`）：

| code | 触发异常 |
|---|---|
| `UNKNOWN_METHOD` | 方法名未注册（`RpcDispatcher.java:83-85`） |
| `BAD_PARAMS` | `BadParamsException`（如 base64 非法、超写入上限） |
| `NOT_FOUND` | `NotFoundException`（路径不存在、不是目录/文件） |
| `SANDBOX_DENIED` | `SandboxViolationException`（**路径越界/符号链接逃逸/对根操作**，见 §6） |
| `AUTH_REQUIRED` | `AuthRequiredException` |
| `INTERNAL` | 其余一切 `Throwable`（含 InterruptedException「请求被取消」） |

判别 `RpcError` 的惯用写法（`code` 是普通只读属性，非 symbol）：

```ts
try {
  await ctx.sdk.rpc(ctx.sdk.workerId, 'task.fileChanges', { taskId })
} catch (e) {
  // RpcError 的 message 形如 "[NOT_FOUND] 任务不存在"，code 属性可精确分支
  if (e instanceof Error && 'code' in e && (e as { code: string }).code === 'SANDBOX_DENIED') {
    // 路径越界——见 §11 坑 4
  }
}
```

另两条传输层事实：重连期间 RPC 在 HubClient 层**入队等待重放**，不会立刻抛「未连接」（`session.ts:377-378` 注释、`hub-client.ts:277-279`）；`fs.read`/`fs.search` 这类大结果会经 `rpc.data` 分批（本文两个 fs 方法不涉及）。

### 3.3 完整小例子：前端调后端插件自注册 RPC

以内置 `file-change` 插件的真实链路为蓝本（后端注册 `task.fileChanges`，前端轮末面板拉数据）。

前端（web 侧，示例按 `every-agent-plugins/file-change/web/roundChangesStore.ts:81-92` 简化）：

```ts
import type { PluginContext } from '@everyagent/plugin-api'

// activate(ctx) 时把 ctx 存进模块级持有者，供 React 组件后取
// （照抄 every-agent-plugins/file-change/web/pluginRuntime.ts 的做法）
let pluginCtx: PluginContext | null = null

export async function fetchRoundChanges(taskId: string): Promise<unknown> {
  const ctx = pluginCtx!
  // 目标 worker 优先用任务归属，缺省回退当前宿主 worker
  const targetWorker = ctx.sdk.workerId
  try {
    // 后端插件在 activate() 里 ctx.registerRpcMethod("task.fileChanges", ...) 注册的方法，
    // 前端就是一条普通 RPC：方法名原样、参数为 JSON 对象
    const result = await ctx.sdk.rpc(targetWorker, 'task.fileChanges', { taskId })
    return (result as { rounds?: unknown[] }).rounds ?? []
  } catch (e) {
    // RpcError：e.code 是 'NOT_FOUND'/'INTERNAL' 等错误码，e.message 形如 '[NOT_FOUND] 任务不存在'
    console.warn('拉取轮次文件变更失败', e)
    return []
  }
}
```

后端（java 侧，示意；命名规范、ACL 与完整约定详见[后端任务与 RPC](../backend/task-and-rpc.md)）：

```java
// every-agent-plugins/file-change/src/main/java/dev/everyagent/plugin/filechange/FileChangePlugin.java:35 的原型
@Override
public void activate(WorkerPluginContext ctx) {
    // 方法名直接进 worker 的 RpcDispatcher，无前缀改写
    // （every-agent-worker/.../plugin/WorkerPluginContextImpl.java:186-189）
    ctx.registerRpcMethod("task.fileChanges", this::rpcTaskFileChanges);
}

private void rpcTaskFileChanges(RpcContext rpc) throws IOException {
    String taskId = rpc.strParam("taskId");
    // ... 查询本插件落盘的数据 ...
    rpc.ok(Json.obj().set("rounds", rounds)); // rpc.ok 的 result 即前端 Promise 的 resolve 值
}
```

同一个 `sdk.rpc` 也能直接调 worker 内置方法（`plugin.list`、`task.*` 等），现成范例：`every-agent-plugins/plugin-manager/web/PluginManagerPanel.tsx:199,222,226`（调 `plugin.list` / `plugin.enable` / `plugin.disable`）。

## 4. `ctx.storage` —— 本地键值存储

| 方法 | 签名（`index.ts:200-204`） | 宿主实现 | 行为细节 |
|---|---|---|---|
| `get` | `get<T>(key, defaultValue?): T \| undefined` | `pluginLoader.ts:145-152` | 读 `localStorage.getItem('plugin:<id>:' + key)`，`JSON.parse` 还原；**无值或解析抛异常都返回 `defaultValue`**（整个 try/catch 包住） |
| `set` | `set(key, value)` | `pluginLoader.ts:153-159` | `JSON.stringify(value)` 后写入；**localStorage 满/不可用时静默吞异常**（catch 空体，注释「localStorage 满或不可用，忽略」） |
| `delete` | `delete(key)` | `pluginLoader.ts:160-162` | `localStorage.removeItem`，无 try/catch（localStorage 本身不可用会抛） |

要点：

- **键格式**：`plugin:<pluginId>:<key>`（前缀定义 `pluginLoader.ts:143`）。全仓只有 `pluginLoader.ts` 读写该前缀——**没有任何卸载/禁用清理逻辑**（坑 3）。
- **值形态**：任意可 `JSON.stringify` 的值；`undefined`、函数、循环引用会序列化成非法/空内容，读回时 `JSON.parse` 失败 → 走 `defaultValue`。
- **多标签页**：源码中**没有任何同步机制**——`set` 不派发领域事件，也没人监听 `storage` 事件。同源 localStorage 物理上是跨标签共享的（另一标签的下一次 `get` 能读到新值，属浏览器标准行为，本仓未实测），但两个标签里的插件实例各自持有独立内存态，不会互相通知刷新。
- **容量与清理是插件自己的责任**：宿主不提供 `keys()`/`clear()`，要列键只能自己记索引键。

用法示例（注意 `get` 的 defaultValue 与 JSON 往返）：

```ts
// 读：无值/解析失败都落到第二个参数，不会抛
const collapsed = ctx.storage.get<boolean>('tree.collapsed', false)
// 写：值整体 JSON.stringify，对象/数组/number 都可以
ctx.storage.set('tree.collapsed', true)
ctx.storage.set('recentPicks', ['a.md', 'b.md'])   // 读回即 string[]
// 删：只删这一个键，没有清空本插件全部键的 API
ctx.storage.delete('recentPicks')
// ⚠️ set 静默吞错（配额满不告警）：关键数据写入后可读回校验
ctx.storage.set('bigBlob', json)
if (ctx.storage.get<string>('bigBlob') !== json) { /* 降级：不入库/提示 */ }
```

## 5. `ctx.commands` —— 命令注册表

| 方法 | 签名（`index.ts:206-209`） | 宿主实现 | 行为细节 |
|---|---|---|---|
| `registerCommand` | `(id, handler) => Disposable` | `pluginLoader.ts:170-176` | `Map.set(id, handler)`——**同 id 后注册静默覆盖先注册，不报错不告警**；返回的 Disposable 的 `dispose()` 是 `Map.delete(id)`（按 id 删，**不认 handler**，见坑 2） |
| `executeCommand` | `async (id, ...args) => Promise<unknown>` | `pluginLoader.ts:178-184` | 查表调用 `handler(...args)` 并 await 其返回值；**未注册的 id reject `Error('未知命令: <id>')`** |

三条必须知道的边界（都源自同一个实现事实）：

1. **注册表是每个插件私有的**：`commands: createCommandRegistry()` 在构造每个 ctx 时新建一个空 `Map`（`pluginLoader.ts:388` 调 `:167-169`）。插件 A `executeCommand` 不到插件 B 注册的命令。
2. **没有宿主内置命令**：宿主自身不向任何插件注册表塞命令——`every-agent-web/src` 中 `registerCommand` 调用点为零（唯一出现处是注释 `every-agent-web/src/plugin/api.ts:11`）。想触发宿主动作用 `ctx.ui` 的动作方法（`openPluginTab` 等，见 [UI 扩展点](ui-extensions.md)）或 `sdk.rpc`。
3. **内置插件也没人用它**：`every-agent-plugins/*/web` 下 `ctx.commands` 零调用（rg 确认）。当前它是「留给你自己插件内部用的解耦点」，不是全局命令总线。

插件内部解耦的标准用法（把 UI 回调与实现拆开）：

```ts
// 注册：id 当插件内唯一常量管理（建议 <pluginId>.<verb> 风格自约束）
const d1 = ctx.commands.registerCommand('my-notes.insert-date', () => Date.now())
// 执行：返回 Promise，handler 的同步返回值/异步结果都在这里
const now = await ctx.commands.executeCommand('my-notes.insert-date')
// 未注册的 id 会 reject：Error('未知命令: my-notes.insert-date')
// 注销：dispose 立即从表里删掉该 id（见 §11 坑 2：按 id 删，不认 handler）
d1.dispose()
```

## 6. `ctx.fs` —— 工作区文件系统网关

| 方法 | 签名（`index.ts:170-175`） | 宿主实现 | RPC |
|---|---|---|---|
| `listDir` | `(workspaceRoot: string, dir: string) => Promise<PluginFileStat[]>` | `pluginLoader.ts:254-263` → `workspaceGateway.listDir`（`every-agent-web/src/platform/fs/workspaceGateway.ts:380-393`） | `fs.list`（worker `every-agent-worker/.../modules/FsService.java:61` 注册、`:74-90` 实现） |
| `delete` | `(workspaceRoot: string, path: string) => Promise<void>` | `pluginLoader.ts:264` → `workspaceGateway.deletePath`（`workspaceGateway.ts:329-342`） | `fs.delete`（`FsService.java:68` 注册、`:248-258` 实现，**递归删除**） |

**jail 范围结论（重点）**：`ctx.fs` 不是 jailed 到「插件目录」，而是 jailed 到**第一个参数 `workspaceRoot` 指定的工作区根**（机器绝对路径，身份键）。worker 侧沙箱的合法根集合 = **工作区根 ∨ 用户授权的外部根（externalRoots）∨ 系统技能目录**（`FsService.java:264-281` 的 `readSandbox`/`sandbox`，两者实现相同，只差注释语义）。也就是说：

- 插件能读写**整个工作区**（含 `.git`、`data/` 等一切），不是只有自己那块；
- `workspaceRoot` 必须是前端注册表里已登记的根，否则**前端直接抛普通 `Error('无法确定该工作区所属 worker(工作区未注册或 worker 离线)')`，RPC 都发不出去**（`workspaceGateway.ts:28-34`）；
- `dir`/`path` 是**工作区相对路径**（空串 = 工作区根；`listDir` 空串经 `toWorkerPath` 归一为 `'.'`，`workspaceGateway.ts:148-151`）。入参带不带前导 `/` 都接受（`every-agent-web/src/platform/fs/pathUtils.ts:21-29` 归一），但前端归一**只合并分隔符、不去 `..`**——`../x` 会原样到达 worker；
- worker 侧 `Sandbox.resolveExisting/resolveTarget` 做 `root.resolve(rel).normalize()` 后**词法前缀校验 + realpath 双校验**：越界抛「路径越界」、符号链接逃逸抛「符号链接逃逸」（`every-agent-worker/.../modules/Sandbox.java:59-92`），映射为 RPC 错误码 `SANDBOX_DENIED`（`RpcDispatcher.java:97-98`）；
- `delete` 拒绝工作区根本身：前端空路径直接抛 `'不能删除工作区根目录'`（`workspaceGateway.ts:331-335`），worker 侧 `requireNotRoot` 抛 `SANDBOX_DENIED`「不能对工作区根执行该操作」（`Sandbox.java:121-131`、`FsService.java:251`）；
- `listDir` 返回的 `PluginFileStat.path` 是**带前导 `/` 的业务绝对路径**（`workspaceGateway.ts:390` 经 `toBusinessAbsolutePath`），与入参坐标系不同，别直接拿去回传 `delete`——先 `workspaceGateway.normalizePath` 同款归一或自行去前导斜杠（`PluginFileStat.path` 喂回 `listDir`/`delete` 可行，因入参两形态都接受；此行为源码可证、未实测）。

现成范例：`every-agent-plugins/git/web/GitSidebarPanel.tsx:433`（`ctx.fs.listDir(workspaceRoot, dir).catch(() => [])`）、`:574`（`await ctx.fs.delete(workspaceRoot, path)`）。最小用法：

```ts
// 列目录：dir 为工作区相对路径，空串 = 根；返回条目的 path 带前导 /
const rows = await ctx.fs.listDir(workspaceRoot, 'docs')
for (const row of rows) {
  row.isDirectory  // boolean
  row.name         // 'plugin-guide'
  row.path         // '/docs/plugin-guide'（业务绝对路径，见上）
}
// 删除：递归删除文件或整棵目录；空路径/根路径都会被拒（见 §11 坑 4）
await ctx.fs.delete(workspaceRoot, 'docs/draft.md')
```

写成功后宿主会广播 `workspace-file-changed` 领域事件（`workspaceGateway.ts:158-165` 的 `emitFileChanged`，`deletePath` 内调用），你的插件可以用 `ctx.events.on('workspace-file-changed', ...)` 感知自己和他人的写操作。

## 7. `ctx.events` —— 领域事件总线

| 方法 | 签名（`index.ts:162-167`） | 宿主实现 | 行为细节 |
|---|---|---|---|
| `on` | `(eventName, handler) => Disposable` | `pluginLoader.ts:234-240` | 直接 `domainEventBus.subscribe(eventName, handler)`，返回的 Disposable 包住 unsubscribe 函数——**dispose 是真清理**（`every-agent-web/src/events/eventBus.ts:20-30`：从 Set 删除，空 Set 连键一起删） |
| `emit` | `(eventName, payload) => void` | `pluginLoader.ts:241-246` | 直接 `domainEventBus.emit(eventName, payload)`，**无任何过滤/改写** |

**emit 的可达范围结论（读 bridge 实现后确定）**：`ctx.events` 是宿主进程内 `domainEventBus` 的**最薄委托**（实现只有两行转发，`pluginLoader.ts:232-249`）。所以 `emit` 的受众 = **同一浏览器页面内该总线的全部订阅者**，具体包括：

- ✅ **宿主 UI 代码**：`Layout.tsx`、`taskStore.ts`、`taskStream.ts`、`FileTabPage.tsx` 等大量模块直接 `domainEventBus.subscribe(...)`（rg 确认，如 `every-agent-web/src/components/app/Layout.tsx`）。事件名命中宿主具名事件（`DOMAIN_EVENTS` 常量表，`every-agent-web/src/events/domainEvents.ts:91` 起，完整 36 事件手册见 [事件](events.md)）时宿主会真消费；
- ✅ **其他插件**（它们同样经 `ctx.events.on` 挂在总线上）；
- ❌ **到不了 worker / hub**：这条总线是纯前端内存对象，没有任何上行桥接；
- ❌ **到不了其他浏览器标签页**，也无持久化；
- ⚠️ **无监听者时静默**：`eventBus.emit` 开头 `if (!listeners || listeners.size === 0) return`（`eventBus.ts:11-17`），派发经 `queueMicrotask` 异步执行——emit 本身永远不报错（坑 5）。

类型层面 `PluginDomainEvent` 只具名列 9 个事件名 + `(string & {})` 兜底（`index.ts:149-160`），但运行时**任意字符串都能订阅/发布**（TS 断言穿透，`pluginLoader.ts:236,243`）。

现成范例：`every-agent-plugins/file-change/web/index.ts:24-33`（`ctx.events.on('task-round-closed'/'task-deleted', ...)` 作废缓存，Disposable 未持有——运行期无需中途停听，页面卸载时宿主统一清理，见 §9）、`every-agent-plugins/git/web/usePluginWorkspaces.ts:31`。

## 8. `ctx.ui` —— 一笔带过

`UiRegistry` 有 12 个 `register*`（sidebar 项、工作区标签类型、工具调用视图、文件编辑器……）与 5 个动作方法（`openPluginTab`/`openFileTab`/`openDiffTab`/`appendComposerText`/`setComposerRawContent`），声明在 `index.ts:607-634`，宿主实现是单例 `pluginDispatcher`（ctx 里 `ui: pluginDispatcher as unknown as ...` 强转接入，`pluginLoader.ts:394`）。每个 `register*` 返回的 Disposable 都是**真清理**：`ListExtensionRegistry.register` 的 dispose 从数组 splice 并通知订阅者重渲染（`every-agent-web/src/plugin/ExtensionRegistry.ts:45-56`）。逐扩展点字段表与代码范例见 [UI 扩展点](ui-extensions.md)。

## 9. 寿命与清理

**Disposable 语义是真的**：`events.on`（unsubscribe）、`ui.register*`（splice + notify）、`commands.registerCommand`（Map.delete）三种 Disposable 调 `dispose()` 都会立即生效（证据见各节）。宿主侧的代管与停用钩子有三条事实（known-issues #8 修复后口径）：

1. **宿主跟踪你的 Disposable**：激活时 `ctx.ui` / `ctx.commands` / `ctx.events` 都包了一层收集代理（`pluginLoader.ts` 的 `trackDisposables`），注册方法返回的 Disposable 全部记入该插件的 `disposables` 数组。你自己持有的 Disposable 依然有效——各注册点的 dispose 幂等，宿主随后的统一 dispose 无害。
2. **`deactivate` 在页面卸载时被调用**：`window` 的 `pagehide`（刷新/关闭/跳转，含插件面板「重新加载」按钮触发的 `location.reload()`）时，宿主对全部已激活插件先调 `module.deactivate?()`，再逆序 dispose 收集的全部注册项、移除注入的插件 CSS（`pluginLoader.ts` 的 `unloadPlugin` / `unloadAllPlugins`）。这是与后端 worker 优雅关闭（`@PreDestroy` → `deactivate`，[后端总览](../backend/overview.md) §3.2）对齐的**尽力而为**钩子：同步清理一定执行，异步 `deactivate`（`await` 之后的代码）不保证在页面卸载前跑完。**运行期**禁用插件不会触发热卸载——worker 侧 Java 贡献要到下一次启动才摘除，前端单独摘除会造成两侧不同步，生效边界统一维持「重启 worker + 刷新页面」。
3. **页面刷新 = 全部清零**：blob 模块、`loadedPlugins`、扩展点注册表、事件订阅、命令表全是模块内存态，刷新即丢（丢之前宿主已按上一条统一 deactivate + dispose）；活下来的只有 `ctx.storage`（localStorage）与 worker 侧数据。同一次会话内的防重复激活靠两层：`loadedPlugins.has(id)` 幂等 + 加载 Promise 锁（`pluginLoader.ts` 的 `loadingPromise` 注释解释了为什么需要）。`loadPlugins()` 由 `every-agent-web/src/main.tsx` 多处触发（启动、重连、切换 worker）。

推论：注册类资源即使不手动持有，页面卸载时也会被宿主统一清理；但**运行期**没有任何停用时机（插件直到页面卸载一直活着），中途要停听/注销仍需自己持有 Disposable 调 dispose。需要警惕的反而是**组件级**泄漏——在 React 组件 `useEffect` 里 `ctx.events.on` 时记得返回 `() => disposable.dispose()`（范例：`every-agent-plugins/git/web/GitSidebarPanel.tsx:334` 附近的用法）。

## 10. 综合示例：一个把 ctx 用满的最小插件骨架

把本文各节拼成一个可抄的骨架（仅示意组装方式；UI 扩展点部分见 [UI 扩展点](ui-extensions.md)）：

```ts
// web/index.ts —— 纯类型 import 是唯一合法姿势（§11 坑 1 同源）
import type { PluginContext, PluginModule, PluginFileStat } from '@everyagent/plugin-api'
import React from 'react'

let ctxRef: PluginContext | null = null   // 组件后取 ctx 的模块级持有者（file-change 同款）

const plugin: PluginModule = {
  activate(ctx: PluginContext) {
    ctxRef = ctx

    // ① 身份：pluginId 即 plugin.json 的 id；extensionPath 为 webMain 换算的产物路径（约定 'web/index.js'）
    console.log(`[my-plugin] activated: ${ctx.pluginId} @ ${ctx.extensionPath}`)

    // ② storage：读上次状态（无值给默认），键实际落在 plugin:my-plugin:panel.open
    if (ctx.storage.get<boolean>('panel.open', false)) openMyPanel()

    // ③ commands：插件内部命令（私有表，不跨插件；id 冲突静默覆盖，见 §5）
    ctx.commands.registerCommand('my-plugin.refresh', async () => {
      // ⑤ fs：列工作区根（workspaceRoot 用注册表里的合法根，别自己拼路径）
      const root = (await ctx.sdk.workspace.list())[0]?.root
      if (!root) return []
      return ctx.fs.listDir(root, '') as Promise<PluginFileStat[]>
    })

    // ④ events：订阅宿主领域事件（同名即被宿主 UI 消费）；Disposable 真清理
    const onFileChanged = ctx.events.on('workspace-file-changed', (payload) => {
      const p = payload as { workspaceRoot?: string; filePath?: string }
      if (p.filePath?.endsWith('.md')) refreshMdCount(p.workspaceRoot ?? '')
    })
    void onFileChanged // 页面卸载时宿主统一 dispose（§9）；运行期要中途停听才需自己持有

    // ⑥ sdk.rpc：调后端插件自注册的 RPC（后端侧见 §3.3）
    void ctx.sdk.rpc(ctx.sdk.workerId, 'my-plugin.stats', {}).catch(() => {})
  },
  // deactivate 在页面卸载（刷新/关闭）时被宿主调用（§9 事实 2）；无清理逻辑可省略
}

function openMyPanel(): void { /* ctx.ui.registerSidebarItem(...) 见 ui-extensions.md */ }
async function refreshMdCount(_root: string): Promise<void> { /* ... */ }

export default plugin
```

骨架里的六个触点一一对应本文 §2~§7；真实可抄的成品建议直接读 `every-agent-plugins/file-change/web/`（`index.ts` + `pluginRuntime.ts` + `roundChangesStore.ts` 三件套）与 `every-agent-plugins/git/web/gitGateway.ts:91`。

## 11. 常见坑（现象 → 根因 → 正确写法）

### 坑 1：把 `@everyagent/plugin-api` 当值 import

- **现象**：`import { PluginContext } from '@everyagent/plugin-api'`（没写 `type`）类型检查可能不炸，但一旦出现值用法（`export { X } from '@everyagent/plugin-api'`、运行时访问其导出），`npm run build:plugins` 直接失败：`Could not resolve "@everyagent/plugin-api"`。
- **根因**：该包是**纯类型包**（`every-agent-plugin-api/js/package.json` 只有 `types: index.ts`，无 `main`/`exports`）。esbuild 会把纯类型位置的 import 整体擦除，所以「错但不炸」；值位置擦不掉就去解析模块名，而插件目录之上没有任何 `node_modules`（仓库根与 `every-agent-plugins/` 都没有，符号链接只在 `every-agent-web/node_modules` 里）⇒ 构建期解析失败，不是运行期报错。另 `every-agent-web` 的 `isolatedModules: true` ⇒ 再导出类型必须 `export type`。
- **正确写法**：一律 `import type { ... } from '@everyagent/plugin-api'`；再导出用 `export type { ... }`；需要运行时对象时只用 `ctx` 本身。

### 坑 2：命令 id「冲突」不报错，且旧 Disposable 会误杀新命令

- **现象**：同一插件里两次 `ctx.commands.registerCommand('myCmd', h1)` / `('myCmd', h2)`，没有任何告警，之后调 `h1` 的 Disposable.dispose()，结果 `h2` 也调不到了；或者期望 `executeCommand` 能调到别的插件/宿主的命令，得到 `Error('未知命令: ...')`。
- **根因**：注册表是每插件私有的普通 `Map`（`pluginLoader.ts:388`），`registerCommand` 就是 `Map.set` —— 后注册**静默覆盖**（`:171`）；Disposable 的 dispose 按 **id** 删除而非按 handler（`:173-176`），所以旧 Disposable 能删掉新 handler。跨插件与宿主内置命令根本不存在（§5）。
- **正确写法**：命令 id 当作插件内唯一常量管理（建议 `<pluginId>.<verb>` 风格自约束）；注册前若不确定，先自己记账避免重复；dispose 只对「最后一次成功注册」有效，重复注册场景请在注册新的之前先 dispose 旧的。

### 坑 3：storage 键带插件前缀，卸载重装后残留

- **现象**：插件卸载（或 `plugin.disable` + 目录删除）后再装回来，用户旧数据「复活」；改了 `id` 后老数据全部读不到。
- **根因**：键是 `plugin:<pluginId>:<key>`（`pluginLoader.ts:143`），写在浏览器 localStorage 里；全仓**没有任何代码**在卸载/禁用时清理该前缀（`plugin.uninstall` 只删 worker 机器上的插件目录，`every-agent-web/src` 中该前缀的读写点仅 `pluginLoader.ts`）。换 `id` = 换存储桶，旧键成孤儿。
- **正确写法**：把 `ctx.storage` 当「可能被用户带走/残留的缓存」而非可信状态；需要版本迁移时自己存 schema 版本键、自己写迁移（读旧前缀 → 写新前缀）；敏感数据不要放这里（localStorage 明文）。

### 坑 4：fs 路径越界被 `SANDBOX_DENIED` 拒绝

- **现象**：`ctx.fs.delete(workspaceRoot, '../x')` 或路径里含指向工作区外的符号链接时，Promise reject `[SANDBOX_DENIED] 路径越界: ../x`；`workspaceRoot` 拼错/未注册时则是普通 `Error('无法确定该工作区所属 worker...')`；`delete(ws, '')` 抛 `'不能删除工作区根目录'`。
- **根因**：前端归一只合并分隔符不去 `..`（`pathUtils.ts:21-29`），越界路径原样到 worker；worker `Sandbox` 做 `root.resolve(rel).normalize()` 词法前缀校验 + 已存在路径 realpath 校验（`Sandbox.java:59-75`），写入目标还要对父目录 realpath（`:77-92`）；根本身受 `requireNotRoot` 保护（`:121-131`）。合法根 = 工作区根 ∨ 授权外部根 ∨ 系统技能目录（`FsService.java:264-281`）。
- **正确写法**：只用工作区相对路径（`dir/file.txt`、空串=根），从 UI 拿到的 `/` 开头路径先归一；跨工作区操作先 `sdk.workspace.list()` 拿合法 `root` 再传；要访问工作区外目录走宿主授权流程（`AUTH_REQUIRED`），别试图用 `..` 逃逸。

### 坑 5：`emit` 无监听者静默，且到不了 worker

- **现象**：`ctx.events.emit('my-plugin-sync-done', {...})` 后「什么都没发生」——不报错、无日志；或期望 worker/另一个浏览器标签页收到事件，也收不到。
- **根因**：`eventBus.emit` 无监听者直接 return（`eventBus.ts:11-17`），且派发是 `queueMicrotask` 异步；这条总线是纯前端进程内对象，没有上行 worker 的桥，也没有跨标签/持久化通道（§7）。
- **正确写法**：插件间前端联动用约定好的事件名（建议带插件前缀，且双端同仓维护字面量）；要让 worker 知道什么，走 `sdk.rpc` 调后端自注册方法（§3.3）或后端插件自己订阅任务流；调试时先 `ctx.events.on(name, console.log)` 挂探针确认拼写。

## 12. 下一步读

- 12 个 UI 扩展点逐个字段表与注册代码：[UI 扩展点](ui-extensions.md)
- 21 个宿主事件 + 8 个具名事件的完整手册：[事件](events.md)
- `registerRpcMethod` 命名规范、ACL 与后端事件发射：[后端任务与 RPC](../backend/task-and-rpc.md)
