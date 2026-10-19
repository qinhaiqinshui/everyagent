/**
 * error / cancelled kind 视图(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * 步骤 8 遗留的「kind → 旧 traceTypeRegistry key」映射在此收敛为 kind 直配:
 * - `error` → 以 `run_error` 呈现(错误色调 tone:error + 缺省三角警示图标);
 * - `cancelled` → 以 `system_notice` 呈现(系统通知图标/摘要行)。
 * 两者均复用 TaskTraceShell 呈现(payload 的 title/summary/content 形状兼容 trace 形状);
 * TraceShell 内的旧映射随此删除(只按传入 kind 查 traceTypeRegistry)。
 */
import React from 'react'
import type { KindViewProps } from './registry'
import { TaskTraceShell } from './TraceShell'

/** `error` kind 视图:任务/子任务运行错误,run_error 错误色调。 */
export function ErrorView({
  item,
  taskId,
}: KindViewProps<'error'>): React.ReactNode {
  return <TaskTraceShell item={{ ...item, kind: 'run_error' }} taskId={taskId} />
}

/** `cancelled` kind 视图:任务取消,系统通知形态。 */
export function CancelledView({
  item,
  taskId,
}: KindViewProps<'cancelled'>): React.ReactNode {
  return <TaskTraceShell item={{ ...item, kind: 'system_notice' }} taskId={taskId} />
}
