# sandbox-windows-codex

以 Java + JNA 复刻 OpenAI Codex 的 Windows 原生沙箱机制，作为 every-agent 的高隔离沙箱后端插件（后端 id=`codex`）。

- 机制事实源：[docs/codex-analysis.md](docs/codex-analysis.md)；设计决策：[docs/design.md](docs/design.md)
- 架构约束：`docs/ARCHITECTURE.md` §7.10（沙箱插件化：Backend 只管挂载）、红线（插件零 worker 依赖、PermissionGate 兜底越界 IO）

> 定性澄清：codex **未用 AppContainer**（全源码 0 命中 `CreateAppContainerProfile`）——capability SID 是手工合成的 `S-1-5-21-{a}-{b}-{c}-{d}` group SID；隔离主体是**真实本地双账户**（Offline/Online）+ `WRITE_RESTRICTED` 受限令牌。

## 1. 机制概述（对应 codex 源码结构）

| 机制 | 本插件实现 | 对应 codex 源 |
|---|---|---|
| 真实本地双账户 + 沙箱组 | 账户 `<前缀>Offline`/`<前缀>Online`（默认 `EACodex*`,见 `SandboxAccounts.DEFAULT_PREFIX`），组 `<前缀>SandboxUsers`；隐藏账户（登录界面不可见） | `sandbox_users.rs` / `principals.rs` / `hide_users.rs` |
| 合成 capability SID | 随机 `S-1-5-21-…`，按 workspace / readonly / per-cwd / per-writable-root 分键持久化 `cap_sid` | `cap.rs` |
| WRITE_RESTRICTED 受限令牌 | `DISABLE_MAX_PRIVILEGE\|LUA_TOKEN\|WRITE_RESTRICTED`；restricting = caps → 额外 → logon SID → Everyone；default DACL + SeChangeNotifyPrivilege 恢复 | `token.rs` |
| 读 ACL 三段式 | 账户基线无权读真实用户目录 → 组授 RX → deny-read ACE 压制 | `acl.rs` / `deny_read_*.rs` |
| 防火墙 | offline 账户 4 条 block 规则（COM `INetFwPolicy2`，LocalPolicyModifyState 自检 + SID 回读校验，fail-closed 顺序） | `firewall.rs` |
| WFP | 12 条持久 filter（ICMP/DNS53/DoT853/SMB445/139 × v4/v6，ALE_USER_ID 条件，事务 + 固定自有 GUID） | `wfp.rs` / `filter_specs.rs` |
| DPAPI 机器作用域凭据 | `.sandbox-secrets/sandbox_users.json`（目录 DENY 沙箱组） | `dpapi.rs` / `identity.rs` |
| JobObject + 私有桌面 + 句柄白名单 | `KILL_ON_JOB_CLOSE` + `PROC_THREAD_ATTRIBUTE_JOB_LIST` 原子挂接；私有桌面（受限令牌 spawn 必需） | `parts/03` |
| marker 两阶段提交 | `CREATE_NEW` 空 sentinel → 原子重写 + `Global\` 互斥 + 进程内 singleflight | `setup.rs`（不变量①） |
| 帧协议 IPC | 4B LE 长度前缀 + JSON，base64 二进制，版本 6，9 个消息 wire tag 对齐 | `ipc_framed.rs` / `framed_io.rs` |

「宁拒不裸」：任何表达不了的限制一律拒绝执行，绝不降级明文（netsh 降级、明文执行均不做）。

## 2. 架构（四层与命名管道）

```
worker JVM（真实用户，broker 宿主）              setup helper JVM（管理员，UAC 一次）
┌ CodexSandboxPlugin ─────────────┐  ShellExecuteExW ┌ SetupHelperMain ──────────┐
│ CodexSandboxProvider/Backend    │─"runas" 提权───▶│ 建组/账户/ACL/防火墙/WFP/  │
│ CodexSandboxManager(根登记)     │◀─ stdout 报告 ──│ marker 两阶段提交          │
│ CodexCommandExecutor            │                 └───────────────────────────┘
│  └ RunnerClient / RunnerPipe    │  CreateProcessWithLogonW(沙箱账户, 明文密码)
└──────┬──────────────────────────┘
       │ \\\\.\\pipe\\every-agent-codex-runner-<128bit nonce>-in / -out   （DACL 只授沙箱账户 + PID 校验）
       ▼
runner JVM（沙箱账户，agent 侧）                ┌ CodexRunnerMain ───────────┐
┌ CodexRunnerMain ────────────────┐            │ 读 SpawnRequest → TokenFactory│
│ SandboxTokenFactory(受限令牌)    │            │ → 私有桌面 → JobObject        │
│ ChildProcess(cmd.exe /d /c …)   │──帧流────▶ │ → 子进程 stdout/stderr base64 │
└──────┬──────────────────────────┘  output/   └─────────────────────────────┘
       ▼
child 进程（WRITE_RESTRICTED 令牌 + Job + 句柄白名单 + 私有桌面）
```

- 提权只发生在**一次性 setup**（延迟触发，仅当 codex 被选为最高优先级可用沙箱时）；日常执行零提权。
- 命令跑在**宿主路径**（无路径重写），读写边界由账户基线 DACL + capability SID ACE 表达——这是与 wsl-ubuntu（路径翻译）的根本差异。
- 与 PermissionGate 的分工：gate 仍是工作区外路径访问的前置授权（fs.\*/命令越界先弹 ask）；授权通过的 externalRoots 进入 mount(RW) → 写根 → preflight 落 allow-write ACE。沙箱内 IO 另有 OS 级兜底：即便 gate 被绕过，沙箱账户对未授权路径默认拒绝写（mic 后端没有的第二道闸）。

## 3. 安装（setup 流程）

setup 是**延迟触发**的——`Provider.isAvailable` 仅探测 Windows 平台（不查 marker），`priority=8`。沙箱选择器在所有后端注册完毕后按优先级选出最高者。只有当 codex 真正胜出（即没有 WSL 等更高优先级沙箱可用）时，`Provider.create()` 才被调用，此时才同步执行 setup（会弹 UAC）。

1. codex 后端被沙箱选择器选中（`SandboxProviderRegistry.select` 按 priority 选最高可用），`CodexSandboxProvider.create()` 被调用。若 setup marker 未就绪，在此同步执行完整 setup（会弹 UAC 提权确认窗，用户需当场同意）。幂等：已完成时 marker 双闸门短路返回；账户/凭据失配时重跑 setup 修复。
2. UAC 弹窗 → 用户同意 → 提权 helper 依次完成：
   - 建组 `EACodexSandboxUsers`（`NetLocalGroupAdd`）；
   - 建账户 `EACodexOffline`/`EACodexOnline`（`NetUserAdd`，24 字符随机密码，先禁用、网络限制就绪后才解禁——修复路径不变量④）；
   - 密码 DPAPI 机器作用域加密 → `.sandbox-secrets/sandbox_users.json`（目录 DENY 沙箱组）；
   - 账户级 ACL：平台默认读根（C:\Windows、Program Files×2、ProgramData + java.home + 插件目录）组授 RX；写根 allow-write ACE（组 + root cap SID 双主体）；
   - 防火墙：offline 账户 4 条 block 规则（出/入非环回、环回 TCP 代理端口补集、环回 UDP 全禁；`LocalUserAuthorizedList` SDDL 按 SID 限定）+ LocalPolicyModifyState 自检 + 写后读回；
   - WFP：12 条持久 filter（固定自有 provider/sublayer GUID，事务包裹；排在防火墙之后的第二道防线）；
   - 隐藏账户（`Winlogon\UserList=0`）、目录锁定（`.sandbox` 组 RWX / `.sandbox-secrets` 组 DENY / `.sandbox-bin` 组 R+X+Protected）、marker 两阶段提交。
3. marker（`<codexHome>/.sandbox/setup_marker.json`）+ 凭据文件双闸门就绪 → 后续 `create()` 调用短路返回，不再弹 UAC。
4. 幂等：已完成时 setup 立即短路返回；账户/凭据失配（错误码 1317/1332/1326/1331/1387 等）时重跑 setup 修复。执行期自愈矩阵（design.md §4.3.1）：沙箱账户被删、组被删（SID 解析 1332「帐户名与安全标识间无任何映射」）、凭据文件 sandbox_users.json 丢失/损坏、setup marker 被删——命令执行链内强制重 setup 重建后**原地重试一次**，无需重启 worker；写根 ACL/`.sandbox-bin`/cap_sids.json/profile 目录本就按需自愈。

选择语义：auto 模式下 codex priority=8——**低于 wsl-ubuntu(10)**（不抢既有默认后端，WSL 可用的机器行为不变）、**高于 windows-mic(5)**（无 WSL 的机器上 auto 兑现为更强隔离）。isAvailable 仅探测 Windows 平台（不查 marker），故选择器始终能看到 codex 后端；只有当 codex 胜出时 `create()` 才触发 setup（首次弹 UAC，后续幂等短路）。

> 注意：worker 当前后端词汇表尚未收录 `codex` 值（`sandbox.type=codex` 会被归一为 auto），因此实际生效路径是「auto + setup 就绪即最高可用优先」。worker 侧词汇表扩展见 §7 TODO。

## 4. 配置

`worker.sandbox`（经 every-agent 全局配置）：

| 键 | 默认 | 说明 |
|---|---|---|
| `worker.sandbox.type` | `auto` | 见上；写 `codex` 表达意图（当前归一为 auto） |
| `worker.sandbox.allow-network=false` | — | 断网 → Offline 账户；放行 → Online 账户 |
| `worker.sandbox.timeout-ms` | 沙箱默认 | 单命令看门狗（runner 侧超时 exit=192 + 父侧 terminate 宽限 15s） |

插件自管键（`plugin.json contributes.config`，`codex.*`）：

| 键 | 默认 | 接线状态 | 说明 |
|---|---|---|---|
| `codex.home` | `<沙箱持久根>/codex` | ✅ | 状态根（.sandbox/.sandbox-secrets/.sandbox-bin/cap_sid 之父） |
| `codex.account-prefix` | `EACodex` | ✅ | 账户 `<前缀>Offline/Online`、组 `<前缀>SandboxUsers` |
| `codex.network-policy` | `auto` | ✅ | auto（随 allow-network）/ `offline` / `online` 强制 |
| `codex.proxy-ports` | 空 | ✅ | offline 放行的环回 TCP 代理端口列表（setup 时生成规则） |
| `codex.allow-local-binding` | `false` | ✅ | true = 移除环回 block 规则 |
| `codex.java-home` | 当前 JVM | ✅ | runner 启动用 java.exe 根目录 |
| `codex.extra-read-roots` | 空 | ⏳ 未接线 | 追加读根（见 §7 TODO） |
| `codex.extra-deny-write-paths` | 空 | ⏳ 未接线 | 追加 deny-write 路径 |
| `codex.setup.auto-uac` | `true` | ⏳ 未接线 | 已移入 activate() 同步执行，此键不再需要 |
| `codex.setup-timeout-ms` / `codex.exec-timeout-ms` | 120000 / 沙箱默认 | ⏳ 未接线 | 走 `worker.sandbox.timeout-ms` |

## 5. 与 windows-mic / wsl-ubuntu 的差异

| 维度 | sandbox-windows-codex | sandbox-windows-mic | sandbox-wsl-ubuntu |
|---|---|---|---|
| 隔离模型 | 专用本地账户 + 机器级 DACL + cap SID + WRITE_RESTRICTED 令牌 | Restricted Token（去特权）+ Medium IL，宿主身份 | WSL2 发行版内 root 直连 |
| 读隔离 | 默认拒读（账户基线），deny-read 可加 | 无（Medium IL 可读用户文件） | 发行版边界 |
| 写隔离 | OS 级：allow-write ACE 交集才可写，gate 之外的第二道闸 | 无 OS 兜底，全靠 PermissionGate | 发行版边界（drvfs 挂载面） |
| 网络 | 账户级防火墙 + WFP（Offline 天然断网） | 无（Job 管不了网络），剥代理 env | 发行版网络栈 |
| 提权 | 首次 setup 一次 UAC | 无 | 无 |
| 文件系统副作用 | 持久（账户/ACL/防火墙/cap_sid），提供两阶段卸载 | 零副作用零残留 | 发行版镜像 |
| 执行形态 | 三进程：worker→runner JVM（沙箱账户）→child | 单进程直接 spawn | wsl.exe 子进程 |
| priority | 就绪 8 / 未 setup 0 | 5（auto 兜底） | 10 |
| 使用场景 | 高隔离/不可信代码/需断网；setup 成本一次 | 日常低摩擦 | Linux 语义/跨发行版 |

## 6. AI 工具

| 工具 | Provider | 说明 |
|---|---|---|
| `cmd` | CodexBashToolProvider（appliesTo：当前后端 id==codex） | `cmd.exe /d /c <command>`，cwd=工作区根，stdin 关闭，rg 前置进 PATH；输出每流 100 万字符截断，`[stderr]`/超时/exit code 尾注与 wsl-ubuntu 同款 |

> setup 已移入 `CodexSandboxPlugin.activate()` 同步执行（幂等），不再以 AI 工具暴露。

## 7. 限制与遗留 TODO

- **PrivateDesktop**：`ChildProcess` 已支持 lpDesktop，会话层尚未接线（`privateDesktopName=null`；受限令牌不设桌面时 PowerShell 会 `STATUS_DLL_INIT_FAILED`——cmd 首期不受影响）。
- **ConPTY**：暂缓（pipe + stdio 桥；AI 命令非交互、stdin=NUL，无 TTY 需求）。
- **windows-admin 端到端**：setup 幂等/越界拦截/Offline 断网/卸载的端到端冒烟标记 `@Tag("windows-admin")`，需管理员 Windows 手动回归（对齐 codex `sandbox_smoketests.py` 断言面）。
- **worker 后端词汇表**：`worker.sandbox.type=codex` 显式值尚未被 worker `normalizeBackend` 收录（当前归一 auto，靠 priority 生效）——待 worker 侧步骤扩展。
- 未接线配置键见 §4（⏳ 行）；deny-read walker 对账与动态 deny-read 列表为二期。
- externalRoot 指向非 owner 目录时 preflight 刷 ACE 可能无 WRITE_DAC——首期拒绝并提示重新 setup 授权。

## 8. 卸载

两阶段（prepare：锁→禁用账户→按 SID 杀进程 → finish：删三目录→WFP→防火墙规则→unhide→DeleteProfile+NetUserDel→NetLocalGroupDel；flags 可回滚、SID 复验防同名顶替，不变量⑤）：

```bash
# 组装 remove 载荷并经提权 helper 执行（或重新激活插件触发 setup 修复）
payload='{"version":5,"offline_username":"EACodexOffline","online_username":"EACodexOnline",
  "group_name":"EACodexSandboxUsers","codex_home":"<codexHome>","real_user":"<用户名>",
  "mode":"remove"}'
java -cp <本插件jar> dev.everyagent.plugin.sandbox.codex.setup.SetupHelperMain \
  --setup-payload "$(printf %s "$payload" | base64 -w0)"   # UAC 一次
```

卸载后 marker/凭据/账户/组/防火墙/WFP/目录/注册表 UserList 值全部消失，无关对象不受影响；`Provider.isAvailable()` 自动回到 false。

## 9. 模块结构

```
dev.everyagent.plugin.sandbox.codex
├── CodexSandboxPlugin            # 入口：注册 Provider + BashToolProvider + SetupToolProvider
├── CodexSandboxOptions/Manager   # 插件配置快照 / 挂载根登记簿（无复杂状态）
├── CodexSandboxProvider          # id=codex；isAvailable 只探测（绝不自动 UAC）；priority 8/0
├── CodexSandboxBackend           # mount=恒等映射 + 根登记；onWorkspaceRemoved=no-op
├── CodexCommandExecutor          # readiness→preflight→SpawnRequest→会话→聚合/格式化
├── CodexBashToolProvider         # cmd 工具（appliesTo=codex 后端）
├── CodexSandboxProvider/Backend    # 后端 id=codex
├── accounts/ acl/ setup/ fw/ session/ runner/ win/   # 域代码（步骤 1-6 交付）
└── docs/ design.md codex-analysis.md parts/
```
