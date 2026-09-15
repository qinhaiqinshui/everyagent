/**
 * 文件写入类工具（create_file / update_file）调用美化视图。
 *
 * 折叠态/展开态的完整渲染交给共享组件 FileToolEntry（与 read_file 同构）；
 * 本视图仅负责按工具特性计算折叠行的 inline-preview（写入类只展示 path）。
 * 工作区根由 TaskWorkspaceContext 提供，FileToolEntry 自行消费以打开文件标签页。
 *
 * 自描述注册：导出 `toolViews` 即被目录即注册表自动发现，命中 create_file /
 * update_file 时完全接管渲染，未命中工具不受影响。
 */

import type { ToolViewDefinition, ToolViewProps } from './types'
import { FileToolEntry } from './FileToolEntry'

function FileWriteToolView({ details }: ToolViewProps) {
  return (
    <>
      {details.map((detail, idx) => {
        const args = (detail.arguments ?? {}) as Record<string, unknown>
        const fullPath = typeof args.path === 'string' ? args.path : ''
        return (
          <FileToolEntry
            key={detail.toolCallId ?? idx}
            detail={detail}
            inlinePreview={fullPath}
          />
        )
      })}
    </>
  )
}

export const toolViews: ToolViewDefinition[] = [
  { toolName: 'create_file', component: FileWriteToolView },
  { toolName: 'update_file', component: FileWriteToolView },
]
