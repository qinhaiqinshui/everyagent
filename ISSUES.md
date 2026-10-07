# 问题清单（待修 / 未闭环）

本文件登记**已定位但尚未修复**的问题，供后续按优先级处理。每条都给可跳转的
`文件:行`、触发条件、当前是否有信号透出、以及建议修法。

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

| # | 环节 | 级别 |
|---|---|---|
| 1 | tail 读线程异常空吞 | **真 bug** |
| 2 | 排空超时不外传 + 超时后照删承载文件 | **真 bug** |
| 3 | 文件承载创建失败，静默换轨回管道 | **真 bug** |
| 4 | 协议演进时其余帧静默丢弃 | **真 bug**（低频） |
| 5 | 脚本落盘失败回退 `-EncodedCommand` | 有意降级，仅对模型静默 |
| 6 | 混排编码整体判 ANSI | 已知设计边界（有注释 + 上游缓解） |
| 7 | CLIXML 抽不到文本返回空段 | 有意降噪（风险仅在模式漂移） |

### 1. tail 读线程异常被空 catch 吞掉

- **位置**：`every-agent-plugins/sandbox-windows-codex/src/main/java/dev/everyagent/plugin/sandbox/codex/runner/ChildProcess.java:523`
  （`startFileTailReader` 定义于 `:496`，由 `startOutputReaders` 在文件承载下调用 `:461-462`）
- **触发**：承载文件打不开 / 读 channel 异常 / tail 循环内任何 `RuntimeException`
- **当前信号**：**无**。catch 块体只有一行注释「读不到即提前收尾;退出码仍由 waitForExit 给出,不影响会话」
- **为什么危险**：退出码正常 + 输出为空 = 与"命令确无输出"完全同形，且这是**唯一**会整段吞掉 stdout 的点
- **建议修法**：把异常类型与已读字节数写进该流的结果文本（如 `[输出承载异常: … 已读 N 字节]`），
  使模型侧可见；常量放 `plugin-api/ExecResults`，不得让插件反向依赖 worker（§14.9）

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

### 4. 未知帧静默丢弃

- **位置**：`every-agent-plugins/sandbox-windows-codex/src/main/java/dev/everyagent/plugin/sandbox/codex/CodexCommandExecutor.java:447`
  注释「其余帧（父→runner 方向不会出现；容忍协议演进）静默丢弃」
- **触发**：runner 与 worker 版本不一致、IPC 协议演进（如 `.sandbox-bin` 陈旧 jar 遮蔽场景）
- **后果**：若某天输出改用新帧类型承载，旧 worker 会把**输出全部丢掉且 rc 正常**——
  这正是「worker 新、runner 旧 → 输出落在没人读的地方」的复发形态
- **建议修法**：未知帧计数，会话结束时若非零则附 `[协议告警:丢弃 N 个未知帧 <类型>]`；
  并在 `.sandbox-bin` 物化清单校验里加协议版本比对（配合 §7.10）

### 5. 脚本落盘失败回退 `-EncodedCommand`（仅对模型静默）

- **位置**：`CodexCommandExecutor.java:343-362`（catch 后 `argv = commandArgv(command)`，
  并 `LOG.log(WARNING, "[exec] 命令脚本文件承载失败,回退 -EncodedCommand: {0}")`）
- **当前信号**：**worker 侧有 WARNING，模型侧无**
- **后果**：行为不变，但**PS 报错定位质量退化**（`PositionMessage` 不再引用用户命令行；
  见同文件 `:535` 注释与 §7.10 PS-002/PS-003）
- **建议修法**：优先级低。若要让模型知情，附一行 `[降级:脚本承载失败,报错行号可能不准]`

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

| 事项 | 状态 |
|---|---|
| **包内 runner 仍是 PS-003 修复前形态**，导致本会话仍在出现「含裸对象语句 → 空结果」（实测旧形态 `outLen=2` vs 修复后 `263`）。§7.10 已给判据 | 待重打包（`copy:plugins` + dist + 重启 worker）才能生效；本仓 8 次提交均未进包 |
| 插件自带 `bin/rg.exe` 未进包（已修 `copy-plugins.mjs`），且当前运行实例里 **rg 实际不可用而描述谎报可用**；我加的三档回退第二档 `runtime/bin/rg.exe` 实测可用（ripgrep 15.2.0） | 同上，需重打包验证 |
| `NetworkSlashProviderTest` 6 个 error：上游把 `TaskService.get()` 返回类型改为 `TaskRuntime`，测试桩仍造 `ExecContext` | 既有欠账，已用 `git archive` 基线对照确认与 shell 工具改动无关 |
| WSL 托管分支的理论缺口：用户手工以同名 `EveryAgent` 从别处导入 rootfs 时，"镜像出处"判据会误判为可用 | 已记 §7.10；若要真判需 `command -v rg` 探测（成本权衡同节） |
| 宿主 `runtime/bin` 里的 rg 无任何机制注入 WSL 发行版（需同时解决 drvfs 可达性与 seccomp 禁 execve） | 仅记录，无方案 |

---

*本清单由 2026-12 的「powershell 工具输出为何为空」调查产出的未修项整理而成；
调查结论与链路事实以 `docs/ARCHITECTURE.md` §7.10 为准，本文件只登记待修动作。*
