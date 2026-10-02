/**
 * update_file 内嵌 diff 表（update-file-view 插件内部组件，展开态正文）。
 *
 * 输入是宿主已算好的展示行（DiffRow[]）与变更统计——LCS 只算一次，折叠态徽章与
 * 展开态表格共用同一份结果。展示约定：
 * - 顺序遵循 git 惯例：同一行被改写时先「− 旧值」后「+ 新值」，并对实际改动的
 *   词做行内高亮（v2.0 → v3.0 这类小改动一眼可辨，不必逐字比对两行全文）。
 * - 一律完整展开：不做上下文折叠，也没有「替换片段 / 展开全部 / 收起上下文」这类
 *   标题与控件，片段内所有行（含未变更的上下文行）直接平铺呈现。
 * - 行号是**片段内相对行号**（update_file 的 oldcontent/content 只是文件的一个
 *   片段，不是整文件），经每行行号的 tooltip 说明，避免被误读为文件绝对行号。
 *
 * 样式走本插件自带 updateDiff.css（紧凑行高，适配聊天气泡内宽度），
 * 配色用全局 design token（--text-* / --bg-* / --border-* / --accent-*），明暗主题自动适配。
 */

import React from 'react'
import type { DiffRow, InlinePart } from './utils'
import './updateDiff.css'

export interface UpdateFileDiffProps {
  /** 展示行（已修正顺序、带行内高亮片段）。 */
  rows: DiffRow[]
  /** 新增行数。 */
  added: number
  /** 删除行数。 */
  removed: number
  /** 调用失败：diff 只是 AI 尝试写入的内容，并未落盘。 */
  unapplied?: boolean
}

export function UpdateFileDiff({ rows, added, removed, unapplied }: UpdateFileDiffProps) {
  const changedRowCount = added + removed

  // 无实际变更：不必铺一整张 diff 表，一句说明即可。
  if (changedRowCount === 0) {
    return (
      <div className="update-diff">
        {unapplied ? <span className="update-diff__unapplied">未应用</span> : null}
        <div className="update-diff__empty">替换内容与原片段一致，文件未发生实际变化。</div>
      </div>
    )
  }

  return (
    <div className="update-diff">
      {unapplied ? <span className="update-diff__unapplied">未应用</span> : null}
      <div className="update-diff__table" role="table" aria-label="update_file 变更内容">
        {rows.map((row, idx) => <DiffRowView key={`r${idx}`} row={row} />)}
      </div>
    </div>
  )
}

/** 一条展示行：context 一行；changed 拆成「− 旧值 / + 新值」两行；纯增删各一行。 */
function DiffRowView({ row }: { row: DiffRow }) {
  if (row.kind === 'changed') {
    return (
      <>
        <DiffLineView marker="−" type="removed" lineNumber={row.oldLine} parts={row.oldParts} />
        <DiffLineView marker="+" type="added" lineNumber={row.newLine} parts={row.newParts} />
      </>
    )
  }
  if (row.kind === 'removed') {
    return <DiffLineView marker="−" type="removed" lineNumber={row.oldLine} parts={row.oldParts} />
  }
  if (row.kind === 'added') {
    return <DiffLineView marker="+" type="added" lineNumber={row.newLine} parts={row.newParts} />
  }
  return <DiffLineView marker="" type="context" lineNumber={row.newLine ?? row.oldLine} parts={row.oldParts} />
}

/** 一行 diff：行号 + 变更标记 + 内容（内容里的改动词带行内高亮）。 */
function DiffLineView({
  marker,
  type,
  lineNumber,
  parts,
}: {
  marker: string
  type: 'context' | 'added' | 'removed'
  lineNumber: number | null
  parts: InlinePart[]
}) {
  return (
    <div className={`update-diff__line update-diff__line--${type}`} role="row">
      <span className="update-diff__ln" title="片段内相对行号（非文件绝对行号）">{lineNumber ?? ''}</span>
      <span className="update-diff__marker" aria-hidden="true">{marker}</span>
      <span className="update-diff__content">
        {parts.length > 0
          ? parts.map((part, idx) => (part.changed
            ? <span key={idx} className={`update-diff__hl update-diff__hl--${type}`}>{part.text || '\u00A0'}</span>
            : <React.Fragment key={idx}>{part.text || '\u00A0'}</React.Fragment>))
          : '\u00A0'}
      </span>
    </div>
  )
}
