---
title: 工具与沙箱扩展点
nav_order: 5
parent: backend
has_children: false
---

# 工具与沙箱扩展点

**一句话定位**：本篇讲后端四类「贴近执行」的扩展点——给 AI 注入工具（`ToolProvider`）、拦截每一次工具执行（`ToolExecutionInterceptor`）、提供命令沙箱后端（`SandboxProvider`）、处理用户输入里的文件引用（`FileReferenceHandler`），外加它们共同取数的统一执行上下文 `ExecContext`。四个 SPI 全部住在 plugin-api 的 `spi/` 包下，注册入口都是 `activate(ctx)` 里的 `ctx.register*`（注册方法全集见 [后端模型总览](overview.md)，本篇只展开这 4 个 + ExecContext）。

| SPI | 注册方法 | worker 侧注册表 | 消费点（详见各节） | 内置范例插件 |
|---|---|---|---|---|
| `ToolProvider` | `registerToolProvider`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerPluginContext.java:42`） | `ToolProviderRegistry` | `AgentBuilder` 装配期聚合 | subagent、sandbox-windows-mic、sandbox-wsl-ubuntu、sandbox-windows-codex |
| `ToolExecutionInterceptor` | `registerToolExecutionInterceptor`（`WorkerPluginContext.java:57`） | `ToolExecutionInterceptorRegistry` | per-run `InterceptingToolCallingManager` | secret-redaction、unattended |
| `SandboxProvider` | `registerSandboxProvider`（`WorkerPluginContext.java:48`） | `SandboxProviderRegistry` | `OsSandbox` 惰性解析 + `SandboxPathRegistry` | sandbox-windows-mic、sandbox-wsl-ubuntu、sandbox-windows-codex |
| `FileReferenceHandler` | `registerFileReferenceHandler`（`WorkerPluginContext.java:69`） | `FileReferenceHandlerRegistry` | 任务生命周期节点 `FileReferenceProcessNode` | image-vision（唯一） |

所有注册方法的 worker 实现(`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/WorkerPluginContextImpl.java:126,136,151,182`)都只是往对应注册表 `add` 一行——**注册即生效、无过滤、无校验**。

## 1. ToolProvider —— 给 AI 注入工具

### 1.1 接口定义（原码摘录）

`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/ToolProvider.java`：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/ToolProvider.java
public interface ToolProvider {

    /** 插件 id。 */
    String pluginId();

    /** 为指定任务创建工具回调列表（ctx 含 subjectId、workspaceRoot、sandbox 等，extends ExecContext）。 */
    List<ToolCallback> createTools(ToolContext ctx);

    /** 可选：工具是否适用于此任务。 */
    default boolean appliesTo(ToolContext ctx) {
        return true;
    }
}
```

`ToolCallback` 是 **Spring AI 的类型**（`org.springframework.ai.tool.ToolCallback`）——worker 不发明自己的工具模型，插件工具直接就是 Spring AI 工具。`ToolContext` 本身 `extends ExecContext`，外加工具创建侧专属槽位（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/ToolContext.java:17-46`）：

| 槽位 | 含义 | 用法 |
|---|---|---|
| `agentId()` | 本 agent 的 ID（主 agent = mainAgentId，子 agent = 其 agentId） | 审计/日志 |
| `sandbox()` | 生效沙箱后端（`OsSandbox` 门面，未解析到 SPI 后端时 `id()=="direct"`） | `appliesTo` 判定生效条件 |
| `workspaces()` | 工作区管理器（多工作区注册表） | 解析/校验路径 |
| `rgBinary()` | ripgrep 二进制路径（搜索工具用，可能 null） | 自带搜索工具时复用 |
| `shellExecutor()` | 已组装好的 shell 执行器（授权 + 沙箱已内建；不含 rg 注入） | 注册 ShellTool |

### 1.2 worker 侧消费链路：工具怎么到模型手里

worker **复用 Spring AI 框架**，不手搓工具循环（根 `AGENTS.md` 红线）。链路全部有源码依据：

```
插件 activate(ctx)
  └─ ctx.registerToolProvider(p) → ToolProviderRegistry.register
     （WorkerPluginContextImpl.java:126；注册表 CopyOnWriteArrayList，ToolProviderRegistry.java:19-23）
每轮 agent 装配（AgentBuilder.create()，每次 build 重新聚合，构造 ToolContextImpl）
  ├─ for (p : toolRegistry.getProviders()) if (p.appliesTo(toolCtx)) → createTools 并入
  │    证据：AgentBuilder.java:125-133；Build.tools(list, ModifyMode) 可增删改 :223-236
运行（AgentRunner.run）
  ├─ options.toolCallbacks(tools...) 塞进 Prompt options（AgentRunner.java:40-49）
  └─ chatClient.prompt(prompt).stream() —— 工具循环由 Spring AI 的 ToolCallingAdvisor 全权接管
       （WorkerToolEventAdvisor extends ToolCallingAdvisor，WorkerToolEventAdvisor.java:66,80）
```

worker 自己的内置工具（read_file、powershell/bash）也走同一条链：`BuiltInToolProviders` 在 `@PostConstruct` 把适配器注册进同一注册表（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/BuiltInToolProviders.java`：`FileToolsProvider` / `DirectShellToolProvider`）——**插件工具与内置工具完全平权**。`ask_user` 不在内置之列：由独立的 **ask-user 插件**以 `ToolProvider` 提供（java-only，主/子 agent 均可用，超时读 worker 配置 `worker.limits.ask-timeout-ms`，见 [内置插件清单](../reference/builtin-plugins.md) §5），与内置工具同链装配。

### 1.3 工具怎么写：注解式（推荐）

Spring AI 约定：写一个普通类，方法上标 `@Tool`（描述）+ 参数上标 `@ToolParam`（参数描述、可选性），再经 `ToolCallbacks.from(...)` 转 `ToolCallback`。最小范例（仓库内体量最小的 ToolProvider 插件就是 subagent）：

```java
// every-agent-plugins/subagent/src/main/java/dev/everyagent/plugin/subagent/SubAgentToolsProvider.java
public class SubAgentToolsProvider implements ToolProvider {
    private final SubAgentManager subAgentManager;

    @Override public String pluginId() { return "subagent"; }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        // ToolCallbacks.from：把 @Tool 注解类批量转成 ToolCallback（Spring AI 提供）
        return List.of(ToolCallbacks.from(new SubAgentTools(subAgentManager, ctx)));
    }
}

// every-agent-plugins/subagent/src/main/java/dev/everyagent/plugin/subagent/SubAgentTools.java:22-30
@Tool(description = "派生一个子 agent 去完成一项独立子任务。子 agent 有独立上下文,看不到当前对话。"
        + "异步运行,立即返回 agentId;必须再用 wait_agents 等待完成并收集结果。...")
public String run_agent(
        @ToolParam(description = "交给子 agent 的完整任务描述(需自包含,不能引用本对话内容)") String input,
        @ToolParam(description = "简短标题", required = false) String title,
        @ToolParam(description = "指定复用的 agentId", required = false) String agentId) { ... }
```

要点：**描述写给模型看**，直接决定模型会不会用、怎么用；可缺省参数标 `required = false`；需要 per-run 状态（如 ExecContext）就在 `createTools` 里 `new` 进工具实例（工具对象每次装配新建）。注册就是 activate 里的一行：`ctx.registerToolProvider(new SubAgentToolsProvider(subAgentManager))`（`SubAgentPlugin.java:31`）。

### 1.4 工具怎么写：复用 ShellTool（命令类工具）

要做「跑命令」类工具不要自己拼 `ProcessBuilder`：`ToolContext.shellExecutor()` 已把 **PermissionGate 授权 + 沙箱 + 输出护栏** 内建好（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/ToolContextImpl.java:106-118`），plugin-api 提供 `ShellTool` 工厂（底层是 Spring AI `FunctionToolCallback.builder`，`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/shell/ShellTool.java:41-49`）。**描述必传（2026-12 决策：核心零默认）**——`powershell(String description, ShellExecutor)` / `bash(String description, ShellExecutor)`，工具用途、工作目录、rg 可用性、实际 shell 等一律由提供者按自身事实全量声明，null/空串运行期拒绝；写漏描述不会被核心的模板兜底（模板与后端事实脱节正是谎报之源，见 ARCHITECTURE §7.10「描述精简的第三批」）。范例：

```java
// every-agent-plugins/sandbox-windows-mic/src/main/java/dev/everyagent/plugin/sandbox/mic/WindowsMicShellToolProvider.java:41-65（节选）
    @Override
    public boolean appliesTo(ToolContext ctx) {
        // 只在当前生效沙箱后端是 windows-mic 时贡献工具（§7.10 约定判据）
        return ctx.sandbox() != null && "windows-mic".equals(ctx.sandbox().id());
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        ShellExecutor base = ctx.shellExecutor();
        if (base == null) { return List.of(); }   // 拿不到执行器就不注册，绝不自己另起炉灶
        ShellExecutor exec = rgDir != null ? withRgInPath(base, rgDir) : base; // rg 注入 PATH 自己包一层
        // 描述全量自写（核心零默认）：用途/工作目录/rg 可用性按本后端事实声明
        return List.of(ShellTool.powershell("在系统上用 PowerShell 执行真实 OS 命令;"
                + "命令工作目录默认为任务工作区根;" + rgNote, exec).callback());
    }
```

### 1.5 常见坑

| 坑 | 说明 | 证据 |
|---|---|---|
| 在 provider 里存 per-run 状态 | `createTools` 在**每次** agent 装配时都会被调用，provider 是长生命周期对象；per-run 状态放 `createTools` 里 new 出的工具实例 | `AgentBuilder.java:125-133` |
| 以为插件是 Spring bean | 插件经 `URLClassLoader` 加载，**零 Spring 注解**；依赖只能 `ctx.services()` / `ctx.getService(Class)` | [plugin.json 字段参考](../plugin-manifest.md) §7；`SubAgentPlugin.java:22-24` 注释 |
| `shellExecutor()` 返回 null 没处理 | DIRECT 之外的路径拿不到就返回 `List.of()`，别自己造执行器 | `WindowsMicShellToolProvider.java:47-50` |
| 忘写 `appliesTo` 导致多后端工具齐发 | 各沙箱插件的命令工具都靠 `ctx.sandbox().id()` 区分生效条件；插件 pom 任何 scope 禁止依赖 worker（§14.9） | `WindowsMicShellToolProvider.java:41-43` |

## 2. ToolExecutionInterceptor —— 工具执行拦截链

### 2.1 接口定义（原码摘录）

`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/ToolExecutionInterceptor.java`：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/ToolExecutionInterceptor.java
public interface ToolExecutionInterceptor {

    String id();

    /** 链上位置：升序 = 执行序。float 允许任意插位；同 order 按注册顺序。 */
    float order();

    /** 拦截入口：ctx = prompt/chatResponse/toolCalls + 显式任务上下文（替代 ThreadLocal）；
     *  返回 = 工具执行结果（短路=合成结果；放行=真实执行结果）。 */
    ToolExecutionResult invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception;
}
```

`ToolExecutionContext extends ExecContext`，专属成员只有三个只读视图：`prompt()` / `chatResponse()` / `toolCalls()`（本轮模型下发的工具调用清单，`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/ToolExecutionContext.java:17-21`）。

### 2.2 拦截时机与能力（如实）

| 时机 | 写法 | 能力 | 内置范例 |
|---|---|---|---|
| 下行（`next.proceed` 之前） | 不调 `next` 直接 `return` 合成结果 | **阻断执行**：模型收到你合成的 `ToolExecutionResult`，真实工具不跑（对照：调 `next` 即放行，unattended 非无人值守时直接放行，`:34-36`） | unattended 对「纯 ask_user 轮」合成自动回答短路（`every-agent-plugins/unattended/src/main/java/dev/everyagent/plugin/unattended/UnattendedToolInterceptor.java:73-80`） |
| 下行改写入参 | `next.proceed(包装后的 ctx)` | 想改真实执行所见的 prompt/chatResponse，就包一个新 `ToolExecutionContext` 传下去——链尾用的是**你传下去的那个 ctx**（`delegate.executeToolCalls(c.prompt(), c.chatResponse())`，`ToolExecutionChainExecutor.java:34`） | **无内置范例，未实测** |
| 上行（`next.proceed` 返回之后） | 拿返回的 `ToolExecutionResult` 重建 | **改写结果**：重建 `conversationHistory` 里的 `ToolResponseMessage` 后返回新结果——落盘/推送/回灌模型三路全覆盖 | secret-redaction 掩码凭据（`every-agent-plugins/secret-redaction/src/main/java/dev/everyagent/plugin/secretredaction/SecretRedactionInterceptor.java:62-130`） |

**order 语义**：升序 = 执行序（洋葱模型：order 小的在外层，先下行、后上行）；float 允许任意插位；同 order 按注册顺序（稳定排序，`ToolExecutionChainExecutor.java:31`）。现役整条链只有两环：unattended=100（最外层）、secret-redaction=900（最内层、紧贴真实执行，`SecretRedactionInterceptor.java:62` 类注释给出取值理由：在权限门/审计之后、事件发射之前）。

### 2.3 消费链路

```
ChatClient.prompt(...).stream()
  └─ ToolCallingAdvisor（Spring AI 工具循环）
       └─ executeToolCalls 落到 per-run InterceptingToolCallingManager
            （AgentBuilder.build() 每次 new，持 ExecContext、不依赖 ThreadLocal；AgentBuilder.java:296-298）
            ├─ interceptorRegistry.sorted() 取全量拦截器   InterceptingToolCallingManager.java:52-53
            └─ ToolExecutionChainExecutor.run 按升序折叠成嵌套链（ToolExecutionChainExecutor.java:27-51）
                 链尾 = Spring AI 真实 ToolCallingManager（WorkerBeanConfiguration.toolCallingManager()
                 ：maxCallsPerTool=200、maxTotalToolCalls=500、取消类异常穿透）
```

注册代码就是 activate 里的一行：`ctx.registerToolExecutionInterceptor(new SecretRedactionInterceptor())`（`every-agent-plugins/secret-redaction/src/main/java/dev/everyagent/plugin/secretredaction/SecretRedactionPlugin.java:20-22`）。

### 2.4 常见坑

| 坑 | 说明 |
|---|---|
| 以为是 per-tool 拦截 | 链是**全局**的：每次 `executeToolCalls`（一整轮全部工具调用）都过全链；要只处理某工具自己在 `invoke` 里按 `ctx.toolCalls()` 的 name 过滤（secret-redaction 只重建本轮 callId，`:85-91`） |
| 短路时漏拼 `conversationHistory` | 短路结果必须自包含：assistant 消息 + 合成的 `ToolResponseMessage` 都要在（`UnattendedToolInterceptor.java:75-80` 的拼法可直接抄） |
| 在拦截器里 import 任务域类型 | §14.11 红线：横切层禁止 import `TaskEntry/TaskRuntime/TaskInfo`，取数一律走 ExecContext 槽位（`subjectId()`/`metadata()` 等） |
| 上行改写破坏 seq 不可变 | 只能改**本轮**工具结果；assistant 正文是流式落盘的，事后改写违反「运行中日志永不修剪」（secret-redaction 类注释明说不覆盖该场景） |

## 3. SandboxProvider —— 沙箱后端

### 3.1 消费点核实结论（先说最关心的）

**SandboxProvider 在 worker 侧是真接线、非预留**，共四个实证消费点（全部 main 代码，非单测）：

| # | 消费点 | 证据（相对仓库根） |
|---|---|---|
| 1 | `SandboxProviderRegistry`（`@Component`）：`register`/`unregister`/`select`/`describeProviders`/`generation` | `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/SandboxProviderRegistry.java:26-137` |
| 2 | `OsSandbox.backend()` 按注册表**代次惰性解析** delegate，解析结果（含 null→DIRECT）按代次缓存；`id()`/`mount()`/`onWorkspaceRemoved()` 一律转发给 delegate | `every-agent-worker/src/main/java/dev/everyagent/worker/os/OsSandbox.java:102-161` |
| 3 | `AgentBuilder.createToolContext` 把 `OsSandbox` 门面塞进 `ToolContextImpl.sandbox()`，供所有插件的 `appliesTo(ctx.sandbox().id())` 判定 | `AgentBuilder.java:135-139`、`ToolContextImpl.java:39-53,74-77` |
| 4 | `SandboxPathRegistry` 把注册意图按当前生效后端**物化**为 `mount()` 映射表（宿主路径 ↔ 沙箱内路径），advisor/工具链查表翻译 | 架构 §7.10；登记点 `AgentBuilder.registerSandboxPaths`（`AgentBuilder.java:146-173`）、`WorkerBeanConfiguration.skillAdvisor`（`WorkerBeanConfiguration.java:63-70`） |

### 3.2 接口定义（原码摘录）

`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/SandboxProvider.java`：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/SandboxProvider.java
public interface SandboxProvider {

    /** 后端 id（如 "wsl-ubuntu"/"windows-mic"/"docker"/"none"）。 */
    String id();

    /** 此后端在当前平台是否可用（探测）。 */
    boolean isAvailable();

    /** 优先级（auto 模式下多后端可用时选最高；数值越大优先级越高）。 */
    default int priority() { return 0; }

    /** 创建沙箱后端。 */
    SandboxBackend create(SandboxConfig config);

    /** 沙箱配置（从 WorkerProperties.Sandbox 解析）。 */
    record SandboxConfig(String type, boolean enabled, boolean networkDenied,
            boolean allowPrivilegeEscalation, long timeoutMs, Path persistentRoot, Object props) { }
}
```

`create()` 的产物是**极简三方法**的 `SandboxBackend`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/SandboxBackend.java:13-63`）：`id()` + `mount(List<MountRequest>)`（批量挂载，返回 {宿主路径→沙箱内路径}，幂等）+ `onWorkspaceRemoved(Path)`（best-effort 清理）。**不执行命令、不翻译路径、不涉及工具注册、不涉及授权策略**——命令执行归各插件自己的 CommandExecutor（§3.4）。

### 3.3 选择规则与时机（时序红线）

- **选择规则**（`SandboxProviderRegistry.select`，`:84-137`）：显式 `type` 精确命中且 `isAvailable()` → `create()`；命中但不可用 / 无对应 provider → 转 auto；auto = `isAvailable()` 过滤后取 `priority()` 最高者（**只对胜出者调 `create()`**，重副作用如 codex 的 UAC setup 不因探测被提前触发）；`enabled=false` 或 `type=none` → 返回 null，调用方退化 DIRECT。配置键 `worker.sandbox.type`，旧值归一（`acl`→`windows-mic` 等）见 `OsSandbox.normalizeBackend`（`OsSandbox.java:182-201`）。
- **时机红线**（§7.10 原文口径）：全部 SandboxProvider 都由插件在 `PluginLoader` 的 `@PostConstruct` 里注册，而 `PluginLoader → WorkerServices → OsSandbox` 的构造依赖链决定了 OsSandbox **必然先于插件激活完成初始化**——后端不得在 `@PostConstruct` 一次性定论。`OsSandbox` 曾因此在启动期 `select()` 恒得「无可用后端」（类 Javadoc 记录了这次翻车，`OsSandbox.java:81-87`）；现改为按**注册表代次惰性解析**（每次注册/注销自增，`SandboxProviderRegistry.java:49-58`）。
- 现役后端：`wsl-ubuntu`(10)、`codex`(Windows 8)、`windows-mic`(5)、`direct`（无 Provider，OsSandbox 自身兜底）。⚠️ 仓库现状：`sandbox-windows-mic` 与 `sandbox-wsl-ubuntu` 的 `plugin.json` 均写 `"enabled": false`（内置扫描期整目录跳过），实际在册的 SPI 后端只有 codex——启用前两者需改清单并重启 worker（`every-agent-plugins/sandbox-windows-mic/plugin.json:9`、`every-agent-plugins/sandbox-wsl-ubuntu/plugin.json:9`）。

### 3.4 与 worker 命令执行链的关系（§7.10 windows-mic 语境）

沙箱后端不执行命令；命令的**实际执行**有两条互不相同的路：

| 路 | 谁执行 | 说明 |
|---|---|---|
| worker 核心命令工具 | `CommandExecutor`（`every-agent-worker/src/main/java/dev/everyagent/worker/tools/CommandExecutor.java`） | cwd 锁 `Path.of(task.workspaceRoot())`（`:95`）；执行前 `gate.requireCommand`（危险动词/越界路径授权，`:90`）与 `gate.requirePrivilege`（`:128`）；实际 spawn 走 `OsSandbox.spawnNative/spawnToFileRedirected`（`:137-196`，超时/输出护栏/凭据 env 剔除内建） |
| 沙箱插件自己的命令工具 | 插件自带 CommandExecutor（如 `every-agent-plugins/sandbox-windows-codex/.../CodexCommandExecutor.java`） | §7.10：「只有某后端才做得到的隔离能力，其命令与状态一律归该插件」——完全自由实现、自扫描路径、自定授权策略，经 `ToolProvider.appliesTo` 控制生效 |

windows-mic 后端的隔离语义即架构 §7.10 的 **Windows Medium IL 契约**原文：「沙箱进程运行在 Medium IL(Restricted Token 去特权但不降级)，天然可写工作区与已授权目录，不对文件系统做任何标注或 ACL 修改——零副作用、零残留。越界写拦截由 PermissionGate 责任链承担。」（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §7.10）

### 3.5 范例与注册代码

最小范例：`sandbox-windows-mic`（provider 46 行 + backend 33 行 + 入口 34 行）；`WindowsMicSandboxBackend` 全用默认实现（mount 返回原路径、onWorkspaceRemoved no-op）——windows-mic 命令跑在宿主上，无需挂载（`WindowsMicSandboxBackend.java:14-33`）。

```java
// every-agent-plugins/sandbox-windows-mic/src/main/java/dev/everyagent/plugin/sandbox/mic/WindowsMicSandboxProvider.java:16-46（节选）
public final class WindowsMicSandboxProvider implements SandboxProvider {
    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    @Override public String id() { return "windows-mic"; }
    @Override public boolean isAvailable() { return WINDOWS; }   // 探测必须轻量：不弹 UAC、不做 setup
    @Override public int priority() { return 5; }                // auto 模式的兜底位次
    @Override public SandboxBackend create(SandboxConfig config) {
        return new WindowsMicSandboxBackend(props);              // 胜出才付创建成本
    }
}

// every-agent-plugins/sandbox-windows-mic/src/main/java/dev/everyagent/plugin/sandbox/mic/WindowsMicSandboxPlugin.java:24-33
public void activate(WorkerPluginContext ctx) throws Exception {
    WorkerConfig props = ctx.getService(WorkerConfig.class);
    // 沙箱后端 + 命令工具一起注册：后端只管挂载/清理，命令工具才是 AI 看得见的东西
    ctx.registerSandboxProvider(new WindowsMicSandboxProvider(props));
    ctx.registerToolProvider(new WindowsMicShellToolProvider(rgDir));
}
```

### 3.6 常见坑

| 坑 | 说明 |
|---|---|
| 在 `@PostConstruct` / 构造器里探测或定论后端 | 时序红线：注册晚于你初始化，见 §3.3；探测放 `isAvailable()`，创建放 `create()` |
| 只注册 SandboxProvider 就指望有命令工具 | 后端与命令工具是**两个注册**：AI 能用的命令工具来自配套的 `ToolProvider`（§3.5 范例两行并列） |
| `mount()` 不幂等 | 契约要求重复调用相同路径不重复挂载（`SandboxBackend.java:44`） |
| priority 记反 / 改配置即生效 | **数值越大优先级越高**（auto 取 max，`SandboxProviderRegistry.java:127-133`）；配置随 worker 启动读取，插件启停一律**重启 worker** |

## 4. FileReferenceHandler —— 用户输入里的文件引用

### 4.1 处理什么输入形态

用户在前端输入框用 `@` 挂文件时，rawContent 里是 **opaque token**（4 连方括号定界 + base64url payload，kind 为 `system.workspace_file` / `system.external_file`，与前端 composer 胶囊同格式）。核心生命周期节点 `FileReferenceProcessNode` 把它解析成 `FileReference`：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/FileReference.java
public record FileReference(
        String path,        // 交给 AI 的引用路径（workspace_file=工作区相对路径；external_file=工作区外绝对路径）
        String fileName,    // 文件名（路径最后一段，即输入框胶囊 label）
        String fullPath,    // 完整路径（workspace_file=带前导 / 的业务绝对路径）
        boolean external)   // 是否工作区外引用
```

### 4.2 接口定义（原码摘录）

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/FileReferenceHandler.java
public interface FileReferenceHandler {

    /** 插件 id。 */
    String pluginId();

    /** 支持的扩展名集合（小写含点，如 {".png", ".jpg"}）。 */
    Set<String> extensions();

    /** 处理一个文件引用；返回 null 等价于「放弃处理」（保持现有路径文本行为）。 */
    FileReferenceResult process(FileReferenceContext ctx, FileReference ref);
}
```

返回值 `FileReferenceResult(replacementText, attachments)`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/FileReferenceResult.java`）：`replacementText` 改写 input 中对应路径文本（null=不改写）；`attachments` 追加进 `TaskEntry.metadata.attachments` 持久化，由核心 `FileAttachmentAdvisor` 统一注入模型。attachment 项约定结构 `{type:"image", dataUrl, fileName, mimeType}`。

### 4.3 消费链路

```
任务输入处理（轮次循环段）
  └─ FileReferenceProcessNode（TaskLifecycleNode，order=875，consume.input=880 之前）
       every-agent-worker/src/main/java/dev/everyagent/worker/task/lifecycle/FileReferenceProcessNode.java:43-76
       ├─ 正则解析 rawContent 中的 [[[[...]]]] token（复用 SlashTokenEncoder）    :83-100
       ├─ handlers.find(扩展名) → 未命中直接跳过（保持路径文本）                   :103
       ├─ handler.process(new ContextView(ctx), ref)；异常 catch 记 WARN 不阻断  :109-114
       ├─ replacementText 改写 ctx.input()；attachments 并入 metadata.attachments :117-133,167-190
       └─ FileAttachmentAdvisor 后续统一把 attachments 注入模型
```

注册表按扩展名一对一映射，**同扩展名后注册覆盖先注册并打 WARN**（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/FileReferenceHandlerRegistry.java:30-45`）。

### 4.4 内置范例：image-vision（唯一实现者）

注册（`every-agent-plugins/image-vision/src/main/java/dev/everyagent/plugin/imagevision/ImageVisionPlugin.java:29-30`，配置来自 `plugin.json` 的 `contributes.config`）：`ImageVisionSettings settings = ImageVisionSettings.from(ctx.config()); ctx.registerFileReferenceHandler(new ImageReferenceHandler(ctx.services(), settings));`

处理主流程关键片段（`every-agent-plugins/image-vision/src/main/java/dev/everyagent/plugin/imagevision/ImageReferenceHandler.java`）：

```java
// ImageReferenceHandler.java:76-91 —— process：开关 + 路径解析，任何失败都降级为纯路径文本
public FileReferenceResult process(FileReferenceContext ctx, FileReference ref) {
    if (!settings.enabled()) { return null; }          // 关闭 = 放弃处理
    Path file = resolveAuthorized(ctx, ref);
    if (file == null) { return null; }                  // 越界/不存在/未授权 → 降级
    try { return buildResult(file, ref); }
    catch (IOException | RuntimeException e) { return null; }  // 读取失败也降级，不阻断任务
}

// ImageReferenceHandler.java:196-206 —— 组装结果：超限自动缩放压缩，转 base64 dataUrl
String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(payload);
attachment.put("type", "image"); attachment.put("dataUrl", dataUrl);
attachment.put("fileName", ref.fileName()); attachment.put("mimeType", mimeType);
return new FileReferenceResult("（附图：" + ref.fileName() + "）", List.of(attachment));
```

工作区外引用（`external_file`）的授权路径：先 `toRealPath()`，再经 `ctx.interaction().ask(...)` 弹**与 PermissionGate 同款的三档授权弹窗**（本轮运行/本任务/拒绝），拒绝/超时/异常一律按拒绝降级为路径文本（`ImageReferenceHandler.java:117-165`）。

### 4.5 常见坑

| 坑 | 说明 |
|---|---|
| 扩展名大小写/缺点 | 注册键统一**小写含点**（`.png`）；查表侧也做了 lowercase（`FileReferenceHandlerRegistry.java:34-41`、`FileReferenceProcessNode.java:246-254`） |
| 处理器里做重活阻塞输入链 | 节点在任务输入处理临界段内同步执行；读大文件前设上限（image-vision 设 256MB 硬上限，`ImageReferenceHandler.java:51`） |
| 以为早期槽位都有值 | `FileReferenceContext` 是早期节点：任务未创建时 `snapshot()/emitter()/interaction()/dataDir()` 等可为 null，`subjectId()/workspaceId()/workspaceRoot()` 由早期回退字段供给（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/FileReferenceContext.java:14-20`） |
| 自己改写 input / 拼持久化 | 核心已做 input 改写与 metadata 合并，handler 只返回结果；别的持久化走 `pluginDir()` + 自注册 RPC（[持久化与插件私有状态](persistence-and-state.md)） |

## 5. ExecContext —— 槽位判据与字段表

### 5.1 §14.11 红线原文（口径出处）

[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §14.11（执行上下文管道）：

> **槽位判据**：ExecContext 槽位 = 任何执行主体都必然具备的核心属性与端口(subjectId/workspaceRoot/workspaceId/snapshot/emitter/agentFactory/interaction/metadata/dataDir/terminal/agents 活动实体注册表;configId 不设独立槽,经 snapshot().configId() 取);**插件功能与主体特有槽位不进核心接口**……

> **禁止**：横切层 import `TaskEntry/TaskRuntime/TaskInfo`;绕过 `ctx.agentFactory()` 手工组装四件套 map;绕过 `ctx.interaction()` 手动填 taskId context;worker 侧新增 properties 透传通道。工作流层实现 `WorkflowRuntime implements ExecContext` 后,同一条授权链、同一批 advisor、同一工具链零改动复用。

本篇四个 SPI 的上下文全部 `extends ExecContext`（`ToolContext` / `ToolExecutionContext` / `FileReferenceContext`，§14.11 明文列举），插件代码**只准**从这些槽位取执行数据。

### 5.2 逐字段表

源码：`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/execution/ExecContext.java:25-91`。

| 字段/方法 | 类型 | 含义 | 谁消费 | 红线相关 |
|---|---|---|---|---|
| `subjectId()` | `String` | 执行主体 ID（今天=taskId，未来=workflowId） | 授权状态分区、审计日志、`interaction()` ask 自动补键 | §14.11「横切层取主体 ID 一律 subjectId()」 |
| `workspaceRoot()` | `String`（可能 null） | 工作区根路径 | **决定命令 cwd**：`CommandExecutor.java:95` `Path.of(task.workspaceRoot())`；也决定沙箱挂载意图（`AgentBuilder.java:146-173`） | §7.8 工作区内放行的判定基准；需要 Path 形态自行转换并判 null（`ToolContext.java` Javadoc） |
| `workspaceId()` | `String` | 工作区稳定 ID | 工作区归属/数据目录推导 | — |
| `snapshot()` | `ModelConfig` | 完整模型配置快照（configId 经 `snapshot().configId()` 取，**无独立槽**） | advisor 装配模型参数 | §14.11 明文 |
| `emitter()` | `EventEmitter` | 已绑定本主体的事件口（trace/审计） | secret-redaction 发 `task.trace`（`SecretRedactionInterceptor.java:140-160`） | 只发语义 EmitEvent，不感知 wire 事件名/seq（§14.0） |
| `agentFactory()` | `AgentFactory` | 预绑定 Agent 工厂（`create(agentId)`） | 子 agent / 审议 agent 创建 | §14.11「不传裸工厂/裸服务/任务域对象」 |
| `metadata()` | `Map<String,Object>` | 随 meta.json 落盘的持久策略标记（unattended/ai-review/attachments） | `UnattendedToolInterceptor.java:35` 读 `unattended` 标记；`FileReferenceProcessNode` 写 `attachments` | 只承载持久策略标记，不混运行时瞬态（§14.11） |
| `dataDir()` | `Path` | 主体数据目录（grants.json 等落盘） | 授权链 | — |
| `terminal()` | `boolean` | 主体是否已收口 | leak-guard：终态后不再发射事件 | — |
| `interaction()` | `InteractionService` | 预绑定交互口（ask 的 context 自动补 subjectId） | image-vision 外部文件授权弹窗（`ImageReferenceHandler.java:136-150`） | §14.11「绕过 ctx.interaction() 手动填 taskId context」禁止 |
| `agents()` | `Map<String,AgentContext>` | 本主体活动 agent 注册表（主 agent + 各插件派生） | `AgentBuilder.build()` 自动 put（`:333`，不发事件） | §14.11「插件不再手动 put/emit」 |
| `METADATA_ATTACHMENTS_KEY` | 常量 `"attachments"` | metadata 中附件列表键名 | `FileReferenceProcessNode`/`FileAttachmentAdvisor` | — |

### 5.3 「命令跑在哪个环境/目录」到底由什么决定

| 问题 | 答案 | 证据 |
|---|---|---|
| 跑在**哪个目录** | `workspaceRoot()` 槽位：命令执行器把子进程 cwd 锁定在工作区根 | `CommandExecutor.java:30,95` |
| 跑在**哪个环境**（宿主 / WSL / 受限令牌） | **不是 ExecContext 字段**，由生效沙箱后端决定：worker 按 `worker.sandbox.type` + 注册表选出 SandboxProvider，`OsSandbox` 门面把结论暴露给 `ToolContext.sandbox()`；插件的命令工具自己决定怎么用后端 | `OsSandbox.java:102-161`、`ToolContext.java:25-33`、架构 §7.10 |
| 工作区**只读还是可写** | 工作区根按 `READ_WRITE` 登记挂载意图（§7.16 externalRoots 同为读写）；系统技能目录也读写挂入（§7.17），另有一次面向 prompt 路径翻译的 `READ_ONLY` 登记 | `AgentBuilder.java:158-171`、`WorkerBeanConfiguration.java:63-70` |
| **jail 校验在谁那边** | worker 侧：文件路径责任链（`WorkspaceAllowCheck`→…→`AuthorizeCheck`，先 realpath 再校验前缀）+ `WorkspaceSandbox.resolveExisting`（词法 + realpath 双校验）。插件**复用**这些通道（经 `ctx.workspaces()`），不得自造越界放行 | 架构 §7.8；`ImageReferenceHandler.java:107-115`；红线：不得绕过 gate 放行越界 IO（根 `AGENTS.md`） |

## 6. 工具红线速查

写工具/沙箱类插件前，三条架构红线必须过一遍（原文见 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)）：

| 红线 | 出处 | 与本篇四个 SPI 的关系 |
|---|---|---|
| **PermissionGate**：危险操作必须经用户授权——AI 工具的工作区外路径访问先弹 `kind=authorization` 的 ask（拒绝/本轮运行/本任务三档）；命令中的危险动词**仅当命令引用可能落在工作区外**时才需授权，工作区内增删改查直接放行（cwd 锁定 + 沙箱可写性契约兜底）；拒绝抛异常回灌模型 | §7.8 | 用 `ctx.shellExecutor()` 就自动带上；自己碰文件系统要走 `ctx.workspaces()` 的 jailed 解析；**不得绕过 gate 直接放行越界 IO** |
| **沙箱契约（windows-mic）**：沙箱进程 Medium IL（Restricted Token 去特权但不降级），天然可写工作区与已授权目录，零文件系统标注、零 ACL 修改、零残留；越界写拦截由 PermissionGate 责任链承担，无 OS 级写隔离兜底 | §7.10 | SandboxProvider 实现者不要试图靠改 ACL/打标记做隔离；隔离做不到的部分交给授权链 |
| **ExecContext 管道**：横切层只见 ExecContext 槽位，不见 `TaskEntry/TaskRuntime/TaskInfo`；禁止 properties 黑盒 map 与强转再现；插件功能与主体特有槽位不进核心接口 | §14.11 | 四个 SPI 的 ctx 全部 `extends ExecContext`；插件代码 import 任务域类型即违红线 |

## 下一步读

- 挂在模型调用链上的增强（AdvisorProvider / order 坐标 / 禁插段）：[advisors.md](advisors.md)
- 任务生命周期节点与自注册 RPC（`FileReferenceProcessNode` 所在的那条链）：[task-and-rpc.md](task-and-rpc.md)
- 插件单测怎么写（只依赖 plugin-api 的 JUnit5 范式）：[调试与测试](../guides/debugging-and-testing.md)
