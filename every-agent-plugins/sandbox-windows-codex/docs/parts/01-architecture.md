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
