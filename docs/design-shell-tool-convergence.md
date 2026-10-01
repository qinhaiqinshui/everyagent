# Shell 工具与命令执行器收敛方案

> 状态：已确认方向，待实施。插件机制生命周期（后端选中回调等）不在本次范围。
> 背景讨论：3 个 CommandExecutor 重复、多个 shell 工具壳同构、appliesTo 语义辨析。

## 1. 现状

| 沙箱后端 (`SandboxBackend.id`) | 注册的工具名 | 工具类 | 执行器 |
|---|---|---|---|
| `direct` / `windows-mic`（Windows 原生域） | `powershell` | worker `PowerShellTool` | worker `CommandExecutor` |
| `direct`（非 Windows） | `bash` | worker `BashTool` | worker `CommandExecutor` |
| `codex` | `powershell` | `CodexPowerShellTool`（CodexBashToolProvider 内嵌） | `CodexCommandExecutor` |
| `wsl-ubuntu` | `bash` | `WslUbuntuBashTool`（WslUbuntuBashToolProvider 内嵌） | `WslUbuntuCommandExecutor` |

三个执行器的执行链本质不同（原生 ProcessBuilder / 沙箱账户 runner 会话 / WSL 发行版内 exec），这是后端差异的本体，**不合并**。

重复的纯公共部分：

1. `format(...)` — 三份近乎相同的「stdout + [stderr] + 超时尾注 + exit code 尾注」；
2. `truncate(s, 200)` — 三份逐字相同的审计日志截断；
3. `POWERSHELL_PREFIX` — worker 与 codex 两处重复；
4. `MAX_OUTPUT_CHARS = 1_000_000` — codex 与 wsl-ubuntu 重复；
5. command 空串检查 — 三处重复；
6. rg 目录注入 PATH — worker 与 codex 两处不同写法做同一件事。

四个「shell 工具」形态同构（一个 `command` 入参 → `String execute(command, shell)` → 格式化文本），但各包一层壳，描述文本漂移（powershell 工具描述有「输出编码已自动设为 UTF-8」，codex 那份没有；bash 措辞也不同）。

## 2. 架构约束（依据 ARCHITECTURE.md 三层解耦）

- 三层只依赖 `every-agent-contract` / plugin-api，互相零依赖。
- worker `CommandExecutor` 依赖 worker 内部类型（`PermissionGate` / `TaskEntry` / `OsSandbox`），插件不能反向依赖 worker → **统一执行器类物理上没有落点**。
- 执行机制必须留在各自后端；能收敛的只有**对外契约**（工具名/入参/描述/返回格式）与**纯公共静态逻辑**。
- `ExecResult` 已抽到 plugin-api 的先例可复用。

## 3. 方案

### 3.0 rg 归属下放（前置调整）

**现状**：rg 由 worker 核心 `RipgrepBinary` 组件管理（定位程序根 `runtime/bin/rg.exe` 或 `rg`），`CommandExecutor` / `CodexCommandExecutor` 在执行时注入 PATH。

**改为**：rg 由**各沙箱插件自己带、自己注入**，worker 核心不再管理 rg。

- **每个沙箱插件自带 rg 二进制**（放在插件目录内，如 `<pluginDir>/bin/rg.exe` 或 `<pluginDir>/bin/rg`），确保无其他插件时也能用。
- **插件 activate 时检查**：目标环境（子进程 PATH）是否已包含 rg；若已包含（其他插件已注入或系统自带），不重复添加；若未包含，把自己的 rg 目录加进去。
- **worker 核心删除 `RipgrepBinary`** 及 `CommandExecutor` 中的 rg 注入逻辑；`ToolContext.rgBinary()` 方法删除（或改语义为「当前后端提供的 rg 路径」）。
- **搜索服务（`FsSearchService` / `TaskSearchService`）**：它们也依赖 rg，需评估——若 worker 不再带 rg，这两个服务要么改为从当前激活后端的插件获取 rg 路径，要么保留 worker 侧的 rg 但标记为「内部搜索专用，不注入 shell」。**建议**：保留 worker 的 rg 定位能力供内部搜索用，但 shell 工具的 rg 注入完全交给插件。

### 3.1 plugin-api 新增 shell 工具小包

```
dev.everyagent.plugin.api.shell
├─ ShellExecutor          @FunctionalInterface  String execute(String command, String shell)
├─ ShellTool              通用工具类（name + ShellExecutor，编程式 ToolCallback）
└─ ExecResults            format / truncate / POWERSHELL_PREFIX / MAX_OUTPUT_CHARS
```

- **ShellExecutor**：`String execute(String, String)`。三个现有执行器签名已完全一致，直接 `exec::execute` 适配，零改动。
- **ShellTool**：取代 `PowerShellTool` / `BashTool` / `CodexPowerShellTool` / `WslUbuntuBashTool` 四个壳。
  - 工厂方法 `ShellTool.powershell(exec)` / `ShellTool.bash(exec)`；
  - 描述分两层：
    - **默认基线描述**（工具名 / 工作目录 / stdin 为 null 设备等与后端无关的通用契约）由 ShellTool 内置；
    - **开放描述覆盖/追加接口**（如 `description(String)` 全量覆盖、`appendDescription(String)` 追加备注），由后端按自身能力定制；
  - rg 提示统一由各后端在追加描述中写明（各后端都带 rg，但注入方式不同：codex 经 runner classpath、wsl-ubuntu 发行版自带、windows-mic / direct 经 PATH 注入）。
- **ExecResults**：即上文的 CommandExecResults，与 ShellTool 同包收敛。
  - `format(ExecResult)` 及带 truncated / interrupted 标志的重载；
  - `truncate(String, int)`；
  - `POWERSHELL_PREFIX` 常量；
  - `MAX_OUTPUT_CHARS` 常量。

依赖可行性：plugin-api 已依赖 Spring AI（`ToolProvider` 返回 `ToolCallback`），ShellTool 落此无问题。

### 3.2 打通执行器注入 SPI

`ToolContext` 增加方法：

```java
/** 已组装好的 shell 执行器（授权 + 沙箱已内建），插件用它注册 ShellTool。 */
default ShellExecutor shellExecutor() { return null; }
```

worker `ToolContextImpl` 构造时把 `new CommandExecutor(...)` 的 `::execute` 塞入。windows-mic 插件不接触 worker 类即可拿到带门禁 + 沙箱的执行器。

### 3.3 注册权交还后端（不主动注册）

- `wsl-ubuntu`：`WslUbuntuBashToolProvider` 改为 `List.of(ShellTool.bash(exec).appendDescription("rg 已加入 PATH...").callback())`，appliesTo `wsl-ubuntu` 不变。rg 由发行版自带（安装脚本 / apt），插件 activate 时检查确认。
- `codex`：`CodexBashToolProvider` 改为 `List.of(ShellTool.powershell(exec).appendDescription("rg 已加入 PATH...").callback())`，appliesTo `codex` 不变。rg 由插件自带（`<pluginDir>/bin/`），activate 时检查 PATH 是否已包含 rg，未包含则注入。
- `windows-mic` 插件：**新增** `WindowsMicShellToolProvider`，appliesTo `windows-mic`，用 `ctx.shellExecutor()` 注册 `ShellTool.powershell(...)`（追加 rg / UTF-8 提示）。rg 由插件自带（`<pluginDir>/bin/`），activate 时检查 PATH 是否已包含 rg，未包含则注入。
- `direct`（无沙箱插件）：保留 worker 内置最小 `DirectShellToolProvider`，appliesTo 仅 `sandbox == null`，按 OS 选 bash / powershell（追加 rg / UTF-8 提示）。rg 由 worker 核心保留（内部搜索仍需），但 shell 注入逻辑移到此处。
- **删除** `BuiltInToolProviders` 中的 `PowerShellToolProvider` + `BashToolProvider`，及 `PowerShellTool` / `BashTool` 壳类。worker 不再凭 `os.name` + backend id 替后端做决定。

### 3.4 appliesTo 语义（结论：保留）

- 插件激活（`EveryAgentPlugin.activate`）是启动期一次性注册 provider 工厂；
- 工具实例在**每次装配 agent** 时经 `createTools(ToolContext)` 用当次任务上下文创建；
- `appliesTo` 表达「provider 已注册，但本进程激活的后端不是我」这层过滤——当前沙箱后端是进程级单例（`OsSandbox` 启动解析一个 delegate），但「哪个后端配哪个工具」的知识应留在各插件内，worker 核心不维护后端 id → 工具的硬编码映射；
- `AdvisorProvider` 有同款 appliesTo 循环，机制不只为沙箱服务。

可顺手简化：沙箱类 provider 的 appliesTo 全是 `ctx.sandbox() != null && "<id>".equals(ctx.sandbox().id())` 模板，plugin-api 提供便捷工厂收掉样板，例如：

```java
ToolProvider.forSandbox("codex", ctx -> List.of(ShellTool.powershell(exec).callback()));
```

### 3.5 不变量

- 三层解耦不破：插件只依赖 plugin-api；worker `CommandExecutor` 实现类仍只在 worker 内，对外只暴露 `ShellExecutor` 函数式接口。
- 执行机制差异留各后端：`ShellExecutor` 是 lambda，背后是 ProcessBuilder / runner 会话 / WSL exec，互不感知。
- 「一个工具一个职责」不违反：`ShellTool` 只做参数透传 + 契约固化；格式化在 `ExecResults`；授权 / 沙箱在执行器内。
- worker 特有的 CLIXML 剥除、临时 .ps1 绕行（Windows 原生域问题）留在 worker `CommandExecutor`，不下沉。

## 4. 明确不做（本次范围外）

- 插件机制补「沙箱后端选中回调」/ 调整 activate 时机：方向 B，独立后续优化。
- 沙箱后端 per-task 化：当前是进程级配置，不涉及。
- 三个 CommandExecutor 的执行链合并：本质差异，不抽象。

## 5. 实施清单

0. **rg 下放（前置）**：
   - 各沙箱插件（codex / windows-mic）把 rg 二进制放入 `<pluginDir>/bin/`；
   - wsl-ubuntu 确认发行版自带 rg（安装脚本已含）；
   - worker 核心 `CommandExecutor` 删除 rg 注入逻辑；`RipgrepBinary` 保留但标记为「内部搜索专用」；
   - `ToolContext.rgBinary()` 删除或改语义。
1. plugin-api 新增 `dev.everyagent.plugin.api.shell` 包：`ShellExecutor` / `ShellTool` / `ExecResults`。
2. `ToolContext` 增加 `shellExecutor()` default 方法；`ToolContextImpl` 装配 `CommandExecutor::execute`。
3. worker：删 `PowerShellTool` / `BashTool` / 对应两个 provider；`CommandExecutor` 改用 `ExecResults`（删重复的 format / truncate / 常量）；新增 `DirectShellToolProvider`；`BuiltInToolProviders` 相应调整。
4. codex 插件：`CodexCommandExecutor` 改用 `ExecResults`；`CodexBashToolProvider` 改用 `ShellTool`（删内嵌 `CodexPowerShellTool`）；activate 时检查并注入 rg。
5. wsl-ubuntu 插件：`WslUbuntuCommandExecutor` 改用 `ExecResults`；`WslUbuntuBashToolProvider` 改用 `ShellTool`（删内嵌 `WslUbuntuBashTool`）。
6. windows-mic 插件：新增 `WindowsMicShellToolProvider`；activate 时检查并注入 rg。
7. plugin-api 加 `ToolProvider.forSandbox(id, fn)` 便捷工厂（可选）。
8. 各模块测试同步更新（`CodexCommandExecutorTest`、`CodexBashToolProviderTest`、`PermissionGateTest` 等引用处）。
9. 提交：feat 前缀，一次一事（可先提交 rg 下放 + ExecResults 下沉，再提交 ShellTool 收敛）。
