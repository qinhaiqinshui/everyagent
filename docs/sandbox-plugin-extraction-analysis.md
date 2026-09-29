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

**Worker 核心不能保留任何 WSL 概念，核心不能依赖插件。**

当前问题：WSL 路径翻译（`WslPathMapper`）、WSL 发行版清理（`WslUmounter`）、WSL 后端判定（`isWslBackend()`/`isWslDirect()`/`isWslBwrap()`）深度渗透到 worker 核心的 7+ 个组件中。

解决方案：**将这些能力下沉到 `SandboxBackend` SPI 接口**，让后端自己提供实现，核心只调接口。后端 id `wsl-direct` 改名 `wsl-ubuntu`，`wsl-bwrap` 后端及其全部代码删除。

### 2.2 SandboxBackend SPI 接口改造

```java
public interface SandboxBackend {
    // ===== 现有方法（保留）=====
    ExecResult spawnSandboxed(String command, Path cwd, Map<String,String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege);
    default ExecResult spawnSandboxedWindows(...) { /* 默认退化为 spawnSandboxed */ }
    ExecResult spawnNative(String[] argv, Path cwd, Map<String,String> env, long timeoutMs);
    String id();
    boolean registerBashTool();

    // ===== 新增：路径翻译（核心消除 WslPathMapper 依赖）=====
    
    /** 宿主路径 → 沙箱内 AI 可见路径;null = 不翻译（非翻译型后端如 windows-mic） */
    default String toSandboxPath(Path hostPath) { return null; }
    
    /**
     * 沙箱内 AI 视角路径 → 宿主 IO 路径;null = 不翻译。
     * knownRoots = 工作区根 + 外部授权根 + skill 根等全部已挂载路径。
     * 后端按自己的挂载约定匹配前缀并翻译。
     */
    default String toHostPath(String sandboxPath, List<Path> knownRoots) { return null; }
    
    /**
     * 沙箱后端是否需要核心做命令授权检查（PermissionGate.requireCommand）。
     * 
     * 设计理由：是否弹窗授权由沙箱决定。
     * - 隔离型后端（wsl-ubuntu：发行版整体隔离，root 完整权限，automount 关闭，
     *   手动挂载工作区）不需要命令级授权检查——隔离本身保证了安全。
     * - 宿主型后端（windows-mic、DIRECT）需要命令级授权检查——命令跑在宿主上，
     *   危险命令和越界路径必须经用户授权。
     * 
     * 默认 true（保守安全）：windows-mic、DIRECT 不 override。
     * wsl-ubuntu override 为 false。
     */
    default boolean requiresCommandGate() { return true; }

    // ===== 新增：工作区生命周期钩子（核心消除 WslUmounter 依赖）=====
    
    /** 工作区被删除时调用;后端 best-effort 清理挂载等;默认 no-op */
    default void onWorkspaceRemoved(Path root) {}

    // ===== 删除的 WSL 专属方法 =====
    // boolean isWslBackend();       → 删除
    // default boolean isWslBwrap(); → 删除
    // default boolean isWslDirect(); → 删除
}
```

**不在 `SandboxBackend` 中添加任何与工具注册/powershell 相关的方法。** "让 AI 访问宿主"是一个独立的 `ToolProvider`，与沙箱无关——见 §2.3。

### 2.3 "让 AI 访问宿主"工具——独立 ToolProvider，与沙箱无关

当前 `PowerShellEnableSlashProvider`（注册 `/允许AI访问电脑` 斜杠命令）的注册条件是 `sandbox.isWslBackend()`，`PowerShellToolProvider.appliesTo()` 检查 `powershellEnabled` 任务级开关。这两个都是 `ToolProvider` / 斜杠命令机制，**不是沙箱的职责**。

**改造方案**：

1. **`PowerShellEnableSlashProvider`**：注册条件从 `sandbox.isWslBackend()` 改为判断 `sandbox.registerBashTool()`——`registerBashTool()=true` 意味着 AI 的命令工具是 bash（跑在非宿主环境中），此时追加宿主原生命令工具有意义；`registerBashTool()=false`（windows-mic，命令工具本就是宿主原生的）则不需要。

2. **`PowerShellToolProvider`**：`appliesTo()` 逻辑不变——已是通用的 `ToolProvider.appliesTo()` 机制，基于 `isWindows()` + `registerBashTool()` + `powershellEnabled` 判定，不引用任何 WSL 类。

3. 这些 powershell 相关类（`PowerShellEnableSlashProvider`、`PowerShellEnableToken`、`PowerShellEnableSlashResolver`、`PowerShellToolProvider`、`PowerShellTool`）**全部留在 worker 核心**——它们是命令工具实现，不是 WSL 概念。只是把 `isWslBackend()` 引用换成 `registerBashTool()`。

**核心原则**：`SandboxBackend` SPI 只负责沙箱执行和路径翻译。"让 AI 访问宿主"是一个独立的 `ToolProvider`，通过已有的 `ToolProviderRegistry` + `AgentBuilder.appliesTo()` 机制运作，不需要沙箱 SPI 介入。

### 2.4 核心组件改造

#### FsToolSupport.java（路径翻译）
```java
// 删除 import WslPathMapper、WslBwrapSandbox
// 删除 resolveWslPath() 中的 WSL 专属逻辑

// 改为：
private String resolveSandboxPath(TaskEntry t, String rel) {
    if (sandbox == null || rel == null || rel.isBlank()) return rel;
    List<Path> knownRoots = collectKnownRoots(t); // 工作区根 + 外部根 + skill 根
    String host = sandbox.toHostPath(rel, knownRoots);
    return host != null ? host : rel;  // null = 非翻译后端，原样返回
}
```

#### CommandExecutor.java（命令授权检查）
```java
// 删除 import WslPathMapper
// 删除 isWslBackend()/isWslDirect()/isWslBwrap()/useSeccompInterception() 分支

// 是否做命令授权检查，由沙箱后端决定：
if (sandbox.requiresCommandGate()) {
    // 后端跑在宿主上（windows-mic、DIRECT），命令路径本就是宿主路径，直接喂给 PermissionGate
    gate.requireCommand(task, agentId, command);
}
// 真实执行的命令保持原文，不翻译

// extraRoots：由 sandbox 的 spawnSandboxed 参数携带，不再在 CommandExecutor 里按后端类型分发
// powershell 始终走 spawnSandboxedWindows（已有逻辑）
```

#### SkillAdvisor.java（知识包路径翻译）
```java
// 删除 import WslPathMapper、OsSandbox.isWslBackend()/isWslDirect()
// 改为：
String sandboxPath = sandbox.toSandboxPath(Path.of(skill.knowledgePath()));
String path = sandboxPath != null ? sandboxPath : skill.knowledgePath();
```

#### ExternalFileTokenResolver.java（外部文件引用文本）
```java
// 删除 import WslPathMapper、OsSandbox.isWslDirect()/isWslBwrap()
// 改为：
String sandboxPath = sandbox.toSandboxPath(real);
if (sandboxPath != null) {
    sb.append("（沙箱内: ").append(sandboxPath).append("）");
}
```

#### WorkspaceManager.java（工作区删除时清理）
```java
// 删除 import WslUmounter
// 删除 WslUmounter umounter 字段和注入
// 改为：
sandbox.onWorkspaceRemoved(root);  // 后端自己处理 umount，默认 no-op
```

#### PowerShellEnableSlashProvider.java（powershell opt-in 注册）
```java
// 删除 import OsSandbox.isWslBackend()
// 改为：
if (sandbox.registerBashTool()) {
    // registerBashTool()=true 意味着 AI 的命令工具是 bash（跑在非宿主环境中），
    // 追加宿主原生命令工具（powershell）有意义。
    // registerBashTool()=false（windows-mic）命令工具本就是宿主原生的，不需要追加。
    registry.registerProvider("powershell-enable", this::items);
}
```

#### BashToolProvider / PowerShellToolProvider
```java
// 不需要改动，已用 registerBashTool() 抽象判定
```

#### WorkerProperties.java
```java
// 删除 Wsl 嵌套类（distro/tarball/roIslands/pwshEnabled/loginShell）
// 删除 interceptPrivilege 字段（仅 wsl-bwrap 用）
// type 取值改为：auto | wsl-ubuntu | windows-mic | none
// WSL 专属配置由插件通过 plugin.json contributes.config 自管
```

### 2.4 删除清单

**从 worker 中删除的文件：**

| 文件 | 原因 |
|------|------|
| `os/wsl/WslBwrapSandbox.java` | wsl-bwrap 后端删除 |
| `os/wsl/WslPathMapper.java` | 路径翻译下沉到 sandbox-wsl-ubuntu 插件的 Backend 实现 |
| `os/wsl/WslUmounter.java` | 清理逻辑下沉到 sandbox-wsl-ubuntu 插件的 `onWorkspaceRemoved()` |
| `os/wsl/WslDirectSandbox.java` | 移到 sandbox-wsl-ubuntu 插件 |
| `os/windows/WindowsSandbox.java` | 移到 sandbox-windows-mic 插件 |
| `os/windows/Win32Ex.java` | 移到 sandbox-windows-mic 插件 |
| `os/windows/WindowsAcl.java` | 移到 sandbox-windows-mic 插件 |
| `os/windows/WindowsIntegrity.java` | 移到 sandbox-windows-mic 插件 |
| `plugin/adapters/BuiltInSandboxProviders.java` | 三个后端都移到插件 |
| `plugin/adapters/WindowsMicSandboxProvider.java` | 移到插件 |
| `plugin/adapters/WindowsMicSandboxBackend.java` | 移到插件 |
| `plugin/adapters/WslBwrapSandboxProvider.java` | 删除（wsl-bwrap 后端删除） |
| `plugin/adapters/WslBwrapSandboxBackend.java` | 删除 |
| `plugin/adapters/WslDirectSandboxProvider.java` | 移到插件（改名 WslUbuntuSandboxProvider） |
| `plugin/adapters/WslDirectSandboxBackend.java` | 移到插件（改名 WslUbuntuSandboxBackend） |
| `plugin/adapters/DirectSpawnSupport.java` | 移到 plugin-api 的 spi 包，改 public |
| 测试文件（多个） | 随对应源文件删除/迁移 |

**整个 `os/wsl/` 目录清空删除。整个 `os/windows/` 目录清空删除。**

### 2.5 OsSandbox 瘦身（纯门面 + DIRECT 默认沙箱）

```java
@Component
public final class OsSandbox implements SandboxBackend {
    // 删除 Backend 枚举（WSL_BWRAP/WSL_DIRECT/WINDOWS_MIC/DIRECT）
    // 删除所有 WSL/windows 分发逻辑
    // 删除 isWslBackend()/isWslBwrap()/isWslDirect()/isWindowsSandboxActive()
    // 删除 spawnSandboxedSeccomp()
    // 删除 wslDirectMountRoots()
    
    // 保留：
    // - SandboxProviderRegistry.select() → delegate
    // - DIRECT 回退：runDirect() / runDirectCommand()
    // - spawnNative()
    // - 新增 SPI 方法默认实现（toSandboxPath/toHostPath/requiresCommandGate/onWorkspaceRemoved）
    //   → OsSandbox 作为 DIRECT 后端，路径翻译方法返回 null
    //   → delegate 不为 null 时，转发到 delegate 的对应方法
}
```

OsSandbox 成为**纯门面**：有 delegate 就转发，没有就 DIRECT 回退。不再知道任何后端的具体实现。

### 2.6 新建 2 个插件模块

```
every-agent-plugins/
├── sandbox-windows-mic/          ← 新建
│   ├── plugin.json
│   ├── pom.xml
│   └── src/main/java/dev/everyagent/plugin/sandbox/mic/
│       ├── WindowsMicSandboxPlugin.java
│       ├── WindowsMicSandboxProvider.java
│       ├── WindowsMicSandboxBackend.java       (实现 toSandboxPath 等返回 null)
│       ├── WindowsSandbox.java
│       ├── Win32Ex.java
│       ├── WindowsAcl.java
│       └── WindowsIntegrity.java
│
├── sandbox-wsl-ubuntu/            ← 新建
│   ├── plugin.json
│   ├── pom.xml
│   └── src/main/java/dev/everyagent/plugin/sandbox/wslubuntu/
│       ├── WslUbuntuSandboxPlugin.java
│       ├── WslUbuntuSandboxProvider.java       (id="wsl-ubuntu", priority=10)
│       ├── WslUbuntuSandboxBackend.java         (实现 toSandboxPath/toHostPath/requiresCommandGate/onWorkspaceRemoved)
│       ├── WslUbuntuSandbox.java                 (原 WslDirectSandbox)
│       ├── WslPathMapper.java                   (原 worker/os/wsl/WslPathMapper，搬入插件)
│       ├── WslUmounter.java                     (原 worker/os/wsl/WslUmounter，搬入插件)
│       └── WslCommon.java                        (从 WslBwrapSandbox 提取的共享类型/方法)
```

#### sandbox-wsl-ubuntu 的 Backend 实现路径翻译

```java
public final class WslUbuntuSandboxBackend implements SandboxBackend {
    @Override
    public String toSandboxPath(Path hostPath) {
        return WslPathMapper.toDirectMount(hostPath);  // C:\a\b → /c/a/b
    }
    
    @Override
    public String toHostPath(String sandboxPath, List<Path> knownRoots) {
        // 匹配挂载前缀 → 翻译回 Windows 路径
        for (Path root : knownRoots) {
            String mount = WslPathMapper.toDirectMount(root);
            if (sandboxPath.equals(mount) || sandboxPath.startsWith(mount + "/")) {
                return root.toString() + sandboxPath.substring(mount.length());
            }
        }
        // 其余路径（发行版内部 /tmp/、/root/ 等）→ UNC \\wsl$\<distro>\...
        return WslPathMapper.toWindowsToken(sandboxPath, null);  // /c/a/b → C:/a/b
    }
    
    @Override
    public boolean requiresCommandGate() {
        return false;  // 发行版整体隔离，命令级授权检查由隔离承担
    }
    
    @Override
    public void onWorkspaceRemoved(Path root) {
        WslUmounter.umountQuietly(root);
    }
    
    // 不实现任何工具注册方法——"让 AI 访问宿主"是独立 ToolProvider，与沙箱无关
    
    @Override
    public boolean registerBashTool() { return true; }
    // ... spawnSandboxed 等委托 WslUbuntuSandbox.run()
}
```

### 2.7 后端 id 与配置

| 后端 | id | priority | 说明 |
|------|-----|----------|------|
| WSL Ubuntu | `wsl-ubuntu` | 10 | 原 wsl-direct 改名 |
| Windows MIC | `windows-mic` | 5 | 不变 |
| DIRECT（默认沙箱） | — | — | OsSandbox 自身，无 Provider |

`WorkerProperties.Sandbox.type`：`auto` | `wsl-ubuntu` | `windows-mic` | `none`
- 旧值 `wsl-direct` → 归一为 `wsl-ubuntu`
- 旧值 `wsl-bwrap`/`bwrap`/`wsl` → 归一为 `auto` 并 WARN

### 2.8 插件注册

```java
// WindowsMicSandboxPlugin
public void activate(WorkerPluginContext ctx) {
    WorkerProperties props = ctx.getService(WorkerProperties.class);
    ctx.registerSandboxProvider(new WindowsMicSandboxProvider(props));
}

// WslUbuntuSandboxPlugin
public void activate(WorkerPluginContext ctx) {
    WorkerProperties props = ctx.getService(WorkerProperties.class);
    WorkspaceManager workspaces = ctx.getService(WorkspaceManager.class);
    ctx.registerSandboxProvider(new WslUbuntuSandboxProvider(props, workspaces));
}
```

sandbox-wsl-ubuntu 插件不负责 powershell 工具注册。"让 AI 访问宿主"的 `PowerShellToolProvider` 和 `PowerShellEnableSlashProvider` 留在 worker 核心，注册条件从 `isWslBackend()` 改为 `registerBashTool()`。

### 2.9 plugin.json

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
└── sandbox-docker/               ← 未来新增
    ├── plugin.json
    ├── pom.xml
    └── src/main/java/dev/everyagent/plugin/sandbox/docker/
        ├── DockerSandboxPlugin.java              (入口)
        ├── DockerSandboxProvider.java            (工厂)
        ├── DockerSandboxBackend.java             (执行)
        └── DockerClient.java                     (Docker API 封装)
```

### 3.2 plugin.json

```json
{
  "id": "sandbox-docker",
  "name": "Docker 沙箱",
  "version": "0.1.0",
  "description": "Docker 容器隔离沙箱（每任务一个容器，工作区 bind mount）",
  "author": "everyagent",
  "main": "dev.everyagent.plugin.sandbox.docker.DockerSandboxPlugin"
}
```

### 3.3 Provider 实现

```java
public final class DockerSandboxProvider implements SandboxProvider {
    @Override public String id() { return "docker"; }
    @Override public boolean isAvailable() { /* 探测 docker info, 缓存 */ }
    @Override public int priority() { return 30; } // 高于 wsl-ubuntu(10), auto 模式下优先选择
    @Override public SandboxBackend create(SandboxConfig config) {
        WorkerProperties props = (WorkerProperties) config.props();
        return new DockerSandboxBackend(props);
    }
}
```

### 3.4 Backend 实现（含路径翻译）

```java
public final class DockerSandboxBackend implements SandboxBackend {
    // 路径翻译：宿主 C:\workspace → 容器内 /workspace
    @Override
    public String toSandboxPath(Path hostPath) {
        // Windows → Linux 容器内路径映射
    }
    
    @Override
    public String toHostPath(String sandboxPath, List<Path> knownRoots) {
        // 容器内 Linux 路径 → 宿主 Windows 路径
    }
    
    @Override
    public boolean requiresCommandGate() {
        return false;  // 容器隔离，命令级授权检查由容器隔离承担
    }
    
    @Override
    public void onWorkspaceRemoved(Path root) {
        // 清理容器（docker rm）等
    }
    
    @Override
    @Override
    public boolean registerBashTool() { return true; }  // 容器内 bash
    
    @Override
    public ExecResult spawnSandboxed(String command, Path cwd, Map<String,String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege) {
        // 构造 docker run --rm -v <workspace>:/workspace -w /workspace
        //   --memory=<limit> --cpus=<limit> --network=<none|default>
        //   <image> <shell> -c "<command>"
        // 用 DirectSpawnSupport.runDirect() 执行 docker argv
    }
    
    // spawnNative: docker exec <container> <argv> 或回退 DirectSpawnSupport
}
```

### 3.5 配置扩展

在 `WorkerProperties.Sandbox` 中添加 Docker 子配置（或通过 `contributes.config` 让插件自管配置）：

```yaml
worker:
  sandbox:
    type: docker          # 显式选择 docker 后端
    docker:
      image: ubuntu:24.04    # 基础镜像
      network: none          # 默认无网络
      memory-limit: 2g       # 容器内存上限
      cpu-limit: 2.0         # CPU 核数上限
```

### 3.6 用户配置

在 `application-worker.yaml` 中：
```yaml
worker:
  sandbox:
    type: docker
    enabled: true
```

auto 模式下，Docker 可用时优先级最高 (priority=30)，自动选择。

## 四、迁移步骤（执行顺序）

### Step 1: SandboxBackend SPI 接口改造（plugin-api）
- 新增 4 个 default 方法：`toSandboxPath()`、`toHostPath()`、`requiresCommandGate()`、`onWorkspaceRemoved()`
- 删除 3 个 WSL 专属方法：`isWslBackend()`、`isWslBwrap()`、`isWslDirect()`
- `DirectSpawnSupport` 移到 plugin-api 的 `spi` 包，改为 public

### Step 2: Worker 核心去 WSL 化
- **FsToolSupport**：删除 `WslPathMapper`/`WslBwrapSandbox` 引用，`resolveWslPath()` 改为调 `sandbox.toHostPath(rel, knownRoots)`
- **CommandExecutor**：删除 `WslPathMapper`/`isWslBackend()`/`isWslDirect()`/`isWslBwrap()`/`useSeccompInterception()` 分支，命令授权检查改由 `sandbox.requiresCommandGate()` 决定是否执行
- **SkillAdvisor**：删除 `WslPathMapper`/`isWslBackend()`/`isWslDirect()` 引用，知识包路径翻译改用 `sandbox.toSandboxPath()`
- **ExternalFileTokenResolver**：删除 `WslPathMapper`/`isWslDirect()`/`isWslBwrap()` 引用，沙箱内路径改用 `sandbox.toSandboxPath()`
- **WorkspaceManager**：删除 `WslUmounter` 注入，删除工作区时改调 `sandbox.onWorkspaceRemoved(root)`
- **PowerShellEnableSlashProvider**：删除 `isWslBackend()` 条件，改用 `sandbox.registerBashTool()`
- **WorkerProperties**：删除 `Wsl` 嵌套类、`interceptPrivilege` 字段；`type` 取值更新
- **OsSandbox**：删除 `Backend` 枚举、所有 WSL/windows 分发逻辑、`isWsl*()` 方法；新增 SPI 方法转发到 delegate

### Step 3: 删除 worker 中的沙箱实现文件
- 删除 `os/wsl/` 整个目录（`WslBwrapSandbox`、`WslPathMapper`、`WslUmounter`、`WslDirectSandbox`）
- 删除 `os/windows/` 整个目录（`WindowsSandbox`、`Win32Ex`、`WindowsAcl`、`WindowsIntegrity`）
- 删除 `plugin/adapters/BuiltInSandboxProviders`、所有沙箱 Provider/Backend 适配器
- 删除对应测试文件

### Step 4: 创建 sandbox-windows-mic 插件
- 创建模块、`pom.xml`、`plugin.json`
- 移入 `WindowsSandbox` + `Win32Ex` + `WindowsAcl` + `WindowsIntegrity` + Provider + Backend + 入口类
- Backend 的 `toSandboxPath()` 等返回 null/false（非翻译型后端）

### Step 5: 创建 sandbox-wsl-ubuntu 插件
- 创建模块、`pom.xml`、`plugin.json`
- 移入 `WslDirectSandbox`（改名 `WslUbuntuSandbox`）、`WslPathMapper`、`WslUmounter` + Provider + Backend + 入口类
- 从删除的 `WslBwrapSandbox` 中提取共享类型到 `WslCommon.java`
- Backend 实现 `toSandboxPath()`/`toHostPath()`/`requiresCommandGate()`/`onWorkspaceRemoved()`
- 后端 id = `wsl-ubuntu`

### Step 6: 更新 ARCHITECTURE.md
- §7.10 沙箱后端描述更新：两个独立插件 + OsSandbox DIRECT 默认
- 删除所有 wsl-bwrap 相关描述
- 新增 SandboxBackend SPI 路径翻译方法文档

### Step 7: 验证
- `mvn clean install` 全量构建
- 验证 auto 模式：wsl-ubuntu(10) > windows-mic(5) > DIRECT
- 验证显式 type 路由
- 验证路径翻译：FsToolSupport 读 AI 产生的 Linux 路径正确翻译回 Windows 路径
- 验证 SkillAdvisor 知识包路径正确翻译为沙箱内路径
- 验证工作区删除时 onWorkspaceRemoved 被调用
- 验证 registerBashTool() 控制 powershell 斜杠命令注册

## 五、风险与注意事项

| 风险 | 缓解 |
|------|------|
| `SandboxBackend` SPI 删除 `isWslBackend()` 等方法 | 路径翻译新增 4 个 default 方法替代；powershell 工具注册改用 `registerBashTool()` 判定，不涉及 SPI |
| `toHostPath()` 翻译逻辑复杂（多种前缀匹配） | 完整逻辑搬入 wsl-ubuntu 插件 Backend；核心只调接口，不关心实现 |
| OsSandbox 被多个核心组件直接引用 | 保留为 `@Component` + `SandboxBackend`，所有方法签名兼容（SPI 方法转发 delegate） |
| wsl-ubuntu 插件依赖 worker | 与 git 插件同模式，内置插件允许依赖 worker |
| 插件加载顺序 | `BuiltInPluginScanner` 保证 builtin 优先；OsSandbox `@PostConstruct` 时 delegate 已就绪 |
| 旧配置 `type=wsl-bwrap` | 归一为 `auto` 并 WARN |
| 旧配置 `type=wsl-direct` | 归一为 `wsl-ubuntu`，静默兼容 |
| WorkerProperties 删除 `Wsl` 嵌套类 | WSL 专属配置移到插件 `contributes.config`；旧配置 key 忽略并 WARN |
| `OsSandbox.logBackendAtStartup()` 简化 | 改为 `delegate != null ? delegate.id() : "direct"`，不再 switch 具体后端 |
| sandbox-windows-mic 的 JNA 依赖 | 移到插件 pom.xml，JNA 跨平台无编译问题 |
