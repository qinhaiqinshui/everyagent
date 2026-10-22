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
