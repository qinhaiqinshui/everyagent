/**
 * `tool.result` kind 视图(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * 配对语义:正常路径的结果被对应 message 下发块按 callId 吸收(absorbedIntoMessage
 * → true),本视图 return null 不重复渲染(吸收判定从 renderMessageGroups 的分组级
 * 逻辑收敛到视图内部);**孤儿**(callId 无对应下发:迟到/未知/老数据)独立渲染兜底,
 * 形态不丢——per-tool 美化视图接管,无参数回填(本就无下发锚点)。
 */
import React from 'react'
import type { KindViewProps } from './registry'
import { useThreadBoard } from './threadBoardContext'
import { absorbedIntoMessage, detailOfOrphan } from './toolDomain'
import { getToolView } from '@/components/task/toolViews/registry'

/** `tool.result` kind 视图(payload 经 KindPayloadMap 与 handler 编译期配对)。 */
export function ToolResultView({
  item,
  payload,
}: KindViewProps<'tool.result'>): React.ReactNode {
  const { items } = useThreadBoard()
  if (!payload) return null
  // 结果已被对应 message 下发块吸收(按 callId join 进批壳调用行),不单独渲染。
  if (absorbedIntoMessage(item, items)) {
    return null
  }
  // 孤儿:独立兜底条目(per-tool 美化视图;工具名缺失回退「工具」)。
  const detail = detailOfOrphan(payload)
  const View = getToolView(detail.toolName)
  return (
    <div className="nagent-msg nagent-msg--tool">
      <div className="nagent-msg__body">
        <View details={[detail]} />
      </div>
    </div>
  )
}
