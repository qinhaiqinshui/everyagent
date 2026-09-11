import React from 'react'
import { Input, Select } from 'antd'
import type { InputRef, RefSelectProps } from 'antd'
import { useWorkspaceShell } from '../app/WorkspaceShellContext'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { hubSession, type WorkerInfo } from '@/hub/session'
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
 * null = 当前选中工作区的根目录。
 */
interface SearchScope {
  /** 范围所属工作区根（worker 机器绝对路径）。 */
  workspaceRoot: string
  /** 搜索根路径（业务绝对形态；空串表示工作区根）。 */
  rootPath: string
  /** 范围显示名（目录名或「工作区根目录」）。 */
  label: string
}

/** 下拉选项里展示的工作区短名：取路径末段（盘符根/斜杠根退化为全路径）。 */
function displayRootLabel(root: string): string {
  const trimmed = root.replace(/[\\/]+$/, '')
  return trimmed.split(/[\\/]/).pop() || trimmed
}

/**
 * 搜索侧边栏面板（活动栏一级入口，仿 VSCode 搜索面板）。
 *
 * - 绑定：顶部 worker / 工作区两个必选下拉（多 worker 显式归属，注册表就绪后自动
 *   落定默认项），未选齐前搜索不可发起；
 * - 输入区：搜索词 + Aa（大小写）/ ab|（全字）/ .*（正则）三个开关（聚焦时支持
 *   Alt+C / Alt+W / Alt+R 切换）+ ‥ 展开「包含/排除文件」glob 过滤器；
 * - 非法正则：输入框红框 + 错误提示，不触发搜索；
 * - 范围：当前选中工作区根；资源管理器右键「搜索」经 WORKSPACE_SEARCH_PANEL_REQUESTED
 *   事件跳转预填 worker/工作区/目录，可「×」恢复为工作区根；
 * - 结果树：SearchResultsTree 按文件分组渲染，命中行点击打开文件并定位到行。
 */
export default function SearchSidebarPanel() {
  const { openGlobalFileTab } = useWorkspaceShell()
  const [registry, setRegistry] = React.useState(workspaceRegistry.current)
  /** worker 目录快照（与设置页同款订阅）：worker 下拉的候选来源与排序基准。 */
  const [directory, setDirectory] = React.useState<WorkerInfo[]>(hubSession.directory)

  React.useEffect(() => workspaceRegistry.subscribe(setRegistry), [])
  React.useEffect(() => {
    const unsubscribe = hubSession.onDirectory(setDirectory)
    return () => {
      unsubscribe()
    }
  }, [])

  /** 显式选中的 worker / 工作区绑定（必选；不再用注册表首项隐式兜底）。 */
  const [workerId, setWorkerId] = React.useState('')
  const [workspaceRoot, setWorkspaceRoot] = React.useState('')
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
  const workerSelectRef = React.useRef<RefSelectProps | null>(null)

  const search = useWorkspaceSearch()
  /** hook 内 useCallback 的稳定引用：切换绑定/事件预填时清旧结果。 */
  const searchReset = search.reset
  const searching = search.status === 'searching'

  /**
   * worker 候选：目录里拥有至少一个非 missing 工作区的 worker（注册表镜像只含已连
   * worker，离线/禁用的天然不在内），顺序跟随目录；registry 快照仅作重算触发信号。
   */
  const workerOptions = React.useMemo(
    () => directory.filter((worker) =>
      workspaceRegistry.workspacesOf(worker.workerId).some((entry) => !entry.missing)),
    [directory, registry],
  )

  /** 当前 worker 的可搜索工作区（过滤 missing；registry 快照仅作重算触发信号）。 */
  const workspaceOptions = React.useMemo(
    () => (workerId ? workspaceRegistry.workspacesOf(workerId).filter((entry) => !entry.missing) : []),
    [registry, workerId],
  )

  /** scope 必须落在当前选中工作区内，否则视为绑定切换残留的脏数据，不生效。 */
  const activeScope = scope && scope.workspaceRoot === workspaceRoot ? scope : null
  const scopeRootPath = activeScope && activeScope.rootPath && activeScope.rootPath !== '/' ? activeScope.rootPath : ''
  /** 必选约束：worker 与工作区都选中后才可发起搜索。 */
  const bindingReady = Boolean(workerId && workspaceRoot)

  /** worker 下拉选项：label=workerId；事件预填的未注册 worker 补「（未注册）」占位展示。 */
  const workerSelectOptions = React.useMemo(() => {
    const options = workerOptions.map((worker) => ({ value: worker.workerId, label: worker.workerId }))
    if (workerId && !workerOptions.some((worker) => worker.workerId === workerId)) {
      options.push({ value: workerId, label: `${workerId}（未注册）` })
    }
    return options
  }, [workerId, workerOptions])

  /** 工作区下拉选项：label=路径末段短名、title=全路径；未注册选中值同样补占位。 */
  const workspaceSelectOptions = React.useMemo(() => {
    const options = workspaceOptions.map((entry) => ({
      value: entry.root,
      label: displayRootLabel(entry.root),
      title: entry.root,
    }))
    if (workspaceRoot && !workspaceOptions.some((entry) => entry.root === workspaceRoot)) {
      options.push({
        value: workspaceRoot,
        label: `${displayRootLabel(workspaceRoot)}（未注册）`,
        title: workspaceRoot,
      })
    }
    return options
  }, [workspaceOptions, workspaceRoot])

  /** 指定 worker 的首个非 missing 工作区根（默认落定与切换时的重置目标）。 */
  const firstRootOf = React.useCallback(
    (id: string) => workspaceRegistry.workspacesOf(id).find((entry) => !entry.missing)?.root ?? '',
    [],
  )

  /**
   * 默认落定与失效切换：
   * - 绑定为空（未选过）时自动选中首个候选 worker + 其首个非 missing 工作区，只在
   *   空值时补齐，不覆盖用户手动选择；
   * - 当前选中 worker 的工作区全部失效（上一轮可选、本轮不可选）时，自动切换到下
   *   一个可用 worker（全无则清空待选）；事件预填的未注册 worker 从未「可选」，保持原样。
   */
  const prevWorkerCandidateIdsRef = React.useRef<Set<string>>(new Set())
  React.useEffect(() => {
    const candidateIds = new Set(workerOptions.map((worker) => worker.workerId))
    const prev = prevWorkerCandidateIdsRef.current
    prevWorkerCandidateIdsRef.current = candidateIds
    if (!workerId) {
      const firstWorkerId = workerOptions[0]?.workerId ?? ''
      if (firstWorkerId) {
        setWorkerId(firstWorkerId)
        setWorkspaceRoot(firstRootOf(firstWorkerId))
      }
      return
    }
    if (prev.has(workerId) && !candidateIds.has(workerId)) {
      const nextWorkerId = workerOptions[0]?.workerId ?? ''
      setWorkerId(nextWorkerId)
      setWorkspaceRoot(nextWorkerId ? firstRootOf(nextWorkerId) : '')
      setScope(null)
      searchReset()
      return
    }
    if (!workspaceRoot && candidateIds.has(workerId)) {
      const firstRoot = firstRootOf(workerId)
      if (firstRoot) setWorkspaceRoot(firstRoot)
    }
  }, [firstRootOf, registry, searchReset, workerId, workerOptions, workspaceRoot])

  /** 手动切换 worker：工作区重置为该 worker 首个非 missing 工作区，范围与旧结果清空。 */
  const handleWorkerChange = React.useCallback((nextWorkerId: string) => {
    if (!nextWorkerId || nextWorkerId === workerId) return
    setWorkerId(nextWorkerId)
    setWorkspaceRoot(firstRootOf(nextWorkerId))
    setScope(null)
    searchReset()
  }, [firstRootOf, searchReset, workerId])

  /** 手动切换工作区：子目录范围与旧结果清空。 */
  const handleWorkspaceChange = React.useCallback((nextRoot: string) => {
    if (!nextRoot || nextRoot === workspaceRoot) return
    setWorkspaceRoot(nextRoot)
    setScope(null)
    searchReset()
  }, [searchReset, workspaceRoot])

  /** 发起搜索：以当前输入区全部选项与解析后的范围调用状态机（绑定未选齐时守卫不发起）。 */
  const runSearch = React.useCallback(() => {
    if (!bindingReady) return
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
  }, [bindingReady, caseSensitive, excludePatterns, includePatterns, query, scopeRootPath, search, useRegex, workspaceRoot])

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
   * 资源管理器跳转：以事件为准同时落定 worker/工作区绑定与搜索范围并聚焦输入框
   * （worker/root 不在候选里也保留，下拉补「（未注册）」选项展示）；
   * 面板从隐藏变为显示时（SIDEBAR_PANEL_SHOWN）聚焦——已选工作区聚焦输入框，
   * 否则聚焦 worker 下拉（引导先完成绑定）。
   */
  const workspaceRootRef = React.useRef(workspaceRoot)
  workspaceRootRef.current = workspaceRoot

  React.useEffect(() => {
    const focusInput = () => {
      requestAnimationFrame(() => {
        searchInputRef.current?.focus()
      })
    }
    const unsubscribeSearchRequested = domainEventBus.subscribe(
      DOMAIN_EVENTS.WORKSPACE_SEARCH_PANEL_REQUESTED,
      ({ workerId: requestedWorkerId, workspaceRoot: requestedWorkspaceRoot, rootPath, label }) => {
        setWorkerId(requestedWorkerId)
        setWorkspaceRoot(requestedWorkspaceRoot)
        setScope({ workspaceRoot: requestedWorkspaceRoot, rootPath, label })
        // 绑定随事件变化，旧工作区结果作废。
        searchReset()
        focusInput()
      },
    )
    const unsubscribePanelShown = domainEventBus.subscribe(DOMAIN_EVENTS.SIDEBAR_PANEL_SHOWN, ({ panelId }) => {
      if (panelId !== 'search') return
      if (workspaceRootRef.current) {
        focusInput()
        return
      }
      requestAnimationFrame(() => {
        workerSelectRef.current?.focus()
      })
    })
    return () => {
      unsubscribeSearchRequested()
      unsubscribePanelShown()
    }
  }, [searchReset])

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
  const showIntroHint = !bindingReady || (search.status === 'idle' && !result)
  /** 空态引导文案：区分「暂无 worker/工作区」与「输入关键词搜索」。 */
  const introHint = !bindingReady
    ? (!workerOptions.length && !workerId
      ? '暂无可搜索的 worker，连接并注册工作区后可搜索。'
      : !workerId
        ? '请先选择 worker。'
        : workspaceOptions.length
          ? '请先选择工作区。'
          : '暂无可搜索的工作区。')
    : '输入关键词搜索工作区文件内容'

  return (
    <div style={panelStyle}>
      <div style={selectAreaStyle}>
        <Select
          ref={workerSelectRef}
          aria-label="worker"
          value={workerId || undefined}
          placeholder={workerOptions.length ? '选择 worker' : '暂无可搜索的 worker'}
          popupMatchSelectWidth={false}
          onChange={handleWorkerChange}
          options={workerSelectOptions}
          style={selectStyle}
        />
        <Select
          aria-label="工作区"
          value={workspaceRoot || undefined}
          placeholder="选择工作区"
          popupMatchSelectWidth={false}
          onChange={handleWorkspaceChange}
          options={workspaceSelectOptions}
          style={selectStyle}
        />
      </div>

      <div style={inputAreaStyle}>
        <div style={inputRowStyle}>
          <Input
            ref={searchInputRef}
            type="text"
            value={query}
            placeholder={bindingReady ? '搜索（支持正则）' : '请先选择 worker 与工作区'}
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
              disabled={!bindingReady}
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

        {activeScope && scopeRootPath ? (
          <div style={scopeRowStyle} title={`${activeScope.workspaceRoot}${toBusinessAbsolutePath(scopeRootPath)}`}>
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
        {showIntroHint ? <div style={centerHintStyle}>{introHint}</div> : null}
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

      <div style={footerStyle}>
        <span style={footerTextStyle}>{search.summary}</span>
        <div style={footerActionsStyle}>
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

const selectAreaStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '10px 10px 0',
  flexShrink: 0,
}

const selectStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 0,
  fontSize: 'var(--text-xs)',
}

const inputAreaStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '6px 10px 8px',
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

const footerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  flexShrink: 0,
  padding: '4px 10px',
  borderTop: '1px solid var(--border-light)',
}

const footerTextStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const footerActionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 2,
}

const chevronCollapsedTransformStyle: React.CSSProperties = {
  transform: 'rotate(-90deg)',
}
