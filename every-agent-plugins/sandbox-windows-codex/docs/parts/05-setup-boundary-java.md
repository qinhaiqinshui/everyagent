# 第 5 分片：setup / 卸载流程、安全边界总结与 Java 实现要点

> 源码基线：`codex-rs/windows-sandbox-rs`（main@92bc601a）。除特别注明外，相对路径均以该 crate 为根；
> `service/…` 指 `codex-rs/windows-sandbox-service/src/…`。
>
> 本分片覆盖四层结构：**编排层**（`src/setup.rs`，CLI 进程内决定 payload/提权/并发去重）、**特权助手层**（`src/setup_provisioning.rs` + 子模块，真正执行账户/ACL/网络变更，运行在提权后的 `codex-windows-sandbox-setup.exe`）、**卸载层**（`src/uninstall_windows.rs` + 子模块与 `removal.ps1`）、**注册层**（`app_package.rs`/`runtime_ownership.rs`，Legacy vs Registered 运行时身份）。分层记忆：编排层永远不碰特权 API，助手层永远不做策略决策（roots 全由 payload 带入）。

## 5.1 setup 编排（`src/setup.rs`）

### 5.1.1 ElevationPayload 与传输编码
- `struct ElevationPayload`（setup.rs:701）字段：`version(=SETUP_VERSION=5)`、`offline_username("CodexSandboxOffline")`、`online_username("CodexSandboxOnline")`、`codex_home`、`command_cwd`、`read_roots/write_roots/deny_read_paths/deny_write_paths`、`proxy_ports:Vec<u16>`、`allow_local_binding`、`otel`、`real_user`、`mode:SetupMode`、`runtime`（Legacy 时 `skip_serializing_if` 省略，读端默认 Legacy）、`refresh_only`。
- 编码链（`run_setup_exe_payload`，setup.rs:956）：payload → `serde_json` → `BASE64_STANDARD` 编码 →
  - **argv 单参数**：payload ≤ 24,000 个 UTF-16 单位时直接作为唯一命令行参数（`launch_environment::needs_environment`，launch_environment.rs:15）；elevated 路径经 `quote_arg`（setup.rs:850）做 CRT 规则引号转义（反斜杠×2、内嵌引号前 `\"`）后填入 `SHELLEXECUTEINFOW.lpParameters`。
  - **env 分块**：超过阈值时追加 `--launch-payload-env` 参数并通过 `environment_transport::encode` 写入子进程环境：前缀 `CODEX_SANDBOX_LAUNCH_`，每块 16 KiB（按 UTF-8 char boundary 回退），上限 16 MiB，附 `…_COUNT`/`…_BYTES` 两个元变量（environment_transport.rs:9-14）；helper 端 `real_main`（setup_provisioning.rs:469）识别该参数后 `decode` 还原。`configure_command` 会先 `env_remove` 继承来的传输变量防串扰。
- `find_setup_exe`：优先 `bundled_executable_path_for_exe(exe, "codex-windows-sandbox-setup.exe")`（同目录捆绑），否则退回裸文件名。

### 5.1.2 run_elevated_setup 与 ShellExecuteExW "runas"
- `run_elevated_setup` → `run_elevated_setup_inner`（setup.rs:1112）：校验 `validate_elevated_filesystem_policy` → 先建 `codex_home/.sandbox`（`OrchestratorSandboxDirCreateFailed`）→ `is_elevated()`（setup.rs:504：`AllocateAndInitializeSid(SECURITY_NT_AUTHORITY, 2, SECURITY_BUILTIN_DOMAIN_RID=0x20, DOMAIN_ALIAS_RID_ADMINS=0x220)` + `CheckTokenMembership` 判定 BUILTIN\Administrators 成员）→ `run_setup_exe(payload, needs_elevation, …)`。
- payload 根集合由三条构建链产出：`build_payload_roots`（setup.rs:1258，应用 `SetupRootOverrides`）、`gather_read_roots`/`gather_full_read_roots_for_permissions`（symbolic 全读策略才放开 `WINDOWS_PLATFORM_DEFAULT_READ_ROOTS`=[C:\Windows, Program Files×2, ProgramData] + USERPROFILE 逐项减去 `USERPROFILE_ROOT_EXCLUSIONS`）、`effective_write_roots_for_setup`（`expand_user_profile_root`→profile 根展开→排除敏感子目录→`filter_ssh_config_dependency_roots`→`filter_sensitive_write_roots` 剥离 codex_home/.sandbox）。
- `run_setup_exe_payload`（setup.rs:956）提权分支：
  - `SHELLEXECUTEINFOW`：`fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC`（源码注释：Tokio worker 无消息循环，ShellExecuteEx 须同步激活）；`lpVerb="runas"`；`nShow=0(SW_HIDE)` 隐藏 UAC 子进程窗口。
  - 成功后 `WaitForSingleObject(hProcess, INFINITE)` + `GetExitCodeProcess` + `CloseHandle`；退出码非 0 → `report_helper_failure`。
  - `ShellExecuteExW` 失败且 `GetLastError()==ERROR_CANCELLED(1223)` → `OrchestratorHelperLaunchCanceled`（用户点了"否"），否则 `OrchestratorHelperLaunchFailed`。
  - 退出码 0 还需 `verify_setup_completed`（`identity::sandbox_setup_is_complete` 读 marker），防 helper 假成功（`OrchestratorHelperIncomplete`）。
- 非提权分支（`needs_elevation=false`）：`std::process::Command` + `creation_flags(0x08000000 CREATE_NO_WINDOW)`、stdio 全 null；或经 `setup_launch::spawn_with_retained_handles`（CREATE_SUSPENDED + `DuplicateHandle` 注入保留目录句柄 + `NtResumeProcess`，见 5.4）。
- `run_elevated_provisioning_setup_with_retained_handles`（setup.rs:1189）：服务路径，要求调用进程**已提权**（否则 `OrchestratorElevationRequired`），`mode=ProvisionOnly`，并强校验 codex_home 为绝对本地盘路径。
- `run_elevated_setup_with_proxy_settings`（setup.rs:1076）：`registered_core_requested()` 时改走认证服务 `provisioning_client::provision_windows_sandbox_via_service`（要求 outcome==Provisioned），不下发本地 helper。

### 5.1.3 run_setup_refresh：不提权
- `run_setup_refresh`（setup.rs:224）→ `run_setup_refresh_inner` → `run_setup_refresh_payload`（setup.rs:374）：源码注释明示 "Refresh should never request elevation; ensure verb isn't set and we don't trigger UAC"——直接 `background_command` + `launch_environment::configure_command` + stdio null，同步 `status()` 等待。
- 刷新 payload：`refresh_only=true`、`mode=Full`、read roots 仅保留 deny-read 语义所需（write roots 正常下发）；`run_setup_refresh_with_extra_read_roots` 变体可注入额外只读根并把平台默认根排除。
- 失败处理同 5.1.4；成功后再次 `clear_setup_error_report`。

### 5.1.4 singleflight 去重
- `run_setup_singleflight(key=b64, run)`（setup.rs:149）：`SETUP_FLIGHTS: OnceLock<Mutex<HashMap<String, Arc<SetupFlight>>>>`；leader 执行闭包，follower 在 `SetupFlight::wait()`（Mutex+Condvar）上等待**共享同一结果**（`SharedSetupError` 保留 `SetupErrorCode` 供 follower 复原结构化错误）；leader 完成后 `flight.complete()` 并按 `Arc::ptr_eq` 摘除表项。
- 例外：`retained_handles` 非空（服务请求）**不得**加入 bare flight——其 helper 带目录保护启动，与裸 helper 混跑会破坏保护（`run_setup_exe`，setup.rs:933 注释）。key 即 b64 payload，等价请求天然合并。

### 5.1.5 setup_error.json 错误协议
- 路径：`codex_home/.sandbox/setup_error.json`（`setup_error.rs::setup_error_path`）。
- `SetupErrorCode`（setup_error.rs:15，26 枚举，snake_case，兼作 metric tag）：编排侧 `OrchestratorSandboxDirCreateFailed / ElevationCheckFailed / ElevationRequired / PayloadSerializeFailed / HelperLaunchFailed / HelperLaunchCanceled / HelperExitNonzero / HelperReportReadFailed / HelperIncomplete`；helper 侧 `HelperRequestArgsFailed / SandboxDirCreateFailed / LogFailed / UserProvisionFailed / UsersGroupCreateFailed / UserCreateOrUpdateFailed / DpapiProtectFailed / UsersFileWriteFailed / SetupMarkerWriteFailed / SidResolveFailed / CapabilitySidFailed / FirewallComInitFailed / FirewallPolicyAccessFailed / FirewallPolicyIneffective / FirewallRuleCreateOrAddFailed / FirewallRuleVerifyFailed / ReadAclHelperSpawnFailed / SandboxLockFailed / UnknownError`。
- 协议时序：orchestrator 启动 helper 前 `clear_setup_error_report`（清除失败 → 记日志、`cleared_report=false`）；helper 失败时在 `run_payload`（setup_provisioning.rs:566）写 `{code,message}`（ProvisionOnly 用 `write_file_atomically`，其余 `write_setup_error_report`）；orchestrator 见非 0 退出码后 `read_setup_error_report` 还原 `SetupFailure`（读到即精确错误，读不到降级 `OrchestratorHelperExitNonZero`，读失败 `OrchestratorHelperReportReadFailed`）。消息进 metric 前经 `redact_username_segments` 把 `C:\Users\<name>` 打码为 `<user>`。

### 5.1.6 readiness 判定（何时跳过 setup）
- `identity.rs::sandbox_setup_is_complete`（identity.rs:79）：marker 存在且 `version_matches()` ∧（registered 请求时 `app_package::registered_setup_is_ready`）∧ `.sandbox-secrets/sandbox_users.json` 版本匹配——即"marker + 凭据"双文件闸门。
- `sandbox_setup_is_complete_with_settings`（identity.rs:93）：再比对排序后的 `proxy_ports` 与 `allow_local_binding`，网络设置漂移即触发 refresh（对应 5.2.4 的 `request_mismatch_reason`）。
- 凭据自愈：`identity.rs`（约 233 行）把 `CreateProcessWithLogonW`/`LogonUserW` 的 `ERROR_LOGON_FAILURE | ERROR_PASSWORD_EXPIRED | ERROR_PASSWORD_MUST_CHANGE` 归类为"密码失配"，触发**重跑 setup 刷新凭据后重试**，而非直接报错；`prepare_sandbox_creds` 还会在 marker 过期/组被清理时走账户修复（identity.rs:374）。

## 5.2 setup 助手（`src/setup_provisioning.rs` 及子模块）

### 5.2.1 四种模式与全局锁
- `enum SetupMode`（setup_provisioning.rs:122，kebab-case）：`Full`（一次性完整 setup：账户+ACL+网络，且可拉起 read-ACL 子进程）、`InteractiveProvision`（CLI 首次交互提权入口，只建账户/网络，不动 roots）、`ProvisionOnly`（服务路径，接受他人 CODEX_HOME，错误与 secrets 原子写）、`ReadAclsOnly`（`spawn_read_acl_helper` 派生的子模式，仅授读 ACE，独占 `read_acl_mutex`，重复运行直接跳过）。
- `provisions_accounts(refresh_only)`：Interactive/ProvisionOnly 恒真；Full 在非 refresh 时为真；ReadAclsOnly 恒假。为真时 `real_main` 持 `acquire_sandbox_setup_lock(INFINITE)` 并**拒绝** registered runtime 与已注册 installation record（"registered Core owns these sandbox accounts"）。
- `Global\CodexSandboxSetup` 锁（`setup_mutex.rs`）：`CreateMutexW` + SDDL `"D:P(A;;GA;;;SY)(A;;GA;;;BA)"`（仅 SYSTEM/管理员可持有）；`WaitForSingleObject` 接受 `WAIT_ABANDONED`（崩溃者自动释放，setup 自带账户修复）；guard `SandboxSetupLock` Drop 时 `ReleaseMutex`，线程绑定（PhantomData）。跨 setup/卸载/PowerShell 清理串行化账户与网络变更。

### 5.2.2 建组、建户与凭据
- 建组：`winutil.rs::ensure_sandbox_users_group` → `NetLocalGroupAdd`（`LOCALGROUP_INFO_1`，组名 `CodexSandboxUsers`，注释 "Codex Sandbox users"），已存在（NERR_GroupExists）视为成功；失败映射 `HelperUsersGroupCreateFailed`。
- 建户（`setup_provisioning/sandbox_users.rs::ensure_local_user`）：`NetUserAdd` level 1，`USER_INFO_1 { usri1_priv=USER_PRIV_USER, usri1_flags=UF_SCRIPT|UF_DONT_EXPIRE_PASSWD|new_user_flags }`。`new_user_flags` 由 `provision_sandbox`（setup_provisioning.rs:688）决定：检测到既有账户被禁用（中断的清理残留）时新建/重置账户也带 `UF_ACCOUNTDISABLE`，直到网络限制修复成功后再统一解禁（防止半成品账户放行登录）。
- 回退：`NetUserAdd` 非 NERR_Success（用户已存在/密码策略不符）→ `NetUserSetInfo` level **1003**（只重置密码，不动 flags——注释：flags 可能属企业策略所有，先改密码后改 flags 的两步会在中间态失败丢凭据）；仍失败 → `HelperUserCreateOrUpdateFailed`。
- 加组：`ensure_local_group_member` → `NetLocalGroupAddMembers` level 3（`LOCALGROUP_MEMBERS_INFO_3`，按 `domain\name`），成员已在组中的错误码被忽略；另将账户加入内置 `Users`（`S-1-5-32-545`，`LookupAccountSidW` 反查本地化组名）确保普通用户主体。
- 密码：`random_password`（SmallRng 24 字符，字母+数字+符号）；`write_secrets` 对两账户密码 `dpapi_protect`（dpapi.rs：`CryptProtectData`，`CRYPTPROTECT_UI_FORBIDDEN|CRYPTPROTECT_LOCAL_MACHINE`——machine scope 让 elevated/非 elevated 进程都能解）→ base64 → `.sandbox-secrets/sandbox_users.json`（version + offline/online {username,password}）。secrets 目录 DACL 对组设 `DENY_ACCESS`（`lock_sandbox_dir`，见 5.2.4）。
- 隐藏账户（`hide_users.rs`）：`HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon\SpecialAccounts\UserList` 下按用户名写 `REG_DWORD 0`（登录界面不显示，best-effort）；`hide_current_user_profile_dir` 在 command-runner 内对首次生成的 profile 目录设 `FILE_ATTRIBUTE_HIDDEN|SYSTEM`。

### 5.2.3 防火墙 + WFP 安装
- 防火墙（`setup_provisioning/firewall.rs`，`configure_offline_sandbox_network` 调用）：COM `CoInitializeEx(COINIT_APARTMENTTHREADED)`（容忍 `RPC_E_CHANGED_MODE`，仅清理自己初始化的引用计数）→ `CoCreateInstance(NetFwPolicy2)` → `INetFwPolicy2::Rules`。
  - 规则族（`LocalUserAuthorizedList = "O:LSD:(A;;CC;;;<offline SID>"` 按用户 SID 限定，`NET_FW_ACTION_BLOCK`、`NET_FW_PROFILE2_ALL`）：`codex_sandbox_offline_block_outbound` / `_block_inbound`（RemoteAddresses=补集式非环回 `0.0.0.0-126.255.255.255,128.0.0.0-255.255.255.255,::,::2-…`）；环回 `…_block_loopback_udp`（127.0.0.0/8,::/127 全禁）与 `…_block_loopback_tcp`（先全禁再 `SetRemotePorts` 收窄到代理端口**补集** `blocked_loopback_tcp_remote_ports`）。
  - **fail-closed 顺序**：先装宽 block 再删 legacy allow（`codex_sandbox_offline_allow_loopback_proxy`）；`allow_local_binding` 模式则反序删 allow/loopback 规则（无端口级例外需求）。
  - 有效性自检：`INetFwPolicy2::LocalPolicyModifyState` 必须 `S_OK + NET_FW_MODIFY_STATE_OK`——组策略覆盖（GP_OVERRIDE）或仅部分 profile 生效（S_FALSE）→ `HelperFirewallPolicyIneffective`，宁可 setup 失败不留无效规则。
  - 写后回读 `LocalUserAuthorizedList` 验证包含预期 SID（`HelperFirewallRuleVerifyFailed`）。规则操作全程幂等（存在则 cast 复用并重刷全部字段）。
- WFP（`src/wfp.rs::install_wfp_filters_for_account`）：`FwpmEngineOpen0(RPC_C_AUTHN_DEFAULT, FWPM_SESSION0{txnWaitTimeoutInMSec=INFINITE})` → `FwpmTransactionBegin0` → `FwpmProviderAdd0`/`FwpmSubLayerAdd0`（固定 GUID：provider `2e31d31c-3948-4753-9117-e5d1a6496f41`，sublayer `e65054fd-4d32-4c7c-95ef-621f0cf6431a`，`FWPM_*_FLAG_PERSISTENT`，容 `FWP_E_ALREADY_EXISTS`）→ 对 `filter_specs::FILTER_SPECS` 每条 `FwpmFilterDeleteByKey0`（容 NOT_FOUND）+ `FwpmFilterAdd0` → `FwpmTransactionCommit0`（未提交 Drop 时 `FwpmTransactionAbort0`）。用户条件：`BuildExplicitAccessWithNameW(FWP_ACTRL_MATCH_FILTER)` + `BuildSecurityDescriptorW` 生成 SD blob，以 `FWP_CONDITION_ALE_USER_ID` + `FWP_SECURITY_DESCRIPTOR_TYPE` 匹配账户（Elevated 后备防线，防火墙规则被 GPO 清掉时仍拦截）。账户修复路径中 WFP 失败即中止、不解禁账户。

### 5.2.4 setup_marker.json
- 路径 `codex_home/.sandbox/setup_marker.json`；读端结构 `setup.rs::SetupMarker{version, offline_username, online_username, created_at, proxy_ports, allow_local_binding}`，写端（sandbox_users.rs:336）额外含恒空的 `read_roots/write_roots`。
- `prepare_setup_marker`（sandbox_users.rs:346）：先删旧 marker（容 NotFound）→ 由 `real_user` 解析 owner SID → SDDL `"D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;<owner SID>)"` 经 `ConvertStringSecurityDescriptorToSecurityDescriptorW(SDDL_REVISION_1)` → `CreateFileW(GENERIC_WRITE, share=0, CREATE_NEW, FILE_ATTRIBUTE_NORMAL)` **先建空文件**——空 marker 使 `sandbox_setup_is_complete` 恒失败，天然充当"setup 未完成"哨兵（成功前保持空）。
- 两阶段提交：`PreparedSetupMarker::Retained(File)`（ProvisionOnly：独占句柄钉住整个事务，服务中途崩溃也不留半开状态）vs `::Reopen`（Full/Interactive：drop 句柄，commit 时按路径重开写入）。`commit_setup_marker` 在 `run_setup` 全部成功后写 JSON 内容（RFC3339 时间戳）。
- marker 与请求对账：`SetupMarker::request_mismatch_reason`——offline 身份下 proxy_ports/allow_local_binding 变化即要求重刷防火墙（local-binding 模式无端口规则故只比布尔）；`version_matches()` 不符触发完整重 setup。

### 5.2.5 Full 模式执行顺序与沙箱目录 DACL（`run_setup_full` / `lock_sandbox_dir`）
`run_setup_full`（setup_provisioning.rs:906）严格排序，顺序本身即安全属性：
1. 非 refresh 时先 `provision_sandbox`（账户+防火墙+WFP，见 5.2.2/5.2.3）；
2. **deny-read ACE 同步先行**：`sync_persistent_deny_read_acls` 在命令启动前于本进程完成（注释明确"must be present before the sandboxed command starts"，不交给后台 helper）；
3. 读授权异步委派：`read_roots` 非空且 `read_acl_mutex_exists()==false` 时 `spawn_read_acl_helper`（自克隆 payload，改 `mode=ReadAclsOnly, refresh_only=true`，CREATE_NO_WINDOW）；已有实例则跳过；
4. refresh 时补 `setup_runtime_bin::ensure_codex_app_runtime_paths_readable`；
5. write roots 逐个 `path_write_aces_need_refresh`（组 SID + 每 root 的 `workspace_write_cap_sid_for_root` capability SID）幂等检查后，用 `std::thread::scope` 并行授 `ensure_allow_write_aces`（线程内本地转 PSID，失败回传 channel 计入 `refresh_errors`）；refresh 模式下任何 error 都使整体 bail；
6. deny-write 路径若不存在则**预建哨兵目录**再挂 deny ACE（防沙箱事后在可写父目录下自建同名绕过 carveout）；
7. 最后 `lock_sandbox_bin_dir`（refresh 也执行）与 `lock_persistent_sandbox_dirs`（仅非 refresh）。

`lock_sandbox_dir`（setup_provisioning.rs:281）统一经 `SetEntriesInAclW`（EXPLICIT_ACCESS_W×4：组/SYSTEM/Administrators/real_user，`OBJECT_INHERIT_ACE|CONTAINER_INHERIT_ACE`）构造 DACL；ProvisionOnly 模式对目标目录用 no-reparse 句柄 `SetSecurityInfo(READ_CONTROL|WRITE_DAC)`，其余按名 `SetNamedSecurityInfoW`。三个目录矩阵：

| 目录 | 组(CodexSandboxUsers) ACE | real_user 掩码 | DACL 继承 |
|---|---|---|---|
| `.sandbox` | GRANT，RWX+DELETE | RWX（无 DELETE） | Inherited |
| `.sandbox-secrets` | **DENY**，RWX+DELETE | RWX | Inherited |
| `.sandbox-bin`（仅 Legacy） | GRANT，R+X | RWX+DELETE+**WRITE_DAC** | **Protected**（owner 不提权也能重刷此 DACL） |

注册 runtime 时跳过 `.sandbox-bin` 锁定；`lock_persistent_sandbox_dirs` 顺带删除 legacy 的 `.sandbox/sandbox_users.json` 残留。

## 5.3 卸载（`src/uninstall_windows.rs` + 子模块）

- 顶层 `clean_up_packaged_windows_sandbox(codex_home, report, clean_up_desktop)` = `prepare` + `finish` 两阶段。
- **阶段一 prepare**（`prepare_packaged_windows_sandbox_cleanup_with_retained_tokens`）：
  1. `acquire_sandbox_setup_lock(5_000ms)`（短超时，setup 正在跑则报错退出）；
  2. `principals::DisabledSandboxUsers::disable()`：对 `CodexSandboxOffline/Online` 记录 `original_flags`+SID 后 `set_local_user_flags(flags | UF_ACCOUNTDISABLE)`（经 NetUserSetInfo level 1 flags）——禁登录防新沙箱进程；
  3. `retained_logons::RetainedLogons::capture(tokens,…)`：仅保留调用方显式传入的**私有清理令牌**（≤2 个），经 `GetTokenInformation(TokenStatistics)` 提取 `AuthenticationId` 与 user SID 钉住身份；`contains_sid` 供后续判定某账户是否延后删除。runner 令牌一律不保留；
  4. `processes::stop`：枚举两账户名下进程句柄（按 token user SID 匹配、排除 retained），`TerminateProcess(1)`，5 秒 deadline 内 `WaitForSingleObject` 等待退出；
  5. 任一步失败 → `users.restore()` 还原原 flags（此时未移除任何保护，可安全还原），错误拼接返回。
- **阶段二 finish**（持有锁与禁用态）：`validate_current`（账户 SID 未变且仍禁用，防中途被替换/复用）→ `release_setup_log` → 删 `.sandbox` / `.sandbox-secrets` / `.sandbox-bin` 三目录（NotFound 视作已清）→ 回调 `clean_up_desktop`（在账户仍禁用、目录句柄仍钉住时清理用户桌面文件）→ `wfp::remove_wfp_filters()`（事务内删全部 filter + sublayer + provider，容 NOT_FOUND）→ `uninstall_windows/firewall.rs::cleanup_firewall_rules`（按 5 个固定规则名 `INetFwRules::Remove`，不碰无关规则）→ `hide_users::unhide_sandbox_users`（删 UserList 注册表值）→ `users.remove_users`（`DeleteProfileW` 删 profile + `NetUserDel`，retained SID 对应账户**延后**给 finalizer）→ 全部成功且无 retained 账户时 `principals::remove_sandbox_principal("CodexSandboxUsers")`（`NetLocalGroupDel`，容 NERR_GroupNotFound）。每步独立容错、错误聚合；guard Drop 仅释放锁。
- **removal.ps1**（`service/registered_runtime/removal.ps1`，registered runtime 专用 finalizer）：SYSTEM（S-1-5-18）强校验；stdin 读 JSON 计划（retiring 代际 + targets[{handle,sid,username}] ≤2）；对每个 target `LoadUserProfile` 后输出 `READY`，等 `COMMIT` + EOF 才动手（否则只卸载 profile）；循环内重建 `Global\CodexSandboxSetup` 互斥（同 SDDL）、复验 HKLM 记录四元组（retiring/user_sid/codex_home/package_family）未变；按 retained 用户令牌 `ImpersonateLoggedOnUser` 后 `PackageManager.RemovePackageAsync`（WinRT 异步转同步等待）逐用户注销注册运行时包；确认无注册残留后 `UnloadUserProfile` + `DeleteProfile` + `DeleteUser`（NetUserGetInfo level 23 复验 SID 与禁用位，`NetUserDel`）+ `NetLocalGroupDel("CodexSandboxUsers")`；删除 legacy 父键值与 RegisteredCore 子键；失败记事件日志（`RegisterEventSource`/`ReportEvent`，id 3003/3004）并 30s 重试，最后若 owner 重装了包则重启服务。启用 `SeBackupPrivilege/SeRestorePrivilege`。
- **两阶段拆分的动机**（`PreparedWindowsSandboxCleanup` 文档注释）：prepare 结束到 finish 之间是"账户禁用 + 进程已停 + 锁仍持有"的安全静止态——此时 drop guard 只丢锁、不回滚也不推进；finish 每步独立容错（单步失败不阻断其余清理），且重试必须复用**原账户身份**（`validate_current` 防"禁用后被同名新账户顶替再误删"）。资源删除顺序保证：先停进程、后删保护（WFP/防火墙），中途失败也不会出现"账户可登录但无网络限制"的窗口——除非账户仍禁用，保护永不先于禁用解除。

## 5.4 运行时注册（`src/app_package.rs`、`src/runtime_ownership.rs`）

- `enum SetupRuntime { Legacy(default), Registered }`（runtime_ownership.rs:20）：Legacy = 物化 `.sandbox-bin` helper（拷贝并继承沙箱 ACL）；Registered = 只信任**OS 包身份**（MSIX/AppX 内的 Core），不从目录/清单文本推断权威。`current_setup_runtime()` ← `app_package::registered_core_requested()`（进程启动时读一次 `CODEX_WINDOWS_REGISTERED_CORE=1`，OnceLock；该 flag 本身不构成授权）。
- 包身份链（app_package.rs）：`GetPackageFullName(GetCurrentProcess())`（`APPMODEL_ERROR_NO_PACKAGE` → 无身份）→ `GetStagedPackagePathByFullName` 解析 OS 暂存目录 → `verify_registered_core_runner` 复核 runner 进程包名、staged 路径下 `app\resources\codex-command-runner.exe` 与 `QueryFullProcessImageNameW` 实际镜像三者一致（防 alias 被劫持到别的包）。别名常量 `APP_CORE_RUNNER_ALIAS = "codex-core-command-runner.exe"`。
- HKLM 安装记录（runtime_ownership.rs + `installation_record.rs`）：legacy 键 `SOFTWARE\OpenAI\Codex\WindowsSandboxService` / 值 `ProvisionedInstallation`（REG_SZ JSON，≤4096 UTF-16 单位，包升级不覆盖服务键）；registered 专键 `SOFTWARE\OpenAI\Codex\WindowsSandboxService\RegisteredCore` **子键**（老服务卸载整键删除时子键使其失败，保住 Core 状态）。`InstallationRecord{user_sid, codex_home, session_id, desktop_installation, runtime:Option<RuntimeRegistration>}`；`save_installation` 按 `runtime.is_some()` 选键，写后 `RegFlushKey` 强制落盘；`remove_installation` 仅允许退役 legacy 记录（registered 归 service-exit finalizer），`RegDeleteKeyValueW` + `SHDeleteEmptyKeyW` 清扫空键。
- `RuntimeRegistration{package_family, accounts:Vec<RuntimeAccountRegistration{account(offline/online), user_sid, alias_path, cleanup_logon_pending}>, metadata_roots, ready_package, retiring}`：`ready_for_package` = retiring 为空 ∧ 双账户齐全互异 ∧ alias 就绪 ∧ 无 pending 清理登录，且 `ready_package` 精确匹配当前包全名；`admit_owner` 校验 user_sid+codex_home+package_family 一致且无未竟清理。`registered_setup_is_ready` 组合检查：两账户未禁用 ∧ 有包身份 ∧ 记录匹配。readiness 消费（`registered_runner_alias`）逐项复核 owner SID、codex_home、retiring、alias 绝对路径与文件名后才返回 runner 路径。
- Registered 的 setup/刷新一律经认证 provisioning 服务（5.1.2、`provisioning_client`），helper 命令行路径在 `real_main`/`run_elevated_setup_inner` 被显式拒绝。
- 服务侧事务（`service/provisioning/registered.rs::run`，模块头注释即 "admit, provision, register, then publish readiness"）：持 `acquire_sandbox_setup_lock(5_000)` → 校验 `CodexSandboxUsers` 组 SID 未被替换（`SANDBOX_GROUP_CHANGED`）→ `setup_is_complete` 判定 → `refresh_only` 请求只允许更新注册/元数据，**绝不**建账户或修复 teardown（"Startup may update registrations, but must never create accounts"）→ 非 refresh 走 `on_authenticated_user` 回调完成 provision + 注册，写 `RuntimeRegistration`（先 `retiring`/`cleanup_logon_pending` 等围栏字段、后 `ready_package` 发布就绪）→ 凭据失配（`SandboxAccountCredentialMismatch`）按白名单升级为完整 setup 修复（`service/provisioning/registered_tests.rs` 覆盖）。

## 5.5 安全边界总结

**防什么（threat model）**
| 威胁 | 机制 | 出处 |
|---|---|---|
| 写越界（沙箱进程改系统/用户文件） | 账户级 DACL：仅对 write roots 授 allow-ACE（按 capability SID 精确到根）；deny-write ACE 保护 `.git/.codex/.agents` 等敏感子路径（缺目录则预建哨兵目录再挂 deny）；`filter_sensitive_write_roots` 剥离 codex_home/.sandbox、`USERPROFILE_ROOT_EXCLUSIONS`（.ssh/.aws/.gnupg…）、ssh_config Include 依赖 | setup.rs `effective_write_roots_for_permissions`、`build_payload_deny_write_paths`；setup_provisioning.rs `run_setup_full` |
| 读越界（离线策略读隐私） | deny-read ACE 持久化 + walker 对账；ReadAclsOnly 子进程只授 `FILE_GENERIC_READ|EXECUTE`；读根收敛（symbolic full-read 才放开 USERPROFILE/平台默认根） | `deny_read_*.rs`、`apply_read_acls` |
| 网络（offline 外联） | 防火墙 4 规则按 SID block 非环回归环 + WFP persistent 双向 filter（事务、fail-closed 安装顺序、LocalPolicyModifyState 自检） | 5.2.3 |
| 进程树逃逸 | JobObject 约束 + runner 管道生命周期 + `CREATE_NO_WINDOW`；卸载时按账户枚举杀进程 | process.rs、uninstall_windows/processes.rs |
| 句柄泄漏 / 权限句柄外流 | ProvisionOnly 用 no-reparse 目录句柄改 ACL（`SetSecurityInfo` 而非按名）；retained handle 用 `DuplicateHandle` 显式注入 `CREATE_SUSPENDED` 子进程、失败即 kill；marker `CREATE_NEW`+share 0 | no_reparse_dir.rs、setup_launch.rs、5.2.4 |
| 跨登录会话串扰 | 沙箱账户独立 logon session（CreateProcessWithLogonW）；私有 desktop 复用须同账户同权限；token.rs 保证子进程/IPC 留在 runner logon session 内 | desktop.rs、token.rs |
| IPC 伪造 / 未授权 setup | provisioning 管道认证 + machine policy 校验；`Global\CodexSandboxSetup` 互斥 SDDL 仅 SY/BA；registered 记录四元组对账；`real_user` 取自进程 token 而非 USERNAME 环境变量 | service/ipc/*、runtime_ownership.rs `current_setup_user` |
| 并发 setup 竞态（多 CLI 同时首装/刷新） | 进程内 singleflight（b64 payload 为 key 合并等价请求）+ 跨进程 `Global\CodexSandboxSetup` 互斥 + marker `CREATE_NEW`（第二次创建必败）+ `SETUP_VERSION=5` 版本闸门拒绝过期 payload | 5.1.4、real_main 版本校验 |
| 卸载中途账户顶替（同名新账户冒充） | `DisabledSandboxUsers::validate_current`（SID 不变且仍禁用）；removal.ps1 `DeleteUser` 前以 NetUserGetInfo level 23 复验 SID+禁用位；重试"不得收养替换账户"（finish 文档注释） | uninstall_windows.rs、removal.ps1 |
| 中断的 setup/清理残留半成品 | 检测禁用账户 → 新建账户同样带 `UF_ACCOUNTDISABLE`，WFP/防火墙恢复成功后才解禁；marker 空 sentinel 使 readiness 恒假 | provision_sandbox、5.2.4 |

**不防什么（明确边界）**
- **内核级**：ACL/WFP 全在用户态系统服务层，rootkit/内核驱动可绕过；沙箱账户本身是普通本地用户。
- **online 账户网络全通**：防火墙/WFP 仅绑定 offline SID；`SandboxNetworkIdentity::Online`（网络策略启用且未强制代理）无任何网络规则。
- **legacy（unelevated restricted-token）后端**：只降写权限，不隔离读、不隔离网络（Windows 沙箱 README 明确 legacy 语义）。
- **同机管理员/同机用户**：任何管理员可改 ACL、删防火墙规则；`CRYPTPROTECT_LOCAL_MACHINE` 使本机任意账户可解 sandbox_users.json（加密只防"把文件拷走离线读"）。
- **宿主共享资源**：沙箱账户对 `%ProgramFiles%`、`C:\Windows` 等保有只读（默认读根），恶意代码仍可读取其中内容（这正是"可读"授权本身）。

**三平台机制对照**
| 维度 | Linux（Landlock/bwrap+seccomp） | macOS（Seatbelt sbpl） | Windows Codex |
|---|---|---|---|
| 载体 | 进程内 LSM 规则 + bubblewrap 命名空间 | 每进程声明式 profile（sandbox-exec） | 机器级本地账户 + 文件 DACL + WFP/防火墙 |
| 提权需求 | 无（unprivileged Landlock） | 无 | 首次 setup 需管理员（UAC "runas"），之后 refresh 不提权 |
| 授权粒度 | 每 path 的 R/W/EXEC 位图 | sbpl 规则（file-read*/file-write*、network） | allow/deny ACE 按 capability SID 挂在对象上 |
| 网络控制 | seccomp/netns（managed proxy） | network policy sbpl | 防火墙 COM 规则 + WFP filter（仅 offline） |
| 持久性 | 进程生命周期 | 进程生命周期 | 账户/ACL/规则**持久**，须显式卸载清理 |
| 崩溃语义 | 随进程消失 | 随进程消失 | 中断留下禁用账户 → setup 检测修复后再解禁 |

## 5.6 Java（JNA）实现要点清单

### 5.6.1 按 DLL 分组的 Win32 API 清单
- **kernel32**：`CreateMutexW/ReleaseMutex/WaitForSingleObject`（Global 锁）、`CreateFileW`（marker CREATE_NEW、no-reparse 打开须 `FILE_FLAG_OPEN_REPARSE_POINT`）、`GetFileAttributesW/SetFileAttributesW`（HIDDEN|SYSTEM）、`CloseHandle/LocalFree`（释放 SID/ACL/SD/blob）、`DuplicateHandle/GetCurrentProcess`、`GetExitCodeProcess`、`QueryFullProcessImageNameW`、`GetLastError`（JNA `Native.getLastError()`，须 `W32APIOptions`）。
- **advapi32**：`AllocateAndInitializeSid/FreeSid/CheckTokenMembership`（提权判定）、`ConvertStringSidToSidW/ConvertSidToStringSidW`、`LookupAccountNameW/LookupAccountSidW`（双段式 buffer 预探）、`SetEntriesInAclW + SetNamedSecurityInfoW/SetSecurityInfo`（EXPLICIT_ACCESS_W/TRUSTEE_W，`TRUSTEE_IS_SID`）、`ConvertStringSecurityDescriptorToSecurityDescriptorW`（SDDL → SECURITY_ATTRIBUTES）、`BuildExplicitAccessWithNameW/BuildSecurityDescriptorW`（WFP 用户条件 SD）、`CryptProtectData/CryptUnprotectData`（dpapi 实际在 crypt32.dll）、`OpenProcessToken/GetTokenInformation`（real_user 取 token user SID）、`LogonUserW`、`CreateProcessWithLogonW`、`AdjustTokenPrivileges/LookupPrivilegeValue`；注册表 `RegCreateKeyExW/RegOpenKeyExW/RegSetValueExW/RegDeleteValueW/RegQueryValueExW/RegFlushKey/RegCloseKey`（`SHDeleteEmptyKeyW` 在 shlwapi）。
- **netapi32**：`NetLocalGroupAdd`(LOCALGROUP_INFO_1)、`NetLocalGroupAddMembers`/`NetLocalGroupDel`、`NetUserAdd`(USER_INFO_1)、`NetUserSetInfo`(level 1003 密码 / level 1 flags)、`NetUserGetInfo`(level 1 flags / level 23 uninstall 校验)、`NetUserDel`、`NetApiBufferFree`（所有 Net* 输出必须释放）。
- **ole32 + oleaut32**：`CoInitializeEx(COINIT_APARTMENTTHREADED)`（容忍 RPC_E_CHANGED_MODE=0x80010106）、`CoCreateInstance`（CLSID_NetFwPolicy2 / CLSID_NetFwRule，IID_INetFwPolicy2 / IID_INetFwRule3——常量值取自 SDK `netfw.h`，勿手抄以免笔误）、`CoUninitialize`；`SysAllocStringLen`（BSTR）。
- **fwpuclnt**（WFP）：`FwpmEngineOpen0/FwpmEngineClose0`（FWPM_SESSION0）、`FwpmTransactionBegin0/FwpmTransactionCommit0/FwpmTransactionAbort0`、`FwpmProviderAdd0/FwpmProviderDeleteByKey0`、`FwpmSubLayerAdd0/FwpmSubLayerDeleteByKey0`、`FwpmFilterAdd0/FwpmFilterDeleteByKey0`（FWPM_FILTER0 含 union 与 GUID 数组，需手工 `Structure` 映射）。
- **shell32/user32**：`ShellExecuteExW`（SHELLEXECUTEINFOW，"runas" 提权）；窗口隐藏靠 `nShow=SW_HIDE`。
- **userenv**：`DeleteProfileW`（卸载）；`LoadUserProfile/UnloadUserProfile` 仅 removal 路径需要。

### 5.6.2 JNA 调用难点
1. **防火墙 COM**：`INetFwPolicy2/INetFwRule3` 是 IDispatch 双接口，JNA 无内建 COM 支持。选项：(a) Java 22+ FFM + jextract 预生成绑定（最贴近原实现）；(b) 回退 `netsh advfirewall firewall` 子命令（无法表达 `LocalUserAuthorizedList` 的 SDDL 与 `LocalPolicyModifyState` 自检——安全语义降级，不建议）；(c) 仅这一个环节写小段 JNI/Panama 调用 COM。BSTR/variant 内存需 `SysFreeString`/`VariantClear`。
2. **WFP 事务**：fwpuclnt 无类型库，结构体（FWPM_FILTER0 的 union、FWP_VALUE0/FWP_CONDITION_VALUE0 tagged union）须手写 JNA `Structure` + union；事务三段必须 try/finally 包裹（Abort 兜底）；`FWP_E_*` HRESULT 容错表照抄 wfp.rs。
3. **DPAPI**：API 本身简单（crypt32 `CryptProtectData`），难点是 scope 语义——必须 `CRYPTPROTECT_LOCAL_MACHINE` 才能让 elevated helper 加密、非 elevated runner 解密；blob 输出用 `LocalFree` 释放。
4. **CreateProcessWithLogonW**：需**明文密码 UTF-16**（从 DPAPI 解出的 bytes 直接 `new String(…, UTF_16LE)`，不要经默认 charset）；无 LOGON_WITH_PROFILE 时账户无 profile（execution alias 需要 profile 才可用）；每次调用产生独立 logon session，管道句柄不可跨 session 直接继承——参照 runner_pipe 用 named pipe 而非匿名管道；错误码 1326（密码失配）触发凭据修复路径（identity.rs 分类）。
5. **ShellExecuteExW "runas"**：在无消息循环线程必须 `SEE_MASK_NOASYNC`，否则可能挂起；`ERROR_CANCELLED=1223` 要单列成"用户拒绝"错误而非失败；UAC 弹窗体验要求主线程 STA（或保持 console 进程默认行为即可）。
6. **字符串/内存通用**：全部 wide (`char[]`/`WString`)；`NetApiBufferFree` 与 `LocalFree` 不可混用；`NERR_*` 非 Win32 错误码，不走 GetLastError。
7. **命令行长度**：Windows 命令行上限 32,767 个 UTF-16 字符——这正是 `launch_environment` 24,000 阈值 + 环境分块存在的原因。Java 版若用 `ProcessBuilder`，大 payload 必须实现同样的 `--launch-payload-env` 约定（helper 端从 `System.getenv()` 重组），且分块环境变量单值别超 32 KiB。
8. **JNA 结构体映射**：`FWPM_FILTER0/FWP_VALUE0` 含 union 与嵌套指针数组，须 `Structure.ByReference` + `setAutoSynch(false)` 手动读写并复用 `Memory`；GUID 是 16 字节顺序布局（data1 u32LE、data2/data3 u16LE、data4[8]）；`USER_INFO_1` 等含 `*mut u16` 指针字段，须先钉住 Java 侧 `char[]` 内存再取地址，调用期间不可让 GC 移动（JNA 的 `Memory` 天然固定）。

### 5.6.3 可简化 / 暂缓项
- **registered runtime（AppX/服务）**：`SetupRuntime::Registered`、provisioning service、removal.ps1 全链条依赖 MSIX 打包渠道，Java 版首期只做 **Legacy**：`run_setup_refresh`/`run_elevated_setup` + 单 helper 进程即可闭环；`runtime` 字段保持缺省 Legacy 以兼容 payload。
- **windows-sandbox-service（SCM 常驻服务）**：暂缓——用"按需提权 helper"替代（`runas` 一次 UAC + marker 缓存），refresh 走不提权路径。
- **ConPTY**：首期用 pipe（stdio bridge）替代 PTY；交互式 TTY 需求出现再引入 ConPTY（conpty/mod.rs）。
- **环境分块传输**：payload 常小于 24k UTF-16 时可只实现 argv 单参数 + `quote_arg`；分块（environment_transport）等根列表膨胀后再补。
- **deny-read walker 对账**（`deny_read_walker.rs`/`deny_read_state.rs`）：二期再做——首期只做 setup 时一次性 `sync_persistent_deny_read_acls`；对账解决的是"用户手动删了 deny ACE"的漂移，可在 refresh 中按需补。
- **隐藏账户注册表键**：保真实现（一次 `RegSetValueExW`），成本极低不建议砍——否则登录界面出现两个 `CodexSandbox*` 账户，观感即"侵入"。
- **elevated runner named-pipe IPC**：可简化为"helper 直接 spawn 沙箱子进程 + stdio 桥"；保留 `Global\CodexSandboxSetup` 互斥与 singleflight（Java 用 `ConcurrentHashMap<String,CompletableFuture>` 等价实现）。
- **不可简化的不变量**：① marker `CREATE_NEW` 空 sentinel + 两阶段提交；② 防火墙先宽 block 后删 allow 的 fail-closed 顺序 + LocalPolicyModifyState 自检 + SID 回读；③ WFP 事务包裹 + 固定 GUID；④ 中断修复路径（禁用账户在 WFP 恢复成功前不解禁）；⑤ 卸载两阶段（禁用→停进程→删资源）与 flags 回滚。

### 5.6.4 建议模块划分与落地顺序（对应 Rust 源）
| Java 模块（建议） | 职责 | 对应 Rust |
|---|---|---|
| `win.Kernel32Ex / Advapi32Ex / NetApi32Ex / Fwpuclnt` | JNA interface + 结构体 + 错误码 | 各 win32 封装 |
| `sandbox.SetupOrchestrator` | payload 组装、is_elevated、runas 启动、singleflight、错误报告读写 | setup.rs |
| `sandbox.SetupHelper`（独立可执行或 `--setup-payload` 子模式） | real_main：锁、四模式分派、marker 两阶段 | setup_provisioning.rs |
| `sandbox.AccountProvisioner` | 建组/建户/密码回退/DPAPI 凭据/隐藏账户 | sandbox_users.rs、hide_users.rs、dpapi.rs |
| `sandbox.FirewallInstaller` / `sandbox.WfpInstaller` | 规则族 + 有效性自检 / provider+sublayer+filter 事务 | setup_provisioning/firewall.rs、wfp.rs |
| `sandbox.AclComposer` | EXPLICIT_ACCESS/SDDL/allow/deny ACE、cap SID | acl.rs、lock_sandbox_dir、cap.rs |
| `sandbox.Uninstaller` | prepare/finish 两阶段 + retained token | uninstall_windows.rs |
| `sandbox.InstallationRecordStore` | HKLM 记录读写 flush（首期仅 legacy 键） | runtime_ownership.rs、installation_record.rs |
- 落地顺序建议：① AccountProvisioner + SetupOrchestrator（能跑通 UAC→建户→marker）；② AclComposer + run_setup_full 等价物；③ FirewallInstaller；④ WfpInstaller（可放最后，防火墙已挡大部分流量，WFP 是 GPO 冲突时的第二道防线）；⑤ Uninstaller；⑥ InstallationRecordStore。每步都以 `sandbox_smoketests.py` 的等价断言做回归。

### 5.6.5 Java 版验收清单（对齐 `sandbox_smoketests.py` 的断言面）
- 写越界：工作区内目标文件写入成功（`assert_exists`）、工作区外/只读根写入失败且文件不存在；额外 writable root 生效、非列表内 root 拒绝。
- 读越界：deny-read 路径（.env/密钥类）读取失败；symbolic read-only 策略下平台默认根可读。
- junction/symlink 不穿越（smoketests 专门构造 junction/symlink 用例）。
- 网络：offline 账户非环回出站被 block、仅代理端口环回可达（脚本内置 loopback proxy fixture，`start_loopback_proxy_fixture`）。
- setup 幂等：连续两次 setup 第二次走 marker 短路；refresh 不弹 UAC（无 `runas` verb）。
- 卸载后：三目录、5 条防火墙规则、WFP provider/sublayer/filter、UserList 注册表值、两账户与组全部消失且无关规则/账户不受影响。


