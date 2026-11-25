---
name: 工作流编排
description: 当用户任务与工作流相关时使用本技能。
tools: [workflow_create, workflow_get, workflow_update, workflow_delete, workflow_list, workflow_run, workflow_rerun, workflow_instance_status, workflow_instance_log, workflow_instance_events, workflow_instance_cancel]
---

# 工作流编排

工作流 = 可复用的多步骤执行图：开始节点先行，命令节点经系统 shell 执行（Windows=`cmd.exe /c`、Unix=`/bin/sh -c`，详见「命令节点执行环境」），AI 节点由命名 agent（持续会话线）执行；边条件决定路由（支持循环，maxRuns 兜限）；运行变量在 command/input 里以 `{{varName}}` 注入。
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
- `workflow_instance_log`：实例节点运行日志（runs 过滤投影；stdout/stderr/text 各截断 4000 字符；每条含 `runNo` 实例级全局序号与 `attemptIndex` 该节点第几次尝试，从 1 起）。
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
    {"id": "n2", "type": "command", "name": "初始化", "x": 240, "y": 200, "command": "mkdir -p out/{{novelName}}", "timeoutMs": 60000, "maxRuns": 2},
    {"id": "n3", "type": "ai",      "name": "生成大纲", "x": 440, "y": 200, "agent": "writer", "modelConfigId": null, "systemPrompt": "你是资深小说作者", "input": "请为《{{novelName}}》拟一份章节大纲", "maxRuns": 2},
    {"id": "n4", "type": "ai",      "name": "润色", "x": 640, "y": 200, "agent": "writer", "input": "润色上一节点产出"}
  ],
  "edges": [
    {"id": "e1", "from": "n1", "to": "n2"},
    {"id": "e2", "from": "n2", "to": "n3", "condition": {"type": "onSuccess"}},
    {"id": "e3", "from": "n3", "to": "n4"}
  ]
}
```

硬约束（校验失败返回逐项 `details`）：start 节点有且仅有一个；边引用的节点必须存在；maxRuns∈[1,10]；`{{var}}` 只能出现在 command/input 且必须已在 variables 声明，**花括号必须配对**（`{{topic}` 缺右花括号会被创建期拒绝，不会按字面量静默输出）；AI 节点的 `agent` **必填**（不再支持匿名一次性会话）且不可用保留名 meta/events/runs/agents/supervisor；outputMatches 的 pattern 必须是可编译正则；循环允许（靠 maxRuns 兜底）。condition.type 取值：always（缺省）/onSuccess/onError/exitCode（带 `value`）/outputContains/outputNotContains（带 `text`）/outputMatches（带 `pattern`）。

### 命令节点执行环境
命令节点由 worker 的 nativeExec 直接 spawn 系统 shell 进程执行（与 git/term 同一通道，不经 PermissionGate、不走本会话的命令工具）：
- **Windows：`cmd.exe /c <command>`** —— 按 cmd 语法写（`dir`、`set`、`&&`、`^` 续行转义等）。
- **Linux/macOS：`/bin/sh -c <command>`** —— POSIX sh 语法；`sh` **不是 bash**，`[[ ]]`、数组、`<<<` 等 bash 专有语法不可用。
- 要跨平台复用的定义，command 需写两侧都兼容的写法（或按平台拆成不同节点、用边条件路由）。
- cwd 固定为该工作流归属的工作区根；缺省超时 120 秒，节点 `timeoutMs`（毫秒）可覆盖；**超时/中止/取消会级联终止整个进程树**（含 shell 派生的孙进程），实例终态后命令不再有副作用。实例终态（失败/取消）收口时仍在飞的节点会被中断并落终态 `cancelled`（发 `node.interrupted` 关闭事件；不写 runs 记录、runCount 不计中断尝试——rerun 时预算完整）。
- stdout 与 stderr 全量留档到实例 runs；出边条件求值用的 output = stdout+stderr 拼接，exitCode = 进程退出码。
- **非零退出码 = 节点失败**（节点状态 `failed` + `node.error` 事件，不再按成功口径静默收束）：未达 `maxRuns` 自动重试（`node.retry` 事件留痕）；重试耗尽后有条件出边（onError/exitCode/always）接住则沿分支继续（实例可正常完成，返回体带 failed 计数告警）；无人接住走定义级 `onError` 策略（fail=实例失败 / askUser=挂起）。**注意**：失败被 `onError` 边接住时优先分支，fail-fast 仅在无人接住时生效。
- `{{var}}` 替换发生在命令交给 shell **之前**，变量值原样替换、不做 shell 转义——值里含空格/引号等特殊字符时需在 command 里自行加引号；**注入面提示**：把用户输入/上游 agent 产出直接接到 command 的用法构成命令注入（值里的 `&`、`|`、`>`、`%` 会改写命令语义），外部值应经 AI 节点消化后再落命令，或由调用方自行转义。
- **Windows 编码注意**：cmd 重定向写盘用控制台 OEM 码页（中文系统=GBK），`chcp 65001` 在重定向场景不生效；命令节点产出的中文文件与 AI 节点（UTF-8）混用会被下游脚本按 UTF-8 读坏——中文产物建议由 AI 节点落盘，或在命令内显式转码（如 `powershell -Command "Out-File -Encoding utf8"`）。

### 会话线（agent）规则
- **定义节点**：首次使用某 agent 名的节点在该节点上带 `systemPrompt`（+可选 `modelConfigId`），即这条会话线的定义处。
- **引用节点**：下游节点复用同名 `agent` 即延续同一会话线（跨节点共享上下文）；`systemPrompt` 不写（写了也不该写——生效值始终来自上游定义节点），`modelConfigId` 可各自另配。
- 模型取值优先级：节点 `modelConfigId` → 系统默认模型。

### workflow_run 的返回与字段语义
`workflow_run` 阻塞至终态后返回：
- 成功：`{"instanceId":"wi_...","status":"done","durationMs":1234,"nodes":[{"nodeId":"c","nodeName":"...","status":"done","runCount":1}]}`；若存在未执行(pending)/失败(failed)节点（失败被 onError 分支接住或分支未激活），附带 `"warning":"... pending=N, failed=M ..."`——不要忽略该告警，核对 nodes 与 `workflow_instance_log`。
- 节点失败（fail-fast，主 agent 即督导者）：`{"error":"节点「X」失败: ...","nodeId":"n2","runNo":3,"message":"...","context":[失败节点历史输出摘要],"hint":"可用 workflow_rerun 从中断节点续跑..."}`——先诊断 context，能修复再按 hint 续跑或全新运行，把结论告诉用户。命令节点非零退出码重试耗尽且无人接住时同样走此形态（message 形如「命令退出码非零: N」）。
- 挂起等用户（onError=askUser）：`{"status":"waiting-user","pendingDecision":{...}}`——把 question 呈现给用户，决策经工作流实例页落地，之后用 `workflow_instance_status` 跟进。
- 运行中实例在任务页实时可见（AI 节点=子 agent 会话条目，命令节点=wf.node 轨迹卡片），无需轮询。
- 节点状态词表（`nodeStates` 与 `nodes[].status` 同构）：`pending`（未运行）/`running`（在飞）/`done`/`skipped`/`failed`（节点自身失败）/`cancelled`（实例终态收口中断的在飞运行——非节点自身失败，rerun 时按未完成重置可重跑）。终态实例的节点不会残留 `running`。
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
