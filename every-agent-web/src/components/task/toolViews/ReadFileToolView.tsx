/**
 * read_file 工具调用美化视图。
 *
 * 折叠态/展开态的完整渲染交给共享组件 FileToolEntry（与 create_file/update_file 同构）；
 * 本视图仅负责按工具特性计算折叠行的行号区间预览（line_start / line_end 格式化为
 * [1-80] 紧凑区间），渲染在文件名与完整路径之间。
 * 工作区根由 TaskWorkspaceContext 提供，FileToolEntry 自行消费以打开文件标签页。
 *
 * 自描述注册：导出 `toolView` 即被目录即注册表自动发现，命中工具名 `read_file` 时
 * 完全接管渲染，未命中工具不受影响。
 */

import type { ToolViewDefinition, ToolViewProps } from './types'
import { FileToolEntry } from './FileToolEntry'

/**
 * 把 read_file 的行号参数格式化为紧凑区间：两者齐备 [1-80]；仅起始 [1-]；仅结束 [-80]。
 * 均未提供（全量读取）返回空串，折叠行退化为「文件名 + 路径」。
 */
function formatLineRange(args: Record<string, unknown>): string {
  const has = (v: unknown) => v !== undefined && v !== null
  const start = has(args.line_start) ? String(args.line_start) : ''
  const end = has(args.line_end) ? String(args.line_end) : ''
  if (!start && !end) return ''
  return `[${start}-${end}]`
}

function ReadFileToolView({ details }: ToolViewProps) {
  return (
    <>
      {details.map((detail, idx) => (
        <FileToolEntry
          key={detail.toolCallId ?? idx}
          detail={detail}
          inlineExtras={formatLineRange((detail.arguments ?? {}) as Record<string, unknown>)}
        />
      ))}
    </>
  )
}

export const toolView: ToolViewDefinition = {
  toolName: 'read_file',
  component: ReadFileToolView,
}
