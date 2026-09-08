import React from 'react'
import { createPortal } from 'react-dom'
import { ChevronDownIcon, FolderIcon, FileIcon } from '@/components/shared/AppGlyphs'
import { workspaceExplorerQueryService } from '@/query/workspaceExplorerQueryService'
import { findExplorerNode, upsertExplorerChildren } from '@/query/workspaceExplorerTreeUtils'
import type { WorkspaceExplorerNode } from '@/types/workspaceExplorer'

/**
 * 工作区文件系统（worker 侧 fs.* RPC 网关）的通用路径选择器。
 *
 * 紧凑内联触发器（显示当前选中路径名），点开后用 portal 弹出精简资源树，
 * 懒加载读取目录(初始只拉第一层,展开目录按需请求,零递归)。
 *
 * 通过 `selectionMode` 决定选中的粒度：
 * - `directory`：只允许选中目录节点（文件节点不渲染）；
 * - `file`：文件可选中，目录仅作为可展开导航容器；
 * - `any`：目录与文件均可选中。
 *
 * 选中即关闭并回填，无需额外确认按钮。
 */
export type PathSelectionMode = 'directory' | 'file' | 'any'

export interface WorkspacePathPickerProps {
  /** 所属工作区根(worker 机器绝对路径,树数据落对应工作区)。 */
  workspaceRoot: string
  /** 当前选中路径（业务绝对路径，如 `/data` 或 `/common-phrases.json`）。 */
  value: string
  /** 选中路径变化。 */
  onChange: (path: string) => void
  /**
   * 选择粒度：
   * - `directory`：只选目录（默认）；
   * - `file`：只选文件；
   * - `any`：目录与文件皆可。
   */
  selectionMode?: PathSelectionMode
  /** 是否禁用。 */
  disabled?: boolean
  /**
   * 是否允许选择工作区根目录。
   * 开启后弹层顶部多出一行「工作区根 /」，选中回填空串（表示根）。
   * 默认关闭，既有调用方行为不变。
   * 仅在选择粒度包含目录时（`directory` / `any`）有效。
   */
  allowRoot?: boolean
}

export default function WorkspacePathPicker({
  workspaceRoot,
  value,
  onChange,
  selectionMode = 'directory',
  disabled = false,
  allowRoot = false,
}: WorkspacePathPickerProps) {
  const rootRef = React.useRef<HTMLDivElement | null>(null)
  const triggerRef = React.useRef<HTMLButtonElement | null>(null)
  const menuRef = React.useRef<HTMLDivElement | null>(null)
  const [open, setOpen] = React.useState(false)
  const [position, setPosition] = React.useState<{ top: number; left: number } | null>(null)
  const [nodes, setNodes] = React.useState<WorkspaceExplorerNode[]>([])
  // 异步懒加载回调需要读取最新树,用 ref 旁路闭包。
  const nodesRef = React.useRef(nodes)
  nodesRef.current = nodes
  const [expanded, setExpanded] = React.useState<Set<string>>(new Set())
  const [loading, setLoading] = React.useState(false)
  const [loadError, setLoadError] = React.useState('')

  const triggerLabel = resolvePathLabel(value)
  const isRootSelected = value === '' || value === '/'
  const showRootRow = allowRoot && selectionMode !== 'file'

  React.useLayoutEffect(() => {
    if (!open) {
      setPosition(null)
      return
    }

    let cancelled = false
    setLoading(true)
    setLoadError('')
    workspaceExplorerQueryService.readTree(workspaceRoot)
      .then(async ({ nodes: treeNodes }) => {
        if (cancelled) return
        setNodes(treeNodes)
        // 定位选中项:reveal 沿路径逐段 stat 返回节点链(旁支零查找),
        // 沿链逐级加载目录子项并展开,让当前选中节点在弹层里可见。
        if (!value || value === '/') return
        const chain = await workspaceExplorerQueryService.reveal(workspaceRoot, value).catch(() => [])
        if (cancelled) return
        let tree = nodesRef.current
        for (const node of chain) {
          if (node.type !== 'directory') continue
          const children = await workspaceExplorerQueryService.loadChildren(workspaceRoot, node).catch(() => null)
          if (children && !cancelled) {
            tree = upsertExplorerChildren(tree, node.path, children)
            setNodes(tree)
          }
        }
        if (cancelled) return
        setExpanded((prev) => {
          const next = new Set(prev)
          for (const node of chain) {
            if (node.type === 'directory') next.add(node.path)
          }
          return next
        })
      })
      .catch((treeLoadError) => {
        if (cancelled) return
        setLoadError(treeLoadError instanceof Error ? treeLoadError.message : '读取目录失败')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    const updatePosition = () => {
      const trigger = triggerRef.current
      if (!trigger) return
      const triggerRect = trigger.getBoundingClientRect()
      const menuHeight = menuRef.current?.getBoundingClientRect().height ?? 280
      const viewportHeight = window.innerHeight
      const padding = 10
      const gap = 6
      const preferBottom = triggerRect.bottom + gap
      const preferTop = triggerRect.top - menuHeight - gap
      const top = preferBottom + menuHeight <= viewportHeight - padding
        ? preferBottom
        : Math.max(padding, preferTop)
      setPosition({ top, left: Math.max(padding, triggerRect.left) })
    }

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node
      if (!rootRef.current?.contains(target) && !menuRef.current?.contains(target)) {
        setOpen(false)
      }
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }

    updatePosition()
    window.addEventListener('pointerdown', handlePointerDown)
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('resize', updatePosition)
    window.addEventListener('scroll', updatePosition, true)
    return () => {
      cancelled = true
      window.removeEventListener('pointerdown', handlePointerDown)
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('resize', updatePosition)
      window.removeEventListener('scroll', updatePosition, true)
    }
  }, [open, value, workspaceRoot])

  const handleToggleExpand = React.useCallback((path: string) => {
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(path)) {
        next.delete(path)
      } else {
        next.add(path)
        // 展开:懒加载该目录的直接子节点(幂等:已加载/非目录则跳过)。
        const node = findExplorerNode(nodesRef.current, path)
        if (node && node.type === 'directory' && !node.loaded) {
          void workspaceExplorerQueryService.loadChildren(workspaceRoot, node)
            .then((children) => setNodes((prev) => upsertExplorerChildren(prev, path, children)))
            .catch(() => {
              // 加载失败静默:该目录保持未加载,再次点击展开会重试
            })
        }
      }
      return next
    })
  }, [workspaceRoot])

  const handleSelect = React.useCallback((path: string) => {
    setOpen(false)
    if (path !== value) {
      onChange(path)
    }
  }, [onChange, value])

  return (
    <div ref={rootRef} style={pickerRootStyle}>
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => {
          if (!disabled) setOpen((current) => !current)
        }}
        style={{
          ...triggerStyle,
          opacity: disabled ? 0.55 : 1,
          cursor: disabled ? 'not-allowed' : triggerStyle.cursor,
        }}
        title={value}
      >
        {selectionMode === 'file' ? (
          <FileIcon size={13} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
        ) : (
          <FolderIcon size={13} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
        )}
        <span style={triggerLabelStyle}>{triggerLabel}</span>
        <ChevronDownIcon
          size={13}
          style={{
            color: 'var(--text-muted)',
            transform: open ? 'rotate(180deg)' : 'rotate(0deg)',
            transition: 'transform 0.18s ease',
          }}
        />
      </button>
      {open && typeof document !== 'undefined' ? createPortal(
        <div
          ref={menuRef}
          role="listbox"
          aria-label="选择工作区路径"
          style={{
            ...menuStyle,
            top: position?.top ?? 0,
            left: position?.left ?? 0,
            visibility: position ? 'visible' : 'hidden',
          }}
        >
          {loading ? (
            <div style={emptyStyle}>加载中…</div>
          ) : loadError ? (
            <div style={emptyStyle}>{loadError}</div>
          ) : nodes.length === 0 ? (
            <div style={emptyStyle}>没有可选项</div>
          ) : (
            <div style={treeScrollStyle}>
              {showRootRow ? (
                <div
                  role="option"
                  aria-selected={isRootSelected}
                  onClick={() => handleSelect('')}
                  style={{
                    ...rowStyle,
                    background: isRootSelected ? 'color-mix(in srgb, var(--accent-blue-dim) 65%, transparent)' : 'transparent',
                    color: isRootSelected ? 'var(--text-primary)' : 'var(--text-secondary)',
                    fontWeight: isRootSelected ? 700 : 400,
                  }}
                  title="/"
                >
                  <span style={{ ...caretStyle, visibility: 'hidden' }}>{'▸'}</span>
                  <FolderIcon size={13} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                  <span style={rowLabelStyle}>工作区根 /</span>
                </div>
              ) : null}
              {nodes.map((node) => renderPathNode({
                node,
                depth: 0,
                selectionMode,
                expanded,
                selectedValue: value,
                onToggleExpand: handleToggleExpand,
                onSelect: handleSelect,
              }))}
            </div>
          )}
        </div>,
        document.body,
      ) : null}
    </div>
  )
}

interface PathNodeRenderProps {
  node: WorkspaceExplorerNode
  depth: number
  selectionMode: PathSelectionMode
  expanded: Set<string>
  selectedValue: string
  onToggleExpand: (path: string) => void
  onSelect: (path: string) => void
}

/** 节点是否应出现在列表中（目录模式隐藏文件，其余模式都显示）。 */
function isNodeVisible(node: WorkspaceExplorerNode, mode: PathSelectionMode): boolean {
  if (mode === 'directory') return node.type === 'directory'
  return true
}

/** 节点是否可被选中。 */
function isNodeSelectable(node: WorkspaceExplorerNode, mode: PathSelectionMode): boolean {
  if (mode === 'directory') return node.type === 'directory'
  if (mode === 'file') return node.type === 'file'
  return true
}

function renderPathNode({
  node,
  depth,
  selectionMode,
  expanded,
  selectedValue,
  onToggleExpand,
  onSelect,
}: PathNodeRenderProps): React.ReactNode {
  if (!isNodeVisible(node, selectionMode)) return null

  const isDirectory = node.type === 'directory'
  // 懒加载:目录统一显示展开箭头(空目录点开为空),不额外探测 hasChildren。
  const hasDirChildren = isDirectory
  const isExpanded = expanded.has(node.path)
  const selected = node.path === selectedValue
  const selectable = isNodeSelectable(node, selectionMode)
  const glyph = isDirectory
    ? <FolderIcon size={13} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
    : <FileIcon size={13} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />

  // 行点击：可选中则选中；目录在「不可选中」时（仅文件模式）视为导航展开。
  const handleRowClick = () => {
    if (selectable) {
      onSelect(node.path)
      return
    }
    if (isDirectory) {
      onToggleExpand(node.path)
    }
  }

  return (
    <React.Fragment key={node.path}>
      <div
        role="option"
        aria-selected={selectable && selected}
        onClick={handleRowClick}
        style={{
          ...rowStyle,
          paddingLeft: 8 + depth * 14,
          background: selected ? 'color-mix(in srgb, var(--accent-blue-dim) 65%, transparent)' : 'transparent',
          color: selected ? 'var(--text-primary)' : 'var(--text-secondary)',
          fontWeight: selected ? 700 : 400,
          cursor: selectable ? 'pointer' : (node.type === 'directory' ? 'pointer' : 'default'),
        }}
        title={node.path}
      >
        <span
          onClick={(event) => {
            if (!isDirectory) return
            event.stopPropagation()
            onToggleExpand(node.path)
          }}
          style={{ ...caretStyle, visibility: isDirectory ? 'visible' : 'hidden' }}
        >
          {isExpanded ? '▾' : '▸'}
        </span>
        {glyph}
        <span style={rowLabelStyle}>{node.name}</span>
      </div>
      {isExpanded && isDirectory
        ? (node.children ?? [])
          .filter((child) => child.type === 'directory')
          .map((child) => renderPathNode({
            node: child,
            depth: depth + 1,
            selectionMode,
            expanded,
            selectedValue,
            onToggleExpand,
            onSelect,
          }))
        : null}
    </React.Fragment>
  )
}

/** 取路径的展示名（路径最后一段，如 `/data/my-project` → `my-project`）。 */
function resolvePathLabel(path: string): string {
  const segments = path.split('/').filter(Boolean)
  if (segments.length === 0) return '/'
  return segments[segments.length - 1]
}

const pickerRootStyle: React.CSSProperties = {
  position: 'relative',
  display: 'inline-flex',
}

const triggerStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '4px 0',
  border: 'none',
  background: 'transparent',
  color: 'var(--text-primary)',
  cursor: 'pointer',
  textAlign: 'left',
  whiteSpace: 'nowrap',
}

const triggerLabelStyle: React.CSSProperties = {
  whiteSpace: 'nowrap',
  fontSize: 'var(--text-xs)',
  fontWeight: 400,
  lineHeight: 1.4,
  maxWidth: 160,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
}

const menuStyle: React.CSSProperties = {
  position: 'fixed',
  zIndex: 'var(--z-tooltip)',
  minWidth: 220,
  maxWidth: 320,
  maxHeight: 320,
  padding: 6,
  borderRadius: '14px',
  border: '1px solid color-mix(in srgb, var(--accent-blue) 12%, var(--border))',
  background: 'color-mix(in srgb, var(--task-launcher-surface-bg, var(--bg-secondary)) 96%, white)',
  boxShadow: '0 18px 42px rgba(0, 0, 0, 0.14)',
  backdropFilter: 'blur(12px)',
  display: 'flex',
  flexDirection: 'column',
}

const treeScrollStyle: React.CSSProperties = {
  minHeight: 0,
  overflowY: 'auto',
  display: 'flex',
  flexDirection: 'column',
}

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  paddingRight: 10,
  padding: '6px 10px 6px 8px',
  borderRadius: 'var(--radius-sm)',
  whiteSpace: 'nowrap',
  userSelect: 'none',
}

const caretStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: 12,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  flexShrink: 0,
  cursor: 'pointer',
}

const rowLabelStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
}

const emptyStyle: React.CSSProperties = {
  padding: '14px 10px',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  textAlign: 'center',
}
