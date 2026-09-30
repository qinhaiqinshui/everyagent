# 第 3 分片：网络隔离、Job Object 进程管理、exec 执行链

源码基准：openai/codex main@92bc601a，核心 crate `codex-rs/windows-sandbox-rs/`（下文省略此前缀），Job Object 在 `codex-rs/utils/pty/src/win/job.rs`。

总览：Windows 沙箱的网络隔离是**双机制叠加**——防火墙 COM 规则（按账户 SID 拦非环回 + 环回流量，代理端口补集放行）+ WFP 过滤器（事务化硬封 ICMP/DNS/SMB 端口）；legacy（restricted-token）后端另有纯环境变量/denybin 桩兜底。进程管理围绕 Job Object（整树终止而非配额）、`PROC_THREAD_ATTRIBUTE_*` 属性列表（原子挂 job、句柄继承白名单、ConPTY）与私有桌面展开。隔离粒度是**沙箱账户**（offline/online 本地用户的 SID），而非令牌位——防火墙 `LocalUserAuthorizedList` 与 WFP `ALE_USER_ID` 都按账户 SID 写死即为此故。

## 1. 网络隔离（一）：防火墙 COM 规则 — `src/setup_provisioning/firewall.rs`

由提权 setup helper 在 provisioning 事务中调用：`src/setup_provisioning.rs` 先 `resolve_sid(payload.offline_username)` 解析离线沙箱账户 SID，再走 `configure_offline_sandbox_network`（先 `firewall::ensure_offline_proxy_allowlist`，后 `firewall::ensure_offline_network_blocks`），随后 `install_wfp_filters`（764 行）装 WFP；修复禁用账户的恢复路径要求 WFP 成功后才重新启用账户（防止清理后保护缺失时重开登录）。

### 1.1 规则名清单（常量，幂等复用的稳定内部标识）

| 内部名（`INetFwRules` 集合 key） | 方向 / 协议 | RemoteAddresses | RemotePorts |
|---|---|---|---|
| `codex_sandbox_offline_block_outbound` | 出站 / ANY | 非环回段 | `*` |
| `codex_sandbox_offline_block_inbound` | 入站 / ANY | 非环回段 | `*` |
| `codex_sandbox_offline_block_loopback_tcp` | 出站 / TCP | 环回段 | 代理端口补集 |
| `codex_sandbox_offline_block_loopback_udp` | 出站 / UDP | 环回段 | `*` |
| `codex_sandbox_offline_allow_loopback_proxy` | 遗留 allow 规则 | — | 仅被移除，不再创建 |

地址常量：
- `LOOPBACK_REMOTE_ADDRESSES = "127.0.0.0/8,::/127"`
- `NON_LOOPBACK_REMOTE_ADDRESSES = "0.0.0.0-126.255.255.255,128.0.0.0-255.255.255.255,::,::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"` —— 用区间枚举表达「除 127.0.0.0/8 与 ::1 外的一切地址」：v4 掐掉 127.x.x.x，v6 以 `::`（任意）加 `::2-ffff:…` 掐掉 ::1。
- 单元测试 `configured_remote_address_literals_are_accepted_by_firewall_com` 用真实 `NetFwRule` COM 对象验证这些字面量被防火墙接受。

### 1.2 LocalUserAuthorizedList SDDL

两个入口均构造 `local_user_spec = format!("O:LSD:(A;;CC;;;{offline_sid}")`，经 `configure_rule` → `rule.SetLocalUserAuthorizedList()` 写入。规则作用域因此**只覆盖离线沙箱账户**（按 SID），宿主用户与其他账户流量不受影响。其余统一属性（`configure_rule`）：`SetAction(NET_FW_ACTION_BLOCK)`、`SetEnabled(VARIANT_TRUE)`、`SetProfiles(NET_FW_PROFILE2_ALL)`、`SetDescription(友好名)`、`SetDirection`；`configure_rule_network_scope` 写 `SetProtocol`/`SetRemoteAddresses`/`SetRemotePorts`。

### 1.3 环回 TCP 端口补集放行

`blocked_loopback_tcp_remote_ports(proxy_ports)`：代理端口过滤掉 0、排序去重后，扫描 1..65535，对每个放行端口前的空档产出区间（`port_range_string`：单值输出 `n`，否则 `start-end`），最终 join 为 RemotePorts——语义是「环回 TCP 只放行代理监听端口，其余全部 block」。补集为空（`None`）时不缩窄 TCP 规则。

### 1.4 fail-closed 安装顺序与 LocalPolicyModifyState

`ensure_offline_proxy_allowlist(offline_sid, proxy_ports, allow_local_binding, log)` 的顺序刻意保证不 fail open：
1. `allow_local_binding=true`（本地绑定放行模式）：`remove_rule_if_present` 移除遗留 allow 规则与环回 UDP/TCP block 规则后返回，避免陈旧代理例外残留。
2. 否则先装 `block_loopback_udp`（全端口）→ 再装**未缩窄**的 `block_loopback_tcp`（RemotePorts=`*`，全环回 TCP 先全 block）→ 然后才 `remove_rule_if_present(OFFLINE_PROXY_ALLOW_RULE_NAME)`（注释：显式 block 就位后才移除遗留重叠 allow，切回代理模式不 fail open）→ 最后用代理端口补集缩窄 TCP 规则；缩窄更新失败则停留在全 block（fail-closed）。

每步入口先 `FirewallComApartment::initialize()`：`CoInitializeEx(COINIT_APARTMENTTHREADED)`，容忍 `RPC_E_CHANGED_MODE`（服务线程已是 MTA 时继续用现有 apartment，测试 `firewall_apartment_tests.rs` 专门锁住这一行为），成功时 drop 对称 `CoUninitialize`；然后 `CoCreateInstance(NetFwPolicy2)` 取 `INetFwPolicy2::Rules`。

`ensure_local_policy_rules_take_effect` → `validate_local_policy_modify_result` 在写任何规则**之前**执行：直调 vtable 的 `LocalPolicyModifyState`——
- HRESULT 出错 → `SetupErrorCode::HelperFirewallPolicyAccessFailed`；
- 返回 `S_FALSE`（答案只对部分活跃 profile 成立）→ `HelperFirewallPolicyIneffective`；
- 状态非 `NET_FW_MODIFY_STATE_OK`（如 `NET_FW_MODIFY_STATE_GP_OVERRIDE` 组策略覆盖）→ `HelperFirewallPolicyIneffective`。

即：只要本地规则不保证对所有 profile 生效，直接失败，不静默降级（对应测试 `local_policy_modify_state_rejects_*`）。

### 1.5 幂等与读回校验

`ensure_block_rule`：`rules.Item(name)` 命中则 `cast::<INetFwRule3>()` 复用；未命中则 `CoCreateInstance(NetFwRule)` + `SetName`，**先把全部属性配置完再 `Rules::Add`**（不留半配置规则入库），随后无论新旧都重放一遍属性保持幂等。读回校验：`SetLocalUserAuthorizedList` 之后调 `rule.LocalUserAuthorizedList()`，若返回串不含 `offline_sid` → `SetupErrorCode::HelperFirewallRuleVerifyFailed`（防 COM 层静默丢 SID，配错用户的规则等于没有隔离）。每步失败都映射到 `SetupFailure`（`HelperFirewallComInitFailed` / `HelperFirewallPolicyAccessFailed` / `HelperFirewallRuleCreateOrAddFailed` / `HelperFirewallRuleVerifyFailed`）。卸载侧 `src/uninstall_windows/firewall.rs` 仅按上述已知内部名移除，保留无关规则。

## 2. 网络隔离（二）：WFP — `src/wfp.rs` + `src/wfp/filter_specs.rs`

### 2.1 稳定 GUID 命名空间（Codex 拥有身份，源码注释明示不可再生成，否则会孤儿化旧对象）

- Provider：`PROVIDER_KEY = 0x2e31d31c-3948-4753-9117-e5d1a6496f41`，`FWPM_PROVIDER_FLAG_PERSISTENT`，`ensure_provider` 用 `FwpmProviderAdd0` 添加、容忍 `FWP_E_ALREADY_EXISTS`。
- Sublayer：`SUBLAYER_KEY = 0xe65054fd-4d32-4c7c-95ef-621f0cf6431a`，`FWPM_SUBLAYER_FLAG_PERSISTENT`，weight `0x8000`，挂在该 provider 下（`ensure_sublayer`）。

### 2.2 层与条件

- 层（`filter_specs.rs`）：`FWPM_LAYER_ALE_AUTH_CONNECT_V4/V6`（连接授权）与 `FWPM_LAYER_ALE_RESOURCE_ASSIGNMENT_V4/V6`（资源分配，堵 raw socket/绑定旁路；仅 ICMP 的 2 条使用）。`FWPM_LAYER_NAME_RESOLUTION_CACHE` 层**有意省略**——源码注释：普通静态过滤器形状在校验时返回 `FWP_E_OUT_OF_BOUNDS`。
- 条件（`wfp.rs::build_conditions`，全部 `FWP_MATCH_EQUAL`，多条 AND）：
  - `FWPM_CONDITION_ALE_USER_ID`（`FWP_SECURITY_DESCRIPTOR_TYPE` 字节块）：`UserMatchCondition::for_account` 用 `BuildExplicitAccessWithNameW(账户名, FWP_ACTRL_MATCH_FILTER, GRANT_ACCESS)` + `BuildSecurityDescriptorW` 构造**按账户 SID** 匹配的 SD blob，drop 时 `LocalFree`；
  - `FWPM_CONDITION_IP_PROTOCOL`（`FWP_UINT8`）：`IPPROTO_ICMP` / `IPPROTO_ICMPV6`；
  - `FWPM_CONDITION_IP_REMOTE_PORT`（`FWP_UINT16`）：53 / 853 / 445 / 139。
- 动作 `FWP_ACTION_BLOCK`；每条过滤器 `FWPM_FILTER_FLAG_PERSISTENT`（重启存活）。

12 条过滤器（`FILTER_SPECS`，key/name 均有唯一性测试）：

| name | key（前 8 位） | layer | 附加条件 |
|---|---|---|---|
| codex_wfp_icmp_connect_v4 | 9f5f3812 | AUTH_CONNECT_V4 | Protocol=ICMP |
| codex_wfp_icmp_connect_v6 | 87498484 | AUTH_CONNECT_V6 | Protocol=ICMPv6 |
| codex_wfp_icmp_assign_v4 | af4751de | RESOURCE_ASSIGNMENT_V4 | Protocol=ICMP |
| codex_wfp_icmp_assign_v6 | ea10db66 | RESOURCE_ASSIGNMENT_V6 | Protocol=ICMPv6 |
| codex_wfp_dns_53_v4 | 83172805 | AUTH_CONNECT_V4 | RemotePort=53 |
| codex_wfp_dns_53_v6 | d23b2efb | AUTH_CONNECT_V6 | RemotePort=53 |
| codex_wfp_dns_853_v4 | 420b026f | AUTH_CONNECT_V4 | RemotePort=853（DoT） |
| codex_wfp_dns_853_v6 | 8d917c81 | AUTH_CONNECT_V6 | RemotePort=853 |
| codex_wfp_smb_445_v4 | e1d6e0af | AUTH_CONNECT_V4 | RemotePort=445 |
| codex_wfp_smb_445_v6 | c2bceca4 | AUTH_CONNECT_V6 | RemotePort=445 |
| codex_wfp_smb_139_v4 | ba10c618 | AUTH_CONNECT_V4 | RemotePort=139 |
| codex_wfp_smb_139_v6 | fe7f22b8 | AUTH_CONNECT_V6 | RemotePort=139 |

（每条都叠加 User 条件；DNS 两条描述注明 TCP/UDP 通杀——仅按端口匹配。）

### 2.3 事务安装/卸载

`install_wfp_filters_for_account(account)`：`Engine::open(INFINITE)`（`FwpmEngineOpen0`，session 名 "Codex Windows Sandbox WFP"，`txnWaitTimeoutInMSec`）→ `FwpmTransactionBegin0` → `ensure_provider` → `ensure_sublayer` → 对每条 spec `delete_filter_if_present`（`FwpmFilterDeleteByKey0`，容忍 `FWP_E_FILTER_NOT_FOUND`/`FWP_E_NOT_FOUND`，先删旧副本再重加保证参数新鲜）→ `add_filter`（`FwpmFilterAdd0`）→ `FwpmTransactionCommit0`；`Transaction` 未提交即 drop 时 `FwpmTransactionAbort0` 回滚，`Engine` drop 时 `FwpmEngineClose0`。`remove_wfp_filters` 对称删过滤器→子层→provider（容忍 NOT_FOUND，事务锁等待 1s）。包装层 `src/wfp_setup.rs::install_wfp_filters`：`catch_unwind` 包住安装、经 Statsig 发 `codex.windows_sandbox.wfp_setup_success/failure`（服务名 `codex-windows-sandbox-setup`，panic 也计为失败），错误仍向上传播。

## 3. 网络隔离（三）：legacy env 兜底 — `src/env.rs::apply_no_network_to_env`

**只在 legacy 后端生效**：全仓唯一非测试调用链是 `src/spawn_prep.rs::prepare_legacy_spawn_context`（在 `common.permissions.should_apply_network_block()` 即网络策略禁用时调用），该函数只被 `src/unified_exec/backends/legacy.rs` 与 `src/lib.rs` legacy capture 使用；elevated 后端走 `prepare_elevated_spawn_context_for_permissions`，网络由防火墙/WFP 承担，不写这些变量。

内容（一律 `entry().or_insert_with()`，不覆盖用户已有值）：
- `SBX_NONET_ACTIVE=1`（标记位）；
- 代理指向废弃端口 9：`HTTP_PROXY`/`HTTPS_PROXY`/`ALL_PROXY`/`GIT_HTTP_PROXY`/`GIT_HTTPS_PROXY` = `http://127.0.0.1:9`，`NO_PROXY=localhost,127.0.0.1,::1`；
- 包管理器离线：`PIP_NO_INDEX=1`、`PIP_DISABLE_PIP_VERSION_CHECK=1`、`NPM_CONFIG_OFFLINE=true`、`CARGO_NET_OFFLINE=true`；
- Git 旁路封死：`GIT_SSH_COMMAND="cmd /c exit 1"`（ssh 协议直接失败）、`GIT_ALLOW_PROTOCOLS=""`；
- denybin 桩：`ensure_denybin(&["ssh","scp"], None)` 在 `~/.sbx-denybin/` 写 `ssh.bat/.cmd`、`scp.bat/.cmd`，内容固定 `@echo off\r\nexit /b 1\r\n`；同时**删除**可能残留的 `curl/wget` 桩（避免误杀真实工具）；`prepend_path` 把该目录插到 PATH 最前，`reorder_pathext_for_stubs` 把 `PATHEXT` 中 `.BAT;.CMD` 提到最前——保证桩优先于真 ssh/scp 被解析。

定位：这是受限令牌后端没有账户级防火墙时的**尽力而为**软隔离（进程可自行 unset），elevated 后端不依赖它。

## 4. Job Object — `codex-rs/utils/pty/src/win/job.rs`

- `create()`：`CreateJobObjectW(null, null)` → `set_limit_flags(JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_BREAKAWAY_OK)`（`SetInformationJobObject` + `JobObjectExtendedLimitInformation`）。**只设 LimitFlags，无任何 CPU/内存/IO 限额**——Job 只承担「整树终止」，不做资源配额。`create_without_breakaway()` 变体只留 KILL_ON_JOB_CLOSE。
- 沙箱路径用 **PROC_THREAD_ATTRIBUTE_JOB_LIST 原子挂接**（见 §5），不用事后 `AssignProcessToJobObject`（那是非原子窗口）。`src/proc_thread_attr.rs::set_job`（属性号 `0x0002_000D`）注释明确：若 Windows 无法满足 job list（如父 job 禁止嵌套）则**失败 spawn**，绝不短暂运行一棵无约束的沙箱进程树——`?` 直接向上传播错误。
- utils-pty 内的兼容路径（`spawn_contained`/`assign_and_resume_process`，`CREATE_SUSPENDED` + ntdll `NtResumeProcess`）允许嵌套 job 拒绝时回退为无约束运行——但沙箱 crate 不走该回退。
- `preserve_descendants()`：持 `preserve_descendants: Mutex<bool>` 锁，重新 `set_limit_flags(JOB_OBJECT_LIMIT_BREAKAWAY_OK)`（清掉 KILL_ON_JOB_CLOSE），让根进程正常退出后子孙存活（unified_exec 会话的正常退出路径调用）。
- `terminate()`：同一把锁下（与 preserve 竞争先到先得，注释明示）`TerminateJobObject(handle, 1)`。辅助 API：`open_process_handle`（PID→`OpenProcess(PROCESS_TERMINATE, bInheritHandle=0)` 句柄，防 PID 复用）、`terminate_process_handle`（`TerminateProcess` 单点兜底）。

## 5. 句柄继承白名单、超时、取消

**句柄白名单**：`src/process.rs::create_process_as_user` 管道模式下收集 `inherited_handles = [stdin_h, stdout_h]`（stderr 句柄不同才追加），逐个 `SetHandleInformation(HANDLE_FLAG_INHERIT)`，再 `attrs.set_handle_list(...)` → `UpdateProcThreadAttribute(PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x0002_0002)`；`CreateProcessAsUserW` 以 `bInheritHandles=1` 启动——子进程**只**继承这几支管道句柄，runner 自身的 IPC 管道等其余句柄不泄漏进沙箱。无 stdio 参数时走 `ensure_inheritable_stdio` 转发本进程 stdio。属性列表还按需追加 `preserve_desktop_app_context()`（`PROC_THREAD_ATTRIBUTE_DESKTOP_APP_POLICY`，仅当本进程持有 package identity 时，防止系统 shell 脱离打包环境后无法从受保护目录启动子进程）。

**超时（WaitForSingleObject + WAIT_TIMEOUT=0x102）三条路径**：
- legacy capture（`src/lib.rs` windows_impl，`wait_for_process`）：无取消令牌时单次 `WaitForSingleObject(process, timeout)`，返回 `0x0000_0102` → `WaitOutcome::TimedOut`；有令牌时 50ms 轮询并兼顾 deadline。timed_out/cancelled → `job.terminate()`，失败再 `TerminateProcess(根,1)`；`exit_code = 128 + 64`（=**192**，POSIX SIGTERM 语义）；正常退出读 `GetExitCodeProcess`。根正常退出则 `job.preserve_descendants()`。
- elevated runner（`src/bin/command_runner/win.rs:653-680`）：同样 `WaitForSingleObject(pi.hProcess, timeout)` → `WAIT_TIMEOUT` → `terminate_job_or_process` + `TERMINATION_WAIT_MS` 二次等待确认根进程退出；timed_out 时 `exit_code = 128 + 64`。
- legacy unified_exec 会话（`backends/legacy.rs` 专用等待线程）：`WaitForSingleObject(pi.hProcess, timeout)` 超时只 terminate，退出码如实读取（TerminateJobObject 的 1），不合成 192——192 仅是 capture 语义。

**取消（Terminate 帧）**：elevated capture 由 `src/elevated_impl.rs::spawn_cancel_writer` 轮询 `WindowsSandboxCancellationToken`（`park_timeout(50ms)` 限延迟不忙等），触发即向 runner 管道写 `Message::Terminate`（`src/elevated/ipc_framed.rs` 长度前缀 JSON 分帧，`IPC_PROTOCOL_VERSION=1`）；runner 输入循环（`command_runner/win.rs:523`）收到 `Terminate` → `terminate_job_or_process(&job, process)`（job 失败再 TerminateProcess 根进程）。legacy 会话取消是 `ProcessDriver.terminator` 闭包直接调 `terminate_job_or_process`；`stdio_bridge` 收到 ctrl_c 时调 `session.request_terminate()` 后仍等待真实退出码。

## 6. 私有桌面 — `src/desktop.rs`

- `PrivateDesktop::create`：名字 `format!("CodexSandboxDesktop-{:x}", SmallRng::from_entropy().gen::<u128>())`（随机不可猜），`CreateDesktopW(name, null, null, dwflags=0, DESKTOP_ALL_ACCESS, null)`（不带 SECURITY_ATTRIBUTES，默认 DACL）；随后 `grant_desktop_access`：取当前令牌 logon SID，`SetEntriesInAclW`（`DESKTOP_ALL_ACCESS`/`GRANT_ACCESS`/`TRUSTEE_IS_SID`）+ `SetSecurityInfo(SE_WINDOW_OBJECT, DACL_SECURITY_INFORMATION)` 把桌面 DACL 授给同 logon session 的主体；失败即 `CloseDesktop` 回滚。
- `LaunchDesktop::prepare` 记录 `startup_name = "Winsta0\\{name}"`，`startup_info_desktop()` 供 `STARTUPINFOW.lpDesktop` 使用。`process.rs` 注释给出动机：受限令牌启动若不设 lpDesktop，PowerShell 等进程会 `STATUS_DLL_INIT_FAILED`；私有桌面同时把沙箱 GUI/全局钩子隔离在默认桌面之外。
- 共享复用：elevated 走 `shared_private_desktop_for_user`（wrapper 侧 `crate::desktop::DesktopPolicy::elevated` 构造策略指纹），legacy 走 `LaunchDesktop::shared_legacy_name`（`DesktopPolicy` 含 readonly/写根 capability SID、network_enabled、deny 路径等）。两者都以 `(sandbox_sid, DesktopPolicy)` 为 key 查进程内 `SHARED_PRIVATE_DESKTOPS: Mutex<HashMap<..>>` 缓存——同一账户且策略完全相同才复用；不同策略分开桌面，防 GUI 钩子跨策略泄漏。共享路径用 SDDL `D:P(A;;0x{DESKTOP_ALL_ACCESS:x};;;{owner_user_sid})(A;;0x{DESKTOP_PARTICIPANT_ACCESS:x};;;{sandbox_sid})` 创建（注释：`CreateProcessWithLogonW` 与调用方共享 logon SID，故另授 owner 用户 SID 管理权）。
- `LaunchDesktop::open_private(name)`（wrapper 转发模式用）：严格校验名字（`CodexSandboxDesktop-` 前缀 + 非空、≤32 个 ASCII hex 的 nonce）后 `OpenDesktopW(DESKTOP_PARTICIPANT_ACCESS)`，不创建也不回退 Default。`CreatedProcess`/`PipeSpawnHandles` 持有 `LaunchDesktop`（`_desktop` 字段）保活，进程结束才释放。

## 7. exec 执行链

### 7.1 unified_exec 双后端 — `src/unified_exec/mod.rs`

`spawn_windows_sandbox_session_with_desktop` 按 `WindowsSandboxLevel` 分发：
- `Elevated` → `backends/elevated.rs`：解析权限→`DesktopPolicy`（无现成桌面名时）→构造 `SpawnRequest`（含 cap_sids、proxy、读写根、deny 列表、tty/stdin_open）→ `spawn_runner_transport_with_retry`（`runner_client`，named pipe 连提权 command runner，凭据失配可触发刷新重试）→ 输入/resize 转成 IPC 帧。
- 否则 legacy → `backends/legacy.rs`：`prepare_legacy_spawn_context`（含 §3 网络兜底）→ `prepare_legacy_session_security`（受限令牌 + capability SID）→ `apply_legacy_session_acl_rules` → 直接 spawn。legacy 明确拒绝四件事：`proxy_enforced` 与 `network_proxy_restricting_sid`（"managed networking requires the elevated Windows sandbox backend"）、受限盘读（`!has_full_disk_read_access`）、非空 deny-read 覆盖（WRITE_RESTRICTED 令牌下 restricting SID 只对写权威，deny-read 不可信）。
- 两后端共同产出 `SpawnedProcess`（`backends/windows_common.rs::finish_driver_spawn` 组装 `ProcessDriver`：writer_tx/stdout_rx/stderr_rx/exit_rx/terminator/resizer）。elevated 侧：`start_runner_pipe_writer` 独占写管道；`start_runner_stdin_writer` 把输入封成 `Message::Stdin`（base64，tty 时经 `WindowsTtyInputNormalizer` 规整换行），EOF 补 `Message::CloseStdin`；`make_runner_resizer` 发 `Message::Resize{rows,cols}`；`start_runner_stdout_reader` 解 `Output`(stdout/stderr 双流)/`Exit{exit_code,timed_out}`/`Error` 帧。

legacy 会话时序（`backends/legacy.rs::spawn_windows_sandbox_session_legacy`）：
1. `prepare_legacy_spawn_context`（§3 网络兜底 + NUL/pager/PATH 规范化）→ 四项前置拒绝检查；
2. `legacy_session_capability_roots` + `prepare_legacy_session_security` 造受限令牌与 capability SID，`allow_null_device_for_workspace_write` 放行 NUL 设备，`apply_legacy_session_acl_rules` 铺会话 ACL；
3. `spawn_legacy_process`：`tty` → ConPTY 路径（§7.3），否则 `spawn_process_with_pipes` 3 管道路径；桌面来自 `private_desktop_name`（wrapper 复用）或 `LaunchDesktop::prepare_legacy`；
4. 专用等待线程 `WaitForSingleObject`：超时 → `terminate_job_or_process`；正常退出 → `job.preserve_descendants()`；随后关 ConPTY/令牌句柄，`finalize_exit` 读退出码并写成功/失败日志；
5. 返回 `ProcessDriver`：stdin 走 writer_tx→输入写线程（管道直接 `WriteFile`；ConPTY 走 normalizer），terminator/resizer 如 §5/§7.3。

elevated 会话时序（`backends/elevated.rs`）：解析权限 → （无桌面名时）`DesktopPolicy::elevated` + 要求共享桌面名随 `SpawnRequest.private_desktop_name` 下发 → `spawn_runner_transport_with_retry` 连接 runner（失败可 `refresh_logon_sandbox_creds` 刷新凭据重试一次）→ runner 进程内按 §5 等待/终止，经 `Exit` 帧回传 `exit_code/timed_out` → `record_command` 打点（`runner_metrics`）。

### 7.2 env.rs 与环境块

- `normalize_null_device_env`：任意变量值 `/dev/null`（含 `\\dev\null` 写法）→ `NUL`（POSIX 习惯适配）。
- `ensure_non_interactive_pager`：`GIT_PAGER`/`PAGER` 缺省 `more.com`，`LESS=""` —— 非交互分页不挂起。
- `inherit_path_env`（由 `spawn_prep.rs::SpawnPrepOptions{inherit_path,..}` 控制）：缺省时从父进程补 `PATH`/`PATHEXT`，保证依赖父环境的调用方行为稳定。
- `inject_git_safe_directory`（`src/sandbox_utils.rs`）：沿 cwd 找 git worktree 根，追加 `GIT_CONFIG_KEY_n=safe.directory`（根与 `根/*` 两条）并递增 `GIT_CONFIG_COUNT`，规避沙箱内 git 「dubious ownership」报错。
- `process.rs::make_env_block`：把 env map 按 key **大小写不敏感排序**（同键名大小写差异再按原串 tie-break，满足 Windows 环境块大小写不敏感且排序确定的要求），逐条写 UTF-16 `"K=V\0"`，末尾再补一个 `\0` 终结符；配合 `CREATE_UNICODE_ENVIRONMENT` 标志把宽字符块传给 `CreateProcessAsUserW`。

### 7.3 管道模式（3 个匿名管道）与 ConPTY 模式

- 管道模式：`process.rs::spawn_process_with_pipes` 依次 `CreatePipe` 建 stdin/stdout 管道，`StderrMode::Separate` 时再建 stderr 管道（共 3 支；`MergeStdout` 则 stderr 复用 stdout 写端，2 支）。父进程在 spawn 后关闭子侧句柄，按 `StdinMode` 决定是否保留 `stdin_write`，并持有 `stdout_read`/`stderr_read`；输出经 `read_handle_loop`（8KiB `ReadFile` 循环、EOF 后 `CloseHandle`）泵进 broadcast channel；控制台行为由 `ConsoleMode::NoWindow`（有 stdio 时附 `CREATE_NO_WINDOW`，避免弹出窗口）决定。
- ConPTY 模式：`src/conpty/mod.rs::spawn_conpty_process_as_user`——`RawConPty::new(80, 24)` 初始尺寸；属性列表 `set_pseudoconsole`（`PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x0002_0016`）+ `set_job`（+ package identity 时的 desktop app policy）；`STARTF_USESTDHANDLES` 但 hStdInput/Output/Error 均为 `INVALID_HANDLE_VALUE`（句柄由 pseudoconsole 属性携带，pseudoconsole 的 in/out 句柄随属性自动继承，不需 HANDLE_LIST）；创建标志 `EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT`。resize：legacy 会话直接 `ResizePseudoConsole`（`backends/legacy.rs::resize_conpty_handle`，持 hpc 互斥锁）；elevated 会话经 `Message::Resize` 帧由 runner 侧 `ResizePseudoConsole`（`command_runner/win.rs:507`）执行。

### 7.4 wrapper.rs 的 argv/env 分块传参协议 — `src/wrapper.rs`

`create_windows_sandbox_command_args_for_permission_profile` 生成 `codex.exe --run-as-windows-sandbox …` 的 argv（给需要 argv 形状启动器的直接 spawn 调用方，对齐 macOS seatbelt / Linux 包装器路径）：

| flag | 值 | 说明 |
|---|---|---|
| `--codex-home` | 绝对路径（强制校验） | 沙箱数据根 |
| `--command-cwd` | 绝对路径 | 命令 cwd |
| `--permission-profile` | JSON | PermissionProfile |
| `--env-json` | JSON | 工作负载 env（先剔除 launcher 传输变量） |
| `--windows-sandbox-level` | disabled/restricted-token/elevated | 后端选择 |
| `--workspace-root` | 可重复 | 空则回退 command_cwd |
| `--windows-sandbox-private-desktop-name` | 桌面名 | 调用方在此已按策略复用/创建共享桌面（§6） |
| `--proxy-enforced` / `--network-proxy-restricting-sid` / `--preserve-proxy-settings` | 布尔/SID | elevated 网络代理参数 |
| `--read-roots-json` / `--read-roots-include-platform-defaults` / `--write-roots-json` / `--deny-read-paths-json` / `--deny-write-paths-json` | JSON/布尔 | 文件系统覆盖 |
| `--` | — | 分隔内层命令（必填，缺失即 bail） |

超长兜底：序列化后的参数若超出 Windows 命令行限制（`launch_environment::needs_environment`），退化为两参数 `[--run-as-windows-sandbox, --launch-payload-env]`，由 `src/environment_transport.rs::encode` 把**完整参数 JSON** 按 16KiB（`CHUNK_BYTES`）UTF-8 字符边界切块，写入 `CODEX_SANDBOX_LAUNCH_<i>` 系列环境变量（另加 `CODEX_SANDBOX_LAUNCH_COUNT`/`CODEX_SANDBOX_LAUNCH_BYTES`，总量上限 16MiB，禁止 NUL，launcher-only 命名空间 `is_key` 判定）；`run_windows_sandbox_wrapper_main` 端按 `--launch-payload-env` 用 `decode`（校验块数/长度/去重/边界）重组后交给 `parse_windows_sandbox_wrapper_args` 严格解析——未知参数直接 bail。随后以 `spawn_windows_sandbox_session_with_desktop(..., Some(private_desktop_name))` 启动会话（`timeout_ms: None, tty: false, stdin_open: true`）。

### 7.5 stdio_bridge — `src/stdio_bridge.rs`

`forward_sandbox_session_stdio(spawned)`（wrapper 主进程用它把自身 stdio 桥接到沙箱会话，返回退出码）：
- stdin 转发线程：8KiB 块读 stdin → `blocking_send` 进会话 writer_tx；EOF/错误时经 oneshot 通知主循环 `session.close_stdin()`（下游转成 `CloseStdin` 帧或关闭管道写端）。
- stdout/stderr 各一个专用阻塞线程：`tokio_runtime.block_on(output_rx.recv())` → `write_all` + `flush`（同步写本进程 stdio，写完即刷）。
- 退出：`tokio::select!` 会话 `exit_rx` vs `ctrl_c`；ctrl_c → `session.request_terminate()`，随后**仍等待**真实退出码（不猜 -1）。
- 退出后给两个输出转发线程 5s 排空超时（`output_drain_timeout`）：既让大尾巴输出尽量吐完，又不因罕见 EOF 异常永久悬挂 wrapper。

## 8. 小结

网络面三道闸（防火墙 COM 按账户 SID + LocalPolicyModifyState/读回双校验 fail-closed、WFP 事务化持久过滤器、legacy env+denybin 桩）与进程面三件套（Job Object 整树收尸 + 属性列表原子挂接/句柄白名单继承、私有桌面按策略隔离、双后端 unified exec 会话）共同构成「账户级隔离」模型：网络规则的主体、桌面的复用键、capability SID 的归属全部锚定在沙箱账户 SID 上，进程内则靠 Job Object + STARTUPINFOEX 属性保证容器关系在进程启动那一刻原子成立。

## 附录：关键 API → 文件速查表

| 能力 | 文件 | 关键函数/常量 |
|---|---|---|
| 防火墙规则安装 | src/setup_provisioning/firewall.rs | `ensure_offline_network_blocks` / `ensure_offline_proxy_allowlist` / `ensure_block_rule` / `configure_rule` / `blocked_loopback_tcp_remote_ports` / `validate_local_policy_modify_result` |
| WFP 安装 | src/wfp.rs, src/wfp/filter_specs.rs | `install_wfp_filters_for_account` / `remove_wfp_filters` / `UserMatchCondition::for_account` / `build_conditions` / `FILTER_SPECS` |
| legacy 网络兜底 | src/env.rs, src/spawn_prep.rs | `apply_no_network_to_env` / `ensure_denybin` / `prepend_path` / `reorder_pathext_for_stubs`（调用点 `prepare_legacy_spawn_context`） |
| Job Object | ../utils/pty/src/win/job.rs | `JobObject::create` / `terminate` / `preserve_descendants` / `set_limit_flags` |
| 进程创建/属性 | src/process.rs, src/proc_thread_attr.rs | `create_process_as_user` / `spawn_process_with_pipes` / `make_env_block` / `read_handle_loop`；`set_job` / `set_handle_list` / `set_pseudoconsole` / `preserve_desktop_app_context` |
| ConPTY | src/conpty/mod.rs | `spawn_conpty_process_as_user`（80×24 初始，`EXTENDED_STARTUPINFO_PRESENT`） |
| 私有桌面 | src/desktop.rs | `PrivateDesktop::create` / `grant_desktop_access` / `LaunchDesktop::prepare` / `shared_legacy_name` / `open_private` |
| 超时/取消 | src/lib.rs, src/bin/command_runner/win.rs, src/elevated_impl.rs | `wait_for_process`（WAIT_TIMEOUT→192） / `spawn_cancel_writer`（Terminate 帧） / `terminate_job_or_process` |
| unified exec | src/unified_exec/* | `spawn_windows_sandbox_session_with_desktop`（分发）/ `backends::legacy` / `backends::elevated` / `windows_common::{finish_driver_spawn, make_runner_resizer, start_runner_stdin_writer}` |
| wrapper 协议 | src/wrapper.rs, src/environment_transport.rs | `create_windows_sandbox_command_args_for_permission_profile` / `parse_windows_sandbox_wrapper_args` / `encode` / `decode`（16KiB×16MiB 分块） |
| stdio 桥 | src/stdio_bridge.rs | `forward_sandbox_session_stdio`（8KiB 转发、5s 排空、ctrl_c→terminate） |

