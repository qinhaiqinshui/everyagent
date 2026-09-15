/**
 * read_file 工具调用美化视图。
 *
 * 折叠态/展开态的完整渲染交给共享组件 FileToolEntry（与 create_file/update_file 同构）；
 * 本视图仅负责按工具特性计算折叠行的 inline-preview：path + 其余参数（line_start /
 * line_end 等以 key=value 形式跟在 path 后面）。
 * 工作区根由 TaskWorkspaceContext 提供，FileToolEntry 自行消费以打开文件标签页。
 *
 * 自描述注册：导出 `toolView` 即被目录即注册表自动发现，命中工具名 `read_file` 时
 * 完全接管渲染，未命中工具不受影响。
 */

import type { AggregatedToolDetail, ToolViewDefinition, ToolViewProps } from './types'
import { FileToolEntry } from './FileToolEntry'

/** 把除 path 外的参数格式化为 "key=value" 空格串；未提供的参数不显示。 */
function formatExtraArgs(args: Record<string, unknown>): string {
  return Object.entries(args)
    .filter(([key, value]) => key !== 'path' && value !== undefined && value !== null)
    .map(([key, value]) => `${key}=${typeof value === 'string' ? value : JSON.stringify(value)}`)
    .join(' ')
}

function buildInlinePreview(detail: AggregatedToolDetail): string {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  const fullPath = typeof args.path === 'string' ? args.path : ''
  const extraArgs = formatExtraArgs(args)
  return [fullPath, extraArgs].filter(Boolean).join(' ')
}

function ReadFileToolView({ details }: ToolViewProps) {
  return (
    <>
      {details.map((detail, idx) => (
        <FileToolEntry
          key={detail.toolCallId ?? idx}
          detail={detail}
          inlinePreview={buildInlinePreview(detail)}
        />
      ))}
    </>
  )
}

export const toolView: ToolViewDefinition = {
  toolName: 'read_file',
  component: ReadFileToolView,
}
