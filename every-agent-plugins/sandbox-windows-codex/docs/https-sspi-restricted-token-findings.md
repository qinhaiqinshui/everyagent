# 沙箱内 HTTPS 故障排查报告（SEC_E_NO_CREDENTIALS）

> 2026-10-21/22 排查定案。结论：**Windows 安全设计使然，非本插件 bug**。
> 受限令牌进程被 OS 拒发登录会话的 SSPI 缓存凭据，Schannel 栈 TLS 全灭；
> 自带 TLS 栈的工具（Java/JSSE、Node、Python、git+OpenSSL 后端）不受影响。

## 1. 现象

沙箱内一切 HTTPS 失败，表现为本地即死（TCP/DNS/明文 HTTP 均正常）：

```
curl: (35) schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS (0x8009030e)
Invoke-WebRequest → "安全包中没有可用的凭证"
git(默认后端) → fatal: schannel: AcquireCredentialsHandle failed: ...
```

## 2. 根因

**`CreateRestrictedToken` 派生的受限令牌进程，`AcquireCredentialsHandle` 对
Schannel/NTLM/Negotiate（含机器上下文）一律返回 `SEC_E_NO_CREDENTIALS`**——
LSA 拒绝向受限令牌进程发放登录会话的缓存凭据（安全设计：防止沙箱进程
冒用用户身份做认证）。Schannel 的 TLS 客户端在本地取凭据第一步即失败，
握手根本到不了网络层。

这与 Chromium/Edge 将网络服务进程留在沙箱外是同一限制。

## 3. 证据链（关键实验）

| # | 实验 | 结果 |
|---|---|---|
| 1 | 沙箱内裸调 SSPI `AcquireCredentialsHandle`（NTLM/Schannel/Negotiate/机器上下文） | 全部 `SEC_E_NO_CREDENTIALS` |
| 2 | 同一受限进程内：Java JSSE TLS vs curl(Schannel) | **JSSE `TLS: OK (TLSv1.2)`；curl 失败**——网络路径无恙，纯凭据层问题 |
| 3 | 同一受限进程内：`git -c http.sslBackend=openssl ls-remote https://github.com/...` | **成功拿到 ref**；同命令默认后端（schannel）失败 |
| 4 | 健康 CPWLW 会话里以 `SandboxTokenFactory` 同款形态（flags=13 + caps/user/logon/Everyone + 私有桌面 + `CreateProcessAsUserW`）spawn 子进程 | 子进程 curl 稳定复现 `SEC_E_NO_CREDENTIALS`（三次独立复现；会话自身正常）|
| 5 | 宿主侧同构造实验（宿主令牌、不经 seclogon） | 受限变体无一产出 HTTP=200（对照不受限 curl=200）|
| 6 | 排除项 | 防火墙/WFP（仅约束 offline 账户）、证书存储/DPAPI/LSA 包、LUA/WRITE_RESTRICTED flags 组合、账户与密码、seclogon 调用路径、worker 进程状态、管道/env/cmdline/目标镜像参数（worker 进程内正交二分 v1~v3 全通过）|

「历史日志显示偶发成功」是假象：那些 `rc=0` 的 HTTPS 命令都是
`try{}catch{}` 结构，错误被吞、rc 只反映脚本执行完成。

## 4. 影响面

| TLS 栈 | 沙箱内可用性 |
|---|---|
| Schannel（curl.exe / PowerShell·.NET / WinHTTP·WinINet / git 默认后端） | ❌ 不可用 |
| Java JSSE（mvn/gradle/java） | ✅ |
| Node（npm，BoringSSL） | ✅ |
| Python（pip，OpenSSL） | ✅ |
| git 显式 OpenSSL 后端 | ✅ |

## 5. 规避方法

- git：`git -c http.sslBackend=openssl <cmd>`，或全局
  `git config --global http.sslBackend openssl`（本机实测可用）
- 命令行 HTTP 测试：优先明文 HTTP 目标；需 HTTPS 时用 Java/Python/Node 探针
- PowerShell `Invoke-WebRequest`/`curl.exe` 在沙箱内**不要**用于 HTTPS 验证
  （会误判为"断网"）

## 6. 修复方向建议（产品层）

1. **文档**：`test-cases.md` TC-3.5（预期 Online 账户 HTTPS 成功）与实际不符，
   应改写为「Schannel 受限说明 + openssl 规避」——本次仅出报告未改用例
2. **可选增强**：执行层识别 git 命令时自动注入 `http.sslBackend=openssl`
   （或经 `GIT_CONFIG_KEY_n` 环境机制），让 git 开箱即用
3. **不可行方向**（已论证，勿踩坑）：loopback 代理救不了 Schannel——CONNECT
   隧道下客户端仍需自己做 TLS 握手，仍要过 `AcquireCredentialsHandle`；
   MITM 代理同理。客户端换 TLS 栈是唯一出路
4. 原生 codex 的 loopback 代理（proxy_ports）面向的是**网络策略管控**
   （offline 账户仅放行环回），不是、也不能解决本问题

## 7. 伴生发现（与本故障无关，建议另行处理）

- `HKLM\SYSTEM\CurrentControlSet\Control\Lsa\Security Packages` 被清空为 `""`
  （健康值应为 `kerberos/msv1_0/schannel/wdigest/tspkg/pku2u`）；当前 lsass
  仍持开机加载的包故未致命，但极不健康——疑似某"优化/安全"软件所为，
  建议还原后重启
- **seclogon 登录会话泄漏**：每条沙箱命令经 `CreateProcessWithLogonW` 建一个
  登录会话、从不注销，实测累计 1700+。长期建议 runner 退出时注销会话或
  引导用户定期重启
- worker 自带精简 JRE（`resources/jre`）无 `jdk.compiler` 模块，**不支持
  `java Foo.java` 单文件源码模式**——诊断探针曾因此静默无输出（表现为
  "输出文件未生成"），后续排障注意

## 8. 落地补记（2026-12，阶段一 Runner 池化 + 阶段二沙箱 HTTPS）

### 8.1 池化落地结论：登录会话从「每命令 1 个」降为「池化有界」

§7 第二条的 seclogon 泄漏已由 **runner 常驻池化**（阶段一，提交
9519e588/198c84ef/8cef35ab）治理：worker 侧 `RunnerPools` 按
`codexHome + 沙箱账户` 分池，复用空闲 runner（健康探测：进程存活 + `-out`
管道连通且零残留字节）、每命令独占、空闲 TTL（默认 10 分钟）到期发
`shutdown` 帧关会话、池上限默认 2、凭据失配自愈整池作废；runner 侧单命令
循环改造为「单读线程帧分发 + 主线程串行多命令」。泄漏面由每命令 1 个登录
会话降为**每 worker 有界个数（池上限 × 键数）**；配套
`LogonSessionMonitor`（LSA `LsaEnumerateLogonSessions`，secur32）周期审计
会话总数（默认 30 分钟 INFO / 超 64 WARN 附「重启 worker 可回收」提示）。
实测快照（池化前）：total=180，EACodexOnline=169——正是本条所述泄漏。

### 8.2 阶段二沙箱 HTTPS：宿主 loopback 代理已落地，Schannel 兜底 = 文档化

对 openai/codex main 的源码实证（`.everyagent/codex-https-research.md`，
 crate `codex-rs/network-proxy`）+ 用户决策「Codex 怎么实现本项目就怎么做」：

- **已落地**：宿主（worker 进程）内嵌 `LoopbackHttpProxy`（`proxy` 包）——仅
  绑 127.0.0.1，首选端口 3128 + 管理段 3128..3159 顺序探测（**拒绝端口 0，
  绝不随机**）；CONNECT 隧道透传（默认不做 MITM、不生成任何证书、不写系统
  证书库）；明文 HTTP 绝对 URI 转发；阻断 403 + `x-proxy-error` 头；SOCKS5
  裁剪（ALL_PROXY 回退 http url，与 codex 关 socks 一致）。`ProxyEnv` 对
  online 命令注入全套代理 env（HTTP(S)_PROXY/WS(S)_PROXY/ALL_PROXY/FTP_PROXY
  大小写成对、NO_PROXY=localhost,127.0.0.1,::1、NODE_USE_ENV_PROXY=1、
  ELECTRON_GET_USE_PROXY=true、EVERY_AGENT_PROXY_ACTIVE=1 活性标记），
  用户显式值不覆盖、旧托管键先剥离再重注。**防火墙协同有意不接线**：
  代理仅服务 online 账户（本无任何防火墙/WFP 规则，环回直连畅通），不把
  代理端口加进 offline 放行清单——本项目无 codex 的逐连接归因
  （restricting SID + GetExtendedTcpTable），加了会击穿 offline 密闭性；
  端口确定性由固定管理段保证，无漂移对账面（详见 `ProxyRegistry` 类 Javadoc）。
- **Schannel 兜底 = 本文档即兜底（不做 MITM）**：§6.3/§6.4 的论证被 codex
  源码实证再次确认——codex 默认 `mode=full + mitm=false`（MITM 仅
  limited/hooks/credential-broker 场景 opt-in），且全仓对
  `AcquireCredentialsHandle`/`SEC_E_NO_CREDENTIALS` 零处理，Windows 代理
  E2E 测试只跑裸 TCP/HTTP。**根因是凭据获取先于证书校验**——即便 MITM
  动态签发证书，Schannel 客户端仍在 `AcquireCredentialsHandle` 一步死掉，
  与信任链无关；MITM 只能帮「自带信任链可注入」的 OpenSSL 系客户端，而
  它们经 CONNECT 隧道本来就能用。故本项目与 codex 同款默认：不做 MITM。
- **§6.2 的建议已落地**：执行器对 git 工作区命令经 `GIT_CONFIG_KEY_n`
  env 机制自动追加 `http.sslBackend=openssl`（仅当 env 链未显式配置时；
  代理不在跑也注入——该缺陷与代理无关），git 开箱即用。
- **规避指引（面向模型/用户，与 §5 一致并扩充）**：git（自动 openssl 注入）、
  Java/JSSE、Node、Python/OpenSSL 天然可用；curl.exe/PowerShell/.NET
  `SslStream` 等 Schannel 原生栈在沙箱内 **HTTPS 不可用（平台限制，非断网）**；
  确需 curl 建议使用 openssl 构建的 curl。误判防护：代理阻断返回 403 +
  `x-proxy-error`，与 Schannel 失败（`SEC_E_NO_CREDENTIALS`/「安全包中没有
  可用的凭证」）可区分——前者是策略拦截、后者是平台限制。
