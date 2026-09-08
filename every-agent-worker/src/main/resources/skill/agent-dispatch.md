# 子 Agent 编排

注意！子Agent上下文独立于你，它无法看到你的上下文，所以你给它的任务务必说明清楚。它有与你一样的工具，以及可以看到当前工作区目录内的文件。
## 适用条件
- 当某个任务可以并行执行来提高效率时，交给子 Agent 执行。
- 当你只需要一个结果，但是探索这个结果会读取大量无用历史上下文时，可以派发子Agent来帮你探索并得出你要的结论。
- 当需要等待、停止、重启子 Agent，或查看它们的运行状态与结果时，使用本技能。
## 一、核心原则

1. 派发子Agent时必须明确其目标。
2. 派发子Agent后必须进行收尾工作。不可派发后不管。

## 二、工具操作（派发、等待、续跑、停止）

| 工具 | 关键参数 | 作用 |
| --- | --- | --- |
| `run_agent` | `input`（必需）、`title`（新建时必需）、`agentId`（续跑时填）、`blocking` | 派发或续跑一个子 Agent，返回 `{ agentId, status, result }` |
| `wait_agents` | `agentId`（可选）、`timeoutMs`（默认 30000） | 等待子 Agent 完成；传 `agentId` 返回 `{ mode: "single", waitStatus, agent }`，不传则等待后返回 `{ mode: "list", waitStatus, agents }` |
| `stop_agent` | `agentId`（必需） | 按 `agentId` 中止一个运行中的子 Agent |
| `list_agents` | 无 | 列出当前任务下全部子 Agent（含 `agentId` / `title` / `status` / `latestActivity`），是 wait/stop 的 `agentId` 来源 |

## 三、派发方式
- **阻塞派发**：`blocking: true`，等待子 Agent 结束并直接返回结果，适合必须拿到结果才能继续的步骤。
- **异步派发**：`blocking: false`（默认），立即返回 `agentId`。彼此无依赖、不读写同一中间产物的多个子 Agent 可全部异步派发，再统一用 `wait_agents` 收集结果。
- 你使用异步派发后，必须再用 `wait_agents` 等待完成，不可派发后不收口。

## 四、续跑 / 重启 / 重试
- 你给 `run_agent` 传入已存在的 `agentId`，即在该 agent 上继续（复用其历史上下文，不重做已完成部分）；不传 `agentId` 则新建 agent 全新执行（此时 `title` 必填）。
- 子 Agent 卡住时：先 `stop_agent` 停止它，再以同一 `agentId` 调 `run_agent` 重启续跑。
- 子 Agent 失败（`run_agent` 报错或 `wait_agents` 的 `agent.status` 为 `error`）时：修复 `input` 后以同一 `agentId` 重试（续跑），或不传 `agentId` 来以新的子Agent重跑该工作。

## 五、状态语义
- `agent.status` 的 `completed` / `stopped` / `error` 为终态（已完成或终止）；`running` 为仍在运行；`waiting-user` 表示该子 agent 挂起等待用户回答（回答后自动恢复 `running`）。
- `wait_agents` 的 `waitStatus` 只表示本次等待行为：`completed` 表示等到返回，`timeout` 表示本次等待超时；超时时仍返回最新 `agent.latestActivity` 供判断进度。
- `agent.latestActivity` 承载最近活动与错误信息，包含 `reasoning` / `content` / `error` / `createdAt` / `updatedAt`，不会包含完整 `messages` 历史。

## 六、边界与注意事项
- 子 Agent 的历史上下文按 `agentId` 隔离。若你想让先后运行的两个子任务共享历史，就传入相同的 `agentId`；但**不可让多个子 Agent 同时运行在同一 `agentId` 上**（运行中的 agent 再次 `run_agent` 会报错，须先 `stop_agent`）。
- `wait_agents` / `stop_agent` 的 `agentId` 应来自 `list_agents()` 返回结果，不要凭空捏造。
- 你作为主Agent负责子Agent的管理、完成情况验收、最终任务完成情况总结，子 Agent 负责执行被明确界定的子任务。
- 若子 Agent 有疑问你需要回答并让它继续。
- 若想多个子Agent并行执行，则必须blocking: false，否则就算同时下发多个run_agent工具调用，也只会串行执行。