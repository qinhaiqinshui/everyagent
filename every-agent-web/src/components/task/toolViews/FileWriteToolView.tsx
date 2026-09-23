/**
 * 文件写入类工具（create_file / update_file）调用美化视图。
 *
 * 折叠态/展开态的完整渲染交给共享组件 FileToolEntry（与 read_file 同构）；文件写入类
 * 无附加参数，文件名 + 完整路径由 FileToolEntry 从 args.path 统一提取渲染。
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
      {details.map((detail, idx) => (
        <FileToolEntry
          key={detail.toolCallId ?? idx}
          detail={detail}
        />
      ))}
    </>
  )
}

export const toolViews: ToolViewDefinition[] = [
  { toolName: 'create_file', component: FileWriteToolView },
  { toolName: 'update_file', component: FileWriteToolView },
]
