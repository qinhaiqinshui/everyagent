# 方案:windows-mic 后端改用 Medium IL(Restricted Token 不降级)

## 1. 背景与动机

### 1.1 现状

windows-mic 后端(WSL 探测失败回退)的沙箱进程执行链:

1. `CreateRestrictedToken(DISABLE_MAX_PRIVILEGE)` — 去全部特权与 SID
2. `SetTokenInformation(TokenIntegrityLevel, S-1-16-4096)` — 降为 Low IL
3. `CreateJobObject` — 进程数/内存/CPU/超时/KillOnJobClose
4. `CreateProcessAsUserW` — 以受限 token spawn

由于 MIC 的 `NO_WRITE_UP` 策略,Low IL 进程写不了默认 Medium 的用户文件。为此,命令执行前 `CommandExecutor.prepareWritableRoots()` 对**工作区根**和**每个 EXEC 授权根**做两件事:

- `WindowsIntegrity.ensureWritable()` — 标注 Low 完整性(`S:(ML;OICI;NW;;;LW)`)
- `WindowsAcl.grantWriteAccess()` — 追加 `BUILTIN\Users (OI)(CI) 修改+删除` Allow ACE

### 1.2 问题

这套方案有**两个确定性损害**:

1. **安全等级降低外溢**:Low IL 标注带 `(OI)(CI)` 继承,工作区里**用户正常新建的文件**也继承 Low IL。等于降低了这些文件的安全等级——任何 Low-IL 进程(如浏览器沙箱)都能写。这不只影响沙箱进程,影响的是用户的整个工作区树。

2. **永久残留,无回收**:worker 退出、工作区删除、授权过期时,没有把标注改回 Medium、不删除追加的 ACE。Low 标签和 DACL ACE **永久残留**在文件系统上。架构文档写的「幂等无残留」与实际不符。

3. **动态工作区放大损害**:工作区路径完全用户自定义(`workspaces.add` RPC 可注册任意路径),每新建一个工作区就标注一次、残留一次。外部授权根同理。工作区越多,被降低安全等级的目录树越多。

### 1.3 被否决的替代方案

| 方案 | 否决理由 |
|------|---------|
| AppContainer | 架构文档已否决:capability 模型不适合开放式开发工作流;普通 ACE 全失效,工作区文件需逐个授权,破坏面太大 |
| 专用低权账号 | 面临与 Low IL 相同的动态工作区问题:每新建工作区需追加该账号的 DACL ACE,仍有残留。只有工作区统一在固定父目录下才能零副作用,但当前架构允许任意路径 |
| 保留 Low IL + 加回收逻辑 | 回收需遍历整棵树逐个改回 Medium + 删除 ACE,遍历成本高(标注时就有 10 万项上限);且工作区可能被外部清理/移动,回收不完整 |

### 1.4 选定方案:Medium IL

沙箱进程改为 `Restricted Token(去特权) + Medium IL(不降级)`:

- 保留 `CreateRestrictedToken` 去特权(`SeDebugPrivilege` 等仍被剥)
- 保留 Job Object 资源护栏(进程数/内存/CPU/超时/KillOnJobClose)
- **跳过** `applyLowIntegrity()`
- **跳过** `prepareWritableRoots()`(不再标注 Low IL、不改 DACL)
- `WindowsIntegrity` 和 `WindowsAcl` 两个类保留不删,只是不再被调用

## 2. 代价:失去 OS 级写隔离兜底

当前 Low IL 的防线:

```
Java 层 PermissionGate 万一漏判(符号链接/realpath race/解析 bug)
    → Low IL 进程写 Medium 文件 → MIC NO_WRITE_UP → OS 拒绝
```

改为 Medium IL 后:

```
Java 层 PermissionGate 万一漏判
    → Medium IL 进程写 Medium 文件 → OS 放行
    → 越界写成功(沙箱进程 = 用户权限)
```

**评估**:这个 trade-off 可接受。理由:

1. **确定性损害 > 概率性风险**:Low IL 标注的副作用是每创建一个工作区就确定性地残留一次;PermissionGate 漏判是概率性事件,且有三层防御纵深(L1 `CommandCheck` 扫描 → L2 `execRootsSandboxed` 过滤 → `OverBroadRootCheck` 拒收过宽根)。
2. **WSL 后端本就无 IL 隔离**:wsl-direct 跑 root,wsl-bwrap 跑 user namespace,都不依赖 Windows MIC。windows-mic 作为回退后端,不需要维持比主力后端更强的隔离。
3. **Job Object + Restricted Token 仍保留**:资源耗尽(fork 炸弹/OOM/CPU 占满)和提权(sudo/runas)仍被拦。失去的只是「写工作区外的文件」这层 OS 兜底。

## 3. 改动清单

### 3.1 `WindowsSandbox.java`

**当前**(`runWithCmdLine` 方法):

```java
hRestricted = allowPrivilege
        ? buildCurrentToken()       // 提权档:保留当前 token
        : buildRestrictedToken();   // 降权档:去特权
if (!allowPrivilege && !applyLowIntegrity(hRestricted)) {
    log.warn("[sandbox] 设 Low IL 失败,继续(降权仍生效)");
}
```

**改后**:

```java
hRestricted = allowPrivilege
        ? buildCurrentToken()       // 提权档:保留当前 token(不变)
        : buildRestrictedToken();   // 降权档:去特权(不变)
// 不再调用 applyLowIntegrity:Medium IL 天然可写工作区,无需标注
// Low IL 标注副作用(降低文件安全等级 + 无回收)不可接受,详见 design-windows-mic-medium-il.md
```

`applyLowIntegrity()` 方法保留不删(未来可选档可能复用)。

### 3.2 `CommandExecutor.java`

**当前** (`prepareWritableRoots` 方法):

```java
private void prepareWritableRoots(Path cwd, boolean forceNative) {
    if (!windowsHost || (!forceNative && !sandbox.isWindowsSandboxActive())) {
        return;
    }
    boolean ok = WindowsIntegrity.ensureWritable(cwd);
    ok &= WindowsAcl.grantWriteAccess(cwd);
    for (Path extra : gate.execRootsSandboxed(task)) {
        ok &= WindowsIntegrity.ensureWritable(extra);
        ok &= WindowsAcl.grantWriteAccess(extra);
    }
    if (!ok && !writableRootWarned) {
        writableRootWarned = true;
        log.warn("...");
    }
}
```

**改后**:整个方法体直接返回(不再标注/授权):

```java
private void prepareWritableRoots(Path cwd, boolean forceNative) {
    // Medium IL 方案:沙箱进程运行在 Medium IL(Restricted Token 去特权但不降级),
    // 天然可写工作区与已授权目录,无需标注 Low 完整性或追加 DACL。
    // 零文件系统副作用,零残留。详见 docs/design-windows-mic-medium-il.md
}
```

`WindowsIntegrity` 和 `WindowsAcl` 的 import 保留(方法签名仍在,只是空体);`writableRootWarned` 字段可删可留(无害)。

### 3.3 `OsSandbox.java`

`isWindowsSandboxActive()` 语义不变(仍返回 windows + enabled + WINDOWS_MIC),但调用方(`CommandExecutor`)不再据此触发标注。该方法仍用于日志和后端判定。

启动日志 `logBackendAtStartup()` 中 windows-mic 分支更新描述:

```java
case WINDOWS_MIC -> log.info("[sandbox] 生效后端 = windows-mic(配置 {}):Restricted Token + Medium IL "
        + "+ Job Object,零文件系统副作用(Medium IL 天然可写,不标注/不改 ACL)", configured);
```

### 3.4 不改动的部分

| 组件 | 理由 |
|------|------|
| `WindowsIntegrity.java` | 保留不删,代码还在,只是不再被调用 |
| `WindowsAcl.java` | 同上 |
| `Win32Ex.java` | SDDL/完整性相关原语保留,不影响 |
| `PermissionGate` / `CommandCheck` / `GrantRegistry` | 授权链不变,仍是第一道防线 |
| `buildRestrictedToken()` | 仍调用,去特权仍生效 |
| `buildCurrentToken()` | 提权档不变 |
| Job Object 限额 | 不变 |
| wsl-direct / wsl-bwrap 后端 | 不受影响 |

## 4. 架构文档更新

### 4.1 §7.10 后端表

windows-mic 行改:

```
| **windows-mic** | Restricted Token + Medium IL + Job Object(Windows 原生路径,零文件系统副作用) | `windows-mic` / WSL 探测失败回退 |
```

### 4.2 §7.10 Low IL 可写性契约

删除「Windows Low IL 可写性契约」整条,替换为:

```
- **Windows Medium IL 契约**(对 windows-mic 后端):沙箱进程运行在 Medium IL(Restricted Token 去特权但不降级),天然可写工作区与已授权目录,不对文件系统做任何标注或 ACL 修改——零副作用、零残留。代价是失去 MIC NO_WRITE_UP 的 OS 级写隔离兜底,越界写拦截完全由 PermissionGate 责任链承担(L1 CommandCheck 扫描 → L2 execRootsSandboxed 过滤 → OverBroadRootCheck 拒收过宽根)。Job Object(进程数/内存/CPU/超时)与 Restricted Token(去特权)仍保留。
```

### 4.3 §7.10 外部授权根消费

windows-mic 相关描述更新:

```
windows-mic Medium IL 天然可写(无需标注/ACL,同工作区契约);
```

### 4.4 §7.10 PowerShell 方言

```
windows-mic 语义:Restricted Token + Medium IL + Job Object(零文件系统副作用)
```

### 4.5 §7.10 Windows 沙箱技术路线说明

追加:

```
windows-mic 后端已从 Low IL + ACL 标注改为 Medium IL(Restricted Token 不降级):Low IL 标注的 (OI)(CI) 继承会降低工作区整棵树的安全等级(用户正常新建文件也继承 Low),且无回收逻辑导致永久残留;动态工作区(用户可注册任意路径)放大此损害。Medium IL 牺牲了 MIC NO_WRITE_UP 的 OS 级写隔离兜底,但换来零文件系统副作用——在动态工作区场景下,确定性损害(Low IL 残留)大于概率性风险(PermissionGate 漏判)。
```

### 4.6 §14 红线第 6 条

```
6. **沙箱**:路径必须先规范化(realpath)再校验 workspace 根前缀,拒绝 `..`、绝对路径逃逸与符号链接逃逸;字符串前缀匹配不够;授权护的是「工作区外」,不是删除动作本身;不得绕过 PermissionGate 直接放行越界 IO;windows-mic 后端沙箱进程运行在 Medium IL,不对文件系统做标注或 ACL 修改;git 凭证只存 worker 本机加密文件,不经协议传输,注入走 env(askpass) 不经 shell 参数;
```

(原「Windows Low IL 树标注与 DACL 授权只对工作区/EXEC 授权根生效,不得开放到 Everyone」整句删除。)

## 5. 兼容性

### 5.1 已有 Low IL 标注的残留

从 Low IL 切到 Medium IL 后,之前已标注为 Low 的工作区树**不会自动恢复** Medium。残留的 Low 标签不影响功能(Medium IL 进程读写 Low 文件不受限,MIC 只拦 write-up 不拦 write-down),只是安全等级仍偏低。用户可手动恢复:

```powershell
icacls <path> /setintegritylevel (OI)(CI) M
```

或忽略——残留的 Low 标签不阻碍任何操作。

### 5.2 配置兼容

不新增配置项。`worker.sandbox.type=windows-mic` 仍然有效,只是行为从 Low IL 变为 Medium IL。`allowPrivilegeEscalation` 配置不变(提权档走 `buildCurrentToken`,与 IL 无关)。

### 5.3 wsl 后端不受影响

wsl-direct / wsl-bwrap 后端不经过 `WindowsSandbox` / `prepareWritableRoots`,完全不受此改动影响。
