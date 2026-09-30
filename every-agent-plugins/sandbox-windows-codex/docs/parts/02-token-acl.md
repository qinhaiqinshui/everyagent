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
