import React from 'react'
import type { MarkdownHeading } from './markdownOutline'

export default function MarkdownOutlinePanel({
  headings,
  onSelect,
  showHeader = true,
}: {
  headings: MarkdownHeading[]
  onSelect: (heading: MarkdownHeading) => void
  /** 是否渲染自带的「Markdown 结构」标题栏。嵌入到 Dialog 等已有标题的容器时可置 false。 */
  showHeader?: boolean
}) {
  return (
    <div className="file-viewer__outline" style={outlinePanelStyle}>
      {showHeader ? (
        <div style={outlineHeaderStyle}>
          <span style={outlineTitleStyle}>Markdown 结构</span>
          <span style={outlineMetaStyle}>{headings.length} 个标题</span>
        </div>
      ) : null}
      {headings.length === 0 ? (
        <div style={outlineEmptyStyle}>当前文件没有可导航的 1-6 级标题</div>
      ) : (
        <div style={outlineListStyle}>
          {headings.map((heading) => (
            <button
              key={heading.id}
              type="button"
              onClick={() => onSelect(heading)}
              className="file-viewer__outline-item"
              style={{
                ...outlineItemStyle,
                marginLeft: `${(heading.level - 1) * 14}px`,
              }}
              title={heading.text}
            >
              <span style={outlineItemTextStyle}>{heading.text}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

const outlinePanelStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
  overflow: 'hidden',
  background: 'var(--bg-secondary)',
}

const outlineHeaderStyle: React.CSSProperties = {
  padding: '12px 14px',
  borderBottom: '1px solid var(--border-light)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
}

const outlineTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 800,
  color: 'var(--text-primary)',
}

const outlineMetaStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const outlineEmptyStyle: React.CSSProperties = {
  padding: '14px 16px',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
}

const outlineListStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  padding: '8px 8px 12px',
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
}

const outlineItemStyle: React.CSSProperties = {
  width: '100%',
  border: '1px solid transparent',
  borderRadius: 'var(--radius-sm)',
  background: 'transparent',
  color: 'var(--text-secondary)',
  display: 'flex',
  alignItems: 'center',
  padding: '0 10px',
  textAlign: 'left',
  cursor: 'pointer',
}

const outlineItemTextStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  lineHeight: 1.5,
  color: 'var(--text-primary)',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
}
