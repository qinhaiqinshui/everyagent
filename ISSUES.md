# ISSUES.md — 已知问题登记清单

> 本文件是仓库级问题事实源之一:「输出静默丢失」环节清单从 `docs/ARCHITECTURE.md` §「输出承载契约」迁出后登记于此(原文件只保留链路与取舍结论,行号级待修项以此处为准,避免两处事实源漂移)。新增问题按同样格式登记:位置 / 环节 / 性质 / 状态,证据与取舍结论仍写回 ARCHITECTURE.md 对应章节。

## 一、「输出静默丢失」环节清单(实测 7 条)

口径来源:ARCHITECTURE.md §「输出承载契约」迁移条目(含三条口径修正)。**其中 2 条经核实不是 bug,登记保留以防止被反复"修复"**。

| # | 位置 | 环节 | 性质与状态 |
|---|---|---|---|
| 1 | `every-agent-plugins/sandbox-windows-codex` `CodexCommandExecutor:447` | 未知帧类型静默丢弃(不 warn、不计数) | **待修**。「worker 新、runner 旧」陈旧 jar 场景下会把输出全部丢掉而 rc 正常——正是 97f64e92(PS-003 顶层断流)的复发形态;叠加「mvn package 未 clean 时旧插件 jar 按字典序遮蔽新类」的构建风险,构成断流复发的完整链路 |
| 2 | `every-agent-plugin-api` `ExecResults:187-205` `decodeConsoleOutput` | UTF-8/ANSI 混排整体判 ANSI | **非 bug,勿修**:javadoc 明示为可接受边界,且自 cmd-chcp 包装后上游已消除该场景 |
| 3 | `every-agent-plugin-api` `ExecResults:290-312` `decodeClixml` | 抽不到文本时返回空段 | **非 bug,勿改降噪逻辑**:有意降噪且含残块兜底;真实风险仅「正则模式漂移」,正解是补端到端护栏测试(见 §三) |
| 4 | `ChildProcess:523` | 空 catch 吞掉 tail 读线程异常 | **待修**(最小改:补异常透出,与文件承载分支对等) |
| 5 | `ChildProcess:533` `awaitOutputReaders` | 排空结果不外传 | **待修(接口缺陷)**。口径修正:早前记作「丢弃 `done.await()` 返回值」不准确——该方法**返回类型本就是 `void`**,属接口不外传;修法须**改签名**而非补调用 |
| 6 | `ChildProcess:533/:555` | 承载文件排空超时不外传,且超时后照删承载文件 | **待修**。注意 `truncated` 标志是父侧字节上限判定,够不到此处的超时删除路径 |
| 7 | `ChildProcess:594` `tryCreate` | 三条 `return null` 路径默认级别无 warn | **待修**。后果:`:145/:152` 静默换轨回管道承载——承载语义一变,编码/断流/CLIXML 表现全变,排查难度倍增 |

## 二、2026-10-12 登记(本轮排查新增)

以下来自 desktop 打包环境(v1.0.1)实测排查,证据链与修复记录见 ARCHITECTURE.md 对应条目:

| 位置 | 问题 | 状态 |
|---|---|---|
| 构建流程 | `mvn package` 未 clean 时,已安装的旧插件 jar 在类路径按字典序遮蔽新构建类——#1 的温床(ARCHITECTURE.md §635 已记风险,此处登记为**流程待办**:构建强制 `clean package`,启动日志打印沙箱插件 jar 指纹) | 待办 |
| `WorkerToolEventAdvisor:257` | `exec.terminal()`(停止任务)时跳过 tool.result 事件发射,冷启动回放以「[上一轮被中断,无结果]」占位——属可接受的停止语义,登记备查 | 备查(不修) |
| worker 日志 | `worker.out.log` 无轮转,实测已达 318MB | **已修(2026-10-12)**:根因是 CONSOLE appender 输出被 desktop 重定向进该文件、与 worker.log 双份落盘。worker 的 `logback-spring.xml` 改为本地定义 CONSOLE + ThresholdFilter 门控(`worker.console-log-threshold`,默认 **OFF**),`application.yml` 默认关控制台并关 banner(`spring.main.banner-mode=off`);dev profile 恢复 INFO/console;打包临时排障 `-Dworker.console-log-threshold=INFO`。stdio 重定向保留(JVM 原生崩溃输出仍落该文件作现场证据),正常情况下近零大小 |
| codex 沙箱 ACL | 历史手工 grant 的 cap SID ACE 残留永不回收(ACL 噪声);已由「profile 树纳入每会话写根」修复(2b918304)绕开依赖,但残留清理无机制 | 待办 |

## 三、护栏与红线

- **让失败可见若需新增 IPC 字段,必须同步 `.sandbox-bin` 物化清单**,否则即制造 #1 的复发形态(ARCHITECTURE.md 红线原文)。
- #3 的正解:为 CLIXML 抽取补**端到端护栏测试**(真实产物驱动,防正则模式漂移),不是改降噪逻辑。
- 空串歧义已收口(2026-10-12,253ea483):rc=0 且全空时返回显式占位 `[命令执行完成(无输出)]`——上表任一待修项的实际吞输出将**以占位显形**而非伪装成「命令未执行」。
