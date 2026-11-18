/**
 * 展开态元信息行:工具开始执行时间点(「开始于 HH:mm:ss」,悬停显完整年月日)。
 *
 * 三个内置视图(DefaultToolView/CommandToolView/FileToolEntry)的展开详情顶部共用,
 * 三处渲染同一条信息不打折扣。时间点来源 = detail.startedAt:结果载荷(tool.result)
 * 的权威 startedAt;运行中(结果未到)由 messageView 灌入瞬态 tool.started 的
 * startedAt。startedAt 缺失(老事件未记录/排队未开始)返回 null,展开态保持原样。
 *
 * 纯展示组件,不做任何数据推导;格式化经 helpers(formatToolStartTime/
 * formatToolStartFullTime,本地时区 24 小时制手动拼接,与 TasksPanel 同口径)。
 */
import { formatToolStartFullTime, formatToolStartTime } from './helpers'

export function ToolStartMeta({ startedAt }: { startedAt: number | undefined }) {
  const clock = formatToolStartTime(startedAt)
  if (!clock) return null
  return (
    <div className="nagent-tool__meta">
      开始于
      <span className="nagent-tool__meta-time" title={formatToolStartFullTime(startedAt)}>{clock}</span>
    </div>
  )
}
