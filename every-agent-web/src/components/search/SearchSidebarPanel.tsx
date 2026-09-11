import React from 'react'
import { Input } from 'antd'
import type { InputRef } from 'antd'
import { useWorkspaceShell } from '../app/WorkspaceShellContext'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { toBusinessAbsolutePath } from '@/platform/fs/pathUtils'
import type { WorkspaceContentSearchHit } from '@/query/workspaceContentSearch'
import SidebarScrollArea from '../shared/SidebarScrollArea'
import { IconButton, InlineSpinner } from '@/components/shared/ui'
import { ChevronDownIcon, CloseIcon, SearchIcon } from '../shared/AppGlyphs'
import SearchResultsTree from './SearchResultsTree'
import { useWorkspaceSearch } from './useWorkspaceSearch'

/**
 * 搜索范围：资源管理器右键目录「搜索」跳转过来时预填；
 * null = 默认范围（注册表首个工作区的根目录）。
 */
interface SearchScope {
  /** 范围所属工作区根（worker 机器绝对路径）。 */
  workspaceRoot: string
  /** 搜索根路径（业务绝对形态；空串表示工作区根）。 */
  rootPath: string
  /** 范围显示名（目录名或「工作区根目录」）。 */
  label: string
}

/**
 * 搜索侧边栏面板（活动栏一级入口，仿 VSCode 搜索面板）。
 *
 * - 输入区：搜索词 + Aa（大小写）/ ab|（全字）/ .*（正则）三个开关（聚焦时支持
 *   Alt+C / Alt+W / Alt+R 切换）+ ‥ 展开「包含/排除文件」glob 过滤器；
 * - 非法正则：输入框红框 + 错误提示，不触发搜索；
 * - 范围：默认工作区根；资源管理器右键「搜索」经 WORKSPACE_SEARCH_PANEL_REQUESTED
 *   事件跳转预填目录，可「×」恢复为工作区根；
 * - 结果树：SearchResultsTree 按文件分组渲染，命中行点击打开文件并定位到行。
 */
export default function SearchSidebarPanel() {
  const { openGlobalFileTab } = useWorkspaceShell()
  const [registry, setRegistry] = React.useState(workspaceRegistry.current)

  React.useEffect(() => workspaceRegistry.subscribe(setRegistry), [])

  /** 默认搜索范围的工作区根：注册表首个工作区（与 Layout 的 primaryRoot 兜底一致）。 */
  const defaultWorkspaceRoot = React.useMemo(
    () => registry?.workspaces[0]?.root ?? registry?.defaultRoot ?? '',
    [registry],
  )

  const [scope, setScope] = React.useState<SearchScope | null>(null)
  const [query, setQuery] = React.useState('')
  const [caseSensitive, setCaseSensitive] = React.useState(false)
  const [wholeWord, setWholeWord] = React.useState(false)
  const [useRegex, setUseRegex] = React.useState(false)
  const [includePatterns, setIncludePatterns] = React.useState('')
  const [excludePatterns, setExcludePatterns] = React.useState('')
  /** 过滤器区（包含/排除 glob）展开态。 */
  const [filtersOpen, setFiltersOpen] = React.useState(false)
  /** 折叠态的文件分组路径集合（结果树展开策略由面板统一持有，供折叠/展开全部）。 */
  const [collapsedFiles, setCollapsedFiles] = React.useState<Set<string>>(new Set())
  const searchInputRef = React.useRef<InputRef | null>(null)

  const search = useWorkspaceSearch()
  const searching = search.status === 'searching'

  const workspaceRoot = scope?.workspaceRoot || defaultWorkspaceRoot
  const scopeRootPath = scope && scope.rootPath && scope.rootPath !== '/' ? scope.rootPath : ''
  const hasEntries = (registry?.workspaces.length ?? 0) > 0

  /** 发起搜索：以当前输入区全部选项与解析后的范围调用状态机。 */
  const runSearch = React.useCallback(() => {
    if (!workspaceRoot) return
    void search.run({
      pattern: query,
      useRegex,
      caseSensitive,
      wholeWord,
      includePatterns: includePatterns.trim() || undefined,
      excludePatterns: excludePatterns.trim() || undefined,
      workspaceRoot,
      rootPath: scopeRootPath,
    })
  }, [caseSensitive, excludePatterns, includePatterns, query, scopeRootPath, search, useRegex, workspaceRoot])

  /**
   * 新结果落地时重置折叠策略：
   * 命中数 > 10 的文件默认折叠；全部结果只有 1 个文件且命中 < 50 时全部展开。
   */
  React.useEffect(() => {
    const result = search.result
    if (!result) {
      setCollapsedFiles(new Set())
      return
    }
    if (result.files.length === 1 && result.matchCount < 50) {
      setCollapsedFiles(new Set())
      return
    }
    setCollapsedFiles(new Set(
      result.files
        .filter((file) => (file.matches?.length ?? 0) > 10)
        .map((file) => file.path),
    ))
  }, [search.result])

  /**
   * 资源管理器跳转：预填搜索范围并聚焦输入框；
   * 面板从隐藏变为显示时（SIDEBAR_PANEL_SHOWN）同样聚焦，便于直接输入。
   */
  React.useEffect(() => {
    const focusInput = () => {
      requestAnimationFrame(() => {
        searchInputRef.current?.focus()
      })
    }
    const unsubscribeSearchRequested = domainEventBus.subscribe(
      DOMAIN_EVENTS.WORKSPACE_SEARCH_PANEL_REQUESTED,
      ({ workspaceRoot: requestedWorkspaceRoot, rootPath, label }) => {
        setScope({ workspaceRoot: requestedWorkspaceRoot, rootPath, label })
        focusInput()
      },
    )
    const unsubscribePanelShown = domainEventBus.subscribe(DOMAIN_EVENTS.SIDEBAR_PANEL_SHOWN, ({ panelId }) => {
      if (panelId !== 'search') return
      focusInput()
    })
    return () => {
      unsubscribeSearchRequested()
      unsubscribePanelShown()
    }
  }, [])

  /** 命中行点击：打开文件并定位到行（与资源管理器打开文件同一通道）。 */
  const handleOpenHit = React.useCallback((filePath: string, hit: WorkspaceContentSearchHit) => {
    if (!workspaceRoot) return
    openGlobalFileTab(
      { workspaceRoot, filePath: toBusinessAbsolutePath(filePath) },
      { mode: 'readwrite', lineNumber: hit.lineNumber },
    )
  }, [openGlobalFileTab, workspaceRoot])

  const toggleFileCollapsed = React.useCallback((filePath: string) => {
    setCollapsedFiles((current) => {
      const next = new Set(current)
      if (next.has(filePath)) {
        next.delete(filePath)
      } else {
        next.add(filePath)
      }
      return next
    })
  }, [])

  const collapseAll = React.useCallback(() => {
    setCollapsedFiles((current) => {
      const result = search.result
      if (!result) return current
      return new Set(result.files.map((file) => file.path))
    })
  }, [search.result])

  const expandAll = React.useCallback(() => {
    setCollapsedFiles(new Set())
  }, [])

  /** 输入框键盘：Enter 触发搜索；聚焦时 Alt+C / Alt+W / Alt+R 切换三个匹配开关。 */
  const handleSearchInputKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.altKey && !event.ctrlKey && !event.metaKey) {
      const key = event.key.toLowerCase()
      if (key === 'c') {
        event.preventDefault()
        setCaseSensitive((current) => !current)
        return
      }
      if (key === 'w') {
        event.preventDefault()
        setWholeWord((current) => !current)
        return
      }
      if (key === 'r') {
        event.preventDefault()
        setUseRegex((current) => !current)
        return
      }
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      runSearch()
    }
  }

  const result = search.result
  const showNoMatch = search.status === 'done' && result !== null && result.matchCount === 0
  const showSearchingHint = searching && !result
  const showIntroHint = !hasEntries || (search.status === 'idle' && !result)

  return (
    <div style={panelStyle}>
      <div style={headerStyle}>
        <span style={headerTitleStyle}>搜索</span>
        <div style={headerActionsStyle}>
          <IconButton
            variant="ghost"
            size="sm"
            icon={<ChevronDownIcon size={14} style={chevronCollapsedTransformStyle} />}
            aria-label="折叠全部"
            title="折叠全部"
            onClick={collapseAll}
          />
          <IconButton
            variant="ghost"
            size="sm"
            icon={<ChevronDownIcon size={14} />}
            aria-label="展开全部"
            title="展开全部"
            onClick={expandAll}
          />
        </div>
      </div>

      <div style={inputAreaStyle}>
        <div style={inputRowStyle}>
          <Input
            ref={searchInputRef}
            type="text"
            value={query}
            placeholder="搜索（支持正则）"
            status={search.regexInvalid ? 'error' : undefined}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={handleSearchInputKeyDown}
            style={searchInputStyle}
            suffix={(
              <span style={inputTogglesStyle}>
                <SearchToggleButton
                  label="‥"
                  title="切换包含/排除文件过滤器"
                  active={filtersOpen}
                  onClick={() => setFiltersOpen((current) => !current)}
                />
                <SearchToggleButton
                  label="Aa"
                  title="区分大小写 (Alt+C)"
                  active={caseSensitive}
                  onClick={() => setCaseSensitive((current) => !current)}
                />
                <SearchToggleButton
                  label="ab|"
                  title="全字匹配 (Alt+W)"
                  active={wholeWord}
                  onClick={() => setWholeWord((current) => !current)}
                />
                <SearchToggleButton
                  label=".*"
                  title="使用正则表达式 (Alt+R)"
                  active={useRegex}
                  onClick={() => setUseRegex((current) => !current)}
                />
              </span>
            )}
          />
          {searching ? (
            <>
              <InlineSpinner size={14} />
              <IconButton
                variant="ghost"
                size="sm"
                icon={<CloseIcon size={13} />}
                aria-label="取消搜索"
                title="取消搜索"
                onClick={search.cancel}
              />
            </>
          ) : (
            <IconButton
              variant="ghost"
              size="sm"
              icon={<SearchIcon size={14} />}
              aria-label="搜索"
              title="搜索（Enter）"
              onClick={runSearch}
            />
          )}
        </div>

        {filtersOpen ? (
          <div style={filtersStyle}>
            <Input
              type="text"
              aria-label="包含的文件"
              placeholder="包含的文件，例：*.ts, src/**"
              value={includePatterns}
              onChange={(event) => setIncludePatterns(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  runSearch()
                }
              }}
              style={filterInputStyle}
            />
            <Input
              type="text"
              aria-label="排除的文件"
              placeholder="排除的文件，例：*.css, dist/**"
              value={excludePatterns}
              onChange={(event) => setExcludePatterns(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  runSearch()
                }
              }}
              style={filterInputStyle}
            />
          </div>
        ) : null}

        {scope && scopeRootPath ? (
          <div style={scopeRowStyle} title={`${scope.workspaceRoot}${toBusinessAbsolutePath(scopeRootPath)}`}>
            <span style={scopeLabelStyle}>搜索范围：{scopeRootPath}</span>
            <IconButton
              variant="ghost"
              size="sm"
              icon={<CloseIcon size={12} />}
              aria-label="恢复为工作区根"
              title="恢复为工作区根"
              onClick={() => setScope(null)}
            />
          </div>
        ) : null}

        {search.error ? <div style={errorStyle}>{search.error}</div> : null}
      </div>

      <SidebarScrollArea style={resultsAreaStyle}>
        {showIntroHint ? (
          <div style={centerHintStyle}>
            {!hasEntries ? '暂无注册工作区，连接后可搜索。' : '输入关键词搜索工作区文件内容'}
          </div>
        ) : null}
        {showSearchingHint ? <div style={centerHintStyle}>搜索中…</div> : null}
        {showNoMatch ? <div style={centerHintStyle}>未找到匹配</div> : null}
        {result && result.matchCount > 0 ? (
          <SearchResultsTree
            result={result}
            collapsedFiles={collapsedFiles}
            onToggleFile={toggleFileCollapsed}
            onOpenHit={handleOpenHit}
          />
        ) : null}
      </SidebarScrollArea>

      {search.summary ? <div style={summaryStyle}>{search.summary}</div> : null}
    </div>
  )
}

/**
 * 输入框内嵌的小型开关按钮（Aa / ab| / .* / ‥）。
 * onMouseDown 阻止默认行为，点击后焦点留在输入框内（Enter 可继续触发搜索）。
 */
function SearchToggleButton({
  label,
  title,
  active,
  onClick,
}: {
  label: string
  title: string
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      title={title}
      aria-pressed={active}
      onMouseDown={(event) => event.preventDefault()}
      onClick={onClick}
      style={{
        ...toggleButtonStyle,
        ...(active ? toggleButtonActiveStyle : null),
      }}
    >
      {label}
    </button>
  )
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

const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  padding: '10px 12px 6px',
  flexShrink: 0,
}

const headerTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  letterSpacing: 0.3,
}

const headerActionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 2,
}

const inputAreaStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '0 10px 8px',
  flexShrink: 0,
}

const inputRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  minWidth: 0,
}

const searchInputStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontSize: 'var(--text-xs)',
}

const inputTogglesStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 2,
}

const toggleButtonStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
  fontFamily: 'var(--font-mono)',
  lineHeight: 1.4,
  padding: '1px 4px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
}

const toggleButtonActiveStyle: React.CSSProperties = {
  background: 'var(--accent-blue-dim)',
  color: 'var(--accent-blue)',
  fontWeight: 700,
}

const filtersStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  paddingLeft: 14,
}

const filterInputStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
}

const scopeRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  padding: '2px 6px',
  borderRadius: 'var(--radius-sm)',
  border: '1px solid var(--border-light)',
  background: 'var(--bg-primary)',
  minWidth: 0,
}

const scopeLabelStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-secondary)',
  fontFamily: 'var(--font-mono)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const errorStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-red)',
  wordBreak: 'break-all',
}

const resultsAreaStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  padding: '0 10px',
}

const centerHintStyle: React.CSSProperties = {
  padding: '32px 12px',
  textAlign: 'center',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const summaryStyle: React.CSSProperties = {
  flexShrink: 0,
  padding: '6px 12px',
  borderTop: '1px solid var(--border-light)',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const chevronCollapsedTransformStyle: React.CSSProperties = {
  transform: 'rotate(-90deg)',
}
