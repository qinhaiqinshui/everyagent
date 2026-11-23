---
name: 工作流编排
description: 当需要创建、查询、修改、删除或列出可复用的工作流定义（多节点执行图：开始/命令/AI 节点、条件边、运行变量、命名 agent），供用户在画布上查看与运行时，使用本技能。
---

# 工作流编排

工作流 = 可复用的多步骤执行图：开始节点先行，命令节点跑 shell，AI 节点由命名 agent（持续会话线）执行；边条件决定路由（支持循环，maxRuns 兜限）；运行变量在 command/input 里以 `{{varName}}` 注入。
本技能的五工具**未随请求下发工具定义**，按本文档的名称与参数直接调用即可（调用失败请核对参数拼写与 JSON 格式）。

## 适用条件
- 用户要建立可复用、可多次运行的多步骤流程（生成/构建/检查流水线等）。
- 需要 AI 分支路由、循环重试、多 agent 协作编排时。
- 管理已有工作流定义（查看/改/删/列出）时。

## 工具参考
| 工具 | 关键参数 | 作用 |
| --- | --- | --- |
| `workflow_create` | `definition`（完整定义 JSON 文本） | 创建定义，返回 `{id}` |
| `workflow_get` | `id` | 返回完整定义 JSON |
| `workflow_update` | `id`、`definition`（完整定义 JSON） | 全量替换更新（非增量合并） |
| `workflow_delete` | `id` | 删除定义（历史运行实例保留） |
| `workflow_list` | `workspace`（可选，工作区根绝对路径） | 定义摘要列表，按更新时间倒序 |

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

## 边界与注意
- `definition` 参数是 **JSON 字符串**（整体作为一个参数传入，内部引号转义），不是逐字段传参。
- update 是全量替换：必须传完整定义（含未改动的节点/边），漏传即删除。
- 创建/修改成功返回 `{"id":"wf_..."}`，记下 id 供后续 get/update/delete；校验失败按 `details` 逐项修正后重试。
- 定义归属当前任务的工作区；仅当用户明确要跨工作区列表时才传 `workspace`（工作区根绝对路径）。
- 运行工作流（画布上的运行按钮/实例管理）不是工具能做的事——建好定义后告知用户在界面运行。
