import React from 'react'
import WorkspacePageShell from '../shared/WorkspacePageShell'
import BrandLoadingBlock from '../shared/BrandLoadingBlock'
import MoreActionsButton, { type MoreActionItem } from '../shared/MoreActionsButton'
import { FileIcon, FileTextIcon, SearchIcon } from '../shared/AppGlyphs'
import { Button } from '@/components/shared/ui'
import { Input } from 'antd'
import type { InputRef } from 'antd'
import { useWorkspaceShell } from '../app/WorkspaceShellContext'
import { useAppUi } from '../app/AppUiContext'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { fileTabQueryService } from '@/query/fileTabQueryService'
import { clearFileTabDirtyState, setFileTabDirtyState } from '@/services/fileDirtyStateRegistry'
import { fileTabCommandService } from '@/services/fileTabCommandService'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import type { UiFileSidebarPanelDefinition } from '@/plugin/types'
import type { FileTabOpenMode } from '@/types'
import type { FileContentHeaderAction, FileTabResource } from './file-tab-types'
import { getFallbackFileContentEditor, listFileContentEditors, resolveFileContentEditorByPath } from './editors/registry'
import FindInFileBar from './FindInFileBar'
import { useContentFind } from './useContentFind'
import { useHighlightMatches } from './useHighlightMatches'

export type FileTabPageLifecycle = {
  canUnmount: () => boolean
  requestClose: () => boolean
}

export default function FileTabPage({
  file,
  onClose,
  onLifecycleChange,
}: {
  file: FileTabResource | null
  onClose: () => void
  onLifecycleChange?: (lifecycle: FileTabPageLifecycle | null) => void
}) {
  const { showToast } = useAppUi()
  const { isMobile } = useResponsiveViewport()
  const {
    renameFileTabs,
    setGlobalFileTabMode,
    setWorkspaceFileNameEditRequested,
    setWorkspaceFileLineLocateRequested,
    requestWorkspaceFileLocate,
  } = useWorkspaceShell()
  const [content, setContent] = React.useState('')
  const [draftContent, setDraftContent] = React.useState('')
  const [loading, setLoading] = React.useState(false)
  const [saving, setSaving] = React.useState(false)
  const [justSaved, setJustSaved] = React.useState(false)
  const justSavedTimerRef = React.useRef<number | null>(null)
  const [error, setError] = React.useState('')
  const [refreshRevision, setRefreshRevision] = React.useState(0)
  const [fileNameDraft, setFileNameDraft] = React.useState('')
  const [nameEditing, setNameEditing] = React.useState(false)
  const [fileSidebarPanels, setFileSidebarPanels] = React.useState<UiFileSidebarPanelDefinition[]>([])
  const [activeFileSidebarPanelId, setActiveFileSidebarPanelId] = React.useState<string | null>(null)
  const [editorHeaderActions, setEditorHeaderActions] = React.useState<FileContentHeaderAction[]>([])
  const [externalReloadRequestedAt, setExternalReloadRequestedAt] = React.useState(0)
  const fileNameInputRef = React.useRef<InputRef>(null)
  const savingRef = React.useRef(false)
  const justSavedRef = React.useRef(false)
  const dirtyRef = React.useRef(false)
  dirtyRef.current = draftContent !== content
  const openMode: FileTabOpenMode = file?.mode ?? 'readonly'
  const canEditContent = Boolean(file && openMode === 'readwrite')
  const canRenameFile = Boolean(file && openMode === 'readwrite')
  const [findOpen, setFindOpen] = React.useState(false)
  const editorContainerRef = React.useRef<HTMLDivElement | null>(null)
  // 查找始终基于「当前可见可编辑」的源文本：编辑态搜草稿、其余态搜已读内容。
  const findSourceContent = openMode === 'readwrite' ? draftContent : content
  // 替换只写回可编辑草稿；只读态传 noop，替换按钮随之禁用。
  const findContentChange = React.useCallback(
    (next: string) => {
      if (canEditContent) setDraftContent(next)
    },
    [canEditContent],
  )
  const find = useContentFind(findSourceContent, findContentChange)
  useHighlightMatches(editorContainerRef, {
    regex: find.regex,
    activeIndex: find.activeIndex,
    enabled: findOpen,
  })
  const selectedEditorDescriptor = React.useMemo(() => {
    if (file?.editorKind) {
      return listFileContentEditors().find((item) => item.kind === file.editorKind) ?? getFallbackFileContentEditor()
    }
    // 未显式指定编辑器种类时，按文件扩展名解析（如 .md → Markdown 编辑器）。
    // 否则会始终落到兜底纯文本编辑器，缺失预览/编辑等专属控件。
    return resolveFileContentEditorByPath(file?.filePath ?? '')
  }, [file?.editorKind, file?.filePath])
  const isFallbackEditor = React.useMemo(() => {
    if (!file) return false
    const extension = getFileExtension(file.fileName || file.filePath).toLowerCase()
    return selectedEditorDescriptor.kind === 'text' && !selectedEditorDescriptor.extensions.includes(extension)
  }, [file, selectedEditorDescriptor])

  React.useEffect(() => {
    if (!file) return

    setLoading(true)
    setError('')
    setContent('')
    setDraftContent('')

    fileTabQueryService.readTextContent(file).then((nextContent) => {
      setContent(nextContent)
      setDraftContent(nextContent)
    })
      .catch((readError) => setError(String(readError)))
      .finally(() => setLoading(false))
  }, [file?.id, file?.reloadKey, refreshRevision, externalReloadRequestedAt])

  React.useEffect(() => {
    if (!file) return
    // 订阅底层文件变更事件：仅当变更的就是“当前这个文件标签页”时，
    // 才重新读取内容更新视图；其它已打开标签页互不影响。
    const unsubscribe = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_FILE_CHANGED, (payload) => {
      // 多工作区并行:先比对工作区,再比对路径——别的工作区同名文件不触发本标签回读。
      if (payload.workspaceRoot !== file.workspaceRoot) return
      if (payload.filePath !== file.filePath && payload.oldFilePath !== file.filePath) return
      // 重命名由 renameFileTabs 直接更新标签路径（内容不变），无需回读，避免按旧路径读到失效文件。
      if (payload.operation === 'rename') return
      if (payload.operation === 'delete') {
        // 文件已被删除：无未保存编辑时提示失效，有未保存编辑则保留本地草稿。
        if (!dirtyRef.current) {
          setError(`文件已被删除：${payload.filePath}`)
        }
        return
      }
      // 不覆盖用户未保存的编辑，也不在刚刚自己保存后立刻回读造成闪烁。
      if (dirtyRef.current || justSavedRef.current) return
      setExternalReloadRequestedAt(Date.now())
    })
    return () => unsubscribe()
  }, [file?.filePath, file?.workspaceRoot])

  React.useEffect(() => {
    setFileNameDraft(file?.fileName ?? '')
    setNameEditing(false)
  }, [file?.filePath, file?.fileName])

  React.useEffect(() => {
    setEditorHeaderActions([])
  }, [file?.id])

  React.useEffect(() => {
    let cancelled = false
    void pluginDispatcher.dispatch<UiFileSidebarPanelDefinition>('ui.file_sidebar_panels')
      .then((panels) => {
        if (cancelled) return
        setFileSidebarPanels(panels)
      })
      .catch((error) => {
        if (cancelled) return
        throw error
      })
    return () => {
      cancelled = true
    }
  }, [])

  React.useEffect(() => {
    if (!file?.nameEditRequestedAt) return
    setNameEditing(true)
    setWorkspaceFileNameEditRequested(file.id, undefined)
  }, [file?.id, file?.nameEditRequestedAt, setWorkspaceFileNameEditRequested])

  const handleLineLocateApplied = React.useCallback(() => {
    if (!file || file.lineLocateRequestedAt == null || file.lineNumber == null) return
    setWorkspaceFileLineLocateRequested(file.id, undefined)
  }, [file, setWorkspaceFileLineLocateRequested])

  React.useEffect(() => {
    if (!nameEditing) return
    requestAnimationFrame(() => {
      fileNameInputRef.current?.focus()
      fileNameInputRef.current?.select()
    })
  }, [nameEditing])

  const isDirty = draftContent !== content
  const normalizedFileNameDraft = file ? normalizeFileNameDraft(fileNameDraft, file.fileName) : ''
  const isFileNameDirty = Boolean(canRenameFile && file && normalizedFileNameDraft && normalizedFileNameDraft !== file.fileName)
  const showSaveButton = canEditContent || nameEditing || isFileNameDirty
  const showEditButton = Boolean(file && openMode === 'readonly')
  const availableFileSidebarPanels = React.useMemo(
    () => file ? fileSidebarPanels.filter((panel) => panel.isAvailable?.(file) ?? true) : [],
    [file, fileSidebarPanels],
  )
  const activeFileSidebarPanel = React.useMemo(
    () => availableFileSidebarPanels.find((panel) => panel.id === activeFileSidebarPanelId) ?? null,
    [activeFileSidebarPanelId, availableFileSidebarPanels],
  )
  const defaultHeaderActions = React.useMemo(
    () => editorHeaderActions.filter((action) => action.placement !== 'save-adjacent'),
    [editorHeaderActions],
  )
  const saveAdjacentHeaderActions = React.useMemo(
    () => editorHeaderActions.filter((action) => action.placement === 'save-adjacent'),
    [editorHeaderActions],
  )
  // 编辑器已自带「预览|编辑」视图切换按钮（如 Markdown 的 markdown-view-toggle）时，
  // 文件级「编辑」按钮不再重复渲染。
  const editorProvidesViewMode = React.useMemo(
    () => editorHeaderActions.some((action) => action.id === 'markdown-view-toggle'),
    [editorHeaderActions],
  )

  React.useEffect(() => {
    if (!file?.id) return
    setFileTabDirtyState(file.id, isDirty || isFileNameDirty)
    return () => {
      clearFileTabDirtyState(file.id)
    }
  }, [file?.id, isDirty, isFileNameDirty])

  React.useEffect(() => {
    if (!activeFileSidebarPanelId) return
    if (availableFileSidebarPanels.some((panel) => panel.id === activeFileSidebarPanelId)) return
    setActiveFileSidebarPanelId(null)
  }, [activeFileSidebarPanelId, availableFileSidebarPanels])

  const canUnmount = React.useCallback(() => {
    if (!file) return true
    if (savingRef.current) {
      showToast('文件正在保存中，暂时不能关闭。', 'error')
      return false
    }
    if (isDirty || isFileNameDirty) {
      showToast('当前文件有未保存修改，不能关闭。', 'error')
      return false
    }
    return true
  }, [file, isDirty, isFileNameDirty, showToast])

  const requestClose = React.useCallback(() => {
    if (!canUnmount()) return false
    onClose()
    return true
  }, [canUnmount, onClose])

  React.useEffect(() => {
    onLifecycleChange?.(file ? { canUnmount, requestClose } : null)
    return () => {
      onLifecycleChange?.(null)
    }
  }, [canUnmount, file, onLifecycleChange, requestClose])

  React.useEffect(() => {
    return () => {
      if (justSavedTimerRef.current !== null) {
        window.clearTimeout(justSavedTimerRef.current)
      }
    }
  }, [])

  const handleSave = React.useCallback(async () => {
    if (!file || !canEditContent) return
    if (draftContent === content && !isFileNameDirty) return
    if (savingRef.current) return
    savingRef.current = true
    setSaving(true)
    setError('')
    try {
      const saveResult = await fileTabCommandService.saveFile({
        file,
        nextFileName: normalizedFileNameDraft,
        nextContent: draftContent,
      })

      if (isFileNameDirty) {
        renameFileTabs(file.workspaceRoot, file.filePath, saveResult.filePath)
      }

      setContent(saveResult.content)
      setDraftContent(saveResult.content)
      setFileNameDraft(saveResult.fileName)
      setNameEditing(false)
    } catch (saveError) {
      setError(String(saveError))
    } finally {
      savingRef.current = false
      setSaving(false)
      // 保存结束后短暂保持按钮「可保存」外观，避免 isDirty 归零导致的样式骤变闪烁。
      setJustSaved(true)
      justSavedRef.current = true
      if (justSavedTimerRef.current !== null) {
        window.clearTimeout(justSavedTimerRef.current)
      }
      justSavedTimerRef.current = window.setTimeout(() => {
        setJustSaved(false)
        justSavedRef.current = false
      }, 600)
    }
  }, [canEditContent, content, draftContent, file, isFileNameDirty, normalizedFileNameDraft, renameFileTabs])

  React.useEffect(() => {
    if (!file) return

    const handleWindowKeyDown = (event: KeyboardEvent) => {
      const isSaveShortcut = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's'
      if (!isSaveShortcut) {
        return
      }
      if (!canEditContent) {
        return
      }
      event.preventDefault()
      void handleSave()
    }

    window.addEventListener('keydown', handleWindowKeyDown)
    return () => {
      window.removeEventListener('keydown', handleWindowKeyDown)
    }
  }, [canEditContent, file, handleSave])

  React.useEffect(() => {
    if (!file) return
    const handleFindKeyDown = (event: KeyboardEvent) => {
      const key = event.key.toLowerCase()
      const isFindToggle = (event.ctrlKey || event.metaKey) && !event.shiftKey && key === 'f'
      const isFindNext = (event.ctrlKey || event.metaKey) && !event.shiftKey && key === 'g'
      const isFindPrev = (event.ctrlKey || event.metaKey) && event.shiftKey && key === 'g'
      if (isFindToggle) {
        // 拦截浏览器原生查找，打开内置查找条。
        event.preventDefault()
        setFindOpen(true)
        return
      }
      if (!findOpen) return
      if (event.key === 'F3') {
        event.preventDefault()
        if (event.shiftKey) find.prev()
        else find.next()
        return
      }
      if (isFindNext) {
        event.preventDefault()
        find.next()
        return
      }
      if (isFindPrev) {
        event.preventDefault()
        find.prev()
        return
      }
      if (event.key === 'Escape') {
        setFindOpen(false)
      }
    }
    window.addEventListener('keydown', handleFindKeyDown)
    return () => {
      window.removeEventListener('keydown', handleFindKeyDown)
    }
  }, [file, findOpen, find])

  /**
   * 编辑态（textarea）下没有可高亮的文本节点：查找条改由行级滚动定位当前命中。
   * 只读 / 预览态由 useHighlightMatches 负责高亮 + 滚动，此处 querySelector 找不到 textarea 会直接跳过。
   */
  React.useLayoutEffect(() => {
    if (!findOpen || !find.activeMatch) return
    const root = editorContainerRef.current
    if (!root) return
    const textarea = root.querySelector('textarea')
    if (!textarea) return
    const computed = getComputedStyle(textarea)
    const lineHeightPx = parseFloat(computed.lineHeight) || (parseFloat(computed.fontSize) * 1.8) || 25
    const targetTop = Math.max(0, (find.activeMatch.line - 3) * lineHeightPx)
    textarea.scrollTo({ top: targetTop, behavior: 'smooth' })
  }, [findOpen, find.activeMatch])

  const handleRefresh = React.useCallback(() => {
    if (!file) return
    if (savingRef.current) {
      showToast('文件正在保存中，暂时不能刷新。', 'error')
      return
    }
    if (isDirty || isFileNameDirty) {
      showToast('当前文件有未保存修改，不能刷新。', 'error')
      return
    }
    setRefreshRevision((current) => current + 1)
  }, [file, isDirty, isFileNameDirty, showToast])

  const handleEnableEditing = React.useCallback(() => {
    if (!file || openMode === 'readwrite') return
    setGlobalFileTabMode(file.id, 'readwrite')
  }, [file, openMode, setGlobalFileTabMode])

  const mobileMoreActionItems = React.useMemo<MoreActionItem[]>(() => {
    if (!isMobile) return []

    // 仅把「标题栏未直接展示」的默认动作收进更多菜单；
    // save-adjacent 动作（如 Markdown 的「预览|编辑」）已在标题栏渲染，避免重复。
    const items: MoreActionItem[] = [
      ...buildHeaderMoreActionItems(defaultHeaderActions),
    ]

    // 编辑器未自带视图切换控件时，才在更多菜单补充「编辑」入口。
    if (showEditButton && !editorProvidesViewMode) {
      items.push({
        key: 'enable-editing',
        label: '编辑',
        onSelect: handleEnableEditing,
        disabled: loading || saving,
      })
    }

    // 文件内查找入口（Ctrl+F 之外的菜单入口；查找条本身由 FileTabPage 内 state 驱动）。
    items.push({
      key: 'find-in-file',
      label: '查找',
      icon: <SearchIcon size={13} />,
      onSelect: () => setFindOpen(true),
      disabled: loading || Boolean(error),
    })

    items.push(
      {
        key: 'locate-file',
        label: '定位到文件',
        onSelect: () => {
          if (file) {
            requestWorkspaceFileLocate(file.filePath, file.workspaceRoot)
          }
        },
      },
      {
        key: 'refresh',
        label: loading ? '刷新中...' : '刷新',
        onSelect: handleRefresh,
        disabled: loading || saving,
      },
    )

    for (const panel of availableFileSidebarPanels) {
      items.push({
        key: `toggle-file-sidebar:${panel.id}`,
        label: activeFileSidebarPanelId === panel.id ? `隐藏${panel.title}` : panel.title,
        onSelect: () => setActiveFileSidebarPanelId((current) => current === panel.id ? null : panel.id),
      })
    }

    return items
  }, [
    activeFileSidebarPanelId,
    availableFileSidebarPanels,
    defaultHeaderActions,
    editorProvidesViewMode,
    file,
    handleEnableEditing,
    handleRefresh,
    isMobile,
    loading,
    requestWorkspaceFileLocate,
    error,
    saving,
    showEditButton,
  ])

  const desktopMoreActionItems = React.useMemo<MoreActionItem[]>(() => {
    if (isMobile) return []

    const items: MoreActionItem[] = [
      {
        key: 'find-in-file',
        label: '查找',
        icon: <SearchIcon size={13} />,
        onSelect: () => setFindOpen(true),
        disabled: loading || Boolean(error),
      },
      {
        key: 'locate-file',
        label: '定位到文件',
        onSelect: () => {
          if (file) {
            requestWorkspaceFileLocate(file.filePath, file.workspaceRoot)
          }
        },
      },
      {
        key: 'refresh',
        label: loading ? '刷新中...' : '刷新',
        onSelect: handleRefresh,
        disabled: loading || saving,
      },
    ]

    for (const panel of availableFileSidebarPanels) {
      items.push({
        key: `toggle-file-sidebar:${panel.id}`,
        label: activeFileSidebarPanelId === panel.id ? `隐藏${panel.title}` : panel.title,
        onSelect: () => setActiveFileSidebarPanelId((current) => current === panel.id ? null : panel.id),
      })
    }

    return items
  }, [activeFileSidebarPanelId, availableFileSidebarPanels, file, handleRefresh, isMobile, loading, requestWorkspaceFileLocate, error, saving])

  if (!file) {
    return (
      <div style={emptyViewStyle}>
        <div style={{ marginBottom: 12, color: 'var(--text-muted)' }}>
          <FileIcon size={40} />
        </div>
        <div style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-base)' }}>
          从侧边栏或任务产物中打开文件
        </div>
      </div>
    )
  }

  return (
    <>
      <div style={fileViewerHostStyle}>
        <WorkspacePageShell
          bodyPadding={0}
          shellClassName="file-viewer"
          shellStyle={fileViewStyle}
          headerStyle={fileHeaderShellStyle}
          bodyStyle={fileShellBodyStyle}
          bodyInnerStyle={fileShellBodyInnerStyle}
          header={(
            <div className="file-viewer__header" style={fileHeaderStyle}>
              <div className="file-viewer__header-main-row" style={fileHeaderMainRowStyle}>
                <div style={fileHeaderTitleGroupStyle}>
                  <FileTextIcon size={16} style={{ color: 'var(--text-secondary)' }} />
                  {nameEditing ? (
                    <Input
                      ref={fileNameInputRef}
                      value={fileNameDraft}
                      onChange={(event) => setFileNameDraft(event.target.value)}
                      onBlur={() => setNameEditing(false)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault()
                          void handleSave()
                        }
                        if (event.key === 'Escape') {
                          event.preventDefault()
                          setFileNameDraft(file.fileName)
                          setNameEditing(false)
                        }
                      }}
                      style={fileHeaderNameInputStyle}
                    />
                  ) : (
                    <span
                      onClick={() => {
                        if (canRenameFile) {
                          setNameEditing(true)
                        }
                      }}
                      style={canRenameFile ? fileHeaderNameButtonStyle : fileHeaderNameReadonlyStyle}
                      title={canRenameFile ? '点击修改文件名' : undefined}
                    >
                      {file.fileName}
                    </span>
                  )}
                </div>
                <div className="file-viewer__actions" style={fileHeaderActionRowStyle}>
                  {!isMobile ? renderHeaderActionGroups(defaultHeaderActions) : null}
                  {renderHeaderActionGroups(saveAdjacentHeaderActions)}
                  {showSaveButton ? (
                    <Button
                      onClick={handleSave}
                      style={(isDirty || isFileNameDirty || saving || justSaved) && normalizedFileNameDraft ? saveButtonStyle : saveButtonDisabledStyle}
                      disabled={(!isDirty && !isFileNameDirty) || saving || !normalizedFileNameDraft}
                      title="Ctrl+S"
                    >
                      保存
                    </Button>
                  ) : null}
                  {showEditButton && !editorProvidesViewMode ? (
                    <Button
                      type="button"
                      onClick={handleEnableEditing}
                      style={metaActionButtonStyle}
                      disabled={loading || saving}
                    >
                      编辑
                    </Button>
                  ) : null}
                  <MoreActionsButton
                    items={isMobile ? mobileMoreActionItems : desktopMoreActionItems}
                    title="文件更多操作"
                  />
                </div>
              </div>
            </div>
          )}
        >
          <div className="file-viewer__body" style={fileBodyRowStyle}>
            <div style={fileContentStyle}>
              {loading ? (
                <BrandLoadingBlock
                  size="md"
                  title="正在读取文件"
                  subtitle={file.filePath}
                />
              ) : error ? (
                <div style={emptyViewStyle}>{error}</div>
              ) : (
                <div ref={editorContainerRef} style={editorScopeStyle}>
                  <selectedEditorDescriptor.Component
                    file={file}
                    mode={openMode}
                    content={content}
                    draftContent={draftContent}
                    loading={loading}
                    error={error}
                    lineNumber={file.lineNumber}
                    lineLocateRequestedAt={file.lineLocateRequestedAt}
                    onLineLocateApplied={handleLineLocateApplied}
                    onDraftChange={setDraftContent}
                    onHeaderActionsChange={setEditorHeaderActions}
                    onRequestEditMode={handleEnableEditing}
                    findRegex={findOpen ? find.regex : null}
                    findActiveIndex={find.activeIndex}
                    findEnabled={findOpen}
                  />
                </div>
              )}
            </div>
            {activeFileSidebarPanel ? (
              <aside className="file-viewer__sidebars" style={sidebarsWrapStyle}>
                <activeFileSidebarPanel.Panel
                  file={file}
                  requestRefresh={() => setRefreshRevision((current) => current + 1)}
                />
              </aside>
            ) : null}
          </div>
        </WorkspacePageShell>
        {findOpen && file && !loading && !error ? (
          <div style={findBarHostStyle}>
            <FindInFileBar
              query={find.query}
              onQueryChange={find.setQuery}
              caseSensitive={find.caseSensitive}
              onToggleCaseSensitive={find.toggleCaseSensitive}
              count={find.count}
              activeIndex={find.activeIndex}
              onNext={find.next}
              onPrev={find.prev}
              onClose={() => setFindOpen(false)}
              replaceQuery={find.replaceQuery}
              onReplaceQueryChange={find.setReplaceQuery}
              onReplace={find.replace}
              onReplaceAll={find.replaceAll}
              canReplace={canEditContent}
              isMobile={isMobile}
            />
          </div>
        ) : null}
      </div>
    </>
  )
}

function normalizeFileNameDraft(value: string, currentFileName: string): string {
  const trimmed = value.trim()
  if (!trimmed) return ''
  const ext = getFileExtension(currentFileName)
  const sanitized = trimmed.replace(/[<>:"|?*\\/]/g, '_')
  if (!ext) return sanitized
  return sanitized.toLowerCase().endsWith(ext.toLowerCase())
    ? sanitized
    : `${sanitized}${ext}`
}

function getFileExtension(fileName: string): string {
  const dotIndex = fileName.lastIndexOf('.')
  if (dotIndex <= 0) return ''
  return fileName.slice(dotIndex)
}

function renderHeaderActionGroups(actions: FileContentHeaderAction[]): React.ReactNode {
  if (actions.length === 0) return null

  const groups = new Map<string, FileContentHeaderAction[]>()
  actions.forEach((action) => {
    const groupKey = action.groupId ?? `__single__:${action.id}`
    const current = groups.get(groupKey)
    if (current) {
      current.push(action)
      return
    }
    groups.set(groupKey, [action])
  })

  return Array.from(groups.entries()).map(([groupKey, groupActions]) => {
    const isSegmentedGroup = !groupKey.startsWith('__single__:') && groupActions.length > 1
    if (isSegmentedGroup) {
      return (
        <div key={groupKey} style={headerActionGroupStyle}>
          {groupActions.map((action) => (
            <Button
              key={action.id}
              type="button"
              onClick={action.onClick}
              title={action.title}
              disabled={action.disabled}
              style={{
                ...headerSegmentButtonStyle,
                ...(action.active ? headerSegmentButtonActiveStyle : null),
                ...(action.disabled ? headerActionDisabledStyle : null),
              }}
            >
              {action.label}
            </Button>
          ))}
        </div>
      )
    }

    return groupActions.map((action) => (
      <Button
        key={action.id}
        type="button"
        onClick={action.onClick}
        title={action.title}
        disabled={action.disabled}
        style={{
          ...metaActionButtonStyle,
          ...(action.active ? metaActionButtonActiveStyle : null),
          ...(action.disabled ? headerActionDisabledStyle : null),
        }}
      >
        {action.label}
      </Button>
    ))
  })
}

function buildHeaderMoreActionItems(actions: FileContentHeaderAction[]): MoreActionItem[] {
  return actions.map((action) => ({
    key: action.id,
    label: action.active ? `✓ ${action.label}` : action.label,
    onSelect: action.onClick,
    disabled: action.disabled,
  }))
}

const emptyViewStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  color: 'var(--text-muted)',
  textAlign: 'center',
  padding: 24,
}

const fileViewStyle: React.CSSProperties = {
  background: 'var(--bg-primary)',
}

const fileShellBodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
  overflow: 'hidden',
}

const fileShellBodyInnerStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  padding: 0,
  overflow: 'hidden',
}

const fileViewerHostStyle: React.CSSProperties = {
  position: 'relative',
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
}

const fileHeaderShellStyle: React.CSSProperties = {
  background: 'var(--bg-primary)',
  borderBottom: '1px solid color-mix(in srgb, var(--border) 55%, var(--bg-primary))',
}

const fileBodyRowStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'row',
  minWidth: 0,
  minHeight: 0,
  overflow: 'hidden',
}

const sidebarsWrapStyle: React.CSSProperties = {
  width: 320,
  minWidth: 320,
  borderLeft: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
  height: '100%',
  position: 'relative',
  overflow: 'hidden',
}

const fileHeaderStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'stretch',
  padding: '6px 18px 2px',
  gap: 10,
}

const fileHeaderMainRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 10,
  minWidth: 0,
  width: '100%',
  flexWrap: 'nowrap',
}

const fileHeaderActionRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 4,
  alignItems: 'center',
  flexWrap: 'nowrap',
  overflowX: 'auto',
  minWidth: 0,
  flexShrink: 0,
}

const fileHeaderTitleGroupStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  minWidth: 0,
  flex: 1,
}

const fileHeaderNameStyle: React.CSSProperties = {
  color: 'var(--text-primary)',
  fontWeight: 800,
  fontSize: 'var(--text-base)',
  letterSpacing: 0,
}

const fileHeaderNameReadonlyStyle: React.CSSProperties = {
  ...fileHeaderNameStyle,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const fileHeaderNameButtonStyle: React.CSSProperties = {
  ...fileHeaderNameReadonlyStyle,
  cursor: 'text',
}

const fileHeaderNameInputStyle: React.CSSProperties = {
  ...fileHeaderNameStyle,
  background: 'var(--bg-secondary)',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-sm)',
  padding: '3px 8px',
  outline: 'none',
  minWidth: 220,
  maxWidth: 420,
}

const fileContentStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--bg-secondary)',
  padding: '0 0 0 8px',
}

const editorScopeStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
}

const findBarHostStyle: React.CSSProperties = {
  position: 'absolute',
  top: 52,
  right: 16,
  zIndex: 30,
  pointerEvents: 'auto',
}

const metaActionButtonStyle: React.CSSProperties = {
  background: 'var(--bg-secondary)',
  border: '1px solid var(--border)',
  borderRadius: 999,
  color: 'var(--text-primary)',
  cursor: 'pointer',
  fontSize: 'var(--text-xs)',
  fontWeight: 500,
  padding: '4px 10px',
  whiteSpace: 'nowrap',
}

const metaActionButtonActiveStyle: React.CSSProperties = {
  background: 'var(--accent-blue-dim)',
  borderColor: 'color-mix(in srgb, var(--accent-blue) 36%, var(--border))',
  color: 'var(--accent-blue)',
  fontWeight: 700,
}

const headerActionGroupStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  padding: 2,
  borderRadius: 999,
  background: 'var(--bg-secondary)',
  border: '1px solid var(--border-light)',
  gap: 2,
}

const headerSegmentButtonStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: 'var(--text-secondary)',
  cursor: 'pointer',
  fontSize: 'var(--text-xs)',
  fontWeight: 500,
  padding: '4px 10px',
  borderRadius: 999,
  whiteSpace: 'nowrap',
}

const headerSegmentButtonActiveStyle: React.CSSProperties = {
  background: 'var(--accent-blue-dim)',
  color: 'var(--accent-blue)',
  boxShadow: 'inset 0 0 0 1px color-mix(in srgb, var(--accent-blue) 38%, transparent)',
  fontWeight: 700,
}

const headerActionDisabledStyle: React.CSSProperties = {
  color: 'var(--text-muted)',
  cursor: 'not-allowed',
  opacity: 0.6,
}

const saveButtonStyle: React.CSSProperties = {
  background: 'var(--bg-secondary)',
  border: '1px solid var(--border)',
  borderRadius: 999,
  color: 'var(--text-primary)',
  cursor: 'pointer',
  fontSize: 'var(--text-xs)',
  fontWeight: 600,
  padding: '4px 10px',
  whiteSpace: 'nowrap',
  minWidth: 72,
  textAlign: 'center',
}

const saveButtonDisabledStyle: React.CSSProperties = {
  background: 'var(--bg-secondary)',
  border: '1px solid var(--border-light)',
  borderRadius: 999,
  color: 'var(--text-muted)',
  cursor: 'not-allowed',
  fontSize: 'var(--text-xs)',
  fontWeight: 500,
  padding: '4px 10px',
  whiteSpace: 'nowrap',
  opacity: 0.6,
  minWidth: 72,
  textAlign: 'center',
}
