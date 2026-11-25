---
name: 子 Agent
description: 当任务可并行执行以提高效率、只需结论但自行探索会读取大量无用上下文，或需等待、停止、重启子 Agent 及查看其运行状态与结果时，使用本技能。
tools: [run_agent, list_agents, wait_agents, stop_agent]
---

# 子 Agent 编排

子 Agent 上下文独立于你：它看不到你的对话，任务描述务必自包含。它拥有与你一样的工具，可读写当前工作区文件。
本技能的四工具会在你读取本知识包后自动加入工具列表，直接按列表中的定义调用即可。

## 适用条件
- 某个任务可并行执行以提高效率时，交给子 Agent。
- 只需一个结论、但自行探索会读取大量无用历史上下文时，派子 Agent 去探索并取回结论。
- 需要等待、停止、重启子 Agent，或查看其状态与结果时。

## 核心原则
1. 派发时必须明确目标（自包含、不引用本对话内容）。
2. 派发后必须收尾：用 `wait_agents` 等待并收集结果，不可派发后不管。

## 工具参考
以下四个工具在你读取本知识包后自动出现在工具列表中（含参数定义），按列表内工具正常调用即可，无需记忆参数。

- `run_agent`：异步派发或续跑子 Agent，立即返回 `agentId`。
- `wait_agents`：等待子 Agent 完成——传 `agentId` 等单个，省略等全部。
- `list_agents`：列出当前任务全部子 Agent（**含之前轮次已结束的**），是 wait/stop 的 `agentId` 来源。
- `stop_agent`：停止一个运行中的子 Agent。

## 派发方式
- `run_agent` 始终异步，立即返回 `agentId`；彼此无依赖、不读写同一中间产物的多个子 Agent 可并行派发（同批多个 run_agent 调用），再统一 `wait_agents` 收集。
- 派发后必须 `wait_agents` 收口。若产出最终回复前没来得及 wait，系统会等待子 Agent 自然完成（约 5 分钟上限）后才收口；下一轮可用 `list_agents`/`wait_agents` 查看终态与结果并汇报。

## 续跑 / 重启 / 重试
- 传已存在的 `agentId` 即续跑（复用其历史上下文，不重做已完成部分）；不传则新建（此时 `title` 必填）。续跑**跨轮有效**：`list_agents` 里的历史 `agentId`（含中断、停止后遗留的）都可继续，上下文已持久化，跨轮、跨重启不丢。
- 卡住：先 `stop_agent` 停止，再以同一 `agentId` `run_agent` 重启续跑。
- 失败（`run_agent` 报错或 `wait_agents` 的 `agent.status` 为 error）：修复 `input` 后以同一 `agentId` 重试，尽量复用已积累的上下文；重试仍失败则报告用户决定。无法解决时最多再让子 Agent 以同一 `agentId` 重试一次。

## 状态语义
- `agent.status`：`completed`/`stopped`/`error` 为终态；`running` 运行中；`waiting-user` 挂起等用户回答（回答后自动恢复）。
- `wait_agents` 的 `waitStatus` 只描述本次等待行为：`completed` 等到返回；`timeout` 本次等待超时，仍返回最新摘要（`agent.latestActivity`）供判断进度。
- `latestActivity` 含 `reasoning`/`content`/`error`/`createdAt`/`updatedAt`，不含完整历史。

## 边界与注意
- 上下文按 `agentId` 隔离：想让两个子任务共享历史就传同一 `agentId`，但**不可多个子 Agent 同时跑在同一 `agentId` 上**（运行中再 `run_agent` 会报错，须先 `stop_agent`）。
- `wait_agents`/`stop_agent` 的 `agentId` 必须来自 `list_agents` 或 `run_agent` 返回值，不要凭空捏造。
- 你（主 Agent）负责子 Agent 的管理、验收与最终总结；子 Agent 只执行被明确界定的子任务。子 Agent 有疑问时你需回答并让它继续。
