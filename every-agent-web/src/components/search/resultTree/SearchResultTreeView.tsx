/**
 * 通用搜索结果树原语 · 视图组件。
 *
 * 按 SearchResultGroup[] 渲染「组头(折叠切换 + 图标 + 主名 + 次要信息 + 来源
 * 标记 + 命中数徽章)+ 命中行(前缀标记 + 高亮正文)」,是 SearchResultsTree(文件)与
 * TaskSearchResultsTree(任务)共用的渲染实现:树容器 / 分组 / 组头 / 命中行 /
 * 高亮样式在此单点,目标间的小差异(文件图标 vs 无、目录 grow vs 状态标签、行号
 * vs 轮次前缀)全部体现在模型(header / prefix)上,不在本组件分支。外部
 * provider 来源标记同样由 header.providerId 驱动(缺省/内置 rg 静默不显示)。
 * 折叠状态由调用方(SearchPanel)统一持有,键 = SearchResultGroup.key。
 */
import React from 'react'
import { ChevronDownIcon } from '../../shared/AppGlyphs'
import { FileTypeIcon } from '../../shared/FileTypeGlyphs'
import HighlightedMatchLine from './HighlightedMatchLine'
import type { SearchResultGroup, SearchResultHit, SearchResultHitPrefix } from './model'
import { isBuiltinProviderId } from './model'

export interface SearchResultTreeViewProps {
  /** 分组列表(顺序即展示顺序)。 */
  groups: SearchResultGroup[]
  /** 处于折叠态的 groupKey 集合(不在集合内 = 展开),由面板统一持有(供折叠/展开全部)。 */
  collapsedKeys: ReadonlySet<string>
  /** 切换某分组的折叠态。 */
  onToggleGroup: (key: string) => void
}

/** 树容器样式(两棵结果树共用;文件名搜索的扁平行列表也复用同一容器)。 */
export const resultTreeStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '2px 0 8px',
}

/**
 * 通用搜索结果树:逐组渲染 SearchResultGroup,组头点击(或 Enter)切换折叠。
 */
export default function SearchResultTreeView({
  groups,
  collapsedKeys,
  onToggleGroup,
}: SearchResultTreeViewProps) {
  return (
    <div style={resultTreeStyle}>
      {groups.map((group) => (
        <ResultGroupView
          key={group.key}
          group={group}
          collapsed={collapsedKeys.has(group.key)}
          onToggle={() => onToggleGroup(group.key)}
        />
      ))}
    </div>
  )
}

/** 单个分组:头部行(折叠切换 + 组头信息 + 命中数)+ 命中行列表。 */
function ResultGroupView({
  group,
  collapsed,
  onToggle,
}: {
  group: SearchResultGroup
  collapsed: boolean
  onToggle: () => void
}) {
  const [hovered, setHovered] = React.useState(false)
  const { header } = group

  // 叶子组(无命中行,如 file-name):组头本身即条目(可点击打开),无折叠箭头与命中数徽章。
  if (group.hits.length === 0) {
    const clickable = typeof header.onOpen === 'function'
    return (
      <div style={groupStyle}>
        <div
          role={clickable ? 'button' : undefined}
          tabIndex={clickable ? 0 : undefined}
          title={header.title}
          style={{
            ...headerStyle,
            background: hovered && clickable ? 'var(--bg-hover)' : 'transparent',
            cursor: clickable ? 'pointer' : 'default',
          }}
          onClick={clickable ? header.onOpen : undefined}
          onKeyDown={clickable ? (event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              header.onOpen?.()
            }
          } : undefined}
          onMouseEnter={() => setHovered(true)}
          onMouseLeave={() => setHovered(false)}
        >
          <span style={leafIndentStyle} />
          {header.icon ? (
            <span style={iconStyle}>
              <FileTypeIcon fileName={header.icon.fileName} size={14} />
            </span>
          ) : null}
          <span style={nameStyle}>{header.name}</span>
          {header.detail ? (
            <span style={header.detail.grow ? detailGrowStyle : detailStyle}>
              {header.detail.text}
            </span>
          ) : null}
          {!isBuiltinProviderId(header.providerId) ? (
            <span style={providerTagStyle} title={header.providerId}>
              {header.providerId}
            </span>
          ) : null}
        </div>
      </div>
    )
  }

  return (
    <div style={groupStyle}>
      <div
        role="button"
        tabIndex={0}
        title={header.title}
        aria-expanded={!collapsed}
        style={{
          ...headerStyle,
          background: hovered ? 'var(--bg-hover)' : 'transparent',
        }}
        onClick={onToggle}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            onToggle()
          }
        }}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
      >
        <span style={chevronStyle}>
          <ChevronDownIcon size={13} style={collapsed ? chevronCollapsedStyle : undefined} />
        </span>
        {header.icon ? (
          <span style={iconStyle}>
            <FileTypeIcon fileName={header.icon.fileName} size={14} />
          </span>
        ) : null}
        <span style={nameStyle}>{header.name}</span>
        {header.detail ? (
          <span style={header.detail.grow ? detailGrowStyle : detailStyle}>
            {header.detail.text}
          </span>
        ) : null}
        {!isBuiltinProviderId(header.providerId) ? (
          <span style={providerTagStyle} title={header.providerId}>
            {header.providerId}
          </span>
        ) : null}
        <span style={countStyle}>{group.hits.length}</span>
      </div>
      {!collapsed ? (
        <div style={matchesStyle}>
          {group.hits.map((hit, index) => (
            <ResultHitRow key={`${group.key}:${index}`} hit={hit} />
          ))}
        </div>
      ) : null}
    </div>
  )
}

/** 单条命中行:前缀标记(右对齐灰字)+ 高亮正文,点击(或 Enter)执行 onOpen。 */
function ResultHitRow({ hit }: { hit: SearchResultHit }) {
  const [hovered, setHovered] = React.useState(false)

  return (
    <div
      role="button"
      tabIndex={0}
      title={hit.title}
      style={{
        ...hitRowStyle,
        background: hovered ? 'var(--bg-hover)' : 'transparent',
      }}
      onClick={hit.onOpen}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          hit.onOpen()
        }
      }}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {hit.prefix ? <span style={hitPrefixStyle(hit.prefix)}>{hit.prefix.text}</span> : null}
      <span style={hitTextStyle}>
        <HighlightedMatchLine line={hit.label} matchIndex={hit.matchIndex} matchText={hit.matchText} />
      </span>
    </div>
  )
}

/** 命中行前缀样式(文件行号 minWidth 30 / 任务轮次标记 minWidth 84,均右对齐灰字)。 */
function hitPrefixStyle(prefix: SearchResultHitPrefix): React.CSSProperties {
  return {
    ...hitPrefixBaseStyle,
    minWidth: prefix.minWidth ?? 30,
  }
}

const groupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  minWidth: 0,
}

const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 5,
  minWidth: 0,
  padding: '3px 6px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  userSelect: 'none',
}

const chevronStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  color: 'var(--text-muted)',
}

const chevronCollapsedStyle: React.CSSProperties = {
  transform: 'rotate(-90deg)',
}

/** 叶子组无折叠箭头,用等宽占位对齐图标缩进(与非叶子组组头对齐)。 */
const leafIndentStyle: React.CSSProperties = {
  display: 'inline-block',
  width: 13,
  flexShrink: 0,
}

const iconStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
}

const nameStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 600,
  color: 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  flexShrink: 1,
}

/** 组头次要信息(不伸展):任务状态标签等。 */
const detailStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

/** 组头次要信息(占满剩余宽度、超长省略):文件相对目录。 */
const detailGrowStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  flex: 1,
  minWidth: 0,
}

/**
 * 组头来源标记(detail 区尾部的小 tag,贴合 antd Tag 的轻量观感):
 * 仅外部 provider 结果显示;providerId 缺省或为内置 rg 时不渲染任何标记。
 * 悬停 title 提示完整来源 id(覆盖组头默认 title,悬停在该 tag 上时生效)。
 */
const providerTagStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 'var(--text-xs)',
  lineHeight: 1.5,
  padding: '0 4px',
  borderRadius: 4,
  border: '1px solid var(--border)',
  color: 'var(--text-muted)',
  background: 'var(--bg-tertiary)',
  maxWidth: 120,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  cursor: 'pointer',
}

const countStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  padding: '0 6px',
  borderRadius: 999,
  color: 'var(--accent-blue)',
  background: 'var(--accent-blue-dim)',
}

const matchesStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
  paddingLeft: 10,
}

const hitRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
  padding: '1px 6px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  fontFamily: 'var(--font-mono)',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
  minWidth: 0,
}

const hitPrefixBaseStyle: React.CSSProperties = {
  flexShrink: 0,
  textAlign: 'right',
  color: 'var(--text-muted)',
  userSelect: 'none',
  whiteSpace: 'nowrap',
}

const hitTextStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
  overflow: 'hidden',
}
