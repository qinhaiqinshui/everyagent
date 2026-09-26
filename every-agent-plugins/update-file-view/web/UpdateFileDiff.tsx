/**
 * update_file 内嵌 diff 表（update-file-view 插件内部组件，展开态正文）。
 *
 * 用 worker 工具参数 oldcontent（替换前片段）与 content（替换后片段）生成
 * 行级 unified diff（复用 web 的 buildLineDiff 纯函数，LCS 行对齐）。
 *
 * 样式走本插件自带 updateDiff.css（紧凑行高，适配聊天气泡内宽度），
 * 配色用全局 design token（--text-* / --bg-* / --border-*），明暗主题自动适配。
 */

import React from 'react'
import { buildLineDiff } from './utils'
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
