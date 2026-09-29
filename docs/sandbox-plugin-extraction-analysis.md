# 沙箱实现抽离为独立插件 — 分析与方案

## 一、现状：沙箱架构全景

### 1.1 分层结构

```
┌─────────────────────────────────────────────────────────┐
│              every-agent-plugin-api (SPI)               │
│  SandboxProvider (工厂) → create(SandboxConfig) →      │
│  SandboxBackend (执行) → ExecResult                    │
│  SandboxConfig (record, props 字段携带 WorkerProperties)│
└─────────────────────────────────────────────────────────┘
                           ↑ 依赖
┌─────────────────────────────────────────────────────────┐
│              every-agent-worker (实现)                   │
│                                                         │
│  ┌─ plugin/registry/ ──────────────────────────────┐   │
│  │  SandboxProviderRegistry                          │   │
│  │    register/unregister/select(配置→Backend)       │   │
│  └──────────────────────────────────────────────────┘   │
│                          ↑ 注册                          │
│  ┌─ plugin/adapters/ ───────────────────────────────┐   │
│  │  BuiltInSandboxProviders (@PostConstruct)         │   │
│  │    ├─ WindowsMicSandboxProvider → Backend          │   │
│  │    ├─ WslBwrapSandboxProvider   → Backend          │   │
│  │    └─ WslDirectSandboxProvider  → Backend          │   │
│  │  DirectSpawnSupport (包私有, spawnNative 共享)     │   │
│  └──────────────────────────────────────────────────┘   │
│                          ↓ 委托                          │
│  ┌─ os/ ────────────────────────────────────────────┐   │
│  │  OsSandbox (@Component, 门面+DIRECT 回退)         │   │
│  │    ├─ SandboxProviderRegistry.select() → delegate │   │
│  │    └─ 回退: 直接 ProcessBuilder (DIRECT)          │   │
│  ├─ os/windows/ ────────────────────────────────────┐   │
│  │  WindowsSandbox (static, JNA Win32 API)           │   │
│  │  Win32Ex (JNA 扩展原语)                           │   │
│  │  WindowsAcl (DACL 授权)                           │   │
│  │  WindowsIntegrity (Low IL 标注)                   │   │
│  ├─ os/wsl/ ────────────────────────────────────────┐   │
│  │  WslBwrapSandbox (static, WSL+bubblewrap)         │   │
│  │  WslDirectSandbox (static, WSL 直连)              │   │
│  │  WslPathMapper (路径映射)                         │   │
│  │  WslUmounter (卸载器)                             │   │
│  └──────────────────────────────────────────────────┘   │
│  ┌─ config/ ────────────────────────────────────────┐   │
│  │  WorkerProperties.Sandbox (配置中枢)              │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 1.2 三个现有沙箱后端

| 后端 | id | 优先级 | 隔离策略 | 实现类 |
|------|-----|--------|---------|--------|
| **Windows MIC** | `windows-mic` | 5 | Restricted Token + Medium IL + Job Object (进程/内存/CPU 上限) | `WindowsSandbox` (JNA Win32 API) |
| **WSL Bwrap** | `wsl-bwrap` | 20 | WSL2 发行版 + bubblewrap 挂载命名空间隔离 (--bind 白名单 + --unshare-net) | `WslBwrapSandbox` (static utility) |
| **WSL Direct** | `wsl-direct` | 10 | WSL2 发行版 root 完整权限直连 (automount 关闭 + 手动挂载) | `WslDirectSandbox` (static utility) |

### 1.3 Provider + Backend 适配层

每个沙箱后端由一对类组成：

**Provider** (工厂, 实现 `SandboxProvider`):
- `WindowsMicSandboxProvider` → `id()="windows-mic"`, `isAvailable()=Windows`, `priority()=5`
- `WslBwrapSandboxProvider` → `id()="wsl-bwrap"`, `isAvailable()=probe(WSL)`, `priority()=20`
- `WslDirectSandboxProvider` → `id()="wsl-direct"`, `isAvailable()=probe(WSL)`, `priority()=10`

**Backend** (执行, 实现 `SandboxBackend`):
- `WindowsMicSandboxBackend` → 委托 `WindowsSandbox.run()`
- `WslBwrapSandboxBackend` → 委托 `WslBwrapSandbox.run()`
- `WslDirectSandboxBackend` → 委托 `WslDirectSandbox.run()`

所有 Backend 的 `spawnNative()` 都委托 `DirectSpawnSupport.runDirect()`。

### 1.4 注册机制

```
BuiltInSandboxProviders (@Component, @PostConstruct)
  → SandboxProviderRegistry.register(provider)
  
OsSandbox (@Component, @PostConstruct)
  → SandboxProviderRegistry.select(config)
  → delegate = 选中的 SandboxBackend (或 null → DIRECT 回退)
```

### 1.5 配置

`WorkerProperties.Sandbox` 嵌套类：
- `type`: `auto`(默认) | `wsl-direct` | `wsl-bwrap` | `windows-mic` | `none`
- `enabled`, `timeoutMs`, `memoryLimitMb`, `cpuHardCapPercent`, `activeProcessLimit`
- `networkPolicy`, `allowNetwork`, `allowPrivilegeEscalation`, `interceptPrivilege`
- `persistentState`, `persistentRoot`
- `Wsl` 子配置: `distro`, `tarball`, `roIslands`, `pwshEnabled`, `loginShell`

`SandboxConfig` (SPI record) 通过 `config.props()` 携带 `WorkerProperties` 实例，后端强转读取。


## 二、抽离方案

### 2.1 核心原则

1. **Worker 核心不能保留任何 WSL 概念，核心不能依赖插件。**
2. **"让 AI 访问宿主"是一个独立的工具，与沙箱无关。** 核心提供，默认关闭，"允许AI访问电脑"开关控制。
3. **沙箱插件提供自己的命令工具，完全自由。** 沙箱自己实现 CommandExecutor、自己扫描路径、自己决定授权策略、自己提供 ToolProvider（甚至可以不提供）。
4. **核心做挂载中间人。** 核心收集需要被沙箱访问的宿主路径，调沙箱的 `mount()` 方法，沙箱返回沙箱内路径。核心保存映射表，自己做路径翻译。消费方只调核心，不感知沙箱。
5. **InteractionService 纯透传。** 不绑 taskId/agentId，只管 questions + timeout + 上层注入的标记字段。
6. **ToolContext 由核心准备。** agentId/taskId/workspaceRoot 等上层信息像 emitter 一样注入，沙箱只取自己需要的字段，不感知上层语义。

### 2.2 整体共存架构

```
用户发消息 → 核心:
├── 构建 ToolContext（agentId/taskId/workspaceRoot/sandbox/...）
├── 遍历 ToolProviderRegistry
│   ├── 核心的 FileToolsProvider         ← 文件工具（核心提供，路径翻译走 SandboxPathRegistry）
│   ├── 核心的 AskUserToolProvider       ← ask_user 工具（核心提供）
│   ├── 核心的 HostCommandToolProvider   ← 宿主访问工具（DIRECT 默认沙箱，默认关闭）
│   │   ├── Windows → PowerShellTool + CommandExecutor（含授权扫描）
│   │   └── Linux   → BashTool + CommandExecutor（含授权扫描）
│   ├── 沙箱插件的 ToolProvider(s)       ← 沙箱自由提供（或多个，或零个）
│   │   └── 沙箱自己的 BashTool + 沙箱自己的 CommandExecutor（沙箱内路径、沙箱自己的授权策略）
│   └── 其他插件的 ToolProvider
└── AgentBuilder 聚合所有 appliesTo()=true 的工具
```

### 2.3 SandboxBackend SPI 接口（最终极简）

```java
public interface SandboxBackend {
    /** 后端 id。 */
    String id();

    /**
     * 挂载一个宿主路径到沙箱内，返回沙箱内路径。
     * 核心调此方法，沙箱按自己的方式挂载（drvfs / bind mount / 不挂载）。
     * 
     * - DIRECT: 不挂载，返回原宿主路径。
     * - wsl-ubuntu: mount -t drvfs，返回 /c/Users/...（原路径形态）。
     * - docker: -v bind mount，返回 /workspace（或自定义挂载点）。
     * - windows-mic: 不挂载（命令跑在宿主上），返回原路径。
     * 
     * 核心保存 {宿主路径 → 返回值} 映射表，路径翻译时查表，不调沙箱。
     */
    default String mount(Path hostPath, Access access) { return hostPath.toString(); }

    /**
     * 工作区被删除时调用;后端 best-effort 清理挂载等;默认 no-op。
     */
    default void onWorkspaceRemoved(Path root) {}

    /** 挂载访问语义。 */
    enum Access { READ_ONLY, READ_WRITE }
}
```

**删除的 SPI 方法：**
- `spawnSandboxed()` — 核心的宿主访问工具自己 ProcessBuilder，沙箱插件自己的 CommandExecutor 自己执行（`wsl -d xxx` / `docker run`）。没人调它了。
- `spawnSandboxedWindows()` — WSL 专属 hack，让 powershell 命令回宿主执行。不需要了。
- `spawnNative()` — 只被 NativeGit（git 插件）调用，不是沙箱能力，是通用"宿主进程执行"服务。移出 SPI，逻辑保留在 worker 核心供 git 插件调。
- `toSandboxPath()` / `toHostPath()` — 路径翻译由核心做。核心调 `mount()` 拿到映射关系后自己查表翻译，不依赖沙箱。
- `isWslBackend()` / `isWslBwrap()` / `isWslDirect()` — WSL 专属判定。
- `registerBashTool()` — 工具注册策略。
- `requiresCommandGate()` — 授权策略。
- `translateCommandForGate()` — 命令路径翻译。

**SandboxBackend 纯粹是：挂载 + 工作区生命周期 + 标识。不执行命令、不翻译路径、不涉及工具注册、不涉及授权策略。**

### 2.4 核心的 SandboxPathRegistry（中间人）

核心内部新增 `@Component`，管理路径映射表。**不在 plugin-api，只在 worker 核心内部。**

```java
@Component
public class SandboxPathRegistry {
    private final SandboxBackend sandbox;  // 当前激活的后端（可能为 OsSandbox DIRECT）
    
    // 宿主路径 → 沙箱内路径
    private final Map<Path, String> hostToSandbox = new ConcurrentHashMap<>();
    // 沙箱内路径 → 宿主路径
    private final Map<String, Path> sandboxToHost = new ConcurrentHashMap<>();
    
    /** 路径提供方注册需要被沙箱访问的宿主路径。 */
    public void register(Path hostPath, Access access) {
        String sandboxPath = sandbox.mount(hostPath, access);
        hostToSandbox.put(hostPath, sandboxPath);
        sandboxToHost.put(sandboxPath, hostPath);
    }
    
    /** 宿主路径 → AI 可见路径;无映射则原样返回。 */
    public String toSandboxPath(Path hostPath) {
        return hostToSandbox.getOrDefault(hostPath, hostPath.toString());
    }
    
    /** AI 视角路径 → 宿主路径;无映射则返回 null（核心返回错误，AI 换工具）。 */
    public String toHostPath(String sandboxPath) {
        // 精确匹配或前缀匹配
        for (Map.Entry<String, Path> e : sandboxToHost.entrySet()) {
            String mount = e.getKey();
            if (sandboxPath.equals(mount) || sandboxPath.startsWith(mount + "/")) {
                return e.getValue().toString() + sandboxPath.substring(mount.length());
            }
        }
        return null;
    }
    
    /** 工作区删除时通知沙箱清理。 */
    public void onWorkspaceRemoved(Path root) {
        sandbox.onWorkspaceRemoved(root);
        hostToSandbox.remove(root);
        sandboxToHost.values().remove(root);
    }
}
```

### 2.5 路径翻译流程

```
1. 路径提供方注册 → SandboxPathRegistry.register(hostPath, access)
   → 核心调 sandbox.mount(hostPath, access)
   → 沙箱返回沙箱内路径（如 /c/Users/.../workspace）
   → 核心保存映射表

2. 文件消费方调 SandboxPathRegistry 翻译:
   - FsToolSupport: toHostPath("/c/Users/.../file.txt") → "C:\Users\...\file.txt" → Java NIO
   - SkillAdvisor: toSandboxPath("C:\Users\...\skills\x") → "/c/Users/.../skills/x" → 注入 prompt
   - ExternalFileTokenResolver: toSandboxPath(real) → 沙箱内路径 → 附在引用文本后

3. 翻译不了（注册表外路径）:
   - toHostPath 返回 null → 文件工具返回错误 "无法在宿主侧访问该路径"
   - AI 改用沙箱自己的命令工具（如 cat）读文件
```

### 2.6 谁注册路径

| 注册方 | 注册什么 | 访问语义 |
|--------|---------|---------|
| `WorkspaceManager` | 工作区根 | 读写 |
| `PermissionGate` | 用户授权的外部根 | 读写 |
| `SkillsReadonlyRoots` | skills 目录 | 读写 |
| `ExternalFileTokenResolver` | @ 引用根 | 读写 |

注册时机：工作区创建时、用户授权时、skills 目录初始化时。核心在适当时机调 `SandboxPathRegistry.register()`。

### 2.7 InteractionService 通用化

```java
// 现在（绑死 taskId/agentId）：
AskResult ask(String taskId, String agentId, List<AskQuestion> questions, long timeoutMs);

// 改后（纯透传）：
AskResult ask(List<AskQuestion> questions, long timeoutMs, Map<String,String> context);
```

`context` 是上层注入的标记字段（如 taskId、agentId、或者未来工作流的 flowId/nodeId 等），InteractionService 原样透传给前端，不解析、不依赖。

### 2.8 核心的宿主访问工具（默认沙箱 / DIRECT）

核心自带一个"访问宿主"的命令工具，不管有没有沙箱插件都存在。默认关闭，用户通过"允许AI访问电脑"开关打开。

```
核心（DIRECT = 默认沙箱）:
├── CommandExecutor          ← 扫描路径 → 走授权 → ProcessBuilder 宿主执行 → 格式化
├── 工具按系统决定:
│   ├── Windows → PowerShellTool
│   └── Linux   → BashTool
├── 开关: "允许AI访问电脑"（默认关闭）
└── 与插件沙箱共存
```

这个工具走自己的授权链：扫描命令中的危险动词和越界路径，需要授权时调 `InteractionService.ask()` 弹窗。**不再调 `sandbox.spawnSandboxed()`**，直接 ProcessBuilder 执行。

**PowerShellEnableSlashProvider** 留在核心，注册条件从 `sandbox.isWslBackend()` 改为始终注册（核心的宿主访问工具开关，与沙箱无关）。

### 2.9 沙箱插件提供自己的命令工具

沙箱插件不只提供 `SandboxBackend`（挂载+清理），还提供 `ToolProvider`（命令工具）。沙箱完全自由：

```java
// sandbox-wsl-ubuntu 插件
public class WslUbuntuSandboxPlugin implements EveryAgentPlugin {
    public void activate(WorkerPluginContext ctx) {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        WorkspaceManager workspaces = ctx.getService(WorkspaceManager.class);

        // 1. 注册沙箱后端
        ctx.registerSandboxProvider(new WslUbuntuSandboxProvider(props, workspaces));

        // 2. 注册自己的命令工具（ToolProvider）
        ctx.registerToolProvider(new WslUbuntuBashToolProvider(props, workspaces));
    }
}

// 沙箱自己的 BashToolProvider
public class WslUbuntuBashToolProvider implements ToolProvider {
    public boolean appliesTo(ToolContext ctx) {
        // 只在当前沙箱是 wsl-ubuntu 时生效
        return "wsl-ubuntu".equals(ctx.sandbox().id());
    }

    public List<ToolCallback> createTools(ToolContext ctx) {
        // 沙箱自己的 CommandExecutor（Linux 路径形态，自己扫描）
        var exec = new WslUbuntuCommandExecutor(ctx.workspaceRoot());
        return List.of(ToolCallbacks.from(new BashTool(exec)));
    }
}
```

沙箱插件自己的 `CommandExecutor`：
- 路径形态是 Linux（`/c/Users/...`），沙箱自己做路径扫描
- 自己决定是否需要授权（wsl-ubuntu 发行版隔离，可能不需要）
- 需要授权时自己调 `InteractionService.ask()` 或走 `AuthorizationHandler` 链
- 直接构造进程执行（`wsl -d xxx -e bash -c "..."`），不调 `sandbox.spawnSandboxed()`
- 自己格式化结果

### 2.10 核心组件改造

#### FsToolSupport.java（路径翻译）
```java
// 删除 import WslPathMapper、WslBwrapSandbox
// 注入 SandboxPathRegistry
// resolveWslPath() 改为：
private String resolveSandboxPath(String rel) {
    String host = pathRegistry.toHostPath(rel);
    return host != null ? host : null;  // null = 路径在注册表外，调用方返回错误
}
// 调用方处理 null：
//   read_file → 返回错误 "无法在宿主侧访问该路径，请使用 bash 工具（如 cat）"
```

#### SkillAdvisor.java（知识包路径翻译）
```java
// 删除 import WslPathMapper、OsSandbox.isWslBackend()/isWslDirect()
// 改为：
String sandboxPath = pathRegistry.toSandboxPath(Path.of(skill.knowledgePath()));
// toSandboxPath 无映射时原样返回（DIRECT 场景）
```

#### ExternalFileTokenResolver.java（外部文件引用文本）
```java
// 删除 import WslPathMapper、OsSandbox.isWslDirect()/isWslBwrap()
// 改为：
String sandboxPath = pathRegistry.toSandboxPath(real);
if (!sandboxPath.equals(real.toString())) {
    sb.append("（沙箱内: ").append(sandboxPath).append("）");
}
```

#### WorkspaceManager.java（工作区删除时清理）
```java
// 删除 import WslUmounter
// 删除 WslUmounter umounter 字段和注入
// 改为：
pathRegistry.onWorkspaceRemoved(root);  // 核心通知沙箱清理 + 清理映射表
```

#### CommandExecutor.java（核心宿主访问工具用）
```java
// 删除所有 WSL 分支逻辑（isWslBackend()/isWslDirect()/isWslBwrap()/useSeccompInterception()）
// 删除 WslPathMapper.translateCommand()
// 删除 sandbox.spawnSandboxed() / spawnSandboxedWindows() 调用
// 简化为：仅服务于核心的宿主访问工具（DIRECT）
//   1. 扫描命令（宿主路径形态）
//   2. 走授权（调 InteractionService.ask()）
//   3. 宿主执行（ProcessBuilder 直接执行）
//   4. 格式化结果
```

#### PowerShellEnableSlashProvider.java
```java
// 删除 sandbox.isWslBackend() 条件
// 改为始终注册（核心的宿主访问工具开关，与沙箱无关）
```

#### BashToolProvider / PowerShellToolProvider
```java
// 留在核心，但只服务于"宿主访问工具"（DIRECT）
// appliesTo() 删除 registerBashTool() 判定
// 改为按系统决定：Windows → PowerShellTool，Linux → BashTool
// "允许AI访问电脑"开关控制是否注册
```

#### WorkerProperties.java
```java
// 删除 Wsl 嵌套类（distro/tarball/roIslands/pwshEnabled/loginShell）
// 删除 interceptPrivilege 字段（仅 wsl-bwrap 用）
// type 取值改为：auto | wsl-ubuntu | windows-mic | none
// WSL 专属配置由插件通过 plugin.json contributes.config 自管
```

#### OsSandbox 瘦身（纯门面 + DIRECT 默认沙箱）
```java
// 删除 Backend 枚举、所有 WSL/windows 分发逻辑、isWsl*() 方法
// 删除 spawnSandboxed()、spawnSandboxedWindows()、spawnSandboxedSeccomp()、wslDirectMountRoots()
// 删除 spawnNative()（移出 SPI，逻辑保留为核心通用服务供 git 插件调）
// 删除 exec/drainExec 线程池（不再执行沙箱命令）
// 保留：
// - SandboxProviderRegistry.select() → delegate
// - 实现 SandboxBackend：mount() 返回原路径（DIRECT 不挂载），onWorkspaceRemoved() no-op
// - delegate 不为 null 时转发到 delegate
```

#### PermissionGate 从 plugin-api 移除
- `PermissionGate` 接口从 `every-agent-plugin-api` 中移除
- `ToolContext.gate()` 移除
- `WorkerServices.gate()` 移除
- 沙箱插件不直接调 PermissionGate，需要授权时用 `InteractionService` + `AuthorizationHandler` 链
- worker 核心内部保留 `PermissionGate` 实现类（核心自己的宿主访问工具用），只是不暴露到 plugin-api

### 2.11 删除清单

**从 worker 中删除的文件：**

| 文件 | 原因 |
|------|------|
| `os/wsl/WslBwrapSandbox.java` | wsl-bwrap 后端删除 |
| `os/wsl/WslPathMapper.java` | 移到 sandbox-wsl-ubuntu 插件 |
| `os/wsl/WslUmounter.java` | 移到 sandbox-wsl-ubuntu 插件 |
| `os/wsl/WslDirectSandbox.java` | 移到 sandbox-wsl-ubuntu 插件 |
| `os/windows/WindowsSandbox.java` | 移到 sandbox-windows-mic 插件 |
| `os/windows/Win32Ex.java` | 移到 sandbox-windows-mic 插件 |
| `os/windows/WindowsAcl.java` | 移到 sandbox-windows-mic 插件 |
| `os/windows/WindowsIntegrity.java` | 移到 sandbox-windows-mic 插件 |
| `plugin/adapters/BuiltInSandboxProviders.java` | 三个后端都移到插件 |
| `plugin/adapters/WindowsMicSandboxProvider.java` | 移到插件 |
| `plugin/adapters/WindowsMicSandboxBackend.java` | 移到插件 |
| `plugin/adapters/WslBwrapSandboxProvider.java` | 删除 |
| `plugin/adapters/WslBwrapSandboxBackend.java` | 删除 |
| `plugin/adapters/WslDirectSandboxProvider.java` | 移到插件（改名） |
| `plugin/adapters/WslDirectSandboxBackend.java` | 移到插件（改名） |
| `plugin/adapters/DirectSpawnSupport.java` | 逻辑保留在 worker 核心作为通用宿主进程执行服务 |
| `plugin-api/.../spi/PermissionGate.java` | 从 plugin-api 移除 |
| 测试文件（多个） | 随对应源文件删除/迁移 |

**整个 `os/wsl/` 目录清空删除。整个 `os/windows/` 目录清空删除。**

**留在 worker 核心的文件：**

| 文件 | 原因 |
|------|------|
| `os/OsSandbox.java` | 瘦身为纯门面 + DIRECT 默认沙箱（实现 mount 返回原路径） |
| `SandboxPathRegistry.java`（新增） | 核心路径映射表中间人 |
| `tools/CommandExecutor.java` | 核心的宿主访问工具用（DIRECT），直接 ProcessBuilder |
| `tools/BashTool.java` | 核心的宿主访问工具（Linux） |
| `tools/PowerShellTool.java` | 核心的宿主访问工具（Windows） |
| `plugin/adapters/BashToolProvider.java` | 核心的宿主访问工具注册 |
| `plugin/adapters/PowerShellToolProvider.java` | 核心的宿主访问工具注册 |
| `plugin/BuiltInToolProviders.java` | 保留，但删除沙箱相关注册行 |
| `powershell/PowerShellEnable*.java` | 核心的宿主访问工具开关 |
| `tools/PermissionGate.java` | 保留在 worker 内部，不暴露到 plugin-api |
| `tools/permission/*` | 授权责任链，核心内部使用 |
| `modules/Sandbox.java` | 路径 jail（Java NIO 越界检查），保留 |

### 2.12 新建 2 个插件模块

```
every-agent-plugins/
├── sandbox-windows-mic/          ← 新建
│   ├── plugin.json
│   ├── pom.xml
│   └── src/main/java/dev/everyagent/plugin/sandbox/mic/
│       ├── WindowsMicSandboxPlugin.java          (入口)
│       ├── WindowsMicSandboxProvider.java         (id="windows-mic", priority=5)
│       ├── WindowsMicSandboxBackend.java          (mount 返回原路径, onWorkspaceRemoved no-op)
│       ├── WindowsSandbox.java                   (JNA Win32)
│       ├── Win32Ex.java                          (JNA 扩展原语)
│       ├── WindowsAcl.java                       (DACL 授权)
│       └── WindowsIntegrity.java                 (Low IL 标注)
│
├── sandbox-wsl-ubuntu/            ← 新建
│   ├── plugin.json
│   ├── pom.xml
│   └── src/main/java/dev/everyagent/plugin/sandbox/wslubuntu/
│       ├── WslUbuntuSandboxPlugin.java           (入口)
│       ├── WslUbuntuSandboxProvider.java          (id="wsl-ubuntu", priority=10)
│       ├── WslUbuntuSandboxBackend.java          (mount=drvfs挂载, onWorkspaceRemoved=umount)
│       ├── WslUbuntuSandbox.java                 (原 WslDirectSandbox)
│       ├── WslPathMapper.java                    (原 worker/os/wsl/)
│       ├── WslUmounter.java                      (原 worker/os/wsl/)
│       ├── WslCommon.java                        (从 WslBwrapSandbox 提取的共享类型)
│       ├── WslUbuntuBashToolProvider.java        (沙箱自己的命令工具 ToolProvider)
│       └── WslUbuntuCommandExecutor.java         (沙箱自己的命令执行器)
```

#### sandbox-wsl-ubuntu 的 Backend 实现

```java
public final class WslUbuntuSandboxBackend implements SandboxBackend {
    @Override
    public String id() { return "wsl-ubuntu"; }

    @Override
    public String mount(Path hostPath, Access access) {
        // wsl-ubuntu: drvfs 挂载 C:\a\b → /c/a/b
        String mountPoint = WslPathMapper.toDirectMount(hostPath);
        if (mountPoint == null) return hostPath.toString(); // UNC/相对路径不挂载
        // 执行 wsl -d xxx -u root mount -t drvfs "C:\a\b" /c/a/b
        ensureMount(hostPath, mountPoint);
        return mountPoint;
    }

    @Override
    public void onWorkspaceRemoved(Path root) {
        WslUmounter.umountQuietly(root);
    }
    // 不实现 spawnSandboxed — 沙箱插件自己的 CommandExecutor 直接 wsl -d xxx 执行
}
```

### 2.13 后端 id 与配置

| 后端 | id | priority | mount 行为 | 说明 |
|------|-----|----------|-----------|------|
| WSL Ubuntu | `wsl-ubuntu` | 10 | drvfs 挂载 → /c/... | 原 wsl-direct 改名 |
| Windows MIC | `windows-mic` | 5 | 返回原路径 | 命令跑在宿主上 |
| DIRECT（默认沙箱） | — | — | 返回原路径 | OsSandbox 自身，无 Provider |

`WorkerProperties.Sandbox.type`：`auto` | `wsl-ubuntu` | `windows-mic` | `none`
- 旧值 `wsl-direct` → 归一为 `wsl-ubuntu`
- 旧值 `wsl-bwrap`/`bwrap`/`wsl` → 归一为 `auto` 并 WARN

### 2.14 plugin.json

```json
{
  "id": "sandbox-windows-mic",
  "name": "Windows MIC 沙箱",
  "version": "0.1.0",
  "description": "Windows 原生进程沙箱（Restricted Token + Medium IL + Job Object）",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.sandbox.mic.WindowsMicSandboxPlugin"
}
```

```json
{
  "id": "sandbox-wsl-ubuntu",
  "name": "WSL Ubuntu 沙箱",
  "version": "0.1.0",
  "description": "WSL2 Ubuntu 发行版 root 直连沙箱（工作区手动挂载）",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.sandbox.wslubuntu.WslUbuntuSandboxPlugin"
}
```

## 三、未来 Docker 沙箱实现指南

### 3.1 新建插件模块

```
every-agent-plugins/
└── sandbox-docker/
    ├── plugin.json
    ├── pom.xml
    └── src/main/java/dev/everyagent/plugin/sandbox/docker/
        ├── DockerSandboxPlugin.java              (入口)
        ├── DockerSandboxProvider.java            (id="docker", priority=30)
        ├── DockerSandboxBackend.java             (mount=bind mount, onWorkspaceRemoved=docker rm)
        ├── DockerClient.java                     (Docker API 封装)
        ├── DockerBashToolProvider.java           (沙箱自己的命令工具)
        └── DockerCommandExecutor.java            (沙箱自己的命令执行器)
```

### 3.2 Backend 实现

```java
public final class DockerSandboxBackend implements SandboxBackend {
    @Override
    public String id() { return "docker"; }

    @Override
    public String mount(Path hostPath, Access access) {
        // docker: -v C:\workspace:/workspace
        String mountPoint = "/workspace"; // 或按路径计算
        // docker run -v hostPath:mountPoint ...
        return mountPoint;
    }

    @Override
    public void onWorkspaceRemoved(Path root) {
        // 清理容器（docker rm）等
    }
}
```

Docker 场景下，AI 传容器内独有路径（如 `/tmp/output.txt`）给 `read_file`：
- 核心 `SandboxPathRegistry.toHostPath("/tmp/output.txt")` → 查不到映射 → 返回 null
- `read_file` 返回错误 "无法在宿主侧访问该路径"
- AI 改用 bash 工具 `cat /tmp/output.txt`（沙箱插件提供的命令工具）

## 四、迁移步骤

### Step 1: SandboxBackend SPI 改造（plugin-api）
- 删除 `spawnSandboxed()`、`spawnSandboxedWindows()`、`spawnNative()`、`isWslBackend()`、`isWslBwrap()`、`isWslDirect()`、`registerBashTool()`
- 删除 `toSandboxPath()`、`toHostPath()`（如果之前已加则删除）
- 新增 `mount(Path, Access)` 和 `onWorkspaceRemoved()` 两个 default 方法
- `ExecResult` 保留在 plugin-api（沙箱插件 CommandExecutor 可能需要）
- `PermissionGate` 从 plugin-api 移除
- `ToolContext.gate()` 移除
- `WorkerServices.gate()` 移除
- `InteractionService.ask()` 通用化：去掉 taskId/agentId，改为 questions + timeout + context 透传

### Step 2: Worker 核心新增 SandboxPathRegistry
- 新建 `SandboxPathRegistry`（@Component），管理 {宿主路径 → 沙箱内路径} 映射表
- WorkspaceManager 注册工作区根
- PermissionGate 注册外部授权根
- SkillsReadonlyRoots 注册 skills 目录
- ExternalFileTokenResolver 注册 @ 引用根

### Step 3: Worker 核心去 WSL 化
- **FsToolSupport**：`resolveWslPath()` 改为调 `pathRegistry.toHostPath(rel)`，null 时返回错误
- **SkillAdvisor**：知识包路径翻译改用 `pathRegistry.toSandboxPath()`
- **ExternalFileTokenResolver**：沙箱内路径改用 `pathRegistry.toSandboxPath()`
- **WorkspaceManager**：删除 `WslUmounter` 注入，改调 `pathRegistry.onWorkspaceRemoved(root)`
- **CommandExecutor**：删除所有 WSL 分支，不再调 `sandbox.spawnSandboxed()`，直接 ProcessBuilder
- **BashToolProvider / PowerShellToolProvider**：删除 `registerBashTool()` 判定，改为按系统 + "允许AI访问电脑"开关
- **PowerShellEnableSlashProvider**：删除 `isWslBackend()` 条件，始终注册
- **WorkerProperties**：删除 `Wsl` 嵌套类、`interceptPrivilege`；`type` 取值更新
- **OsSandbox**：删除 Backend 枚举、WSL/windows 分发逻辑、`isWsl*()` 方法、`spawnSandboxed()`、`spawnNative()`、线程池；实现 `mount()` 返回原路径 + `onWorkspaceRemoved()` no-op

### Step 4: 删除 worker 中的沙箱实现文件
- 删除 `os/wsl/` 整个目录
- 删除 `os/windows/` 整个目录
- 删除 `plugin/adapters/BuiltInSandboxProviders`、所有沙箱 Provider/Backend 适配器
- 删除对应测试文件

### Step 5: 创建 sandbox-windows-mic 插件
- 移入 `WindowsSandbox` + `Win32Ex` + `WindowsAcl` + `WindowsIntegrity` + Provider + Backend + 入口类
- Backend 的 `mount()` 返回原路径（windows-mic 命令跑在宿主上）
- 可选：提供自己的 ToolProvider，或依赖核心的宿主访问工具

### Step 6: 创建 sandbox-wsl-ubuntu 插件
- 移入 `WslDirectSandbox`（改名）、`WslPathMapper`、`WslUmounter` + Provider + Backend + 入口类
- 从 `WslBwrapSandbox` 提取共享类型到 `WslCommon.java`
- Backend 实现 `mount()`（drvfs 挂载，返回 /c/... 形态）+ `onWorkspaceRemoved()`（umount）
- 提供自己的 `WslUbuntuBashToolProvider`（ToolProvider）+ `WslUbuntuCommandExecutor`
- 后端 id = `wsl-ubuntu`

### Step 7: 更新 ARCHITECTURE.md
- §7.10 沙箱后端描述更新
- 删除所有 wsl-bwrap 相关描述
- 新增 SandboxBackend SPI 极简接口文档（mount + onWorkspaceRemoved）
- 新增 SandboxPathRegistry 路径翻译中间人描述
- 新增"核心宿主访问工具"与"沙箱命令工具"共存架构描述

### Step 8: 验证
- `mvn clean install` 全量构建
- 验证 auto 模式：wsl-ubuntu(10) > windows-mic(5) > DIRECT
- 验证核心宿主访问工具（"允许AI访问电脑"开关）在 DIRECT 和各沙箱下都能工作
- 验证沙箱插件自己的命令工具正确注册
- 验证路径翻译：核心调 mount() 后映射表正确，FsToolSupport 翻译路径正确
- 验证注册表外路径返回错误，AI 换用沙箱命令工具
- 验证工作区删除时 onWorkspaceRemoved 被调用
- 验证 InteractionService 不绑 taskId/agentId

## 五、风险与注意事项

| 风险 | 缓解 |
|------|------|
| `SandboxBackend` SPI 大幅删减 | 删除的方法均有替代：命令执行→沙箱自己 CommandExecutor；路径翻译→核心 SandboxPathRegistry 查表；工具注册→沙箱自己注册 ToolProvider |
| `PermissionGate` 从 plugin-api 移除 | 沙箱用 `InteractionService` + `AuthorizationHandler` 替代；核心内部保留 PermissionGate |
| `InteractionService.ask()` 签名变更 | 去掉 taskId/agentId，改为 context 透传；调用方需适配 |
| 核心做挂载的时机 | 工作区注册时 / 用户授权时 / skills 初始化时调 `SandboxPathRegistry.register()`；沙箱的 `mount()` 须幂等（多次注册同一路径不重复挂载） |
| wsl-ubuntu 挂载是动态的（当前每次命令前 ensureMount） | 改为核心注册时调 `mount()`，沙箱内部幂等挂载；`wsl --shutdown` 后挂载丢失，下次 `mount()` 自愈重挂 |
| OsSandbox 被多个核心组件直接引用 | 保留为 `@Component` + `SandboxBackend`，方法签名兼容（mount 返回原路径） |
| 核心宿主访问工具与沙箱命令工具共存 | 通过 `ToolProvider.appliesTo()` 各自控制生效条件，不冲突 |
| wsl-ubuntu 插件依赖 worker | 与 git 插件同模式，内置插件允许依赖 worker |
| 插件加载顺序 | `BuiltInPluginScanner` 保证 builtin 优先；OsSandbox `@PostConstruct` 时 delegate 已就绪 |
| 旧配置 `type=wsl-bwrap` | 归一为 `auto` 并 WARN |
| 旧配置 `type=wsl-direct` | 归一为 `wsl-ubuntu`，静默兼容 |
| sandbox-windows-mic 的 JNA 依赖 | 移到插件 pom.xml，JNA 跨平台无编译问题 |
| docker 场景注册表外路径 | read_file 等返回错误，AI 改用沙箱命令工具（如 cat）；这是设计预期行为 |
