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
