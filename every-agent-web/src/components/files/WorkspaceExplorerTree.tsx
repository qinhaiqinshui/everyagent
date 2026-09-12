import React from 'react'
import { Dropdown, Tree, Tooltip, theme } from 'antd'
import type { MenuProps, TreeDataNode } from 'antd'
import { ChevronDownIcon, ChevronRightIcon, FolderIcon } from '../shared/AppGlyphs'
import { FileTypeIcon } from '../shared/FileTypeGlyphs'
import type { ListRowActionItem } from '../shared/ui/ListRowActions'
import { Checkbox } from '../shared/ui'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import { useLongPress } from '@/hooks/useLongPress'
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
  const containerRef = React.useRef<HTMLDivElement | null>(null)
  // 当前打开右键菜单的行路径：同一时刻只允许一个右键菜单打开(原生右键习惯)，由各 TreeNodeRow 受控。
  const [openMenuPath, setOpenMenuPath] = React.useState<string | null>(null)

  const expandedKeys = React.useMemo(() => Array.from(expandedPaths), [expandedPaths])

  const treeData = React.useMemo(() => {
    const toNode = (node: WorkspaceExplorerNode): TreeDataNode & { dataRef: WorkspaceExplorerNode } => {
      const isDirectory = node.type === 'directory'
      return {
        key: node.path,
        // 名称前不渲染 folder/file 图标:开合状态由 switcherIcon(通用箭头)表达
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

  if (nodes.length === 0) {
    return (
      <div style={{ padding: token.paddingSM, color: token.colorTextDisabled, fontSize: token.fontSizeSM }}>
        当前没有可显示的工作区文件。
      </div>
    )
  }

  const titleRender = (dataNode: TreeDataNode) => {
    const node = (dataNode as TreeDataNode & { dataRef: WorkspaceExplorerNode }).dataRef
    const isMultiSelect = multiSelectMode && !!onToggleSelectedPath
    const isSelected = isMultiSelect && !!selectedPaths?.has(node.path)
    const target: WorkspaceExplorerContextTarget = {
      workspaceRoot,
      path: node.path,
      name: node.name,
      type: node.type,
      openTarget: node.openTarget,
    }
    const customItems = getActionItems?.(target) ?? []
    const allItems: ListRowActionItem[] = onRequestDelete
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
    // 树行右键菜单统一走 antd Dropdown(trigger=contextMenu)：把自定义动作项映射成 antd Menu items，
    // 由 antd 负责弹层定位/关闭/键盘等，业务动作仍通过原 onSelect 回调触发(目标即当前行 target)。
    const menuItems: MenuProps['items'] = allItems.map((item) => ({
      key: item.key,
      label: item.label,
      icon: item.icon,
      danger: item.danger,
      disabled: item.disabled,
      onClick: () => item.onSelect?.(),
    }))

    return (
      <TreeNodeRow
        node={node}
        target={target}
        menuItems={menuItems}
        open={openMenuPath === node.path}
        onOpenChange={(next) => setOpenMenuPath(next ? node.path : null)}
        isMobile={isMobile}
        isMultiSelect={isMultiSelect}
        isSelected={isSelected}
        metaMode={metaMode}
        onToggleDirectory={onToggleDirectory}
        onOpenFile={onOpenFile}
        onToggleSelectedPath={onToggleSelectedPath}
      />
    )
  }

  return (
    <div ref={containerRef} style={{ padding: `0 ${token.paddingXXS}px` }}>
      <Tree.DirectoryTree
        ref={treeRef as never}
        treeData={treeData}
        titleRender={titleRender}
        // antd 内置 icon 槽位仍关闭:类型图标由 titleRender(TreeNodeRow)自行渲染
        showIcon={false}
        // 关闭 DirectoryTree 默认的 expandAction='click'(单击节点自动展开目录)。
        // 展开/收起改为:双击名称(onDoubleClick toggle) 或 单击箭头(onExpand)。
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
          // 单击仅选中(高亮)。目录展开/收起由双击(onDoubleClick)或点击箭头(onExpand)触发。
          onSelectPath(key)
        }}
        // 展开/收起图标 = 通用折叠箭头(收起朝右 ChevronRight,展开朝下 ChevronDown),独占一列。
        // 注意:antd 6 会把用户 switcherIcon 包进 SwitcherIconCom 再传给 rc-tree,
        // 故 switcher span 必然渲染(返回 false 只能清空内容,span 仍占位)。
        // 叶子行(文件)返回 null → antd 渲染 noop 空 span 保留占位(不隐藏),
        // 使文件名与文件夹名称左对齐、缩进一致;列尾 margin 由 CSS 清零,收紧图标与名称间距。
        // SVG 居中由 .ws-tree.ant-tree .ant-tree-switcher 的 display:flex 负责,SVG 自身保持默认 block。
        switcherIcon={({ isLeaf, expanded }) => {
          if (isLeaf) return null
          return expanded ? (
            <ChevronDownIcon size={12} style={{ color: token.colorTextTertiary }} />
          ) : (
            <ChevronRightIcon size={12} style={{ color: token.colorTextTertiary }} />
          )
        }}
        style={{ fontSize: token.fontSizeSM }}
      />
    </div>
  )
}

/**
 * 单行树节点主体：每行独立持有 open(菜单开关)/长按状态，右键菜单用 antd Dropdown(trigger=contextMenu) 实现。
 *
 * 背景：此前右键/长按菜单是组件级单个共享 actionsRef(ListRowActions)，所有 titleRender 行
 * 都挂同一个 ref，React 会让它最终指向最后一个挂载的行 → 无论右键哪一行，菜单都绑定到
 * 目录最后一个文件。改为每行独立组件后，动作项命中当前行 target，右键菜单由 antd 管理(定位/关闭/键盘)。
 */
function TreeNodeRow({
  node,
  target,
  menuItems,
  open,
  onOpenChange,
  isMobile,
  isMultiSelect,
  isSelected,
  metaMode,
  onToggleDirectory,
  onOpenFile,
  onToggleSelectedPath,
}: {
  node: WorkspaceExplorerNode
  target: WorkspaceExplorerContextTarget
  menuItems: MenuProps['items']
  /** 是否打开右键菜单(由父级统一控制：同一时刻只有一个右键菜单打开)。 */
  open: boolean
  onOpenChange: (next: boolean) => void
  isMobile: boolean
  isMultiSelect: boolean
  isSelected: boolean
  metaMode?: 'size' | 'modified'
  onToggleDirectory: (path: string) => void
  onOpenFile: (target: WorkspaceExplorerOpenTarget) => void
  onToggleSelectedPath?: (path: string) => void
}) {
  const { token } = useToken()
  const isDirectory = node.type === 'directory'
  const metaText = metaMode === 'size' ? formatBytes(node.size) : formatMtime(node.mtimeMs)
  // 移动端长按弹出右键菜单：直接控制受控 Dropdown 的 open；桌面端处理器为空操作。
  const { wasLongPressed, ...longPressHandlers } = useLongPress({
    isMobile,
    delay: 500,
    onLongPress: () => onOpenChange(true),
  })

  const content = (
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
      onClick={() => {
        if (wasLongPressed()) return
        // 不阻止冒泡:让 antd 的 onSelect 正常触发(单击选中高亮)。
        // 目录展开/收起改由双击(onDoubleClick)或点击箭头(onExpand)触发。
      }}
      onMouseDown={(e) => {
        // 阻止浏览器「双击选中文字」的默认行为:双击打开文件/展开目录时,文件名不应被高亮选中。
        // e.detail 为本次按压的连击计数,>1 表示双击/三击的第二次及以后按压;preventDefault 只拦默认选字,不影响 click/dblclick 触发。
        if (e.detail > 1) e.preventDefault()
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
      onPointerDown={longPressHandlers.onPointerDown}
      onPointerMove={longPressHandlers.onPointerMove}
      onPointerUp={longPressHandlers.onPointerUp}
      onPointerLeave={longPressHandlers.onPointerLeave}
      onContextMenu={longPressHandlers.onContextMenu}
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
      {/* 名称前置类型图标:文件行按扩展名渲染语言徽章(未知退化为中性文件轮廓),
          目录行用极简描边文件夹,与 switcher 箭头列错开一级缩进。 */}
      <span style={{ flexShrink: 0, display: 'inline-flex', alignItems: 'center' }}>
        {isDirectory ? (
          <FolderIcon size={15} style={{ color: 'var(--text-muted)' }} />
        ) : (
          <FileTypeIcon fileName={node.name} size={15} />
        )}
      </span>
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
    </div>
  )

  if (!menuItems?.length) {
    return content
  }
  return (
    <Dropdown
      open={open}
      onOpenChange={onOpenChange}
      trigger={['contextMenu']}
      menu={{ items: menuItems }}
    >
      {content}
    </Dropdown>
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
