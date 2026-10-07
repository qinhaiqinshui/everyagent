# Codex 沙箱手动测试用例文档

> **目标读者**：测试工程师
> **测试环境**：Windows 11（无 WSL），管理员账户，every-agent worker 已部署且 codex 插件已安装
> **前置知识**：无需了解代码，本文自包含所有操作步骤与预期结果
> **测试标记**：所有用例均为人工执行（需 UAC 弹窗、管理员权限、Windows 原生功能）

---

## 0. 测试准备

### 0.1 环境要求

| 项目 | 要求 |
|---|---|
| 操作系统 | Windows 11（Pro/Home 均可，分别测试） |
| WSL | **未安装或已卸载**（确保 codex 后端被选中） |
| 账户权限 | 管理员（setup 需要 UAC） |
| Java | JDK 25（worker 运行需要） |
| 工作区 | 默认工作区或手动注册的工作区（有写入权限的目录） |

### 0.2 确认 codex 后端生效

启动 worker 后，在 AI 对话中执行命令：

```
echo "hello"
```

**预期**：命令执行成功，返回 `hello`。

确认方式：查看 worker 日志，应包含 `[exec] codex rc=0` 字样——表示 codex 后端被选中并成功执行。

### 0.3 测试路径约定

本文档使用以下占位路径，请替换为实际路径：

| 占位符 | 含义 | 示例 |
|---|---|---|
| `<WORKSPACE>` | 工作区根目录 | `C:\Users\Test\defaultworkspace` |
| `<EXTERNAL>` | 工作区外的测试目录 | `C:\CodexTest\external` |
| `<CODEX_HOME>` | codex 状态目录 | 默认 `<沙箱持久根>/codex`，可在 worker 日志中查找 |

### 0.4 测试数据准备

```powershell
# 在 PowerShell（非沙箱）中执行，准备测试目录
mkdir C:\CodexTest\external -Force
mkdir C:\CodexTest\sandboxtest -Force
cd C:\CodexTest\sandboxtest
echo "test content" > inside.txt
```

---

## 1. Setup 安装流程

### TC-1.1 首次 setup 弹 UAC

**前置**：codex 沙箱从未 setup 过（`<CODEX_HOME>\.sandbox\setup_marker.json` 不存在）

**步骤**：
1. 启动 worker（确保 WSL 不可用，codex 优先级最高）
2. 在 AI 对话中发送任意命令（如 `echo "hello"`）
3. 观察 Windows 桌面是否弹出 UAC 提权确认窗

**预期**：
- ✅ UAC 弹窗出现，提示需要管理员权限
- ✅ 用户点击"是"后，setup 自动完成（建账户/组/ACL/防火墙/WFP/marker）
- ✅ setup 完成后命令正常执行，返回 `hello`
- ✅ 以下对象应存在：
  - 本地账户 `EACodexOffline` 和 `EACodexOnline`（`net user` 可查）
  - 本地组 `EACodexSandboxUsers`（`net localgroup` 可查）
  - `<CODEX_HOME>\.sandbox\setup_marker.json`（非空，含 version 字段）
  - `<CODEX_HOME>\.sandbox-secrets\sandbox_users.json`（凭据文件）

**验证命令**：
```powershell
net user EACodexOffline
net user EACodexOnline
net localgroup EACodexSandboxUsers
type "<CODEX_HOME>\.sandbox\setup_marker.json"
```

---

### TC-1.2 setup 幂等——第二次不弹 UAC

**前置**：TC-1.1 已完成，marker 就绪

**步骤**：
1. 重启 worker
2. 在 AI 对话中发送命令 `echo "hello again"`
3. 观察是否弹出 UAC

**预期**：
- ✅ **不弹 UAC**（marker 短路返回）
- ✅ 命令正常执行，返回 `hello again`
- ✅ setup_marker.json 内容不变（未重写）

---

### TC-1.3 setup 幂等——账户/凭据失配时自动修复

**前置**：TC-1.1 已完成

**步骤**：
1. 在 PowerShell（管理员）中重置 Online 账户密码：
   ```powershell
   net user EACodexOnline "wrongpassword123!"
   ```
2. 在 AI 对话中发送命令 `echo "recover test"`（无需重启 worker——执行链内自动自愈）
3. 观察 UAC 是否弹出（修复式 setup）

**预期**：
- ✅ 命令检测到凭据失配（Windows error 1326 等），自动触发修复式 setup（可能弹 UAC），setup 完成后**同一请求内**重试命令
- ✅ 命令正常执行，返回 `recover test`（1326 不再直接回给模型）
- ✅ 凭据文件 `<CODEX_HOME>\.sandbox-secrets\sandbox_users.json` 被更新（两账户密码均重新生成）
- ✅ UAC 被拒绝时报错同时携带原始凭据错误与 setup 失败原因；重发命令可再次触发自愈
- ✅ 一次命令至多自愈一次（自愈后仍失配则报「账户/凭据自愈后重试仍失败」，不循环）

---

### TC-1.3b 依赖件被删自愈矩阵——账户/组/凭据文件/marker（design.md §4.3.1）

**前置**：TC-1.1 已完成

**步骤**（四组独立执行，每组破坏后直接发命令 `echo "selfheal"`，均不重启 worker）：
1. **删账户**：`net user EACodexOnline /delete` → 发命令
2. **删组**（复现「帐户名与安全标识间无任何映射完成」报错）：`net localgroup EACodexSandboxUsers /delete` → 发命令
3. **删凭据文件**：删除 `<CODEX_HOME>\.sandbox-secrets\sandbox_users.json` → 发命令
4. **删 marker**：删除 `<CODEX_HOME>\.sandbox\setup_marker.json` → 发命令

**预期**（四组一致）：
- ✅ 每组均自动触发重 setup（可能弹一次 UAC），重建对应依赖件（账户经 NetUserAdd 重建/组重建+重挂成员/凭据重写/marker 重提交）后**同一请求内**重试命令成功，返回 `selfheal`
- ✅ 全程无「请重新启动 worker」/「re-run setup」类让人工干预的报错
- ✅ 组被删场景不再出现 `[codex sandbox 执行失败] 帐户名与安全标识间无任何映射完成`（1332 已入自愈管线）
- ✅ UAC 被拒绝时报错携带原始错误与 setup 失败原因；重发命令可再次触发自愈
- ✅ 写根 ACL 被 `icacls <root> /reset`、`.sandbox-bin` 被删、cap_sids.json 被删——本就按命令自愈（§4.3.1 #5-#7），验证命令仍正常即可

---

### TC-1.4 账户隐藏——登录界面不可见

**前置**：TC-1.1 已完成

**步骤**：
1. 注销当前用户（或锁屏）
2. 查看 Windows 登录界面

**预期**：
- ✅ 登录界面**不显示** `EACodexOffline` 和 `EACodexOnline`
- ✅ 只显示真实用户账户

**验证命令**：
```powershell
# 检查注册表
reg query "HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon\SpecialAccounts\UserList" /v EACodexOffline
# 预期：值为 0x0
```

---

### TC-1.5 沙箱目录权限锁定

**前置**：TC-1.1 已完成

**步骤**：
1. 检查三个关键目录的 ACL

**验证命令**：
```powershell
# .sandbox 目录：沙箱组应有 RWX
icacls "<CODEX_HOME>\.sandbox"

# .sandbox-secrets 目录：沙箱组应被 DENY
icacls "<CODEX_HOME>\.sandbox-secrets"

# .sandbox-bin 目录：沙箱组应有 R+X，DACL 为 Protected
icacls "<CODEX_HOME>\.sandbox-bin"
```

**预期**：
- ✅ `.sandbox`：`EACodexSandboxUsers:(OI)(CI)(F)` （完全控制）
- ✅ `.sandbox-secrets`：`EACodexSandboxUsers:(DENY)` （拒绝访问）
- ✅ `.sandbox-bin`：`EACodexSandboxUsers:(OI)(CI)(RX)` 且 DACL 标记为 Protected

---

## 2. 文件读写隔离

### TC-2.1 工作区内读写——成功

**前置**：TC-1.1 已完成，工作区 `<WORKSPACE>` 存在

**步骤**：
1. 在 AI 对话中执行：
   ```
   echo "test content" > <WORKSPACE>\test.txt
   ```
2. 在 AI 对话中执行：
   ```
   type <WORKSPACE>\test.txt
   ```

**预期**：
- ✅ 写入成功（exit code = 0）
- ✅ 读取成功，返回 `test content`

---

### TC-2.2 工作区外写——OS 拒绝

**前置**：TC-2.1 已完成，`<EXTERNAL>` 目录不在 externalRoots 中

**步骤**：
1. 在 AI 对话中执行：
   ```
   echo "forbidden" > C:\CodexTest\external\hack.txt
   ```

**预期**：
- ✅ 命令执行失败（exit code ≠ 0）
- ✅ stderr 包含 `Access denied` 或 `Permission denied` 或类似拒绝信息
- ✅ `C:\CodexTest\external\hack.txt` 文件**不存在**

**验证命令**：
```powershell
Test-Path "C:\CodexTest\external\hack.txt"
# 预期：False
```

---

### TC-2.3 工作区外读——OS 拒绝

**前置**：`C:\CodexTest\external\secret.txt` 存在且可被真实用户读取

**步骤**：
```powershell
# 准备（非沙箱）
echo "secret data" > C:\CodexTest\external\secret.txt
```

1. 在 AI 对话中执行：
   ```
   type C:\CodexTest\external\secret.txt
   ```

**预期**：
- ✅ 命令执行失败（exit code ≠ 0）
- ✅ stderr 包含 `Access denied` 或类似拒绝信息
- ✅ **不返回** `secret data` 内容

---

### TC-2.4 系统目录读——成功

**前置**：TC-1.1 已完成

**步骤**：
1. 在 AI 对话中执行：
   ```
   dir C:\Windows\System32\cmd.exe
   ```

**预期**：
- ✅ 命令执行成功（exit code = 0）
- ✅ 能列出 `cmd.exe` 文件信息

---

### TC-2.5 系统目录写——OS 拒绝

**前置**：TC-1.1 已完成

**步骤**：
1. 在 AI 对话中执行：
   ```
   echo "malware" > C:\Windows\evil.dll
   ```

**预期**：
- ✅ 命令执行失败（exit code ≠ 0）
- ✅ stderr 包含拒绝信息
- ✅ `C:\Windows\evil.dll` 文件**不存在**

---

### TC-2.6 用户目录读——OS 拒绝

**前置**：TC-1.1 已完成

**步骤**：
1. 在 AI 对话中执行：
   ```
   type %USERPROFILE%\.ssh\id_rsa
   ```

**预期**：
- ✅ 命令执行失败（exit code ≠ 0）
- ✅ 不返回密钥内容（如果文件存在的话）
- ✅ stderr 包含拒绝信息

---

### TC-2.7 授权后写外部目录——成功

**前置**：`C:\CodexTest\external` 不在 externalRoots 中

**步骤**：
1. 在 AI 对话中请求写外部目录，如使用文件工具写 `C:\CodexTest\external\authorized.txt`
2. PermissionGate 弹出授权弹窗
3. 用户选择"本任务全程允许"
4. 在 AI 对话中执行：
   ```
   echo "authorized" > C:\CodexTest\external\authorized.txt
   ```

**预期**：
- ✅ 步骤 2：授权弹窗出现
- ✅ 步骤 3：用户选择后路径注册为 externalRoot
- ✅ 步骤 4：命令执行成功（exit code = 0）
- ✅ `C:\CodexTest\external\authorized.txt` 存在，内容为 `authorized`

---

### TC-2.8 授权取消后写外部目录——OS 拒绝

**前置**：TC-2.7 已完成，`C:\CodexTest\external` 曾被授权

**步骤**：
1. 移除 externalRoot 授权（通过工作区管理或重启）
2. 在 AI 对话中执行：
   ```
   echo "after revoke" > C:\CodexTest\external\after.txt
   ```

**预期**：
- ✅ 命令执行失败（exit code ≠ 0）
- ✅ stderr 包含拒绝信息
- ✅ `C:\CodexTest\external\after.txt` 不存在

---

### TC-2.9 junction/symlink 不穿越

**前置**：`C:\CodexTest\external\real.txt` 存在

**步骤**：
```powershell
# 准备（非沙箱）：创建指向外部目录的 junction
cmd /c mklink /J "<WORKSPACE>\escape" "C:\CodexTest\external"
```

1. 在 AI 对话中执行：
   ```
   type <WORKSPACE>\escape\real.txt
   ```

**预期**：
- ✅ 命令执行失败（exit code ≠ 0）
- ✅ 沙箱不穿越 junction 读到外部文件
- ✅ stderr 包含拒绝信息

**清理**：
```powershell
cmd /c rmdir "<WORKSPACE>\escape"
```

---

### TC-2.10 脚本内部写工作区外——OS 兜底拒绝

**前置**：`C:\CodexTest\external` 不在 externalRoots 中

**步骤**：
```powershell
# 准备（非沙箱）：在工作区内创建测试脚本
@'
try {
    Set-Content -Path "C:\CodexTest\external\script_write.txt" -Value "from script"
    Write-Host "SUCCESS"
} catch {
    Write-Host "FAILED: $_"
    exit 1
}
'@ | Set-Content "<WORKSPACE>\test_script.ps1"
```

1. 在 AI 对话中执行：
   ```
   powershell -ExecutionPolicy Bypass -File <WORKSPACE>\test_script.ps1
   ```

**预期**：
- ✅ 脚本执行，但写入外部目录失败
- ✅ 输出 `FAILED:` 而非 `SUCCESS`
- ✅ `C:\CodexTest\external\script_write.txt` 不存在

---

## 3. 网络隔离

### TC-3.1 Offline 账户断网——DNS 解析失败

**前置**：`worker.sandbox.allow-network=false`（或 `codex.network-policy=offline`）

**步骤**：
1. 重启 worker 确保配置生效
2. 在 AI 对话中执行：
   ```
   nslookup www.baidu.com
   ```

**预期**：
- ✅ 命令执行失败或返回空结果
- ✅ stderr 包含 DNS 解析失败信息
- ✅ 无法解析出任何 IP 地址

---

### TC-3.2 Offline 账户断网——TCP 连接失败

**前置**：TC-3.1 的配置

**步骤**：
1. 在 AI 对话中执行：
   ```
   Test-NetConnection -ComputerName www.baidu.com -Port 443
   ```

**预期**：
- ✅ TCP 连接失败
- ✅ `TcpTestSucceeded` 为 `False`
- ✅ 无法建立任何外网 TCP 连接

---

### TC-3.3 Offline 账户断网——ICMP ping 失败

**前置**：TC-3.1 的配置

**步骤**：
1. 在 AI 对话中执行：
   ```
   ping www.baidu.com -n 3
   ```

**预期**：
- ✅ ping 失败（全部超时或请求被阻止）
- ✅ 无任何 ICMP 回复

---

### TC-3.4 Offline 账户断网——环回也被拦截

**前置**：TC-3.1 的配置

**步骤**：
1. 在另一终端启动一个本地监听服务（如 `python -m http.server 8888`）
2. 在 AI 对话中执行：
   ```
   curl http://127.0.0.1:8888
   ```

**预期**：
- ✅ 连接被拒绝或超时
- ✅ 无法访问环回地址上的服务（除非端口在 `codex.proxy-ports` 放行列表中）

---

### TC-3.5 Online 账户联网——网络正常

**前置**：`worker.sandbox.allow-network=true` 且 `codex.network-policy=auto`（或 `online`）

**步骤**：
1. 重启 worker 确保配置生效
2. 在 AI 对话中执行：
   ```
   curl https://www.baidu.com -UseBasicParsing
   ```

**预期**：
- ✅ 命令执行成功
- ✅ 返回百度首页 HTML 内容
- ✅ 网络正常访问

---

### TC-3.6 防火墙规则存在性验证

**前置**：TC-1.1 已完成（Offline 账户已 setup）

**步骤**：
1. 检查防火墙规则

**验证命令**：
```powershell
# 查找 codex 相关防火墙规则
Get-NetFirewallRule | Where-Object { $_.DisplayName -like "*EveryAgent*" -or $_.DisplayName -like "*codex*" -or $_.DisplayName -like "*Codex*" }
```

**预期**：
- ✅ 存在 4 条 block 规则（出/入非环回、环回 TCP、环回 UDP）
- ✅ 规则的 `Action` 为 `Block`
- ✅ 规则的 `LocalUser` 包含 Offline 账户的 SID

---

### TC-3.7 WFP 过滤器存在性验证

**前置**：TC-1.1 已完成

**步骤**：
1. 检查 WFP 过滤器

**验证命令**：
```powershell
# 列出 WFP provider（需管理员）
netsh wfp show state
# 或用 PowerShell
Get-NetFirewallProfile | Format-List
```

**预期**：
- ✅ 存在 codex 沙箱自有的 WFP provider/sublayer（固定 GUID）
- ✅ 存在 12 条持久 filter（ICMP/DNS53/DoT853/SMB445/139 × v4/v6）

---

## 4. 提权防护

### TC-4.1 直接提权命令——拒绝

**前置**：TC-1.1 已完成

**步骤**：
1. 在 AI 对话中执行：
   ```
   runas /user:Administrator "cmd.exe"
   ```

**预期**：
- ✅ 命令执行失败（沙箱账户无密码/凭据，无法提权）
- ✅ 或 PermissionGate 弹窗拦截（命令含提权动词）

---

### TC-4.2 脚本内部提权——失败

**前置**：TC-1.1 已完成

**步骤**：
```powershell
# 准备（非沙箱）：创建尝试提权的脚本
@'
try {
    $p = Start-Process -FilePath "cmd.exe" -Verb RunAs -PassThru -ErrorAction Stop
    Write-Host "ELEVATED: PID=$($p.Id)"
} catch {
    Write-Host "PRIVILEGE_DENIED: $_"
    exit 1
}
'@ | Set-Content "<WORKSPACE>\privesc.ps1"
```

1. 在 AI 对话中执行：
   ```
   powershell -ExecutionPolicy Bypass -File <WORKSPACE>\privesc.ps1
   ```

**预期**：
- ✅ 输出 `PRIVILEGE_DENIED` 而非 `ELEVATED`
- ✅ 沙箱账户令牌特权已被清空，无法弹 UAC 或提权

---

### TC-4.3 sudo/su 类命令（如果存在）

**前置**：TC-1.1 已完成

**步骤**：
1. 在 AI 对话中执行：
   ```
   sudo whoami
   ```

**预期**：
- ✅ 命令执行失败（Windows 无 sudo，或被沙箱账户权限拒绝）

---

## 5. 进程管理

### TC-5.1 进程树终止——Job Object 约束

**前置**：TC-1.1 已完成

**步骤**：
```powershell
# 准备（非沙箱）：创建会 spawn 子进程的脚本
@'
# 启动一个后台进程
$proc = Start-Process -FilePath "powershell.exe" -ArgumentList "-Command", "Start-Sleep -Seconds 60" -PassThru
Write-Host "CHILD_PID=$($proc.Id)"
Start-Sleep -Seconds 2
# 检查子进程是否还在
$child = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if ($child) {
    Write-Host "CHILD_ALIVE"
} else {
    Write-Host "CHILD_DEAD"
}
'@ | Set-Content "<WORKSPACE>\spawn_test.ps1"
```

1. 在 AI 对话中执行：
   ```
   powershell -ExecutionPolicy Bypass -File <WORKSPACE>\spawn_test.ps1
   ```
2. 命令执行完毕后，立即检查残留进程：

```powershell
# 在非沙箱 PowerShell 中执行
Get-Process powershell | Where-Object { $_.StartTime -gt (Get-Date).AddMinutes(-5) }
```

**预期**：
- ✅ 命令执行过程中子进程被创建并短暂存活
- ✅ 命令结束后（Job Object 关闭），所有子进程被终止
- ✅ 步骤 2 查不到沙箱 spawn 的残留子进程

---

### TC-5.2 命令超时——看门狗终止

**前置**：`worker.sandbox.timeout-ms` 设置为一个较小值（如 10000=10 秒）

**步骤**：
1. 在 AI 对话中执行一个会长时间运行的命令：
   ```
   Start-Sleep -Seconds 120
   ```

**预期**：
- ✅ 命令在约 10 秒后被强制终止
- ✅ 输出包含超时标记（exit code = 192 或标注 timed out）
- ✅ 不等待 120 秒

---

### TC-5.3 输出截断——大输出命令

**前置**：TC-1.1 已完成

**步骤**：
1. 在 AI 对话中执行一个会产生大量输出的命令：
   ```
   for ($i=0; $i -lt 100000; $i++) { Write-Output "line $i" }
   ```

**预期**：
- ✅ 命令执行成功
- ✅ 输出被截断（每流上限 100 万字符）
- ✅ 输出末尾有截断标记

---

## 6. 命令执行基础功能

### TC-6.1 简单命令——echo

**步骤**：
1. 在 AI 对话中执行：
   ```
   echo "hello world"
   ```

**预期**：
- ✅ 返回 `hello world`
- ✅ exit code = 0

---

### TC-6.2 多命令管道

**步骤**：
1. 在 AI 对话中执行：
   ```
   Get-Process | Select-Object -First 5 | Format-Table Name, Id
   ```

**预期**：
- ✅ 返回前 5 个进程的名称和 PID
- ✅ exit code = 0

---

### TC-6.3 空命令——拒绝执行

**步骤**：
1. 在 AI 对话中执行：
   ```
   ```
   （空字符串或仅空格）

**预期**：
- ✅ 返回 `execute: command 不能为空`
- ✅ 不启动沙箱进程

---

### TC-6.4 无路径系统命令——直接放行

**步骤**：
1. 在 AI 对话中执行：
   ```
   netstat -an | Select-Object -First 10
   ```

**预期**：
- ✅ 命令执行成功（无路径候选，PermissionGate 直接放行）
- ✅ 返回网络连接列表
- ✅ 不弹授权窗

---

### TC-6.5 系统信息查询命令

**步骤**：
1. 在 AI 对话中执行：
   ```
   systeminfo | Select-Object -First 5
   ```

**预期**：
- ✅ 命令执行成功
- ✅ 返回系统信息（OS 名称、版本等）
- ✅ 不弹授权窗

---

### TC-6.6 rg 搜索命令

**前置**：工作区内有多个文件

**步骤**：
1. 在 AI 对话中执行：
   ```
   rg "test" <WORKSPACE>
   ```

**预期**：
- ✅ rg 命令可用（插件自带 rg.exe 已前置进 PATH）
- ✅ 返回匹配 `test` 的搜索结果
- ✅ exit code = 0 或 1（1 表示无匹配，正常）

---

### TC-6.7 命令 stderr 输出

**步骤**：
1. 在 AI 对话中执行：
   ```
   Write-Error "test error message"
   ```

**预期**：
- ✅ stderr 被正确捕获
- ✅ 输出中包含 `[stderr]` 标记和错误信息
- ✅ stdout 和 stderr 分离显示

---

## 7. PermissionGate 授权联动

### TC-7.1 命令直接引用外部路径——弹窗

**前置**：`C:\CodexTest\external` 不在 externalRoots 中

**步骤**：
1. 在 AI 对话中执行：
   ```
   type C:\CodexTest\external\secret.txt
   ```

**预期**：
- ✅ PermissionGate 弹出授权弹窗
- ✅ 弹窗显示命令内容和目标路径
- ✅ 用户选择"拒绝" → 命令不执行，返回权限拒绝
- ✅ 用户选择"本轮运行内允许" → 路径授权，命令进入沙箱执行

---

### TC-7.2 @ 弹窗显式选择外部文件——不弹窗

**前置**：`C:\CodexTest\external\data.txt` 存在

**步骤**：
1. 在 AI 对话输入框中打 `@`
2. 点击 `+` 图标，选择 `C:\CodexTest\external\data.txt`
3. AI 使用该文件路径执行操作

**预期**：
- ✅ 选择动作注册路径为 externalRoot
- ✅ AI 访问该路径时**不再弹授权弹窗**
- ✅ 沙箱内可读写该路径

---

### TC-7.3 危险动词仅在工作区外才弹窗

**步骤**：
1. 在 AI 对话中执行（工作区内删除）：
   ```
   Remove-Item <WORKSPACE>\test.txt
   ```
2. 在 AI 对话中执行（工作区外删除）：
   ```
   Remove-Item C:\CodexTest\external\secret.txt
   ```

**预期**：
- ✅ 步骤 1：**不弹窗**，直接执行（工作区内增删改查自由）
- ✅ 步骤 2：**弹窗**（工作区外 + 危险动词）

---

### TC-7.4 授权粒度——同目录不重复弹

**前置**：`C:\CodexTest\external` 不在 externalRoots 中

**步骤**：
1. 在 AI 对话中执行：
   ```
   type C:\CodexTest\external\file1.txt
   ```
2. 用户选择"本任务全程允许"
3. 在 AI 对话中执行：
   ```
   type C:\CodexTest\external\file2.txt
   ```

**预期**：
- ✅ 步骤 1：弹窗
- ✅ 步骤 3：**不弹窗**（同目录已授权，不重复弹）

---

## 8. 卸载

### TC-8.1 完整卸载——所有资源消失

**前置**：TC-1.1 已完成，codex 沙箱已 setup

**步骤**：
1. 执行卸载（经提权 helper）：
   ```powershell
   # 组装卸载载荷
   $payload = @{
       version = 5
       offline_username = "EACodexOffline"
       online_username = "EACodexOnline"
       group_name = "EACodexSandboxUsers"
       codex_home = "<CODEX_HOME>"
       real_user = $env:USERNAME
       mode = "remove"
   } | ConvertTo-Json -Compress
   
   $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
   
   java -cp "<插件jar路径>" dev.everyagent.plugin.sandbox.codex.setup.SetupHelperMain --setup-payload $b64
   ```
2. UAC 弹窗 → 用户同意
3. 等待卸载完成

**预期——以下对象全部消失**：

| 检查项 | 验证命令 | 预期 |
|---|---|---|
| Offline 账户 | `net user EACodexOffline` | 不存在 |
| Online 账户 | `net user EACodexOnline` | 不存在 |
| 沙箱组 | `net localgroup EACodexSandboxUsers` | 不存在 |
| .sandbox 目录 | `Test-Path "<CODEX_HOME>\.sandbox"` | False |
| .sandbox-secrets 目录 | `Test-Path "<CODEX_HOME>\.sandbox-secrets"` | False |
| .sandbox-bin 目录 | `Test-Path "<CODEX_HOME>\.sandbox-bin"` | False |
| 防火墙规则 | `Get-NetFirewallRule -DisplayName "*Codex*"` | 空 |
| WFP 过滤器 | `netsh wfp show state`（检查 codex GUID） | 不存在 |
| 注册表 UserList | `reg query "HKLM\...\UserList" /v EACodexOffline` | 不存在 |
| cap_sid 文件 | `Test-Path "<CODEX_HOME>\cap_sid"` | False |

---

### TC-8.2 卸载后重新 setup——干净重建

**前置**：TC-8.1 已完成

**步骤**：
1. 重启 worker
2. 在 AI 对话中执行命令 `echo "rebuild"`
3. UAC 弹窗 → 用户同意
4. setup 重新完成

**预期**：
- ✅ UAC 弹窗出现（marker 已删，需要重新 setup）
- ✅ setup 完成后命令正常执行
- ✅ 所有资源重新创建（账户/组/ACL/防火墙/WFP/marker）

---

### TC-8.3 卸载不影响无关对象

**前置**：TC-1.1 已完成，系统上有其他非 codex 的防火墙规则

**步骤**：
1. 记录卸载前的防火墙规则数量：
   ```powershell
   (Get-NetFirewallRule | Where-Object { $_.DisplayName -notlike "*Codex*" -and $_.DisplayName -notlike "*EveryAgent*" }).Count
   ```
2. 执行卸载（TC-8.1）
3. 卸载后再次统计非 codex 规则数量

**预期**：
- ✅ 卸载前后非 codex 防火墙规则数量**不变**
- ✅ 无关账户/组/规则不受影响

---

## 9. 边界与异常

### TC-9.1 非 Windows 平台——拒绝执行

**前置**：在 Linux 或 macOS 上运行 worker（如果可测）

**步骤**：
1. 在 AI 对话中执行 `echo "hello"`

**预期**：
- ✅ 返回 `[codex sandbox 仅在 Windows 上可用,当前平台: ...]`
- ✅ 不崩溃，不执行

---

### TC-9.2 setup marker 不存在——拒绝执行

**前置**：手动删除 `<CODEX_HOME>\.sandbox\setup_marker.json`（模拟 setup 未完成）

**步骤**：
1. 在 AI 对话中执行 `echo "hello"`

**预期**：
- ✅ 返回 `[codex sandbox 未完成 setup;setup 应在后端 create() 时自动触发...]`
- ✅ 不执行命令
- ✅ 不静默降级为明文执行

---

### TC-9.3 worker 拒绝 UAC——setup 失败

**前置**：codex 从未 setup

**步骤**：
1. 在 AI 对话中执行 `echo "hello"`
2. UAC 弹窗出现
3. 用户点击"否"（拒绝提权）

**预期**：
- ✅ setup 失败
- ✅ 命令不执行
- ✅ 返回可读的错误信息（包含"用户拒绝"或类似）
- ✅ 不静默降级为明文执行

---

### TC-9.4 企业 GPO 覆盖防火墙——setup 拒绝

**前置**：企业组策略锁死防火墙策略（`LocalPolicyModifyState` 非 `OK`）

**步骤**：
1. 首次 setup（TC-1.1 流程）

**预期**：
- ✅ setup 失败
- ✅ 错误信息包含 `HelperFirewallPolicyIneffective` 或类似
- ✅ **不降级为 netsh 命令行替代**（宁拒不裸）
- ✅ 不创建半套规则

---

### TC-9.5 并发 setup 竞态——互斥保护

**前置**：codex 从未 setup

**步骤**：
1. 同时启动两个 worker 实例
2. 两个实例同时触发首次 setup

**预期**：
- ✅ 只有一个 setup 实际执行（`Global\EveryAgentCodexSetup` 互斥）
- ✅ 另一个等待或短路返回
- ✅ 不产生重复账户/规则

---

## 10. 配置项验证

### TC-10.1 account-prefix 自定义

**前置**：配置 `codex.account-prefix=MyCorp`

**步骤**：
1. 首次 setup
2. 检查账户名

**预期**：
- ✅ 账户名为 `MyCorpOffline` 和 `MyCorpOnline`
- ✅ 组名为 `MyCorpSandboxUsers`
- ✅ 其他行为不变

---

### TC-10.2 network-policy=offline 强制断网

**前置**：配置 `codex.network-policy=offline`，即使 `allow-network=true`

**步骤**：
1. 重启 worker
2. 在 AI 对话中执行 `curl https://www.baidu.com`

**预期**：
- ✅ 网络被拒绝（使用 Offline 账户）
- ✅ `network-policy=offline` 覆盖了 `allow-network=true`

---

### TC-10.3 network-policy=online 强制联网

**前置**：配置 `codex.network-policy=online`，即使 `allow-network=false`

**步骤**：
1. 重启 worker
2. 在 AI 对话中执行 `curl https://www.baidu.com`

**预期**：
- ✅ 网络正常（使用 Online 账户）
- ✅ `network-policy=online` 覆盖了 `allow-network=false`

---

## 附录 A：测试清单速查表

| 编号 | 模块 | 测试名 | 关键验证点 |
|---|---|---|---|
| TC-1.1 | Setup | 首次 setup 弹 UAC | UAC 弹窗 + 账户/组/marker 创建 |
| TC-1.2 | Setup | setup 幂等 | 第二次不弹 UAC |
| TC-1.3 | Setup | 凭据失配修复 | 自动重 setup |
| TC-1.4 | Setup | 账户隐藏 | 登录界面不可见 |
| TC-1.5 | Setup | 目录权限锁定 | 三目录 ACL 正确 |
| TC-2.1 | 文件 | 工作区内读写 | 成功 |
| TC-2.2 | 文件 | 工作区外写 | OS 拒绝 |
| TC-2.3 | 文件 | 工作区外读 | OS 拒绝 |
| TC-2.4 | 文件 | 系统目录读 | 成功 |
| TC-2.5 | 文件 | 系统目录写 | OS 拒绝 |
| TC-2.6 | 文件 | 用户目录读 | OS 拒绝 |
| TC-2.7 | 文件 | 授权后写外部 | 成功 |
| TC-2.8 | 文件 | 授权取消后写 | OS 拒绝 |
| TC-2.9 | 文件 | junction 不穿越 | OS 拒绝 |
| TC-2.10 | 文件 | 脚本内部写外部 | OS 兜底拒绝 |
| TC-3.1 | 网络 | Offline DNS 失败 | 解析失败 |
| TC-3.2 | 网络 | Offline TCP 失败 | 连接失败 |
| TC-3.3 | 网络 | Offline ping 失败 | ICMP 被拦 |
| TC-3.4 | 网络 | Offline 环回被拦 | 本地服务不可达 |
| TC-3.5 | 网络 | Online 联网正常 | 网络正常 |
| TC-3.6 | 网络 | 防火墙规则存在 | 4 条 block 规则 |
| TC-3.7 | 网络 | WFP 过滤器存在 | 12 条 filter |
| TC-4.1 | 提权 | 直接提权拒绝 | 失败 |
| TC-4.2 | 提权 | 脚本内部提权 | 失败 |
| TC-4.3 | 提权 | sudo 命令 | 失败 |
| TC-5.1 | 进程 | Job Object 进程树终止 | 无残留 |
| TC-5.2 | 进程 | 命令超时终止 | 看门狗生效 |
| TC-5.3 | 进程 | 大输出截断 | 截断标记 |
| TC-6.1 | 命令 | echo | 成功 |
| TC-6.2 | 命令 | 管道 | 成功 |
| TC-6.3 | 命令 | 空命令 | 拒绝 |
| TC-6.4 | 命令 | 无路径命令 | 放行 |
| TC-6.5 | 命令 | 系统信息查询 | 放行 |
| TC-6.6 | 命令 | rg 搜索 | 可用 |
| TC-6.7 | 命令 | stderr 输出 | 分离显示 |
| TC-7.1 | 授权 | 命令引用外部路径 | 弹窗 |
| TC-7.2 | 授权 | @ 选择外部文件 | 不弹窗 |
| TC-7.3 | 授权 | 危险动词工作区内 | 不弹窗 |
| TC-7.4 | 授权 | 同目录不重复弹 | 不弹窗 |
| TC-8.1 | 卸载 | 完整卸载 | 所有资源消失 |
| TC-8.2 | 卸载 | 卸载后重建 | 干净重建 |
| TC-8.3 | 卸载 | 不影响无关对象 | 无关规则不变 |
| TC-9.1 | 异常 | 非 Windows 平台 | 拒绝 |
| TC-9.2 | 异常 | marker 不存在 | 拒绝 |
| TC-9.3 | 异常 | 拒绝 UAC | 拒绝 |
| TC-9.4 | 异常 | GPO 覆盖防火墙 | 拒绝 |
| TC-9.5 | 异常 | 并发 setup | 互斥 |
| TC-10.1 | 配置 | account-prefix | 自定义账户名 |
| TC-10.2 | 配置 | network-policy=offline | 强制断网 |
| TC-10.3 | 配置 | network-policy=online | 强制联网 |
