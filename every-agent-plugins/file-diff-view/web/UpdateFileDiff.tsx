/**
 * update_file 内嵌 diff 视图——由 file-diff-view 插件经
 * `ui.tool_call_detail_view` 扩展点贡献给工具调用展开态。
 *
 * 用 worker 工具参数 oldcontent（替换前片段）与 content（替换后片段）生成
 * 行级 unified diff（复用 web 的 buildLineDiff 纯函数，LCS 行对齐），
 * 直接内嵌在工具展开详情里，无需再打开文件对比。
 *
 * 样式走本插件自带 updateDiff.css（紧凑行高，适配聊天气泡内宽度），
 * 配色用全局 design token（--text-* / --bg-* / --border-*），明暗主题自动适配。
 */

import React from 'react'
import { buildLineDiff } from '@/utils/textDiff'
import type { ToolCallDetailEnhancement } from '@everyagent/plugin-api'
import './updateDiff.css'

export interface UpdateFileDiffProps {
  /** 替换前片段（update_file 的 oldcontent 参数）。 */
  oldContent: string
  /** 替换后片段（update_file 的 content 参数）。 */
  newContent: string
}

/** 上下文行默认折叠为前后各 3 行（±1 行交叠），点击表头切换全量。 */
const CONTEXT_RADIUS = 3

export function UpdateFileDiff({ oldContent, newContent }: UpdateFileDiffProps) {
  const [showAll, setShowAll] = React.useState(false)

  const diffLines = React.useMemo(() => buildLineDiff(oldContent, newContent), [oldContent, newContent])

  // 变更行（added/removed）前后各保留 CONTEXT_RADIUS 行上下文，其余折叠。
  const visibleLines = React.useMemo(() => {
    if (showAll || diffLines.length <= CONTEXT_RADIUS * 2 + 4) return diffLines
    const keep = new Set<number>()
    diffLines.forEach((line, idx) => {
      if (line.type === 'context') return
      for (let d = -CONTEXT_RADIUS; d <= CONTEXT_RADIUS; d += 1) {
        const target = idx + d
        if (target >= 0 && target < diffLines.length) keep.add(target)
      }
    })
    return diffLines.filter((_, idx) => keep.has(idx))
  }, [diffLines, showAll])

  const changedCount = React.useMemo(
    () => diffLines.filter((line) => line.type !== 'context').length,
    [diffLines],
  )

  return (
    <div className="update-diff">
      <button
        type="button"
        className="update-diff__toggle"
        onClick={() => setShowAll((value) => !value)}
        title={showAll ? '折叠上下文' : '显示全部行'}
      >
        {showAll ? '收起上下文' : `显示全部 ${diffLines.length} 行`}
      </button>
      <div className="update-diff__table">
        {visibleLines.map((line, idx) => (
          <div key={`${line.type}-${line.leftLineNumber ?? 'a'}-${line.rightLineNumber ?? 'b'}-${idx}`} className={`update-diff__line update-diff__line--${line.type}`}>
            <span className="update-diff__ln">{line.leftLineNumber ?? ''}</span>
            <span className="update-diff__ln">{line.rightLineNumber ?? ''}</span>
            <span className="update-diff__marker">{line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' '}</span>
            <span className="update-diff__content">{line.content || ' '}</span>
          </div>
        ))}
        {visibleLines.length === 0 ? (
          <div className="update-diff__line update-diff__line--context">
            <span className="update-diff__ln" />
            <span className="update-diff__ln" />
            <span className="update-diff__marker"> </span>
            <span className="update-diff__content">（内容无变化）</span>
          </div>
        ) : null}
      </div>
      <span className="update-diff__stat">{changedCount} 行变更</span>
    </div>
  )
}

/**
 * 从 update_file 工具参数构建详情增强（file-diff-view 插件的 render 实现）。
 * oldcontent / content 任一缺失或非字符串时返回 null，回落核心默认参数块渲染。
 */
export function buildUpdateFileDiffEnhancement(
  args: Record<string, unknown>,
): ToolCallDetailEnhancement | null {
  const oldContent = typeof args.oldcontent === 'string' ? args.oldcontent : null
  const newContent = typeof args.content === 'string' ? args.content : null
  if (oldContent === null || newContent === null) return null
  return {
    content: <UpdateFileDiff oldContent={oldContent} newContent={newContent} />,
  }
}
