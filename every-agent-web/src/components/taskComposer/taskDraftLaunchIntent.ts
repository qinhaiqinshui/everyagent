/**
 * 新任务草稿启动意图（`ctx.ui.startNewTask` → 草稿页 TaskChat）。
 *
 * 插件（如 workflow 的「运行工作流」）要以「宿主原有建立路径」启动一次任务提交时，
 * 自身不发 `task.run` RPC，而是先由 PluginDispatcher 打开（复用）唯一的新任务草稿
 * 标签页，再经本模块把「预填初始内容（含模型配置）+ 自动点发送」的意图投递给草稿页。
 *
 * 双通道配合，解决两种时序（与 taskWorkspaceBrowseIntent / settingsNavigation 同构）：
 * - **pending 单槽**：`startNewTask` 先 `openTab` 再 request——草稿标签此前未打开时，
 *   TaskChat 要等 React commit 后才挂载、此刻无人订阅，意图先记在模块级槽位；
 *   草稿页挂载时经 `consumePendingTaskDraftLaunch` 一次性消费（读后清空）；
 * - **订阅**：草稿标签本就挂载（激活或隐藏）时的即时通知——订阅回调兑现后同样
 *   调 consume 清空 pending，防止草稿页关闭重开后陈旧 pending 二次启动。
 *
 * 消费即清空，故同一次请求不会被两条通道重复兑现。
 */

/** 一次草稿启动请求：预填内容 + 可选覆盖项（字段语义与 ctx.ui.startNewTask 入参一致）。 */
export interface TaskDraftLaunchInput {
  /** 初始输入内容（=rawContent，可含 opaque token 串）。 */
  rawContent: string
  /** 预选的模型配置 id；缺省沿用草稿输入框当前选中。 */
  modelConfigId?: string
  /** 新任务挂靠的工作区根；缺省沿用草稿预设/注册表首选根。 */
  workspace?: string
  /** 任务标题；缺省由初始内容派生。 */
  title?: string
}

let pending: TaskDraftLaunchInput | null = null
const listeners = new Set<(input: TaskDraftLaunchInput) => void>()

/** 发出草稿启动意图（通知已挂载的草稿页，并留 pending 兜底未挂载）。 */
export function requestTaskDraftLaunch(input: TaskDraftLaunchInput): void {
  pending = input
  for (const fn of listeners) fn(input)
}

/** 消费 pending 意图（草稿页挂载时 / 订阅回调兑现后调用；读后清空）。 */
export function consumePendingTaskDraftLaunch(): TaskDraftLaunchInput | null {
  const consumed = pending
  pending = null
  return consumed
}

/** 订阅草稿启动意图；返回退订函数。 */
export function subscribeTaskDraftLaunch(fn: (input: TaskDraftLaunchInput) => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}
