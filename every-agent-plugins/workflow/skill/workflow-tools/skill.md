---
name: 工作流编排
description: 当用户任务与工作流相关时使用本技能。
tools: [workflow_create, workflow_get, workflow_update, workflow_delete, workflow_list, workflow_run, workflow_rerun, workflow_instance_status, workflow_instance_log, workflow_instance_events, workflow_instance_cancel]
---

# 工作流编排

工作流 = 可复用的多步骤执行图：开始节点先行，命令节点经系统 shell 执行（Windows 默认=PowerShell〔pwsh 优先/内置 5.1 回退，宿主极端无 PowerShell 才回退 `cmd.exe /c`〕、Unix=`/bin/sh -c`，详见「命令节点执行环境」），AI 节点由命名 agent（持续会话线）执行；边条件决定路由（支持循环，maxRuns 兜限；多入边节点可选 `joinMode:"all"` 汇合屏障，见「汇合与多入边语义」）；运行变量在 command/input 里以 `{{varName}}` 注入。
本技能的十一个工具会在你读取本知识包后自动加入工具列表，直接按列表中的定义调用即可。

## 适用条件
- 用户要建立可复用、可多次运行的多步骤流程（生成/构建/检查流水线等）。
- 需要 AI 分支路由、循环重试、多 agent 协作编排时。
- 管理已有工作流定义（查看/改/删/列出）时。
- 在当前任务内运行工作流并跟进其过程/结果/故障时。

## 工具参考
以下十一个工具在你读取本知识包后自动出现在工具列表中（含参数定义），按列表内工具正常调用即可，无需记忆参数。

- `workflow_create`：创建定义，返回 `{id}`。
- `workflow_get`：返回完整定义 JSON。
- `workflow_update`：全量替换更新（非增量合并）。
- `workflow_delete`：删除定义（历史运行实例保留）。
- `workflow_list`：定义摘要列表，按更新时间倒序。
- `workflow_run`：**在当前任务内运行工作流，阻塞至终态**；成功返回结构化摘要，失败返回 `{error,nodeId,runNo,context,hint}`（详见下文「workflow_run 的返回与字段语义」）。
- `workflow_rerun`：**从失败实例的中断节点续跑**（阻塞至终态，语义同 run）；已完成节点不重跑，runNo 延续（详见下文「workflow_rerun 的语义与边界」）。
- `workflow_instance_status`：实例状态摘要（status/nodeStates/error/pendingDecision/变量/耗时；不含定义快照）。
- `workflow_instance_log`：实例节点运行日志（runs 投影，按 runNo 升序取末尾 `tail` 条、缺省 10；**可选 `nodeId` 只看该节点**；stdout/stderr/text 各截断 4000 字符；每条含 `runNo` 实例级全局序号与 `attemptIndex` 该节点第几次尝试，从 1 起）。
- `workflow_instance_events`：实例事件流直通 `{events:[{seq(字符串),ts,event,payload}],nextSeq}`。
- `workflow_instance_cancel`：取消运行中的实例（已终态报错）。

### definition JSON 结构
```json
{
  "name": "小说生成", "description": "可选说明",
  "variables": [{"name": "novelName", "label": "小说名", "required": true, "defaultValue": null, "description": "可选"}],
  "onError": "fail | supervisor | askUser",
  "supervisor": {"modelConfigId": "可选", "systemPrompt": "可选(督导 agent)"},
  "nodes": [
    {"id": "n1", "type": "start",   "name": "开始", "x": 40,  "y": 200},
    {"id": "n2", "type": "command", "name": "初始化", "x": 240, "y": 120, "command": "python -c \"import pathlib; pathlib.Path('out/{{novelName}}').mkdir(parents=True, exist_ok=True)\"", "timeoutMs": 60000, "maxRuns": 2},
    {"id": "n3", "type": "ai",      "name": "生成大纲", "x": 440, "y": 120, "agent": "writer", "modelConfigId": null, "systemPrompt": "你是资深小说作者", "input": "请为《{{novelName}}》拟一份章节大纲", "maxRuns": 2},
    {"id": "n4", "type": "ai",      "name": "润色", "x": 640, "y": 40,  "agent": "writer", "input": "润色上一节点产出"},
    {"id": "n5", "type": "command", "name": "汇总", "x": 840, "y": 80,  "command": "python scripts/merge.py \"out/{{novelName}}\"", "joinMode": "all", "maxRuns": 3}
  ],
  "edges": [
    {"id": "e1", "from": "n1", "to": "n2"},
    {"id": "e2", "from": "n2", "to": "n3", "condition": {"type": "onSuccess"}},
    {"id": "e3", "from": "n3", "to": "n4"},
    {"id": "e4", "from": "n3", "to": "n5"},
    {"id": "e5", "from": "n4", "to": "n5"}
  ]
}
```
示例要点：
- n2 的目录创建写的是**两侧兼容**的 python pathlib 一行——`mkdir -p out/{{novelName}}` 是 POSIX 专有写法（PowerShell 的 `mkdir` 是 `New-Item` 别名，`-p` 只是碰巧缩写命中 `-Path` 才能跑，别依赖）；跨平台范式见「Windows 可移植命令范式」。
- n5 演示**多入边汇合**：入边 e4（大纲分支）+e5（润色分支），`"joinMode": "all"` = 两条入边各激活一次才触发**一次**执行（maxRuns 按「触发执行次数」计=3 次额度，不用按入边数手算）；缺省不写 joinMode 时每条激活入边到达各触发一次执行。
- 节点 x/y 只是画布坐标，可省略/置 0（不影响运行）；用户可在编辑器工具栏点「自动布局」一键分层重排并写回全部节点坐标。

硬约束（校验失败返回逐项 `details`）：start 节点有且仅有一个；边引用的节点必须存在；maxRuns∈[1,10]；节点 `joinMode` ∈ {null, "", "none", "all"}（缺省 none；未知取值报错——语义见「汇合与多入边语义」；start/单入边节点带 all 等价缺省，宽松不报错）；`{{var}}` 只能出现在 command/input 且必须已在 variables 声明，**花括号必须配对**（`{{topic}` 缺右花括号会被创建期拒绝，不会按字面量静默输出）；AI 节点的 `agent` **必填**（不再支持匿名一次性会话）且不可用保留名 meta/events/runs/agents/supervisor；**引用节点不允许携带 systemPrompt**——定义节点=该 agent 名上游拓扑序最早持有者（见「会话线（agent）规则」），引用节点带非空白 systemPrompt 会被 create/update 直接拒绝（错误信息带定义节点定位）；outputMatches 的 pattern 必须是可编译正则；循环允许（靠 maxRuns 兜底：额度耗尽=节点失败→无人接住→实例 failed，`workflow_run` 返回 error 形态）。condition.type 取值：always（缺省）/onSuccess/onError/exitCode（带 `value`）/outputContains/outputNotContains（带 `text`）/outputEquals（带 `text`，输出与文本各自 trim 后精确相等——命令尾随换行不影响，适合 `PASS`/`FAIL` 这类标记行整行判定）/outputMatches（带 `pattern`）。

### 命令节点执行环境
命令节点由 worker 的 nativeExec 直接 spawn 系统 shell 进程执行（与 git/term 同一通道，不经 PermissionGate、不走本会话的命令工具）：
- **Windows：默认经 PowerShell 执行**（worker 共享管线：临时 .ps1〔UTF-8 BOM〕+ PATH 探测 pwsh〔PowerShell 7+〕优先、缺失回退内置 powershell.exe〔5.1〕；宿主极端无 PowerShell 才回退 `cmd.exe /c`）——按 **PowerShell 方言**写命令：`python`（无 `python3` 别名）；单引号=字面量、双引号=可插值（`$x` 会展开，要字面量 `$` 用单引号或 `` `$ ``）；`exit 3` 直接成为节点退出码；`&&`/`||` 仅 pwsh 7 支持（回退 5.1 会报语法错，跨机器复用别依赖，改 `;` 顺序或拆节点）。跨平台写法见下文「Windows 可移植命令范式」。
- **Linux/macOS：`/bin/sh -c <command>`** —— POSIX sh 语法；`sh` **不是 bash**，`[[ ]]`、数组、`<<<` 等 bash 专有语法不可用。
- cwd 固定为该工作流归属的工作区根；缺省超时 120 秒，节点 `timeoutMs`（毫秒）可覆盖；**超时/中止/取消会级联终止整个进程树**（含 shell 派生的孙进程），实例终态后命令不再有副作用。实例终态（失败/取消）收口时仍在飞的节点会被中断并落终态 `cancelled`（发 `node.interrupted` 关闭事件；不写 runs 记录——但该次在飞尝试**开跑即已计入 runCount**，执行即消耗、中断不返还，见「maxRuns 记账清单」）。
- stdout 与 stderr 全量留档到实例 runs；出边条件求值用的 output = stdout+stderr 拼接，exitCode = 进程退出码。
- **非零退出码 = 节点失败**（节点状态 `failed` + `node.error` 事件，不再按成功口径静默收束）：**首败即分流，不做自动重试**（`node.retry` 事件已删除——确定性失败重跑到耗尽没有意义，且 maxRuns 是节点**终身执行额度**而非失败重试预算：回指循环的每一轮都消耗额度，把额度烧在必然再败的重试上会让探针形同虚设）。分流两段：①有满足条件的出边（**所有条件类型都算接住**：onError/exitCode/always/outputContains/outputMatches 等按本次完成态 {exitCode, output} 求值）→ 分支接管（节点保持 failed，实例可收束 done，返回体带 failed 计数告警）；②无人接住 → 定义级 `onError` 策略（fail=实例失败 / askUser=挂起）。**注意**：失败被条件出边接住时优先分支，fail-fast 仅在无人接住时生效。要重试请**显式建模**：onError/exitCode 出边回指上游、或 askUser 挂起后的 retry 决策（决策即授权，绕 maxRuns 上限）。
- `{{var}}` 替换发生在命令交给 shell **之前**，变量值原样替换、不做 shell 转义——值里含空格/引号等特殊字符时需在 command 里自行加引号（Windows 侧用双引号包裹：`python do.py "{{变量}}"`）；**注入面提示**：把用户输入/上游 agent 产出直接接到 command 的用法构成命令注入（值里的 `&`、`|`、`>`、`%` 会改写命令语义），外部值应经 AI 节点消化后再落命令，或由调用方自行转义。
- **Windows 编码注意**：PowerShell 管线下 stdout/stderr 由 worker 共享管线经**文件承载按 UTF-8 解码**——命令产出的中文输出在留档/出边条件匹配里都是正常 UTF-8（旧「cmd 重定向 GBK 毁中文」的坑不再适用；仅当宿主极端无 PowerShell 回退 `cmd.exe /c` 时才需要担心 GBK）。但命令**自己重定向写盘的文件**编码由命令语法决定（PowerShell 5.1 的 `>`/`Out-File` 缺省 UTF-16、pwsh 7 缺省 UTF-8 无 BOM）——产物要给下游脚本按 UTF-8 读时显式 `Out-File -Encoding utf8`（或 `| Set-Content -Encoding UTF8`），或统一交给 AI 节点落盘。

### Windows 可移植命令范式（PowerShell × POSIX 两侧成对）
命令节点在 Windows 走 PowerShell 方言、Unix 走 POSIX sh。要在**同一份定义**里跨平台复用，按成对范式写（或干脆按平台拆成两个节点、用边条件/变量路由）：

| 场景 | Windows（PowerShell） | Unix（POSIX sh） |
| --- | --- | --- |
| 建目录（含父级） | `python -c "import pathlib; pathlib.Path('out/{{name}}').mkdir(parents=True, exist_ok=True)"` | `mkdir -p out/{{name}}` |
| 传含中文/空格的变量 | `python do.py "{{name}}"` | `python3 do.py "{{name}}"` |
| 条件判定 | `if (Test-Path out) { python ... }` | `if [ -d out ]; then python3 ...; fi` |
| 文本替换 | 写成 python 脚本（见下） | `sed -i 's/a/b/' file` |

要点：
- **python 而非 python3**：Windows 的官方安装只提供 `python`（`python3` 不存在）；Unix 发行版常见 `python3`。两侧都跑的调用要么把逻辑写成脚本、由目标机器各自的命令节点用各自的解释器名调用，要么约定运行环境统一。
- **引号语义**：给命令传含中文/空格的模板变量一律**双引号包裹**（`python do.py "{{变量}}"`——`{{var}}` 替换在 shell 之前原样落位，双引号防分词）。两侧双引号都允许插值（PowerShell 里 `$x`、sh 里 `$x` 都会展开）——变量值可能含 `$` 时，PowerShell 侧用 `` `$ `` 转义或反引号包裹，sh 侧用 `\$`。
- **sed/grep -P/awk/find -print0 等 POSIX 手法在 PowerShell 下不可用**（PowerShell 等价物 `Select-String`/`Get-ChildItem` 语法也不互通）——**复杂逻辑别塞一行命令，写成 python 脚本文件放工作区**（如 `python scripts/merge.py 参数`），脚本内用标准库天然跨平台，命令节点只做薄调用。
- `exit N` 两侧都直接成为节点退出码（PowerShell 方言里 `exit 3` 即退出码 3）。

### 校验/探针类节点推荐写法
「检查类」节点（测试/质检/条件路由探针——判断产物是否达标、决定走重试环还是出口）推荐**恒退 0 + stdout 输出路由标记**，而不是 exit 非零：

- **写法**：命令正常结束（退出码 0），把结论写成 stdout 标记行——如 `PASS` / `FAIL: 缺章节` / `ROUTE=RETRY`（中文标记实测可用，output 求值口径=stdout+stderr）；下游出边用 `outputContains` 条件路由：`{"type": "outputContains", "text": "ROUTE=RETRY"}` 回指重试环，另一条 `outputNotContains`/`outputContains "PASS"` 走出口。
- **为什么不用 exit 1 表达探针结论**：非零退出码=节点失败（node.error 事件 + 节点 failed 状态）——错误通道与正常控制流耦合在一起：失败节点会进终态 failed 告警（实例收束 done 也要带 warning），「节点失败」与「探针结论=不通过」两种语义混在一处，诊断时要额外分辨。且 maxRuns 是终身额度——探针进重试环后每一轮触发都消耗额度，把「结论路由」伪装成失败会让预算语义更难算。
- **现状**：首败即分流后，exit 非零 + onError/exitCode 出边**也可用**（分支接管、实例收束 done 带 warning）；但输出标记法路由更直观、节点状态干净（探针结论不污染 failed/done 词表），优先采用。
- 探针配合回指循环做重试：把循环内节点的 maxRuns 设为期望轮数（每轮触发执行消耗 1 次）；额度耗尽=节点失败→无人接住→实例 failed（`workflow_run` 返回 error 形态，兜底语义见「maxRuns 记账清单」）。

### 会话线（agent）规则
- **定义节点** = 该 agent 名在**上游拓扑序最早的持有者**：沿边反向可达的 AI 节点里，拓扑序第一个用这个 agent 名的节点（判定与编辑器下拉候选、运行时 systemPrompt 解析同一实现；回指环上解析到自身=视为定义节点）。定义节点携带 `systemPrompt`（**允许为空**=这条会话线没有系统提示词）与可选 `modelConfigId`，即会话线的定义处。
- **引用节点** = 上游已有同名持有者的下游节点：复用同名 `agent` 即延续同一会话线（跨节点共享上下文）；**不允许携带 systemPrompt**——create/update 校验直接拒绝引用节点带非空白 systemPrompt 的定义（错误信息带定义节点定位），`modelConfigId` 可各自另配。
- **存量迁移口径**：磁盘加载与已存实例运行不受此校验影响（旧定义里引用节点带 systemPrompt 的仍按「节点自带值优先」运行）；但该定义想再 update/create 就必须先清空引用节点上的 systemPrompt，或把值挪到（或改）定义节点——校验只挡保存入口。
- 模型取值优先级：节点 `modelConfigId` → 系统默认模型。

### 汇合与多入边语义（joinMode）
节点有多条入边时的执行次数由节点级 `joinMode`（String 字段）决定：

- **缺省（null/""/"none"）——每条激活入边到达即执行一次**：执行次数=激活入边到达数。并行分支 A→X、B→X（缺省 joinMode）会触发 X 执行两次（A 到达一次、B 到达一次）。
- **"all"——汇合屏障**：等**全部入边各激活一次**才触发**一次**执行：到达记账按边去重（环内同一条边重复到达记一条）；触发后记账清零，回指循环每轮重新等齐。并行分支→汇总节点用它——**join 触发 1 次=消耗 1 次 maxRuns**（按触发执行次数计），不用再按入边数手算 maxRuns。
- 互斥条件出边（如 onSuccess/onError 各一条）只会激活一条入边：joinMode=all 的对端等不齐 → 走下条静默 skipped。
- **等不齐的终局——静默 skipped**：整图静默（无 running 节点 + 无挂起决策 + 无非 join 滞留激活）而 join 节点仍未等齐时，该节点置 `skipped` 终态（`node.skipped` 事件留痕），实例按其余分支收束 done。部分分支失败被 onError 边接走、条件不达、回指循环第二轮对端不再重跑——都会走到这里。
- 校验：joinMode ∈ {null, "", "none", "all"}；start 节点无入边、单入边节点带 all 均等价缺省（宽松不报错，也无需写）。

### maxRuns 记账清单
maxRuns 是节点**终身执行额度**（不是失败重试预算——失败已不自动重试，首败即分流）。runCount 在节点**开始执行时**计入（执行即消耗）：

- **消耗额度**：正常执行一次；回指循环每轮重跑；多入边节点被多条激活入边分别触发（缺省 joinMode 的「到达即执行」）；joinMode=all 的等齐触发；**被中断/实例终态收口打断的在飞尝试**（开跑即计数，中断不返还）。
- **不消耗**：skipped（决策跳过/汇合未等齐静默跳过——不产生执行）；额度耗尽后拒绝第 N+1 次调度（未开跑，不计）。
- **上限判定与终局**：调度时 `runCount+1 > maxRuns` 即拒绝并按节点失败处理（错误形如「节点「X」达到最大运行次数上限(maxRuns=N,已完成 N 次)」→ 有条件出边接管或错误策略）；额度耗尽=节点失败→无人接住→实例 failed，`workflow_run` 返回 error 形态。
- **rerun**：目标节点重开**绕过上限**（决策级授权），但 runCount 继续累计——重开运行期内的环回/再激活仍受 maxRuns 约束（防无限循环）；反复重开由调用方自律。
- **水位观测点**：`node.started` / `node.output` 事件 payload 携带 `runCount`（本次运行开始后已消耗额度，**含本次**）与 `maxRuns`（节点额度，缺省 1）——第几次/还剩几次经 `workflow_instance_events` 运行时可查；同一运行的 started 与 output 携带同值。`workflow_run` 返回与 `workflow_instance_status` 的 `nodes[].runCount` 同口径。

### workflow_run 的返回与字段语义
`workflow_run` 阻塞至终态后返回：
- 成功：`{"instanceId":"wi_...","status":"done","durationMs":1234,"nodes":[{"nodeId":"c","nodeName":"...","status":"done","runCount":1}]}`；若存在未执行(pending)/失败(failed)节点（失败被 onError 分支接住或分支未激活），附带 `"warning":"... pending=N, failed=M ..."`——不要忽略该告警，核对 nodes 与 `workflow_instance_log`。
- 节点失败（fail-fast，主 agent 即督导者）：`{"error":"节点「X」失败: ...","nodeId":"n2","runNo":3,"message":"...","context":[失败节点历史输出摘要],"hint":"可用 workflow_rerun 从中断节点续跑..."}`——先诊断 context，能修复再按 hint 续跑或全新运行，把结论告诉用户。命令节点非零退出码**首败且无人接住**时同样走此形态（message 形如「命令退出码非零: N」——失败不自动重试，见「命令节点执行环境」失败语义）。
- 挂起等用户（onError=askUser）：`{"status":"waiting-user","pendingDecision":{...}}`——把 question 呈现给用户，决策经工作流实例页落地，之后用 `workflow_instance_status` 跟进。
- 运行中实例在任务页实时可见（AI 节点=子 agent 会话条目，命令节点=wf.node 轨迹卡片），无需轮询。
- 节点状态词表（`nodeStates` 与 `nodes[].status` 同构）：`pending`（未运行）/`running`（在飞）/`done`/`skipped`（未执行过而被跳过：决策 skip，或 joinMode=all 汇合未等齐的静默跳过）/`failed`（节点自身失败）/`cancelled`（实例终态收口中断的在飞运行——非节点自身失败，rerun 时按未完成重置可重跑）。终态实例的节点不会残留 `running`。
- 字段语义：`runNo` 是**实例级全局序号**（每节点运行递增、跨节点延续，不是节点内尝试次数；节点内尝试次数看 `workflow_instance_log` 的 `attemptIndex`）；`durationMs` 是实例累计墙钟（含 rerun 前的人工间隔），rerun 后的返回另有 `resumedDurationMs`=本次续跑段时长。

### workflow_rerun 的语义与边界
`workflow_rerun` 阻塞至终态后返回（形态同 workflow_run）：
- 续跑成功：`{"instanceId":"wi_...","status":"done","durationMs":...,"nodes":[...]}`——从中断节点续跑后的完整节点进度。
- 再失败：`{"error":...,"nodeId":...,"runNo":...,"context":[...],"hint":"可用 workflow_rerun 从中断节点续跑..."}`——可诊断后再次调用（幂等链），或换 `nodeId` 重开其它节点。
- 语义要点：仅 `failed` 实例可重开；已完成节点（done/skipped）不重跑，只重跑指定节点及其下游；runNo/runs/events 续号追加；命名 agent 复用同一会话（上下文从任务档案恢复，无需也不能重建）；`input` 替换目标节点输入并持久化到实例快照（AI 节点支持 `{{var}}` 模板；**命令节点的输入=command 本体，传 input 会被拒绝报错**——改命令请用 `workflow_update` 修改定义后重跑）。
- 边界：实例关联的原任务已结束/删除（或当前任务不是原任务）时报错——会话档案在原任务目录，只能重新 `workflow_run` 全新运行；实例非 failed（含运行中/挂起）报错。

## 边界与注意
- `definition` 参数是 **JSON 字符串**（整体作为一个参数传入，内部引号转义），不是逐字段传参。
- update 是全量替换：必须传完整定义（含未改动的节点/边），漏传即删除。
- 创建/修改成功返回 `{"id":"wf_..."}`，记下 id 供后续 get/update/delete；校验失败按 `details` 逐项修正后重试。
- 定义与运行缺省归属当前任务的工作区；仅当用户明确要跨工作区时才传 `workspace`（工作区根绝对路径）。
- `variables` 同理是 JSON 文本；必填变量缺失、引用的模型配置不存在会在运行前报错（`{error}`）。
- 实例 id（`wi_` 前缀）来自 workflow_run 返回；事件 `seq` 是雪花 ID，一律按字符串处理，续读传上次 `nextSeq`。
- 运行工作流直接调 `workflow_run`；管理页/侧栏的运行按钮与画布查看是用户侧入口，与工具路径等价。
- 失败修复优先 `workflow_rerun`（从中断节点续跑，已完成节点不重跑，命名 agent 上下文经任务档案保留）；只有要换变量/从头执行时才重新 `workflow_run`。目标节点重开视为一次决策级重试（不受 maxRuns 次数限制，但次数会累计入 runCount）。
