import React from 'react'
import { Tree, Tooltip, theme } from 'antd'
import type { TreeDataNode } from 'antd'
import { FolderIcon } from '../shared/AppGlyphs'
import ListRowActions, { type ListRowActionItem, type ListRowActionsHandle } from '../shared/ui/ListRowActions'
import { Checkbox } from '../shared/ui'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import { useLongPress, type LongPressHandlers } from '@/hooks/useLongPress'
import type {
  WorkspaceExplorerContextTarget,
  WorkspaceExplorerNode,
  WorkspaceExplorerOpenTarget,
} from '@/types/workspaceExplorer'

const { useToken } = theme

export default function WorkspaceExplorerTree({
  workspaceRoot,
  nodes,
  expandedPaths,
  selectedPath,
  locatePath,
  locateRequestedAt,
  onLocateApplied,
  onToggleDirectory,
  onSelectPath,
  onOpenFile,
  onRequestDelete,
  getActionItems,
  metaMode,
  multiSelectMode = false,
  selectedPaths,
  onToggleSelectedPath,
}: {
  /** 所属工作区根(上下文菜单 target 需携带,命令落对应工作区)。 */
  workspaceRoot: string
  nodes: WorkspaceExplorerNode[]
  expandedPaths: Set<string>
  selectedPath: string | null
  locatePath?: string | null
  locateRequestedAt?: number | null
  onLocateApplied?: () => void
  onToggleDirectory: (path: string) => void
  onSelectPath: (path: string) => void
  onOpenFile: (target: WorkspaceExplorerOpenTarget) => void
  onRequestDelete?: (target: WorkspaceExplorerContextTarget) => void
  getActionItems?: (target: WorkspaceExplorerContextTarget) => ListRowActionItem[]
  /** 行尾元信息显示模式：文件大小或最后编辑时间。 */
  metaMode?: 'size' | 'modified'
  /** 多选模式：开启后每行前置复选框，复选框切换选中，行单击不再触发单选高亮。 */
  multiSelectMode?: boolean
  /** 多选模式下的已选路径集合（仅在 multiSelectMode 时使用）。 */
  selectedPaths?: Set<string>
  /** 多选模式下切换单个路径选中态。 */
  onToggleSelectedPath?: (path: string) => void
}) {
  const isMobile = useResponsiveViewport().isMobile
  const { token } = useToken()
  const treeRef = React.useRef<{
    scrollTo: (opts: { key: React.Key; offset?: number }) => void
  } | null>(null)
  const actionsRef = React.useRef<ListRowActionsHandle | null>(null)
  const containerRef = React.useRef<HTMLDivElement | null>(null)

  const expandedKeys = React.useMemo(() => Array.from(expandedPaths), [expandedPaths])

  const treeData = React.useMemo(() => {
    const toNode = (node: WorkspaceExplorerNode): TreeDataNode & { dataRef: WorkspaceExplorerNode } => {
      const isDirectory = node.type === 'directory'
      return {
        key: node.path,
        // 名称前不渲染 folder/file 图标:开合状态由 switcherIcon(FolderIcon open/closed)表达
        title: node.name,
        isLeaf: !isDirectory,
        children: isDirectory && node.children?.length ? node.children.map(toNode) : undefined,
        dataRef: node,
      }
    }
    return nodes.map(toNode) as unknown as TreeDataNode[]
  }, [nodes, expandedPaths])

  // 定位：当 locatePath 变化且已提供时间戳时，滚动到对应行
  React.useEffect(() => {
    if (!locatePath || locateRequestedAt == null) return
    const id = window.setTimeout(() => {
      treeRef.current?.scrollTo({ key: locatePath, offset: 80 })
      onLocateApplied?.()
    }, 0)
    return () => window.clearTimeout(id)
  }, [locatePath, locateRequestedAt, onLocateApplied])

  const { wasLongPressed, ...longPressHandlers } = useLongPress({
    isMobile,
    delay: 500,
    onLongPress: (el, point) => {
      actionsRef.current?.openMenu(el, point)
    },
  })

  if (nodes.length === 0) {
    return (
      <div style={{ padding: token.paddingSM, color: token.colorTextDisabled, fontSize: token.fontSizeSM }}>
        当前没有可显示的工作区文件。
      </div>
    )
  }

  const titleRender = (dataNode: TreeDataNode) => {
    const node = (dataNode as TreeDataNode & { dataRef: WorkspaceExplorerNode }).dataRef
    const isDirectory = node.type === 'directory'
    const isMultiSelect = multiSelectMode && !!onToggleSelectedPath
    const isSelected = isMultiSelect && !!selectedPaths?.has(node.path)
    const metaText = metaMode === 'size' ? formatBytes(node.size) : formatMtime(node.mtimeMs)
    const target: WorkspaceExplorerContextTarget = {
      workspaceRoot,
      path: node.path,
      name: node.name,
      type: node.type,
      openTarget: node.openTarget,
    }
    const customItems = getActionItems?.(target) ?? []
    const moreActionItems: ListRowActionItem[] = onRequestDelete
      ? [
          ...customItems,
          {
            key: 'delete',
            label: '删除',
            danger: true,
            onSelect: () => onRequestDelete(target),
          },
        ]
      : customItems

    return (
      <div
        data-key={node.path}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: token.paddingXS,
          width: '100%',
          cursor: 'default',
          // 名称文字允许鼠标选中复制(文件/文件夹名)
          userSelect: 'text',
          ...(isSelected ? {
            background: 'color-mix(in srgb, var(--accent-blue-dim) 55%, transparent)',
            borderRadius: 'var(--radius-sm)',
          } : null),
        }}
        onClick={(e) => {
          if (wasLongPressed()) return
          // 不阻止冒泡:让 antd 的 onSelect 正常触发(单击选中高亮)。
          // 目录展开/收起改由双击(onDoubleClick)或点击 FolderIcon(onExpand)触发。
        }}
        onDoubleClick={(e) => {
          e.stopPropagation()
          // 双击目录:展开/收起(单击只选中,双击 toggle,与文件系统习惯一致)
          if (isDirectory) {
            onToggleDirectory(node.path)
            return
          }
          // 双击文件:打开编辑
          if (!node.openTarget) return
          onOpenFile(node.openTarget)
        }}
        onContextMenu={(e) => {
          e.preventDefault()
          actionsRef.current?.openMenu(e.currentTarget, { x: e.clientX, y: e.clientY })
        }}
        onPointerDown={longPressHandlers.onPointerDown}
        onPointerMove={longPressHandlers.onPointerMove}
        onPointerUp={longPressHandlers.onPointerUp}
        onPointerLeave={longPressHandlers.onPointerLeave}
      >
        {isMultiSelect ? (
          <span
            style={{ flexShrink: 0, display: 'inline-flex', alignItems: 'center' }}
            onClick={(event) => event.stopPropagation()}
          >
            <Checkbox
              checked={isSelected}
              onChange={() => onToggleSelectedPath?.(node.path)}
              aria-label={`选择 ${node.name}`}
            />
          </span>
        ) : null}
        <span
          style={{
            flex: 1,
            minWidth: 0,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {node.name}
        </span>
        {metaText ? (
          <Tooltip title={metaMode === 'size' ? `大小 ${metaText}` : `修改于 ${metaText}`}>
            <span
              style={{
                flexShrink: 0,
                fontSize: token.fontSizeSM,
                color: token.colorTextTertiary,
                fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
              }}
            >
              {metaText}
            </span>
          </Tooltip>
        ) : null}
        {moreActionItems.length > 0 ? (
          // ListRowActions 触发按钮 22×22 永久占位(ui-overlays 默认 visibility:hidden 仍占布局,
          // 而 WorkspaceExplorerTree 未接入 .ui-row hover 体系,该按钮永远不显示) → 缩到 0 宽
          // 让 actionsRef 仍可用(openMenu 由右键/长按调用,不依赖 trigger DOM),但不再占布局
          <span style={{ flexShrink: 0, width: 0, height: 0, overflow: 'hidden' }}>
            <ListRowActions
              ref={actionsRef}
              items={moreActionItems}
              title={`${node.type === 'directory' ? '文件夹' : '文件'}更多操作`}
              isMobile={isMobile}
            />
          </span>
        ) : null}
      </div>
    )
  }

  return (
    <div ref={containerRef} style={{ padding: `0 ${token.paddingXXS}px` }}>
      <Tree.DirectoryTree
        ref={treeRef as never}
        treeData={treeData}
        titleRender={titleRender}
        // 名称前不渲染 icon(重复);开合状态由 switcherIcon 的 FolderIcon 表达
        showIcon={false}
        // 关闭 DirectoryTree 默认的 expandAction='click'(单击节点自动展开目录)。
        // 展开/收起改为:双击名称(onDoubleClick toggle) 或 单击 FolderIcon(onExpand)。
        expandAction={false}
        blockNode
        selectable={!multiSelectMode}
        className="ws-tree"
        styles={{
          itemTitle: {
            display: 'flex',
            flex: 1,
            minWidth: 0,
            alignItems: 'center',
          },
        }}
        selectedKeys={selectedPath ? [selectedPath] : []}
        expandedKeys={expandedKeys}
        defaultExpandAll={false}
        autoExpandParent={false}
        onExpand={(keys) => {
          // 与父级 expandedPaths 对齐：新增的展开触发 onToggleDirectory(path)
          const next = new Set(keys.map(String))
          // 先处理收起的（从 expandedPaths 中移除）
          for (const prev of expandedPaths) {
            if (!next.has(prev)) onToggleDirectory(prev)
          }
          // 再处理新展开的
          for (const k of next) {
            if (!expandedPaths.has(k)) onToggleDirectory(k)
          }
        }}
        onSelect={(keys) => {
          const key = keys[0] as string | undefined
          if (!key) return
          // 单击仅选中(高亮)。目录展开/收起由双击(onDoubleClick)或点击 FolderIcon(onExpand)触发。
          onSelectPath(key)
        }}
        // 展开/收起图标 = FolderIcon 开/合形态(替代 antd 自带箭头)。
        // 注意:antd 6 会把用户 switcherIcon 包进 SwitcherIconCom 再传给 rc-tree,
        // 故 switcher span 必然渲染(返回 false 只能清空内容,span 仍占 22×22);
        // 此处利用该位置显示开合图标,不再返回 false。叶子行的空 span 由 CSS 隐藏。
        // SVG 居中由 .ws-tree .ant-tree-switcher 的 display:flex 负责,SVG 自身保持默认 block。
        switcherIcon={({ isLeaf, expanded }) => {
          if (isLeaf) return null
          return (
            <FolderIcon
              size={14}
              open={expanded}
              style={{ color: token.colorTextSecondary }}
            />
          )
        }}
        style={{ fontSize: token.fontSizeSM }}
      />
    </div>
  )
}

/**
 * 将字节大小格式化为便于阅读的文本。
 */
function formatBytes(value: number): string {
  if (!value || value < 0) return ''
  if (value < 1024) return `${value}B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)}KB`
  return `${(value / 1024 / 1024).toFixed(1)}MB`
}

/**
 * 将最后修改时间戳格式化为「年-月-日 时:分」。
 * 使用本地时区组件手动拼接，避免 toLocaleString 的本地化差异。
 */
function formatMtime(value: number): string {
  if (!value || Number.isNaN(value)) return ''
  const date = new Date(value)
  const pad = (num: number) => String(num).padStart(2, '0')
  const year = date.getFullYear()
  const month = pad(date.getMonth() + 1)
  const day = pad(date.getDate())
  const hours = pad(date.getHours())
  const minutes = pad(date.getMinutes())
  return `${year}-${month}-${day} ${hours}:${minutes}`
}

export type {
  WorkspaceExplorerContextTarget,
  WorkspaceExplorerNode,
  WorkspaceExplorerOpenTarget,
} from '@/types/workspaceExplorer'
