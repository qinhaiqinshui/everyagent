# sandbox-windows-codex 设计文档

> 以 Java + JNA 复刻 OpenAI Codex 的 Windows 原生沙箱机制,作为 every-agent 的沙箱后端插件。
> 机制事实源:同目录 `codex-analysis.md`(下称"分析文档");SPI 事实源:`every-agent-plugin-api`;
> 架构约束:`docs/ARCHITECTURE.md` §7.10(沙箱插件化、Backend 只管挂载)、红线(插件零 worker 依赖、PermissionGate 兜底越界 IO)。
> 定性澄清:codex **未用 AppContainer**(全源码 0 命中 `CreateAppContainerProfile`)——capability SID 是手工合成的
> `S-1-5-21-{a}-{b}-{c}-{d}` group SID;隔离主体是**真实本地双账户**(Offline/Online)+ WRITE_RESTRICTED 受限令牌。

## 0. 定位与总体形态

三进程模型(对齐 codex,提权只发生在一次性 setup 阶段):

```
worker JVM(真实用户)                    setup helper JVM(管理员,UAC 一次)         runner JVM(沙箱账户)
┌ CodexSandboxPlugin ┐  ShellExecuteExW ┌ SetupHelperMain ┐  CreateProcessWithLogonW ┌ CodexRunnerMain ┐
│ Provider/Backend   │─"runas" 子模式──▶│ 建组/账户/ACL/  │───────────────────────▶│ 派生受限令牌     │
│ RunnerClient(broker)│◀── stdout 报告 ──│ 防火墙/WFP/marker│  named pipe + JSON 帧   │ Job/桌面/子进程  │
└ CodexCommandExecutor┘                 └─────────────────┘                          └─────────────────┘
```

- **宿主侧(broker)**:worker 进程内,负责 setup 编排、凭据读取、刷写根 ACL、spawn runner、帧协议读写。
- **runner(agent 侧)**:以沙箱账户身份运行的第二个 JVM,从自身令牌派生受限令牌、在 JobObject + 私有桌面里 spawn 真正命令,输出经命名管道帧流回。
- 命令跑在**宿主路径**(无路径重写),读写边界由账户基线 DACL + capability SID ACE 表达(分析文档 §2.2)。

## 1. 范围决策

### 1.1 对齐 codex 的机制(做)

| 机制 | 决策 | 依据 |
|---|---|---|
| 真实本地双账户 + 沙箱组 | **做**。账户 `<前缀>Offline`/`<前缀>Online`(默认前缀 `EACodex`,见 `SandboxAccounts.DEFAULT_PREFIX`),组 `<前缀>SandboxUsers`(`groupName`) | 分析文档 §4;读隔离与网络隔离的唯一可靠载体 |
| 合成 capability SID(cap.rs) | **做**。随机 `S-1-5-21-a-b-c-d`,按 readonly/workspace/per-workspace-cwd/per-writable-root 分键,持久化 `<codexHome>/cap_sid.json`,兼容裸 SID 旧格式 | §5;工作区隔离与最小写面(过期根不进令牌) |
| WRITE_RESTRICTED 受限令牌 | **做**。flags `DISABLE_MAX_PRIVILEGE|LUA_TOKEN|WRITE_RESTRICTED`(0x01|0x04|0x08);restricting 顺序 caps → 额外(令牌 user SID 首位)→ logon SID → Everyone;`set_default_dacl`(logon SID GENERIC_ALL + OWNER RIGHTS S-1-3-4 仅 READ_CONTROL)+ 恢复 SeChangeNotifyPrivilege | §2.1(token.rs) |
| 读 ACL 三段式(账户基线无权 → 组授 RX → deny-read ACE 压制) | **做**(首期静态部分) | §2.2 |
| 防火墙 COM 规则(offline 账户 4 条 block + LocalPolicyModifyState 自检 + SID 读回校验、fail-closed 顺序) | **做** | parts/03 §1 |
| WFP 12 条持久 filter(事务 + 固定自有 GUID,不抄 codex 的 GUID) | **做**(排在防火墙之后) | parts/03 §2 |
| DPAPI 机器作用域凭据(`.sandbox-secrets/sandbox_users.json`,目录 DENY 沙箱组) | **做** | §4.2 |
| 隐藏账户(Winlogon\UserList=0 + profile 目录 HIDDEN\|SYSTEM) | **做**(成本极低,不做则登录界面多两个账户) | §4.3、§5.6.3 |
| JobObject(KILL_ON_JOB_CLOSE,只做整树终止不做配额)+ PROC_THREAD_ATTRIBUTE_JOB_LIST 原子挂接 + HANDLE_LIST 句柄白名单 | **做** | parts/03 §4/§5 |
| 私有桌面(随机名 CreateDesktopW + lpDesktop) | **做**(受限令牌 spawn 不设 lpDesktop 时 PowerShell 会 STATUS_DLL_INIT_FAILED) | parts/03 §6 |
| marker 两阶段提交(`CREATE_NEW` 空 sentinel → 原子重写)+ `Global\` 跨进程互斥 + 进程内 singleflight | **做**(不变量①) | §4.5、§5.6.3 |
| 修复路径:中断残留的禁用账户,新建账户先带 UF_ACCOUNTDISABLE,网络限制就绪后才解禁 | **做**(不变量④) | §4.1 |
| 卸载两阶段(禁用→停进程→再删资源;flags 可回滚;SID 复验防同名顶替) | **做**(不变量⑤) | parts/05 §5.3 |
| "宁拒不裸":表达不了的限制一律拒绝执行,绝不降级明文 | **做**(贯穿所有校验) | §7 |

### 1.2 简化 / 暂缓(不做,附理由)

| 项 | 决策 | 理由 |
|---|---|---|
| registered runtime(MSIX/AppX 包身份、别名、removal.ps1) | 暂缓 | 依赖 MSIX 打包渠道,Java 发行无此形态;只做 Legacy(分析文档 §5.6.3) |
| windows-sandbox-service(SCM 常驻服务 + provisioning 管道 + 机器策略) | 暂缓 | 用"按需提权 helper"替代:`ShellExecuteExW("runas")` 一次 UAC + marker 缓存;refresh 走不提权路径 |
| legacy(restricted-token)后端 | **不做** | 生态位与既有 `sandbox-windows-mic` 重叠(同为宿主内受限令牌);codex legacy 本身拒绝 deny-read/受限读/托管网络(§3),单独实现只剩"降权写"价值,且 mic 的 Medium IL 契约(§7.10)已覆盖零副作用场景。本插件 = codex elevated 专精 |
| ConPTY | 暂缓 | 首期 pipe + stdio 桥;AI 命令为非交互一次性执行,worker 契约 stdin=NUL,无 TTY 需求(§7.10) |
| 环境分块传输(`--launch-payload-env`,16KiB×16MiB) | 暂缓 | payload 经管道帧(SpawnRequest.env)传递而非命令行,天然绕开 32,767 字符限制 |
| deny-read walker 对账 | 二期 | 首期 setup 时一次性刷静态 deny-read;对账解决的是用户手删 ACE 的漂移 |
| deny-read 动态列表 | 首期固定为空 | 读隔离主力是"账户基线无权读真实用户目录";沙箱数据目录靠组 DENY ACE |
| SCM/常驻 runner 池、runner 复用 | 暂缓 | 每次会话新起 runner JVM,启动开销 ~200ms 可接受 |
| managed network 代理强制(proxy_enforced、restricting SID) | 暂缓 | every-agent 网络策略只有放行/断网两档(§7.10);断网 = Offline 账户天然达成 |
| UAC helper 的环境分块、launch payload 文件 | 简化 | payload base64 后经 helper 命令行单参数传递(自身 < 4KB);超限再落临时文件 |

### 1.3 不可简化的不变量(照抄 codex)

① marker `CREATE_NEW` 空 sentinel + 两阶段;② 防火墙先宽 block 后删 allow 的 fail-closed 顺序 + LocalPolicyModifyState 自检 + SID 回读;③ WFP 事务包裹 + 固定 GUID(生成后写死在常量里,永不重生成);④ 禁用账户在 WFP/防火墙恢复成功前不解禁;⑤ 卸载两阶段与 flags 回滚;⑥ runner 管道 DACL 只授沙箱账户 + 连接方 PID 校验双因子。

## 2. 模块与类清单

Maven 模块 `every-agent-plugins/sandbox-windows-codex`,包根 `dev.everyagent.plugin.sandbox.codex`,插件 id `sandbox-windows-codex`,后端 id `codex`。依赖:jna/jna-platform 5.16、jackson-databind(帧 JSON)、plugin-api;runner 与 helper 是**同一 jar 的两个额外 main**(无 shade,无独立 exe)。

### 2.1 `win` — Win32 绑定层(JNA 惯用法:扩展平台接口再 `Native.load`,见 mic 的 Win32Ex)

| 类 | 职责 | 对应 codex 源 |
|---|---|---|
| `Kernel32Ex` | kernel32 补齐:CancelSynchronousIo、CreateDesktopW(挂 user32)等 | process.rs / runner_pipe.rs 依赖 |
| `Advapi32Ex` | advapi32 补齐:CreateRestrictedToken、SetEntriesInAclW、AllocateAndInitializeSid/FreeSid、CheckTokenMembership、ConvertSidToStringSidW、ConvertStringSecurityDescriptorToSecurityDescriptorW、RegFlushKey/RegDeleteValueW | token.rs / acl.rs / runtime_ownership.rs |
| `NetApi32Ex` | netapi32 补齐:NetLocalGroupAdd/AddMembers/Del、NetUserSetInfo(level 1 flags / 1003 密码) | sandbox_users.rs / principals.rs |
| `Fwpuclnt` | fwpuclnt 全量自映射:FwpmEngineOpen0/Close0、Transaction*、ProviderAdd/DeleteByKey0、SubLayerAdd/DeleteByKey0、FilterAdd/DeleteByKey0 + FWPM_FILTER0/FWP_VALUE0 结构体 | wfp.rs |
| `UserenvEx` | userenv 自映射:DeleteProfileW(LoadUserProfile 仅卸载路径,暂缓) | uninstall_windows/users |
| `WinStructs` | EXPLICIT_ACCESS_W/TRUSTEE_W/SID_AND_ATTRIBUTES、USER_INFO_1/1003/23、LOCALGROUP_INFO_1/0、STARTUPINFOEXW/PROC_THREAD_ATTRIBUTE_LIST、FWPM 结构体、SHELLEXECUTEINFOW 复用 jna-platform | — |
| `WinErr` | NERR_*/FWP_E_*/APPMODEL 错误码表与分类(凭据失配/可刷新/GPO 覆盖) | identity.rs / firewall.rs |

### 2.2 `sid` — SID 与 capability(cap.rs 等价)

| 类 | 职责 | 对应 |
|---|---|---|
| `SidUtil` | 字符串↔SID 互转、well-known SID(world/OWNER RIGHTS)、LookupAccount* 双段式预探、logon SID 提取(SE_GROUP_LOGON_ID 扫描) | acl.rs / token_groups.rs |
| `CapSidStore` | cap SID 生成(SecureRandom 4×u32 → S-1-5-21-…)、JSON 持久化、裸 SID 兼容、per-cwd/per-writable-root 惰性创建、路径特异度/包含/重叠计算 | cap.rs |

### 2.3 `account` — 账户与凭据

| 类 | 职责 | 对应 |
|---|---|---|
| `AccountProvisioner` | 建组(NetLocalGroupAdd)、建户(NetUserAdd USER_INFO_1,UF_SCRIPT\|UF_DONT_EXPIRE_PASSWD;已存在则 NetUserSetInfo(1003) 只重置密码)、入组(组 + 内建 Users S-1-5-32-545,幂等)、24 字符随机密码、修复模式先禁用后解禁 | sandbox_users.rs |
| `DpapiSecrets` | CryptProtectData(CRYPTPROTECT_UI_FORBIDDEN\|CRYPTPROTECT_LOCAL_MACHINE)/Unprotect + base64,读写 `.sandbox-secrets/sandbox_users.json` | dpapi.rs / identity.rs |
| `SandboxIdentity` | readiness 检查(marker 版本/users 文件/账户禁用/密码过期)、`LogonUserW` 探测、凭据失配分类(SandboxAccountCredentialMismatch) | identity.rs |
| `HideUsers` | 注册表 UserList=0 写/删;profile 目录 HIDDEN\|SYSTEM(后者在 runner 侧首次调用) | hide_users.rs |

### 2.4 `setup` — setup 编排与 helper

| 类 | 职责 | 对应 |
|---|---|---|
| `SetupOrchestrator` | worker 侧:组装 payload(base64)、singleflight(`ConcurrentHashMap<String,CompletableFuture>`)、`isElevated` 探测、UAC `ShellExecuteExW`(SEE_MASK_NOASYNC,ERROR_CANCELLED=1223 单列"用户拒绝")、读 helper stdout 报告 | setup.rs |
| `SetupPayload`/`SetupMode` | 模式枚举 ProvisionOnly/Full/ReadAclsOnly/Uninstall + read/write roots、proxy 设置、SETUP_VERSION | setup.rs |
| `SetupHelperMain` | 提权子模式 main(`--setup-payload <b64>`):`Global\EveryAgentCodexSetup` 互斥 → 四模式分派 → marker 两阶段;SYSTEM 语义下跳过 UAC | setup_provisioning.rs |
| `SetupMarker` | `.sandbox/setup_marker.json` + `CREATE_NEW` 空 sentinel;版本闸门 | setup.rs |
| `ReadAclInstaller` | read roots 展开(平台默认:C:\Windows、两个 Program Files、ProgramData;+java.home+插件目录)、内建主体已持 RX 则跳过、组 allow(RX, OI\|CI) | setup.rs gather_read_roots / apply_read_acls |
| `WriteRootRefresher` | spawn 前 preflight:对每个活跃写根 ensure allow-write ACE(沙箱组 + workspace cap SID),以真实用户身份(文件 owner 持 WRITE_DAC,无需提权);失败即拒绝执行 | lib.rs run_windows_sandbox_legacy_preflight 语义 |
| `SandboxDirLocker` | `.sandbox`(组 GRANT RWX)/`.sandbox-secrets`(组 DENY)/`.sandbox-bin`(组 R+X,Protected DACL)三目录 ACL 矩阵 | lock_persistent_sandbox_dirs |
| `Uninstaller` | 两阶段:prepare(锁→禁用账户→按账户 SID 枚举杀进程,排除 retained)→ finish(删三目录→WFP→防火墙 5 规则名→unhide→DeleteProfileW+NetUserDel→NetLocalGroupDel);每步独立容错、SID 复验 | uninstall_windows.rs |

### 2.5 `net` — 防火墙与 WFP

| 类 | 职责 | 对应 |
|---|---|---|
| `FirewallInstaller` | CoInitializeEx(APARTMENTTHREADED,容忍 RPC_E_CHANGED_MODE)→ INetFwPolicy2;4 条 block 规则(出/入非环回、环回 TCP 代理端口补集、环回 UDP 全禁);`LocalUserAuthorizedList` SDDL `O:LSD:(A;;CC;;;{offline_sid})`;LocalPolicyModifyState 前置自检 + 写后读回含 SID 校验;fail-closed 安装顺序 | firewall.rs |
| `WfpInstaller` | 固定自有 provider/sublayer GUID(PERSISTENT)+ 12 filter(ICMP/DNS53/DoT853/SMB445/139 × v4/v6,ALE_AUTH_CONNECT + RESOURCE_ASSIGNMENT,FWP_ACTION_BLOCK,条件=ALE_USER_ID SD blob + 协议/端口);事务 Begin/Add/Commit/Abort | wfp.rs / filter_specs.rs |
| `LoopbackPorts` | 代理端口排序去重、1..65535 补集区间串、环回/非环回地址常量串 | firewall.rs |

### 2.6 `token`/`proc` — 令牌与进程

| 类 | 职责 | 对应 |
|---|---|---|
| `TokenFactory` | OpenProcessToken(全 access 集)→ CreateRestrictedToken(0x01|0x04|0x08,restricting 顺序 caps→user SID→logon→everyone)→ set_default_dacl → 恢复 SeChangeNotifyPrivilege;半成品句柄绝不外泄 | token.rs |
| `JobObject` | CreateJobObjectW + KILL_ON_JOB_CLOSE|BREAKAWAY_OK(无配额);terminate;本插件不做 preserve_descendants(一次性 exec 语义,树随会话收束,记录与 codex 的差异) | utils/pty win/job.rs |
| `ProcThreadAttr` | InitializeProcThreadAttributeList/UpdateProcThreadAttribute:JOB_LIST(0x2000D,失败即拒绝 spawn)、HANDLE_LIST(0x2002,只继承 stdio 管道) | proc_thread_attr.rs |
| `PrivateDesktop` | CreateDesktopW(随机名 `EveryAgentCodexDesktop-<hex>`)+ 同 logon SID 授 DESKTOP_ALL_ACCESS;每会话新建不复用(会话数少,复用键策略暂缓) | desktop.rs |
| `ChildProcess` | CreateProcessAsUserW(受限令牌 + lpDesktop + CREATE_NO_WINDOW\|CREATE_UNICODE_ENVIRONMENT)、3 匿名管道、句柄白名单、8KiB ReadFile 循环 | process.rs |
| `EnvBlock` | env 规范化(/dev/null→NUL、PAGER=more.com、PATH/PATHEXT 继承、GIT_CONFIG safe.directory 注入)、key 大小写不敏感排序的 UTF-16 环境块 | env.rs / process.rs |

### 2.7 `broker`/`runner` — 双进程 IPC

| 类 | 职责 | 对应 |
|---|---|---|
| `FrameCodec`(+`IpcJson`) | 4 字节 LE 长度前缀 + JSON;MAX_FRAME_LEN=8MiB;`readFrame`(ReadFile 分批,EOF→null)/`writeFrame`(WriteFile 分块+部分写推进);JSON tree 映射在 `IpcJson`(字段名与 codex 逐一对齐);broker 侧超时等待另加 `PeekNamedPipe` 5ms 轮询 | framed_io.rs / ipc_framed.rs |
| `IpcMessage` | 消息集(§4.2)sealed interface + record;version=6;配 `EnvBlock`/`Win32Exception`/`RunnerPaths` 支撑 | ipc_framed.rs |
| `RunnerPipe` | 管道对创建:`\\.\pipe\everyagent-codex-runner-<128bit nonce>-in/-out`,SDDL `D:(A;;GA;;;{sandbox_sid})`,1 实例、65536 缓冲、BYTE 模式;方向 0x2/0x1;连接 + `GetNamedPipeClientProcessId` PID 校验 | runner_pipe.rs |
| `RunnerClient` | spawn runner + 握手 + 凭据重试 + TerminateProcess 收尸;封装为会话(stdin/terminate/exit 队列) | runner_client.rs |
| `RunnerMaterializer` | 把插件 jar 复制到 `.sandbox-bin/runner.jar` 并授组 R+X(防插件目录 ACL 变更后 runner 起不来);解析 java.exe 绝对路径 | helper_materialization.rs |
| `CodexRunnerMain` | runner 侧 main:读 SpawnRequest → TokenFactory 派生令牌 → PrivateDesktop → JobObject → ChildProcess → 输出帧循环 → Exit 帧 → System.exit(code) | bin/command_runner/win.rs |

### 2.8 `api` — SPI 适配层

| 类 | 职责 |
|---|---|
| `CodexSandboxPlugin` | 入口:读 WorkerConfig/pluginDir,注册 Provider + ToolProvider(对照 WslUbuntuSandboxPlugin);Windows 平台 + marker 未就绪时同步执行 setup(幂等,会弹 UAC;失败则插件激活失败) |
| `CodexSandboxProvider` | id=`codex`;isAvailable=Windows ∧ marker+凭据就绪(setup 在 activate() 中同步完成,幂等;isAvailable 只读探测,不触发 setup/UAC);priority:就绪 8(明显低于 wsl-ubuntu 的 10,auto 不抢既有默认后端;高于 windows-mic 兜底 5——setup 完成即显式选用)、未 setup 0 |
| `CodexSandboxBackend` | mount=恒等映射 + 把根登记进 `CodexSandboxManager`(供 CommandExecutor 组 RootPolicy);onWorkspaceRemoved=no-op(ACL 持久、由 state 文件对账,不在此清理;过期根 preflight 幂等跳过) |
| `RootPolicy` | 读根/写根/deny-write 计算:RW 根=工作区根+externalRoots+skills;deny-write=codexHome 与 USERPROFILE 敏感子集 |
| `CodexCommandExecutor` | 执行入口:readiness→preflight 刷 ACL→组 SpawnRequest→RunnerClient 会话→聚合 stdout/stderr(每流上限)→格式化(对照 WslUbuntuCommandExecutor) |
| `CodexBashToolProvider` | ToolProvider:appliesTo=`"codex".equals(ctx.sandbox().id())`;提供 `bash` 工具(描述同 wsl 形态:rg 优先、cwd=工作区根、stdin=NUL) |

## 3. JNA 需补齐的 Win32 原语(已对照 jna-platform 5.16 实测)

**复用 jna-platform(不重映射)**:Kernel32 的命名管道套件(CreateNamedPipeW/ConnectNamedPipe/PeekNamedPipe/GetNamedPipeClientProcessId/WaitNamedPipe/DisconnectNamedPipe)、CreateProcessW、SetHandleInformation、DuplicateHandle、GetExitCodeProcess、SetErrorMode、QueryFullProcessImageNameW、CreateMutexW、CreateFile、LocalFree;Advapi32 的 CreateProcessWithLogonW、LogonUser、OpenProcessToken、GetTokenInformation、AdjustTokenPrivileges、LookupPrivilegeValue、LookupAccountName/Sid、ConvertStringSidToSid、Get/SetNamedSecurityInfo、SetSecurityInfo、RegCreateKeyExW/RegSetValueExW;Netapi32 的 NetUserAdd/Del/GetInfo、NetApiBufferFree;Crypt32Util 的 cryptProtect/UnprotectData(确认 flags 重载可传 LOCAL_MACHINE,否则自映射);Ole32 的 CoInitializeEx/CoCreateInstance/CoUninitialize;Shell32 的 ShellExecuteEx(W);jna-platform 已有的 Job 结构、WinBase/WinNT 类型与 mic Win32Ex 中已踩过的坑(Structure 载荷自动同步、char[] 自带 NUL、UNICODE_OPTIONS)全部沿用。

**需自映射(按 DLL)**:

- **kernel32/user32**:`CancelSynchronousIo(HANDLE)`;`CreateDesktopW/OpenDesktopW/CloseDesktopW`(user32);`GetNamedPipeClientProcessId` 若签名不符再补。
- **advapi32**:`CreateRestrictedToken(base,flags,0,null,0,null,n,SID_AND_ATTRIBUTES[],HANDLE*)`——restricting 数组用 `Memory` 平铺(SID ≤68 字节,Sid 为裸 Pointer 防 JNA 改写目标内存,同 mic 的 TOKEN_MANDATORY_LABEL 教训);`SetEntriesInAclW(EXPLICIT_ACCESS_W[],ACL*,ACL**)`;`AllocateAndInitializeSid/FreeSid`;`CheckTokenMembership`;`ConvertSidToStringSidW`;`ConvertStringSecurityDescriptorToSecurityDescriptorW`(SDDL_REVISION_1);`BuildExplicitAccessWithNameW + BuildSecurityDescriptorW`(WFP 用户条件 SD blob);`RegFlushKey/RegDeleteValueW`(SHDeleteEmptyKeyW 在 shlwapi)。
- **netapi32**:`NetLocalGroupAdd(LOCALGROUP_INFO_1)`、`NetLocalGroupAddMembers`(PSID 数组 level 3)、`NetLocalGroupDel`、`NetUserSetInfo`(level 1 flags / level 1003 密码,`*mut u16` 指针字段须钉在 `Memory` 上)。NERR_* 不走 GetLastError。
- **fwpuclnt**:全量(§2.1);FWPM_FILTER0 的 union/tagged-union 用 `Structure` + `setAutoSynch(false)` 手动读写,GUID 16 字节小序布局。
- **userenv**:`DeleteProfileW`。

**COM(IDispatch)难点决策**:INetFwPolicy2/INetFwRule3 是双接口,JNA 无内建 COM。方案:**手工 vtable 调用**——`CoCreateInstance` 取 `IUnknown*` 后按 `Pointer.vtable` 逐槽取函数指针调用(IDispatch 前置槽固定:invoke/dispatch 略,直接走 vtable 槽位);BSTR 用 `SysAllocStringLen`/`SysFreeString`(oleaut32);`LocalPolicyModifyState` 是 INetFwPolicy2 vtable 尾部方法,槽位以 SDK 头文件为准并在单测里断言 HRESULT。回退顺序:手工 vtable 失败 → 明确报错拒绝(fail-closed),**不做 netsh 降级**(无法表达 LocalUserAuthorizedList 与自检,安全语义降级,与分析文档 §5.6.2 结论一致)。

## 4. agent/broker 双进程方案

### 4.1 runner 如何以沙箱账户启动(决策)

**决策:用当前 JVM 的 `java.exe` 启动,classpath 指向物化的 runner.jar;不打包独立 exe/jlink 运行时。**

- 命令:`<java.home>\bin\java.exe -cp <codexHome>\.sandbox-bin\runner.jar dev.everyagent.plugin.sandbox.codex.runner.CodexRunnerMain --pipe-in=... --pipe-out=...`(逐参数 `quote_windows_arg`)。
- 理由:① java.exe 通常在 Program Files(平台默认读根,Users 组天然 RX),沙箱账户可执行;jlink/GraalVM 原生镜像体积与构建链成本高;② jar 由 `RunnerMaterializer` 复制到 `.sandbox-bin`(组 R+X,Protected DACL),与 codex 物化 helper 的 ACL 矩阵一致,防插件目录(可能在用户私有目录)ACL 不放行;③ 若 java.home 不在默认读根,ReadAclInstaller 将其加入读根。
- `CreateProcessWithLogonW`:账户=沙箱用户名/明文密码(DPAPI 解出的 bytes 直接 `new String(…,UTF_16LE)`,不经默认 charset)、域 `"."`、旗标 `CREATE_NO_WINDOW|CREATE_UNICODE_ENVIRONMENT`、`STARTF_FORCEOFFFEEDBACK`、不传 `LOGON_WITH_PROFILE`(无 execution alias,免建 profile;runner 的 TEMP/TMP 显式指到 `<codexHome>\.sandbox\tmp`);spawn 前后 `SetErrorMode(0x3)`。**不用匿名管道跨 logon session 传 stdio——这正是保留命名管道 IPC 的原因**(每次 WithLogonW 产生独立 logon session)。
- runner JVM 参数:`-XX:+UseSerialGC -Xshare:auto -Dfile.encoding=UTF-8`,最小化启动开销。

### 4.2 帧协议(对齐 IPC_PROTOCOL_VERSION=6)

4 字节 LE 长度前缀 + JSON,单帧 ≤8MiB,二进制一律 base64(STANDARD)。`FramedMessage{version:6, type:…}` 平铺。9 个消息与 wire tag 全对齐:

| 方向 | tag | 载荷(本插件取用子集) |
|---|---|---|
| 父→runner | `spawn_request` | command、cwd、env、writeRoots、denyWritePaths、capSids、timeoutMs、tty=false、stdinOpen=false、desktopName、networkIdentity |
| 父→runner | `stdin`/`close_stdin`/`resize`/`terminate` | data_b64 / 空 / rows,cols(实现但首期不用) / 空 |
| runner→父 | `spawn_ready` | process_id |
| runner→父 | `output` | data_b64、stream=stdout\|stderr |
| runner→父 | `exit` | exit_code、timed_out(超时=128+64=192) |
| runner→父 | `error` | message、stage=read_spawn_request\|spawn_child\|write_spawn_ready、windows_error_code |

SpawnRequest 不搬 codex 的 permission_profile 结构,改为自有 `writeRoots/denyWritePaths/networkIdentity`(两端都是本插件,保持 wire tag 与版本号即可与 codex 语义对齐)。

### 4.3 握手、PID 校验、超时、重试

1. 父进程 `CreateNamedPipeW` 建管道对(§2.7 RunnerPipe,DACL 只授沙箱账户 GENERIC_ALL)。
2. `CreateProcessWithLogonW` 起 runner;失败码 1056(服务已在运行)原凭据重试一次;1326/1331/1387 等凭据类 → 抛 `SandboxAccountCredentialMismatch`,由 SetupOrchestrator 引导交互式修复(UAC),不做首期自动密码轮换。
3. 连接超时:阻塞 `ConnectNamedPipe` 无超时参数 → 辅助虚拟线程先 `DuplicateHandle` 发布自身句柄再阻塞连接,父侧 `recv 15s`,超时 `CancelSynchronousIo(thread)` 中断;ERROR_NOT_FOUND 视为恰好完成再收割;取消后不 join,靠关管道句柄解阻塞。
4. `GetNamedPipeClientProcessId == WithLogonW 返回 PID`,否则 PermissionDenied(与 DACL 构成双因子)。
5. 发 SpawnRequest → `PeekNamedPipe` 5ms 轮询等完整帧,15s 内收 `spawn_ready`;收到 `error`(stage+winerr)或管道提前关闭即失败;任一步失败 `TerminateProcess(pi.hProcess,1)` 收尸。
6. 会话中:父侧写线程独占 `-in` 管;读线程解析 output/exit;看门狗超时或取消 → 写 `terminate` 帧 → runner `TerminateJobObject` 失败回退 `TerminateProcess`;`Exit` 帧到达后会话终结,管道句柄关闭即 runner 退出信号。

## 5. SPI 对接

- **Provider**:`create(SandboxConfig)` 把 `config.props()`(WorkerConfig)与 `persistentRoot` 传入;`isAvailable` 仅 Windows 且 marker 就绪。
- **Backend.mount**:codex 模式命令跑在宿主路径上 → 返回**恒等映射**(同 mic);同时把每个 `MountRequest(hostPath, access)` 登记进 RootPolicy:READ_WRITE→写根(cap SID + preflight 刷 ACE),READ_ONLY→读根补充。映射表由核心 SandboxPathRegistry 使用,本插件不翻译路径。
- **命令执行入口**:对齐 wsl-ubuntu 形态——插件自己的 `CodexCommandExecutor` + `CodexBashToolProvider`(`appliesTo` 判 `ctx.sandbox().id()=="codex"`),提供 `bash` 工具;执行链 = readiness → preflight 刷写根 ACE → SpawnRequest → RunnerClient 会话 → `ExecResult`(stdout/stderr/exitCode/aborted)→ wsl 同款格式化尾注。core 的 DIRECT 工具与本工具经 appliesTo 互斥共存。
- **与 PermissionGate 的关系(分工)**:gate 仍是**工作区外路径访问的前置授权**(fs.* 工具、命令引用越界路径先弹 ask);授权通过的 externalRoots 进入 mount(RW)→ RootPolicy → preflight 落 allow-write ACE。沙箱内 IO 由账户 DACL 二次兜底:即便 gate 被绕过,沙箱账户对未授权路径默认拒绝写(这是 mic 后端没有的 OS 级兜底);工作区内命令不弹授权。沙箱自身不改工作区文件 ACL 之外的任何系统标注。
- **网络**:全局 `allow-network=false`(经 `SandboxConfig.networkDenied`)或 `codex.network-policy=offline` → Offline 账户(防火墙+WFP 天然断网);放行 → Online 账户。选择在会话组 SpawnRequest 时确定,凭据按账户取。任务级 `/禁用网络` 胶囊不在本后端提供(归 wsl-ubuntu 插件,见架构 §7.10)。

## 6. 与 sandbox-windows-mic 的差异

| 维度 | sandbox-windows-codex | sandbox-windows-mic |
|---|---|---|
| 隔离模型 | 专用本地账户 + 机器级 DACL + cap SID + WRITE_RESTRICTED 令牌 | Restricted Token(去特权)+ Medium IL,宿主身份运行 |
| 读隔离 | 默认拒读(账户基线),deny-read ACE 可加 | 无(Medium IL 可读用户文件) |
| 写隔离 | OS 级:allow-write ACE 交集才可写,gate 之外的第二道闸 | 无 OS 兜底,全靠 PermissionGate 责任链 |
| 网络 | 账户级防火墙 + WFP(Offline 断网) | 无(Job 管不了网络),剥代理 env |
| 提权 | 首次 setup 一次 UAC(建账户/ACL/规则) | 无 |
| 文件系统副作用 | 持久(账户/ACL/防火墙规则/cap_sid),提供两阶段卸载 | 零副作用零残留 |
| 进程管理 | JobObject 原子挂接 + 句柄白名单 + 私有桌面 | JobObject(CREATE_SUSPENDED+Assign)+ 配额 |
| 执行形态 | 三进程:worker→runner JVM(沙箱账户)→命令 | 单进程直接 spawn |
| 依赖 | JNA + COM vtable + fwpuclnt | 仅 JNA |
| 使用场景 | 高隔离/不可信代码/需断网;setup 成本一次 | 日常低摩擦,auto 兜底(priority 5) |
| priority | 就绪 8 / 未 setup 0 | 5 |

## 7. 配置项设计(`sandbox.type=codex` 时)

经 `plugin.json contributes.config` 自管(同 wsl 插件惯例;`WorkerConfig.Sandbox.type` 只认 `codex`):

| 键 | 默认 | 说明 |
|---|---|---|
| `codex.home` | `<sandboxPersistentRoot>/codex` | codexHome(`.sandbox`/`.sandbox-secrets`/`.sandbox-bin`/cap_sid.json 之父) |
| `codex.account-prefix` | `EACodex` | 账户 `<前缀>Offline`/`<前缀>Online`、组 `<前缀>SandboxUsers`（前缀受 SAM 账户名 20 字符上限约束，见 `SandboxAccounts.MAX_USERNAME_LEN`） |
| `codex.network-policy` | `auto` | auto(随 allow-network)/`offline`/`online` 强制 |
| `codex.proxy-ports` | 空 | offline 放行的环回 TCP 端口列表(代理) |
| `codex.allow-local-binding` | false | true=移除环回 block 规则 |
| `codex.setup.auto-uac` | ~~true~~ 已移除 | setup 已移入 activate() 同步执行,此键不再需要 |
| `codex.extra-read-roots` / `codex.extra-deny-write-paths` | 空 | 追加读根 / deny-write 路径 |
| `codex.java-home` | 当前 JVM | runner 启动用 java.exe 路径 |
| `codex.setup-timeout-ms` / `codex.exec-timeout-ms` | 120000 / 沙箱默认 | setup 与单命令看门狗 |

## 8. 测试策略

- **跨平台纯逻辑(不 assumeTrue)**:FrameCodec 编解码(长度前缀/8MiB 上限/残帧/EOF);消息集 JSON 往返(base64、version=6);CapSidStore 生成格式/持久化/裸 SID 兼容/按 cwd 与写根取键/特异度排序;RootPolicy 的 allow/deny 计算(包含/重叠/敏感路径剥离);LoopbackPorts 补集区间与环回/非环回地址常量;EnvBlock 大小写不敏感排序;`quote_windows_arg`;错误码分类表(1326/1056/1223/NERR_*);防火墙规则名/SDDL/描述串生成;SpawnRequest→ACL 计划的纯函数投影。
- **Windows-only(`assumeTrue(os.name→win)`)**:JNA 接口加载冒烟;临时目录上的 SetEntriesInAclW/GetNamedSecurityInfo 读写断言;DPAPI 加解密环回;TokenFactory 在自身令牌上的 CreateRestrictedToken(校验 restricting SIDs);Fwpuclnt 结构体内存布局(GUID/union 偏移断言)。
- **需管理员的端到端(标记 `@Tag("windows-admin"`,CI 手动)**:setup 幂等(二次 marker 短路、refresh 无 UAC);写越界/读越界/junction 与 symlink 不穿越;Offline 断网(环回 proxy fixture);卸载后账户/组/规则/目录/注册表值全消失且无关对象不受影响——对齐 `sandbox_smoketests.py` 断言面。

## 9. 落地顺序(对齐分析文档 §5.6.4)

每步都以 windows-admin 冒烟断言做回归,任何一步失败保持 fail-closed:

1. **win 绑定层 + CapSidStore + FrameCodec**:全部可跨平台单测先行(TDD,结构体布局断言);
2. **AccountProvisioner + SetupOrchestrator + SetupHelperMain**:跑通 UAC→建组/建户→DPAPI 凭据→marker 两阶段;
3. **AclComposer(ReadAclInstaller/WriteRootRefresher/SandboxDirLocker)**:setup Full 的 ACL 部分;
4. **FirewallInstaller**(COM vtable)→ **WfpInstaller**(放最后,防火墙已挡大部分流量,WFP 是 GPO 冲突时的第二道防线);
5. **TokenFactory + ChildProcess + 私有桌面**(runner 内,宿主直接手测受限令牌 spawn);
6. **RunnerClient/CodexRunnerMain 帧链路**:握手→spawn→output→exit 全通;
7. **SPI 适配层(Provider/Backend/CommandExecutor/ToolProvider)接入 worker**;
8. **Uninstaller 两阶段** + 修复路径(禁用账户检测)。

## 10. 风险与开放问题

**风险**:① COM vtable 手工调用槽位易错(fwpol 接口方法序)——靠 windows-admin 单测 + LocalPolicyModifyState 自检双保险;② WFP 结构体 union 映射笔误 → 事务 Abort 兜底,任何失败拒绝执行;③ 企业策略禁 NetUserAdd/域控环境 setup 直接失败(fail-closed,报可读错误);④ 杀软/EDR 可能拦截 CreateProcessWithLogonW 与提权 helper;⑤ DPAPI 机器作用域 = 本机任意账户可解密(与 codex 同边界,只防拷走离线读);⑥ GPO 覆盖防火墙 → LocalPolicyModifyState 检出即拒绝,不静默降级;⑦ runner JVM 启动开销与内存(每会话一个 JVM,~50MB)。
**开放问题**:① externalRoot 指向非 owner 目录时 preflight 刷 ACE 可能无 WRITE_DAC——首期拒绝并提示管理员 setup 授权,是否提供"提权刷 ACL"路径待定;② COM 若长期不稳,是否引入 Java 22+ FFM 小段绑定(需评估 JDK 25 模块封禁与 --enable-native-access 分发);③ 常驻 runner/runner 池与多工作区并发会话的桌面复用策略;④ deny-read walker 对账与动态 deny-read 列表(二期);⑤ 多 worker 实例同机共存时 setup 互斥粒度(目前全局互斥已够);⑥ Windows Home 无 secpol.msc 但 NetUserAdd 可用,需实测各 SKU。
