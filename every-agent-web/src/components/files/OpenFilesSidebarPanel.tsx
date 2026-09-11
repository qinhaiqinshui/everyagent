import React from 'react'
import { useWorkspaceShell } from '../app/WorkspaceShellContext'
import { useAppUi } from '@/components/app/AppUiContext'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { WORKSPACE_EXPLORER_ROOT_LABEL, workspaceExplorerQueryService } from '@/query/workspaceExplorerQueryService'
import { findExplorerNode, upsertExplorerChildren } from '@/query/workspaceExplorerTreeUtils'
import { workspaceRegistry, type WorkspaceEntry } from '@/hub/workspaceRegistry'
import { antdConfirm } from '@/utils/appAntdBridge'
import { toBusinessAbsolutePath } from '@/platform/fs/pathUtils'
import { workspaceExplorerCommandService } from '@/services/workspaceExplorerCommandService'
import ConfirmDialog from '../shared/ConfirmDialog'
import MoreActionsButton, { type MoreActionItem } from '../shared/MoreActionsButton'
import { DownloadIcon, FilePlusIcon, FileTextIcon, FolderArrowOutIcon, FolderPlusIcon, MagnifierCheckIcon, UploadIcon, ChevronDownIcon, CheckIcon } from '../shared/AppGlyphs'
import SidebarScrollArea from '../shared/SidebarScrollArea'
import WorkspaceExplorerTree from './WorkspaceExplorerTree'
import WorkspacePathPicker from '@/components/shared/ui/WorkspacePathPicker'
import { pickFiles } from '@/utils/filePicker'
import { Button, TextInput } from '@/components/shared/ui'
import type { ListRowActionItem } from '@/components/shared/ui/ListRowActions'
import type {
  WorkspaceExplorerContextTarget,
  WorkspaceExplorerNode,
  WorkspaceExplorerOpenTarget,
} from '@/types/workspaceExplorer'

/**
 * 打开的文件侧栏面板(多工作区分组版,架构 §5.9/D16)。
 * 面板只负责:跟踪工作区注册表 → 按注册工作区逐组渲染资源管理器;
 * 每组(WorkspaceGroupPanel)独立持有树/搜索/对话框状态,文件操作全部落本组工作区。
 */
export default function OpenFilesSidebarPanel() {
  const { workspaceFileLocateRequest } = useWorkspaceShell()
  const [registry, setRegistry] = React.useState(workspaceRegistry.current)

  React.useEffect(() => workspaceRegistry.subscribe(setRegistry), [])

  const entries = registry?.workspaces ?? []
  // 定位请求按工作区分发;请求未带工作区时落首个分组(单工作区场景行为不变)。
  const locateFallbackRoot = entries[0]?.root ?? null
  const locateRoot = workspaceFileLocateRequest?.workspaceRoot ?? locateFallbackRoot

  return (
    <div style={panelStyle}>
      <SidebarScrollArea style={bodyStyle}>
        <div style={sectionStyle}>
          {registry ? null : <div style={emptyStyle}>连接后可查看工作区注册表。</div>}
          {entries.map((entry) => (
            <WorkspaceGroupPanel
              key={entry.root}
              entry={entry}
              isDefault={entry.root === registry?.defaultRoot}
              locateRequest={locateRoot === entry.root ? workspaceFileLocateRequest : null}
            />
          ))}
        </div>
      </SidebarScrollArea>
    </div>
  )
}

/** 单个工作区分组:独立的资源树 + 最近编辑 + 搜索 + 全部文件操作(均落本工作区)。 */
function WorkspaceGroupPanel({
  entry,
  isDefault,
  locateRequest,
}: {
  entry: WorkspaceEntry
  isDefault: boolean
  locateRequest: { filePath: string; requestedAt: number } | null
}) {
  const workspaceRoot = entry.root
  const {
    workspaceFileTabs,
    activeWorkspaceTab,
    selectedFilePath,
    clearWorkspaceFileLocateRequest,
    closeGlobalFileTab,
    openGlobalFileTab,
    renameFileTabs,
    setActiveSidebarPanel,
  } = useWorkspaceShell()
  const { showToast } = useAppUi()
  const [treeNodes, setTreeNodes] = React.useState<WorkspaceExplorerNode[]>([])
  // 异步回调(懒加载/定位)需要读取最新树与展开态,用 ref 旁路闭包。
  const treeNodesRef = React.useRef(treeNodes)
  treeNodesRef.current = treeNodes
  const lastRevealedPathRef = React.useRef<string | null>(null)
  const [selectedExplorerPath, setSelectedExplorerPath] = React.useState<string | null>(null)
  const [expandedPaths, setExpandedPaths] = React.useState<Set<string>>(new Set([WORKSPACE_EXPLORER_ROOT_LABEL]))
  const expandedPathsRef = React.useRef(expandedPaths)
  expandedPathsRef.current = expandedPaths
  /** 文件变更事件合并刷新的暂存与定时器(一次 rename 会触发 delete+write+rename 多个事件,短窗合并只刷一次)。 */
  const pendingFileChangedRef = React.useRef<Array<{ filePath: string; oldFilePath?: string }>>([])
  const fileChangedFlushTimerRef = React.useRef<number | null>(null)
  const [deleteTarget, setDeleteTarget] = React.useState<WorkspaceExplorerContextTarget | null>(null)
  const [deleting, setDeleting] = React.useState(false)
  const [renameTarget, setRenameTarget] = React.useState<WorkspaceExplorerContextTarget | null>(null)
  const [renameValue, setRenameValue] = React.useState('')
  const [renaming, setRenaming] = React.useState(false)
  const [moveTarget, setMoveTarget] = React.useState<WorkspaceExplorerContextTarget | null>(null)
  const [moveTargetDir, setMoveTargetDir] = React.useState('')
  const [moving, setMoving] = React.useState(false)
  const [createMode, setCreateMode] = React.useState<'file' | 'directory' | null>(null)
  const [createParentPath, setCreateParentPath] = React.useState('')
  const [createParentName, setCreateParentName] = React.useState('')
  const [createValue, setCreateValue] = React.useState('')
  const [creating, setCreating] = React.useState(false)
  const [reloading, setReloading] = React.useState(false)
  const [treeError, setTreeError] = React.useState('')
  const [showInternalFiles, setShowInternalFiles] = React.useState(false)
  const [metaMode, setMetaMode] = React.useState<'size' | 'modified'>('size')
  /** 卡片折叠态:折叠时仅保留头部行(工作区名 + 更多操作),隐藏路径/搜索/文件树等内容。 */
  const [collapsed, setCollapsed] = React.useState(false)
  /** 多选模式:开启后树行前置复选框,支持批量删除/移动。 */
  const [multiSelectMode, setMultiSelectMode] = React.useState(false)
  /** 多选模式下的已选路径集合。 */
  const [selectedPaths, setSelectedPaths] = React.useState<Set<string>>(new Set())
  const [batchDeleteOpen, setBatchDeleteOpen] = React.useState(false)
  const [batchDeleting, setBatchDeleting] = React.useState(false)
  const [batchMoveOpen, setBatchMoveOpen] = React.useState(false)
  const [batchMoveDir, setBatchMoveDir] = React.useState('')
  const [batchMoving, setBatchMoving] = React.useState(false)

  const reloadTree = React.useCallback(async (includeInternalFiles: boolean, keepExpanded = false) => {
    setReloading(true)
    try {
      const { nodes: nextTree } = await workspaceExplorerQueryService.readTree(workspaceRoot, { includeInternalFiles })
      if (!keepExpanded) {
        setTreeNodes(nextTree)
      } else {
        // 保持展开:对每个展开目录按路径重拉(loadChildren 只依赖 path),不依赖旧树节点是否存在。
        // 这样重命名/移动目录后 expandedPaths 已迁移到新路径、旧树中找不到新节点时,也能正确填充子节点。
        let tree = nextTree
        for (const dirPath of expandedPathsRef.current) {
          if (dirPath === WORKSPACE_EXPLORER_ROOT_LABEL) continue
          const probeNode: WorkspaceExplorerNode = {
            path: dirPath,
            name: dirPath.slice(dirPath.lastIndexOf('/') + 1),
            type: 'directory',
            size: 0,
            mtimeMs: 0,
          }
          try {
            const children = await workspaceExplorerQueryService.loadChildren(workspaceRoot, probeNode, { includeInternalFiles })
            tree = upsertExplorerChildren(tree, dirPath, children)
          } catch {
            // 目录可能已被重命名/删除(旧路径失效):跳过该目录,不要整树失败置空。
            // 同时把失效 key 从展开集合移除,避免展开图标与实际状态错位。
            setExpandedPaths((prev) => {
              if (!prev.has(dirPath)) return prev
              const next = new Set(prev)
              next.delete(dirPath)
              return next
            })
          }
        }
        setTreeNodes(tree)
      }
      setTreeError('')
    } catch (treeLoadError) {
      setTreeNodes([])
      setTreeError(treeLoadError instanceof Error ? treeLoadError.message : String(treeLoadError))
    } finally {
      setReloading(false)
    }
  }, [workspaceRoot])

  /**
   * 合并窗口内到达的文件变更事件一次性刷新(架构事件风暴防护):
   * 一次 rename 实际会广播 delete(from) + write(to) + rename 三个事件,若逐条刷新会
   * 连续 reload 互相覆盖,既闪烁又可能把展开态带偏。短窗(80ms)合并后:
   * - 先迁移目录展开态(rename 场景,旧路径 key 会残留导致刷新失败/图标错位);
   * - 有根级事件 → 整树保持展开刷新一次;否则对受影响父目录增量刷新(去重)。
   */
  const flushFileChangedEvents = React.useCallback(async (items: Array<{ filePath: string; oldFilePath?: string }>) => {
    // 重命名/移动目录后,把展开集合中的旧路径前缀迁移为新路径,保持展开语义。
    for (const item of items) {
      if (item.oldFilePath && item.oldFilePath !== item.filePath) {
        expandedPathsRef.current = migrateExpandedPaths(expandedPathsRef.current, item.oldFilePath, item.filePath)
        setExpandedPaths(expandedPathsRef.current)
      }
    }
    const hasRootLevel = items.some((item) => {
      const parts = (item.filePath ?? '').replace(/^\/+/, '').split('/').filter(Boolean)
      return parts.length <= 1
    })
    const hasRename = items.some((item) => Boolean(item.oldFilePath) && item.oldFilePath !== item.filePath)
    // rename 低频且需要迁移展开态、给新目录补加载子节点:整树保持展开刷新一次到位;
    // 其余高频单文件变更(create/modify/delete)走增量刷新,避免大项目全量重拉。
    if (hasRename || hasRootLevel) {
      await reloadTree(showInternalFiles, true)
      return
    }
    const dirPaths = new Set<string>()
    for (const item of items) {
      const parts = (item.filePath ?? '').replace(/^\/+/, '').split('/').filter(Boolean)
      if (parts.length <= 1) continue
      dirPaths.add(`/${parts.slice(0, -1).join('/')}`)
    }
    for (const dirPath of dirPaths) {
      const dirNode = findExplorerNode(treeNodesRef.current, dirPath)
      if (!dirNode || dirNode.type !== 'directory' || !dirNode.loaded) continue
      try {
        const children = await workspaceExplorerQueryService.loadChildren(workspaceRoot, dirNode, { includeInternalFiles: showInternalFiles })
        setTreeNodes((prev) => upsertExplorerChildren(prev, dirPath, children))
      } catch {
        // 刷新失败静默:下次展开/整树刷新会带回最新状态
      }
    }
  }, [reloadTree, showInternalFiles, workspaceRoot])

  /** 登记文件变更事件并在短窗内合并刷新。 */
  const scheduleFileChanged = React.useCallback((payload: { filePath: string; oldFilePath?: string }) => {
    pendingFileChangedRef.current.push(payload)
    if (fileChangedFlushTimerRef.current !== null) return
    fileChangedFlushTimerRef.current = window.setTimeout(() => {
      fileChangedFlushTimerRef.current = null
      const items = pendingFileChangedRef.current
      pendingFileChangedRef.current = []
      void flushFileChangedEvents(items)
    }, 80)
  }, [flushFileChangedEvents])

  React.useEffect(() => {
    void reloadTree(showInternalFiles)
  }, [reloadTree, showInternalFiles])

  React.useEffect(() => {
    const unsubscribeWorkspaceReload = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_RELOAD_ALL_FILES_REQUESTED, () => {
      void reloadTree(showInternalFiles, true)
    })
    // 细粒度文件变更由底层网关统一广播(带 workspaceRoot):短窗合并后增量刷新受影响目录,保持展开状态。
    const unsubscribeFileChanged = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_FILE_CHANGED, (payload) => {
      if (payload.workspaceRoot !== workspaceRoot) return
      scheduleFileChanged({
        filePath: payload.filePath,
        oldFilePath: payload.operation === 'rename' ? payload.oldFilePath : undefined,
      })
    })
    // 侧边栏切到本面板时（onShow）主动刷新，保持展开状态。
    const unsubscribePanelShown = domainEventBus.subscribe(DOMAIN_EVENTS.SIDEBAR_PANEL_SHOWN, (payload) => {
      if (payload.panelId !== 'files') return
      void reloadTree(showInternalFiles, true)
    })

    return () => {
      unsubscribeWorkspaceReload()
      unsubscribeFileChanged()
      unsubscribePanelShown()
      if (fileChangedFlushTimerRef.current !== null) {
        window.clearTimeout(fileChangedFlushTimerRef.current)
        fileChangedFlushTimerRef.current = null
      }
    }
  }, [reloadTree, scheduleFileChanged, showInternalFiles, workspaceRoot])

  /**
   * 展开目录并懒加载其子节点(幂等:已加载/非目录/未在树中则跳过)。
   * 供展开切换、新建/移动/上传后的自动展开复用。
   */
  const expandDirChildren = React.useCallback((dirPath: string) => {
    const node = findExplorerNode(treeNodesRef.current, dirPath)
    if (!node || node.type !== 'directory' || node.loaded) return
    void workspaceExplorerQueryService.loadChildren(workspaceRoot, node, { includeInternalFiles: showInternalFiles })
      .then((children) => setTreeNodes((prev) => upsertExplorerChildren(prev, dirPath, children)))
      .catch(() => {
        // 加载失败静默:该目录保持未加载,再次点击展开会重试
      })
  }, [showInternalFiles, workspaceRoot])

  /**
   * 定位文件/目录:worker 沿路径逐段 stat 返回节点链(旁支零查找),
   * 沿链逐级加载目录子项并展开,选中目标。同路径去重(force 时强制重跑)。
   */
  const revealPath = React.useCallback(async (targetPath: string, force = false) => {
    if (!force && lastRevealedPathRef.current === targetPath) return
    lastRevealedPathRef.current = targetPath
    try {
      const chain = await workspaceExplorerQueryService.reveal(workspaceRoot, targetPath, { includeInternalFiles: showInternalFiles })
      let tree = treeNodesRef.current
      for (const node of chain) {
        if (node.type !== 'directory') continue
        const children = await workspaceExplorerQueryService.loadChildren(workspaceRoot, node, { includeInternalFiles: showInternalFiles })
        tree = upsertExplorerChildren(tree, node.path, children)
        setTreeNodes(tree)
      }
      setExpandedPaths((prev) => {
        const next = new Set(prev)
        next.add(WORKSPACE_EXPLORER_ROOT_LABEL)
        for (const node of chain) {
          if (node.type === 'directory') next.add(node.path)
        }
        return next
      })
      setSelectedExplorerPath(targetPath)
      setTreeError('')
    } catch (revealError) {
      // 定位失败:保留现有树,提示错误供排查
      setTreeError(revealError instanceof Error ? revealError.message : String(revealError))
    }
  }, [showInternalFiles, workspaceRoot])

  React.useEffect(() => {
    const activeFileTab = activeWorkspaceTab?.tabType === 'file' ? activeWorkspaceTab : null
    if (!activeFileTab || activeFileTab.workspaceRoot !== workspaceRoot) return
    if (!selectedFilePath) return
    setSelectedExplorerPath(selectedFilePath)
    void revealPath(selectedFilePath)
  }, [activeWorkspaceTab, selectedFilePath, workspaceRoot, revealPath])

  React.useEffect(() => {
    if (!locateRequest) return
    setSelectedExplorerPath(locateRequest.filePath)
    void revealPath(locateRequest.filePath, true)
  }, [locateRequest, revealPath])

  const handleOpenFile = React.useCallback((target: WorkspaceExplorerOpenTarget) => {
    openGlobalFileTab({ workspaceRoot: target.workspaceRoot, filePath: target.filePath }, target.options)
  }, [openGlobalFileTab])

  const handleRequestDelete = React.useCallback((target: WorkspaceExplorerContextTarget) => {
    setSelectedExplorerPath(target.path)
    setDeleteTarget(target)
  }, [])

  const handleDeleteFile = React.useCallback(async () => {
    if (!deleteTarget || deleting) return
    setDeleting(true)
    try {
      const tabsToClose = workspaceFileTabs.filter((fileTab) => (
        fileTab.workspaceRoot === workspaceRoot && isPathWithinTarget(fileTab.filePath, deleteTarget)
      ))

      await workspaceExplorerCommandService.deletePath(workspaceRoot, deleteTarget.path)

      for (const fileTab of tabsToClose) {
        closeGlobalFileTab(fileTab.id)
      }
      if (selectedExplorerPath === deleteTarget.path || (deleteTarget.type === 'directory' && selectedExplorerPath?.startsWith(`${deleteTarget.path}/`))) {
        setSelectedExplorerPath(null)
      }
      showToast(deleteTarget.type === 'directory' ? '已删除文件夹' : '已删除文件', 'success')
      setDeleteTarget(null)
    } catch (error) {
      showToast(error instanceof Error ? error.message : String(error), 'error')
    } finally {
      setDeleting(false)
    }
  }, [closeGlobalFileTab, deleteTarget, deleting, selectedExplorerPath, showToast, workspaceFileTabs, workspaceRoot])

  const handleRequestRenameTarget = React.useCallback((target: WorkspaceExplorerContextTarget) => {
    setRenameTarget(target)
    setRenameValue(target.name)
  }, [])

  const handleConfirmRename = React.useCallback(async () => {
    if (!renameTarget || renaming) return
    setRenaming(true)
    try {
      const nextPath = await workspaceExplorerCommandService.renamePath(workspaceRoot, renameTarget.path, renameValue)
      const nextBusinessPath = toBusinessAbsolutePath(nextPath)
      renameFileTabs(workspaceRoot, renameTarget.path, nextBusinessPath)
      // 重命名目录后迁移展开态(先同步 ref,让随后的整树刷新读到新路径),避免旧路径 key 残留。
      const nextExpanded = migrateExpandedPaths(expandedPathsRef.current, renameTarget.path, nextBusinessPath)
      expandedPathsRef.current = nextExpanded
      setExpandedPaths(nextExpanded)
      // 立即整树保持展开刷新:把磁盘上的新路径(重命名目录及其子层级)拉回树,
      // 避免残留旧节点、展开图标与子项错位(用户看到「折叠但图标展开」)。
      void reloadTree(showInternalFiles, true)
      showToast('已重命名', 'success')
      if (selectedExplorerPath === renameTarget.path) {
        setSelectedExplorerPath(nextBusinessPath)
      }
      setRenameTarget(null)
      setRenameValue('')
    } catch (error) {
      showToast(String(error), 'error')
    } finally {
      setRenaming(false)
    }
 }, [renameFileTabs, renameTarget, renameValue, renaming, reloadTree, selectedExplorerPath, showInternalFiles, showToast, workspaceRoot])

  const handleRequestMove = React.useCallback((target: WorkspaceExplorerContextTarget) => {
    setMoveTarget(target)
    // 默认目标取当前父目录，用户可从原位置出发浏览到新目录。
    const parentDir = target.path.includes('/') ? target.path.slice(0, target.path.lastIndexOf('/')) : ''
    setMoveTargetDir(parentDir)
  }, [])

  const handleConfirmMove = React.useCallback(async () => {
    if (!moveTarget || moving) return
    setMoving(true)
    try {
      const nextPath = await workspaceExplorerCommandService.movePath(workspaceRoot, moveTarget.path, moveTargetDir)
      const nextBusinessPath = toBusinessAbsolutePath(nextPath)
      renameFileTabs(workspaceRoot, moveTarget.path, nextBusinessPath)
      // 移动目录后迁移展开态,并把目标目录一并加入展开集合(同步 ref,让随后的整树刷新读到完整最新状态)。
      const nextExpanded = migrateExpandedPaths(expandedPathsRef.current, moveTarget.path, nextBusinessPath)
      const withTarget = moveTargetDir ? new Set(nextExpanded).add(moveTargetDir) : nextExpanded
      expandedPathsRef.current = withTarget
      setExpandedPaths(withTarget)
      // 展开目标目录，便于用户立即看到移动结果。
      expandDirChildren(moveTargetDir)
      // 立即整树保持展开刷新,把磁盘新路径拉回树。
      void reloadTree(showInternalFiles, true)
      // 选中项若处于被移动路径下，跟随前缀替换，避免选中态指向失效路径。
      if (selectedExplorerPath && (selectedExplorerPath === moveTarget.path || selectedExplorerPath.startsWith(`${moveTarget.path}/`))) {
        const nextSelected = selectedExplorerPath === moveTarget.path
          ? nextBusinessPath
          : `${nextBusinessPath}${selectedExplorerPath.slice(moveTarget.path.length)}`
        setSelectedExplorerPath(nextSelected)
      }
      showToast('已移动', 'success')
      setMoveTarget(null)
      setMoveTargetDir('')
    } catch (error) {
      showToast(error instanceof Error ? error.message : String(error), 'error')
    } finally {
      setMoving(false)
    }
  }, [moveTarget, moveTargetDir, moving, renameFileTabs, reloadTree, selectedExplorerPath, showInternalFiles, showToast, workspaceRoot, expandDirChildren])

  /** 切换多选模式;退出时清空已选集合。 */
  const toggleMultiSelectMode = React.useCallback(() => {
    setMultiSelectMode((current) => {
      const next = !current
      if (!next) {
        setSelectedPaths(new Set())
      }
      return next
    })
  }, [])

  /** 多选模式下切换单个路径选中态。 */
  const handleToggleSelectedPath = React.useCallback((path: string) => {
    setSelectedPaths((prev) => {
      const next = new Set(prev)
      if (next.has(path)) {
        next.delete(path)
      } else {
        next.add(path)
      }
      return next
    })
  }, [])

  const handleRequestBatchDelete = React.useCallback(() => {
    if (selectedPaths.size === 0) return
    setBatchDeleteOpen(true)
  }, [selectedPaths.size])

  /** 批量删除:对已选路径按前缀去重后逐个删除,并关闭受影响标签页。 */
  const handleBatchDelete = React.useCallback(async () => {
    if (batchDeleting || selectedPaths.size === 0) return
    setBatchDeleting(true)
    try {
      const paths = dedupeWorkspacePaths(Array.from(selectedPaths))
      // 收集受影响标签页(命中任意被删路径本身或其子树)。
      const tabsToClose = workspaceFileTabs.filter((fileTab) => (
        fileTab.workspaceRoot === workspaceRoot &&
        paths.some((path) => fileTab.filePath === path || fileTab.filePath.startsWith(`${path}/`))
      ))

      for (const path of paths) {
        await workspaceExplorerCommandService.deletePath(workspaceRoot, path)
      }
      for (const fileTab of tabsToClose) {
        closeGlobalFileTab(fileTab.id)
      }
      // 当前选中项若处于被删路径下,清除选中,避免指向失效路径。
      if (selectedExplorerPath && paths.some((path) => selectedExplorerPath === path || selectedExplorerPath.startsWith(`${path}/`))) {
        setSelectedExplorerPath(null)
      }
      showToast(`已删除 ${paths.length} 项`, 'success')
      setSelectedPaths(new Set())
      setBatchDeleteOpen(false)
      setMultiSelectMode(false)
    } catch (error) {
      showToast(error instanceof Error ? error.message : String(error), 'error')
    } finally {
      setBatchDeleting(false)
    }
  }, [batchDeleting, closeGlobalFileTab, selectedExplorerPath, selectedPaths, showToast, workspaceFileTabs, workspaceRoot])

  const handleRequestBatchMove = React.useCallback(() => {
    if (selectedPaths.size === 0) return
    setBatchMoveDir('')
    setBatchMoveOpen(true)
  }, [selectedPaths.size])

  /** 批量移动:对已选路径按前缀去重后移动到同一目标目录,并更新打开的标签页路径。 */
  const handleBatchMove = React.useCallback(async () => {
    if (batchMoving || selectedPaths.size === 0) return
    setBatchMoving(true)
    try {
      const paths = dedupeWorkspacePaths(Array.from(selectedPaths))
      const moved: Array<{ oldPath: string; newPath: string }> = []
      for (const path of paths) {
        const nextPath = await workspaceExplorerCommandService.movePath(workspaceRoot, path, batchMoveDir)
        moved.push({ oldPath: path, newPath: nextPath })
      }
      let nextExpanded = expandedPathsRef.current
      for (const { oldPath, newPath } of moved) {
        const newBusinessPath = toBusinessAbsolutePath(newPath)
        renameFileTabs(workspaceRoot, oldPath, newBusinessPath)
        // 移动目录后迁移展开态(逐个前缀迁移,同步 ref)。
        nextExpanded = migrateExpandedPaths(nextExpanded, oldPath, newBusinessPath)
      }
      // 展开目标目录,并把目标目录一并加入展开集合(同步 ref)。
      if (batchMoveDir) {
        nextExpanded = new Set(nextExpanded).add(batchMoveDir)
      }
      expandedPathsRef.current = nextExpanded
      setExpandedPaths(nextExpanded)
      expandDirChildren(batchMoveDir)
      // 立即整树保持展开刷新,把磁盘新路径拉回树。
      void reloadTree(showInternalFiles, true)
      // 选中项若在被移动路径下,跟随前缀替换,避免选中态指向失效路径。
      if (selectedExplorerPath) {
        const matched = moved.find(({ oldPath }) => selectedExplorerPath === oldPath || selectedExplorerPath.startsWith(`${oldPath}/`))
        if (matched) {
          const newBusinessPath = toBusinessAbsolutePath(matched.newPath)
          setSelectedExplorerPath(newBusinessPath + selectedExplorerPath.slice(matched.oldPath.length))
        }
      }
      showToast(`已移动 ${paths.length} 项`, 'success')
      setSelectedPaths(new Set())
      setBatchMoveOpen(false)
      setMultiSelectMode(false)
    } catch (error) {
      showToast(error instanceof Error ? error.message : String(error), 'error')
    } finally {
      setBatchMoving(false)
    }
  }, [batchMoveDir, batchMoving, expandDirChildren, reloadTree, renameFileTabs, selectedExplorerPath, selectedPaths, showInternalFiles, showToast, workspaceRoot])

  const handleRequestCreate = React.useCallback((mode: 'file' | 'directory', target: WorkspaceExplorerContextTarget) => {
    setCreateMode(mode)
    setCreateParentPath(target.path)
    setCreateParentName(target.name)
    setCreateValue('')
  }, [])

  const handleConfirmCreate = React.useCallback(async () => {
    if (!createMode || creating) return
    setCreating(true)
    try {
      // 命令服务返回工作区相对路径(无前导斜杠),选中/打开需转业务绝对形态。
      const createdPath = createMode === 'directory'
        ? await workspaceExplorerCommandService.createDirectory(workspaceRoot, createParentPath, createValue)
        : await workspaceExplorerCommandService.createFile(workspaceRoot, createParentPath, createValue)
      const createdBusinessPath = toBusinessAbsolutePath(createdPath)
      showToast(createMode === 'directory' ? '已创建目录' : '已创建文件', 'success')
      // 展开父目录并选中新建项;根目录的展开键是 WORKSPACE_EXPLORER_ROOT_LABEL('/')。
      expandDirChildren(createParentPath ? toBusinessAbsolutePath(createParentPath) : WORKSPACE_EXPLORER_ROOT_LABEL)
      setExpandedPaths((prev) => new Set(prev).add(createParentPath ? toBusinessAbsolutePath(createParentPath) : WORKSPACE_EXPLORER_ROOT_LABEL))
      setSelectedExplorerPath(createdBusinessPath)
      // 新建文件直接进入编辑(VSCode 习惯);目录只展开定位。
      if (createMode === 'file') {
        openGlobalFileTab({ workspaceRoot, filePath: createdBusinessPath }, { mode: 'readwrite' })
      }
      setCreateMode(null)
      setCreateValue('')
    } catch (error) {
      showToast(error instanceof Error ? error.message : String(error), 'error')
    } finally {
      setCreating(false)
    }
 }, [createMode, creating, createParentPath, createValue, expandDirChildren, openGlobalFileTab, showToast, workspaceRoot])

  const handleRequestDownload = React.useCallback(async (target: WorkspaceExplorerContextTarget) => {
    try {
      await workspaceExplorerCommandService.downloadPath(workspaceRoot, target.path)
      showToast(target.type === 'directory' ? '目录已打包下载' : '文件已下载', 'success')
    } catch (error) {
      showToast(error instanceof Error ? error.message : String(error), 'error')
    }
  }, [showToast, workspaceRoot])

  /**
   * 在系统文件管理器中显示(对标 VSCode Reveal in File Explorer):worker 在
   * 宿主机器上打开文件管理器并选中目标;远程访问场景窗口在 worker 所在电脑弹出。
   */
  const handleRequestRevealInOs = React.useCallback(async (target: WorkspaceExplorerContextTarget) => {
    try {
      await workspaceExplorerCommandService.revealInOsFileManager(workspaceRoot, target.path)
      showToast('已在系统文件管理器中定位', 'success')
    } catch (error) {
      showToast(error instanceof Error ? error.message : String(error), 'error')
    }
  }, [showToast, workspaceRoot])

  const handleRequestUpload = React.useCallback(async (mode: 'file' | 'directory', target: WorkspaceExplorerContextTarget) => {
    const files = await pickFiles(mode === 'directory')
    if (files.length === 0) return
    try {
      await workspaceExplorerCommandService.uploadFiles(workspaceRoot, target.path, files)
      expandDirChildren(target.path)
      setExpandedPaths((prev) => new Set(prev).add(target.path))
      showToast(mode === 'directory' ? `已上传 ${files.length} 个文件（含目录结构）` : `已上传 ${files.length} 个文件`, 'success')
    } catch (error) {
      showToast(error instanceof Error ? error.message : String(error), 'error')
    }
  }, [expandDirChildren, showToast, workspaceRoot])

  /**
   * 跳转到独立搜索面板:切面板(壳层上下文的统一跳转通道) + 发领域事件携带搜索
   * 范围(所属 worker + 工作区根 + 目标目录),搜索面板订阅事件预填 worker/工作区
   * 绑定与范围并聚焦输入框。
   */
  const handleRequestSearch = React.useCallback((target: WorkspaceExplorerContextTarget) => {
    setActiveSidebarPanel('search')
    domainEventBus.emit(DOMAIN_EVENTS.WORKSPACE_SEARCH_PANEL_REQUESTED, {
      // 本组件(WorkspaceGroupPanel)作用域持有 entry: WorkspaceEntry,workerId 直接
      // 取来源 worker(与 target.workspaceRoot 同源,无需反查注册表兜底)。
      workerId: entry.workerId,
      workspaceRoot: target.workspaceRoot,
      rootPath: target.path,
      label: target.name,
    })
  }, [entry.workerId, setActiveSidebarPanel])

  const handleRequestRootSearch = React.useCallback(() => {
    handleRequestSearch({ workspaceRoot, path: '', name: '工作区根目录', type: 'directory' })
  }, [handleRequestSearch, workspaceRoot])

  const handleRequestRefresh = React.useCallback(() => {
    void reloadTree(showInternalFiles)
  }, [reloadTree, showInternalFiles])

  const toggleInternalFiles = React.useCallback(() => {
    setShowInternalFiles((current) => !current)
  }, [])

  const getFileActionItems = React.useCallback((target: WorkspaceExplorerContextTarget): ListRowActionItem[] => {
    const items: ListRowActionItem[] = []
    // 文件行显式提供「打开」:双击之外的第二入口,移动端长按菜单里是唯一入口。
    const openTarget = target.openTarget
    if (target.type === 'file' && openTarget) {
      items.push({
        key: 'open',
        label: '打开',
        icon: <FileTextIcon size={13} />,
        onSelect: () => handleOpenFile(openTarget),
      })
    }
    if (target.type === 'directory') {
      items.push(
        {
          key: 'create-file',
          label: '新建文件',
          icon: <FilePlusIcon size={13} />,
          onSelect: () => handleRequestCreate('file', target),
        },
        {
          key: 'create-directory',
          label: '新建目录',
          icon: <FolderPlusIcon size={13} />,
          onSelect: () => handleRequestCreate('directory', target),
        },
        {
          key: 'upload-file',
          label: '上传文件',
          icon: <UploadIcon size={13} />,
          onSelect: () => handleRequestUpload('file', target),
        },
        {
          key: 'upload-directory',
          label: '上传目录',
          icon: <UploadIcon size={13} />,
          onSelect: () => handleRequestUpload('directory', target),
        },
      )
    }
   items.push({
     key: 'rename',
     label: '重命名',
     onSelect: () => handleRequestRenameTarget(target),
   })
    items.push({
      key: 'move',
      label: '移动到…',
      onSelect: () => handleRequestMove(target),
    })
    items.push({
      key: 'search',
      label: '搜索',
      icon: <MagnifierCheckIcon size={13} />,
      onSelect: () => handleRequestSearch(target),
    })
    items.push({
      key: 'download',
      label: '下载',
      icon: <DownloadIcon size={13} />,
      onSelect: () => handleRequestDownload(target),
    })
    items.push({
      key: 'reveal-in-os',
      label: '在系统文件管理器中显示',
      icon: <FolderArrowOutIcon size={13} />,
      onSelect: () => handleRequestRevealInOs(target),
    })
   return items
  }, [handleOpenFile, handleRequestCreate, handleRequestDownload, handleRequestMove, handleRequestRenameTarget, handleRequestRevealInOs, handleRequestSearch, handleRequestUpload])

  const rootMoreActionItems = React.useMemo<MoreActionItem[]>(() => [
    {
      key: 'refresh',
      label: '刷新',
      onSelect: handleRequestRefresh,
    },
    {
      key: 'toggle-multi-select',
      label: multiSelectMode ? '退出多选' : '多选',
      icon: <CheckIcon size={13} />,
      active: multiSelectMode,
      onSelect: toggleMultiSelectMode,
    },
    {
      key: 'create-file',
      label: '新建文件',
      icon: <FilePlusIcon size={13} />,
      onSelect: () => handleRequestCreate('file', { workspaceRoot, path: '', name: '工作区根目录', type: 'directory' }),
    },
    {
      key: 'create-directory',
      label: '新建目录',
      icon: <FolderPlusIcon size={13} />,
      onSelect: () => handleRequestCreate('directory', { workspaceRoot, path: '', name: '工作区根目录', type: 'directory' }),
    },
    {
      key: 'upload-file',
      label: '上传文件',
      icon: <UploadIcon size={13} />,
      onSelect: () => handleRequestUpload('file', { workspaceRoot, path: '', name: '工作区根目录', type: 'directory' }),
    },
    {
      key: 'upload-directory',
      label: '上传目录',
      icon: <UploadIcon size={13} />,
      onSelect: () => handleRequestUpload('directory', { workspaceRoot, path: '', name: '工作区根目录', type: 'directory' }),
    },
    {
      key: 'search',
      label: '搜索',
      icon: <MagnifierCheckIcon size={13} />,
      onSelect: handleRequestRootSearch,
    },
    {
      key: 'toggle-internal-files',
      label: showInternalFiles ? '隐藏核心文件' : '显示核心文件',
      active: showInternalFiles,
      onSelect: toggleInternalFiles,
    },
    {
      key: 'toggle-meta-mode',
      label: metaMode === 'size' ? '显示最后编辑时间' : '显示文件大小',
      active: metaMode === 'modified',
      onSelect: () => setMetaMode((current) => (current === 'size' ? 'modified' : 'size')),
    },
    {
      key: 'remove-workspace',
      label: '移除工作区…',
      danger: true,
      onSelect: () => void handleRemoveWorkspace(entry),
    },
  ], [entry, handleRequestCreate, handleRequestRefresh, handleRequestRootSearch, handleRequestUpload, metaMode, multiSelectMode, showInternalFiles, toggleInternalFiles, toggleMultiSelectMode, workspaceRoot])

  return (
    <div style={groupStyle}>
      <div style={recentListStyle}>
        <div style={groupHeaderStyle}>
          <div style={groupToggleStyle}>
            <span
              style={groupChevronButtonStyle}
              title={collapsed ? '展开' : '折叠'}
              onClick={() => setCollapsed((value) => !value)}
            >
              <ChevronDownIcon
                size={13}
                style={collapsed ? { ...groupChevronStyle, ...groupChevronCollapsedStyle } : groupChevronStyle}
              />
            </span>
            <span style={groupTitleStyle} title={workspaceRoot}>{getWorkspaceDisplayName(workspaceRoot)}</span>
            {isDefault ? <span style={groupBadgeStyle}>默认</span> : null}
          </div>
          <div style={sectionHeaderActionsStyle}>
            <MoreActionsButton
              items={rootMoreActionItems}
              title="资源管理器更多操作"
              disabled={reloading}
            />
          </div>
        </div>
        {!collapsed && (
          <>
            <div style={workspaceRootStyle} title={workspaceRoot}>{workspaceRoot}</div>
        {treeError ? <div style={emptyStyle}>{treeError}</div> : null}
        {multiSelectMode ? (
          <div style={multiSelectBarStyle}>
            <span style={multiSelectCountStyle}>已选 {selectedPaths.size} 项</span>
            <div style={multiSelectActionsStyle}>
              <Button
                type="button"
                size="sm"
                variant="secondary"
                disabled={selectedPaths.size === 0 || batchDeleting || batchMoving}
                onClick={handleRequestBatchMove}
              >
                移动
              </Button>
              <Button
                type="button"
                size="sm"
                variant="danger"
                disabled={selectedPaths.size === 0 || batchDeleting || batchMoving}
                onClick={handleRequestBatchDelete}
              >
                删除
              </Button>
            </div>
          </div>
        ) : null}
        <WorkspaceExplorerTree
          workspaceRoot={workspaceRoot}
          nodes={treeNodes}
          expandedPaths={expandedPaths}
          selectedPath={selectedExplorerPath}
          locatePath={locateRequest?.filePath ?? null}
          locateRequestedAt={locateRequest?.requestedAt ?? null}
          metaMode={metaMode}
          multiSelectMode={multiSelectMode}
          selectedPaths={selectedPaths}
          onToggleSelectedPath={handleToggleSelectedPath}
          onToggleDirectory={(path) => {
            setExpandedPaths((prev) => {
              const next = new Set(prev)
              if (next.has(path)) {
                next.delete(path)
              } else {
                next.add(path)
                // 展开:懒加载该目录的直接子节点(幂等)。
                expandDirChildren(path)
              }
              return next
            })
          }}
          onSelectPath={setSelectedExplorerPath}
          onOpenFile={handleOpenFile}
          onRequestDelete={handleRequestDelete}
          onLocateApplied={clearWorkspaceFileLocateRequest}
          getActionItems={getFileActionItems}
        />
          </>
        )}
      </div>
      <ConfirmDialog
        open={Boolean(deleteTarget)}
        title={getDeleteDialogTitle(deleteTarget)}
        subtitle={getDeleteDialogSubtitle(deleteTarget)}
        message={getDeleteDialogMessage(deleteTarget)}
        confirmLabel={deleting ? '删除中...' : '确认删除'}
        onConfirm={async () => {
          await handleDeleteFile()
        }}
        onCancel={() => {
          if (!deleting) {
            setDeleteTarget(null)
          }
        }}
      />
      <ConfirmDialog
        open={Boolean(renameTarget)}
        title="重命名"
        subtitle={renameTarget?.type === 'directory' ? '重命名文件夹' : '重命名文件'}
        message={renameTarget ? `当前路径：${renameTarget.path}` : ''}
        confirmLabel={renaming ? '处理中...' : '确认重命名'}
        onConfirm={handleConfirmRename}
        onCancel={() => {
          if (!renaming) {
            setRenameTarget(null)
            setRenameValue('')
          }
        }}
        body={renameTarget ? (
          <TextInput
            type="text"
            value={renameValue}
            autoFocus
            disabled={renaming}
            onChange={(event) => setRenameValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !renaming) {
                void handleConfirmRename()
              }
            }}
           style={renameInputStyle}
         />
       ) : null}
     />
      <ConfirmDialog
        open={Boolean(moveTarget)}
        title="移动"
        subtitle={moveTarget?.type === 'directory' ? '移动文件夹' : '移动文件'}
        message={moveTarget ? `当前路径：${moveTarget.path}` : ''}
        confirmLabel={moving ? '移动中...' : '确认移动'}
        onConfirm={handleConfirmMove}
        onCancel={() => {
          if (!moving) {
            setMoveTarget(null)
            setMoveTargetDir('')
          }
        }}
        body={moveTarget ? (
          <div style={moveBodyStyle}>
            <WorkspacePathPicker
              workspaceRoot={workspaceRoot}
              selectionMode="directory"
              value={moveTargetDir}
              allowRoot
              disabled={moving}
              onChange={setMoveTargetDir}
            />
            <div style={movePreviewStyle}>
              移动后路径：{moveTargetDir ? `${moveTargetDir}/` : ''}{moveTarget.name}
            </div>
          </div>
        ) : null}
      />
      <ConfirmDialog
        open={Boolean(createMode)}
        title={createMode === 'directory' ? '新建目录' : '新建文件'}
        subtitle={createParentName ? `在 ${createParentName} 下创建` : undefined}
        message={createParentPath ? `当前路径：${createParentPath}` : ''}
        confirmLabel={creating ? '创建中...' : '创建'}
        onConfirm={handleConfirmCreate}
        onCancel={() => {
          if (!creating) {
            setCreateMode(null)
            setCreateValue('')
          }
        }}
        body={createMode ? (
          <TextInput
            type="text"
            value={createValue}
            autoFocus
            disabled={creating}
            placeholder={createMode === 'directory' ? '输入目录名称' : '输入文件名称'}
            onChange={(event) => setCreateValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !creating) {
                void handleConfirmCreate()
              }
            }}
            style={renameInputStyle}
          />
        ) : null}
      />
      <ConfirmDialog
        open={batchDeleteOpen}
        title="批量删除"
        subtitle={`将删除 ${selectedPaths.size} 项`}
        message="所选文件/文件夹会被直接删除,此操作会立即生效且不可撤销。"
        confirmLabel={batchDeleting ? '删除中...' : '确认删除'}
        danger
        onConfirm={handleBatchDelete}
        onCancel={() => {
          if (!batchDeleting) {
            setBatchDeleteOpen(false)
          }
        }}
        body={
          <div style={batchPathListStyle}>
            {Array.from(selectedPaths).map((path) => (
              <div key={path} style={batchPathItemStyle}>{path}</div>
            ))}
          </div>
        }
      />
      <ConfirmDialog
        open={batchMoveOpen}
        title="批量移动"
        subtitle={`将移动 ${selectedPaths.size} 项`}
        message="所选文件/文件夹将移动到下方选择的目标目录。"
        confirmLabel={batchMoving ? '移动中...' : '确认移动'}
        onConfirm={handleBatchMove}
        onCancel={() => {
          if (!batchMoving) {
            setBatchMoveOpen(false)
            setBatchMoveDir('')
          }
        }}
        body={
          <div style={moveBodyStyle}>
            <WorkspacePathPicker
              workspaceRoot={workspaceRoot}
              selectionMode="directory"
              value={batchMoveDir}
              allowRoot
              disabled={batchMoving}
              onChange={setBatchMoveDir}
            />
            <div style={movePreviewStyle}>
              目标目录:{batchMoveDir ? `/${batchMoveDir}` : '工作区根'}
            </div>
            <div style={batchPathListStyle}>
              {Array.from(selectedPaths).map((path) => (
                <div key={path} style={batchPathItemStyle}>{path}</div>
              ))}
            </div>
          </div>
        }
      />
    </div>
  )
}

/** 移除工作区注册:挂靠该工作区的任务数据一并删除,工作区目录文件不受影响。 */
async function handleRemoveWorkspace(entry: WorkspaceEntry): Promise<void> {
  const confirmed = await new Promise<boolean>((resolve) => {
    antdConfirm({
      title: '移除工作区注册',
      content: `移除工作区注册?该工作区下的任务数据将一并删除,工作区目录文件不受影响:${entry.root}`,
      okText: '移除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
  if (!confirmed) {
    return
  }
  try {
    await workspaceRegistry.remove(entry.workerId, entry.root)
  } catch {
    // 失败静默:注册表广播会带回最新状态;失败详情可从控制台网络请求排查。
  }
}

/** 工作区显示名:根路径最后一段(如 D:\projects\novel → novel)。 */
function getWorkspaceDisplayName(root: string): string {
  const normalized = root.replace(/[\\/]+$/, '')
  const index = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
  return index >= 0 ? normalized.slice(index + 1) : normalized
}

function isPathWithinTarget(path: string, target: WorkspaceExplorerContextTarget): boolean {
  if (target.type === 'file') {
    return path === target.path
  }
  return path === target.path || path.startsWith(`${target.path}/`)
}

/**
 * 批量操作前对已选路径按前缀去重:若同时选中目录与其内部项,只保留最外层目录,
 * 避免批量删除/移动时对同一子树重复操作(先删父目录再删子项会报错)。
 */
function dedupeWorkspacePaths(paths: string[]): string[] {
  const sorted = [...paths].sort((a, b) => a.length - b.length)
  const result: string[] = []
  for (const path of sorted) {
    if (result.some((existing) => path === existing || path.startsWith(`${existing}/`))) {
      continue
    }
    result.push(path)
  }
  return result
}

function getDeleteDialogTitle(target: WorkspaceExplorerContextTarget | null): string {
  return target?.type === 'directory' ? '删除文件夹' : '删除文件'
}

/** 删除确认弹窗副标题。 */
function getDeleteDialogSubtitle(target: WorkspaceExplorerContextTarget | null): string {
  return target?.type === 'directory' ? '会直接删除当前工作区文件夹及其内容' : '会直接删除当前工作区文件'
}

/**
 * 把展开集合中命中 oldPath 前缀的目录 key 迁移为 newPath(重命名/移动目录后保持展开语义)。
 * 与 Layout.renameFileTabs 的 resolveRenamedPath 同款前缀替换;expandedPaths 存业务绝对路径。
 */
function migrateExpandedPaths(expanded: Set<string>, oldPath: string, newPath: string): Set<string> {
  if (!oldPath || !newPath || oldPath === newPath) return expanded
  let changed = false
  const next = new Set<string>()
  for (const key of expanded) {
    if (key === oldPath) {
      next.add(newPath)
      changed = true
    } else if (key.startsWith(`${oldPath}/`)) {
      next.add(`${newPath}${key.slice(oldPath.length)}`)
      changed = true
    } else {
      next.add(key)
    }
  }
  return changed ? next : expanded
}

function getDeleteDialogMessage(target: WorkspaceExplorerContextTarget | null): string {
  return target ? `确认删除 ${target.path} 吗？这个操作会立即生效。` : ''
}

const renameInputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border)',
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  fontSize: 'var(--text-sm)',
  outline: 'none',
}

const moveBodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
}

const movePreviewStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  wordBreak: 'break-all',
  lineHeight: 1.5,
}

const multiSelectBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  padding: '6px 8px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid color-mix(in srgb, var(--accent-blue) 26%, var(--border))',
  background: 'color-mix(in srgb, var(--accent-blue-dim) 45%, var(--bg-primary))',
}

const multiSelectCountStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--accent-blue)',
  whiteSpace: 'nowrap',
}

const multiSelectActionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  flexShrink: 0,
}

const batchPathListStyle: React.CSSProperties = {
  maxHeight: 160,
  overflowY: 'auto',
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  padding: '8px 10px',
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-md)',
  background: 'var(--bg-primary)',
}

const batchPathItemStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-secondary)',
  fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  lineHeight: 1.6,
}

const panelStyle: React.CSSProperties = {
  width: '100%',
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
  minWidth: 0,
  minHeight: 0,
  background: 'var(--bg-secondary)',
}

const bodyStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
}

const sectionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
}

const sectionHeaderActionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
}

const groupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '8px 6px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border-light)',
  background: 'var(--bg-primary)',
}

const recentListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
}

const groupToggleStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
  flex: 1,
  borderRadius: 'var(--radius-sm)',
}

/** 折叠/展开触发图标:仅点击该图标才折叠或展开(标题/路径等区域不触发)。 */
const groupChevronButtonStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  cursor: 'pointer',
  padding: 2,
  borderRadius: 'var(--radius-sm)',
  color: 'var(--text-muted)',
}

const groupChevronStyle: React.CSSProperties = {
  flexShrink: 0,
  color: 'var(--text-muted)',
  transition: 'transform 0.15s ease',
}

const groupChevronCollapsedStyle: React.CSSProperties = {
  transform: 'rotate(-90deg)',
}

const groupHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
}

const groupTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
  flex: 1,
}

const groupBadgeStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-blue)',
  flexShrink: 0,
}

const workspaceRootStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  padding: '0 2px',
}

const emptyStyle: React.CSSProperties = {
  padding: '12px 8px',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
}
