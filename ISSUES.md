# 问题清单（待修 / 未闭环）

本文件登记**已定位的问题**及其处置状态，供后续按优先级处理。每条都给可跳转的
`文件:行`、触发条件、信号透出情况、以及修法与修复记录（2026-10-08 复核+复验：一、1-5
已修复并经重打包重启端到端复验通过；三、前三条已闭环/消解；四.8 已闭环；四.9 已修复，
待重打包复验）。

判定原则（贯穿全部条目）：**「命令成功且确无输出」与「输出被吞」必须在工具结果文本上
分得开**。后者若表现为前者，AI 会把执行失败读成"没有结果"并在错误前提下继续推理——
这比直接报错更危险。修复方向一律是**让失败可见**，不是把提示加进工具描述
（按既定分工：证据进文档，描述不承载通用常识，见 `AGENTS.md` 与 `docs/ARCHITECTURE.md` §7.10）。

---

## 一、输出静默丢失环节（codex 沙箱 shell 工具链路）

背景：`powershell` 工具由 `sandbox-windows-codex` 提供，命令在受限令牌沙箱里以
`-File 临时脚本` 执行；输出承载有**两条路径**——文件承载（`OutputFiles`，工作区
`.everyagent/tmp` 下的 `ea-codex-out-*/err-*.tmp`）与管道承载（命名管道 + runner tail）。
下表的"静默"指**runner/worker 日志之外，模型收到的结果文本里没有任何异常痕迹**。

| # | 环节 | 级别 | 状态（2026-10-08 复核+复验） |
|---|---|---|---|
| 1 | tail 读线程异常空吞 | **真 bug** | **已修复**（重打包重启后端到端复验通过：部署 jar 内常量在位） |
| 2 | 排空超时不外传 + 超时后照删承载文件 | **真 bug** | **已修复**（同上） |
| 3 | 文件承载创建失败，静默换轨回管道 | **真 bug** | **已修复**（同上） |
| 4 | 协议演进时其余帧静默丢弃 | **真 bug**（低频） | **已修复**（同上） |
| 5 | 脚本落盘失败回退 `-EncodedCommand` | 有意降级，仅对模型静默 | **已修复**（同上） |
| 6 | 混排编码整体判 ANSI | 已知设计边界（有注释 + 上游缓解） | 维持 |
| 7 | CLIXML 抽不到文本返回空段 | 有意降噪（风险仅在模式漂移） | 维持 |

### 1. tail 读线程异常被空 catch 吞掉

- **位置**：`every-agent-plugins/sandbox-windows-codex/src/main/java/dev/everyagent/plugin/sandbox/codex/runner/ChildProcess.java:523`
  （`startFileTailReader` 定义于 `:496`，由 `startOutputReaders` 在文件承载下调用 `:461-462`）
- **触发**：承载文件打不开 / 读 channel 异常 / tail 循环内任何 `RuntimeException`
- **当前信号**：**无**。catch 块体只有一行注释「读不到即提前收尾;退出码仍由 waitForExit 给出,不影响会话」
- **为什么危险**：退出码正常 + 输出为空 = 与"命令确无输出"完全同形，且这是**唯一**会整段吞掉 stdout 的点
- **建议修法**：把异常类型与已读字节数写进该流的结果文本（如 `[输出承载异常: … 已读 N 字节]`），
  使模型侧可见；常量放 `plugin-api/ExecResults`，不得让插件反向依赖 worker（§14.9）
- **已修复（2026-10-08）**：`startFileTailReader` 的 catch 经 `emitCarrierFailure` 把
  `[输出承载异常: <异常> 已读 N 字节]` 作为 Output 帧补进该流（N 由新增的 `emittedTotal`
  累计）；前缀常量 `ExecResults.CARRIER_TAIL_FAILURE_PREFIX` 为编译期内联，runner 物化
  classpath 不变。未新增 IPC 字段，不触 `.sandbox-bin` 物化清单红线。

### 2. 排空超时不外传，且超时后照样删承载文件

- **位置**：同上 `ChildProcess.java:533`（`awaitOutputReaders` **返回类型是 `void`**，
  `done.await(timeoutMs, …)` 的布尔结果被直接丢弃）；`:555`（`close()` 内
  `awaitOutputReaders(1_000L)` 后紧接 `files.deleteQuietly()`）
- **触发**：孙进程持有写端导致尾部未排空 / tail 落后超过 1000ms 宽限
- **当前信号**：**无**。`SessionRun.truncated` 是**父侧字节上限**判定（`:440` `appendCapped`），
  够不到这里，因此截断标记不能覆盖本场景
- **后果**：输出**尾部字节**静默消失（长输出被拦腰砍，且看不出被砍）
- **建议修法**：`awaitOutputReaders` 改为返回 `boolean`，超时即在结果尾部附
  `[降级:输出未在宽限内排空,尾部可能缺失]`；或删文件前二次确认已排空
- **已修复（2026-10-08）**：`awaitOutputReaders` 签名改 `boolean`（`ConsoleProbe` 两处
  调用忽略返回值，兼容）；`runSession` 在 **Exit 帧之前** 对未排空发
  `ExecResults.DRAIN_TIMEOUT_NOTE`（赶在 worker 收帧循环收口前，落在聚合文本尾部）；
  `close()` 内宽限超时落 runner warn 日志（彼时 Exit 多已发出，模型侧由 runSession 层覆盖）。

### 3. 文件承载创建失败 → 静默换轨回管道承载

- **位置**：`ChildProcess.java:145`（`OutputFiles.tryCreate(cwd)`）、`:152-156`
  （失败即落到 `spawnViaPipes`）；`tryCreate` 定义 `:594`，其三条 `return null` 路径
  （`scratchDir` 为 null / 两个文件未全建成 / `catch (RuntimeException)` 吞异常）
  **只有 debug 级 file-timing 日志，默认级别下无任何 warn**
- **触发**：`.everyagent/tmp` 不可写、独占创建失败、残留文件冲突等
- **当前信号**：**无**（默认日志级别）
- **为什么危险**：换轨即改变承载语义 → 编码路径、断流风险、CLIXML 表现**全都变了**，
  而出错时无人知晓当前走的是哪条路径。排查时会被严重误导
- **建议修法**：静默换轨一律在结果尾部留一行 `[降级:文件承载不可用,改用管道承载]`；
  `tryCreate` 的失败原因升级为 warn
- **已修复（2026-10-08）**：`tryCreate` 三条 `return null` 路径（scratchDir null /
  独占创建失败 / `catch RuntimeException`）均补 warn；`ChildProcess.degradedToPipes()`
  暴露换轨事实，`runSession` 在 Exit 帧前发 `ExecResults.CARRIER_FALLBACK_NOTE`。

### 4. 未知帧静默丢弃

- **位置**：`every-agent-plugins/sandbox-windows-codex/src/main/java/dev/everyagent/plugin/sandbox/codex/CodexCommandExecutor.java:447`
  注释「其余帧（父→runner 方向不会出现；容忍协议演进）静默丢弃」
- **触发**：runner 与 worker 版本不一致、IPC 协议演进（如 `.sandbox-bin` 陈旧 jar 遮蔽场景）
- **后果**：若某天输出改用新帧类型承载，旧 worker 会把**输出全部丢掉且 rc 正常**——
  这正是「worker 新、runner 旧 → 输出落在没人读的地方」的复发形态
- **建议修法**：未知帧计数，会话结束时若非零则附 `[协议告警:丢弃 N 个未知帧 <类型>]`；
  并在 `.sandbox-bin` 物化清单校验里加协议版本比对（配合 §7.10）
- **已修复（2026-10-08）**：`aggregate` 收帧循环对未知帧按类型名（`getClass().getSimpleName()`）
  计数，会话尾经 `ExecResults.appendNote` 附告警行。无新增 IPC 字段故不触物化清单；
  协议版本比对仍留待 §7.10。

### 5. 脚本落盘失败回退 `-EncodedCommand`（仅对模型静默）

- **位置**：`CodexCommandExecutor.java:343-362`（catch 后 `argv = commandArgv(command)`，
  并 `LOG.log(WARNING, "[exec] 命令脚本文件承载失败,回退 -EncodedCommand: {0}")`）
- **当前信号**：**worker 侧有 WARNING，模型侧无**
- **后果**：行为不变，但**PS 报错定位质量退化**（`PositionMessage` 不再引用用户命令行；
  见同文件 `:535` 注释与 §7.10 PS-002/PS-003）
- **建议修法**：优先级低。若要让模型知情，附一行 `[降级:脚本承载失败,报错行号可能不准]`
- **已修复（2026-10-08）**：回退发生处置 `scriptFallback` 标志，`aggregate` 返回后以
  `ExecResults.appendNote` 在 stderr 尾部附 `ExecResults.SCRIPT_FALLBACK_NOTE`
  （`SessionRun` 为 record，重建实例）。

### 6. UTF-8/ANSI 混排整体判 ANSI（已知设计边界，非未登记 bug）

- **位置**：`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/shell/ExecResults.java:187-205`
  （严格 UTF-8 + `CodingErrorAction.REPORT`，失败则 `isIncompleteUtf8Tail` 判截断残尾宽容解码，
  否则整体 `new String(bytes, ansiCharset())`）
- **实况修正**：javadoc 已**明写**该边界（"同一字节流内 UTF-8 与 ANSI 中文混排时只能整体择一,
  出现首个非法 UTF-8 字节即整体判为 ANSI,属罕见场景,可接受"），且自 2026-10 起 codex 经
  cmd-chcp 包装让 PS 自身输出也走 UTF-8，混排场景**已在上游消除**，此处只剩兜底真正 ANSI 工具
- **后果**：内容乱码（不是"空"，可见性尚可），但仍不报错
- **建议修法**：不建议改行为。若要更稳，可在整段判 ANSI 时附一行编码提示

### 7. CLIXML 抽不到文本返回空段（有意降噪）

- **位置**：`ExecResults.java:290-312`（`decodeClixml`）+ `:316-322`（`messagesFromClixml`
  先取闭合块、空则取开放标签到段尾）
- **实况修正**：javadoc 明写"抽不到任何文本时返回空段(纯 progress 噪声)"，是**刻意的降噪**，
  且已对残块做了兜底（`:305-308`）
- **真实风险面**：仅当 `CLIXML_TEXT` / `CLIXML_TEXT_OPEN` 两个正则与 PowerShell 实际输出
  **模式漂移**时，真实的 error 记录会被当噪声删掉 → 静默吞错误
- **建议修法**：加一条端到端护栏测试（真跑 `Write-Error`、`Write-Warning`、异常抛错，
  断言还原文本非空），把"模式漂移"变成可发现回归，而不是改降噪逻辑

---

### 修复时的红线（务必先读）

1. 让失败可见需新增 IPC 字段时，**必须同步 `.sandbox-bin` 物化清单**——否则 worker 新、
   runner 旧，输出落在没人读的地方，即条目 4 描述的复发形态。
2. 提示常量留在 `every-agent-plugin-api/ExecResults`，**不得让插件依赖 worker**（§14.9）；
   不新增事件 / 不改 wire 语义（§14.0）。
3. 触及输出承载形态的改动，须一并复验 **PS-003**（顶层裸对象断流）与 **CLIXML 还原**
   两条既有结论，它们与这里的 1/2/3 同链路。

---

## 二、本轮复核与原始调查记录的出入（防误导后人）

上表的行号与措辞由**本会话逐条读码复核**得出，非转述。复核中修正了调查记录三处：

1. 条目 2「`awaitOutputReaders` **丢弃** `done.await()` 返回值」——实况是该方法**返回类型
   本就是 `void`**（`:533`），故不是"调用方漏用返回值"，是**接口不外传**；修法因此必须改签名。
2. 条目 6 提到实现用了 `firstIllegalInputByte`——本仓**不存在**该 API 调用；实际是
   `CharacterCodingException` + `isIncompleteUtf8Tail(bytes, bb.position())`，
   并且**已有截断残尾宽容解码分支**，比记录的情况更好。
3. 条目 7 被列为「静默丢失」——实为**有意的降噪设计**（含残块兜底），真正的风险只是
   正则模式漂移，故降级为"加护栏测试"，而非改逻辑。

同时**新增一条**（原调查未覆盖）：条目 4 未知帧静默丢弃，`CodexCommandExecutor.java:447`。

---

## 三、其它已定位、尚未处理的关联事项

| 事项 | 状态（2026-10-08 复核） |
|---|---|
| **包内 runner 仍是 PS-003 修复前形态**，导致本会话仍在出现「含裸对象语句 → 空结果」（实测旧形态 `outLen=2` vs 修复后 `263`）。§7.10 已给判据 | **已闭环**：当前运行实例实测裸对象输出正常（`Get-Date`、`$PSVersionTable.PSVersion` 多行对象完整输出），重打包+重启已生效 |
| 插件自带 `bin/rg.exe` 未进包（已修 `copy-plugins.mjs`），且当前运行实例里 **rg 实际不可用而描述谎报可用**；我加的三档回退第二档 `runtime/bin/rg.exe` 实测可用（ripgrep 15.2.0） | **已闭环**：实测 `rg --version` = ripgrep 15.2.0，PATH 注入生效（安装目录插件 `bin\rg.exe`）；`runtime/bin/rg.exe` 亦在 |
| `NetworkSlashProviderTest` 6 个 error：上游把 `TaskService.get()` 返回类型改为 `TaskRuntime`，测试桩仍造 `ExecContext` | **已消解**：该测试与 `TaskRuntime` 已随重构删除（全仓 `rg` 无匹配；历史提交 `6a5f6375` 等） |
| WSL 托管分支的理论缺口：用户手工以同名 `EveryAgent` 从别处导入 rootfs 时，"镜像出处"判据会误判为可用 | 维持记录（本次未验证 WSL 行为）；若要真判需 `command -v rg` 探测（成本权衡同 §7.10） |
| 宿主 `runtime/bin` 里的 rg 无任何机制注入 WSL 发行版（需同时解决 drvfs 可达性与 seccomp 禁 execve） | 维持记录，无方案 |

---

## 四、2026-10-08 复核记录与新发现

复核方式：以当前工作区源码逐行读码核对行号（一、全部 7 条行号精确命中），并在运行实例
（desktop 模式 codex 沙箱）实测。一、1-5 已全部修复（见各条目「已修复」行），修复统一
走 **既有 Output 帧文本通道**（Exit 帧之前补 stderr 行）+ `ExecResults.appendNote` 落位，
**零新增 IPC 字段、零物化清单变更、零 wire 语义变化**；护栏测试
`every-agent-plugin-api/src/test/.../ExecResultsNoteTest.java`（5 例）。
复验方式：重打包（部署 jar 2026-10-08 11:50:06）+ 重启 worker 后，① 解包部署 jar 核对
五个提示常量分布——`ChildProcess.class`：`[输出承载异常: `+`degradedToPipes`；
`CodexRunnerMain.class`：排空/换轨两降级提示（编译期内联进 runner，物化清单未变）；
`CodexCommandExecutor.class`：协议告警+脚本回退+`appendNote`+`unknownFrames`；② 运行
实例行为复验：裸对象输出（`Get-Date`/`$PSVersionTable` 多行完整，PS-003 不回归）、
中文错误文本完整可读（CLIXML/解码不回归）、`rg --version`=15.2.0 且中文匹配渲染正确、
正常命令输出无任何 `[降级:…]` 提示污染（提示仅在真异常时出现）。**复验全部通过。**

复核中发现的既有测试失败与本次改动无关（`git stash` 基线对照坐实）：
`CodexCommandExecutorTest` 2 例（junit 临时目录落在 eagent 仓库内污染 git root 探测）、
`SandboxAccountsTest`/`AclPrimitivesTest`（code 5 需管理员）、`WindowsRunnerSmokeTest`
（受限令牌套娃）/`WindowsSessionSmokeTest`（管道 232）——均属在 codex 沙箱内跑测试的
环境限制。

### 8. stderr 直出残留 ANSI/VT 转义序列（用户报「命令执行结果有乱码」的真相）

- **现象**：模型收到 `[31;1m...` 等原始 ESC 序列（用户会话实报）。
- **触发**：pwsh 7.x 错误流**直出**（不经 `2>&1` 合并）且 VT 着色开启时，stderr 字节里
  带颜色码；解码链（`ExecResults.decodeConsoleOutput` → `decodeClixml`）不剥离 VT 序列。
  2026-10-08 实测：`Get-ChildItem <不存在路径>` 的 stderr 满屏 ESC；`2>&1 | Out-String`
  合并后则无。
- **影响**：不吞信息（错误文本完整可读），但污染输出；与条目 6 的编码乱码是**不同形态**
  （此处字节本身是合法 UTF-8，只是含控制序列）。
- **已修复（2026-10-08）**：`ExecResults` 新增 `stripAnsi`（`VT_ESCAPE` 正则：CSI 参数/
  中间/终止字节、OSC 含 BEL/`ESC\`/串尾截断三种终止、其余两字符 ESC 序列；正文与中文
  不动，无 ESC 字节零开销直返），在 `decodeClixml` 的**全部返回路径**出口应用——该方法是
  stderr 解码链统一出口（codex 聚合处 `CodexCommandExecutor:472` 与 worker DIRECT
  `CommandExecutor:232` 都汇入），故两后端全覆盖；stdout 不经此方法，不受影响。
  护栏测试 `ExecResultsAnsiStripTest`（6 例，含无 CLIXML 直出形态与 CLIXML 载荷混色形态）。
- **复验（2026-10-08 重打包 12:09 + 重启后）**：**通过**。同一 repro
  （`Get-ChildItem <不存在路径>`，stderr 直出）由重启前满屏 `\u001B[31;1m` 变为
  **完全干净**——错误文本、定位行、中文引号完整可读；回归项全过：裸对象输出
  （PS-003）、中文、rg 15.2.0 均正常。**本条闭环。**

### 9. PS 报错文本暴露命令脚本承载的临时路径（四.8 剥离 ESC 后显形）

- **现象**：`Get-ChildItem: C:\…\eagent\.everyagent\tmp\ea-cmd-10472-53f65234ee4.ps1:3`
  ——报错头把 codex 后端的命令投递脚本（`writeCommandScript` 落盘、会话结束即删）的
  **绝对路径**打了出来（相对 `-File` 载荷被 pwsh ConciseView 解析成绝对）。
- **定性**：非新问题——路径一直在错误文本里,此前被 ANSI 噪声淹没;四.8 剥净 ESC 后显形
  （该 repro 同时验证了 stripAnsi 生产生效）。不是安全洞（工作区路径本就可见）,但引用的
  文件执行完即不存在,属实现细节泄漏 + 对模型是噪声。DIRECT 后端走
  `buildPowerShellScript` 单行形态,无文件落盘,不受影响。
- **已修复（2026-10-08）**：`ExecResults.maskScriptPath`——执行器在运行时精确提供本次
  脚本三种形态（绝对 / 相对 fileArg / 裸文件名）,大小写不敏感替换为
  `SCRIPT_PATH_MASK`（`<script>`）;`:行号` 与 `Line |` 块保留,定位质量不丢。只掩蔽
  本次形态、不按 `ea-cmd-*` 通配扫（不误伤并列出其它脚本的合法输出）。接入点=
  `CodexCommandExecutor` aggregate 之后（先掩蔽后落降级提示）。护栏测试
  `ExecResultsScriptMaskTest`（4 例,含用户实报形态）。**待重打包重启后复验。**

---

*本清单由 2026-12 的「powershell 工具输出为何为空」调查产出的未修项整理而成；
调查结论与链路事实以 `docs/ARCHITECTURE.md` §7.10 为准，本文件只登记待修动作。*
