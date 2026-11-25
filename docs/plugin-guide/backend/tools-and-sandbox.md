---
title: 工具与沙箱扩展点
nav_order: 5
parent: 后端插件开发
has_children: false
---

# 工具与沙箱扩展点

**一句话定位**：本篇讲后端四类「贴近执行」的扩展点——给 AI 注入工具（`ToolProvider`）、拦截每一次工具执行（`ToolExecutionInterceptor`）、提供命令沙箱后端（`SandboxProvider`）、按扩展名接管 `read_file`（`FileReadHandler`），外加它们共同取数的统一执行上下文 `ExecContext`。四个 SPI 全部住在 plugin-api 的 `spi/` 包下，注册入口都是 `activate(ctx)` 里的 `ctx.register*`（注册方法全集见 [后端模型总览](overview.md)，本篇只展开这 4 个 + ExecContext）。

| SPI | 注册方法 | worker 侧注册表 | 消费点（详见各节） | 内置范例插件 |
|---|---|---|---|---|
| `ToolProvider` | `registerToolProvider`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerPluginContext.java:43`） | `ToolProviderRegistry` | `AgentBuilder` 装配期聚合 | subagent、sandbox-windows-mic、sandbox-wsl-ubuntu、sandbox-windows-codex |
| `ToolExecutionInterceptor` | `registerToolExecutionInterceptor`（`WorkerPluginContext.java:58`） | `ToolExecutionInterceptorRegistry` | per-run `InterceptingToolCallingManager` | secret-redaction、unattended |
| `SandboxProvider` | `registerSandboxProvider`（`WorkerPluginContext.java:49`） | `SandboxProviderRegistry` | `OsSandbox` 惰性解析 + `SandboxPathRegistry` | sandbox-windows-mic、sandbox-wsl-ubuntu、sandbox-windows-codex |
| `FileReadHandler` | `registerFileReadHandler`（`WorkerPluginContext.java:70`） | `FileReadHandlerRegistry` | `FileTools.read_file`（核心授权解析后按扩展名分发，未命中/返回 null 回落默认文本读取） | image-vision（`ImageVisionPlugin`） |

所有注册方法的 worker 实现（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/WorkerPluginContextImpl.java`）都只是往对应注册表 `add` 一行——**注册即生效、无过滤、无校验**。

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
| `rgBinary()` | ripgrep 二进制路径（worker 侧解析结果，可能 null；`rg` AI 工具本体由 ripgrep-tool 插件自带三档解析，不经此槽） | 自带内容搜索类工具时复用 |
| `shellExecutor()` | 已组装好的 shell 执行器（授权 + 沙箱已内建；不含 rg 注入） | 注册 ShellTool |

### 1.2 worker 侧消费链路：工具怎么到模型手里

worker **复用 Spring AI 框架**，不手搓工具循环（根 `AGENTS.md` 红线）。链路全部有源码依据：

```
插件 activate(ctx)
  └─ ctx.registerToolProvider(p) → ToolProviderRegistry.register
     （WorkerPluginContextImpl.java:140；注册表 CopyOnWriteArrayList，ToolProviderRegistry.java:19-23）
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

要点：**描述写给模型看**，直接决定模型会不会用、怎么用；可缺省参数标 `required = false`；需要 per-run 状态（如 ExecContext）就在 `createTools` 里 `new` 进工具实例（工具对象每次装配新建）。注册就是 activate 里的一行：`ctx.registerToolProvider(new SubAgentToolsProvider(subAgentManager))`（`SubAgentPlugin.java:41`）。

### 1.4 工具怎么写：复用 ShellTool（命令类工具）

要做「跑命令」类工具不要自己拼 `ProcessBuilder`：`ToolContext.shellExecutor()` 已把 **PermissionGate 授权 + 沙箱 + 输出护栏** 内建好（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/ToolContextImpl.java:106-118`），plugin-api 提供 `ShellTool` 工厂（底层是 Spring AI `FunctionToolCallback.builder`，`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/shell/ShellTool.java:41-49`）。**描述必传（2026-12 决策：核心零默认）**——`powershell(String description, ShellExecutor)` / `bash(String description, ShellExecutor)`，工具用途、工作目录、实际 shell 等一律由提供者按自身事实全量声明（外部工具可用性不归后端描述宣称——rg 已收敛为独立插件 ripgrep-tool 提供，四个后端描述零 rg），null/空串运行期拒绝；写漏描述不会被核心的模板兜底（模板与后端事实脱节正是谎报之源，见 ARCHITECTURE §7.10「描述精简的第三批」）。范例：

```java
// every-agent-plugins/sandbox-wsl-ubuntu/src/main/java/dev/everyagent/plugin/sandbox/wslubuntu/WslUbuntuBashToolProvider.java:47-50（节选）
    @Override
    public boolean appliesTo(ToolContext ctx) {
        // 只在当前生效沙箱后端是 wsl-ubuntu 时贡献工具（§7.10 约定判据）
        return ctx.sandbox() != null && "wsl-ubuntu".equals(ctx.sandbox().id());
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        ShellExecutor exec = ctx.shellExecutor();
        if (exec == null) { return List.of(); }   // 拿不到执行器就不注册，绝不自己另起炉灶
        // 描述全量自写（核心零默认）：用途/工作目录按本后端事实声明；本后端不带任何
        // 搜索工具（rg 工具由独立插件 ripgrep-tool 提供），描述不提 rg——谎报红线
        return List.of(ShellTool.powershell("在系统上用 PowerShell 执行真实 OS 命令;"
                + "命令工作目录默认为任务工作区根。", exec).callback());
    }
```

### 1.5 hidden 工具（隐式工具）：常驻可执行池，读取知识包后自动暴露

`ToolProvider` 还有第二个产出方法 `createHiddenTools(ToolContext)`（`default` 空实现）：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/ToolProvider.java
public interface ToolProvider {
    List<ToolCallback> createTools(ToolContext ctx);

    /** 可选：产出「隐式工具」——常驻可执行池；frontmatter tools 绑定，读取知识包后自动暴露。 */
    default List<ToolCallback> createHiddenTools(ToolContext ctx) { return List.of(); }
}
```

hidden 工具与常规工具的差别**只在暴露形态**，执行链完全一致（拦截链、事件、授权照常）：

| | 常规工具（`createTools`） | hidden 工具（`createHiddenTools`） |
|---|---|---|
| 进请求 tool 定义（schema 下发） | 是 | **frontmatter `tools` 绑定的工具在模型读取该知识包后以完整定义进请求（自动暴露）**；未读取前不占请求 token |
| 模型怎么知道 | 请求里的 tool schema | **skill 文档**（SkillAdvisor 注入 system prompt，[advisors §5](advisors.md)）——读取前靠文档认知按名调用，读取后定义直接在请求里 |
| 怎么被调用 | 模型 tool call → 常规解析 | **始终可执行**：模型按名 tool call → 工具名解析 fallback **仅查本请求 hidden 池**（池随 prompt options 的框架 ToolContext map 下行，per-run 生命周期、独立键、每 run 组装期一次定型，与 RunContext 同路）；未命中走既有 soft-fail 兜底 |

**安全边界**：解析 fallback 只查本请求 hidden 池，**不启用 Spring 全 Bean 解析**——未知工具名不得凭全局注册表命中（跨任务/跨主体串扰防线）。

**实现纪律——不得捕获 per-run 上下文**：工具名解析与 hidden 池索引均无主体上下文，RunContext/ExecContext 必须从**工具调用时**框架传入的 ToolContext map 里取，不得在 `createHiddenTools` 构造期闭包进工具实例。`appliesTo` 对两类产出一体生效（同一 provider 的适用性判定，如 workflow 的 hidden 工具排除工作流自身主体）。

**实现契约（自动暴露链）**：

- provider 实现 `createHiddenTools` 产出工具池，worker 聚合为「工具名 → ToolCallback」hidden 池，随每 run 的 prompt options 框架 ToolContext map 独立键下行；
- skill.md frontmatter `tools:` 声明本知识包绑定的工具名（`SkillFrontmatter` 解析、`Skill.boundTools()` 透出）；
- 读取触发：模型经 `read_file` **成功**读取该知识包 → `SkillToolExposeInterceptor`（per-call 拦截链节点，id=`skill-tool-expose`，order=450）把 frontmatter `tools` 写入当前 agent 的 `exposedTools`——写入点即 plugin-api 扩展方法 `AgentContext.exposeTools(List<String>)`（default 空实现，零破坏）；
- 组装期把 `exposedTools` 与 hidden 池的**交集**以完整定义（含参数 schema）并入请求 `toolCallbacks`；集合持久化于 `agents.json` 台账条目 `exposedTools` 数组，跨轮/跨重启经 agent 重建回灌——下一轮无需重读知识包。

**skill 文档化是 hidden 工具的另一半**：文档负责 frontmatter `tools` 绑定声明与方法论（何时用、怎么编排），**不再写参数细节**——参数的唯一真源是暴露后进请求的工具定义（schema），文档里再抄一份参数就是双源漂移。写法要点——frontmatter `tools` 列表 + 方法论叙述；skill 全文会被注入 system prompt（[advisors §5](advisors.md)），写散就是拿上下文换上下文，务必精炼。

**配置**：无配置档位——自动暴露是唯一形态（旧应急回退布尔已删除，不存在「回落常规暴露」开关）。

现役范例：subagent 的 agent 族工具（`run_agent`/`list_agents`/`wait_agents`/`stop_agent`，经 agent-dispatch skill 的 frontmatter `tools` 绑定）与 workflow 插件的十一个工具（定义 CRUD 五件 + 运行/查询/重开六件，经 workflow skill 的 frontmatter `tools` 绑定）。

### 1.6 常见坑

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
| 下行（`next.proceed` 之前） | 不调 `next` 直接 `return` 合成结果 | **阻断执行**：模型收到你合成的 `ToolExecutionResult`，真实工具不跑（对照：调 `next` 即放行；unattended 经 `WorkerServices.slashTokens().hasKind(ctx.subjectId(), "unattended.mode")` 判任务级 slash token，非无人值守时直接放行，`:34-36`） | unattended 对「纯 ask_user 轮」合成自动回答短路（`every-agent-plugins/unattended/src/main/java/dev/everyagent/plugin/unattended/UnattendedToolInterceptor.java:73-80`） |
| 下行改写入参 | `next.proceed(包装后的 ctx)` | 想改真实执行所见的 prompt/chatResponse，就包一个新 `ToolExecutionContext` 传下去——链尾用的是**你传下去的那个 ctx**（`delegate.executeToolCalls(c.prompt(), c.chatResponse())`，`ToolExecutionChainExecutor.java:34`） | **无内置范例，未实测** |
| 上行（`next.proceed` 返回之后） | 拿返回的 `ToolExecutionResult` 重建 | **改写结果**：重建 `conversationHistory` 里的 `ToolResponseMessage` 后返回新结果——落盘/推送/回灌模型三路全覆盖 | secret-redaction 掩码凭据（`every-agent-plugins/secret-redaction/src/main/java/dev/everyagent/plugin/secretredaction/SecretRedactionInterceptor.java:62-130`） |

**order 语义**：升序 = 执行序（洋葱模型：order 小的在外层，先下行、后上行）；float 允许任意插位；同 order 按注册顺序（稳定排序，`ToolExecutionChainExecutor.java:31`）。现役链序（架构 §7.14.3）：rg-hint=0 → tool-args-guard=50（worker 内置参数护栏：必填参数缺失时 `synthesize` 失败串、跳过真实调用——你的插件工具若把本应可选的参数漏标 `required = false`，模型省略该参数时会在这里被拦下）→ unattended=100 → file-change=400 → tool-events=500 → secret-redaction=900（最内层、紧贴真实执行，`SecretRedactionInterceptor.java:62` 类注释给出取值理由：在权限门/审计之后、事件发射之前）。

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
| 4 | `SandboxPathRegistry` 把注册意图按当前生效后端**物化**为 `mount()` 映射表（宿主路径 ↔ 沙箱内路径），advisor/工具链查表翻译 | 架构 §7.10；登记点 `AgentBuilder.registerSandboxPaths`（`AgentBuilder.java:157-`）、`WorkerBeanConfiguration.skillAdvisorProvider`（`WorkerBeanConfiguration.java:76-82`） |

### 3.2 接口定义（原码摘录）

`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/SandboxProvider.java`：

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/SandboxProvider.java
public interface SandboxProvider {

    /** 后端 id（如 "wsl-ubuntu"/"codex"/"docker"/"none"）。 */
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

- **选择规则**（`SandboxProviderRegistry.select`，`:84-137`）：显式 `type` 精确命中且 `isAvailable()` → `create()`；命中但不可用 / 无对应 provider → 转 auto；auto = `isAvailable()` 过滤后取 `priority()` 最高者（**只对胜出者调 `create()`**，重副作用如 codex 的 UAC setup 不因探测被提前触发）；`enabled=false` 或 `type=none` → 返回 null，调用方退化 DIRECT。配置键 `worker.sandbox.type`，旧值归一（`windows-mic`/`acl` → auto 并 WARN 等）见 `OsSandbox.normalizeBackend`（`OsSandbox.java:182-201`）。
- **时机红线**（§7.10 原文口径）：全部 SandboxProvider 都由插件在 `PluginLoader` 的 `@PostConstruct` 里注册，而 `PluginLoader → WorkerServices → OsSandbox` 的构造依赖链决定了 OsSandbox **必然先于插件激活完成初始化**——后端不得在 `@PostConstruct` 一次性定论。`OsSandbox` 曾因此在启动期 `select()` 恒得「无可用后端」（类 Javadoc 记录了这次翻车，`OsSandbox.java:81-87`）；现改为按**注册表代次惰性解析**（每次注册/注销自增，`SandboxProviderRegistry.java:49-58`）。
- 现役后端：`wsl-ubuntu`(10)、`codex`(Windows 8)、`direct`（无 Provider，OsSandbox 自身兜底）；`windows-mic` 已随 sandbox-windows-mic 插件移除（2026-10）。⚠️ 仓库现状：`sandbox-wsl-ubuntu` 的 `plugin.json` 写 `"enabled": false`（内置扫描期整目录跳过），实际在册的 SPI 后端只有 codex——启用 wsl-ubuntu 需改清单并重启 worker（`every-agent-plugins/sandbox-wsl-ubuntu/plugin.json:9`）。

### 3.4 与 worker 命令执行链的关系（§7.10 语境）

沙箱后端不执行命令；命令的**实际执行**有两条互不相同的路：

| 路 | 谁执行 | 说明 |
|---|---|---|
| worker 核心命令工具 | `CommandExecutor`（`every-agent-worker/src/main/java/dev/everyagent/worker/tools/CommandExecutor.java`） | cwd 锁 `Path.of(task.workspaceRoot())`（`:95`）；执行前 `gate.requireCommand`（危险动词/越界路径授权，`:90`）与 `gate.requirePrivilege`（`:128`）；实际 spawn 走 `OsSandbox.spawnNative/spawnToFileRedirected`（`:137-196`，超时/输出护栏/凭据 env 剔除内建） |
| 沙箱插件自己的命令工具 | 插件自带 CommandExecutor（如 `every-agent-plugins/sandbox-windows-codex/.../CodexCommandExecutor.java`） | §7.10：「只有某后端才做得到的隔离能力，其命令与状态一律归该插件」——完全自由实现、自扫描路径、自定授权策略，经 `ToolProvider.appliesTo` 控制生效 |

范例：完整后端见 `every-agent-plugins/sandbox-wsl-ubuntu/`（原最小样本 sandbox-windows-mic 已移除）。

范例（已移除的最小样本 sandbox-windows-mic 曾承担此位,现以 wsl-ubuntu 为准）：完整后端见 `every-agent-plugins/sandbox-wsl-ubuntu/`——`WslUbuntuSandboxProvider`（id/priority 见 `WslUbuntuSandboxProvider.java:41,81`，`create` 在 `:86`）,强隔离样本见 sandbox-windows-codex。

```java
// every-agent-plugins/sandbox-wsl-ubuntu/src/main/java/dev/everyagent/plugin/sandbox/wslubuntu/WslUbuntuSandboxProvider.java:41-90（骨架,节选）
public final class WslUbuntuSandboxProvider implements SandboxProvider {

    @Override public String id() { return "wsl-ubuntu"; }
    @Override public boolean isAvailable() { /* 轻量探测:不弹 UAC、不做 setup */ }
    @Override public int priority() { return 10; }               // auto 模式的兜底位次
    @Override public SandboxBackend create(SandboxConfig config) {
        return new WslUbuntuSandboxBackend(props, workspaces, pluginDir);  // 胜出才付创建成本
    }
}

// 入口(WslUbuntuSandboxPlugin):沙箱后端 + 命令工具一起注册——后端只管挂载/清理,命令工具才是 AI 看得见的东西
public void activate(WorkerPluginContext ctx) throws Exception {
    ctx.registerSandboxProvider(...);
    ctx.registerToolProvider(new WslUbuntuBashToolProvider(...));
}
```

### 3.6 常见坑

| 坑 | 说明 |
|---|---|
| 在 `@PostConstruct` / 构造器里探测或定论后端 | 时序红线：注册晚于你初始化，见 §3.3；探测放 `isAvailable()`，创建放 `create()` |
| 只注册 SandboxProvider 就指望有命令工具 | 后端与命令工具是**两个注册**：AI 能用的命令工具来自配套的 `ToolProvider`（§3.5 范例两行并列） |
| `mount()` 不幂等 | 契约要求重复调用相同路径不重复挂载（`SandboxBackend.java:44`） |
| priority 记反 / 改配置即生效 | **数值越大优先级越高**（auto 取 max，`SandboxProviderRegistry.java:127-133`）；配置随 worker 启动读取，插件启停一律**重启 worker** |

## 4. FileReadHandler —— read_file 按扩展名接管

**一句话定位**：核心 `read_file` 对文本文件提供默认支持，本 SPI 让插件按扩展名接管非文本类型的读取（图片 → 图像附件注入；未来 PDF → 提取文本回灌）。

### 4.1 接口定义（原码摘录）

```java
// every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/FileReadHandler.java
public interface FileReadHandler {

    /** 插件 id。 */
    String pluginId();

    /** 支持的扩展名集合（小写含点，如 {".png", ".jpg"}）。 */
    Set<String> extensions();

    /**
     * 读取一个文件。
     * line_start / line_end 等行区间参数对接管处理器无意义，核心不会传递。
     *
     * @param ctx  任务上下文
     * @param path AI 调 read_file 时传入的原始路径参数
     * @param file 核心授权解析后的宿主绝对路径
     * @return 读取结果；返回 null 等价于「放弃接管」（回落核心默认文本读取）
     * @throws RuntimeException 处理失败时抛出，异常信息按工具执行失败文本回灌 AI
     *         （fail-fast，不降级为乱码文本）
     */
    FileReadResult read(ExecContext ctx, String path, Path file);
}
```

返回值 `FileReadResult(text, attachments)`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/FileReadResult.java`）的结果契约：

| 结果 | 契约 |
|---|---|
| `text` | 作为 read_file 的工具结果串**直接回灌 AI** |
| `attachments` | 项结构 `{type:"image", dataUrl, fileName, mimeType}`（dataUrl 为 base64 data URL），合并进 `TaskEntry.metadata.attachments` 持久化（跨轮可读），由核心 `FileAttachmentAdvisor` 注入模型——AI 在工具结果后的**下一次**模型调用即可看到图片；核心做全等去重，同一图片读两次不重复注入；空列表 = 无附件 |
| 返回 `null` | 放弃接管：回落核心默认文本读取，行为与无插件时一致 |
| 抛异常 | 异常信息按工具执行失败文本回灌 AI（fail-fast，不降级为乱码文本） |

### 4.2 注册方式

```java
// activate() 里的一行；扩展名约定小写含点（注册侧自动小写归一），
// 同扩展名后注册覆盖先注册并打 WARN（按扩展名一对一映射的注册表模式）
ctx.registerFileReadHandler(new MyImageReadHandler(settings));
```

worker 侧消费点：`FileTools.read_file`——扩展名命中 handler 时，核心**先行授权解析**（`PermissionGate` READ 档授权 + 工作区沙箱 jail + realpath 校验）再把宿主绝对路径交给处理器；未命中任何 handler 或处理器返回 null，走核心默认文本路径。`read_file` 工具描述已声明「图片等非文本类型文件可由插件处理器按扩展名接管」。

### 4.3 安全模型：路径由核心授权解析后传入

`read` 的 `file` 参数是核心授权解析后的宿主真实绝对路径——**实现方直接读 `file` 即可，不得另行自行解析路径**（防绕过授权）。read_file 是 AI 发起的访问，必须走完整授权链（工作区外路径先弹授权 ask，拒绝/超时回灌错误文本）；用户输入里的 `@` 文件引用是纯路径文本（任何文件类型一致，不做类型化处理），AI 需要内容时调 `read_file` 走本授权链。

### 4.4 与 @ 文件引用的关系

用户输入侧的 `@` 引用（工作区相对路径/外部绝对路径）回归**纯路径文本**：不做插件类型分发，AI 看到路径后按需调用 `read_file`——文本类型走核心默认读取，图片等类型由本 SPI 按扩展名接管。未来 PDF 等类型复用本 SPI：接管处理器返回提取后的文本内容（attachments 为空列表）即可。

## 5. ExecContext —— 槽位判据与字段表

### 5.1 §14.11 红线原文（口径出处）

[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §14.11（执行上下文管道）：

> **槽位判据**：ExecContext 槽位 = 任何执行主体都必然具备的核心属性与端口(subjectId/workspaceRoot/workspaceId/snapshot/emitter/agentFactory/interaction/metadata/dataDir/terminal/agents 活动实体注册表;configId 不设独立槽,经 snapshot().configId() 取);**插件功能与主体特有槽位不进核心接口**……

> **禁止**：横切层 import `TaskEntry/TaskRuntime/TaskInfo`;绕过 `ctx.agentFactory()` 手工组装四件套 map;绕过 `ctx.interaction()` 手动填 taskId context;worker 侧新增 properties 透传通道。工作流层实现 `WorkflowRuntime implements ExecContext` 后,同一条授权链、同一批 advisor、同一工具链零改动复用。

本篇四个 SPI 的上下文全部 `extends ExecContext`（`ToolContext` / `ToolExecutionContext`；`FileReadHandler.read` 直接收 `ExecContext`），插件代码**只准**从这些槽位取执行数据。

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
| `metadata()` | `Map<String,Object>` | 随 meta.json 落盘的持久数据（如 attachments）；**任务级开关不走 metadata**，其真相源是 `slash-tokens.json`（`WorkerServices.slashTokens()` 查询） | `FileTools`（read_file 接管附件合并）写 `attachments` | 只承载持久数据，不混运行时瞬态（§14.11） |
| `dataDir()` | `Path` | 主体数据目录（grants.json 等落盘） | 授权链 | — |
| `terminal()` | `boolean` | 主体是否已收口 | leak-guard：终态后不再发射事件 | — |
| `interaction()` | `InteractionService` | 预绑定交互口（ask 的 context 自动补 subjectId） | human-authorization 授权弹窗、AskUserTool 等 | §14.11「绕过 ctx.interaction() 手动填 taskId context」禁止 |
| `agents()` | `Map<String,AgentContext>` | 本主体活动 agent 注册表（主 agent + 各插件派生） | `AgentBuilder.build()` 自动 put（`:333`，不发事件） | §14.11「插件不再手动 put/emit」 |
| `METADATA_ATTACHMENTS_KEY` | 常量 `"attachments"` | metadata 中附件列表键名 | `FileTools`（read_file 接管）/`FileAttachmentAdvisor` | — |

### 5.3 「命令跑在哪个环境/目录」到底由什么决定

| 问题 | 答案 | 证据 |
|---|---|---|
| 跑在**哪个目录** | `workspaceRoot()` 槽位：命令执行器把子进程 cwd 锁定在工作区根 | `CommandExecutor.java:30,95` |
| 跑在**哪个环境**（宿主 / WSL / 受限令牌） | **不是 ExecContext 字段**，由生效沙箱后端决定：worker 按 `worker.sandbox.type` + 注册表选出 SandboxProvider，`OsSandbox` 门面把结论暴露给 `ToolContext.sandbox()`；插件的命令工具自己决定怎么用后端 | `OsSandbox.java:102-161`、`ToolContext.java:25-33`、架构 §7.10 |
| 工作区**只读还是可写** | 工作区根按 `READ_WRITE` 登记挂载意图（§7.16 externalRoots 同为读写）；系统技能目录也读写挂入（§7.17），另有一次面向 prompt 路径翻译的 `READ_ONLY` 登记 | `AgentBuilder.java:158-171`、`WorkerBeanConfiguration.java:63-70` |
| **jail 校验在谁那边** | worker 侧：文件路径责任链（`WorkspaceAllowCheck`→…→`AuthorizeCheck`，先 realpath 再校验前缀）+ `WorkspaceSandbox.resolveExisting`（词法 + realpath 双校验）。插件**复用**这些通道（经 `ctx.workspaces()`），不得自造越界放行 | 架构 §7.8；红线：不得绕过 gate 放行越界 IO（根 `AGENTS.md`） |

## 6. 工具红线速查

写工具/沙箱类插件前，三条架构红线必须过一遍（原文见 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)）：

| 红线 | 出处 | 与本篇四个 SPI 的关系 |
|---|---|---|
| **PermissionGate**：危险操作必须经用户授权——AI 工具的工作区外路径访问先弹 `kind=authorization` 的 ask（拒绝/本轮运行/本任务三档）；命令中的危险动词**仅当命令引用可能落在工作区外**时才需授权，工作区内增删改查直接放行（cwd 锁定 + 沙箱可写性契约兜底）；**用户 `@` 显式选择的工作区外部授权根 = 已授权**（完全读写 READ+WRITE+EXEC），文件工具与命令串中引用其内的路径都不再弹窗（§7.17）；拒绝抛异常回灌模型 | §7.8 / §7.17 | 用 `ctx.shellExecutor()` 就自动带上；自己碰文件系统要走 `ctx.workspaces()` 的 jailed 解析；**不得绕过 gate 直接放行越界 IO** |
| **沙箱契约（codex）**：沙箱进程 WRITE_RESTRICTED 受限令牌 + capability SID，工作区树可写、区外物理只读；越界写拦截由 PermissionGate 责任链兜底 | §7.10 | SandboxProvider 实现者不要试图靠改 ACL/打标记做隔离；隔离做不到的部分交给授权链 |
| **ExecContext 管道**：横切层只见 ExecContext 槽位，不见 `TaskEntry/TaskRuntime/TaskInfo`；禁止 properties 黑盒 map 与强转再现；插件功能与主体特有槽位不进核心接口 | §14.11 | 四个 SPI 的 ctx 全部 `extends ExecContext`；插件代码 import 任务域类型即违红线 |

## 下一步读

- 挂在模型调用链上的增强（AdvisorProvider / order 坐标 / 禁插段）：[advisors.md](advisors.md)
- 任务生命周期节点与自注册 RPC（`queue.loop`/`consume.input` 所在的那条链）：[task-and-rpc.md](task-and-rpc.md)
- 插件单测怎么写（只依赖 plugin-api 的 JUnit5 范式）：[调试与测试](../guides/debugging-and-testing.md)
