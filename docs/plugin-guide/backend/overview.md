---
title: 后端插件总览
nav_order: 4
parent: backend
has_children: false
---

# 后端插件总览

**一句话定位**：本篇讲 worker 侧插件的**完整运行模型**——plugin.json 目录怎么被两把扫描器发现、jar 怎么被每插件一个 `URLClassLoader` 隔离加载、入口类怎么被反射实例化并 `activate()`、15 个 `ctx.register*` 注册点怎么落进各注册中心，以及单个插件失败为什么拖不垮 worker。写具体扩展点之前，先把这篇读一遍。

## 1. 一图流：从目录扫描到注册生效

```
worker 启动（Spring 容器装配完成 → PluginLoader.init() @PostConstruct，全程只跑一次）
   │
   ├─ BuiltInPluginScanner.scan()                ┐ 两把扫描器（List<PluginScanner> 由 Spring 注入聚合）
   │   根目录 = worker.builtin-plugins-dir（默认 <user.dir>/every-agent-plugins/）
   │   一级子目录按名称排序，逐个判定：
   │     ├─ 根无 plugin.json → 不是插件，跳过
   │     ├─ enabled=false → INFO 跳过（在 target/ 判定之前，防残留误加载）
   │     ├─ 有 target/classes/plugin.json（java 插件）→ 还须 target/ 下有非 sources/javadoc 的 jar
   │     │     └─ 无 jar → WARN「内置插件未构建,请先 mvn package」整目录跳过
   │     └─ 无 target 产物 → 判为纯 web 插件，直接纳入            → source="builtin"
   ├─ ExternalPluginScanner.scan()               ┘
   │   根目录 = worker.plugins-dir（默认 <home>/plugins，<home> 默认 ~/.everyagent）
   │   一级子目录根含 plugin.json 即纳入（不读 enabled）           → source="external"
   ▼
 PluginLoader.scanAndLoad()
   ├─ 单个扫描器抛异常 → WARN 后继续（扫描器级隔离）
   ├─ 结果按 source 排序：builtin 在前 → 同名 id 冲突时内置赢，外部被去重跳过
   ▼ 逐个 loadPlugin()
   ├─ 解析 manifest（builtin：target/classes/plugin.json 优先，否则根清单；external：根 plugin.json）
   ├─ id = 清单 id，空则回退目录名；与已加载 id 重复 → WARN 跳过
   ├─ 在 .disabled-plugins 禁用名单 → 登记 status=「已禁用(未激活)」，不 activate
   ├─ contributes.config.*.default → PluginConfig（内存 map，无用户覆盖、无写盘）
   ├─ 无 main（且无旧格式 provides.spi.EveryAgentPlugin 回退）→ 声明式插件，不跑任何 Java
   ├─ 收集 jar（builtin：target/*.jar；external：lib/*.jar 排序）→ 无 jar → status=「无 jar 文件」
   ├─ new URLClassLoader(jars, parent=worker 自身类加载器)         ← 每插件一个，插件间互不可见
   ├─ 反射：loadClass(main) → 校验实现 EveryAgentPlugin → 无参构造 newInstance()
   └─ plugin.activate(new WorkerPluginContextImpl(...))
        └─ ctx.register* → 各注册中心（ToolProviderRegistry / AdvisorProviderRegistry / …）→ 生效
```

证据主干：`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginLoader.java:151-187`（init + 扫描排序循环）、`:192-345`（单插件加载全流程 `loadPlugin`）。逐条行号见后文各节。

## 2. 两把扫描器与目录解析

### 2.1 判定条件对照

| | 内置扫描器 | 外部扫描器 |
|---|---|---|
| 实现类 | `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/BuiltInPluginScanner.java` | `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/ExternalPluginScanner.java` |
| 扫描根 | `worker.builtin-plugins-dir`（`BuiltInPluginScanner.java:57`） | `worker.plugins-dir`（`ExternalPluginScanner.java:37`） |
| 默认值 | **`user.dir` 下的 `every-agent-plugins/`**（`WorkerProperties.resolveBuiltinPluginsDir`，`every-agent-worker/src/main/java/dev/everyagent/worker/config/WorkerProperties.java:256-266`；相对路径按 JVM 工作目录解析，**cwd 决定插件是否被加载**） | `<系统目录>/plugins`，系统目录默认 `~/.everyagent`（`WorkerProperties.resolvePluginsDir:246-250` + `resolveHomeDir:212-216`） |
| 配置值语法 | 空白=默认；`~` 开头展开为 `user.home`；否则字面路径（`WorkerProperties.java:256-266`） | 空白=默认；否则字面路径（`:246-250`） |
| 一级子目录判定 | 根 `plugin.json` 存在且 `enabled!=false`，再按 `target/` 产物分流（§2.2） | 根 `plugin.json` 存在即纳入，**不读 `enabled`**（`ExternalPluginScanner.java:43-50`） |
| source 标记 | `"builtin"` | `"external"` |
| 根目录不存在 | WARN 并返回空（`BuiltInPluginScanner.java:59-61`） | INFO 并返回空（`ExternalPluginScanner.java:38-41`） |

`<home>` 解析顺序：`worker.home-dir` 显式配置优先（`WorkerProperties.java:29` 字段 + `:212-216` 实现）；空白时回退 `<user.home>/.everyagent`。`worker.plugins-dir` 在其下拼 `plugins`。

### 2.2 内置扫描的 target/ 分流（java / 纯 web 两种命运）

`BuiltInPluginScanner.scan()` 对每个一级子目录按序做三件事（`BuiltInPluginScanner.java:79-101`）：

1. **先查根 `plugin.json` 的 `enabled`**：`enabled=false` → INFO `[plugins-builtin] 插件已禁用(enabled=false),跳过: {}` 后整目录跳过（`:81-84`）。这一步刻意放在 `target/` 判定**之前**——源码注释明说是「避免已构建但未清理的 target/ 残留导致禁用插件被误加载」（类注释 `:26-28`）。`enabled` 缺省或解析失败都视为 `true`（`isEnabled`，`:117-125`，失败时 WARN `[plugins-builtin] 解析 plugin.json 失败,视为已启用: {}`）。
2. **有 `target/classes/plugin.json` = java 插件**：还要求 `target/` 下至少有一个非 `-sources`/`-javadoc` 的 jar（`hasTargetJars:160-162`、`findTargetJars:134-152`，排除后排序）；不满足 → WARN `[plugins-builtin] 内置插件未构建,请先 mvn package: {}` 并**整目录跳过**（连纯 web 部分都不加载）（`:90-97`）。
3. **无 `target/` 产物 = 纯 web 插件**：直接纳入，根 `plugin.json` 即清单（`:98-101`）。

收尾日志：`[plugins-builtin] 扫描到 {} 个内置插件 (共 {} 个子目录)`（`:103`）；外部侧对应 `[plugins-external] 扫描到 {} 个外部插件`（`ExternalPluginScanner.java:55`）。

⚠️ `findTargetJars` 把 `target/` 下所有合格 jar **按字典序全部**塞进插件类加载器：升级版本后残留的旧 jar 会因排序在前而**遮蔽新类**（表现是「已 package、已重启，改动毫无效果」）——改内置插件一律 `clean package`，且 worker 正在运行锁定旧 jar 时必须先停 worker 再构建（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.17「装载前置」段，行 856）。

## 3. 生命周期与时序

### 3.1 时机：`@PostConstruct` 一次跑完

`PluginLoader.init()` 标注 `@PostConstruct`（`PluginLoader.java:151-154`），worker 启动时同步执行 `scanAndLoad()`：

1. 遍历注入的 `List<PluginScanner>`（Spring 自动聚合全部 `PluginScanner` bean）逐个 `scan()`；单个扫描器抛异常只 WARN `[plugins] 插件扫描器 {} 扫描失败,跳过: {}` 后继续（`:164-168`）。
2. 收集结果按 source 排序，`"builtin"` 在前（`List.sort` 稳定排序，`:176-178`）→ **同名 id 冲突时内置优先**；随后逐个 `loadPlugin(sp)`（`:182-184`）。
3. 收尾 INFO：`[plugins] 扫描到 {} 个插件(来自 {} 个扫描器)`（`:180`）与 `[plugins] 加载完成: {}/{} 个插件成功加载`（`:186`）。

**`activate()` 的调用时机**：每个插件在 `loadPlugin` 内被反射实例化后**立刻**调用（`PluginLoader.java:318→333`），此时 worker 的全部 Spring bean（注册中心、`WorkerServices`、`RpcDispatcher`）均已就绪，但任务尚未开始执行——所以 activate 里可以放心注册任何扩展点，但**不要**在 activate 里做重探测（沙箱后端的时序红线案例见 [tools-and-sandbox.md](tools-and-sandbox.md) §3.3）。

### 3.2 deactivate 只在 worker 关闭时调用：改动 = 重启 worker

接口 `EveryAgentPlugin` 声明了 `default void deactivate() {}`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/EveryAgentPlugin.java`），**仅在 worker 优雅关闭时**由 `PluginLoader` 的销毁阶段（`@PreDestroy`）逐个调用——只调 **activate 成功**的插件（激活失败、被禁用名单命中、声明式插件从未 activate，一概跳过）；单个插件停用抛异常只 WARN，不影响其余插件。除此之外 worker 没有任何运行期卸载、重载、启停插件的路径：

- 改 `plugin.json`、改插件代码、`plugin.enable`/`plugin.disable`、装/卸外部插件，**全部要重启 worker 才生效**（禁用机制的差异详见 [persistence-and-state.md](persistence-and-state.md)）；
- 运行期禁用/卸载**不触发** `deactivate()`（名单变更对下一次 worker 启动生效）；线程池、临时文件等资源的清理可以放 `deactivate()`，但要接受异常退出（强杀/崩溃）时它不会被调用——关键数据落盘别依赖它。

## 4. 目录与形态约定

```
# 内置插件（源码形态，Maven target/ 产物即「jar 仓库」）
every-agent-plugins/
├─ system-info/                     # java-only：最小形态
│  ├─ plugin.json                   # 清单（enabled 在扫描期读的是这一份）
│  ├─ pom.xml                       # parent=every-agent-parent，内联 maven-resources-plugin 复制清单
│  ├─ src/main/java/dev/everyagent/plugin/sysinfo/
│  │  ├─ SystemInfoPlugin.java      # main 指向的入口类
│  │  ├─ SystemInfoAdvisorProvider.java
│  │  └─ SystemInfoAdvisor.java
│  └─ target/                       # mvn package 产物（被 git 忽略）
│     ├─ classes/plugin.json        # ← 加载期实际读的 manifest（resolveManifestPath 优先它）
│     └─ system-info-1.0.0.jar      # ← URLClassLoader 的 jar 来源
├─ pdf-viewer/                      # 纯 web：只有 plugin.json + web/，无 target/ 也纳入
└─ git/                             # java+web：main + webMain 并存

# 外部插件（安装形态，lib/ 放 jar）
~/.everyagent/plugins/              # = <home>/plugins（worker.plugins-dir 可覆盖）
└─ my-plugin/
   ├─ plugin.json
   └─ lib/
      └─ my-plugin-1.0.0.jar        # ← 命名自由，只要 *.jar；多个 jar 按文件名排序全量加载
```

两种形态在 `loadPlugin` 里的唯一分派差异（`PluginLoader.java:198-201` manifest 路径、`:271-286` jar 列表）：builtin 用 `BuiltInPluginScanner.resolveManifestPath`（`target/classes/plugin.json` 优先，否则根清单，`:193-202`）+ `findTargetJars`（`target/*.jar`）；external 用根 `plugin.json` + `lib/*.jar`（`Files.list` 后按名排序）。

## 5. `EveryAgentPlugin` 契约与最小入口类

接口全貌（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/EveryAgentPlugin.java`，共 3 个方法）：

| 方法 | 签名行 | 语义 |
|---|---|---|
| `id()` | `:14` | 插件 id，必须与 plugin.json 的 `id` 一致（仅允许 `[a-z0-9-]`，类注释 `:13`） |
| `activate(ctx)` | `:22` | 激活：拿 `WorkerPluginContext`，注册全部扩展点；**抛异常只废自己一个插件**（Javadoc `:18-21` 原文：「单个插件失败不影响其他插件」） |
| `deactivate()` | `:25` | 停用钩子，默认空实现；**worker 优雅关闭时由 PluginLoader 调用**（仅 activate 成功的插件，§3.2），运行期禁用/卸载不触发 |

最小可运行入口类（只依赖 `every-agent-plugin-api`，Spring AI 工具注解经 plugin-api 传递引入——`every-agent-plugin-api/pom.xml:20-33` 带 `spring-ai-model`/`spring-ai-client-chat`/`every-agent-contract`）：

```java
// every-agent-plugins/my-first/src/main/java/dev/everyagent/plugin/myfirst/MyFirstPlugin.java
package dev.everyagent.plugin.myfirst;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public class MyFirstPlugin implements EveryAgentPlugin {

    @Override
    public String id() {
        return "my-first";                    // 必须与 plugin.json 的 id 一致
    }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        // 一切注册都发生在这里：工具 / Advisor / RPC / 生命周期节点……见 §6 总表
        ctx.registerToolProvider(new MyToolProvider());
        // deactivate() 可选：仅在 worker 优雅关闭时被调用（§3.2），无需清理可省略
    }

    /** 最小 ToolProvider：每次 agent 装配时给 AI 注入一个 hello 工具。 */
    static final class MyToolProvider implements ToolProvider {
        @Override public String pluginId() { return "my-first"; }

        @Override
        public List<ToolCallback> createTools(ToolContext ctx) {
            // @Tool 注解类 → ToolCallback 的批量转换由 Spring AI 提供（复用框架，不自搓）
            return List.of(ToolCallbacks.from(new MyTools()));
        }
    }

    static final class MyTools {
        // 描述写给模型看，直接决定模型会不会用这个工具
        @Tool(description = "打个招呼：原样返回输入的名字")
        public String hello(@ToolParam(description = "名字") String name) {
            return "hello, " + name;
        }
    }
}
```

配套 `plugin.json` 只需 `id/name/version/description/author/main/enabled` 七个字段（逐字段语义见 [plugin.json 字段参考](../plugin-manifest.md)）。

## 6. 扩展点总表（导航枢纽）

`WorkerPluginContext` 自身 13 个注册方法（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerPluginContext.java:42-94`：10 个 SPI + 3 个通用）+ 父接口 `TaskPluginContext` 2 个（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/task/TaskPluginContext.java:17,24`）= **15 个注册方法**。每个注册在 worker 侧的实现都只是往对应注册表 `add` 一行（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/WorkerPluginContextImpl.java:126,131,136,156,187`），注册即生效、无过滤、无校验。

### 6.1 注册方法（15 个）

| # | 注册方法（接口行号） | SPI / 接口 | 一句话用途 | 详解 | 内置范例（activate 行号） |
|---|---|---|---|---|---|
| 1 | `registerToolProvider`（`WorkerPluginContext.java:42`） | `ToolProvider` | 给 AI 注入工具（Spring AI ToolCallback） | [tools-and-sandbox.md](tools-and-sandbox.md) | subagent（`SubAgentPlugin.java:31`）、sandbox-windows-codex（`:51`） |
| 2 | `registerToolExecutionInterceptor`（`:57`） | `ToolExecutionInterceptor` | 拦截每轮工具执行：短路 / 改写结果 | [tools-and-sandbox.md](tools-and-sandbox.md) | secret-redaction（`SecretRedactionPlugin.java:22`）、unattended（`:20`） |
| 3 | `registerSandboxProvider`（`:48`） | `SandboxProvider` | 提供沙箱后端（挂载 / 清理 / 探测） | [tools-and-sandbox.md](tools-and-sandbox.md) | sandbox-windows-mic（`:25`）、sandbox-windows-codex（`:47`）、sandbox-wsl-ubuntu（`:37`） |
| 4 | `registerFileReferenceHandler`（`:69`） | `FileReferenceHandler` | 按扩展名处理输入里的 `@` 文件引用 | [tools-and-sandbox.md](tools-and-sandbox.md) | image-vision（`ImageVisionPlugin.java:30`，唯一） |
| 5 | `registerAdvisorProvider`（`:45`） | `AdvisorProvider` | 向模型调用链贡献 Advisor | [advisors.md](advisors.md) | system-info（`SystemInfoPlugin.java:16`）、git（`:33`）、context-compression（`:18`）等 |
| 6 | `registerSearchProvider`（`:51`） | `SearchProvider` | 搜索后端 | [advisors.md](advisors.md) | **零插件使用**（rg 全仓 `registerSearchProvider` 仅接口与实现自身） |
| 7 | `registerAuthorizationHandler`（`:54`） | `AuthorizationHandler` | 授权决议链节点（无人值守 / AI 审议） | [advisors.md](advisors.md) | unattended（`UnattendedPlugin.java:23`）、ai-review（`:24`） |
| 8 | `registerSkillContributor`（`:60`） | `SkillContributor` | 向 system prompt 与 `/` 菜单贡献 skill | [advisors.md](advisors.md) | subagent（`SubAgentPlugin.java:44`） |
| 9 | `registerTokenEstimator`（`:63`） | `TokenEstimator` | 替换内置 Token 估算器 | [advisors.md](advisors.md) | model-rate-limit（`ModelRateLimitPlugin.java:35`） |
| 10 | `registerChatModelEnhancer`（`:66`） | `ChatModelEnhancer` | 模型构建增强（如模型池容灾） | [advisors.md](advisors.md) | model-pool（`ModelPoolPlugin.java:26`） |
| 11 | `registerRpcMethod`（`:79`） | `RpcMethod` | 自定义 RPC 方法（`域.动作` 命名） | [task-and-rpc.md](task-and-rpc.md) | git（`GitPlugin.java:42-54`，13 个）、task-queue（`:29`）、subagent（`:35`）、file-change（`:35`） |
| 12 | `registerSlashProvider`（`:87`） | `SlashProvider` | `/` 菜单命令提供者 | [task-and-rpc.md](task-and-rpc.md) | unattended（`:26`）、git（`:36`）、ai-review（`:27`）、sandbox-wsl-ubuntu（`:44`） |
| 13 | `registerSlashTokenResolver`（`:94`） | `SlashTokenResolver` | slash token 提交解析 | [task-and-rpc.md](task-and-rpc.md) | 同上四者（unattended `:30` / git `:39` / ai-review `:31` / wsl `:45`） |
| 14 | `registerTaskAdmissionPolicy`（`TaskPluginContext.java:17`） | `TaskAdmissionPolicy` | task.run RPC 边缘预检（如放行排队代替 ERR_BUSY） | [task-and-rpc.md](task-and-rpc.md) | task-queue（`TaskQueuePlugin.java:25`） |
| 15 | `registerTaskLifecycleNode`（`TaskPluginContext.java:24`） | `TaskLifecycleNode` | 任务生命周期洋葱链节点 | [task-and-rpc.md](task-and-rpc.md) | task-queue（`:24`）、task-input-queue（`:27-28`）、subagent（`:39`）、task-edit-resend（`:25`） |

### 6.2 辅助方法（5 个，不注册、只取数）

| 方法 | 接口行号 | 语义 |
|---|---|---|
| `pluginId()` | `WorkerPluginContext.java:29` | 本插件 id |
| `pluginDir()` | `:37` | 插件根目录绝对路径（对标 VSCode `ExtensionContext.extensionPath`）——**插件私有数据唯一合法落点**，见 [persistence-and-state.md](persistence-and-state.md) |
| `services()` | `:99` | 取 `WorkerServices` 只读服务（§6.3） |
| `config()` | `:102` | 插件配置，恒等于 plugin.json 的 `contributes.config.*.default`（无用户覆盖、无写盘，见 [plugin.json 字段参考](../plugin-manifest.md) §2.2） |
| `getService(Class)` | `:114` | 按 Class 直取 Spring bean（`WorkerPluginContextImpl.java:230-231` = `applicationContext.getBean(type)`）；Javadoc 自述定位：内置插件取未公开服务的后门，「外部插件不应依赖此方法获取未公开的服务」（`:105-112`） |

### 6.3 `WorkerServices`：能取到什么（逐个列）

`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerServices.java:26`（对标 VSCode 的 `vscode.*` 命名空间——插件只面向此接口编程，不依赖 worker 实现类）：

| 方法 | 行号 | 用途 |
|---|---|---|
| `sandbox()` | `:29` | 沙箱门面（SandboxBackend）：查 `id()` 判后端、委托挂载 |
| `nativeExec()` | `:32` | 宿主原生进程执行器（argv 直传 + 超时 + 输出上限；git 等受控操作用） |
| `workspaces()` | `:35` | 工作区管理器（多工作区注册表、jailed 路径解析） |
| `tokenEstimator()` | `:38` | Token 估算器（内置或插件注册的自定义实现） |
| `interaction()` | `:41` | 用户交互服务（ask 提问 / 授权弹窗） |
| `config()` | `:44` | worker 全局配置只读视图（`WorkerConfig`，即 `worker.*`；≠ `ctx.config()`） |
| `ids()` | `:47` | ID 生成器（单调递增 long + 短 ID） |
| `stream()` | `:50` | 事件扇出口（向 hub 连接广播） |
| `dataDirOf(subjectId)` | `:61` | 任务数据目录（任务不存在或已清理返回 null） |
| `emitterOf(subjectId)` | `:72` | 任务事件发射器（终态任务返回 null，调用方应跳过 emit） |
| `addRoundClosedListener(l)` | `:80` | 注册轮闭合监听器（插件在 activate 时调用） |
| `toSandboxPath(hostPath)` | `:92` | 宿主路径 → AI 沙箱内可见路径翻译（WSL 后端 `C:\...` → `/c/...`） |
| `task()` / `store()` | `TaskServices.java:13,16` | 继承自 task 域子接口：任务信息服务 / 任务落盘服务（不需要 task 域能力的插件不被迫 import，见 `TaskServices.java:10-17` 注释） |

### 6.4 两个真实范例（逐行注释）

**system-info —— 纯 java 小插件**（全文 18 行，`every-agent-plugins/system-info/src/main/java/dev/everyagent/plugin/sysinfo/SystemInfoPlugin.java:11-18`）：

```java
public class SystemInfoPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(SystemInfoPlugin.class);

    @Override
    public String id() { return "system-info"; }        // 与 plugin.json 的 id 一致

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        // 只注册一个扩展点：AdvisorProvider；依赖（WorkerServices）用 ctx.services() 手工取
        ctx.registerAdvisorProvider(new SystemInfoAdvisorProvider(ctx.services()));
        log.info("[system-info] 已注册 SystemInfoAdvisorProvider");
    }
}
```

配套 Provider 的关键片段（`SystemInfoAdvisorProvider.java:40-59`）——展示「服务手工取 + 路径翻译」的标准姿势：

```java
    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        ExecContext exec = a.execution();                // ExecContext 槽位取数（§14.11）
        SandboxBackend sandbox = services.sandbox();     // 只面向接口，不碰 worker 的 OsSandbox 具体类
        String shownWorkspace = exec.workspaceRoot();
        if (shownWorkspace != null && !shownWorkspace.isBlank()) {
            shownWorkspace = services.toSandboxPath(Path.of(shownWorkspace)); // 宿主路径→沙箱内路径
        }
        return new SystemInfoAdvisor(shownWorkspace, sandbox.id());
    }
```

**task-queue —— 多类注册**（`every-agent-plugins/task-queue/src/main/java/dev/everyagent/plugin/taskqueue/TaskQueuePlugin.java:14-30`）：一次 activate 注册 3 种扩展点 + 2 种服务取数：

```java
    @Override
    public void activate(WorkerPluginContext ctx) {
        WorkerConfig config = ctx.services().config();   // worker 全局配置（worker.*）
        StreamEmitter stream = ctx.services().stream();  // 事件扇出口（task.queued 广播用）

        TaskQueue taskQueue = new TaskQueue(config, stream);   // 插件自持状态，非 Spring bean

        ctx.registerTaskLifecycleNode(new QueueAdmissionNode(taskQueue)); // 洋葱链排队节点
        ctx.registerTaskAdmissionPolicy(new TaskQueueAdmissionPolicy()); // RPC 边缘 always-admit

        // 自定义 RPC：方法名「域.动作」，方法引用即处理器
        TaskQueueRpcHandler rpcHandler = new TaskQueueRpcHandler(taskQueue);
        ctx.registerRpcMethod("task.queueList", rpcHandler::rpcTaskQueueList);
    }
```

## 7. 类加载与隔离

### 7.1 结构：每插件一个 URLClassLoader，parent 委派

`PluginLoader.java:306`：`new URLClassLoader(urls, getClass().getClassLoader())`——每个插件一个独立 `URLClassLoader`，**parent 是加载 `PluginLoader` 的类加载器**（即 worker 自身的应用类加载器）。两条直接后果：

1. **标准 parent-first 委派**：插件能 `import` 的一切 = 自己 jar 里的类 + parent 链上可见的一切——`every-agent-plugin-api`（含 SPI、ExecContext、WorkerServices）、`every-agent-contract`、Spring AI（`ToolCallback`/`Advisor`/`ToolCallbacks`）、slf4j、JDK。这也解释了 §5 最小示例为何只需一个 pom 依赖。
2. **插件之间互不可见**：A 插件与 B 插件各持一个 loader，没有共享子路径——同名类的冲突、依赖版本互踩在插件之间被物理隔开（PluginLoader 类注释 `:67-70`：「插件可见 worker 公共 API；插件之间互不可见」）。

### 7.2 为什么仍然禁止依赖 every-agent-worker（§14.9 红线）

worker 的内部类在 parent 链上**技术可见**，但架构红线明令禁止依赖它。红线原文（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §14.9，行 1335）：

> **插件零 worker 依赖**：插件的 pom 中不得出现对 `every-agent-worker` 的依赖，compile/provided/runtime/test 任何 scope 一律禁止；插件测试需要任务/agent/配置等桩时，在测试源码内自建实现 plugin-api 接口的等价桩类，不得把 worker 具体实现类（TaskEntry/AgentEntity/WorkerProperties/SlashCommandRegistry 等）当测试脚手架；类型确实需要跨 worker 与插件共享时，先下沉到 plugin-api（§1.1，文档先行）。

违反的实际后果：

- **外部插件根本构建不出**：worker 不发布到 Maven Central（`dev.everyagent/` 目录 404），仓库外拿不到 artifact；
- **内置插件编译能过但被红线拦截**：reactor 内互相可见，纯属 review/规范问题——但写进 pom 的那一刻就与 worker 内部实现焊死，worker 重构即碎；
- **依赖它取「未公开服务」也属绕道**：正道是 `ctx.services()`（公开面）或 `ctx.getService(Class)`（Javadoc 明示外部插件不应依赖，`WorkerPluginContext.java:105-112`）；
- 类型确需共享时**先下沉 plugin-api，文档先行**（红线原文收尾句）。若真在运行期 import 了 worker 内部类且该类不在插件可见的 classpath 上，表现为 `NoClassDefFoundError`（未实测构造此场景，机理推断）。

### 7.3 插件不是 Spring bean（零 @Component，rg 自证）

```
rg "@Component|@Service|@Autowired|@Bean" every-agent-plugins --glob "*.java"   # → 0 命中
```

25 个内置插件源码（25 份 plugin.json 对应目录）**零 Spring 注解**：插件类由 `URLClassLoader` 反射 `newInstance()` 创建（`PluginLoader.java:318`），不进 Spring 容器，没有依赖注入、没有 `@Value`、没有 AOP。因此：

- 需要什么依赖，就在 `activate(ctx)` 里用 `ctx.services()` / `ctx.getService(Class)` **手工取**，构造器里 `new` 出自己的对象图（§6.4 两个范例的标准姿势）；
- 插件内部想用 Spring 类型的静态工具（如 `ToolCallbacks.from`）没问题——那是普通类调用，不是容器托管。

## 8. 故障隔离与可观测

### 8.1 隔离边界：一层一层都是「跳过 + WARN」

| 层级 | 失败时的行为 | 证据 |
|---|---|---|
| 扫描器级 | 某把扫描器整体抛异常 → WARN `[plugins] 插件扫描器 {} 扫描失败,跳过: {}`，其余扫描器照常 | `PluginLoader.java:164-168` |
| 目录级（builtin） | `enabled=false` / 无 `plugin.json` / 无 jar → INFO/WARN 后整目录跳过 | `BuiltInPluginScanner.java:81-84,79-87,90-97` |
| 插件级 | 清单缺字段走缺省；`lib/` 不可读、无 jar、入口类不合规 → 登记 `LoadedPlugin(active=false, status=…)` 后 `return`，**循环继续下一个** | `PluginLoader.java:278-291,309-315` |
| 激活级 | `activate()` 抛任何异常 → `catch (Exception)` 兜住，WARN `[plugins] 插件 {} 激活失败: {}`，status=`激活失败: …`，其余插件不受影响 | `PluginLoader.java:339-344` |

⚠️ 一个已知例外（**不属于隔离、是坑**）：manifest **JSON 语法错**（尾逗号/注释）抛的是非受检 `JacksonException`，而 `loadPlugin` 只 `catch (IOException)`（`:205-211`），异常冒出逐插件循环（无 per-plugin try/catch 包住解析段）→ worker 启动失败。源码+异常类型已实测，启动复现**未实测**（详见 [plugin.json 字段参考](../plugin-manifest.md) §7）。

### 8.2 `LoadedPlugin.status` 文案全集（逐字抄源码）

`PluginLoader.java:357-360` 的 record 字段，8 种取值：

| status 值 | 何时产生 | active | 证据 |
|---|---|---|---|
| `已激活(内置)` | builtin 插件 activate 成功 | true | `:336` |
| `已激活` | external 插件 activate 成功 | true | `:336` |
| `已禁用(未激活)` | id 在 `.disabled-plugins` 名单，不调 activate | false | `:240-246` |
| `声明式插件` | 无 `main`（也无旧格式回退），不跑任何 Java | true | `:261-267` |
| `无 jar 文件` | 声明了入口类但 jar 列表为空 | false | `:287-291` |
| `lib 目录不可读: <消息>` | external 的 `lib/` `Files.list` 抛 IOException | false | `:278-284` |
| `入口类未实现 EveryAgentPlugin` | `isAssignableFrom` 校验失败 | false | `:309-315` |
| `激活失败: <消息>` | loadClass/实例化/activate 抛异常 | false | `:339-344` |

### 8.3 看什么日志、`plugin.list` 有什么

- **成功**：INFO `[plugins] 插件已激活: id={} name={} v{} entry={} source={}`（`PluginLoader.java:337`）；扫描期还有 `[plugins-builtin] 扫描到 {} 个内置插件…` / `[plugins-external] 扫描到 {} 个外部插件` / `[plugins] 加载完成: {}/{} 个插件成功加载`。
- **失败**：§8.2 对应各 WARN（内置未构建、跳过重复、激活失败等，文案逐字见前文各节）。
- **`plugin.list` RPC**：返回 `id/name/version/description/author/source/active/status/hasMain/hasWebMain/webMain` 字段（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginRpcMethods.java`）——`status` 是加载期实际状态文案（§8.2 那张表的取值逐字出网），激活失败的插件从它一眼可辨。一个口径坑：`active = !isDisabled(id)` 只表示「不在禁用名单」，保持旧语义兼容既有前端——激活失败的插件也报 `active=true`，判「真的跑起来了没有」看 `status` 是否以「已激活」开头。

## 9. 写完第一个后端插件后怎么验证

三步（假设插件目录 `every-agent-plugins/my-first/`）：

1. **独立构建**：插件**不进 Maven reactor**（根 `pom.xml:20-25` 的 `<modules>` 只有 contract/hub/plugin-api/`every-agent-plugins/task-edit-resend`/worker），必须 `-f` 指到插件自己的 pom：

   ```powershell
   mvn -f every-agent-plugins/my-first/pom.xml clean package
   ```

   产物要求：`target/classes/plugin.json`（maven-resources-plugin 复制）+ `target/*.jar`（非 sources/javadoc）——两者缺一，内置扫描器整目录跳过（§2.2）。用 `clean` 防 §2.2 尾注的旧 jar 遮蔽。

2. **重启 worker，看日志**：确认内置插件根目录在 JVM 工作目录下可见（默认 `user.dir/every-agent-plugins/`，从 IDE/脚本/Docker 启动的 cwd 差异见 [构建与运行](../guides/build-and-run.md)）。成功标志一行 INFO：`[plugins] 插件已激活: id=my-first …`；没出现就按 §8.2 的 status/WARN 对照排查。

3. **查目录**：经 `plugin.list` RPC（扩展管理面板或直接调用）确认 `my-first` 在列、`hasMain=true`；再按扩展点验证功能本身（如 §5 的工具会出现在 AI 的可用工具里）。

改动任何东西后的生效方式只有一种：**重启 worker**（§3.2）。

## 10. ⚠️ 与架构文档 §7.14.4 的差异

[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.14.4（行 704-705）写「任务队列插件……以 `@Component` + 构造器注入 worker 注册表的模式注册（同 git 插件 `GitPluginRegistrar` 先例）」。**以代码为准，该表述已过时**：task-queue 实际经 `EveryAgentPlugin.activate()` + `ctx.register*` 注册（`TaskQueuePlugin.java:14-30`，本篇 §6.4 摘录），全仓 25 个内置插件**零 `@Component`**（§7.3 rg 自证）。架构文档的更正由专门的收口步骤统一处理，本篇不改动它；读到 §7.14.4 时请以本节口径为准。

## 下一步读

- 四个「贴近执行」的扩展点（ToolProvider / 拦截链 / SandboxProvider / FileReferenceHandler）：[tools-and-sandbox.md](tools-and-sandbox.md)
- 模型调用链上的增强（AdvisorProvider / TokenEstimator / ChatModelEnhancer / SearchProvider / AuthorizationHandler / SkillContributor）：[advisors.md](advisors.md)
- 任务域扩展点与自注册 RPC / 斜杠命令：[task-and-rpc.md](task-and-rpc.md)
- 插件私有数据与禁用机制：[persistence-and-state.md](persistence-and-state.md)
- 清单字段逐项语义：[plugin.json 字段参考](../plugin-manifest.md)；构建矩阵与 cwd 陷阱：[构建与运行](../guides/build-and-run.md)；按 status 文案逐条排查：[故障排查](../guides/troubleshooting.md)
