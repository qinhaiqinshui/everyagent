---
title: 持久化与状态
nav_order: 8
parent: backend
has_children: false
---

# 持久化与状态

**一句话定位**：本篇回答两个问题——**插件的数据放哪**（自持文件 + 自注册 RPC 读回，§7.15.2 的唯一合法姿势）与**插件的状态怎么看**（`ctx.config()` 的真实现状、两种禁用机制、`LoadedPlugin.status` 与 `plugin.list` 的可见性）。先记住一个总事实：后端插件没有任何「宿主替你存」的机制——配置没有用户覆盖层、状态没有中心存储、启停装卸全部重启 worker 才生效；插件要么把状态落成自己管理的文件，要么接受它随进程消失。

## 1. 插件私有数据：唯一合法姿势是自持

### 1.1 `ctx.pluginDir()` 返回什么

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerPluginContext.java:31-37
/**
 * 插件根目录绝对路径（内置插件源码目录或外部插件安装目录）。
 *
 * <p>对标 VSCode 的 {@link ExtensionContext}.extensionPath。
 * 插件可经此定位自带资源（如 rootfs 镜像、脚本等）。
 */
Path pluginDir();
```

实现是纯字段透传：`WorkerPluginContextImpl` 构造时存下 `pluginDir` 字段，`pluginDir()` 直接返回（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/WorkerPluginContextImpl.java:56,95,121-123`）。值的来源是扫描器产出的 `ScannedPlugin(pluginDir, source)`（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/PluginScanner.java:34`），在 `PluginLoader.loadPlugin` 里原样传给上下文构造（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginLoader.java:321-332`）。因此取值只由「哪个扫描器发现了它」决定：

| source | `pluginDir()` 实际指向 | 默认值来源 | 可覆盖配置 |
|---|---|---|---|
| `builtin` | `<工作目录>/every-agent-plugins/<id>/`（即插件源码目录，不是 jar） | `Path.of("every-agent-plugins")` 相对 `user.dir` 取绝对路径（`WorkerProperties.java:257-266`） | `worker.builtin-plugins-dir`（支持 `~` 开头） |
| `external` | `<系统目录>/plugins/<id>/`（默认 `~/.everyagent/plugins/<id>/`） | `resolveHomeDir().resolve("plugins")`，homeDir 缺省 `<user.home>/.everyagent`（`every-agent-worker/src/main/java/dev/everyagent/worker/config/WorkerProperties.java:212-215,247-251`） | `worker.plugins-dir` |

注意内置插件的 `pluginDir()` 指向**源码目录**（`every-agent-plugins/<id>/`）而非构建产物——插件代码在运行期读写它时，写的是源码树里的文件，`mvn clean` 会连带清掉 `target/` 但不会动插件根下的其他文件。以 `pluginDir()` 为锚点定位自带只读资源（脚本、镜像、二进制）是它的设计用途（javadoc 自证）。

### 1.2 真实插件都把数据放在哪（实例清单）

rg 全部 26 个内置插件对 `pluginDir()` 的使用（排除 `target/`），结合各自的写盘点，实践分三类：

| 插件 | 数据 | 落点 | 写侧 | 读回 |
|---|---|---|---|---|
| file-change | 每轮文件变更全文 | 任务数据目录下 `file-changes/<roundId>.json` | `RoundClosedListener` 回调按轮写（`FileChangeAdvisorProvider.java:61,78-81`） | 自注册 RPC `task.fileChanges`（`FileChangePlugin.java:35,53-58`） |
| git | 远端凭证（加密） | `<workspace>/.everyagent/.git-credentials.enc` + 同级密钥 `.git-credential.key` | `git.credential.save` RPC → `GitCredentialStore.save`（`GitCredentialStore.java:26-32,37,143,162-163`；`GitService.java:496-515`） | 认证失败重试时 `GitCredentialStore.load`（`GitService.java:550`） |
| model-rate-limit | token 校准系数/限流状态 | `<homeDir>/model-rate-state.json`（worker 系统目录根，非 pluginDir） | 单线程 writer 异步合并写（`BuiltinTokenEstimator.java:34,45,249`） | 构造时 `load()` 读回接续（`BuiltinTokenEstimator.java:61-69`，路径 = `config.resolveHomeDir().resolve(FILE_NAME)`） |
| subagent | skill 知识包 | `<skillsDir>/agent-dispatch/skill.md` | activate 后物化 classpath 资源（幂等，`SubAgentSkillContributor.java:30-33,38-55`） | 物化是 AI 能 `read_file` 知识包的必要条件；`/` 菜单条目经 `SkillContributorRegistry`（SPI）进入，不再依赖 `ExternalSkillScanner` 兜底（[advisors.md](advisors.md) §5） |
| sandbox-wsl-ubuntu | 自带资源（只读） | `<pluginDir>/wsl/eagent-rootfs.tar.gz`、`<pluginDir>/wsl/eagent-run.py` | 不写，随插件分发 | `WslCommon.tarballFor/resolveRunner`（`WslCommon.java:66-72,128-137`） |
| sandbox-windows-codex / sandbox-windows-mic | 自带 rg 二进制（只读） | `<pluginDir>/bin/rg.exe` | 不写，随插件分发 | `CodexRg.resolve`（`CodexRg.java:29-41`；系统 PATH 已有则不注入） |

归纳出的选址规律（无强制，全是先例约定）：

- **插件自带只读资源** → `pluginDir()` 下（唯一有官方指定语义的位置）；
- **跨任务的全局可写状态** → worker 系统目录（`ctx.services().config()` 即 `WorkerConfig` 的 `resolveHomeDir()`，`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/config/WorkerConfig.java:44`）下**自己独有的文件名**（model-rate-limit 先例）；
- **任务级数据** → 任务数据目录下**自己的子目录**（`dataDirOf` 定位，file-change 的 `file-changes/` 先例）；
- **工作区级数据** → 工作区 `.everyagent/` 下（git 凭证先例，路径经 `workspaces().sandboxFor(...)` 的 root 取得）。

没有任何插件往 `pluginDir()` 里写过运行期状态——仓库现状里它实际是「插件自带资源目录」。你要写可写状态时，照抄上面后三类先例，别发明新位置（尤其别写进 worker 的固定契约文件，见 §1.3）。

### 1.3 为什么不能用 worker 的任务数据存储

任务数据目录（磁盘布局 `<workspaces根>/<workspaceId>/tasks/<taskId>/`，`every-agent-worker/src/main/java/dev/everyagent/worker/task/TaskStore.java:46,54,392`；[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.15）里的**固定文件名是 worker 的落盘契约**：`meta.json`（TaskSummary schema）、`<agentId>.jsonl`（事件空间，seq 由 EventLog 分配）、`rounds.jsonl`（轮次索引）、`queue.jsonl`、`agents.json`。插件业务数据不进这些文件，口径原文：

> **轮行不含任何插件业务字段**,文件变更等按轮旁路数据由插件自持文件 + 插件 RPC 提供,§7.15.2。（出处：[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.15.1）

§7.15.2（`:759-765`）进一步定死读写两侧：写侧「`RoundIndexStore` 闭合轮后经 `RoundClosedListener` 回调按 roundId 写全文分片 `file-changes/<roundId>.json`(轻量摘要与全文同在该文件,不落 rounds.jsonl 行)」；读侧「唯一取数口是插件自己的 `task.fileChanges` RPC」。task 核心零 fileChanges 概念（§14.11 槽位判据：插件功能不进核心接口，collector 连 TaskRuntime 都不进，[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §14.11）。

`WorkerServices` 也不是插件 KV 存储。`store()`（继承自 `TaskServices`，`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/TaskServices.java:15-16`）暴露的 `TaskStoreService` 只收录「插件实际需要的 TaskStore 方法」（悬空队列 queue.jsonl、截断、meta 读写、会话重建，`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/TaskStoreService.java:20-87`）——`readMeta/writeMeta` 是给确实要改 TaskSummary 字段的场景（如 metadata 持久策略标记），拿 meta.json 当插件状态抽屉会污染 worker 的任务列表 schema。两个硬事实约束自持文件的生命周期：

- **编辑重发截断不删插件文件**：`truncateAfterSeq` 原文「只动事件 jsonl，不删任何插件数据文件——file-changes/、agents.json 等插件自有数据残留陈旧条目被接受，后续经截断事件通知（如 task.truncated）由插件自行清理，开放项」（`TaskStoreService.java:38-45`）——陈旧分片清理由插件自己负责；
- **内存状态不跨重启**：per-run 状态放 advisor/节点的实例字段（[advisors.md](advisors.md) §1.5「provider 里存 per-run 状态」坑位），进程重启即失；要跨重启就得落成文件。

### 1.4 按轮分片数据的专用通道：`addRoundClosedListener`

写「每个任务的每轮一条记录」这类数据，有一条非 `register*` 的服务通道（[task-and-rpc.md](task-and-rpc.md) §2.6）：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerServices.java:75-80
/**
 * 注册轮闭合监听器（插件在 activate 时调用）。
 * 当 RoundIndexStore 持久化新闭合轮后，会回调所有已注册的监听器，
 * 传递 taskId、dataDir 和闭合轮信息列表。插件可据此写入按轮分片的数据文件。
 */
void addRoundClosedListener(RoundClosedListener listener);

// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/RoundClosedListener.java:13-22
public interface RoundClosedListener {
    void onRoundsClosed(String taskId, Path dataDir, List<RoundClosedInfo> closedRounds);
}
```

file-change 的写侧实况（唯一使用者）：advisor 收口把 collector 暂存进 provider 的 `pendingCollectors` Map（key=taskId），回调时取出、对每个闭合 roundId 写 `dataDir.resolve("file-changes").resolve(roundId + ".json")`（`every-agent-plugins/file-change/src/main/java/dev/everyagent/plugin/filechange/FileChangeAdvisorProvider.java:55-86`）；读侧 RPC 用 `services.dataDirOf(taskId)` 反定位同一目录，并用 `ROUND_ID_PATTERN` 白名单防路径穿越（`every-agent-plugins/file-change/src/main/java/dev/everyagent/plugin/filechange/FileChangePlugin.java:22,35,53-72`）——**写读两侧都是插件自己的代码，worker 只递目录**。

### 1.5 最小完整姿势（自持文件 + 自注册 RPC）

```java
// 插件全局状态的标准形态：activate 时读盘恢复，RPC 里读写同一文件
public class MyPlugin implements EveryAgentPlugin {
    private Path stateFile;                    // 选址照 §1.2 规律：全局状态放 homeDir 下自己的文件名

    @Override public void activate(WorkerPluginContext ctx) throws Exception {
        stateFile = ctx.services().config().resolveHomeDir().resolve("my-plugin-state.json");
        ctx.registerRpcMethod("myplugin.state", this::rpcState);   // 读回唯一取数口（§7.15.2 口径）
    }

    private void rpcState(RpcContext rc) { /* 读 stateFile → rc.ok(...)；变更时临时文件 + ATOMIC_MOVE 写回 */ }
}
```

要点：① 数据文件名归插件所有，读写都走插件代码；② 原子写（临时文件 + move）是仓库惯例（`TaskStoreService.writeMeta`、`GitCredentialStore.java:143`、rounds.jsonl 闭合改写均如此）；③ 需要广播变更时用语义 `EmitEvent` / 前端订阅宿主领域事件（[task-and-rpc.md](task-and-rpc.md) §5、[web/events.md](../web/events.md)），不要轮询。

## 2. `ctx.config()` 的真实现状：恒等于 default，无用户覆盖

### 2.1 读取链路（全部三步）

```
plugin.json 的 contributes.config.*.default
  → PluginLoader 解析：只取每项的 "default" 键装 Map        PluginLoader.java:248-259
  → new PluginConfigImpl(configDefaults)                     PluginLoader.java:259
  → activate(ctx) 时经 ctx.config() 交给插件                 PluginLoader.java:321-333
```

`PluginConfigImpl` 的类注释逐字自证现状（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/PluginConfigImpl.java:7-12`）：

> PluginConfig 实现 —— 从 plugin.json contributes.config 读取配置项。
> 阶段二：从内存 Map 读取（plugin.json 解析时填充默认值）。**后续可扩展为从用户设置文件读取覆盖值。**

三个随之而来的现状边界（全部已核实）：

- **不存在 `SettingsStore`**（全仓 rg 零命中）：没有用户设置层、没有运行时写盘路径。`ctx.config().getXxx(key, def)` 拿到的值**恒等于清单里写的 `default`**；插件侧传的 `def` 参数只在该键无 default 时兜底。
- **`type` / `description` 字段无人消费**：`PluginLoader` 对每个配置项只 `path("default")`（`PluginLoader.java:253`）；worker main 代码里 `contributes` 的全部命中只有 PluginLoader 这一处解析 + `PluginDescriptorParser` 的纯模型解析（模型字段无任何调用方，见 [plugin-manifest.md](../plugin-manifest.md)）。写了只是给未来的设置界面留的元数据。
- **改 default 必须重启 worker**：config Map 在 `activate()` 之前的加载期构造（`PluginLoader.java:248-259`），此后没有任何热刷新或 setter——改 plugin.json 的 default 后，当前进程里的插件读到的仍是旧值。

`PluginConfig` 接口共 4 个方法（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/PluginConfig.java:9-17`）：`getString(key, def)` / `getInt(key, def)` / `getBoolean(key, def)` / `<T> get(key, Class<T>, def)`。类型宽容：`getInt` 接受数字或可解析字符串，解析失败回落默认值（`PluginConfigImpl.java:28-43`）。

### 2.2 两个真实用例（仅有的两个 `contributes` 使用者）

| 插件 | 键数 | 声明（plugin.json） | 取值 |
|---|---|---|---|
| image-vision | 3 | `image-vision.enabled`（boolean，default true）/ `image-vision.max-size-mb`（number，default 10）/ `image-vision.extensions`（string，default `.png,.jpg,...`） | `ImageVisionSettings.from(ctx.config())` 一次性解析成 record（`ImageVisionPlugin.java:29`；`ImageVisionSettings.java:24-31`，非法值回落默认再拼装） |
| sandbox-windows-codex | 6 | `codex.home` / `codex.account-prefix` / `codex.network-policy` / `codex.proxy-ports` / `codex.allow-local-binding` / `codex.java-home` | `CodexSandboxOptions.from(ctx.config(), props)`（`CodexSandboxPlugin.java:42`；`CodexSandboxOptions.java:54-64`，与 WorkerConfig 合并出完整选项） |

值得抄的两个习惯：① 键名用**全限定前缀**（`image-vision.*` / `codex.*`）——虽然 config Map 是每插件一份不会串键，但保持前缀让未来的全局设置界面可直接平铺；② 在 activate 里一次性解析成不可变 record 存字段，别在运行期反复 `config.getXxx`（config 对象本身不会变，解析一次就够）。

**当前想让用户改这些值的唯一途径是改 plugin.json 的 default 再重启 worker**（未实测其他途径；无 RPC、无设置文件）。

## 3. 禁用双机制：`enabled=false` vs `.disabled-plugins`

两套机制作用在加载链的不同阶段，可见性与生效面都不同。

### 3.1 `enabled=false`（plugin.json 字段）——扫描期整目录跳过，两把扫描器统一读

内置与外部扫描器都在扫描期读根 `plugin.json` 的 `enabled` 字段，语义完全一致：

- 判定（`BuiltInPluginScanner.isEnabled` 与 `ExternalPluginScanner.isEnabled` 同款实现）：`json.path("enabled").asBoolean(true)`——**缺省视为 true，解析失败也视为 true**（宽容，不因格式错误阻止加载）；
- false → 打 INFO「插件已禁用(enabled=false)，跳过」并整目录 `continue`——插件**根本不进扫描结果**，`PluginLoader` 无从加载，`plugin.list` 里**完全不出现**（连「已禁用」条目都没有）。

内置侧由 `BuiltInPluginScanner.scan()` 先查根 plugin.json（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/BuiltInPluginScanner.java:79-84`，刻意放在 `target/` 判定之前，防已构建残留误加载）；外部侧由 `ExternalPluginScanner.scan()` 在纳入前同样检查（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/ExternalPluginScanner.java`）——外部插件想「装上但默认禁用」，在 plugin.json 里写 `"enabled": false` 即可，不再需要 `.disabled-plugins` 或删目录绕行。

### 3.2 `.disabled-plugins`（PluginStateStore）——加载期不激活，但仍登记

- **文件位置**：`<pluginsDir>/.disabled-plugins`，默认 `~/.everyagent/plugins/.disabled-plugins`（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/PluginStateStore.java:38,103-104`；pluginsDir 解析见 §1.1）——注意它放在**外部插件目录**下，但管的是**全部插件**（含内置）的禁用名单。
- **格式**：纯文本，每行一个插件 id；`#` 开头注释行与空行忽略（`PluginStateStore.java:74-88`）；落盘时按 id 排序写（`:93-98`）。构造时即读盘恢复（`:44-49`）。
- **生效方式**：`PluginLoader` 加载每个插件时查名单，命中则**不调 activate 但仍登记**（`PluginLoader.java:238-246`）——`LoadedPlugin(active=false, status="已禁用(未激活)")`，代码注释自证理由：「仍登记进已加载清单(不激活),否则扩展管理面板看不到它,也就无法再启用」。⇒ `plugin.list` 里**可见**，只是不激活。
- **改动途径**：`plugin.enable` / `plugin.disable` RPC（经 `PluginRegistry` 委托 `PluginStateStore`，改内存 + 立即落盘，`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginRpcMethods.java:157-184`）。两者返回文案自证生效时机：「插件已启用，重启 worker 后生效」（`:167`）、「插件已禁用，重启 worker 后生效」（`:182`）。

### 3.3 对照表

| 维度 | `enabled=false`（plugin.json） | `.disabled-plugins` 文件 |
|---|---|---|
| 阶段 | 扫描期（两把扫描器统一整目录跳过，`BuiltInPluginScanner.java:81-84` / `ExternalPluginScanner.java`） | 加载期（不 activate 但登记，`PluginLoader.java:238-246`） |
| 谁读 | BuiltInPluginScanner 与 ExternalPluginScanner **统一读**（各自 `isEnabled`，缺省视为 true） | `PluginStateStore`（内置/外部统一管） |
| `plugin.list` 是否可见 | ❌ 完全不出现 | ✅ 出现，`disabledIds` 含其 id |
| 对外部插件是否生效 | ✅ 生效（扫描期整目录跳过，与内置同语义） | ✅ 生效 |
| 当前进程内立即生效？ | ❌（配置是启动期读的） | ❌（已激活的贡献留在注册表里，`PluginStateStore.java:29` javadoc 自证） |
| 恢复方式 | 改回 plugin.json + 重启 | `plugin.enable` RPC（或手删文件行）+ 重启 |
| 适合场景 | 随分发包声明「默认不启用」（内置 sandbox-windows-mic、sandbox-wsl-ubuntu 的 `enabled:false` 先例；外部插件同样支持） | 用户运行期自主关停某插件 |

**均须重启 worker 才真正生效**：禁用对当前进程不回收任何已注册贡献（deactivate 只在 worker 优雅关闭时调用，§5.1）；启用同理——本轮进程里它从未被 activate，重启才会走加载链。

### 3.4 前端 `plugin.list` 的 `disabledIds` 来源

`plugin.list` 应答在 `plugins` 数组外附顶层 `disabledIds` 数组（`PluginRpcMethods.java:96-99`，值来自 `PluginRegistry.disabledIds()` → `PluginStateStore.disabledIds()`）。前端唯一消费方是 plugin-manager 插件的管理面板：用 `disabledIds` 判定开关态，切换时本地乐观更新（`every-agent-plugins/plugin-manager/web/PluginManagerPanel.tsx`）——面板上的开关变化**只反映名单文件变化**，插件实际停没停要看 worker 是否重启过（§3.3）。

## 4. 插件状态的可见性：排查时看哪里

### 4.1 `LoadedPlugin.status` 文案全集（逐字抄自 PluginLoader）

| status 文案 | active | 触发条件 | 证据（文件:行号） |
|---|---|---|---|
| `已禁用(未激活)` | false | 命中 `.disabled-plugins` 名单 | `PluginLoader.java:243-244` |
| `声明式插件` | true | 无 `main` 入口类（纯清单贡献） | `PluginLoader.java:265-266` |
| `lib 目录不可读: <消息>` | false | 外部插件 `lib/` 列举失败 | `PluginLoader.java:280-281` |
| `无 jar 文件` | false | 声明了 main 但找不到 jar（内置看 target/，外部看 lib/） | `PluginLoader.java:287-289` |
| `入口类未实现 EveryAgentPlugin` | false | 反射加载成功但类型检查不过 | `PluginLoader.java:312-314` |
| `已激活(内置)` / `已激活` | true | activate() 正常返回（按 source 区分文案） | `PluginLoader.java:335-336` |
| `激活失败: <消息>` | false | activate() 抛异常（不影响其他插件） | `PluginLoader.java:341-343` |

### 4.2 `plugin.list` RPC 返回字段

每个插件条目（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginRpcMethods.java` 的 `list`）：`id` / `name` / `version` / `description` / `author` / `source`（`builtin` 或 `external`）/ `active`（=「不在禁用名单」，旧语义，见 §4.3）/ `status`（**加载期实际状态文案**，透传自 `LoadedPlugin.status`，§4.1 那张表的取值逐字出网）/ `hasMain`（main 非空）/ `hasWebMain`（webMain 非空）/ `webMain`（原始值，前端据此推导 web 产物路径）；顶层另附 `disabledIds`（§3.4）。

### 4.3 `active` 与 `status` 的分工（一个旧语义陷阱）

- **`status` 出网**：`PluginRegistry` 聚合时把 `LoadedPlugin.status` 透传进 `PluginManifest.status`，`plugin.list` 原样序列化——「已激活」「激活失败: …」「已禁用(未激活)」「无 jar 文件」等文案可直接从 RPC 应答读到，排查「插件为什么没生效」不再需要翻 worker 日志（日志仍是最全的报错详情来源）。
- **`active` ≠ 已激活**：plugin.list 的 `active` 取自 `!pluginRegistry.isDisabled(m.id())`——只反映禁用名单（保持旧语义以兼容既有前端）。一个**激活失败**（status=激活失败）的插件，只要不在 `.disabled-plugins` 里，plugin.list 照样报 `active=true`。判断「真的跑起来了没有」以 `status` 为准（是否以「已激活」开头）。

排查路径速查：

| 现象 | 先看哪 |
|---|---|
| plugin.list 里根本没有它 | 根 plugin.json 是否 `enabled:false`（§3.1）；目录是否缺 plugin.json；内置是否没构建（WARN「内置插件未构建,请先 mvn package」，`BuiltInPluginScanner.java:95`） |
| plugin.list 有它但功能没生效 | plugin.list 的 `status` 字段（§4.1 文案全集）是「激活失败」「无 jar 文件」还是「已禁用(未激活)」；报错详情再看 worker 日志 grep `[plugins] id=<id>` |
| 禁用了还在跑 | 本进程激活过的贡献不会回收（§3.3/§5.1），重启 worker |
| 配置改了不生效 | config 是启动期从 default 构造的（§2.1），重启 worker |

## 5. 热边界：重启才生效的那些事

### 5.1 deactivate 只在 worker 关闭时调用

API 声明的 `default void deactivate() {}`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/EveryAgentPlugin.java`）**仅在 worker 优雅关闭时**由 `PluginLoader` 销毁阶段（`@PreDestroy`）对 activate 成功的插件逐个调用（激活失败的插件跳过；单个停用抛异常只 WARN）。运行期禁用/卸载**不触发**它，`PluginStateStore` 类注释自证的设计现状依然成立（`PluginStateStore.java:29-30`）：

> 名单变更对<b>下一次 worker 启动</b>完全生效(已激活的插件在当前进程内贡献留在注册表里)。

推论：禁用/卸载**不回收任何已注册贡献**——已注册的 SPI、RPC 方法、监听器在当前进程里全部留着，要等重启才随进程消失。插件不要指望「运行期停用时清理」的时机存在；可回滚逻辑要做在数据侧（如 file-change 靠截断事件自行清理陈旧分片，§1.3）。前端同样从不 dispose 插件（宿主 `loadedPlugins` 的 disposables 恒空，见 [web/overview-and-loading.md](../web/overview-and-loading.md)）。

### 5.2 升级插件 = 替换文件 + 重启 worker

`plugin.install` / `plugin.uninstall` 的返回文案逐字都是「重启 worker 后生效」（`PluginRpcMethods.java:126,152`）。原因同构：类与 jar 已被 `URLClassLoader` 载入当前进程，同进程内不会换新。实操口径：

- **内置插件**：改源码 → `mvn package`（产物落 `target/`）→ 重启 worker；web 侧改动还要 `npm run build:plugins` + 刷新页面（[guides/build-and-run.md](../guides/build-and-run.md)）。
- **外部插件**：替换 `<pluginsDir>/<id>/` 目录（或走 `.eap` 解压布局）→ 重启 worker。

（jar 文件被进程占用导致 Windows 上无法覆盖替换——未实测，不做断言；稳妥做法是先停 worker 再换文件。）

### 5.3 同名多实例：builtin 优先

两个扫描器都发现同一 id 时：加载前先 `scanned.sort`（builtin 排前，稳定排序，`PluginLoader.java:176-178`），加载循环里对已加载 id 去重——后到者打 WARN「插件 <id> 已加载,跳过重复」直接 return（`PluginLoader.java:221-224`）⇒ **同名时内置赢，外部被跳过**。一个推论：内置插件被 `enabled=false` 干掉后（扫描期就没进列表，§3.1），同名外部插件**才会**被加载——想用外部版覆盖内置版，除了放目录还要把内置的 enabled 关掉。

### 5.4 plugin.json 双份时扫描器用哪份

Java 插件 `mvn package` 后同时存在根 `plugin.json` 与 `target/classes/plugin.json`（maven-resources-plugin 在 process-resources 复制，见 [plugin-manifest.md](../plugin-manifest.md)）。两者读法**不对称**：

| 用途 | 读哪份 | 证据 |
|---|---|---|
| `enabled` 判定 | **根 plugin.json** | `BuiltInPluginScanner.java:79-81`（`dir.resolve("plugin.json")` → `isEnabled`）；类注释明言「不依赖 target/ 是否存在，避免已构建但未清理的 target/ 残留导致禁用插件被误加载」 |
| java/web 形态判定 | `target/classes/plugin.json` 是否存在 | `BuiltInPluginScanner.java:90`（`hasTargetClassesPluginJson`，`:170-172`） |
| manifest 内容（id/main/webMain/contributes…） | **target/classes/plugin.json 优先**，无则根 | `resolveManifestPath`（`BuiltInPluginScanner.java:193-204`）；PluginLoader 按 source 分派调用（`PluginLoader.java:199-201`） |

⇒ **改了根 plugin.json 忘了重新 `mvn package`，字段变更不生效**（加载期读的还是 target 里的旧清单）；只有 `enabled` 例外——它永远看根。这是「我明明改了清单为什么没变化」的最常见根因（[guides/troubleshooting.md](../guides/troubleshooting.md) 排查表同款条目）。

## 下一步读

- 三形态构建矩阵、`worker.builtin-plugins-dir` 按 cwd 解析的坑、改 web 后必须手工 `build:plugins`：[构建与运行](../guides/build-and-run.md)
- 以 `LoadedPlugin.status` 与 worker WARN 文案逐条为行的「现象→根因→修复」排查表：[故障排查](../guides/troubleshooting.md)
- 前端 `ctx.storage` 对照（localStorage、键前缀 `plugin:<pluginId>:`、无跨标签联动）——前端插件有自己的轻量持久化通道：[前端上下文 API](../web/context-api.md)
