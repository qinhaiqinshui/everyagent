# Codex Windows 原生沙箱机制深度分析

> 源码版本：openai/codex main@`92bc601ad60542c92bf0bb1e7a2eb70b84ac49d2`
> 核心 crate：`codex-rs/windows-sandbox-rs`（117 .rs）、`codex-rs/windows-sandbox-service`（29 .rs）
> 文件清单见 `.everyagent/tmp/codex/FILES.md`；本文由 5 个分片合并（parts/01~05）。
> 面向 Java/JNA 实现工程师，所有结论均引自实际源码并标注 `相对路径 + 函数名`。

## 目录
1. [总体架构、双后端、沙箱账户与 capability SID](parts/01-architecture.md)
2. [受限令牌构造与 ACL/deny-read 体系](parts/02-token-acl.md)
3. [网络隔离、Job Object 进程管理、exec 执行链](parts/03-network-job-exec.md)
4. [IPC 帧协议与 SCM 服务](parts/04-ipc-service.md)
5. [setup/卸载流程、安全边界、Java 实现要点](parts/05-setup-boundary-java.md)

# 第 1 分片:总体架构、双后端模型、沙箱账户与 capability SID

> 基线:openai/codex main@92bc601a。以下路径均相对 `codex-rs/`,行号仅供参考。
> 核心 crate:`windows-sandbox-rs`(crate 名 `codex-windows-sandbox`,117 个 .rs)
> 与配套服务 crate `windows-sandbox-service`(29 个 .rs)。

## 1. crate 结构:lib.rs 双实现与对外入口

`windows-sandbox-rs/src/lib.rs`(1056 行)是唯一门面,约 2/3 篇幅是 `pub use` 再导出。
平台切换靠两个条件编译模块:

- `#[cfg(target_os = "windows")] mod windows_impl`(lib.rs:498 起):真实现,内含一次性捕获 API
  - `run_windows_sandbox_capture` / `run_windows_sandbox_capture_with_filesystem_overrides`
    (在内部直接走 **legacy restricted-token** 路径);
  - `run_windows_sandbox_legacy_preflight`(workspace-write 时提前刷 ACL)。
- `#[cfg(not(target_os = "windows"))] mod stub`(lib.rs:1016 起):
  同名函数直接 `bail!("Windows sandbox is only available on Windows")`,
  保证 crate 可在非 Windows 上编译,`core`/`sandboxing`/`exec-server` 可无条件引用。
- 真正平台无关的导出只有少数:`WindowsSandboxCancellationToken`(谓词式取消令牌)、
  `WindowsSandboxProvisioningSettings`(管理员安装的 proxy_ports/allow_local_binding)、
  `deny_read_resolver::resolve_windows_deny_read_paths`。

对外入口分三类:

1. **一次性捕获(capture,无 PTY)**
   - legacy:`windows_impl::run_windows_sandbox_capture[_with_filesystem_overrides]`;
   - elevated:`elevated_impl.rs::run_windows_sandbox_capture_for_permission_profile`
     (lib.rs 再导出为 `run_windows_sandbox_capture_for_permission_profile_elevated`);
   - 消费方 `core/src/exec.rs:660-740`:按 `windows_sandbox_uses_elevated_backend(sandbox_level)` 二选一,
     在 `spawn_blocking` 中同步执行,收集 stdout/stderr/exit_code;失败经 `record_windows_sandbox_spawn_failure` 记指标。
2. **会话式 spawn(PTY/流式,unified exec)**
   - `unified_exec/mod.rs::spawn_windows_sandbox_session_for_level` —— 分发中枢(见 §3);
   - 直连变体:`spawn_windows_sandbox_session_legacy` / `spawn_windows_sandbox_session_elevated_for_permission_profile`。
3. **内部 wrapper**
   - `wrapper.rs::run_windows_sandbox_wrapper_main`,入口常量 `CODEX_WINDOWS_SANDBOX_ARG1 = "--run-as-windows-sandbox"`:
     给 direct-spawn 调用方一个 argv 形态的启动器,等价于 macOS seatbelt / Linux sandbox wrapper 路径
     (从 argv/launch environment 解析沙箱元数据,启动内层命令并转发 stdio)。

`unified_exec/` 的组织(mod.rs 头注释明说):`mod.rs` 只是"thin orchestration layer";

- `backends/legacy.rs`(489 行)把直接受限令牌 spawn 适配成 `codex_utils_pty::SpawnedProcess`;
- `backends/elevated.rs`(282 行)把 elevated command-runner IPC 适配成同一会话 API;
- `backends/windows_common.rs` 存两后端共用的小工具(`finish_driver_spawn`、runner pipe 读写线程等)。

- 其余子系统模块(deny-read ACL、WFP、ConPTY、desktop、setup、provisioning、elevated IPC 等)
  均为 `#[cfg(windows)]` 私有模块 + 选择性 `pub use`,不泄漏内部类型。

## 2. 与上游集成

### 2.1 `sandboxing/src/windows.rs`(401 行)— 文件系统覆盖解析层

核心类型:

```rust
pub struct WindowsSandboxFilesystemOverrides {
    read_roots_override: Option<Vec<PathBuf>>,
    read_roots_include_platform_defaults: bool,
    write_roots_override: Option<Vec<PathBuf>>,
    additional_deny_read_paths: Vec<AbsolutePathBuf>,
    additional_deny_write_paths: Vec<AbsolutePathBuf>,
}
```

关键函数:

- `windows_sandbox_uses_elevated_backend(level) -> bool`(:34,`matches!(level, Elevated)`),
  是 core/exec-server 复用的唯一后端判定;
- `resolve_windows_restricted_token_filesystem_overrides`(:65):legacy 后端只允许"额外 deny-write 刻画"。
  出现以下任一情形直接 `Err("... refusing to run unsandboxed")`:
  根读受限(`windows_policy_has_root_read_access` 为假)、deny-read 非空、
  split 可写根集合 ≠ legacy 投影集合、"在只读刻蚀下重开可写后代"(`has_reopened_writable_descendant`);
- `resolve_windows_elevated_filesystem_overrides`(:216):elevated 后端可承载完整 split policy ——
  显式 `read_roots_override`(仅当根读不可用时给出)、`write_roots_override`(仅当 split 根集合 ≠ legacy 投影)、
  deny-read 集合(委托 `codex_windows_sandbox::resolve_windows_deny_read_paths`)、deny-write 集合;
- 两边都先 `remove_skip_missing_path_entries()`,防止把 skip-missing 条目变成新建的 deny-write 哨兵;
- `unsupported_windows_restricted_token_sandbox_reason`(:46)是 readiness 检查的封装,按 level 二选一取 `.err()`。

### 2.2 core 层配置(`core/src/windows_sandbox.rs`,456 行)

- `WindowsSandboxLevelExt::{from_config, from_features}`(:61-79):
  `permissions.windows_sandbox_mode`(`WindowsSandboxModeToml::Elevated|Unelevated|Mxc`)映射为
  `WindowsSandboxLevel::{Elevated|RestrictedToken|Disabled}`;toml 缺省时回退 feature flag:
  `Feature::WindowsSandboxElevated` → Elevated,`Feature::WindowsSandbox` → RestrictedToken,否则 Disabled。
- `resolve_windows_sandbox_mode` / `legacy_windows_sandbox_mode[_from_entries]`(:83-121)
  处理旧版 `enable_experimental-windows-sandbox` 等兼容读取。
- `windows_sandbox_level_for_legacy_checks`(:46)把 `SandboxType::WindowsMxc` 归一为 RestrictedToken,
  供只认 setup 级别的旧检查使用。
- setup 侧:`run_windows_sandbox_setup(WindowsSandboxSetupRequest)`(:286,模式见 `WindowsSandboxSetupMode`)、
  `sandbox_setup_is_complete` 的平台条件转发、`prepare_elevated_sandbox`/`run_legacy_setup_preflight` 等;
  `core/src/windows_sandbox_read_grants.rs` 负责读授权,`core/src/config/windows_sandbox_config.rs` 是 Config 侧定义。

### 2.3 `exec-server/src/process_sandbox.rs`(450 行)— 消费方式(简述)

- `select_sandbox` 得到 `SandboxType::WindowsRestrictedToken` 时(:300-331),
  按 level 二选一调用 §2.1 的两个 resolve 函数,构造
  `PreparedWindowsSandboxRequest { permission_profile, workspace_roots, windows_sandbox_level, proxy_enforced,
  network_proxy_restricting_sid, proxy_settings_mode, filesystem_overrides }`;
- 真正 spawn 不在 exec-server:由 `sandboxing/src/spawn.rs:55-99` 取出 `windows.filesystem_overrides`,
  原样填进 `WindowsSandboxSessionRequest`,调用 `spawn_windows_sandbox_session_for_level`;
- 即 exec-server 只做"参数准备 + 可行性校验",不触碰 `codex-windows-sandbox` 的 spawn 内部。

## 3. 双后端:WindowsSandboxLevel 语义与能力边界

`protocol/src/config_types.rs` 定义三档(kebab-case 序列化):

- **Disabled**(默认):不用原生沙箱;
- **RestrictedToken**(unelevated/legacy):不提权。父进程直接 `CreateRestrictedToken` 派生令牌
  (`token.rs:476-513`,flags = `DISABLE_MAX_PRIVILEGE | LUA_TOKEN | WRITE_RESTRICTED`;
  `spawn_prep.rs::prepare_legacy_session_security` 产出 `h_token` + readonly/write-root SID),
  配 `LaunchDesktop::prepare_legacy` 私有桌面,`process.rs::create_process_as_user` 启动子进程;
- **Elevated**(提权运行器):一次性提权 setup(§4)预建专用本地账户与 ACL/防火墙;
  运行时 `CreateProcessWithLogonW` 以沙箱账户身份启动常驻 command-runner
  (`elevated/runner_client.rs::spawn_runner_transport`),
  父进程 ↔ runner 之间走 named pipe + 长度前缀 JSON 分帧(`elevated/ipc_framed.rs`,
  版本常量 `IPC_PROTOCOL_VERSION`,底层读写复用 `framed_io.rs`),
  由 runner 在沙箱侧再派生令牌并 spawn 真正命令。

**分发逻辑**(`unified_exec/mod.rs::spawn_windows_sandbox_session_with_desktop`,:66-114):

- `matches!(level, Elevated)` → `backends::elevated::spawn_windows_sandbox_session_elevated_for_permission_profile`;
- 否则走 legacy,且 legacy 分支开头即硬性拒绝:
  `bail!("managed networking requires the elevated Windows sandbox backend")`、
  `bail!("network proxy restricting SID requires the elevated Windows sandbox backend")`。

**能力边界差异**(源码中反复声明的硬边界,lib.rs:712-721 与 backends/legacy.rs:343-350):

1. **deny-read / 受限读只能 elevated**:
   WRITE_RESTRICTED 令牌只在**写检查**时参考 restricting SID(含 capability SID),读检查不看 →
   capability SID 上的 deny-read ACE 对 legacy 不可信(注释原话:
   "WRITE_RESTRICTED tokens consult restricting SIDs only for writes")。
   故 legacy 遇到 `additional_deny_read_paths` 非空或 `!has_full_disk_read_access()` 直接
   `bail!("... require the elevated Windows sandbox backend")`,拒绝运行("宁拒不裸"哲学)。
   elevated 后端由 runner 用 `create_readonly_token_with_caps_and_user_from` 派生 LUA 令牌,
   读检查同样受限,deny-read ACL(`deny_read_acl.rs`/`deny_read_state.rs`)才是权威的。
2. **managed network 只能 elevated**:
   代理强制(`proxy_enforced`)与网络代理 restricting SID 需要账户粒度的 WFP/防火墙规则
   (offline 账户专属,§4),受限令牌模型表达不了 —— 这正是双账户存在的原因。
3. **split 文件系统的表达力差异**:
   legacy 只有当 split 可写根集合与 legacy 投影完全一致、只读刻蚀可退化为 deny-write ACL 时才允许(§2.1);
   elevated 可完整表达 read/write roots 覆盖 + deny-read + deny-write。

## 4. 沙箱账户:offline/online 双账户

常量(`setup.rs:52-54`):`SETUP_VERSION = 5`、
`OFFLINE_USERNAME = "CodexSandboxOffline"`、`ONLINE_USERNAME = "CodexSandboxOnline"`。

### 4.1 创建(`setup_provisioning/sandbox_users.rs`)

- `provision_sandbox_users`(:66):先 `ensure_sandbox_users_group`(`winutil.rs`,内部 `NetLocalGroupAdd`,
  组名 `SANDBOX_USERS_GROUP = "CodexSandboxUsers"`),再对两账户各调
  `ensure_sandbox_user` → `ensure_local_user`(:92);
- `ensure_local_user`:`NetUserAdd`(level 1,`USER_PRIV_USER`,
  flags `UF_SCRIPT | UF_DONT_EXPIRE_PASSWD | new_user_flags`);
  账户已存在时改用 `NetUserSetInfo(1003)` **只重置密码**,保留企业策略拥有的账户 flags;
  随后 `NetLocalGroupAddMembers` 加入沙箱组与内建 Users 组(S-1-5-32-545,幂等,失败忽略);
- 密码:`random_password()`(:275)生成 24 字符随机串(字母+数字+符号);
- 修复模式(`setup_provisioning.rs::provision_sandbox`,:704-762):
  若任一账户已被禁用(中断清理的残留),新建账户先带 `UF_ACCOUNTDISABLE`,
  待 `configure_offline_sandbox_network` + `install_wfp_filters` 成功恢复网络限制后再解禁 ——
  防止半修复状态重新放开被封锁的账户。

### 4.2 密码管理与 DPAPI 存储

- `write_secrets`(sandbox_users.rs:314):两密码分别经 `dpapi.rs::protect`
  (`CryptProtectData`,`CRYPTPROTECT_UI_FORBIDDEN | CRYPTPROTECT_LOCAL_MACHINE`;
  注释明示用**机器作用域**使提权与非提权进程都能解密)后 base64,
  写入 `CODEX_HOME/.sandbox-secrets/sandbox_users.json`(`setup.rs::sandbox_secrets_dir`);
- 该目录由 setup 用 DENY_ACE 封死沙箱组访问
  (`setup_provisioning.rs::lock_persistent_sandbox_dirs`,:820;对照 `.sandbox` 目录是 GRANT);
- 读取侧 `identity.rs::decode_password` 反向 `CryptUnprotectData` + base64 解码;
- 运行时验证 `logon_existing_sandbox_account`(`identity.rs:185`,`LogonUserW(LOGON32_LOGON_INTERACTIVE)`);
  `ERROR_LOGON_FAILURE | ERROR_PASSWORD_EXPIRED | ERROR_PASSWORD_MUST_CHANGE` 被
  `account_logon_error`(:230)归类为 `SandboxAccountCredentialMismatch`,
  驱动 `elevated/runner_client.rs::retry_runner_spawn_once` / `identity.rs::refresh_logon_sandbox_creds` 重试。

### 4.3 隐藏账户(`hide_users.rs`)

- `hide_newly_created_users`:在注册表
  `SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon\SpecialAccounts\UserList`
  给每个账户写 0(best-effort,失败只 `log_note`);
- `hide_current_user_profile_dir`:在 command-runner 内(以沙箱用户身份运行时)首次登录产生 profile 后,
  给 profile 目录打 `FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM`;
- `unhide_sandbox_users`:卸载时删除这些注册表值。

### 4.4 offline/online 选择逻辑

- `setup.rs::SandboxNetworkIdentity::{Offline, Online}`,`from_permissions(permissions, proxy_enforced)`(:746):
  `proxy_enforced || !permissions.network_policy().is_enabled()` → **Offline**,否则 **Online**;
- Offline 账户:被 WFP(`wfp_setup.rs::install_wfp_filters`,仅针对 offline 用户安装)
  与防火墙(`setup_provisioning/firewall.rs::ensure_offline_proxy_allowlist` / `ensure_offline_network_blocks`,
  由 `configure_offline_sandbox_network` 调用)全面封锁,
  仅放行管理员安装的 loopback 代理端口(`proxy_ports`/`allow_local_binding`);
- Online 账户:不受上述网络规则约束,用于允许联网的会话;
- 运行时入口 `identity.rs::require_logon_sandbox_creds`(:267)→ `require_sandbox_account_with_setup`(:326):
  marker 缺失/版本不符(`SetupMarker.version_matches`)、代理设置失配(`request_mismatch_reason`)、
  users 文件缺失、账户被禁用或密码过期(检查 `UF_ACCOUNTDISABLE | UF_PASSWORD_EXPIRED`)时触发完整 setup;
  `run_automatic_setup`(:439)优先走常驻服务
  (`provisioning_client::provision_windows_sandbox_via_service`),服务不可用才回退 UAC helper
  (`run_elevated_setup_with_proxy_settings`)。

### 4.5 setup 主事务(`setup_provisioning.rs`)

- `run_setup`(:622)按 `SetupMode` 分派:
  - `ReadAclsOnly`:`run_read_acl_only`,只刷读 ACL(带 `read_acl_mutex` 跨进程互斥);
  - `InteractiveProvision | ProvisionOnly`:`run_provision_only`
    (建账户 + 隐藏 + 网络限制 + 锁 `.sandbox`/`.sandbox-secrets`/`.sandbox-bin`);
  - `Full`:`run_setup_full`(:907)再加同步 deny-read ACL(`sync_persistent_deny_read_acls`,
    注释:deny-read ACE 必须在沙箱命令启动前就位,不走后台 helper)、后台读授权 helper、写根 capability 授权(§5);
- 最后 `commit_setup_marker` 原子提交 `CODEX_HOME/.sandbox/setup_marker.json`;全程经
  `setup_mutex.rs::acquire_sandbox_setup_lock` 与卸载串行化。

## 5. capability SID 模型(`cap.rs`)

`CapSids { workspace, readonly, workspace_by_cwd, writable_root_by_path }`:

- **生成**:`make_random_cap_sid_string()`(:30)用 `SmallRng::from_entropy` 取 4 个 u32,
  拼成**合成 SID** `S-1-5-21-{a}-{b}-{c}-{d}`。它不对应任何真实机器账户,
  只是一串当作 group SID 使用的随机标识:挂进令牌的组/restricting SID 列表后,
  即可在 ACL 上以它为粒度授权或拒止。
- **明确结论:本 crate 全文没有任何 `CreateAppContainerProfile` / AppContainer profile 调用(rg 全源码 0 命中)
  —— capability SID 完全是手工合成,不依赖 AppContainer 机制。**
- **持久化**:`cap_sid_file(codex_home) = codex_home/cap_sid`;
  `load_or_create_cap_sids`(:44)读 JSON,兼容旧版"裸 SID 文本"格式
  (非 `{` 开头则保留为 workspace 值、新造 readonly、重写为 JSON);每次新增键都 `persist_caps` 全量重写。
- **各键语义**(字段文档注释 + 代码):
  - `readonly`:全机共享的只读会话 capability。令牌模式
    `WindowsSandboxTokenMode::ReadOnlyCapability` 时唯一 cap SID 即它(`spawn_prep.rs` readonly 分支);
  - `workspace` 与 `workspace_by_cwd`(键 = `canonical_path_key(cwd)`):
    按工作区隔离的 capability —— 防止一个工作区的沙箱写波及其他工作区,
    并对 `CWD/.codex` 之类做 per-workspace deny 而不永久影响其他工作区;
    `workspace_cap_sid_for_cwd`(:83)惰性创建并持久化;
  - `writable_root_by_path`(键 = canonical root):每个额外可写根一个独立 capability;
    **只有当前仍被允许的根才进 workspace-write 令牌**,旧根的过期 ACL 因此不会扩大后续沙箱的写面;
    `writable_root_cap_sid_for_path`(:102)惰性创建;
    `workspace_write_cap_sid_for_root`(:120)统一入口:root==cwd 用 workspace 键,否则用 writable_root 键;
  - 辅助:`workspace_write_root_contains_path` / `_overlaps_path` / `_specificity`(路径包含/重叠/特异度)。
- **使用方**:
  - setup 侧:`setup_provisioning.rs::run_setup_full` 为每个写根解析 `workspace_write_cap_sid_for_root`,
    把 allow-write ACE 同时授予沙箱组与该 cap SID(:995-1040,`path_write_aces_need_refresh` 判定是否需刷新);
  - spawn 侧:`spawn_prep.rs::prepare_elevated_spawn_context_for_permissions`(:349)加载 caps:
    workspace-write 模式取 `root_capability_sids(...)`(每个活跃写根一个 SID,为空则报错
    "workspace-write sandbox has no writable root capability SIDs"),readonly 模式取 `caps.readonly`,
    并用首个 SID 调 `allow_null_device`(\Device\NUL 放行);
  - runner 侧:`bin/command_runner/win.rs::spawn_ipc_process`(:235-300)把 `req.cap_sids` 逐个
    `ConvertStringSidToSidW`,按 `token_mode_for_permission_profile` 选
    `create_readonly_token_with_caps_and_user_from` 或 `create_workspace_write_token_with_caps_and_user_from`
    (`token.rs`,内部 `CreateRestrictedToken`,`set_default_dacl` 把子进程/IPC 访问留在 runner 的 logon session 内);
  - legacy 侧:同样用这些 cap SID 做会话级 deny-write ACL
    (`spawn_prep.rs::apply_legacy_session_acl_rules`、`root_capability_sids`、`legacy_session_capability_roots`)。

## 6. 沙箱会话生命周期(elevated 为主,legacy 对照)

以 unified exec 会话(`codex_utils_pty::SpawnedProcess`)为例,elevated 时序:

1. **创建/请求准备**:`spawn_windows_sandbox_session_for_level` 收全量 `WindowsSandboxSessionRequest`;
   elevated 后端先 `ResolvedWindowsSandboxPermissions::try_from_permission_profile_for_workspace_roots` 解析权限,
   再 `prepare_elevated_spawn_context_for_permissions`:
   环境规范化(`env.rs`:NUL 设备、非交互 pager、PATH 继承;`inject_git_safe_directory`)、
   计算有效写根、`require_logon_sandbox_creds`(必要时触发 setup/refresh,§4.4)、加载 cap SIDs;
2. **spawn runner**:`backends/elevated.rs::spawn_runner_transport_task`(`spawn_blocking`)→
   `spawn_runner_transport_with_retry`(凭据失配时 `refresh_logon_sandbox_creds` 重试一次):
   - `find_runner_exe`(legacy 物化于 `.sandbox-bin`,或 registered alias);按需建共享私有桌面(`desktop.rs`);
   - 创建一对 named pipe(`create_named_pipe`,DACL 限沙箱账户);
   - `CreateProcessWithLogonW`(域 ".",`CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT`,
     registered alias 时 `LOGON_WITH_PROFILE`)启动 `codex-command-runner.exe --pipe-in=... --pipe-out=...`;
   - **启动握手**:连两根管道(校验对端 PID)→ 发 `SpawnRequest` 帧 → 收 `SpawnReady`;
     任一步失败即 `TerminateProcess` 收尸(runner_client.rs:333-470);
3. **exec**:runner(`bin/command_runner/win.rs`)读 `SpawnRequest`,
   先 `hide_current_user_profile_dir`,再按 token mode 从自身令牌(`get_current_token_for_restriction`)
   派生受限令牌、打开父进程预建的私有桌面(`LaunchDesktop::open_private`),
   `tty=true` 走 ConPTY(`conpty/spawn_conpty_process_as_user`),否则管道 spawn;进程进 JobObject 约束;
   输出以 `Message::Output{stdout|stderr}` 帧流回;父进程接受 stdin/resize/terminate 帧;
4. **exit**:runner 等待根进程/JobObject,`GetExitCodeProcess` 取码(超时被杀则 128+64),
   发最终 `Message::Exit{exit_code}` 帧,`job.preserve_descendants()` 后
   自身 `std::process::exit(exit_code)`(win.rs:677-725);父进程 `finish_driver_spawn` 把管道驱动
   包成 `SpawnedProcess`(wait/kill/resize 面向调用方);
5. **清理**:
   - 会话级:JobObject terminate 收树、管道句柄随 `RunnerTransport` drop 关闭(即 runner 的退出信号);
   - 机器级:`uninstall_windows.rs::prepare_packaged_windows_sandbox_cleanup[_with_retained_tokens]`:
     setup 锁下停沙箱进程与登录会话(`processes.rs` / `retained_logons.rs`,只清自有令牌)、
     移除防火墙/WFP 规则(`uninstall_windows/firewall.rs`)、禁用账户删 profile、
     `unhide_sandbox_users` 删 principal(`principals.rs`);
   - 中断自愈:下次 `require_sandbox_account` 检测到禁用账户 → 触发修复式重 setup(§4.1)。

legacy 路径无 runner:同进程内
`prepare_legacy_spawn_context` → `prepare_legacy_session_security`(直接造 restricted token + cap SIDs)→
`apply_legacy_session_acl_rules`(会话级 allow/deny ACL)→ `LaunchDesktop::prepare_legacy` → `create_process_as_user` + ConPTY(`backends/legacy.rs`,:317)。
一次性 capture 版(lib.rs `windows_impl`)自建三条匿名管道、双线程收流,
`wait_for_process` 支持超时/取消(取消时 JobObject terminate + `TerminateProcess` 兜底)。

## 7. 小结:设计要点

- **"宁拒不裸"**:任何后端表达不了的限制一律返回 `refusing to run unsandboxed` 类错误,绝不降级明文运行;
- **capability SID = 随机合成 S-1-5-21 group SID + 令牌组/restricting 注入 + ACL 授权**,零 AppContainer 依赖;
  按 cwd/写根分键实现工作区隔离与最小授权面(过期根不进令牌);
- **双账户 × 网络身份**:Offline 账户天然断网(WFP+防火墙,仅放行管理员声明的 loopback 代理端口),
  Online 账户联网;选择由 `proxy_enforced`/网络策略按会话决定,凭据 DPAPI(机器作用域)落盘;
- **提权只发生在 setup/provisioning 一次性阶段**(UAC helper 或常驻 SCM 服务),
  运行期 spawn 用已建账户 `CreateProcessWithLogonW`,无需每次弹窗;
- **双后端共用同一 `WindowsSandboxSessionRequest`/`SpawnedProcess` API**,上层(core/exec-server/sandboxing)只依赖 level 与 overrides,不感知后端差异。
# 第 2 分片：受限令牌构造与 ACL/deny-read 体系

> 源码基线：`codex-rs/windows-sandbox-rs/`（main@92bc601a）。本分片覆盖 token.rs / acl.rs / allow.rs / deny_read_* / workspace_acl.rs / setup_provisioning.rs 六个层次，自底向上构成 Windows 沙箱的「受限令牌 + DACL」双闸门。所有引用均为相对 `codex-rs/windows-sandbox-rs/` 的路径。

## 1. 受限令牌构造（`src/token.rs`）

### 1.1 基础令牌获取 — `get_current_token_for_restriction()`

`token.rs` 自行以 `#[link(name = "advapi32")]` extern 声明 `OpenProcessToken`，对 `GetCurrentProcess()` 以如下 desired access 打开进程令牌：

```
TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ASSIGN_PRIMARY
| TOKEN_ADJUST_DEFAULT | TOKEN_ADJUST_SESSIONID | TOKEN_ADJUST_PRIVILEGES
```

——足够完成复制、查组、设 DefaultDacl/会话 ID、调整特权。legacy 后端用它复制**真实用户**令牌；elevated 运行器（`src/bin/command_runner/win.rs::spawn_ipc_process`）用它复制**沙箱账户**令牌。句柄由调用方 `CloseHandle`。

### 1.2 `CreateRestrictedToken` flags（`create_token_with_caps_from`）

常量定义于 `token.rs:42-44`：

| flag | 数值 | 作用 |
|---|---|---|
| `DISABLE_MAX_PRIVILEGE` | `0x01` | 删除令牌全部特权（不逐个枚举） |
| `LUA_TOKEN` | `0x04` | 以 UAC 有限用户语义建令牌 |
| `WRITE_RESTRICTED` | `0x08` | restricting SIDs **只约束写访问** |

三者按位或后调用 `CreateRestrictedToken(base, flags, 0, null, 0, null, n, entries, &mut new)`：`DisableSidCount=0`、`DeletePrivilegeCount=0`（删特权交给 `DISABLE_MAX_PRIVILEGE`，不禁任何组），restricting SIDs 全部由第 7/8 参数传入。

### 1.3 restricting SIDs 的组成与**精确顺序**

`create_token_with_caps_from` 内注释明确顺序：`Capabilities..., ExtraRestricting..., Logon, Everyone`，全部 `SID_AND_ATTRIBUTES{Attributes: 0}`：

1. **capability SIDs**（`psid_capabilities`）：至少 1 个，空则报错 "no capability SIDs provided"。来源：
   - legacy 只读档：`caps.readonly`（`cap.rs::CapSids`，`<codex_home>/cap_sid` 持久化，随机 `S-1-5-21-a-b-c-d`）；
   - legacy 写档：各写根 cap（`spawn_prep.rs::root_capability_sids` → `workspace_write_cap_sid_for_root`）；
   - elevated：runner 请求里的 `req.cap_sids` 列表。
2. **额外 restricting SIDs**：`create_workspace_write_token_with_caps_and_user_from` / `create_readonly_token_with_caps_and_user_from`（token.rs:318/361）→ `create_token_with_caps_user_and_additional_restrictions_from`：先 `token_user::get_user_sid_bytes(base_token)` 取**令牌用户 SID**压入首位（elevated 下即专用沙箱账户，非真实登录用户），再接调用方追加项（如 `network_proxy_restricting_sid`，`command_runner/win.rs`）。
3. **Logon SID**：`get_logon_sid_bytes` 先用 `token_groups` 扫描带 `SE_GROUP_LOGON_ID(0xC0000000)` 属性的组（`token_groups` 带边界校验：revision、子授权数、SID ≤68 字节、`IsValidSid`/`CopySid` 复制出安全缓冲）；找不到再查 `TokenLinkedToken`（class 19）链上令牌，仍无则报 "Logon SID not present on token"。
4. **Everyone**：`world_sid()` 经 `CreateWellKnownSid(WinWorld_SID=1)` 生成 S-1-1-0。

restricting SIDs 落入令牌后即成 deny-only 组：对它们的 **deny ACE 生效、allow ACE 在普通评估中不生效**——这是 deny ACE 挂 capability SID 能否决访问、而 allow-write ACE 只在 `WRITE_RESTRICTED` 写通道放行的机制根基。

### 1.4 `WRITE_RESTRICTED` 语义（只约束写）

带 `0x08` 的受限令牌：读访问只按普通令牌（用户 SID + 启用组）评估，restricting SIDs 不参与 allow 判定；**写访问额外要求 restricting SIDs 的第二趟评估也放行**。由此：

- capability SID 上的 allow-write ACE 只放行写（读仍需普通组授权）；
- capability SID 上的 deny ACE（读/写掩码均可）因 deny-only 组语义直接否决；
- 写被限制在「普通组 allow ∩ cap SID allow」的交集内，读则完全由普通组决定——读写策略解耦。

### 1.5 TokenDefaultDacl — `set_default_dacl(h_token, logon_sid)`

`CreateRestrictedToken` 成功后、恢复特权之前调用。以 `SetEntriesInAclW` 从空 ACL 构造两条 GRANT_ACCESS ACE：

- logon SID → `GENERIC_ALL(0x1000_0000)`：本登录会话全权（创建者显式持有）；
- **OWNER RIGHTS（S-1-3-4，`LocalSid::from_string("S-1-3-4")`）→ 仅 `READ_CONTROL`**。

随后 `SetTokenInformation(h_token, TokenDefaultDacl, ...)` 写回（失败 `LocalFree` DACL 并回传 `GetLastError`）。目的（源码注释）：把子进程/IPC 对象留在 runner 的登录会话内——elevated 运行器即便同账户也各有独立 logon SID；共享账户作为 owner 隐式持有 WRITE_DAC，OWNER RIGHTS ACE 压制该隐式授权，防止另一 logon 会话改写本会话对象的 DACL。capability（文件系统/路由）可跨 launch 共享，但不得借此触碰别的 launch 的进程、线程与 IPC。

### 1.6 SeChangeNotifyPrivilege 恢复 — `enable_single_privilege`

`LookupPrivilegeValueW(null, name, &mut luid)` → 构造 `TOKEN_PRIVILEGES{Attributes: SE_PRIVILEGE_ENABLED(0x2)}` → `AdjustTokenPrivileges`；`GetLastError()!=0` 同样视为失败。恢复旁路遍历（bypass traverse checking）特权，保证受限进程的文件系统导航性能与可用性。`set_default_dacl` 与本调用以 `and_then` 串联，任一失败 `CloseHandle(new_token)` 后整体报错——半成品令牌绝不外泄。

## 2. 读隔离原理（elevated 后端如何实现默认拒读）

elevated 后端不靠 restricting SIDs 管读，而是「账户基线 + 组授权 + 组 deny」三段式：

1. **沙箱账户基线无权限**：进程跑在专用本地账户 `CodexSandboxOffline/Online` 上（`setup_provisioning/sandbox_users.rs::ensure_local_user`：`NetUserAdd` USER_PRIV_USER + UF_SCRIPT|UF_DONT_EXPIRE_PASSWD，仅加入 Users 组与 `CodexSandboxUsers` 组，`ensure_local_group_member`）。真实用户目录与私有数据上没有任何 ACE 授予该账户/该组 → DACL 默认拒绝读。
2. **CodexSandboxUsers 组授予读**：`setup_provisioning.rs::apply_read_acls`（在 ReadAclsOnly 子进程 `run_read_acl_only` 中执行）对每个 read root：
   - 先 `read_mask_allows_or_log(root, subjects.rx_psids, ...)` 检查内建主体（Users / Authenticated Users / Everyone，`require_all_bits=true`）是否已持完整 RX——已持有则系统本就放行，跳过；
   - 再查组 SID `sandbox_group_psid` 是否已有；
   - 都没有才 `ensure_allow_mask_aces_with_inheritance(root, &[组PSID], FILE_GENERIC_READ|FILE_GENERIC_EXECUTE, OBJECT_INHERIT_ACE|CONTAINER_INHERIT_ACE)` 落组 allow（SET_ACCESS）。
   读根集合来自 `setup.rs::gather_read_roots`：`:root` 符号根展开、helper bin 目录、`WINDOWS_PLATFORM_DEFAULT_READ_ROOTS`（C:\Windows、C:\Program Files、C:\Program Files (x86)、C:\ProgramData）、策略 readable roots。
3. **deny-read ACE 挂在组 SID 上**：`run_setup_full` 以组 SID 字符串为主键调 `sync_persistent_deny_read_acls`（§5.3）。组 SID 是令牌普通启用组，DACL 求值 deny 先于 allow，一条组 deny-read ACE 即压过第 2 步的组 allow。
4. **capability SID allow-ACE 只放行写**：write root 上 `ensure_allow_write_aces(root, &[组PSID, capPSID])` 同时给组与根 cap 落 allow；由 §1.4，cap SID 的 allow 只贡献写通道，读通道仍完全由第 1/2/3 条规则决定。
5. **前置校验**：`resolved_permissions.rs::validate_elevated_filesystem_policy` 要求策略对 `:root` 有效可读且根未被 deny（否则 bail "elevated Windows sandbox requires effective `:root` read access"）——避免组 deny 与根授权组合出锁死机器的状态。

legacy 后端（`spawn_prep.rs::prepare_legacy_session_security` + `apply_legacy_session_acl_rules`）走真实用户令牌：读靠用户自身 ACE（真实用户本就能读自己的文件）：

- `allow` 路径 → readonly 档 `add_allow_ace`（readonly cap SET_ACCESS GENERIC R/W/E），写档按 `matching_root_capability`（包含该路径的写根中 `workspace_write_root_specificity` 最深者）选根 cap `ensure_allow_write_aces`；
- `deny` 路径 → `deny_root_capabilities_for_path`（`workspace_write_root_overlaps_path` 双向包含；无命中回退全部根）逐 cap `add_deny_write_ace`；
- deny-read 同步到 readonly cap SID 或逐写根 cap SID（`spawn_prep.rs:311-326`）——cap SID 是 deny-only 组，deny-read ACE 同样对读生效。

## 3. ACL 原语（`src/acl.rs`）

### 3.1 读取流程 — `fetch_dacl_handle`

`std::fs::OpenOptions` 以 `access_mode(READ_CONTROL)`、`share_mode(FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE)`、`custom_flags(FILE_FLAG_BACKUP_SEMANTICS)` 打开（Rust 打开天然支持扩展长度路径，且不提前解析重解析点）→ `GetSecurityInfo(handle, SE_FILE_OBJECT=1, DACL_SECURITY_INFORMATION, ...)` 返回 `(p_dacl, p_sd)`；SD 由调用方 `LocalFree`。命名变体 `GetNamedSecurityInfoW` 用于 `add_allow_ace`/`revoke_ace`/`path_owner_matches`。

### 3.2 allow-write ACE 掩码与继承

```rust
const WRITE_ALLOW_MASK: u32 =
    FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE;   // 无 FILE_DELETE_CHILD
```

- **不含 `FILE_DELETE_CHILD`**（源码注释）：对每个继承子孙授 DELETE 而非对父目录授 delete-child——父目录的 delete-child 授权会绕过 `.git`、显式只读子路径等受保护子对象上的**直接** deny-write ACE。
- `ensure_allow_write_aces(path, sids)`：经 `ensure_allow_mask_aces_with_inheritance_impl(path, sids, WRITE_ALLOW_MASK, disallow_mask=FILE_DELETE_CHILD, SET_ACCESS, CI|OI)` 写入。
- 刷新判定 `dacl_allow_mask_needs_refresh`：allow 掩码未全齐（`require_all_bits=true`），**或**同一 SID 存在含 `disallow_mask`（FILE_DELETE_CHILD）位的**显式** ACE（`AceScope::Explicit` 跳过继承 ACE——`SET_ACCESS` 替换不了祖先继承来的陈旧授权）。对外由 `path_write_aces_need_refresh(path, psids)` 暴露。
- **护 deny**：若现有 DACL 中存在**任意 trustee** 与（allow 掩码 | GENERIC_READ/WRITE/EXECUTE/ALL）相交的 deny ACE（`dacl_has_deny_mask(DenyAceScope::Any, ...)`），跳过该 grant——没有完整令牌就无法证明新 allow 不会越权压过别的 trustee 的继承 deny，故一律保留现状（注释原文）。
- 写回路径：`SetEntriesInAclW(entries, p_dacl, &mut p_new_dacl)` 与旧 DACL 合并；**仅当确有条目**时才以 `READ_CONTROL|WRITE_DAC` 重开句柄并 `SetSecurityInfo(handle, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, ..., p_new_dacl, ...)`；无变更路径全程只读。
- 同族辅助：`ensure_allow_mask_aces`（固定 CI|OI）；`grant_read_execute_aces`（`GRANT_ACCESS` + RX，不替换既有条目，供 `setup_runtime_bin.rs` 运行时路径可读）；`add_allow_ace`（legacy：`dacl_has_write_allow_for_sid` 幂等检查后 SET_ACCESS 写 GENERIC R/W/E + CI|OI，`SetNamedSecurityInfoW` 落盘）；`allow_null_device`（`\\.\NUL` 内核对象 SE_KERNEL_OBJECT=6 上 SET_ACCESS GENERIC R/W/E，供 stdout/stderr 重定向；legacy 侧另有 `spawn_prep.rs::allow_null_device_for_workspace_write` 给 logon SID 兜底）。
- 查询原语：`dacl_has_write_allow_for_sid`（allow 型、忽略 INHERIT_ONLY、与 FILE_GENERIC_WRITE 相交）；`path_mask_allows`（单次 DACL fetch 的掩码检查，`MapGenericMask` 折算 GENERIC 位）；审计用 `STANDARD_USER_MUTATION_MASK`（写四项 + DELETE + FILE_DELETE_CHILD + WRITE_DAC + WRITE_OWNER）配 `path_has_standard_user_mutation_allow`（Users/Authenticated Users/Everyone 是否有可变更 allow），及 `path_has_trusted_system_owner`（Administrators/SYSTEM/TrustedInstaller `S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464`——确定性服务 SID，不经本地化账户名解析）。

### 3.3 deny ACE

`DenyAceKind::mask()`：

- **Write**：`FILE_GENERIC_WRITE | FILE_WRITE_DATA | FILE_APPEND_DATA | FILE_WRITE_EA | FILE_WRITE_ATTRIBUTES | GENERIC_WRITE(0x4000_0000) | DELETE | FILE_DELETE_CHILD`——删除类权利一并封死，堵住「删掉重建」绕过；
- **Read**：`FILE_GENERIC_READ | GENERIC_READ(0x8000_0000)`。

`add_deny_ace(path, psid, kind)`：以 `READ_CONTROL|WRITE_DAC` 打开（失败则退 `READ_CONTROL` 只读句柄做存在性检查后回传原错误）→ `GetSecurityInfo` → `kind.already_present`（`dacl_has_write_deny_for_sid` / `dacl_has_read_deny_for_sid`，均忽略 INHERIT_ONLY、按 SID 精确匹配）幂等跳过 → 构造 `EXPLICIT_ACCESS_W{grfAccessPermissions: mask, grfAccessMode: DENY_ACCESS(=3), grfInheritance: CONTAINER_INHERIT_ACE(0x2)|OBJECT_INHERIT_ACE(0x1)}` → `SetEntriesInAclW(1, ...)` → `SetSecurityInfo`。继承标志让目录上的 deny 自动覆盖其后新建的子孙。

### 3.4 `SetEntriesInAclW` 的 deny-before-allow 语义

`add_deny_read_ace` 文档注释明确：`SetEntriesInAclW` 把新建 deny ACE 置于 allow ACE **之前**，维持 Windows「deny 先赢」的 DACL 求值顺序——调用方无需自行排序，deny 也不会被旧 allow 遮蔽。

### 3.5 revoke ACE — `revoke_ace`

`GetNamedSecurityInfoW` 取 DACL（**null DACL 直接 Ok**：替换成空 ACL 会全拒）；`EXPLICIT_ACCESS_W{0, REVOKE_ACCESS(=4), CI|OI}` 经 `SetEntriesInAclW` 移除该 SID 全部条目；**若 `AceCount` 前后不变则不调 `SetNamedSecurityInfoW`**——避免无谓触发继承重传播。

### 3.6 文件系统根保护

`ensure_handle_is_not_filesystem_root`：`GetFinalPathNameByHandleW(VOLUME_NAME_NONE)` 解析句柄真实路径，若为单字符 `\` 即根，报 "refusing to apply a deny-read ACE to filesystem root"。deny-read 在**打开句柄**与 **plan 阶段**（§5.2）双重拦截根目录，杜绝别名/重解析路径把机器整体锁死。

## 4. allow/deny 路径模型（`src/allow.rs`）

`AllowDenyPaths { allow: HashSet<PathBuf>, deny: HashSet<PathBuf> }` 由 `compute_allow_paths_for_permissions(permissions, command_cwd, env_map)` 计算：

- 输入 `ResolvedWindowsSandboxPermissions::writable_roots_for_cwd`（`resolved_permissions.rs:153`）：先从 `file_system.entries` 剔除 `Tmpdir`/`SlashTmp` 特殊项（Windows 上 TEMP/TMP 另行处理、Unix /tmp 忽略），再取 `get_writable_roots_with_cwd` 得 `WindowsWritableRoot{root, read_only_subpaths}`；若策略含可写 Tmpdir 项，追加 `windows_temp_env_roots(env_map)`（TEMP/TMP，仅绝对路径）。
- **映射规则**：每个 `root` 经 `dunce::canonicalize`（失败保留原值）且 `exists()` 才进 `allow`；其 `read_only_subpaths` 逐条 `exists()` 后进 `deny`。`allow.rs` 测试佐证：workspace-write 下 `.git`、`.codex`、`.agents`、`.aws`（由 codex-protocol 侧生成的只读子路径）落入 deny；`exclude_tmpdir_env_var` 控制 TEMP/TMP 是否计入；workspace 根条目用运行时 workspace_roots 而非 command_cwd。
- 消费方 `spawn_prep.rs::apply_legacy_session_acl_rules`：`allow` → §2 所述 cap 选择后授权；`deny` → 逐 cap deny-write；`additional_deny_write_paths`（显式 carveout）先 `create_dir_all` 物化再并入 deny；`additional_deny_read_paths` 非空时按档位同步 deny-read 状态；最后对与 canonical cwd 相等的写根做 `.codex`/`.agents` 工作区保护（§5.4）。

## 5. deny-read 链路

### 5.1 目标解析 — `src/deny_read_resolver.rs::resolve_windows_deny_read_paths`

把 split filesystem 策略的 `None`（只读）条目解析为具体 Windows ACL 目标：

- **精确路径透传**：`get_unreadable_roots_with_cwd` 的路径原样保留（含尚不存在者），`push_absolute_path` 经 `dunce::simplified` + `AbsolutePathBuf` 归一并去重；
- **glob 快照展开**：Windows ACL 不理解 glob。`ReadDenyMatcher::try_new_for_local_paths` 先校验语法（非法 glob 在展开前即失败）；`glob_scan_plans` 按「glob 前的字面量前缀根」合并扫描计划（`windows_deny_read_glob_scan` 推导 root/max_depth/pattern_suffix；同根取最深 max_depth、glob 串接为一次扫描）；**从文件系统根起且未配 `glob_scan_max_depth` 的递归 glob 直接报错**（"cannot be safely expanded from a filesystem root"）；
- 展开引擎优先捆绑 ripgrep（`ripgrep_files`）：`rg --files --hidden --no-ignore --glob-case-insensitive --null [--max-depth N] [--glob ...] -- root`；rg 缺失（NotFound）或退出码 2（遍历受保护目录、输出可能不完整）时回退到 `deny_read_walker.rs::collect_existing_glob_directory_matches` 的 matcher 全量遍历（`DirectoryScanMode::IncludeAllFiles`），**绝不采用失败扫描的部分 stdout**；rg 命中的文件再过 `matcher.is_local_path_read_denied` 复核（大小写不敏感匹配 SECRET.ENV），目录匹配（含空目录）由 walker 单独收集（`DirectoriesOnly` 模式）。
- 调用入口：`setup.rs::setup_refresh_deny_read_paths`（先 `remove_skip_missing_path_entries`、`materialize_project_roots_with_workspace_roots` 再解析）。

### 5.2 plan/apply/回滚 — `src/deny_read_acl.rs`

- `plan_deny_read_acl_paths(paths)`：每条路径同时保留**词法路径 + canonical 路径**（`dunce::canonicalize`，`NotFound` 容忍跳过），以 `lexical_path_key`（`\`→`/`、去尾 `/`、小写）去重。双写理由（注释）：词法路径覆盖用户配置的拼法、允许稍后物化尚不存在的精确 deny；canonical 路径额外覆盖 reparse-point 目标，防沙箱经解析后位置读到同一对象。plan 末尾若含 `is_absolute() && parent().is_none()` 的根路径立即 bail。
- `apply_deny_read_acls(paths, psid)`：逐条：不存在则 `create_dir_all` 物化为**目录**（防沙箱在可写父目录下先创建该路径再读取的 TOCTOU 竞态）→ `add_deny_read_ace`；任一条失败即对**本次调用新增的** ACE 逐条 `revoke_ace` 回滚后返回错误——一次性运行不留半套状态；成功返回全部 planned 路径。

### 5.3 持久化与对账 — `src/deny_read_state.rs`

workspace-write / elevated 会话在命令退出后**故意保留 ACL**（注释：子孙进程可能活得比 launcher 久）→ deny-read ACL 集合跨运行有状态。`sync_persistent_deny_read_acls(codex_home, principal_sid, desired_paths, psid)`：

1. 载入 `<codex_home>/.sandbox/deny_read_acl_state.json`：`PersistentDenyReadAclState{principals: BTreeMap<SID 字符串, Vec<PathBuf>>}`（serde_json pretty；文件缺失视为空态）；
2. **先** `apply_deny_read_acls(desired_paths, psid)` 落新目标集（顺序保证撤销前总有 deny 生效）；
3. **后**对旧集合中不在新 `desired_keys`（lexical key 对比）的路径逐条 `revoke_ace`——profile 变更不留陈旧 deny；
4. 新集为空则 `principals.remove(sid)`，否则 upsert，重写落盘。

调用方：elevated = 组 SID（`setup_provisioning.rs:934`，主键即 `sandbox_group_sid_str`）；legacy = readonly cap SID 或逐写根 cap SID（`spawn_prep.rs:311-326`）——同一状态文件按主体分区互不干扰。

### 5.4 工作区保护 — `src/workspace_acl.rs`

`protect_workspace_codex_dir(cwd, psid)` / `protect_workspace_agents_dir` → `protect_workspace_subdir(cwd, psid, ".codex"/".agents")`：目录存在则 `add_deny_write_ace`（deny-**write**，保读拒写，防沙箱改写工作区内凭据/agent 配置）。`spawn_prep.rs` 仅当某写根 cap 的 `root` 恰为 canonical 命令 cwd（`is_command_cwd_root`：`canonicalize_path(root) == canonical_command_cwd`）时施加——其他写根下的同名目录不受影响。

## 6. setup_provisioning.rs 的 ACL 施加顺序

payload（`Payload` 结构）携带 `read_roots / write_roots / deny_read_paths / deny_write_paths / codex_home / command_cwd / real_user` 等；`run_setup_full` 顺序：

1. **账户准备**（`!refresh_only`）：`provision_sandbox` → `ensure_sandbox_users_group`（`winutil.rs:223`：`NetLocalGroupAdd(CodexSandboxUsers)`，容忍 ERROR_ALIAS_EXISTS=1379 / NERR_GROUP_EXISTS=2223）→ `provision_sandbox_users` 建两账户（`ensure_sandbox_user` = `ensure_local_user` + `ensure_local_group_member(CodexSandboxUsers)`，密码随机并 DPAPI 写 `.sandbox-secrets`）→ `hide_newly_created_users` → WFP/防火墙（离线账户网络封锁）。
2. **deny-read 同步（同步执行，必须先于沙箱命令启动）**：`sync_persistent_deny_read_acls(codex_home, 组SID, deny_read_paths, 组PSID)`，失败即中止（`.context("apply deny-read ACLs")`）。
3. **读授权 helper（后台异步）**：read_roots 非空且 `read_acl_mutex_exists()==false` 时 `spawn_read_acl_helper`——克隆 payload 置 `mode=ReadAclsOnly, refresh_only=true`，base64 后经 `launch_environment` 传参拉起自身子进程（CREATE_NO_WINDOW）；子进程 `run_read_acl_only` 先抢 `read_acl_mutex`（单飞），再按 §2 对 read_roots 施加组 RX allow。Full 模式不等它完成——读授权是渐进收敛的。
4. **write 根授权**：逐根（HashSet 去重、缺失跳过）取 `workspace_write_cap_sid_for_root(codex_home, command_cwd, root)`（root 与 cwd 同键时用 `workspace_by_cwd` 工作区 cap，否则 `writable_root_by_path`，`cap.rs:116`；SID 持久化在 `cap_sid` 文件）；`path_write_aces_need_refresh(root, &[组PSID, capPSID])` 判定（检查失败也按需刷新，仅记 refresh_errors）后，`std::thread::scope` 并发对每个待授权根 `ensure_allow_write_aces(root, &[组PSID, capPSID])`——组与根 cap 双主体、WRITE_ALLOW_MASK、CI|OI。
5. **deny-write carveout**：逐路径去重后：不存在则 `create_dir_all` 物化（注释：显式策略 carveout 或 legacy 受保护子 `.git/.codex/.agents`；若不先物化，沙箱可在可写父目录下抢先创建以绕过）；`workspace_write_cap_sids_for_path`（`workspace_write_root_overlaps_path` 双向包含判定选出相关根；无命中回退全部活动根，write_roots 全空回退 cwd 根）逐 SID `add_deny_write_ace(path, deny_psid)`。
6. **`.sandbox-bin` DACL 锁定**（`lock_sandbox_bin_dir`，Registered runtime 直接跳过）：`lock_sandbox_dir` 以空旧 DACL `SetEntriesInAclW` 重建四条 ACE——组 `GRANT_ACCESS` 仅 RX；SYSTEM / Administrators 全量（R/W/E|DELETE）；真实用户 R/W/E|DELETE|**WRITE_DAC**（注释：owner 未提权的 refresh 也要能重贴这个 protected DACL）；`DaclInheritance::Protected` → `SetSecurityInfo(..., DACL|PROTECTED_DACL_SECURITY_INFORMATION)`；ProvisionOnly 模式改用 `open_directory_no_reparse` 的 no-reparse 句柄（READ_CONTROL|WRITE_DAC）防别名的 CODEX_HOME 被劫持。
7. **`.sandbox`/`.sandbox-secrets` 锁定**（`!refresh_only` 时 `lock_persistent_sandbox_dirs`）：`.sandbox`（日志/状态目录）组 GRANT R/W/E|DELETE、继承式；`.sandbox-secrets`（账户密码）组 **`DENY_ACCESS` 全掩码**——沙箱组整组拒绝，任何沙箱会话都读不到凭据；顺带删除 legacy `sandbox_users.json`。

`run_provision_only`（服务 ProvisionOnly / InteractiveProvision 路径）= 步骤 1 + 6 + 7，不做根 ACL；`SetupMode` 与 `refresh_only` 决定是否持 `acquire_sandbox_setup_lock(INFINITE)` 全程 setup 锁；`refresh_only && !refresh_errors.is_empty()` 时 bail（"setup refresh had errors"），普通 Full 尽力而为。

## 7. 小结：三层防线

- **令牌层**（token.rs）：`DISABLE_MAX_PRIVILEGE|LUA_TOKEN|WRITE_RESTRICTED` + caps/user/logon/Everyone restricting SIDs + TokenDefaultDacl(logon GENERIC_ALL + OWNER RIGHTS READ_CONTROL) + SeChangeNotifyPrivilege——削特权、锁会话、写通道双评估、读通道交还 DACL。
- **DACL 层**（acl.rs + setup_provisioning.rs）：组 SID 管读（账户基线默认拒 → read roots 组 allow → deny-read 挂组 SID）、cap SID 管写（write roots allow-write 无 delete-child → carveout deny-write 挂 cap SID）、`.sandbox*` 目录 DACL 锁定与文件系统根保护兜底。
- **对账层**（deny_read_state.rs + allow.rs + deny_read_resolver.rs）：deny_read_acl_state.json 跨运行先加后撤、AllowDenyPaths 把权限档案精确映射到 allow/deny 目标集合、glob 快照展开把不可读模式物化为逐路径 ACE。

## 附录 A：关键常量速查（含数值）

| 常量 | 数值 | 出处 |
|---|---|---|
| `DISABLE_MAX_PRIVILEGE` | `0x01` | token.rs |
| `LUA_TOKEN` | `0x04` | token.rs |
| `WRITE_RESTRICTED` | `0x08` | token.rs |
| `GENERIC_ALL` | `0x1000_0000` | token.rs |
| `SE_GROUP_LOGON_ID` | `0xC0000000` | token.rs |
| `SE_PRIVILEGE_ENABLED` | `0x00000002` | token.rs（enable_single_privilege） |
| `GENERIC_READ / WRITE / EXECUTE` | `0x8000_0000 / 0x4000_0000 / 0x2000_0000` | acl.rs |
| `WRITE_ALLOW_MASK` | FILE_GENERIC_READ\|WRITE\|EXECUTE\|DELETE（无 FILE_DELETE_CHILD） | acl.rs |
| deny-write 掩码 | FILE_GENERIC_WRITE\|FILE_WRITE_DATA\|FILE_APPEND_DATA\|FILE_WRITE_EA\|FILE_WRITE_ATTRIBUTES\|GENERIC_WRITE\|**DELETE\|FILE_DELETE_CHILD** | acl.rs `DenyAceKind::Write` |
| deny-read 掩码 | FILE_GENERIC_READ\|GENERIC_READ | acl.rs `DenyAceKind::Read` |
| `OBJECT_INHERIT_ACE / CONTAINER_INHERIT_ACE` | `0x01 / 0x02` | acl.rs |
| `INHERIT_ONLY_ACE / INHERITED_ACE` | `0x08 / 0x10` | acl.rs（检查时跳过） |
| `DENY_ACCESS / REVOKE_ACCESS / SET_ACCESS` | `3 / 4 / 2` | acl.rs EXPLICIT_ACCESS_W |
| `SE_FILE_OBJECT` | `1` | acl.rs / setup_provisioning.rs |
| `SE_KERNEL_OBJECT` | `6` | acl.rs（allow_null_device） |
| OWNER RIGHTS SID | `S-1-3-4` | token.rs set_default_dacl |
| Everyone SID | `S-1-1-0`（CreateWellKnownSid WinWorldSid=1） | token.rs world_sid |
| TrustedInstaller SID | `S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464` | acl.rs TRUSTED_INSTALLER_SID |
| 沙箱组名 | `CodexSandboxUsers`（winutil.rs `SANDBOX_USERS_GROUP`） | setup_provisioning |

## 附录 B：deny-read 端到端链路

```
PermissionProfile（split filesystem）
  └─ setup.rs::setup_refresh_deny_read_paths            # remove_skip_missing + 物化 project_roots
       └─ deny_read_resolver.rs::resolve_windows_deny_read_paths
            ├─ 精确 unreadable roots 原样透传（含缺失路径）
            └─ glob → glob_scan_plans（字面根+深度，根起无界即报错）
                 ├─ rg --files（快）  ── exit 2 / 缺失 ──▶ deny_read_walker（matcher 全量）
                 └─ matcher.is_local_path_read_denied 复核 ─▶ Vec<AbsolutePathBuf>
  └─ ElevationPayload.deny_read_paths（base64 → setup helper / 服务）
       └─ setup_provisioning.rs::run_setup_full
            └─ deny_read_state.rs::sync_persistent_deny_read_acls(组SID)   # elevated
                 ├─ load deny_read_acl_state.json（.sandbox/）
                 ├─ deny_read_acl.rs::apply_deny_read_acls
                 │    ├─ plan：词法 + canonical 双写，根路径拒绝
                 │    ├─ 缺失路径 create_dir_all 物化
                 │    ├─ acl.rs::add_deny_read_ace（DENY_ACCESS, CI|OI, deny-before-allow）
                 │    └─ 失败 → revoke_ace 回滚本次新增
                 ├─ 旧集合差集 revoke_ace（先加后撤）
                 └─ store_state 回写 JSON
```

legacy 侧等价链路：`resolve_windows_deny_read_paths` → `apply_legacy_session_acl_rules(additional_deny_read_paths)` → `sync_persistent_deny_read_acls(readonly cap SID 或各写根 cap SID)`（spawn_prep.rs:311-326），主体键不同、状态文件共享。
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

# 第 4 分片：IPC 协议与 SCM 服务

覆盖三套相互独立的本地 IPC 与承载它们的服务进程：

1. 通用分帧层：`windows-sandbox-rs/src/framed_io.rs`
2. 父进程(CLI) ↔ elevated command runner 流式协议：`windows-sandbox-rs/src/elevated/ipc_framed.rs`、`elevated/runner_pipe.rs`、`elevated/runner_client.rs`（runner 实现见 `src/bin/command_runner/win.rs`）
3. CLI ↔ Windows 沙箱 provisioning 服务：`windows-sandbox-rs/src/provisioning_protocol.rs`、`provisioning_client.rs`、`service_identity.rs`，以及 `windows-sandbox-service/src/{service,ipc,package_identity,machine_policy,provisioning}.rs`

下文 `sandbox-rs` = `codex-rs/windows-sandbox-rs/src/`，`service` = `codex-rs/windows-sandbox-service/src/`。所有结论均出自上述源码（main@92bc601a）。

## 1. 通用分帧层（framed_io.rs）

三条 IPC 链共用的最小帧格式：**4 字节小端长度前缀 + JSON 载荷**。

- `write_frame(writer, message)`（`sandbox-rs/framed_io.rs`）：
  - `serde_json::to_vec` 序列化；payload 超过 `MAX_FRAME_LEN` 直接 bail（"frame too large"）；
  - 写 `len.to_le_bytes()`（u32 LE）+ payload，随后 `flush()`——保证每帧立即离开用户态缓冲。
- `read_frame(reader)`：
  - 先 `read_exact` 4 字节长度；`UnexpectedEof` 返回 `Ok(None)`（对端正常关闭），其他 IO 错误透传；
  - 长度超限同样 bail；分配 `vec![0; len]` 后 `read_exact` 读满，再 `serde_json::from_slice`。
- `MAX_FRAME_LEN = 8 * 1024 * 1024`（8 MiB）：为**不可信对端的单帧内存**设上界，防止恶意长度声明撑爆进程。
- `wait_for_complete_frame(pipe: &File, deadline: Instant)`：
  - 同步命名管道句柄没有安全的超时读，故用 **`PeekNamedPipe` 轮询**；
  - 先窥探 4 字节得 `frame_len`，当 `total_available >= 4 + frame_len` 时帧完整，返回让调用方执行阻塞 `ReadFile`；
  - 不完整则 `sleep(remaining.min(FRAME_POLL_INTERVAL))`，`FRAME_POLL_INTERVAL = 5ms`；
  - 超过 deadline 返回 `TimedOut`（"timed out waiting for a complete IPC frame"）；
  - 该函数是 §3 的 `spawn_ready` 等待与 §4 的 provisioning 响应等待共用的超时原语。

## 2. elevated runner IPC 协议（elevated/ipc_framed.rs）

`sandbox-rs/elevated/ipc_framed.rs` 定义父进程与 command runner 之间的 JSON 消息模式。

- 协议版本：`IPC_PROTOCOL_VERSION: u8 = 6`；每个帧是 `FramedMessage { version, #[serde(flatten)] message }`，version 与消息体平铺在同一 JSON 对象，读写复用 §1 的 `write_frame/read_frame`。
- `Message` 枚举 `#[serde(tag = "type", rename_all = "snake_case")]`，共 9 个变体；模块注释明确方向：
  - 父→runner 命令：`SpawnRequest / Stdin / CloseStdin / Resize / Terminate`；
  - runner→父 事件/结果：`SpawnReady / Output / Exit / Error`。
- 各变体字段（均为 `payload` 包裹的结构体）：

| 变体（wire tag） | 载荷 | 字段 |
|---|---|---|
| `spawn_request` | `SpawnRequest`（`Box`） | `command: Vec<String>`、`cwd: PathBuf`、`env: HashMap<String,String>`、`permission_profile: PermissionProfile`、`workspace_roots: Vec<AbsolutePathBuf>`、`codex_home`/`real_codex_home`、`cap_sids: Vec<String>`、`network_proxy_restricting_sid: Option<String>`（只加入子进程 restricting SID 集的托管网络身份）、`timeout_ms: Option<u64>`、`tty: bool`、`stdin_open: bool`、`private_desktop_name: Option<String>`（父进程跨 runner 存活的私有桌面） |
| `spawn_ready` | `SpawnReady` | `process_id: u32` |
| `output` | `OutputPayload` | `data_b64: String`、`stream: OutputStream`（`stdout`/`stderr`） |
| `stdin` | `StdinPayload` | `data_b64: String` |
| `close_stdin` | `EmptyPayload` | 空（关闭子进程 stdin） |
| `resize` | `ResizePayload` | `rows: u16`、`cols: u16` |
| `exit` | `ExitPayload` | `exit_code: i32`、`timed_out: bool` |
| `error` | `ErrorPayload` | `message: String`、`stage: ErrorStage`、`windows_error_code: Option<u32>` |
| `terminate` | `EmptyPayload` | 空（请求终止子进程） |

- 二进制安全：stdout/stderr/stdin 字节一律 **base64**（`encode_bytes/decode_bytes`，`general_purpose::STANDARD`）编入 JSON。
- `ErrorStage` 三值（`read_spawn_request / spawn_child / write_spawn_ready`）标记 runner 启动失败的阶段，与 `windows_error_code`（如 1312）一起供父进程分类重试。
- runner 侧流程（`src/bin/command_runner/win.rs`）：
  - `read_spawn_request()`（win.rs:185）失败 → `ErrorStage::ReadSpawnRequest`；
  - `spawn_ipc_process()` 失败 → `SpawnChild`；成功后发 `SpawnReady{process_id}`，发送失败 → `WriteSpawnReady`（win.rs:570-619）；
  - 帧循环（win.rs:435 起）消费 `stdin/close_stdin/resize/terminate`，转发 `output`；
  - `terminate_job_or_process()`（win.rs:406）：先 `JobObject::terminate` 杀整树，失败回退 `TerminateProcess`。
- 父进程捕获循环（`sandbox-rs/elevated_impl.rs:255` 附近）：丢弃迟到 `SpawnReady`、按 `stream` 分流 `output`、以 `exit` 收尾，其他变体视为协议错误；
- 取消路径：`spawn_cancel_writer()`（`elevated_impl.rs:68`）在取消信号触发时向管道写 `Terminate` 帧。

## 3. runner 管道传输与认证（elevated/runner_pipe.rs + runner_client.rs）

### 3.1 管道命名

- `pipe_pair()`（`runner_pipe.rs`）：`SmallRng::from_entropy()` 生成 128 位 nonce；
- 名字形如 `\\.\pipe\codex-runner-<nonce:hex>-in` 与 `...-out`，不可猜测 ⇒ 不可预占、不可枚举。

### 3.2 服务端管道创建（父进程持有管道，runner 是客户端）

- `create_named_pipe(name, access, sandbox_username)`：
  - `resolve_sid(sandbox_username)` + `string_from_sid_bytes` 解析沙箱用户 SID 字符串；
  - SDDL 为 `D:(A;;GA;;;{sandbox_sid})`，经 `ConvertStringSecurityDescriptorToSecurityDescriptorW`（`SDDL_REVISION_1`）转 `SECURITY_ATTRIBUTES`；
  - DACL **只授予该沙箱用户 GENERIC_ALL**，无其他 ACE ⇒ 其他任何账户默认拒绝连接；
  - `CreateNamedPipeW` 参数：`PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT`、实例数 1、出/入缓冲各 65536、默认超时 0；
  - 方向常量在文件头手写（windows-sys 0.52 未导出）：`PIPE_ACCESS_OUTBOUND = 0x2`（父写 runner 读的 `-in` 管）、`PIPE_ACCESS_INBOUND = 0x1`（runner 写父读的 `-out` 管）。

### 3.3 以沙箱身份启动 runner（runner_client.rs::spawn_runner_transport）

- `find_runner_exe()` → `helper_materialization::resolve_command_runner`：registered Core 用注册别名，否则 legacy 物化副本；
- 注册运行时再取 `app_package::registered_runner_alias()`（别名可被更新重定向，见 3.5 ①）；
- 命令行 `<runner> --pipe-in=<in> --pipe-out=<out>`，逐参数 `quote_windows_arg`；
- `CreateProcessWithLogonW` 参数：
  - 账户：沙箱 `username` / `password`、域 `"."`（本机 SAM）；
  - 旗标：仅注册别名场景加 `LOGON_WITH_PROFILE`（execution alias 需账户 profile）；
  - 创建标志 `CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT`；`STARTUPINFOW.dwFlags = STARTF_FORCEOFFFEEDBACK`；
  - spawn 前后 `SetErrorMode(0x3)` 抑制硬错误弹窗，失败码在恢复错误模式前捕获（`RunnerLogonError`）。

### 3.4 15s 连接超时 + CancelSynchronousIo + PID 校验

- `connect_pipe_with_timeout(h, expected_runner_pid, label)`（`RUNNER_PIPE_CONNECT_TIMEOUT = 15s`）：
  - 阻塞的 `ConnectNamedPipe` 无超时参数，故 spawn 名为 `codex-runner-connect-{label}` 的辅助线程；
  - 线程先 `DuplicateHandle` 复制自身线程句柄经 channel 发布（注释：必须在阻塞连接前发布，供超时取消）；
  - 父进程 `recv_timeout(15s)`；超时则对该线程 **`CancelSynchronousIo(thread_handle)`** 中断其阻塞系统调用；
  - 返回 `ERROR_NOT_FOUND` 说明操作恰好在取消前完成，再收割一次结果；取消成功后**不 join** 线程——靠父进程关闭管道句柄让阻塞连接自然解除（注释明确此设计避免二次无限等待）。
- `connect_pipe(h, expected_runner_pid)`（runner_pipe.rs）：
  - `ConnectNamedPipe` 容忍 `ERROR_PIPE_CONNECTED`(535)（客户端已抢先连上）；
  - **`GetNamedPipeClientProcessId` 取连接者 PID，必须 == `CreateProcessWithLogonW` 返回的 `pi.dwProcessId`**，否则 `PermissionDenied`；
  - 与 3.2 的沙箱用户专属 DACL 构成双因子：能连上（跑在沙箱账户下）+ 是刚启动的那个进程（PID 相符）。

### 3.5 SpawnReady 握手与失败处理

- 启动序列（任一步失败统一 `TerminateProcess(pi.hProcess, 1)` 收尸）：
  1. 注册别名时 `verify_registered_core_runner(pi.hProcess, &runner_exe)`——防更新把别名重定向到别的镜像；
  2. `connect_pipe_with_timeout(pipe-in)`；3. `connect_pipe_with_timeout(pipe-out)`；
  4. `send_spawn_request()`（写 `FramedMessage{version:6, SpawnRequest}`）；
  5. `read_spawn_ready()`。
- `read_spawn_ready()`（`RUNNER_SPAWN_READY_TIMEOUT = 15s`）：
  - 先 `wait_for_complete_frame`（§1）等完整帧再读；
  - `SpawnReady` ⇒ 通道建立；`Error` ⇒ `RunnerStartupError`（含 stage + Windows 错误码）；管道提前关闭 ⇒ "runner pipe closed before spawn_ready"。
- 凭据重试（`retry_runner_spawn_once` + `is_refreshable_sandbox_creds_error`）：
  - `ERROR_SERVICE_ALREADY_RUNNING`(1056)：原凭据原样重试一次（无 runner/命令被启动，凭据保持不变）；
  - `ERROR_ACCOUNT_DISABLED / ERROR_LOGON_FAILURE / ERROR_NO_SUCH_LOGON_SESSION`：判定可刷新，触发 `refresh_logon_sandbox_creds` 换新凭据重启 runner；
  - 例外：WindowsApps 命令的 `ERROR_NO_SUCH_LOGON_SESSION`(1312) 是 AppX 激活特性，不换凭据重试（轮换密码无济于事）。

## 4. provisioning 服务 IPC

### 4.1 管道名、服务名与版本（provisioning_protocol.rs + service_identity.rs）

- 基名常量：`SANDBOX_PROVISIONING_PIPE_NAME = r"\\.\pipe\OpenAI.CodexSandbox"`；
- `service_identity.rs::windows_sandbox_service_pipe_name()`：进程有 package family 时追加 `.{family}`（如 `\\.\pipe\OpenAI.CodexSandbox.OpenAI.Codex_3k8sg7r9htsxt`），使**每个打包渠道的服务与管道相互隔离**（账户仍共享）；
- 无包身份时回退基名；可用环境变量 `CODEX_WINDOWS_SANDBOX_PACKAGE_FAMILY` 提示，`validate_service_family_hint` 校验格式（名称 ≤50 字符、`[A-Za-z0-9.-]`、发布者 ID 恰 13 字符）——注释明确该 hint **仅用于查找，不做授权**；
- SCM 服务名同理：`windows_sandbox_service_name()` = `CodexSandboxService` 或 `CodexSandboxService.{包名}`；
- 协议版本 `PROVISIONING_PROTOCOL_VERSION: u8 = 1`；帧结构 `FramedProvisioningMessage { version, #[serde(flatten)] message }` 复用 §1 分帧。

### 4.2 消息（ProvisioningMessage，tag = "type"）

- `RegisterInstallationRequest { codex_home: String }`：只登记桌面卸载所有权，不配置任何沙箱资源；客户端入口 `register_desktop_installation()`（5s 超时）。
- `ProvisionSandboxRequest { payload: SandboxProvisioningRequest }`，字段（`deny_unknown_fields`）：
  - `codex_home`：沙箱根目录；
  - `registered_core: bool`：路由请求——服务端**独立认证**调用方安装镜像后再裁决，不信任此字段；
  - `refresh_only: bool`：只刷新既有注册，不创建/修复沙箱账户；
  - `settings: WindowsSandboxProvisioningSettings`：防火墙设置（`proxy_ports`、`allow_local_binding`）；
  - `listeners: WindowsSandboxProxyListeners`：已知代理监听协议（`http_ports`、`socks_ports`），供托管策略校验，与 settings 分离。
- 响应 `SandboxProvisioningResponse`（tag = "status"）：`Ok` / `Unavailable`（客户端应回退 elevated setup helper）/ `Error { message }`；
- 哨兵错误 `SANDBOX_GROUP_CHANGED`（"sandbox group changed before authentication"）：认证前沙箱组 SID 代际变化，客户端 `provisioning_client/group_change.rs::retry` 重连刷新后的管道一次。

### 4.3 客户端（provisioning_client.rs）

- 入口：`provision_windows_sandbox_via_service()`（Setup；持 `OPENAI_FEDERATION_RULE_ID` / `OPENAI_IDENTITY_TOKEN_FILE` 环境者直接判 Unavailable 不回退）与 `refresh_registered_core_via_service()`（仅 registered，刷新注册）；
- `connect(deadline)`（`PROVISIONING_TIMEOUT = 120s`，服务启动等待上限 `SERVICE_STARTUP_TIMEOUT = 5s`）：
  1. `query_service_status()`：`OpenSCManagerW(SC_MANAGER_CONNECT)` → `OpenServiceW(SERVICE_QUERY_STATUS)` → `QueryServiceStatusEx(SC_STATUS_PROCESS_INFO)`；
  2. `SERVICE_START_PENDING` ⇒ 25ms 间隔轮询至运行（不阻塞桌面就绪检查）；
  3. `SERVICE_RUNNING` 后以 `OpenOptions::custom_flags(SECURITY_SQOS_PRESENT | SECURITY_IMPERSONATION)` 打开管道；
  4. **SQOS 标志的意义**：为本次 `CreateFile` 显式声明管道安全 QoS 为"模拟"级——授权服务端 `ImpersonateNamedPipeClient` 获得可用的客户端模拟令牌；服务端 §5 的全部身份认证（读 token user、会话、组成员、以其身份加载配置）都建立在这条模拟令牌上，服务不掌握用户凭据；
  5. `ERROR_PIPE_BUSY` ⇒ `WaitNamedPipeW` 排队；`ERROR_FILE_NOT_FOUND` / 服务不存在 / 标记删除 ⇒ 返回 `Unavailable`。
- `exchange_request()` 发帧前 `verify_server(pipe)`：
  - **`GetNamedPipeServerProcessId(pipe)` 的 PID 必须等于 SCM `QueryServiceStatusEx` 的 `dwProcessId`，且状态 `SERVICE_RUNNING`**；
  - 双向交叉校验防"连接到冒名的假管道服务器"（否则 bail "the provisioning pipe server does not match the running service"）。
- `read_response()`：响应 version ≠ 1 ⇒ 降级 `Unavailable`（触发旧/新客户端走 helper 回退）；
- IO 类失败（`UnexpectedEof/TimedOut/BROKEN_PIPE/NO_DATA/PIPE_NOT_CONNECTED`）⇒ `Unavailable`；
- `Unavailable` 回退语义：registered Core 一律 bail（"refusing helper fallback"），legacy 才返回 `WindowsSandboxProvisioningOutcome::Unavailable` 由上层起 helper。

### 4.4 服务端帧约束（service/ipc.rs + ipc/request.rs）

- `MAX_REQUEST_BYTES = 4096`、`REQUEST_IDLE_TIMEOUT = 5s`、`MAX_RESPONSE_MESSAGE_BYTES = 512`（错误消息截断 + 控制字符替换为空格）；
- `handle_request()`：`PeekNamedPipe` 循环累计字节，解析 4 字节前缀后必须**恰好一帧**（多一字节即 bail "must contain exactly one IPC frame"）；
- `validate_request()`：版本必须 1；`refresh_only` 必须伴随 `registered_core`；三组端口排序去重且禁 0；listener 端口必须是 `settings.proxy_ports` 子集（"provisioning listener is absent from the proxy settings"）；`codex_home` 非空且无 `\0/\r/\n`；
- `write_response()`：写响应帧后用 `PeekNamedPipe` 探测客户端断开（至多 1s）再 `DisconnectNamedPipe`，确保客户端读得到完整帧。

## 5. SCM 服务（windows-sandbox-service）

### 5.1 服务注册与控制处理（service/service.rs）

- `run()`：组装单条 `SERVICE_TABLE_ENTRYW` 调 `StartServiceCtrlDispatcherW`（`SERVICE_WIN32_OWN_PROCESS`）；
- `service_main_inner()`：`RegisterServiceCtrlHandlerExW` 注册 `service_control_handler`，随后按 `START_PENDING → RUNNING → STOP_PENDING → STOPPED` 推进 `SetServiceStatus`；
- `service_control_handler` 接受的控制码：
  - `SERVICE_CONTROL_STOP`：置 `stop_requested` + `shutdown` 标志、上报 `STOP_PENDING`、`wake_listener()`；
  - `SERVICE_CONTROL_SHUTDOWN`：同上（不置 stop_requested）；
  - `SERVICE_CONTROL_INTERROGATE`：重报当前状态；
  - `SERVICE_CONTROL_SESSIONCHANGE`：唤醒监听器做 owner 会话刷新；
  - 其余返回 `ERROR_CALL_NOT_IMPLEMENTED`。
- `dwControlsAccepted` 仅在 RUNNING 时为 `SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN | SERVICE_ACCEPT_SESSIONCHANGE`；
- `wake_listener()`：起线程循环 `CreateFileW(GENERIC_WRITE)` 打开自家管道，把阻塞在 `ConnectNamedPipe` 的监听循环唤醒（25ms 重试直到服务停止）。

### 5.2 事件日志（service.rs::log_event）

- `RegisterEventSourceW`（source = 服务名，回退 `"CodexSandboxService"`）+ `ReportEventW`，用后 `DeregisterEventSource`；
- 消息上限 `MAX_EVENT_MESSAGE_UNITS = 1024` 个 UTF-16 单位，控制字符替换为空格；
- 事件 ID：**1000** 服务已启动 / **1001** 收到停止请求 / **1002** 已停止 / **1003** 致命错误；**2000** provisioning 成功 / **2001** provisioning 失败 / **2002** 请求被策略拒绝；**3002/3003/3004** 清理开始/完成/明细；
- `policy_rejection_diagnostic()`（ipc.rs）只输出服务自有阶段标签（`runtime/bootstrap/resolve_auth/initialize_auth/managed/enforce`）与类型化错误码（如 `cloud=… http=…`、`io=… win32=…`），**不落原始配置行**——注释指出解析器可能把整行（含凭据）字符串化。

### 5.3 单实例监听（service/ipc/listener.rs）

- `create_provisioning_pipe()` 的 `CreateNamedPipeW` 旗标：
  - `PIPE_ACCESS_DUPLEX | FILE_FLAG_FIRST_PIPE_INSTANCE`；
  - `PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS`；
  - 实例数 1，出/入缓冲 1024 / 4096（= `MAX_REQUEST_BYTES`）；
- `FILE_FLAG_FIRST_PIPE_INSTANCE`：本句柄必须是该名字的第一个实例——重名服务或抢注者直接创建失败，保证**单实例监听**；
- `PIPE_REJECT_REMOTE_CLIENTS`：拒绝远程机器客户端，仅本机可连；
- DACL（`ipc.rs::pipe_security_descriptor`）：
  `D:P(D;;GA;;;{sandbox_sid})(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;IU)`
  - **deny 沙箱组 GENERIC_ALL**：沙箱账户连不上 provisioning 管道，杜绝自我提权回路；
  - **SY(SYSTEM)、BA(Administrators) 全权**；
  - **IU(Interactive Users) 仅 0x12019b**：标准位 `READ_CONTROL|SYNCHRONIZE` + `FILE_READ_DATA|FILE_WRITE_DATA|FILE_READ_EA|FILE_WRITE_EA|FILE_READ_ATTRIBUTES`——可读写帧，但无写属性、追加、写 DAC 等能力；
- `ProvisioningListener::refresh()`：每次响应后复核 `SANDBOX_USERS_GROUP` 当前 SID；变化则先关旧首实例管道、重建描述符再开新管道（deny 规则随组代际更新）。

### 5.4 客户端认证链（ipc/authentication.rs + package_identity.rs）

accept 之后、分发之前执行，全程不信任请求字段：

1. 持 `acquire_sandbox_setup_lock(5s)`，复核组 SID == 建管道时 deny 的 SID（不符 ⇒ `SANDBOX_GROUP_CHANGED`）；
2. `ImpersonateNamedPipeClient(pipe)` → `OpenThreadToken(TOKEN_QUERY | TOKEN_IMPERSONATE)` 取模拟令牌（依赖客户端 4.3 的 SQOS 标志）；
3. **包身份**（`package_identity.rs::authorize_client_process`）：
   - `GetNamedPipeClientProcessId` → `OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)`；
   - `process_package_family()`（`sandbox-rs/package_identity.rs`，底层 `GetPackageFamilyName`；`APPMODEL_ERROR_NO_PACKAGE` = 无包身份）；
   - 规则：客户端包族必须等于服务自身包族；双方都无包身份放行（legacy）；debug `--foreground` 额外容忍已知 Codex 包族（`OpenAI.Codex{,Alpha,Beta,Nightly}_3k8sg7r9htsxt`）；
4. **token user 一致性**（`authorize_client`）：`OpenProcessToken(TOKEN_QUERY)` 读客户端进程令牌 user SID，必须与模拟令牌 user SID 相同——防"进程属 A、管道由 B 连入"的错位；
5. **SessionId ≠ 0**：`GetTokenInformation(TokenSessionId)`，session 0 拒绝（"non-interactive service accounts cannot request provisioning"）；
6. **不得是沙箱组成员**：`CheckTokenMembership(token, sandbox_sid)` 命中即拒绝（"sandbox accounts cannot request provisioning"）；
7. 固化 `ClientIdentity { account, user_sid, session_id, runtime, token, directory_handles }`：
   - `authorize_setup_runtime` 依据**持有的客户端进程镜像**裁决 legacy/registered 路由（非请求字段）；
   - `prepare_codex_home` 以模拟身份打开/创建 codex_home，目录句柄钉进 identity，后续 provisioning 全程持有（防 TOCTOU / 路径偷换）；RegisterInstallation 路径则要求已存在目录并以 `FILE_ADD_FILE|FILE_ADD_SUBDIRECTORY|WRITE_DAC` 打开 + `GetFinalPathNameByHandleW` 解析字面名。

### 5.5 机器策略校验（service/machine_policy.rs）

- `validate_provisioning_settings()`：tokio 单线程 runtime 的 `on_thread_start` 里 `ImpersonateLoggedOnUser(client_token)`——**以调用者身份**加载其配置层栈（模拟失败即 panic 通知主流程）；
- 加载顺序：bootstrap `load_config_toml_with_layer_stack`（并把 `chatgpt_base_url` 还原默认，防 bootstrap 覆盖策略端点）→ `cloud_config_bundle_loader_for_storage_without_cache` 云策略束 → 带云层栈重载，取 `requirements_toml`；
- 校验点（`validate_requirements`）：
  - `requirements.windows.allowed_sandbox_implementations` 不含 `Elevated` ⇒ 拒绝（"managed policy does not permit the elevated Windows sandbox"）；
  - `network.enabled == false` ⇒ 不得有代理端口或 `allow_local_binding`；
  - `network.allow_local_binding == false` ⇒ 禁本地绑定；
  - `network.http_port / socks_port` 指定时，listener 端口必须精确匹配；`proxy_ports` 中未被 listener 归类又不在允许值的"未分类端口"一律拒绝——**漏报 listener 不能绕过端口限制**；
- TOML 解析错误回 `Unavailable`（`is_config_parse_error`）；策略拒绝记事件 2002 并回 `Error`。

### 5.6 provisioning 分派（service/provisioning.rs）

- `RegisterInstallation`：仅写 installation record（codex_home/user_sid/session_id/desktop_installation），返回 `Ok`；
- `SetupRuntime::Registered` ⇒ `provisioning/registered.rs::run`，**服务内事务、无子进程**：
  - setup 锁 + 组代际复核；先 `save_runtime` 持久化 owner 再动账户（"persist the authenticated owner before setup can rotate shared credentials"）；
  - 账户缺/禁/过期（`UF_ACCOUNTDISABLE | UF_PASSWORD_EXPIRED`）或凭据失配（`credentials_need_repair` 以 `logon_existing_sandbox_account` 探测）⇒ `provision_sandbox_in_process()` 原地建账户；
  - `refresh_only` 绝不修复（"registration refresh cannot repair sandbox setup"）；
  - 最后 `registered_runtime::provision` 完成包级运行时注册并发布就绪；
- legacy 路径：
  - `register_owner` 记录 owner 后，`setup_is_complete()`（以 owner 模拟身份查 `sandbox_setup_is_complete_with_settings`）短路返回 `Ok`；
  - 否则解析同目录 `codex-windows-sandbox-setup.exe`：必须普通文件且非 `FILE_ATTRIBUTE_REPARSE_POINT`（防符号链接劫持）；
  - 调 `sandbox-rs/setup.rs::run_elevated_provisioning_setup_with_retained_handles` **spawn setup helper 子进程**：
    - `ElevationPayload { mode: SetupOnly, runtime, … }` base64 传递（`launch_environment` 分块，不受命令行长度限制）；
    - 服务已 SYSTEM ⇒ `needs_elevation = false`，不再 UAC；
    - identity 钉住的目录句柄继承给 helper——目录保护在服务意外退出后仍由 helper 句柄维持（setup.rs 函数 doc 注释明言此意图）。

## 6. 为什么需要 SYSTEM 常驻服务

- **管理员专属操作集中化**：创建/删除本地沙箱用户与组、改系统路径 ACL、配置 WFP 防火墙（`sandbox-rs/wfp*.rs`）、维护服务自身注册——都需要管理员令牌；服务以 SYSTEM 运行（随 MSIX 打包注册），一次安装、长期可用。
- **避免反复 UAC**：无服务路径每次 setup 都走 `run_elevated_setup_inner` ⇒ `run_setup_exe(needs_elevation=true)` 触发交互式 UAC；有服务后普通权限 CLI 通过命名管道请求 provisioning（§4），由 SYSTEM 服务代完成全部提权操作，用户只在安装服务时见到一次授权。
- **策略集中执行**：`allowed_sandbox_implementations`、网络端口/绑定约束必须在**不可被客户端伪造的上下文**校验——§5.5 以 SYSTEM 身份、按客户端模拟令牌独立加载托管/云配置复核；客户端提交的 settings/listeners 只是待审数据。若在客户端本地校验，任何客户端篡改都能绕过。
- **审计与生命周期**：provisioning 成败、策略拒绝、清理过程全部写入 Windows 事件日志（§5.2 事件 ID），管理员集中审计；`installation_record` 跨服务重启持久化 owner，卸载与会话切换清理有唯一权威，不受单个 CLI 进程存亡影响。
- **信任边界收口**：沙箱账户被 deny ACE + `CheckTokenMembership` 双重挡在 provisioning 管道外；提权能力只暴露给"包身份一致 + 模拟令牌 user 一致 + 会话非 0 + 非沙箱组"的签名客户端；客户端反向以 `GetNamedPipeServerProcessId` ↔ SCM PID 交叉验证服务真身（§4.3）——双向防冒充。
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


