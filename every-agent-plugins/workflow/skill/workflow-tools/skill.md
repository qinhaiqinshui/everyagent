---
name: 工作流编排
description: 当需要创建、查询、修改、删除或列出可复用的工作流定义（多节点执行图：开始/命令/AI 节点、条件边、运行变量、命名 agent），供用户在画布上查看与运行时，使用本技能。
---

# 工作流编排

工作流 = 可复用的多步骤执行图：开始节点先行，命令节点跑 shell，AI 节点由命名 agent（持续会话线）执行；边条件决定路由（支持循环，maxRuns 兜限）；运行变量在 command/input 里以 `{{varName}}` 注入。
本技能的十一工具**未随请求下发工具定义**，按本文档的名称与参数直接调用即可（调用失败请核对参数拼写与 JSON 格式）。

## 适用条件
- 用户要建立可复用、可多次运行的多步骤流程（生成/构建/检查流水线等）。
- 需要 AI 分支路由、循环重试、多 agent 协作编排时。
- 管理已有工作流定义（查看/改/删/列出）时。
- 在当前任务内运行工作流并跟进其过程/结果/故障时。

## 工具参考
| 工具 | 关键参数 | 作用 |
| --- | --- | --- |
| `workflow_create` | `definition`（完整定义 JSON 文本） | 创建定义，返回 `{id}` |
| `workflow_get` | `id` | 返回完整定义 JSON |
| `workflow_update` | `id`、`definition`（完整定义 JSON） | 全量替换更新（非增量合并） |
| `workflow_delete` | `id` | 删除定义（历史运行实例保留） |
| `workflow_list` | `workspace`（可选，工作区根绝对路径） | 定义摘要列表，按更新时间倒序 |
| `workflow_run` | `definitionId`（必填）、`variables`（可选 JSON 文本 `{k:v}`）、`workspace`（可选） | **在当前任务内运行工作流，阻塞至终态**；成功返回结构化摘要，失败返回 `{error,nodeId,runNo,context,hint}` |
| `workflow_rerun` | `instanceId`（必填）、`nodeId`（可选，缺省=失败节点）、`input`（可选，替换该节点输入） | **从失败实例的中断节点续跑**（阻塞至终态，语义同 run）；已完成节点不重跑，runNo 延续 |
| `workflow_instance_status` | `instanceId` | 实例状态摘要（status/nodeStates/error/pendingDecision/变量/耗时；不含定义快照） |
| `workflow_instance_log` | `instanceId`、`nodeId`（可选）、`tail`（可选，缺省 10） | 实例节点运行日志（runs 过滤投影；stdout/stderr/text 各截断 4000 字符） |
| `workflow_instance_events` | `instanceId`、`afterSeq`（可选）、`limit`（可选，缺省 50） | 实例事件流直通 `{events:[{seq(字符串),ts,event,payload}],nextSeq}` |
| `workflow_instance_cancel` | `instanceId` | 取消运行中的实例（已终态报错） |

### definition JSON 结构
```json
{
  "name": "小说生成", "description": "可选说明",
  "variables": [{"name": "novelName", "label": "小说名", "required": true, "defaultValue": null, "description": "可选"}],
  "onError": "fail | supervisor | askUser",
  "supervisor": {"modelConfigId": "可选", "systemPrompt": "可选(督导 agent)"},
  "agents": [{"name": "writer", "modelConfigId": "可选", "systemPrompt": "你是资深小说作者"}],
  "nodes": [
    {"id": "n1", "type": "start",   "name": "开始", "x": 40,  "y": 200},
    {"id": "n2", "type": "command", "name": "初始化", "x": 240, "y": 200, "command": "mkdir -p out/{{novelName}}", "timeoutMs": 60000, "maxRuns": 2},
    {"id": "n3", "type": "ai",      "name": "生成大纲", "x": 440, "y": 200, "agent": "writer", "modelConfigId": null, "input": "请为《{{novelName}}》拟一份章节大纲", "maxRuns": 2}
  ],
  "edges": [
    {"id": "e1", "from": "n1", "to": "n2"},
    {"id": "e2", "from": "n2", "to": "n3", "condition": {"type": "onSuccess"}}
  ]
}
```

硬约束（校验失败返回逐项 `details`）：start 节点有且仅有一个；边引用的节点必须存在；maxRuns∈[1,10]；`{{var}}` 只能出现在 command/input 且必须已在 variables 声明；agent 名不可用保留名 meta/events/runs/agents/supervisor；outputMatches 的 pattern 必须是可编译正则。节点引用的 agent 名可不出现在 agents[]（保存时自动并入，补默认模型）；循环允许（靠 maxRuns 兜底）。condition.type 取值：always（缺省）/onSuccess/onError/exitCode（带 `value`）/outputContains/outputNotContains（带 `text`）/outputMatches（带 `pattern`）。

### workflow_create
```json
{"definition": "<完整定义 JSON 文本>"}
```
调用示例（最小两节点）：
```json
{"definition": "{\"name\":\"代码评审\",\"nodes\":[{\"id\":\"n1\",\"type\":\"start\",\"name\":\"开始\",\"x\":40,\"y\":200},{\"id\":\"n2\",\"type\":\"ai\",\"name\":\"评审\",\"x\":240,\"y\":200,\"agent\":\"reviewer\",\"input\":\"评审当前工作区改动\"}],\"edges\":[{\"id\":\"e1\",\"from\":\"n1\",\"to\":\"n2\"}],\"agents\":[{\"name\":\"reviewer\",\"systemPrompt\":\"你是严格的首席工程师\"}]}"}
```

### workflow_get
```json
{"id": "wf_xxx"}
```

### workflow_update
```json
{"id": "wf_xxx", "definition": "<修改后的完整定义 JSON 文本>"}
```

### workflow_delete
```json
{"id": "wf_xxx"}
```

### workflow_list
```json
{}
```

### workflow_run
```json
{"definitionId": "wf_xxx", "variables": "{\"title\":\"星际牧羊人\"}"}
```
阻塞至终态后返回：
- 成功：`{"instanceId":"wi_...","status":"done","durationMs":1234,"nodes":[{"nodeId":"c","nodeName":"...","status":"done","runCount":1}]}`
- 节点失败（fail-fast，主 agent 即督导者）：`{"error":"节点「X」失败: ...","nodeId":"n2","runNo":3,"message":"...","context":[失败节点历史输出摘要],"hint":"可用 workflow_rerun 从中断节点续跑..."}`——先诊断 context，能修复再按 hint 续跑或全新运行，把结论告诉用户。
- 挂起等用户（onError=askUser）：`{"status":"waiting-user","pendingDecision":{...}}`——把 question 呈现给用户，决策经工作流实例页落地，之后用 `workflow_instance_status` 跟进。
- 运行中实例在任务页实时可见（AI 节点=子 agent 会话条目，命令节点=wf.node 轨迹卡片），无需轮询。

### workflow_rerun
```json
{"instanceId": "wi_xxx"}
```
带输入替换重开失败节点（修正提示词后从该节点续跑）：
```json
{"instanceId": "wi_xxx", "nodeId": "n2", "input": "请改用英文重写上一版"}
```
阻塞至终态后返回（形态同 workflow_run）：
- 续跑成功：`{"instanceId":"wi_...","status":"done","durationMs":...,"nodes":[...]}`——从中断节点续跑后的完整节点进度。
- 再失败：`{"error":...,"nodeId":...,"runNo":...,"context":[...],"hint":"可用 workflow_rerun 从中断节点续跑..."}`——可诊断后再次调用（幂等链），或换 `nodeId` 重开其它节点。
- 语义要点：仅 `failed` 实例可重开；已完成节点（done/skipped）不重跑，只重跑指定节点及其下游；runNo/runs/events 续号追加；命名 agent 复用同一会话（上下文从任务档案恢复，无需也不能重建）；`input` 替换目标节点输入并持久化到实例快照（AI 节点支持 `{{var}}` 模板；命令节点的输入=command 本体，不参与 input 替换）。
- 边界：实例关联的原任务已结束/删除（或当前任务不是原任务）时报错——会话档案在原任务目录，只能重新 `workflow_run` 全新运行；实例非 failed（含运行中/挂起）报错。

### workflow_instance_status
```json
{"instanceId": "wi_xxx"}
```

### workflow_instance_log
```json
{"instanceId": "wi_xxx", "nodeId": "n2", "tail": 5}
```

### workflow_instance_events
```json
{"instanceId": "wi_xxx", "afterSeq": "0", "limit": 50}
```

### workflow_instance_cancel
```json
{"instanceId": "wi_xxx"}
```

## 边界与注意
- `definition` 参数是 **JSON 字符串**（整体作为一个参数传入，内部引号转义），不是逐字段传参。
- update 是全量替换：必须传完整定义（含未改动的节点/边），漏传即删除。
- 创建/修改成功返回 `{"id":"wf_..."}`，记下 id 供后续 get/update/delete；校验失败按 `details` 逐项修正后重试。
- 定义与运行缺省归属当前任务的工作区；仅当用户明确要跨工作区时才传 `workspace`（工作区根绝对路径）。
- `variables` 同理是 JSON 文本；必填变量缺失、引用的模型配置不存在会在运行前报错（`{error}`）。
- 实例 id（`wi_` 前缀）来自 workflow_run 返回；事件 `seq` 是雪花 ID，一律按字符串处理，续读传上次 `nextSeq`。
- 运行工作流直接调 `workflow_run`；管理页/侧栏的运行按钮与画布查看是用户侧入口，与工具路径等价。
- 失败修复优先 `workflow_rerun`（从中断节点续跑，已完成节点不重跑，命名 agent 上下文经任务档案保留）；只有要换变量/从头执行时才重新 `workflow_run`。目标节点重开视为一次决策级重试（不受 maxRuns 次数限制，但次数会累计入 runCount）。
