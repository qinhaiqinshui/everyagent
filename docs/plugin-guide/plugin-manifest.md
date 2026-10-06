---
title: plugin.json 字段参考
nav_order: 3
---

# plugin.json 字段参考

**一句话定位**：`plugin.json` 是一个插件的**唯一清单**——worker 启动时靠它判断「这个目录是不是插件、要不要激活、激活哪个 Java 入口类、有没有前端界面、插件配置默认值是什么」，前端再靠 `plugin.list` 的返回决定「要不要去向 worker 拉这个插件的 `web/index.js`」。

它不是构建描述文件（构建归 `pom.xml` / `scripts/build-plugins.mjs`），也不是运行时配置存储（插件私有状态归 `pluginDir()`，见 [持久化与插件私有状态](backend/persistence-and-state.md)）。

## 1. 这份清单在什么时候、被谁读

```
worker 启动（@PostConstruct，全程只读一次）
  ├─ BuiltInPluginScanner.scan()    每级子目录：根 plugin.json 存在 → 才算插件；先看 enabled
  ├─ ExternalPluginScanner.scan()   ~/.everyagent/plugins/<dir>/plugin.json 存在且 enabled≠false → 纳入
  └─ PluginLoader.loadPlugin()      读 manifest（内置优先 target/classes/plugin.json）
                                    → id / name / version / description / author
                                      / main（兜底 provides.spi.EveryAgentPlugin）/ webMain
                                      / contributes.config.*.default
        ↓ 登记 LoadedPlugin（含内部 status） → PluginRegistry 聚合 catalog
        ↓
  plugin.list RPC ── 前端 pluginLoader.ts 过滤 active && hasWebMain && !disabled
        ↓
  plugin.webSource{pluginId, path: <webMain 换算的产物路径>} → 重写 bare import → blob URL → import() → activate(ctx)
```

| 读取者 | 读的字段 | 证据 |
|---|---|---|
| `BuiltInPluginScanner` | 仅 `enabled` | `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/BuiltInPluginScanner.java:79-88`、`isEnabled` 实现 `:117-123` |
| `ExternalPluginScanner` | 仅 `enabled`（与内置同语义） | `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/ExternalPluginScanner.java`（`isEnabled` 同款私有实现） |
| `PluginLoader` | `id/name/version/description/author/main/provides.spi.EveryAgentPlugin/webMain/contributes.config.*.default` + 禁用名单 | `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginLoader.java:202-258` |
| 前端 `pluginLoader.ts` | `plugin.list` 返回的 `active/hasWebMain/webMain/id`（清单字段一律不直接读文件） | `every-agent-web/src/plugin/pluginLoader.ts`（`webEntryJsPath` 消费 `webMain` 值） |

⚠️ **时机**：清单只在 `PluginLoader.init()`（`@PostConstruct`，`PluginLoader.java:151-154`）时读一次，改 `plugin.json` **必须重启 worker**；运行期没有重载入口（`scanAndLoad()` 的第二次调用只出现在单测里：`every-agent-worker/src/test/java/dev/everyagent/worker/plugin/loader/PluginLoaderDisabledTest.java:107,124`）。

## 2. 全字段表

### 2.1 worker 真正消费的字段

所有字段**都不是 JSON Schema 强约束**：解析走 `JsonNode.path("x").asString(缺省)` 逐字段取值，缺什么就用缺省，多写的/拼错的键**静默忽略、不报错**。

| 字段 | 类型 | 必填 | 缺省值（消费点兜底） | 被哪段代码消费（相对仓库根） | 踩坑说明 |
|---|---|---|---|---|---|
| `id` | string | 实践必填（代码不强制） | `""` → 回退**目录名** | `every-agent-worker/.../loader/PluginLoader.java:214-217` | 它有三重身份，见 §4；仓库内 26 个内置插件全部做到 `id == 目录名` |
| `name` | string | 否 | 缺省 = `id` | `PluginLoader.java:226` | 仅展示（`plugin.list` → 扩展管理面板），不参与任何判定 |
| `version` | string | 否 | `"0.0.0"` | `PluginLoader.java:227` | 纯展示字符串：**不校验 semver、不与 Maven pom 版本比对、不参与任何兼容判定**（见 §7） |
| `description` | string | 否 | `""` | `PluginLoader.java:228` | 仅展示 |
| `author` | string | 否 | `""` | `PluginLoader.java:229` | 仅展示 |
| `main` | string（Java 类 FQN） | java / full 形态必填 | `""` → 无 Java 入口，走「声明式插件」路径 | 取值为入口类：`PluginLoader.java:231`；反射加载：`:309-320` | FQN 必须与 `src/main/java` 路径逐段一致（§8）。外部插件的清单同样受 `enabled` 约束（§6），且**看 `main` 有没有对应 jar**：`<id>/lib/*.jar` 为空 → status「无 jar 文件」 |
| `webMain` | string（web 入口源码路径） | web / full 形态必填 | `""` → `hasWebMain=false`，前端直接不加载 | `PluginLoader.java:235` → `PluginRpcMethods.java`（`plugin.list` 同时下发 `hasWebMain` 与原始值） | 值**已被前端消费**：产物路径 = `webMain` 去扩展名拼 `.js`（空值回退 `web/index.js`），见 §5 |
| `enabled` | boolean | 否（建议显式写） | `true`（缺省**或解析失败**都算 true） | `BuiltInPluginScanner.java:81`、`:117-123`；`ExternalPluginScanner` 同款判定 | 内置与外部扫描器**都看它**（同语义：false → 整目录跳过、不加载、不进 `plugin.list`）。详见 §6 |
| `contributes.config.<key>.default` | string / number / boolean | 否 | 无 `default` 键 = 该配置项不进入 map | `PluginLoader.java:248-258` → `PluginConfigImpl`（`every-agent-worker/.../plugin/PluginConfigImpl.java`） | 只有 `default` 被读；`type` / `description` **无人消费**（见 §2.2） |

### 2.2 `contributes.config` 的实际链路（比想象短）

```jsonc
"contributes": { "config": { "image-vision.max-size-mb": { "type": "number", "default": 10, "description": "…" } } }
```

链路：**`contributes.config` → `PluginConfig` → 插件代码**。实测四步：

1. `PluginLoader.java:248-258` 遍历 `contributes.config` 的每个键，**只取子字段 `default`**，塞进 `Map<String,Object>`（JSON number → `Long`/`Double`、string → `String`、boolean → `Boolean`、其他 → 其 `toString()`：`PluginLoader.java:363-375`）；
2. 包成 `new PluginConfigImpl(configDefaults)`（`PluginLoader.java:259`）；
3. 经 `WorkerPluginContextImpl` 交给插件，插件用 `ctx.config()` 取值（接口 `every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/PluginConfig.java`：`getString/getInt/getBoolean/get`；声明见 `every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerPluginContext.java:101-102`）；
4. 真实范例：`every-agent-plugins/image-vision/src/main/java/dev/everyagent/plugin/imagevision/ImageVisionPlugin.java:29` 调 `ImageVisionSettings.from(ctx.config())`，逐项 `config.getBoolean("image-vision.enabled", true)`（`.../ImageVisionSettings.java:24-32`）。另一例：`every-agent-plugins/sandbox-windows-codex/src/main/java/dev/everyagent/plugin/sandbox/codex/CodexSandboxPlugin.java:42`。

⚠️ **三个必须知道的实情**：

- **用户覆盖不存在**：`PluginConfigImpl` 只读那张内存 map，其类注释明写「阶段二：从内存 Map 读取……**后续可扩展为从用户设置文件读取覆盖值**」（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/PluginConfigImpl.java:7-12`）。全仓 grep 无任何 `SettingsStore` / 插件配置写盘路径，也没有 `plugin.setConfig` 之类的 RPC ⇒ **当前 `ctx.config()` 恒等于 `plugin.json` 里写的 `default`**。改配置只能改清单再重启。（计划文档里提到的「→ SettingsStore」环节在现仓不存在，以代码为准。）
- **`type` / `description` 是给人看的注释**：worker 不校验类型，前端也不渲染这两个字段（全仓 `contributes` 只出现在 2 个 `plugin.json`、`PluginLoader`、`PluginDescriptorParser`、plugin-api 注释里）。类型不对时靠 `PluginConfigImpl.getInt/getBoolean` 的兜底 + 插件自己给的第二个参数（真正的代码默认值）救场 ⇒ **`default` 与代码里的兜底值要手工保持一致**。
- **别和 `ctx.services().config()` 混**：后者是 `WorkerConfig`（worker 全局 `worker.*` 配置），与 `contributes.config` 无关，例如 `every-agent-plugins/task-queue/src/main/java/dev/everyagent/plugin/taskqueue/TaskQueuePlugin.java:19`。

### 2.3 存在于 `PluginDescriptor`、但**当前不生效**的字段

写这些字段**没有任何运行期效果**，列出来是为了让你看懂「为什么有人写过它却没作用」。

先认清一个易混点：worker 里其实有**两套互不相干的清单模型**——

- 真正生效的是 `PluginLoader` 里对 `JsonNode` 的手工取值（§2.1）；
- 另一套是富清单模型 `PluginDescriptor` / `PluginDescriptorParser`（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginDescriptor.java`、`.../PluginDescriptorParser.java`），它**根本没有 `main` / `webMain` / `enabled` 字段**，而 **main 代码里零调用点**：`rg "PluginDescriptorParser" every-agent-worker/src/main/java` 只命中该类自身文件，其余引用全在单测 `every-agent-worker/src/test/java/dev/everyagent/worker/plugin/loader/PluginDescriptorParserTest.java`。

| 字段 | 声明位置 | 状态 | 唯一引用 |
|---|---|---|---|
| `minAppVersion` | `PluginDescriptor.java:18`、解析 `PluginDescriptorParser.java:51` | **当前不生效，写了也没用**（无任何版本比较） | 仅单测 `PluginDescriptorParserTest.java:37,70,115,151` |
| `requires.spi`、`requires["every-agent"]` | `PluginDescriptor.java:26-33`、解析 `:88-95` | **当前不生效**：依赖不校验、不解析、不装配 | 仅单测 `PluginDescriptorParserTest.java` |
| `provides.spi`（除一个特例外） | `PluginDescriptor.java:36-46`、解析 `:97-105` | **仅 `provides.spi.EveryAgentPlugin` 这一层被兼容读取**（旧格式入口类回退：`PluginLoader.java:232-234`）；`provides.rpc` / `provides.slash` / `provides.web` **不生效**——RPC 与斜杠命令必须在 `activate()` 里用 `ctx.registerRpcMethod` / `ctx.registerSlashProvider` 真实注册 | `PluginLoader.java:233`（唯一 main 代码触点） |
| `activationEvents` | `PluginDescriptor.java:21`、解析 `:55` | **当前不生效**：没有懒激活，所有启用插件在启动期一次性 `activate()` | 仅单测 `PluginDescriptorParserTest.java:50,84-85,130-131,163-164` |
| `contributes.config.*.type` / `.description` | `PluginDescriptor.java:53-55`（`ConfigItem`） | 运行期**不生效**（仅 `default` 有用）；`ConfigItem` 本身随 §2.3 顶部的 `PluginDescriptor` 一起处于未接线状态 | 仅单测 `PluginDescriptorParserTest.java:88-92,230-241` |

> 结论：**新插件请只写 §2.1 的字段**。需要入口类就直接写顶层 `main`，别再写 `provides.spi.EveryAgentPlugin`（那只是向后兼容路径）。

## 3. 三种形态的完整实例（照抄仓内真实文件）

### 3.1 java-only —— `every-agent-plugins/task-queue/plugin.json`

```json
{
  "id": "task-queue",
  "name": "任务队列",
  "version": "0.1.0",
  "description": "任务队列插件（并发排队等待 + task.queued 事件 + task.queueList RPC）",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.taskqueue.TaskQueuePlugin",
  "enabled": true
}
```

目录：`plugin.json` + `pom.xml` + `src/`，无 `web/`。

### 3.2 java + web —— `every-agent-plugins/git/plugin.json`

```json
{
  "id": "git",
  "name": "Git 操作",
  "version": "0.1.0",
  "description": "Git 操作插件（原生 git RPC + 自动同步）",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.git.GitPlugin",
  "webMain": "web/index.ts",
  "enabled": true
}
```

目录：上面两者并存（`src/main/java/dev/everyagent/plugin/git/GitPlugin.java` + `web/index.ts`）。

### 3.3 纯 web —— `every-agent-plugins/pdf-viewer/plugin.json`

```json
{
  "id": "pdf-viewer",
  "name": "PDF 预览",
  "version": "0.1.0",
  "description": "PDF 预览插件：通过文件内容编辑器注册 .pdf 文件渲染（纯 Web 插件，无 worker 端）",
  "author": "everyagent",
  "webMain": "web/index.ts",
  "enabled": true
}
```

目录：只有 `plugin.json` + `web/`，**无 `pom.xml`、无 `src/`、无 `target/`**（同类还有 `every-agent-plugins/plugin-manager/plugin.json`、`every-agent-plugins/update-file-view/plugin.json`）。

### 3.4 worker / 前端各自得出什么结论

| | java-only（task-queue） | java+web（git） | 纯 web（pdf-viewer） |
|---|---|---|---|
| 内置扫描判定 | 有 `target/classes/plugin.json` ⇒ 判为 Java 插件，还要求 `target/` 下有非 sources/javadoc 的 jar（`BuiltInPluginScanner.java:90-97`） | 同左 | 无 `target/` 产物 ⇒ 判为纯 web 插件直接纳入（`:97-100`） |
| manifest 实际读的是 | `target/classes/plugin.json`（`resolveManifestPath`，`:193-202`） | 同左 | 根 `plugin.json` |
| 入口类 | `main` → 反射实例化 + `activate(ctx)`（`PluginLoader.java:309-336`） | 同左 | 无 → **走「声明式插件」分支，不调任何 Java 代码**（`PluginLoader.java:261-267`） |
| `LoadedPlugin.status` | `已激活(内置)`（内置）/ `已激活`（外部） | 同左 | `声明式插件` |
| `plugin.list` 的 `hasMain` | `true` | `true` | `false` |
| `plugin.list` 的 `hasWebMain` | `false` | `true` | `true` |
| `plugin.list` 的 `active` | `true` | `true` | `true` |
| 前端是否加载 | 否（`hasWebMain=false` 被过滤掉，`pluginLoader.ts:364-367`） | 是 | 是 |

⚠️ 两个容易读错的口径：

1. **`plugin.list` 的 `active` = 「不在禁用名单里」**，即 `active = !pluginRegistry.isDisabled(id)`（`PluginRpcMethods.java:68`），**不代表后端激活成功**：激活失败的插件仍会报 `active=true`。真判据只有 `hasMain` / `hasWebMain` + worker 日志。
2. **`status` 文案不出 RPC**：`plugin.list` 只返回 `id/name/version/description/author/source/active/hasMain/hasWebMain` 九个字段（`PluginRpcMethods.java:62-70`），`LoadedPlugin.status` 没有对外消费者 ⇒ 排查看日志，文案集见 [故障排查](guides/troubleshooting.md)：`已激活(内置)` / `已激活` / `已禁用(未激活)` / `声明式插件` / `无 jar 文件` / `lib 目录不可读: …` / `入口类未实现 EveryAgentPlugin` / `激活失败: …`。

## 4. `id` 的三重身份（最容易踩坑处）

同一个字符串同时是**目录名**、**注册标识**和**前端存储作用域**，三处不一致就会出灵异现象。仓库内 26 个内置插件实测全部 `id == 目录名`。

| 身份 | 谁用它 | 不一致的后果 |
|---|---|---|
| ① **目录名**（内置与外部扫描都按 `<plugins-root>/<dir>/plugin.json` 组织） | `BuiltInPluginScanner.java:79-101`、`ExternalPluginScanner.java:44-49` | 目录名与 `id` 不一致时**扫描仍会成功**，登记用清单里的 `id`（`PluginLoader.java:214-217`）⇒ 目录与 ID 对不上号，人肉排查时找不到是哪份清单 |
| ② **`.eap` zip 顶层目录名** | `PluginRpcMethods.extractEap`：`PluginRpcMethods.java:212-235`（首个非目录条目的 `/` 前段即 pluginId；条目不含 `/` 时回退 **zip 文件名去掉 `.eap`**；全空回退字符串 `"unknown"`） | 顶层目录叫 `my-plugin/` 而清单写 `"id": "other"` ⇒ 解出来的目录是 `~/.everyagent/plugins/my-plugin/`，但注册 id 是 `other`：`plugin.uninstall{pluginId:"other"}` 按 `<pluginsRoot>/<id>` 找目录会 `NOT_FOUND`（`:119-123`），禁用名单 `.disabled-plugins` 里写的 `other` 也永远对不上这个目录。**打包时务必让顶层目录 = `id`**，细节见 [打包与安装](guides/packaging-and-install.md) |
| ③ **前端 localStorage 作用域 + 日志标识** | `createPluginStorage` 前缀 `plugin:${pluginId}:`（`every-agent-web/src/plugin/pluginLoader.ts:141-163`）；控制台 `[plugins] 插件已激活: ${id}`（`:406`） | 改 `id` = **换存储桶**：老用户的插件本地数据一夜变孤儿。要重命名请自己写迁移（读旧前缀 → 写新前缀） |

补充：**同名冲突时内置优先**。`PluginLoader` 先把 `builtin` 排在前面再逐个加载，后到的同 id 插件被去重跳过并 WARN「插件 {} 已加载,跳过重复」（`PluginLoader.java:176-184`、`:219-223`）⇒ 想「装个同名外部插件去覆盖内置插件」是**无效**的，只能改 `id`。

命名建议：**小写字母 + 数字 + 连字符**，正则 `^[a-z0-9][a-z0-9-]{1,38}$`（脚手架 `create-everyagent-plugin` 的 id 校验同此）；实测仓内 26 个内置插件的 `id` 全部符合，例：`image-vision`、`task-edit-resend`。不要用中文、空格、下划线、点号；也不要和内置插件撞名（26 个现存 id 见 [内置插件清单](reference/builtin-plugins.md)）。

## 5. `webMain` 消费链与产物路径约定（known-issues #5 修复后）

**`webMain` 的值已被前端真实消费。** 链路三行：

- worker 侧：`plugin.list` 同时下发 `hasWebMain = !m.webMain().isEmpty()` 与 `webMain` 原始值（`PluginRpcMethods.java`）；
- 前端侧：产物路径 = `webMain` 去掉最后一个扩展名后拼 `.js`（`pluginLoader.ts` 的 `webEntryJsPath`：`"web/index.ts"` → `"web/index.js"`；空值/旧 worker 未下发时回退约定产物位 `web/index.js`）；CSS 同理取同名 `.css`；
- `ctx.extensionPath` 也改为该换算结果（不再是硬编码字面量）。

由此推出三条硬约束：

1. **`webMain` 必须非空**（否则 `hasWebMain=false`，插件在前端根本不出现）。仓内约定值是 **源码路径 `"web/index.ts"`**（9 个含 web 的插件全都这么写，例 `every-agent-plugins/git/plugin.json`）。
2. **产物必须落在 `webMain` 换算出的路径**：`build-plugins.mjs` 固定扫 `every-agent-plugins/<id>/web/index.ts` 并输出 `<id>/web/index.js`——照约定写 `"web/index.ts"` 时换算结果与产物天然一致；若把 `webMain` 改成别的源码路径，就必须让产物也落在同名 `.js` 上，否则 `plugin.webSource` 读不到该文件抛错（前端打日志 `[plugins] 插件 <id> 加载失败` 后跳过）。构建命令（PowerShell 下 `npm` 被执行策略拦截时用 `npm.cmd`）：

   ```powershell
   cd every-agent-web ; npm.cmd run build:plugins
   ```

   这条流水线**不在 `dev` / `build` / 桌面打包里**，改前端不会自动重编。
3. **不要提交 `web/index.js`**：根 `.gitignore:24-28` 已把产物排除——

   ```gitignore
   # 内置插件 web/ 预编译产物（esbuild 生成，不入库）
   every-agent-plugins/*/web/index.js
   every-agent-plugins/*/web/index.js.map
   every-agent-plugins/*/web/index.css
   every-agent-plugins/*/web/index.css.map
   ```

   （另 `.gitignore:1` 忽略 `target/`。）插件自己写 `.gitignore` 时不要与这几行冲突，更不要 `git add -f` 把产物拉进版本库。

CSS 同走换算路径：esbuild 把样式抽到与入口同名的 `.css`，宿主按入口路径把 `.js` 换成 `.css` 后经 `plugin.webSource` 注入 `<style id="plugin-css:<id>">`。完整加载链路与 bare import 白名单见 [前端总览与加载链路](web/overview-and-loading.md)。

## 6. `enabled` 与运行期禁用机制的分工

| 机制 | 写在哪 | 判在哪 | 效果 | 生效条件 |
|---|---|---|---|---|
| `enabled: false` | `plugin.json` | 扫描期：内置 `BuiltInPluginScanner.java:79-88` 与外部 `ExternalPluginScanner` **统一判定** | 目录被**跳过**：不加载、不进 `plugin.list`、前端与扩展管理面板都**看不到它** | 重启 worker |
| 运行期禁用 | `~/.everyagent/plugins/.disabled-plugins`（每行一个 id，`PluginStateStore.java:38,74-104`），由 `plugin.disable` RPC 写（`PluginRpcMethods.java:153-166`） | 加载期：`PluginLoader.java:240-245` | 仍登记进目录（`plugin.list` 可见，`active=false`、`status="已禁用(未激活)"`），但**不调 `activate()`**，不注册任何贡献 | 重启 worker |

两个坑：

- **外部插件同样有 `enabled` 语义**：`ExternalPluginScanner` 与内置同款判定（缺省视为 true），`enabled=false` 整目录跳过、不加载——「装上但默认禁用」直接写它即可；用户运行期自主关停仍走 `.disabled-plugins`（或把目录移走）。
- **为什么 `enabled` 检查要在 `target/` 判定之前做**：源码注释解释得很直白——避免「已构建但没清理的 `target/` 残留导致禁用插件被误加载」（`BuiltInPluginScanner.java:26-28`、`:76-78`）。实测仓内有两个内置插件正是这样关掉的：`every-agent-plugins/sandbox-windows-mic/plugin.json` 与 `every-agent-plugins/sandbox-wsl-ubuntu/plugin.json` 均 `"enabled": false`，且两者的 `target/` 都还在。
- 缺省即 `true`；**解析失败也算 `true`**（`isEnabled` 的 `catch (Exception)` 宽容分支，`BuiltInPluginScanner.java:117-123`）。

两种机制的更细对比（含插件私有数据的合法存放）见 [持久化与插件私有状态](backend/persistence-and-state.md)。

## 7. 常见错误

| 现象 | 根因 | 实际行为（按源码） | 证据 |
|---|---|---|---|
| 写了 `"webmain"` / `"WebMain"` / `"enable"` / `"descriptoin"` 之类，插件没生效 | 字段名**大小写敏感** | 取不到 → 走缺省；**没有任何「未知字段」告警**（不是 POJO 绑定，是逐字段 `path()` 取值） | `PluginLoader.java:214-235` |
| 清单 JSON 语法错（尾随逗号、`//` 注释、少半个括号） | Jackson 3 严格 JSON | 扫描期 `isEnabled` 吞掉异常并 WARN「解析 plugin.json 失败,视为已启用」→ 仍进待加载队列；加载期 `PluginLoader.java:207-212` **只 `catch (IOException)`**，而 `Json.parse`（`every-agent-contract/src/main/java/dev/everyagent/contract/json/Json.java`，`JsonMapper.builder().build()`）抛的是**非受检** `tools.jackson.core.JacksonException`（实测 jackson-core 3.1.5，与 worker classpath 同版本：尾逗号/注释 → `StreamReadException`，截断 → `UnexpectedEndOfInputException`，二者均 `extends RuntimeException`）→ 该异常逃出 `loadPlugin`，而 `scanAndLoad()` 的逐插件循环**没有 per-plugin try/catch**（`PluginLoader.java:182-184`）⇒ 一路冒出 `@PostConstruct`，**worker 启动失败**。**源码 + 独立异常类型实测，未实测启动复现** | `Json.java:15-21`、`PluginLoader.java:182-184,207-212`、`BuiltInPluginScanner.java:117-123` |
| 清单是**空文件** | 空串不抛异常（实测返回 `MISSING` 节点） | 全字段走缺省：`id`=目录名、`version`=`0.0.0`、无 `main`/`webMain` ⇒ 以「声明式插件」身份登记且 `active=true`，但前端不加载 | `PluginLoader.java:214-235,261-267` |
| `main` 类名写错（FQN 拼错、包名与目录不符、忘了 `mvn package`） | `classLoader.loadClass(entryClass)` 抛 `ClassNotFoundException` | 被 `catch (Exception)` 兜住 → `status = "激活失败: <类名>"`，日志 WARN `[plugins] 插件 <id> 激活失败: ...`；`plugin.list` 仍报 `active=true`（见 §3.4 口径 1） | `PluginLoader.java:309-315,340-344` |
| `main` 指向的类存在但没实现 `EveryAgentPlugin` | 接口 `isAssignableFrom` 检查失败 | `status = "入口类未实现 EveryAgentPlugin"`，日志 WARN 同义 | `PluginLoader.java:310-315` |
| 声明了 `main` 但 `target/` 下没有非 sources/javadoc 的 jar | 内置扫描器要求「有 `target/classes/plugin.json` 就得有 jar」 | 内置：WARN「`[plugins-builtin] 内置插件未构建,请先 mvn package: <目录>`」并**整目录跳过**（连纯 web 部分都不加载）；外部：能进目录，但 `lib/` 无 jar → `status = "无 jar 文件"` | `BuiltInPluginScanner.java:90-97`、`PluginLoader.java:269-291` |
| `plugin.json` 的 `version` 与 pom 的 `<version>` 不一致（git 实测：清单 `0.1.0` vs pom `1.0.0`） | 两者**互不校验** | 没有任何影响：清单 version 纯展示；Maven 侧版本由 `pom.xml` 的 `<version>` 决定，产物 jar 名 `git-1.0.0.jar` 也随之而来（实测 `every-agent-plugins/git/target/git-1.0.0.jar`），而扫描器只按 `target/*.jar` **通配**取 jar，不看名字里的版本 | `every-agent-plugins/git/plugin.json`、`every-agent-plugins/git/pom.xml:7-13`、`BuiltInPluginScanner.java:134-152` |
| 改完内置插件的 `plugin.json` 却不生效 | 见 §8：Java 形态读的是 `target/classes/` 里的**副本** | 根清单只有 `enabled` 字段被直接读；其余字段要重跑 `mvn` 复制才更新 | `BuiltInPluginScanner.java:193-202`、`PluginLoader.java:198-212` |

## 8. 与 `pom.xml` 的对应关系

1. **`main` 的 FQN 必须与 `src/main/java` 路径逐段一致**。例：`"main": "dev.everyagent.plugin.git.GitPlugin"` ⇔ `every-agent-plugins/git/src/main/java/dev/everyagent/plugin/git/GitPlugin.java`。改名/挪包时两边一起改，漏改的失败表现见 §7 第 4 行。
2. **`plugin.json` 是被 Maven 复制进 classpath 的**，靠插件 pom 里内联的 `maven-resources-plugin`：`process-resources` 阶段执行 `copy-resources`，把 `${project.basedir}/plugin.json` 复制到 `${project.build.outputDirectory}`（= `target/classes`）。范例见 `every-agent-plugins/git/pom.xml:20-46`（注释原文：「将插件根目录的 plugin.json 复制到 classpath 根目录，供 PluginRegistry 扫描」）。
3. **为什么这件事重要**：内置扫描器**用 `target/classes/plugin.json` 的存在与否来判定「这是 Java 插件」**（`BuiltInPluginScanner.java:90`、`hasTargetClassesPluginJson` `:170-172`），而 `PluginLoader` 读的 manifest 也优先是这份副本（`resolveManifestPath` `:193-202`）。实测 `every-agent-plugins/git/plugin.json` 与 `every-agent-plugins/git/target/classes/plugin.json` 内容一致（`fc` 无差异）。⇒ 对 java / full 形态：**只改根清单不重跑 `mvn`（哪怕 `mvn process-resources`），worker 读到的仍是旧副本**，而 `enabled` 却已经按新值判定 —— 两边半新半旧是这个形态最坑的不一致。
4. **插件不进 Maven reactor**：根 `pom.xml` 的 `<modules>` 只有 `every-agent-contract` / `every-agent-hub` / `every-agent-plugin-api` / `every-agent-plugins/task-edit-resend` / `every-agent-worker`（`pom.xml:21-25`），其余插件要**逐个** `mvn -f every-agent-plugins/<id>/pom.xml package`。命令矩阵与 cwd 陷阱见 [构建与运行](guides/build-and-run.md)。
5. **依赖红线**：插件 pom 只允许 `every-agent-plugin-api`（+ test 作用域的 `spring-boot-starter-test`），**任何 scope 都禁止依赖 `every-agent-worker`**（[`../ARCHITECTURE.md`](../ARCHITECTURE.md) §14.9；`plugin.json` 的 `requires.spi` 帮不上任何忙——它不生效，见 §2.3）。

## 9. 下一步读

- 清单之外，后端能注册什么、生命周期怎么走：[后端模型总览](backend/overview.md)
- `webMain` → `web/index.js` 之后发生的事（bare import 改写、`window.__EA_*`、`activate(ctx)`）：[前端总览与加载链路](web/overview-and-loading.md)
- 三形态各自的构建命令、cwd 如何决定内置插件是否被加载：[构建与运行](guides/build-and-run.md)
- `.eap` 包内部布局与 `plugin.install` 的目录规则（§4 身份 ② 的完整版）：[打包与安装](guides/packaging-and-install.md)
- 现象对不上本文：[故障排查](guides/troubleshooting.md) 与 [已知问题与现状偏差](reference/known-issues.md)
