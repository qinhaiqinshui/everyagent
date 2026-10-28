# Windows Codex 沙箱实现细节

> 本文内容提取自 [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) §7.10「命令沙箱(插件化,多后端)」,
> 仅保留与 Windows Codex 后端(`sandbox-windows-codex`)实现直接相关的段落,原始技术内容完整不变,
> 章节引用(如 §7.8、§7.17)保持原样。

---

## 后端对比表 · Codex 行

| 后端 | id | priority | grant/revoke | toSandbox | 说明 |
|---|---|---|---|---|---|
| **Windows Codex** | `codex` | Windows?8:0 | 登记/注销写根(cap SID + preflight 刷 ACE)与读根 | 恒等(命令跑宿主路径) | Windows 原生强隔离(双本地账户 `EACodexOffline`/`EACodexOnline` + 组 `EACodexSandboxUsers` + WRITE_RESTRICTED 受限令牌 + capability SID + 防火墙/WFP);工作区树经 capability SID ACE 可写、区外只读;**默认启用,即 Windows 出厂默认后端**(优先级低于 wsl-ubuntu,但后者默认禁用;setup 延迟到首次 `create()` 才做、弹一次 UAC);**沙箱账户 profile 一致性治理**——`LOGON_WITH_PROFILE` 常态供给真 profile,但账户重建换 RID 后残留旧 profile 会让新账户读 HKCU 全拒读(PS 5.1 启动读 GPO 策略键即崩,其余程序无感),setup 侧以 NTUSER.DAT 属主校验+`DeleteProfileW` 重建、runner 启动 HKCU 自检失败(stage=`hkcu_profile_check`)触发同一自愈 |

---

## 授权根的回收语义

回收走账本差量——run 档清空(新用户输入)/ 主体驱逐 → 该 owner 的集合收敛 → 无人期望的根被 `revoke`;**跨主体共享的根在仍有主体期望时不被回收**(聚合在账本层完成,SPI 只带路径)。

两侧的物理回收机制**不同,原因是令牌模型**:

| | 授权 ACE 主体 | 回收机制 | 生效性 |
|---|---|---|---|
| **写** | 组 SID + **该根 cap SID** | cap SID 是 **restricting SID**(`CreateRestrictedToken` + `WRITE_RESTRICTED`),`revoke` 后不再进新令牌 → 写检查(DACL ∩ restricting SIDs)不匹配 | ✅ 立即失效;磁盘 ACE **刻意不撤** |
| **读** | 组 SID(**不能**用 cap SID) | restricting SID **只参与写检查**,读检查看令牌普通组;若把读 ACE 授给 cap SID 则根本读不了 → 读只能授组 SID,而组 SID 恒在令牌中 | ✅ 靠 **`ReadGrantState` 账本 + 物理撤 ACE**(preflight 差量对账) |

- **写侧为何不撤 ACE**:cap SID 门控已让陈旧 ACE 失效(**安全已达标**);而物理撤销会打断**仍持有该 SID 的存活子进程**(命令可起后台进程,活得比 launcher 久)——codex 原生正因此选择「ACL 留在原地 + SID 门控」,本仓同构。
- **读侧为何必须撤**:无门控手段可用;`ReadGrantState`(`<sandbox>/.sandbox/read_grants_state.json`,按组 SID 分区)记录「我们施过读授权的路径」,每次 preflight 先补齐/确认当前读根,再对本轮不再是读根的旧路径 `revokeAce`(下一条命令启动前完成 → 即刻系统级拒绝)。两条保护:①**当前写根**只遗忘不撤(写 ACE 是组+cap 双主体,撤组会连带丢掉读;且写根本身可能由读根升级而来);②**当前 deny-read 目标**只遗忘不撤(`revokeAce` 删该 SID 全部显式 ACE,会连带删掉 deny → 反而放宽)。系统本就放行读的路径(Everyone/Users/Authenticated Users 已持 RX)不入账——我们没施加任何东西。

---

## ACL 目标域守卫(AclTargetGuard)

codex 后端对任何路径施加 ACL(写根 allow / deny-write / deny-read / 读根 allow)前经 `AclTargetGuard` 单点校验——**fail-closed,2026-10-23 事故加固**:

- **盘根**与**系统关键目录**一律拒做目标:
  - `%SystemRoot%`/`%ProgramFiles%`(含 x86/W6432 变体)的**自身·其下·其祖先**全拒(祖先放行等于整体覆盖系统树);
  - `%ProgramData%` 与 `<SystemDrive>\Users` 拒**自身与祖先**、其下放行(WER 报告目录与沙箱账户 profile 是 codex 原版合法目标)。
- 词法 + realpath 双形态判定(防 reparse point 绕过)。
- **失败语义分级**:
  - 写根/deny-write/deny-read 命中即**抛 IOException**(setup fail-closed,错误直达 setup_error.json,用户可见);
  - 读根仅在「内建主体已持 RX」跳过失效、**即将真正写 ACE** 时命中 → 跳过该根并入 best-effort 错误列表(读授权缺失是可用性软失败,不因 legacy 脏配置搞挂整个 setup)。

**背景**:工作区外部授权根曾含历史整盘 `C:\` 授权,经 `SandboxPathRegistry`(READ_WRITE)→ 后端写根 → `ProvisioningAcl` SET_ACCESS 一路无守卫直达 `C:\` 根 DACL,异常 ACE 令全机 Java 进程 `toRealPath` 全灭(文件工具/命令授权链瘫痪,修复需手工恢复根 ACL)。worker 侧同步加**下发过滤**:`WorkspaceManager.syncSandboxRoots` 对 externalRoots 中的系统关键目录/盘根拒不下发(log.warn 提示移除),legacy 注册表脏数据兜底;注册入口 `addExternalRoot` 维持既有 `OverBroadRootCheck` 语义不变(系统目录与普通区外目录同权走授权决议链,§7.8)。

---

## 子进程临时目录供给

> 2026-10 新增

沙箱账户不加载 profile(`CreateProcessWithLogonW` 无 `LOGON_WITH_PROFILE`),自身无 `%USERPROFILE%`;而命令 env 继承自 worker(真实用户)——`TEMP`/`TMP` 指着真实用户的 `%LOCALAPPDATA%\Temp`,沙箱账户对之**无写权限**(ACL 只授真实用户/SYSTEM/Administrators),`mvn`/`pytest`/jar 签名一类用临时目录的工具全数 `Access Denied`。

Codex 原生靠账户登录加载 profile 获得 `%TEMP%`;本沙箱的等价物是**子进程真正可写的**工作区 `<workspace>/.everyagent/tmp`——**落点必须 capability 覆盖**:命令子进程跑在 `WRITE_RESTRICTED` 受限令牌下(SandboxTokenFactory,restricting SIDs = caps+user+logon+Everyone),写检查要求 restricting 列表也授予权限,**普通组 ACE(如 EACodexSandboxUsers (M))对受限令牌的写检查无效**——实测 `<codexHome>/.sandbox/tmp` 组 ACL 齐全仍全树写拒,只有 setup 注入过 capability SID ACE 的工作区树可写。

故命令 env(`CodexCommandExecutor.childEnv(workspaceRoot)`)把 `TEMP`/`TMP` 指到工作区 `.everyagent/tmp`(与 ChildProcess.OutputFiles 同一落点,目录由每次 spawn 的 scratchDir `Files.createDirectories` 保证);runner 自身 env(`RunnerClient.runnerEnvironment`)的 TEMP 仍指 `<codexHome>/.sandbox/tmp`(runner 非受限令牌,组 ACL 对其有效,runner-stderr.log 落那里)。共享目录语义即临时目录语义,不做会话隔离与清扫。

---

## 沙箱自愈

> 2026-10 新增

执行链内识别「可愈损伤」并自动重建——账户/组被外部删除(Win32 1332/1317)、密码被外部改动(1326 系)、凭据文件丢失/损坏/版本失配/DPAPI 解密失败、setup marker 被删——统一经 `Reprovisioner` 强制完整重 setup(重建账户/组、轮换密码、重写凭据文件与 marker)后**原地重试一次**(至多一次防循环);setup 失败回传双层错误。此前仅「密码失配」可自愈,现已并入同一管线。

---

## 沙箱 profile 供给

> 2026-10 新增

沙箱账户此前无 profile——env 型工具(git/npm/pip)继承的 `USERPROFILE`/`HOME` 指宿主目录(只读,配置/缓存写全拒;宿主 `.gitconfig`/`.npmrc` 凭据泄露面),JVM 系的 `user.home` 更回落 `C:\`(mvn 默认仓库 `C:\.m2` 建不了,exit 1)。

正解<b>治根不逐工具特判</b>(用户决策;曾试 settings.xml 生成+MAVEN_ARGS 注入=file:// 宿主镜像的 maven 特判方案,因通用性被否,提交 50165a5 后撤销):runner 的 `CreateProcessWithLogonW` 常态化 `LOGON_WITH_PROFILE`(codex 原生仅 execution alias 场景开,我们常态化)→ Windows 为沙箱账户创建/加载真 profile(`C:\Users\<account>`,默认落点,无法定制)→ ①`childEnv` 把 `USERPROFILE`/`HOME`/`APPDATA`/`LOCALAPPDATA` 指到该 profile(env 型工具);②JVM 系零配置——`user.home` 走 `GetUserProfileDirectory`,profile 加载后自然正确。可写性实测:仅授账户 Full(无组/无 capability SID)的目录,WRITE_RESTRICTED 令牌可写(写检查=DACL∩restricting SIDs,账户 user SID 两边都在),无需任何 ACE 补丁。代价:首启建 profile 一次(秒级,RunnerClient 首次 spawn 后自检目录存在,缺失打 WARN)+各类依赖冷下载一份(账户级持久复用,与宿主缓存互不污染)。`TEMP`/`TMP` 维持工作区 `.everyagent/tmp` 不变(临时文件随任务走,不写脏持久 profile;对齐 codex 写根模型)。

**git safe.directory 注入**(codex 原生 `sandbox_utils::inject_git_safe_directory` 逐语义移植):仓库属主是宿主用户而命令跑在沙箱账户下,git ≥2.35.2 的 ownership 保护直接 fatal;profile 隔离切断了对宿主 `~/.gitconfig` 的借读后,宿主曾有的 `safe.directory=*` 豁免不再可见(那本就是意外依赖+泄露面,实测宿主确有 `*`)。`childEnv` 经 git 官方 env 配置机制(`GIT_CONFIG_COUNT/KEY_n/VALUE_n`)注入 `safe.directory=<向上找到的 git 树根>` 与 `<root>/*`(嵌套仓库一并信任;`.git` 为文件的 worktree 形态用 `Files.exists` 覆盖),每次 spawn 按当时 workspaceRoot 重算,零落盘,精确到本仓库树——比宿主原 `*` 更收紧。卸载:`Uninstaller` 阶段二已有 `DeleteProfileW`→`NetUserDel`(先删 profile 再删账户,顺序保证按 SID 可定位)。

**2026-10-12 修正**:「账户 Full 即可写,无需 ACE 补丁」的前提**不成立**——宿主实测评 `C:\Users\<account>\AppData\Local` 写入即 `Access denied`,且该树上残留着多个互不相同的 cap SID (M) ACE(历史 write-root grant 的痕迹),证明 profile ACL 一旦被 provisioning/grant 碰过,账户继承权限模型即失效,npm/pip 等用户级缓存全拒;此前 npm 能写 cache 是目录曾被手工 grant 过,非代码自然工作。修复三条:①`CodexCommandExecutor.withSandboxProfileRoot` 把本会话 profile 树补为写根——与工作区根同源 cap(`CapSids.workspaceWriteCapSidForRoot`,按 root 持久稳定)+同一 preflight 预配(组+cap 双主体 ACE),每会话自愈,不再依赖账户 Full 假设;②`sandboxProfileDir(NetworkIdentity)` 按会话身份取账户,修 offline 会话 env 指向 Online profile 的错配(令牌 Offline 账户、env 却指 Online profile,restricted 写检查同样全拒);③`childEnv` 补 `HOMEDRIVE`/`HOMEPATH` 重定向——NT 老式拼法 `%HOMEDRIVE%%HOMEPATH%` 仍被少数老工具用来定位 home,此前这对值从宿主进程链原样继承(指宿主用户家),与现代变量指向的沙箱 profile 形成「脑裂」:读侧静默借读宿主 `.gitconfig`/`.npmrc`/`.ssh`(区外只读不报错,泄露面),写侧受限令牌 EPERM;现按本会话账户 profile 取值,对齐真实 `LOGON_WITH_PROFILE` 登录语义(HOMEDRIVE 带尾分隔符,拼接重复分隔符属原生形态),非 Windows 单测无此约定自动跳过。

---

## 输出承载契约

> Windows PowerShell,非 ASCII 正确性的唯一保证

`powershell` 工具的子进程 **stdout/stderr 一律用文件承载**(`OsSandbox.spawnToFileRedirected`;codex 后端由 runner 的 `ChildProcess` 同样以文件句柄承载 stdout/stderr——实测受限令牌 + 无控制台时,PS 自身输出与 stderr、退出码都已正确,但 PS 内部再调原生命令仍按 GBK 解码——该缺口历史上由 `RgShim` 特判 rg 兜住,**已删除**(见「命令 stdin 契约」),改由下述 `ConsoleProbe` 统一治理;管道仅作回退)。

2026-10-05 追加实测:`CREATE_NO_WINDOW` 会让系统给子进程**新建一个隐藏控制台**,其码页取系统 OEM(中文 936、日文 932、西欧 850——随机器语言而变,不可写死),而 .NET Framework 在子进程**启动那一刻**缓存 `GetConsoleOutputCP()`,此后 `chcp` 改不动它、CLM 又禁 setter——这是 PowerShell 解原生子进程输出必错的总根,cmd/rg/git 同症状(与命令无关)。

runner 因此增加 `ConsoleProbe`:用生产同款 spawn **实测** UTF-8 能否被解对(结论只回传 ASCII 标记,避免用待验证的通道传结论)——已对则**什么都不动**;不对才设 `CP_UTF8` 并去掉 `CREATE_NO_WINDOW`,**复测通过才采用,否则把码页改回启动时读到的原值**(无配置项、不写死语言假设,本机 OEM/ACP 一律运行时查询)。**默认启用**——`EA_CONPROBE=0` 才显式关闭,且只在 runner 管道连接后的后台虚拟线程跑(见「探测/诊断不压在启动关键路径」)。

不得用管道。原因:PS 5.1 的 stdout 指向**管道**时,`[Console]::OutputEncoding` 取系统 OEM 码页(中文 Windows=936/GBK)而非控制台码页——`chcp 65001` 改的是控制台,不同步到它;沙箱账户受 WDAC/AppLocker 进入 CLM(Constrained Language Mode),属性 setter 被策略拒,**运行时也改不动**。于是原生子进程(rg/git/npm)写出的 UTF-8 字节被 PS 先按 GBK 解码(非法序列当场成 U+FFFD,信息不可逆丢失)再按 GBK 编码送回管道,读端任何"智能解码"都救不回来(现场:中文仓库里 `rg 架构 docs` 返回乱码文件名,把乱码名回灌 rg 直接 os error 2,任务卡死)。

stdout 指向**文件**时,原生子进程直接继承该文件句柄写原始字节,PS 完全不参与转码;实测同一文件里 cmdlet 中文与原生 UTF-8 **同为合法 UTF-8**,stderr 也不再被包成 CLIXML。承载文件由 worker JVM 创建并把可继承句柄交给子进程,故**不要求**沙箱账户对临时目录有写权限(受限账户下系统 TEMP 常不可写,故 `createScratchFile` 必须带工作区 `.everyagent/tmp` 兜底)。读端 `ExecResults.decodeConsoleOutput` 的 UTF-8→ANSI 智能回退自此**降级为兜底**,不再是中文正确性的依赖。

---

## cmd-chcp 包装

> 2026-10,BUG-1 混排编码的正解

上述 ConsoleProbe 在服务上下文拿不到控制台(实测 `AttachConsole`/`AllocConsole` 均 err 5,verdict 恒 `skip:no-console`),继承控制台路线不可行;而真正的破局点是**把码页设置挪到 powershell.exe 启动之前**——.NET 的 `[Console]::OutputEncoding` getter 在 PS 进程**首次访问时**才读 `GetConsoleOutputCP()` 并缓存,CLM 禁的只是运行时 setter,**外部程序 `chcp.com` 设控制台码页不受 CLM 限制**。

故 `CodexCommandExecutor.commandArgv` 把 PowerShell 命令包装成 `cmd.exe /d /s /c "chcp.com 65001 >nul 2>&1 & powershell.exe -NoProfile -ExecutionPolicy Bypass -EncodedCommand <base64(UTF-16LE)>"`:cmd 先建隐藏控制台并设 CP=65001,powershell 在**同一个**控制台里启动、getter 首读即 UTF-8——PS 自身输出与「PS 管道内捕获原生输出」的解码全部变 UTF-8,与原生工具的 UTF-8 字节**同流同码**,严格解码一次通过。`-EncodedCommand` 载荷是 base64(cmd 安全字符集,免疫 `&`/`|`/引号嵌套地狱),Java 侧 `getBytes(UTF_16LE)` 不带 BOM。实测:git log 管道捕获、rg 直出、PS 字面量混排全净,退出码经 cmd→powershell 正确传导。POWERSHELL_PREFIX 里先跑的 `chcp 65001` 对本进程已缓存无效但保留(FullLanguage/有控制台场景的加成,见其 javadoc)。此前「整流 GBK 回退」在 PS 字面量+rg 直出混排时整流误判(GBK 字面量在场→rg 的 UTF-8 也被按 GBK 解出乱码)——包装后该场景消失,回退仅剩兜底真正 ANSI 工具的价值。stderr 侧:ps 流记录在 stderr 非控制台时仍序列化为 CLIXML(与承载形态无关,只看是否控制台),由 `ExecResults.decodeClixml` 统一还原为真实错误文本——codex 后端执行器聚合处与 worker DIRECT 路径均已接入(2026-10 前者漏接,文件承载下 Write-Error 的 CLIXML 直达模型)。

2026-10 再补三笔:

1. 命令投递改**脚本文件承载**为主形态——prefix/用户命令/exit 尾部各占一行写入工作区 `.everyagent/tmp/ea-cmd-<pid>-<纳秒>.ps1`(UTF-8 BOM;`CREATE_NEW`+pid+纳秒命名,零 crypto,同 8s 教训;`powershell -File` 强制 .ps1 扩展,实测 .tmp 直接拒绝),cmd-chcp 包装不变、载荷经 `-File <工作区相对路径>`(`scriptFileArg`:`.everyagent\tmp\ea-cmd-*.ps1`,cwd=工作区根)投递——**载荷绝不能内嵌引号**(2026-12 codex 沙箱实测回归:argv 要经 runner `argvToCommandLine`(CRT 规则)**二次序列化**,载荷内字面 `"` 被转义成 `\"`,`cmd /d /s /c` 只剥**首尾**引号、`\"` 原样落到 powershell.exe 命令行,PS 按 CRT 规则解成**字面引号**→路径值两端带 `"`→`Processing -File '"…"' failed: Illegal characters in path`,退出码 -196608/0xFFFD0000 每条命令必挂;相对路径分量全部自生成、无空格无引号,任何序列化层都不会碰它,工作区含空格也免疫):PS 报错的 PositionMessage 从 -EncodedCommand 单行形态的「At line:1 char:506 + ...nue'; $DebugPreference='SilentlyContinue'; java -version 2>&1; $__EA...」(回显内部包装前缀,泄漏实现且定位不可读)变为精确引用用户命令行(「At <脚本>:2 char:1 + java -version 2>&1」);写文件失败回退 -EncodedCommand(行为无回退,仅定位质量回退)。
2. `childEnv` 注入 `JAVA_TOOL_OPTIONS=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`(putIfAbsent 不覆盖用户显式配置):JDK 18+(JEP 400)在**重定向**流上 System.out/err 默认按 `native.encoding`(中文机器=GBK,实测 `java -XshowSettings:properties` 显示 stdout/stderr.encoding=GBK)编码,与 PS 侧 UTF-8 解码冲突——直出时 GBK 字节混流触发整流 GBK 回退、PS 字面量中文反向乱码;PS 管道捕获时被按 UTF-8 有损解成 U+FFFD(不可逆,实测 mvn 中文断言消息全损);注入后中文探针 stdout/stderr 全对,代价仅每次 JVM 启动 stderr 多一行「Picked up JAVA_TOOL_OPTIONS」。
3. ConsoleProbe 探测脚本此前经 createExclusive 落 .tmp 名,-File 拒绝非 .ps1 扩展 → 探测脚本从未真正执行、measure 恒 BAD 恒回退;已改直写 .ps1。

另:8s 慢 spawn 已定案(2026-10)——真凶是 `Files.createTempFile` 内部 TempFileHelper 的静态 SecureRandom **首次取数**:无 profile 账户下 Windows Crypto(`CryptAcquireContext`)超时,实测恒 ~8.0s、与实时保护/进程创建/文件系统全无关,进程内只付一次;runner 每命令一个 JVM 即每命令付 8s。修复=输出承载文件改 JNA `CREATE_NEW` 一次到位(名字 pid+纳秒,零 crypto;`ChildProcess.OutputFiles.createExclusive`,ConsoleProbe 探测脚本同源),历史归因链(CreateProcessAsUserW/jnidispatch 解压/Defender)全部证伪。诊断打点已收敛为统一 slf4j 方案:runner 物化 classpath 补齐 slf4j-api/simple——simple 不在 worker 依赖图(builtin 模式父委派借不到、java.class.path 也没有),故两路供给:插件 pom 由 dependency-plugin 在 package 期把 slf4j-simple 复制进 target/(findTargetJars 连同插件 jar 一起进插件 classloader),RunnerMaterializer 物化源扩为「插件 codeSource + **插件 classloader URLs** + java.class.path 匹配项」三路(external 模式的 lib/*.jar 亦由 classloader URLs 覆盖)。simple 绑定落 System.err(tee→runner-stderr.log)且 `cacheOutputStream=false` 保证写 tee 后新流;spawn-timing/stdio-prep/file-timing 分段计时与 calib 对照实验全为 debug 级,默认静默,worker 进程置 `EA_RUNNER_DEBUG=1` 透传 `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug` 打开。CodexRunnerMain 的 boot 期 stage 行仍走 System.err+PENDING 缓冲(tee 安装前 logger 输出会落进无去向的旧流,属技术上必要,非框架混用)。

---

## PS-003:顶层对象输出断流修复

> 2026-12,2026-12 复测收紧归因

PS 以 `-File` 运行且 stdout/stderr 被重定向(文件**或**管道——与承载形态无关,worker DIRECT 文件承载与 codex runner 管道承载实测同病)时,脚本**顶层裸对象**输出(`Get-Location` 的 PathInfo、`[pscustomobject]`、`Select-Object` 产物等)一旦触发格式化引擎渲染,**该对象自身与其后所有由 PowerShell 管线渲染的输出**静默丢失——rc=0、stderr 空、连 CLIXML 都不产生,模型只见空结果。

**两条归因收紧(旧记录把范围说过头,已按实测改)**:①**不止 PS 5.1**——pwsh 7.6.6 在同一沙箱链路上 100% 同样触发(本产品默认宿主就是 pwsh,故这不是"老宿主"问题);②**"其后全部输出"不等于"全部字节"**——原生子进程自己写句柄的字节**保留**(实测旧形态 `Get-Location; git status; Write-Output 'TAIL'` 里 git 的 550 字节完整到达,丢的是 PathInfo 自身与其后的 `Write-Output`),即丢失范围是「裸对象 + 其后 PS 渲染条目」,据此才能解释为何同一批次时空时不空。另实测 `Get-Date`/`Get-Item`/`Int32` 顶层输出**不触发**,`Select-String | Select-Object -First 3`(MatchInfo)**不触发**,纯 `git status` 单命令**不丢**,`$PSVersionTable.PSVersion.ToString()` 等纯字符串不受影响(机理未明,疑与 host 格式化管线在无窗口控制台下的初始化路径有关;`try-catch`、`$Host.UI.RawUI` 预访问均不能救)。**管道内**的 `Format-Table`/`Out-String` 实测健康,坏的只是「顶层隐式 Out-Default」路径。

修复=`ExecResults.buildPowerShellScript(command)`:prefix/`& {`/用户命令/`} | Out-String -Stream -Width 4096`+exit 尾部逐行拼装,用户命令包进脚本块、对象文本化在**管道内**完成(`-Stream` 的必要性见「PS-004」);行边界同时保护用户命令末尾 `#` 注释不被 `}` 吞掉、保持 PS 报错 PositionMessage 精确引用用户行。语义实测不变:块内 `exit N` 终止进程且码 N(尾部不执行,与原行为一致)、`$LASTEXITCODE` 传导(`cmd /c exit 3`→rc=3)、空输出块零字节(`format` 落「无输出」占位)、`Write-Error` 等错误流照旧走 stderr+CLIXML 还原。worker `CommandExecutor`(DIRECT/windows-mic 经 shellExecutor 复用同一路径)、codex `buildScript` 主形态与 `commandArgv` 回退分支三处共用该方法。护栏见 `ExecResultsScriptTest.topBareObjectOutputSurvivesFileCarriage`(端到端:真实产物 spawn + 重定向,断言裸对象与其后 Write-Output 均到达)。

**该修复的生效前提是「runner 侧与 worker 侧形态一致」——这是本条最容易踩空的地方**:2026-12 一次线上调查中,同一会话连续多批返回空结果、而**改写成字符串插值/管道形式的批次正常**,当时的机理就是运行实例的 runner jar 仍是修复前形态(`.sandbox-bin` 陈旧 jar 遮蔽的既有风险面),源码已修但包内未修。**判据**:若某批含裸对象语句(`Get-Location` 打头几乎必然)而结果为空、同批里原生命令却有输出,先怀疑两侧形态不一致,而不是怀疑命令写错。空结果的字面呈现由前端承担——实测三处渲染「（无输出）」:`every-agent-web/src/components/task/toolViews/CommandToolView.tsx:109`、`DefaultToolView.tsx:139/143`(后者区分运行中「（等待结果…）」)、`FileToolEntry.tsx:159`;源码里已无 `(no output)` 字面量(重扫 1069 个源文件 0 命中,读取错误数 1 已显式统计而非吞掉;该串只存在于旧构建产物),二者语义等价:**空串既表示"合法无输出"也表示"输出被吞"**。

---

## PS-004:异常终止丢输出修复

> 2026-12

PS 脚本里 `Write-Output` 写的是**成功输出流对象**,而框架收口 `& { … } | Out-String -Width 4096`(**不带 `-Stream`** 的 `Out-String`)会把整条成功流**收进一个字符串、只在管道收尾时才吐**。于是脚本一旦因 `exit N`(**含 `exit 0`**)、`throw` 或 `-ErrorAction Stop` 这类**终止性中断**离开管道,缓冲未 flush、**已产生的输出全丢**:实测 `Write-Output "X"; exit 2` 只剩 `[exit code: 2]`(rc 对而内容空)、`Write-Output "X"; exit 0` 只剩「（无输出）」(像「命令没执行」)。

**判据(三条互相印证)**:①触发面=终止性中断(`exit`/`throw`/`-ErrorAction Stop`),正常结束不丢;②`Write-Host` / `[Console]::Out` **直写继承的 stdout 句柄,不受影响**(最强线索——内容没进成功流就没有缓冲可丢);③最后一条**原生命令**的非零退出码不丢(`Write-Output "P1"; cmd /c "exit 5"` 输出与码俱在,因为那是子进程的退出码、PS 管道正常收尾)。这也正是上文「调查者侧假证据陷阱」③(`-ErrorAction Stop` 把已完成统计连同中断一起丢失)的机理。

**修复**:收口改用 **`Out-String -Stream -Width 4096`**——逐条即出,终止路径下已产生的行照常到达 stdout。两宿主(PS 5.1 / pwsh 7.6.6)实测三契约同时成立:裸对象断流修复(PS-003)不变、`exit`/`throw` 前输出保留、`Out-String:Width` 免疫不变(200 字符属性仍单行 200,不折 120);唯一差别是**空输出块零字节**(不再多一个尾随空行,更贴合 `format` 的空串占位语义)。护栏:`ExecResultsScriptTest.outputBeforeTerminatingExitSurvives` / `outputBeforeThrowSurvives`(端到端真实 spawn + 重定向,断言 `exit 2` / `throw` 前的 `Write-Output` 均到达)。**回归判据**:任何「先打印再 `exit`」的合法命令返回空 → 先查收口是否丢掉了 `-Stream`,而不是怀疑命令写错。修复前的用户侧规避:关键结果用 `Write-Host`/`[Console]::Out` 输出或落文件。

---

## PS-005:工具结果文本被 JSON 字符串化修复

> 2026-12

Spring AI 的结果转换器默认是 `DefaultToolCallResultConverter`,其 `convert()` 对**所有**结果一律 `JsonHelper.toJson(result,true)`——返回 `String` 的**文本类工具**(`powershell`/`bash`/`read_file`/`create_file`/`update_file`/子 agent 四件套/`ask_user`)因此被 JSON 字符串化:外层加一对 `"`、换行变字面 `\r\n`、引号变 `\"`、反斜杠变 `\\`。对模型是纯损耗——多行输出压成一行转义串、可读性明显下降,且与 `ExecResults.format` 的纯文本形态(「stdout 数据段 + 可选 `[stderr]` 段 + exit code 尾注」)以及 `read_file` 描述里承诺的「直接返回文件文本内容(纯文本)」自相矛盾。

**证据(两处独立确认)**:①单元级——`FunctionToolCallback`(shell 工具壳)与 `ToolCallbacks.from`(=`MethodToolCallback`,文件工具/子 agent)两条注册路径,返回 `"行一\n行二"` 的工具结果**都被**转成 `"\"行一\\n行二\""`(`javap -c` 亦证实 `DefaultToolCallResultConverter` 无 String 短路,`MethodToolCallback` 的 `DEFAULT_RESULT_CONVERTER` 就是它);②运行级——产品**自身事件日志**里 `tool.result.summary` 直接就是 `"\"# ISSUES.md …\n\n…\""` 这类字面转义串,而该值就是回灌模型与落盘的同一份 `ToolResponseMessage.responseData()`。**这不是任何单次修复引入的**:全库回溯 2026-09 起的任务日志,文本类工具结果一直是该形态(属框架默认长期行为),只是「先打印再 `exit`」被 PS-004 修好之后,长输出的转义形态才变得显眼。

**修复**:新增 `PlainTextToolCallResultConverter`(`every-agent-plugin-api` 的 `dev.everyagent.plugin.api.tool`)——`String` 结果**原样透传**,非文本结果回退框架默认转换器(保住「结构化结果 → JSON 对象」语义:那类结果的 `{…}` 本就是模型要读的 JSON,不能再套一层引号);挂载点两处:`@Tool(resultConverter = …)`(注解默认值即 `DefaultToolCallResultConverter.class`,显式覆盖即可)与 `FunctionToolCallback.builder().toolCallResultConverter(…)`,已覆盖 shell 工具壳、`FileTools` 三件与 subagent / ask-user 插件(同一 helper 注册的工具不应有两种形态)。护栏:`ShellToolResultConverterTest` 三条——两条断言「挂转换器后为纯文本」(两条注册路径各一)+ 一条**反面参照**(不挂转换器即得 `"\"行一\\n行二\""`,Spring AI 若改默认行为会立刻暴露,并说明该配置不是冗余)。**判据**:任何文本类工具结果出现外层引号或 `\r\n` 字面量 → 先查该工具的 `resultConverter` 是否丢失。**一处未定位的观察(留档待查)**:本部署里 subagent 系工具(`list_agents`/`wait_agents`)的事件日志 `tool.result.summary` **已是纯文本**(形如 `{"agents":[]}`,首字符 `{`),尽管它们同样是 `@Tool` + `String` 返回值、同样经 `ToolCallbacks.from` 注册(单测证明该路径会 JSON 字符串化),而 `read_file`/`powershell` 的同一字段是转义串——即同库内两种形态并存且成因未定位(可能来自部署构建与 HEAD 的模块差异,或该部署下另有工具注入路径)。本次修复对 subagent / ask-user 也显式挂上转换器,使最终形态**与注册路径无关**,该观察不影响修复结论,但若日后要解释「为何某工具偶发带引号」,应先查此处。

---

## 命令 stdin 契约(Codex 实现)

AI 命令的 stdin 语义口径是**无输入可用、不可交互输入**(非交互/headless 工具的通用做法)。契约按**效果**表述,**实现手段各处不同且不必相同**。三档现状:

- worker DIRECT:`ProcessBuilder.Redirect.from(NUL 设备)`(§7.9,Windows=`NUL`/其余=`/dev/null`)→ 读到立即 EOF;
- wsl-ubuntu:`eagent-run.py::_stdin_null()` 在 exec 前把 fd0 重定向 `/dev/null`(wsl-direct / bwrap / seccomp 三条路径;seccomp supervisor 自身 stdin 承载控制帧除外)→ 立即 EOF;
- **codex**:建 stdin 管道 + spawn 后立即 `closeStdin()` 关写端(`CodexRunnerMain`,由 `SpawnRequest.stdinOpen()=false` 触发;终端类会话才留开通道)→ 读到 EOF;**形态是「已关闭的管道」,不是 NUL 设备**;

旧措辞「一律接 null 设备」把手段当契约,对 codex 不成立,已按效果口径改写。

- **「字符设备」与「管道」对子进程不等价(2026-12 实测,本条曾写错)**:Windows `NUL`、Linux `/dev/null` 都是**字符设备**。rg/grep 一类工具「未给路径时据 stdin 可读判输入源」的规则里,**「管道/普通文件」算可读输入 → 把 stdin 当输入源**(管道下裸 `rg <pattern>` 得空 + exit 1);**「字符设备」不算 → 回退搜 cwd**——故 DIRECT / wsl 下,裸 `rg <pattern>` **本来就能搜当前目录**。由此两条:
  - 「无路径静默空结果」这个坑**只在 codex 一档(管道)发生**;旧文本「stdin 恒为空管道/NULL 设备 → 恒得空结果 + exit 1」把两类混为一谈,**归因错误,已订正**(2026-12 实测:同一 `rg.exe`,stdin 接 NUL 搜 cwd、接空管道则空 + exit 1);
  - **反向代价(为何不把 codex 改成 NUL)**:NUL 会让 **Windows CRT 的 `_isatty()` 判出「是终端」**——其判据即「`GetFileType == FILE_TYPE_CHAR`」,而 NUL 是字符设备(2026-12 沙箱内实测:`msvcrt`/`ucrt` 的 `_isatty(0)` 在 NUL 下返回 `0x40`(=FDEV 标志位),python `sys.stdin.isatty()` 得 `True`;管道下三者依次为 `0`/`False`)。子进程据此误判自己在终端上、走交互分支,破坏本契约的「不可交互」一半。**故 Windows 上不存在同时满足「读到 EOF」与「不被误判为终端」的 stdin 形态**;codex 的「关闭的管道」是对该取舍的**正确**选择,**不得**以「治 rg 空结果」为由改成 NUL。Linux 无此矛盾:`/dev/null` 亦是字符设备,但 POSIX `isatty(3)` 走 `tcgetattr`(对 `/dev/null` 得 `ENOTTY`)→ 判假,**非**「字符设备即终端」(依 POSIX 定义,**未在本机实测**——本机无 Linux)。
  - 「不可交互」的落地**不依赖 stdin 形态**,由**显式 env** 钉住(口径同 git 插件 `NativeGit` 的 `GIT_TERMINAL_PROMPT=0`);分页器等同类项(`GIT_PAGER`/`PAGER`/`LESS`,对齐 codex 原生 `env.rs::ensure_non_interactive_pager`)本实现**尚未接线,登记为待办**。
  - 副作用必须被处理:rg / grep / findstr 一类搜索工具的通用规则是「**未给文件参数 且 stdin 非终端** → 把 stdin 当输入源」;在 **stdin 为管道**的后端(codex)→ **得空结果 + exit 1,与「代码里不存在该符号」完全同形**(codex 内实测 `findstr 模式` 无文件即如此);在 **stdin 为字符设备**的后端(DIRECT / wsl / mic 的 NUL 类句柄)→ rg 回退搜 cwd,**无此副作用**。这是 **Unix/Windows 的既有惯例,不是本实现的 bug**,故修复方向不是改 rg 语义,而是**在调用契约层根治**(ripgrep-tool 插件的 schema 化 `rg` 工具:`path` 省略=调用侧归一化为工作区根**绝对路径**,argv 恒以显式 path 终结、与 stdin 无关,详见「rg 的 stdin 语义陷阱」)+**结果层保住判读信号**——`ExecResults.format` 只在非零时附 `[exit code: N]`,而 `POWERSHELL_EXIT_TAIL` 负责把 rg 的 1/2 从 PS 传导出来,缺了传导则该歧义更严重。

---

## rg 的 stdin 语义陷阱

> 根因与设计对策

ripgrep 的**文档化行为**是「调用时未给 path 参数 且 stdin **为管道 / 普通文件** → 搜索 stdin 而非当前目录」(注意:**不是「非 tty」**——字符设备含 NUL 亦非 tty,但 rg 会搜 cwd;粗写成「非 tty」正是把本坑归因搞错的措辞根源);训练语料里的 rg 几乎全部跑在 tty 环境(= 无 path 就搜当前目录),模型由此形成稳定先验。而本环境一切 shell 工具经沙箱执行,stdin 无输入可用;**在 stdin 为「管道」的后端(codex)**,模型在 shell 里写裸 `rg <pattern>` 时 rg 把该空管道当输入源、静默得**空结果 + exit 1**,与「工作区里确实没有该内容」完全同形,模型据「正常返回空结果」误读为「无结果」并在错误前提下继续推理(2026-12 同一会话两次实测踩坑;codex 原生 Windows 沙箱 AI 命令同样 `tty:false` 走管道,非本实现特有)。**坑因是「管道」而非「NULL 设备」**——DIRECT / wsl 的字符设备 stdin 下裸 rg 搜 cwd 正常(见上条命令 stdin 契约的订正)。

**教训:工具描述压不过训练先验**——描述里先后放过「未给文件参数时会改读空 stdin,务必显式给出路径」「rg 搜索必须显式带上路径参数」两代警示子句,AI 实测仍漏传路径(警告被前面的优势宣传淹没、stdin 机理又需自行推导),故最终放弃描述治理,改在**调用契约层根治**:rg 能力收敛为独立插件 `ripgrep-tool`(二进制三档解析见 §7.17「程序附属文件」),其 schema 化 `rg` 工具把「忘传路径」消灭在 schema 层——`pattern` 必填;`path` 可选但**省略即由调用侧归一化为工作区根绝对路径**(argv 恒以显式 path 参数终结,与 stdin 完全无关),模型不传路径也得到「搜当前工作区」的正确行为;exit 1 显式标注「[rg] 无匹配」以区分「没搜到」与「执行坏了」。**结果侧 hint 兜底**:模型若仍习惯在 shell 里敲裸 rg,同插件的 `RgHintInterceptor`(工具执行拦截链上行段,order=0)识别「powershell/bash 工具 + 结果文本恰为 `[exit code: 1]` + 命令串含独立 rg token」三条件(逐条保守,stdout 有内容/exit 2 正则错误均不命中)时在结果尾部追加一行 hint,引导下一轮改用 rg 工具;显式带路径的真·无匹配同样会命中,hint 措辞条件化故仅信息性、无害。

---

## 环境侧信道闸门

> §7.17 凭据纪律的环境维度

沙箱隔离了文件系统与网络,**默认还会把宿主进程环境整块继承**给子进程——宿主 shell 里散落的 API key 因此对沙箱内任意命令(`Get-ChildItem Env:`/`env`/`cat /proc/self/environ`)可见,并随工具输出落进事件日志与模型上下文。故**一切子进程环境构造点必须先过 `SecretPatterns.scrubEnv`/`scrubInPlace`**,共 6 处:

1. `CodexCommandExecutor.childEnv`(codex 命令 env)
2. `RunnerClient.spawnWithLogon`
3. `RunnerClient.runnerEnvironment`(runner env block)
4. `TerminalPtyFactory.open`(内嵌终端 PTY)
5. `OsSandbox.runDirectCommand`
6. `OsSandbox.spawnToFileRedirected`

> sandbox-windows-mic 的 `WindowsSandbox.buildEnvBlock` 已随插件移除,2026-10。

同一 `scrubInPlace` 口径,`ProcessBuilder.environment()` 活视图只能就地删。判定两条:①**值形态指纹**(不看变量名——真事故里泄露的 key 挂在名叫 `codex` 的变量上)②**名字属凭据词族**(含裸 `key`,覆盖 `HUB_KEY`/`DEPLOY_KEY` 这类无指纹随机值)且值非短占位;`SSH_AUTH_SOCK`/`AUTHLOGONSERVER`/`PATH`/`SYSTEMROOT` 等运行时关键变量走**豁免表**(误删会直接打断 git-over-ssh 与进程启动)。审计**只打被删变量名、绝不打值**;且**按调用方去重**——剔除名单首次出现或发生变化时打 INFO,与该调用方上次相同则降 DEBUG(`SecretPatterns.repeatedScrub` 记忆;宿主环境在 worker 进程生命周期内基本恒定,逐次 spawn 都打同一份名单曾把日志刷成每秒数条的纯噪音,去重后审计证据仍在、噪音归零);规则源住在 `every-agent-plugin-api/util/SecretPatterns`(三层与插件共用,不新增跨层依赖)。文本**内容**输出掩码(`redact`/`mask` 等,作用于工具结果/事件流/模型上下文)已全部住在 `secret-redaction` 插件的 `SecretRedactor` 内,核心不持有内容脱敏逻辑也不依赖该插件;worker 与沙箱插件**自身日志出口**(DEBUG HTTP 请求体、`[exec] cmd=` 命令审计)的凭据形态掩码则是常驻纪律,走 `SecretPatterns.maskSecrets`(§7.17 ③)。

---

## 探测/诊断不压在启动关键路径

> 2026-10-05 真实回归

`ConsoleProbe`(探测式判定"本机 PS 能否解对原生 UTF-8",判据用实测而非码页算术——子进程自比"原生命令字节解码后是否等于已知串",只回传 ASCII 标记,避免用待验证的通道传结论)最初接在 `CodexRunnerMain.main` 里(`run()` 之前),结果 worker 报 `timed out after 15000ms connecting \\.\pipe\…-in`,**整个沙箱起不来**。机制不是探测本身耗时,而是它的首个 JNA 调用把 `jnidispatch.dll` 解包(本机实测约 8.2s)从 `openPipe` **之后**搬到**之前**,直接顶破 `RunnerClient.PIPE_CONNECT_TIMEOUT_MS=15s`;控制台 API 可用时还要再叠两次 powershell 探针 spawn(会顶破 `SPAWN_READY_TIMEOUT_MS`)。现状:**默认启用**(2026-10:修复 PS 管道内捕获乱码的正解,配合删除 `RgShim` 特判),`EA_CONPROBE=0` 显式关闭;仍只在 `stage("pipes opened")` 之后起虚拟线程跑——不参与任何连接预算。"继承一个码页为 65001 的控制台"是通杀所有命令的正解,但落地取决于 runner 能否拿到控制台(self→AttachConsole(parent)→AllocConsole 三级;2026-10-05 实测本机均 `ERROR_ACCESS_DENIED(5)`,该分支自动回退 `CREATE_NO_WINDOW`、verdict 落 runner-stderr.log);若现场长期拿不到控制台,下一步是给 runner 接私有桌面或回到 ConPTY 的协议代价评估。

**诊断类不得进入主路径的类加载图(2026-10-05 第二次打挂沙箱)**:`ConsoleProbe` 初版用 `org.slf4j.Logger`,而 runner 是**独立子 JVM**,物化 classpath(`.sandbox-bin` 的 9 个 jar)里**没有 slf4j** → 类初始化即 `NoClassDefFoundError: org/slf4j/LoggerFactory`;又因为 `ChildProcess.spawn` 直接调了 `ConsoleProbe.inheritConsole()`,**引用即触发 `<clinit>`**,于是"探测默认关闭"完全救不了主路径,每条命令必崩(broker 侧只见 `PeekNamedPipe failed: 109`)。教训两条:① 诊断能力要用**开关 + 类加载隔离**双重保证——结论由被调方自己的字段承接(`ChildProcess.setInheritConsoleMode`),主路径不得引用诊断类;② runner 子 JVM 里既不能用 slf4j,也不宜用 `System.Logger`(JUL 的 ConsoleHandler 绑定的是 `installStderrTee` 换流之前的旧 `System.err`,日志会丢),诊断一律走 `System.err`,与 `CodexRunnerMain` 同口径。**顺带记一条待办**:`bootLibraryPath` 依赖物化目录里预提取的 `jnidispatch.dll`,该 dll 实际**在位**(此前"目录里没有"是我用 `Select-Object -First 5` 截断列表后误读出来的),`jna native warmed` 计时只有 76ms → "每个 runner 付 8.2s JNA 解包"这条**已被分阶段计时日志否掉**。8.2s 出在 `restricted token derived`(+191ms)→`token+child spawned`(+8236ms) 之间,即 `CreateProcessAsUserW` 内部;但同一段路径也有 257ms 的会话,且工具侧三条命令墙钟实测均约 1.0s,与"每条 8.2s"直接矛盾——那批 8.2s 会话与具体命令的对应关系**尚未查清,记为未解释观测,不可当作结论去优化**。教训:分阶段计时要先与调用方墙钟对齐再下结论;`-First N` 截断的目录列表不构成"文件不存在"的证据。

---

## -cp 通配符收敛的前提(目录纯净)

> 第二类静默遮蔽

1024 字符命令行上限逼出 `RunnerClient.collapseClasspathWildcard` → `<dir>\*`,而 Java 的目录通配符会展开该目录下**全部** `.jar`。于是物化目录 `.sandbox-bin` 里任何"不在 classpath 条目内"的 jar 都被静默装进 runner JVM;同一 artifact 的旧版本 jar(`*-0.11.0.jar`)按文件名排序**早于** `*-1.0.0.jar` → 旧同名类优先命中,表现是"已 clean package、已重启,改动却毫无效果,且全程无报错"(本次 rg 修好而 git/cmd 仍坏,正是 worker 侧与 runner 侧加载了不同版本)。

防护:`RunnerMaterializer.purgeStaleArtifacts` 在物化后删除"artifact 基名与本次保留项相同、且该基名在 classpath 里只出现一次"的陈旧 jar(同基名出现两次=有意共存的双版本依赖,如 `jackson-core` 2.21.5 与 3.1.5 并列,**绝不删**——Jackson 2/3 是不同 groupId 而非新旧替代);`RunnerClient.hasForeignJar` 对目录内 classpath 之外的 jar **只警告不阻断收敛**——拒绝收敛会退回显式 classpath(9 条全路径≈1095 字符)撞上 CreateProcessWithLogonW 的 1024 上限,让沙箱整体起不来,比遮蔽更糟,故消除陈旧 jar 是物化阶段的唯一防线。

---

## 描述精简 · 第一批

> 详见 §7.10「命令 stdin 契约」中 ①移除→恢复的完整沿革

历史方案是命令前置 rg 命令名特判包装(plugin-api `shell/RgShim`:按 rg 语法判「没给路径」时补 `.`、`--files`/`--type-list` 等不读 stdin 的模式不补,并把输出口改文件承载);**已删除(2026-10 用户决策)**——命令名级特判只覆盖 rg 一个命令,与「修宿主编码环境」的正解重复;无路径语义的兜底历经「工具描述提示显式路径 → ripgrep-tool 插件 schema 契约层根治 + 结果侧 hint」演进,直跑 rg 的输出口由输出承载契约兜住。**该决策继续有效,不要重造 RgShim**;若将来要回到实现层兜底,前提是先给出「不靠命令名特判」的通用机制(rg 不接受 rc 文件里的位置参数,故无轻量做法)。

## 描述精简 · 第二批

> 2026-12,同一决策延续

按「只留**框架私有**与**本沙箱特有**两类事实」把剩余条目再压一遍,量化过程是程序生成最终 description 后逐条计数(不靠手算)。删除 codex 追加层的「用户目录已指向沙箱账户 profile(含 Maven/npm/pip/gradle 缓存),勿手动指定仓库/缓存路径」与「TEMP 在工作区 `.everyagent/tmp`,随任务清理」两条;压缩 stdin 语义(去「读它会立即得到空结果,部分后端直接读取失败」——三后端手段差异已在上一条记录,模型无从分辨也无法行动)、`-join` 条、rg 优势条(四后端同口径改为「已在 PATH,尊重 .gitignore,全仓递归远快于 findstr/grep」)、git 条(去「已注入本仓库 safe.directory」实现细节)。**残余风险**:前者删掉后,模型可能改去真实宿主用户目录找缓存或显式指定 `-Dmaven.repo.local`/npm cache 路径,那属**工作区外访问**,会走 PermissionGate 授权弹窗而非静默失败——即失效模式是「多一次询问」而非「错误结果」,故可接受;若线上观察到反复无谓弹窗,恢复该条优先于调整 gate 阈值。实测体量:powershell 基线 103 字、codex 整条描述可用分支 272 字/不可用 255 字(本轮起点 461、两轮前 833)。护栏:`ShellToolBaselineTest.baselineStaysShort` 阈值 130 字,超限即视为有人重新往里塞通用常识。(2026-12 rg 条再简化,用户决策):rg 优势条连同 §631/§7.10-stdin 的显式路径子句合并压缩为「内容搜索优先用 rg（已在path），速度更快。rg 搜索必须显式带上路径参数」——去掉「尊重 .gitignore / 远快于 findstr·grep」对照与惯例解释,只留「优先用 rg + 更快 + 必须带路径」三个可操作信号,四后端同口径(该条目已随 rg 归属下放整体退役:四后端描述终态零 rg,rg 由独立插件 ripgrep-tool 的 schema 化工具提供,见「rg 的 stdin 语义陷阱」)。

## 描述精简 · 第三批 + 描述权整体移交提供者

> 2026-12 用户决策

`ShellTool` **不再内置任何默认基线描述**——「谁提供 shell 工具,谁写全量描述」,工厂签名改为必传 `description`(`powershell(String, ShellExecutor)` / `bash(String, ShellExecutor)`,空/null 描述运行期拒绝),原基线的「工具用途/工作目录」两句随移交由四个后端(codex/windows-mic/wsl-ubuntu/DIRECT)各自声明,`description()`(全量覆盖)与 `appendDescription()`(追加)语义保留。同批按用户决策再删三条:①stdin 效果句(常量 `STDIN_NOTE` 一并删除;**stdin 契约本身不动**——见「命令 stdin 契约」,删的只是投喂模型的文案,三后端实现手段不变);②powershell 基线的 `-join`/`$OFS` 条(PowerShell 通用常识);③各后端追加层的「中文等非 ASCII 输出已正确解码」句(编码正确性由「输出承载契约」的框架机制保证,不是模型需要的操作指令)。**残余风险**:stdin 句删除后,模型尝试交互式命令只会看到超时/空结果而非「不可交互」的预先声明;非 ASCII 句删除后,模型可能自加 `[Console]::OutputEncoding`/`chcp` 类修正——实测无害(cmd-chcp 包装已在进程外收口码页),若线上因此出问题,回本条与「输出承载契约」核对再议。护栏:`ShellToolBaselineTest` 随基线删除重写为 `ShellToolDescriptionTest`(核心零默认 + 描述必传 + 覆盖/追加语义);codex 侧 `CodexBashToolProviderTest` 同步断言三条新删文案不回归;描述长度守护职责随描述权移交各提供者自担。

## 描述精简 · 第四批

> 2026-12 用户决策,单条:codex 的 git 身份提示退役

「git 不读宿主全局配置,提交须带 -c user.name=<名> -c user.email=<邮箱>」从 codex 后端描述删除,并**根治其成因**——执行器在发现 `.git` 树根时经既有 `GIT_CONFIG_COUNT/KEY_n/VALUE_n` 机制(safe.directory 同款:零落盘、每次 spawn 按 workspaceRoot 重算、非 git 语境不注入)追加注入 `user.name=EveryAgent` / `user.email=everyagent@localhost`,裸 `git commit` 直接成功(实测)。**优先级语义为有意选择**:env 配置属 command 作用域,**压过仓库本地 `user.*`**(实测:仓库本地 LocalOwner 被 env 身份覆盖,author/committer 均 EveryAgent)——工作区由 agent 操作,提交署名恒为 agent 身份属诚实归因,不再随模型各自发挥(此前同一 AI 会话三次提交出现过三个不同邮箱)。护栏:`CodexCommandExecutorTest.childEnvInjectsGitConfigForRepoRoot` 断言 4 条 KEY/VALUE 与 COUNT=4;`CodexBashToolProviderTest.backendDoesNotReAddPrunedBaselineClaims` 断言描述不再含「提交须带」「user.name」。

## 描述精简 · 第五批

> 2026-12 用户决策,单条:「实际执行 shell=<exe>」独立子句退役,shell 身份(名称+版本)并入首句且不硬编码

codex 描述开头由固定「在系统上用 PowerShell 执行真实 OS 命令」改为「在系统上用 <探测展示名> 执行真实 OS 命令」:`CodexCommandExecutor.shellDisplay()` 返回 "PowerShell <major.minor>"(版本经一次 spawn 探测 `$PSVersionTable`,进程级缓存、10s 超时,失败降级保守标注 pwsh→7+ / powershell→5.1)或 "cmd"(三级探测兜底到 cmd.exe 时**如实报 cmd**,不得硬编码 PowerShell——cmd 兜底是执行器的真实分支,`isPowerShell=false` 走原生 argv 路径,谎报会诱导模型写 PowerShell 语法)。版本判断依据(`&&`/`||` 仅 7+)仍对模型可见,只是不再单独成句。护栏:`CodexBashToolProviderTest.descriptionReportsShellIdentityInOpening`(首句含探测展示名)+ `backendDoesNotReAddPrunedBaselineClaims`(「实际执行 shell」不得回归)。

---

## 原生命令空字符串参数修正

> 2026-12 复测修正旧断言

此前记录写作「PS 5.1 引擎行为,PS 7.3 才修」,实测**归因有误**——`-File` 脚本形态下 Windows PowerShell 5.1 与 pwsh 7.6 **均完整传递空串**(`pwsh`/`powershell -NoProfile -File t.ps1 '' 'B'` → `argc=2 [|B]`,两版本一致);丢弃只发生在 `-Command` 内联形态,而该形态已随 PS-002 从两个后端彻底删除(worker `CommandExecutor` 与 codex `commandArgvForFile` 唯一形态都是 `-File`)。因此 `ShellTool` 的 powershell 基线**不再断言「当前环境是 5.1、空串必丢」**(那是把版本与执行形态两个条件混为一谈),改述为「历史 `-Command` 形态会静默丢弃,仍建议避免歧义空参」。**连带教训两条**:① 记录 shell 引擎行为必须同时绑定「版本 + 执行形态(-File/-Command)」两个条件,缺一即成误导;② 外层 PowerShell 会把传给 `-Command` 的双引号串**先行插值**(`"$args"` 被外层展开成空 → 内层收到残缺脚本报 ParserError),所以测「参数传递」这类问题必须用 `-File` 脚本承载,否则测的是自己的引号而不是被测行为。

---

## 空串歧义收口

> 2026-10-12 修复

`ExecResults.format` 两个重载在「rc=0 且 stdout/stderr/标志全空」时改为返回显式占位 `[命令执行完成(无输出)]`——此前空串既表示「合法无输出」也表示「输出被上游吞掉」,对模型不可区分;占位后残余的吞输出路径(陈旧 jar 断流、管道读线程异常、静默换轨等)会以该占位顶替空串而**当场显形**,不再被误读为「命令未执行」而反复重试(宿主实测曾因此空返回连环重试同一命令)。占位仅在原结果为空时注入,有真实输出/非零退出码/任何标志时零变化。

---

## 连带修正:工具描述不得再建议模型自加 | Out-String

> 2026-12

`ShellTool` powershell 基线原写「需要可靠读取对象输出时建议显式转字符串(如 `| Out-String` 或 `-ExpandProperty`)」,实测**方向相反且有害**:模型自加的内层 `Out-String` 按无控制台的默认 **120 列**折行(同一 200 字符属性 → 折成 120×6 行),而外层 4096 收到的是已折好的字符串、救不回来;模型**什么都不加**、把裸输出交给外层收口才是完整一行。即框架接入本身是对的,**是提示语在诱导模型破坏该接入**——这类「提示语与框架机制打架」需按「谁拥有该职责」判定:框架已做的收口/编码/传导,描述只做解释与禁止反向操作,不得再让模型重复一遍。同批处理另外两条:「`&&`/`||` 各版本报错细节」与后端追加的「实际执行 shell=<exe>」职责重叠,压缩为「不确定 shell 版本就一律用 `;`」;「空字符串参数」经上条归因修正后**整条从描述删除**(教训留本文件即可,不必每次喂模型)。新增「多值用 `-join`」:字符串拼数组按 `$OFS` 空格挤成一行是真歧义,而 `-ExpandProperty` 管不到它。回归护栏:`ShellToolBaselineTest`。

**二次修正(2026-12,用户决策):上述「框架已收口 + 不要自加 `| Out-String`」这条提示本身也从描述删除,描述对输出宽度转为完全沉默**,同批删除「`&&`/`||` 与 shell 版本」连接符细则(与后端追加的「实际执行 shell=<exe>」重复)。理由同为「属通用常识/重复表达,不必每次投喂」。**须区清楚两件事**:①被删的只是**提示语**,`ExecResults.buildPowerShellScript` 的 `Out-String -Stream -Width 4096` 收口(PS-004 后)**机制继续生效**;②由此产生的**结构性风险是真实存在的**——收口是框架私有行为、模型无法自行推知,而它从人类日常经验里学到的恰恰是「管道输出要 `| Out-String`」,即**描述现在不再拦截那个会破坏收口的动作**。**该风险已按此优先级在框架侧消除(2026-12 落地)**:`ExecResults.POWERSHELL_PREFIX` 预置 `$PSDefaultParameterValues['Out-String:Width']=OUT_STRING_WIDTH`,使「模型自不加 `| Out-String`」都走同一个 4096 默认宽——实测同一 200 字符属性,加 guard 前后内层 maxLineLen 由 **120→200**;值由常量派生(无两处硬编码),用户显式 `-Width` 仍优先,且位于 PREFIX 单行内**不占用户命令行号**,不影响 PositionMessage 定位。护栏:`ExecResultsScriptTest` 两条——形态断言(guard 在 PREFIX 且值派生)+ **端到端断言**(真实 `buildPowerShellScript` 产物直接 spawn 取原始 stdout,断言自加 `Out-String` 后仍完整一行);端到端那条同时证明该赋值在 `powershell -File` 重定向形态(CLM)下被接受。**若将来再遇「长行被折断」,先查此项是否被删,而不是往描述里加禁令。**
