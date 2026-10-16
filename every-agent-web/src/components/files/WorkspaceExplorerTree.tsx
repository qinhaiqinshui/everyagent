import React from 'react'
import { Dropdown, Tree, Tooltip, theme } from 'antd'
import type { MenuProps, TreeDataNode } from 'antd'
import type { AlignType } from '@rc-component/trigger'
import { ChevronDownIcon, ChevronRightIcon, FolderIcon } from '../shared/AppGlyphs'
import { FileTypeIcon } from '../shared/FileTypeGlyphs'
import type { ListRowActionItem } from '../shared/ui/ListRowActions'
import { MenuList } from '../shared/ui/Menu'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import { useLongPress } from '@/hooks/useLongPress'
import type {
  WorkspaceExplorerContextTarget,
  WorkspaceExplorerNode,
  WorkspaceExplorerOpenTarget,
} from '@/types/workspaceExplorer'

const { useToken } = theme

/** 菜单项行高(antd Menu 默认 min-height) + 容器上下 padding,用于预估菜单高度。 */
const MENU_ITEM_HEIGHT = 32
const MENU_PADDING = 8
/** 菜单宽度估算值(antd 菜单按内容撑宽,无固定值),用于水平方向钳制避免溢出视口右缘。 */
const MENU_WIDTH_ESTIMATE = 200

/** 拖动框选(橡皮筋)触发的最小位移(px):超过此距离视为拖动而非单击。 */
const RUBBER_BAND_THRESHOLD = 5

export default function WorkspaceExplorerTree({
  workspaceRoot,
  nodes,
  expandedPaths,
  locatePath,
  locateRequestedAt,
  onLocateApplied,
  onToggleDirectory,
  onOpenFile,
  onRequestDelete,
  getActionItems,
  metaMode,
  selectedPaths,
  onMultiSelectChange,
}: {
  /** 所属工作区根(上下文菜单 target 需携带,命令落对应工作区)。 */
  workspaceRoot: string
  nodes: WorkspaceExplorerNode[]
  expandedPaths: Set<string>
  locatePath?: string | null
  locateRequestedAt?: number | null
  onLocateApplied?: () => void
  onToggleDirectory: (path: string) => void
  onOpenFile: (target: WorkspaceExplorerOpenTarget) => void
  onRequestDelete?: (target: WorkspaceExplorerContextTarget) => void
  getActionItems?: (target: WorkspaceExplorerContextTarget) => ListRowActionItem[]
  /** 行尾元信息显示模式：文件大小、最后编辑时间，或 none(默认隐藏)。 */
  metaMode?: 'size' | 'modified' | 'none'
  /** 当前选中集合(保持点击顺序)；行内自绘高亮是唯一高亮来源，不再走 antd 内置选中通道。 */
  selectedPaths?: Set<string>
  /** 选中集合更新入口(Ctrl/Shift 点击与空白处拖动框选统一走此回调)。 */
  onMultiSelectChange?: (paths: Set<string>) => void
}) {
  const isMobile = useResponsiveViewport().isMobile
  const { token } = useToken()
  const treeRef = React.useRef<{
    scrollTo: (opts: { key: React.Key; offset?: number }) => void
  } | null>(null)
  const containerRef = React.useRef<HTMLDivElement | null>(null)
  // 当前打开右键菜单的行路径：同一时刻只允许一个右键菜单打开(原生右键习惯)，由各 TreeNodeRow 受控。
  const [openMenuPath, setOpenMenuPath] = React.useState<string | null>(null)

  // 多选：锚点路径(Shift+Click 范围选择的起点)。
  const anchorPathRef = React.useRef<string | null>(null)
  // 多选：拖动框选矩形状态(null = 未在框选)。
  const [rubberBand, setRubberBand] = React.useState<{
    x1: number; y1: number; x2: number; y2: number
  } | null>(null)
  // 多选：框选结束后抑制下一次 click(防止框选误触发单选)。
  const suppressNextClickRef = React.useRef(false)

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
  }, [nodes])

  // 定位：当 locatePath 变化且已提供时间戳时，滚动到对应行
  React.useEffect(() => {
    if (!locatePath || locateRequestedAt == null) return
    const id = window.setTimeout(() => {
      treeRef.current?.scrollTo({ key: locatePath, offset: 80 })
      onLocateApplied?.()
    }, 0)
    return () => window.clearTimeout(id)
  }, [locatePath, locateRequestedAt, onLocateApplied])

  /** 获取容器内所有可见树节点路径(按 DOM 顺序；折叠子树不在 DOM 中，天然被排除)。 */
  const getVisiblePaths = React.useCallback((): string[] => {
    const container = containerRef.current
    if (!container) return []
    const paths: string[] = []
    container.querySelectorAll('.ant-tree-treenode').forEach((el) => {
      const content = el.querySelector('[data-key]')
      const path = content?.getAttribute('data-key')
      if (path) paths.push(path)
    })
    return paths
  }, [])

  /** Shift+Click 范围选择：返回锚点到目标之间所有可见节点路径。 */
  const computeRangeSelection = React.useCallback((anchor: string, target: string): Set<string> => {
    const visiblePaths = getVisiblePaths()
    const anchorIdx = visiblePaths.indexOf(anchor)
    const targetIdx = visiblePaths.indexOf(target)
    if (anchorIdx === -1 || targetIdx === -1) return new Set([target])
    const start = Math.min(anchorIdx, targetIdx)
    const end = Math.max(anchorIdx, targetIdx)
    return new Set(visiblePaths.slice(start, end + 1))
  }, [getVisiblePaths])

  /** 更新选中集合:处理节点点击(Ctrl/Shift/普通)。 */
  const handleMultiSelectClick = React.useCallback((path: string, e: React.MouseEvent) => {
    if (!onMultiSelectChange) return
    const isCtrl = e.ctrlKey || e.metaKey
    const isShift = e.shiftKey

    if (isShift && anchorPathRef.current) {
      // Shift+Click: 范围选择
      const rangePaths = computeRangeSelection(anchorPathRef.current, path)
      if (isCtrl) {
        // Ctrl+Shift+Click: 追加范围到现有选择
        const next = new Set([...(selectedPaths ?? []), ...rangePaths])
        onMultiSelectChange(next)
      } else {
        onMultiSelectChange(rangePaths)
      }
    } else if (isCtrl) {
      // Ctrl+Click: 切换单个路径
      const next = new Set(selectedPaths ?? [])
      if (next.has(path)) next.delete(path)
      else next.add(path)
      onMultiSelectChange(next)
      anchorPathRef.current = path
    } else {
      // 普通点击: 仅选中该路径
      onMultiSelectChange(new Set([path]))
      anchorPathRef.current = path
    }
  }, [onMultiSelectChange, selectedPaths, computeRangeSelection])

  /** 右键/长按归一选中:被操作行若不在当前选中集合内,选中集合收缩为该行;已在集合内则保持多选不变。 */
  const normalizeSelectionForPath = React.useCallback((path: string) => {
    if (!onMultiSelectChange) return
    if (selectedPaths?.has(path)) return
    // 锚点同步指向该行,避免下一次 Shift 点击从旧锚点起算。
    anchorPathRef.current = path
    onMultiSelectChange(new Set([path]))
  }, [onMultiSelectChange, selectedPaths])

  /** 容器 mousedown：开始潜在的拖动框选(常态可用,无需任何开关)。 */
  const handleContainerMouseDown = React.useCallback((e: React.MouseEvent) => {
    if (!onMultiSelectChange) return
    if (e.button !== 0) return // 只响应左键
    const target = e.target as HTMLElement
    // 点击展开/收起箭头不触发框选
    if (target.closest('.ant-tree-switcher')) return

    const container = containerRef.current
    if (!container) return

    const startClientX = e.clientX
    const startClientY = e.clientY
    let isDragging = false
    const preSelection = new Set(selectedPaths ?? [])

    const handleMouseMove = (moveE: MouseEvent) => {
      const dx = moveE.clientX - startClientX
      const dy = moveE.clientY - startClientY

      if (!isDragging && (Math.abs(dx) > RUBBER_BAND_THRESHOLD || Math.abs(dy) > RUBBER_BAND_THRESHOLD)) {
        isDragging = true
      }
      if (!isDragging) return

      // 阻止默认文字选择行为
      moveE.preventDefault()

      const rect = container.getBoundingClientRect()
      const x1 = startClientX - rect.left
      const y1 = startClientY - rect.top
      const x2 = moveE.clientX - rect.left
      const y2 = moveE.clientY - rect.top

      setRubberBand({ x1, y1, x2, y2 })

      // 计算与橡皮筋矩形相交的树节点(用视口坐标)
      const rbLeft = Math.min(moveE.clientX, startClientX)
      const rbRight = Math.max(moveE.clientX, startClientX)
      const rbTop = Math.min(moveE.clientY, startClientY)
      const rbBottom = Math.max(moveE.clientY, startClientY)

      const intersected: string[] = []
      container.querySelectorAll('.ant-tree-treenode').forEach((el) => {
        const content = el.querySelector('[data-key]')
        const path = content?.getAttribute('data-key')
        if (!path) return
        const elRect = el.getBoundingClientRect()
        if (elRect.left < rbRight && elRect.right > rbLeft && elRect.top < rbBottom && elRect.bottom > rbTop) {
          intersected.push(path)
        }
      })

      const isAdditive = moveE.ctrlKey || moveE.metaKey
      const next = isAdditive
        ? new Set([...preSelection, ...intersected])
        : new Set(intersected)
      onMultiSelectChange(next)
    }

    const handleMouseUp = () => {
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
      if (isDragging) {
        // 框选结束后抑制 click 事件,防止误触发单选
        suppressNextClickRef.current = true
        setTimeout(() => { suppressNextClickRef.current = false }, 0)
      }
      setRubberBand(null)
    }

    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
  }, [onMultiSelectChange, selectedPaths])

  if (nodes.length === 0) {
    return (
      <div style={{ padding: token.paddingSM, color: token.colorTextDisabled, fontSize: token.fontSizeSM }}>
        当前没有可显示的工作区文件。
      </div>
    )
  }

  const titleRender = (dataNode: TreeDataNode) => {
    const node = (dataNode as TreeDataNode & { dataRef: WorkspaceExplorerNode }).dataRef
    const isMultiSelect = !!onMultiSelectChange
    const isSelected = isMultiSelect && !!selectedPaths?.has(node.path)
    const target: WorkspaceExplorerContextTarget = {
      workspaceRoot,
      path: node.path,
      name: node.name,
      type: node.type,
      openTarget: node.openTarget,
      size: node.size,
      mtimeMs: node.mtimeMs,
      createdTs: node.createdTs,
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

    return (
      <TreeNodeRow
        node={node}
        target={target}
        items={allItems}
        open={openMenuPath === node.path}
        onOpenChange={(next) => setOpenMenuPath(next ? node.path : null)}
        isMobile={isMobile}
        isMultiSelect={isMultiSelect}
        isSelected={isSelected}
        metaMode={metaMode}
        suppressNextClickRef={suppressNextClickRef}
        onToggleDirectory={onToggleDirectory}
        onOpenFile={onOpenFile}
        onMultiSelectClick={handleMultiSelectClick}
        onContextMenuSelect={normalizeSelectionForPath}
      />
    )
  }

  // 橡皮筋矩形渲染参数
  const rbRect = rubberBand ? {
    left: Math.min(rubberBand.x1, rubberBand.x2),
    top: Math.min(rubberBand.y1, rubberBand.y2),
    width: Math.abs(rubberBand.x2 - rubberBand.x1),
    height: Math.abs(rubberBand.y2 - rubberBand.y1),
  } : null

  return (
    <div
      ref={containerRef}
      style={{
        padding: `0 ${token.paddingXXS}px`,
        position: 'relative',
        // 框选拖动期间禁止浏览器文字选中:空白处按下拖动只产生橡皮筋选区,不产生文字选区。
        userSelect: 'none',
        WebkitUserSelect: 'none',
      }}
      onMouseDown={handleContainerMouseDown}
      onClickCapture={(e) => {
        // 框选结束后抑制 click 事件,防止误触发单选
        if (suppressNextClickRef.current) {
          e.stopPropagation()
          e.preventDefault()
          suppressNextClickRef.current = false
        }
      }}
    >
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
        // 选中态不交给 antd:行内自绘高亮是唯一高亮来源,避免两套选中通道互相漂移。
        selectable={false}
        className="ws-tree"
        styles={{
          itemTitle: {
            display: 'flex',
            flex: 1,
            minWidth: 0,
            alignItems: 'center',
          },
        }}
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
      {rbRect && (
        <div
          style={{
            position: 'absolute',
            left: rbRect.left,
            top: rbRect.top,
            width: rbRect.width,
            height: rbRect.height,
            background: 'color-mix(in srgb, var(--accent-blue) 10%, transparent)',
            border: '1px solid color-mix(in srgb, var(--accent-blue) 35%, transparent)',
            borderRadius: 2,
            pointerEvents: 'none',
            zIndex: 1,
          }}
        />
      )}
    </div>
  )
}

/**
 * 单行树节点主体：每行独立持有 open(菜单开关)/长按状态。
 *
 * 桌面端右键菜单用 antd Dropdown(trigger=contextMenu) 实现，由 rc-trigger 用自己的
 * onContextMenu 拿到鼠标坐标做对齐，再配合本组件的 offset/maxHeight 计算保证不溢出。
 * 移动端长按菜单改走 MenuList（portal 到 body、position:fixed、实测尺寸后夹紧视口内），
 * 不依赖 rc-trigger 的对齐，因为长按是定时器触发、并不经过 rc-trigger 的 contextmenu 处理。
 *
 * 背景：此前右键/长按菜单是组件级单个共享 actionsRef(ListRowActions)，所有 titleRender 行
 * 都挂同一个 ref，React 会让它最终指向最后一个挂载的行 → 无论右键哪一行，菜单都绑定到
 * 目录最后一个文件。改为每行独立组件后，动作项命中当前行 target。
 */
function TreeNodeRow({
  node,
  target,
  items,
  open,
  onOpenChange,
  isMobile,
  isMultiSelect,
  isSelected,
  metaMode,
  suppressNextClickRef,
  onToggleDirectory,
  onOpenFile,
  onMultiSelectClick,
  onContextMenuSelect,
}: {
  node: WorkspaceExplorerNode
  target: WorkspaceExplorerContextTarget
  /** 行级动作项（含删除等）。桌面端映射为 antd Menu items；移动端直接交给 MenuList。 */
  items: ListRowActionItem[]
  /** 是否打开右键菜单(由父级统一控制：同一时刻只有一个右键菜单打开)。 */
  open: boolean
  onOpenChange: (next: boolean) => void
  isMobile: boolean
  isMultiSelect: boolean
  isSelected: boolean
  metaMode?: 'size' | 'modified' | 'none'
  /** 框选结束后抑制下一次 click 的共享 ref。 */
  suppressNextClickRef?: React.MutableRefObject<boolean>
  onToggleDirectory: (path: string) => void
  onOpenFile: (target: WorkspaceExplorerOpenTarget) => void
  /** 选中集合的节点点击处理(Ctrl/Shift/普通)。 */
  onMultiSelectClick?: (path: string, e: React.MouseEvent) => void
  /** 右键/长按打开菜单前归一选中集合:被操作行不在选中集合内时收缩为该行,已在集合内则保持多选不变。 */
  onContextMenuSelect?: (path: string) => void
}) {
  const { token } = useToken()
  const isDirectory = node.type === 'directory'
  // 默认(none)不显示行尾元信息；「显示大小」/「显示最后编辑时间」才各自启用对应文本。
  const metaText =
    metaMode === 'size'
      ? formatBytes(node.size)
      : metaMode === 'modified'
        ? formatMtime(node.mtimeMs)
        : ''
  // 右键菜单锚点:记录鼠标右键坐标(桌面)或长按触摸点坐标(移动端)。
  // 移动端这里作为 MenuList 的 anchorPoint 使用。
  const [mousePos, setMousePos] = React.useState<{ x: number; y: number } | null>(null)
  // 移动端长按的行元素(MenuList 的 anchor,用于「点在触发元素上不关闭」判定)。
  const [anchorEl, setAnchorEl] = React.useState<HTMLElement | null>(null)

  // 移动端长按弹出右键菜单：先归一选中集合，再记录触摸点与行元素并打开菜单；桌面端处理器为空操作。
  // onLongPress 为每次渲染新建的内联回调，useLongPress 的 onPointerDown 依赖它并随之重建，
  // 故长按定时器触发时读到的是最近一次渲染的 onContextMenuSelect(其依赖含选中集合，始终为最新值)。
  const { wasLongPressed, ...longPressHandlers } = useLongPress({
    isMobile,
    delay: 500,
    onLongPress: (target, point) => {
      onContextMenuSelect?.(node.path)
      setMousePos(point)
      setAnchorEl(target)
      onOpenChange(true)
    },
  })

  // 监听 contextmenu 事件记录鼠标坐标(在 Dropdown 的 onOpenChange 之前触发)；
  // 归一选中须在记录坐标之前完成:多选区之外右键等于重新单选，多选区之内右键保持集合不变。
  const handleContextMenu = React.useCallback((e: React.MouseEvent) => {
    onContextMenuSelect?.(node.path)
    setMousePos({ x: e.clientX, y: e.clientY })
    longPressHandlers.onContextMenu(e)
  }, [longPressHandlers, node.path, onContextMenuSelect])

  // 桌面端:根据鼠标位置和预估菜单高度计算 align offset 和 maxHeight。
  // autoAdjustOverflow=false 后,rc-trigger 不做翻转/shift,菜单始终从鼠标点向下展开(bottomLeft)。
  // 我们通过 align.offset 上移菜单确保不溢出底部视口;
  // 上下都不够时通过 styles.root.maxHeight 出滚动条。
  const itemCount = items?.length ?? 0
  const menuEstimatedHeight = itemCount * MENU_ITEM_HEIGHT + MENU_PADDING

  const menuAlign = React.useMemo<AlignType | undefined>(() => {
    if (!mousePos) return undefined
    const viewportH = window.innerHeight
    const viewportW = window.innerWidth
    const EDGE = 8
    const GAP = 4
    const spaceDown = viewportH - mousePos.y - EDGE
    const spaceUp = mousePos.y - EDGE
    // 水平方向:默认向右偏移 6px;若右侧空间放不下估算的菜单宽度(移动端视口窄),
    // 向左钳制使菜单右缘不超出视口;菜单宽于视口时贴左缘,避免左右都溢出。
    const menuWidth = Math.min(MENU_WIDTH_ESTIMATE, viewportW - EDGE * 2)
    const minOffsetX = EDGE - mousePos.x
    const maxOffsetX = viewportW - EDGE - mousePos.x - menuWidth
    const offsetX = Math.max(minOffsetX, Math.min(6, Math.max(minOffsetX, maxOffsetX)))
    if (menuEstimatedHeight <= spaceDown) {
      // 下方足够:正常向下展开(菜单顶部距鼠标点 GAP)
      return { offset: [offsetX, GAP] }
    }
    if (menuEstimatedHeight <= spaceUp) {
      // 上方足够:向上偏移使菜单整体在鼠标上方
      return { offset: [offsetX, -(menuEstimatedHeight + GAP)] }
    }
    // 上下都不够:选较大一侧,偏移到不溢出
    if (spaceDown >= spaceUp) {
      // 下方较多:正常向下展开,maxHeight 限制高度出滚动条
      return { offset: [offsetX, GAP] }
    }
    // 上方较多:偏移到视口顶部,maxHeight 限制高度
    return { offset: [offsetX, -(mousePos.y - EDGE)] }
  }, [mousePos, menuEstimatedHeight])

  const menuRootStyle = React.useMemo<React.CSSProperties | undefined>(() => {
    if (!mousePos) return undefined
    const viewportH = window.innerHeight
    const EDGE = 8
    const GAP = 4
    const spaceDown = viewportH - mousePos.y - EDGE - GAP
    const spaceUp = mousePos.y - EDGE - GAP
    if (menuEstimatedHeight <= spaceDown || menuEstimatedHeight <= spaceUp) {
      // 能完全展开,不需要 maxHeight
      return undefined
    }
    // 上下都不够:限制为较大一侧的空间
    return { maxHeight: Math.max(spaceDown, spaceUp), overflowY: 'auto' }
  }, [mousePos, menuEstimatedHeight])

  const content = (
    <div
      data-key={node.path}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: token.paddingXXS,
        width: '100%',
        cursor: 'default',
        // 节点名称一律禁止浏览器文本选中:文件名的复制走「属性」弹窗与右键菜单,不靠选词,
        // 同时避免长按触发系统选词/放大镜与自定义长按右键菜单冲突。
        userSelect: 'none',
        WebkitUserSelect: 'none',
        // iOS Safari:长按不弹系统「拷贝/全选」呼出菜单。
        WebkitTouchCallout: 'none',
        // 选中项:VSCode 风格高亮背景(唯一高亮来源;antd 侧 selectable=false,不再产生内置 selected 态)
        ...(isSelected ? {
          background: 'var(--bg-active)',
          borderRadius: 'var(--radius-sm)',
        } : null),
      }}
      onClick={(e) => {
        if (wasLongPressed()) return
        if (suppressNextClickRef?.current) {
          suppressNextClickRef.current = false
          return
        }
        if (isMultiSelect && onMultiSelectClick) {
          e.stopPropagation()
          onMultiSelectClick(node.path, e)
          return
        }
        // 调用方未提供选中回调时不做任何副作用:
        // 目录展开/收起由双击(onDoubleClick)或点击箭头(onExpand)触发。
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
      onContextMenu={handleContextMenu}
    >
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

  if (!items?.length) {
    return content
  }

  // 移动端长按菜单走 MenuList:portal 到 document.body + position:fixed + 实测尺寸后
  // 夹紧视口内,不依赖 rc-trigger 对齐。长按是定时器触发、不经过 rc-trigger 的
  // contextmenu 处理,因此不能复用桌面端 Dropdown 的对齐路径。
  if (isMobile) {
    return (
      <>
        {content}
        {open && anchorEl ? (
          <MenuList
            items={items}
            anchor={anchorEl}
            anchorPoint={mousePos}
            anchorPointMode="center"
            onClose={() => onOpenChange(false)}
            title="更多操作"
          />
        ) : null}
      </>
    )
  }

  // 桌面端右键:映射为 antd Menu items,交给 Dropdown(trigger=contextMenu) 定位。
  const antdMenuItems: MenuProps['items'] = items.map((item) => ({
    key: item.key,
    label: item.label,
    icon: item.icon,
    danger: item.danger,
    disabled: item.disabled,
    onClick: () => item.onSelect?.(),
  }))

  return (
    <Dropdown
      open={open}
      onOpenChange={onOpenChange}
      trigger={['contextMenu']}
      menu={{ items: antdMenuItems }}
      // 完全禁用 antd 自动翻转/位置调整。
      // rc-trigger 对齐逻辑(useAlign)中,adjustY=false → needAdjustY=false → 不翻转;
      // shiftY=undefined → 不 shift。位置完全由 align.offset 控制,行为确定性。
      // 注意:autoAdjustOverflow={{ adjustX:0, adjustY:0 }} 是错误的写法,
      // 因为 useAlign 的 supportAdjust(val) 对 0 返回 0>=0=true(启用翻转),
      // 必须用 false 才能真正禁用。
      autoAdjustOverflow={false}
      align={menuAlign}
      styles={menuRootStyle ? { root: menuRootStyle } : undefined}
      rootClassName="ws-context-menu"
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
