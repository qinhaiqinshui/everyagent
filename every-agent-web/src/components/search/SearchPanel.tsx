import React from 'react'
import { Input, InputNumber, Radio, Select } from 'antd'
import type { InputRef } from 'antd'
import type { SearchFilterField } from '@everyagent/plugin-api'
import { useWorkspaceShell } from '../app/WorkspaceShellContext'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { taskStore } from '@/task/taskStore'
import { DRAFT_TASK_ID } from '@/components/task/taskChatDraft'
import { readRecentSelection } from '@/utils/textSelection'
import { normalizeWorkspaceRelativePath, toBusinessAbsolutePath } from '@/platform/fs/pathUtils'
import { searchWorkerIdOf } from '@/query/search'
import type { WorkspaceTab } from '@/types'
import type { UiSearchTypeDefinition } from '@/plugin/types'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import SidebarScrollArea from '../shared/SidebarScrollArea'
import { IconButton, InlineSpinner } from '@/components/shared/ui'
import MoreActionsButton, { type MoreActionItem } from '../shared/MoreActionsButton'
import { ChevronDownIcon, CloseIcon } from '../shared/AppGlyphs'
import SearchResultsView from './SearchResultsView'
import { useWorkspaceSearch } from './useWorkspaceSearch'
import { buildSearchResultGroups } from './searchTypes/groupAdapter'
import {
  ALL_SEARCH_TYPE_ID,
  FILE_CONTENT_KIND,
  TASK_KIND,
  isAllSearchType,
  listSearchTypes,
  mapKindsToTypes,
  normalizeSearchTypeId,
  resolveSearchType,
} from './searchTypes'

/** 打开信号过期阈值：距上次打开超过该时长，认为搜索框内容已过期需清空。 */
const SEARCH_PANEL_STALE_MS = 5 * 60 * 1000

/**
 * 搜索面板核心组件。
 *
 * 侧边栏与双击 Shift 弹窗共用本组件，各自独立实例（内部状态与「上次打开时间」按
 * 实例隔离），仅 `variant` 决定类型选择器的呈现形态（侧边栏下拉 / 弹窗无边框按钮行）。
 *
 * 重构要点（搜索类型注册表 + 统一 search）：
 * - 类型来自注册表（内置三类 + 插件经 `ui.search_types` 注册），「全部」伪类型的
 *   `kinds` 为空 = 不传 = 全部已注册类型（含任务）；
 * - **无 worker 选择器**：worker 由 `workspaceRegistry.workerIdOfRoot(workspaceRoot)`
 *   反查，无归属时报可读错误、禁止发起；
 * - 过滤区**按当前类型的 `filters` schema 渲染**（核心默认渲染器按 `type` 出控件；
 *   `section:'more'` 收在「更多」菜单；类型提供 `FilterView` 时整块交给插件）；
 *   值按 `${kind}.${field}` 装进不透明 `filters` 袋透传，核心不再追加 `.git`、不拼 glob；
 * - 结果区：默认适配器把平铺命中项按 `(kind, groupKey)` 分组交给通用结果树；类型提供
 *   `ResultView` 时该类型区块交给插件组件渲染。
 */
interface SearchPanelProps {
  /** 打开信号：宿主每次「打开」面板时递增；初始 0 不触发。 */
  openSignal?: number
  /** 提供时（弹窗模式）命中跳转打开文件/任务后请求关闭宿主。 */
  onRequestClose?: () => void
  /** 是否监听资源管理器右键「搜索」跳转事件（仅侧边栏实例为 true）。 */
  listenWorkspaceSearchRequested?: boolean
  /** 呈现形态：侧边栏（类型下拉）/ 弹窗（无边框按钮行）。 */
  variant?: 'sidebar' | 'modal'
}

/** 命名字段绑定：类型 + 字段声明 + 命名空间键（`${kind}.${field}`）。 */
interface FieldBinding {
  type: UiSearchTypeDefinition
  field: SearchFilterField
  nsKey: string
}

/** 下拉选项里展示的工作区短名：取路径末段（盘符根/斜杠根退化为全路径）。 */
function displayRootLabel(root: string): string {
  const trimmed = root.replace(/[\\/]+$/, '')
  return trimmed.split(/[\\/]/).pop() || trimmed
}

/** 字段命名空间键：`${kind}.${field}`（类型多 kind 时取首个 kind，与后端 provider 约定一致）。 */
function nsKeyOf(type: UiSearchTypeDefinition, field: SearchFilterField): string {
  return `${type.kinds[0] ?? type.id}.${field.key}`
}

/** 空值判定（空串 / null / undefined 视为未设置）。 */
function isEmptyFilterValue(value: unknown): boolean {
  return value === '' || value === null || value === undefined
}

export default function SearchPanel({
  openSignal = 0,
  onRequestClose,
  listenWorkspaceSearchRequested = false,
  variant = 'sidebar',
}: SearchPanelProps) {
  const { openGlobalFileTab, openTaskChatTab, activeWorkspaceTab } = useWorkspaceShell()
  const [registry, setRegistry] = React.useState(workspaceRegistry.current)

  React.useEffect(() => workspaceRegistry.subscribe(setRegistry), [])

  /** 订阅插件扩展点版本号：插件注册搜索类型即上屏（`listSearchTypes` 现读注册表）。 */
  const pluginExtensionsVersion = React.useSyncExternalStore(
    pluginDispatcher.subscribeExtensionsChanged,
    pluginDispatcher.getExtensionsVersion,
  )
  const searchTypes = React.useMemo(() => listSearchTypes(), [pluginExtensionsVersion])

  /** 选中工作区根（worker 与 workspaceId 均由注册表反查/交给后端）。 */
  const [workspaceRoot, setWorkspaceRoot] = React.useState('')
  /** 选中类型 id（默认「全部」）。 */
  const [typeId, setTypeId] = React.useState(ALL_SEARCH_TYPE_ID)
  const [query, setQuery] = React.useState('')
  /** 过滤值（键 = `${kind}.${field}` 命名空间）。 */
  const [filterValues, setFilterValues] = React.useState<Record<string, unknown>>({})
  /** 「更多」菜单里已展开的字段（键 = 命名空间键）。 */
  const [openMoreFields, setOpenMoreFields] = React.useState<Set<string>>(new Set())
  /** 折叠态分组键集合。 */
  const [collapsedKeys, setCollapsedKeys] = React.useState<Set<string>>(new Set())
  const searchInputRef = React.useRef<InputRef | null>(null)

  const search = useWorkspaceSearch()
  const searchReset = search.reset
  const searching = search.status === 'searching'

  /** 工作区候选：全部非 missing 已注册工作区（多 worker 合并）。 */
  const workspaceOptions = React.useMemo(
    () => (registry?.workspaces ?? [])
      .filter((entry) => !entry.missing)
      .map((entry) => ({ value: entry.root, label: displayRootLabel(entry.root), title: entry.root })),
    [registry],
  )

  /** worker 反查：由当前选中工作区根判定归属，无归属即不可搜索（报可读错误）。 */
  const workspaceWorkerId = React.useMemo(
    () => (workspaceRoot ? searchWorkerIdOf(workspaceRoot) : undefined),
    [workspaceRoot, registry, pluginExtensionsVersion],
  )
  /** 必选约束：选中工作区且其归属 worker 可解析后才可发起搜索。 */
  const bindingReady = Boolean(workspaceRoot && workspaceWorkerId)

  /** 当前选中类型定义（未知 id 回落「全部」）。 */
  const selectedType = React.useMemo(
    () => resolveSearchType(typeId, searchTypes),
    [typeId, searchTypes],
  )
  const allSelected = isAllSearchType(selectedType)

  /** 默认落定与失效切换：未选 / 失效则落首个候选工作区并清旧结果。 */
  React.useEffect(() => {
    if (workspaceOptions.length === 0) {
      if (workspaceRoot) {
        setWorkspaceRoot('')
        searchReset()
      }
      return
    }
    if (!workspaceRoot || !workspaceOptions.some((option) => option.value === workspaceRoot)) {
      setWorkspaceRoot(workspaceOptions[0].value)
      searchReset()
    }
  }, [workspaceOptions, workspaceRoot, searchReset])

  /** 过滤区参与渲染的类型组（「全部」= 全部有字段声明的类型；单类型 = 该类型）。 */
  const activeTypeGroups = React.useMemo(() => {
    if (allSelected) {
      return searchTypes.filter((type) => (
        type.id !== ALL_SEARCH_TYPE_ID && ((type.filters?.length ?? 0) > 0 || Boolean(type.FilterView))
      ))
    }
    return (selectedType.filters?.length ?? 0) > 0 || selectedType.FilterView ? [selectedType] : []
  }, [allSelected, searchTypes, selectedType])

  /** 走默认渲染器的类型组（无 FilterView）。 */
  const groupsWithDefault = React.useMemo(
    () => activeTypeGroups.filter((type) => !type.FilterView),
    [activeTypeGroups],
  )
  /** 走插件 FilterView 的类型组。 */
  const groupsWithCustomView = React.useMemo(
    () => activeTypeGroups.filter((type) => Boolean(type.FilterView)),
    [activeTypeGroups],
  )

  const inlineBindings = React.useMemo<FieldBinding[]>(
    () => groupsWithDefault.flatMap((type) => (type.filters ?? [])
      .filter((field) => (field.section ?? 'inline') !== 'more')
      .map((field) => ({ type, field, nsKey: nsKeyOf(type, field) }))),
    [groupsWithDefault],
  )
  const moreBindings = React.useMemo<FieldBinding[]>(
    () => groupsWithDefault.flatMap((type) => (type.filters ?? [])
      .filter((field) => field.section === 'more')
      .map((field) => ({ type, field, nsKey: nsKeyOf(type, field) }))),
    [groupsWithDefault],
  )

  const setFieldValue = React.useCallback((nsKey: string, value: unknown) => {
    setFilterValues((current) => ({ ...current, [nsKey]: value }))
  }, [])

  /** 组装不透明 `filters` 袋（核心不解释内容；path 字段归一为工作区相对路径）。 */
  const effectiveFilters = React.useMemo(() => {
    const out: Record<string, unknown> = {}
    for (const binding of [...inlineBindings, ...moreBindings]) {
      const raw = filterValues[binding.nsKey]
      if (isEmptyFilterValue(raw)) continue
      if (binding.field.type === 'boolean' && raw === false) continue
      const value = binding.field.type === 'path' ? normalizeWorkspaceRelativePath(String(raw)) : raw
      if (value === '') continue
      out[binding.nsKey] = value
    }
    // FilterView 自定义字段：值原样透传（插件自行处理语义与归一）。
    for (const type of groupsWithCustomView) {
      for (const kind of type.kinds) {
        const prefix = `${kind}.`
        for (const [key, value] of Object.entries(filterValues)) {
          if (key.startsWith(prefix) && !isEmptyFilterValue(value) && value !== false) {
            out[key] = value
          }
        }
      }
    }
    return out
  }, [filterValues, inlineBindings, moreBindings, groupsWithCustomView])

  /** 命中词是否按正则解释（任一字段声明 `isRegex` 为真）；用于非法正则预检。 */
  const regexEnabled = React.useMemo(() => {
    const byBinding = [...inlineBindings, ...moreBindings]
      .some((binding) => binding.field.key === 'isRegex' && Boolean(filterValues[binding.nsKey]))
    const byView = groupsWithCustomView
      .some((type) => type.kinds.some((kind) => Boolean(filterValues[`${kind}.isRegex`])))
    return byBinding || byView
  }, [filterValues, inlineBindings, moreBindings, groupsWithCustomView])

  /** kind → 拥有它的类型（结果分组归属与 `ResultView` 派发）。 */
  const typeByKind = React.useMemo(() => mapKindsToTypes(searchTypes), [searchTypes])

  /**
   * 发起搜索：以当前选中工作区、类型 kinds、过滤袋调用统一状态机。
   * 「全部」不传 kinds（= 全部已注册类型，含任务）；单类型只传该类型 kinds。
   */
  const runSearch = React.useCallback(() => {
    if (!bindingReady) return
    void search.run({
      workspace: workspaceRoot,
      pattern: query,
      kinds: allSelected ? undefined : selectedType.kinds,
      filters: effectiveFilters,
      regex: regexEnabled,
    })
  }, [allSelected, bindingReady, effectiveFilters, query, regexEnabled, search, selectedType, workspaceRoot])

  /** 切换工作区：清理旧结果（范围/过滤值保留，键按 kind 命名空间隔离）。 */
  const handleWorkspaceChange = React.useCallback((nextRoot: string) => {
    if (!nextRoot || nextRoot === workspaceRoot) return
    setWorkspaceRoot(nextRoot)
    searchReset()
  }, [searchReset, workspaceRoot])

  /** 切换类型：清理旧结果；「更多」展开态随之收敛到仍存在的字段。 */
  const handleTypeChange = React.useCallback((nextTypeId: string) => {
    if (!nextTypeId || nextTypeId === typeId) return
    setTypeId(nextTypeId)
    searchReset()
  }, [searchReset, typeId])

  /** 「更多」菜单：`section:'more'` 字段开关 + 清除全部过滤。 */
  const hasAnyFilterValue = React.useMemo(
    () => Object.values(filterValues).some((value) => !isEmptyFilterValue(value) && value !== false),
    [filterValues],
  )
  const moreItems: MoreActionItem[] = [
    ...(hasAnyFilterValue
      ? [{
        key: 'clear-filters',
        label: '清除全部过滤',
        onSelect: () => setFilterValues({}),
      }]
      : []),
    ...moreBindings.map((binding): MoreActionItem => ({
      key: `more-${binding.nsKey}`,
      label: (allSelected ? `${binding.type.label} · ` : '') + binding.field.label,
      active: openMoreFields.has(binding.nsKey),
      onSelect: () => setOpenMoreFields((current) => {
        const next = new Set(current)
        if (next.has(binding.nsKey)) {
          next.delete(binding.nsKey)
        } else {
          next.add(binding.nsKey)
        }
        return next
      }),
    })),
  ]

  /** 默认结果分组（仅不含 `ResultView` 的 kind），供折叠策略与「折叠/展开全部」使用。 */
  const defaultGroups = React.useMemo(() => {
    if (!search.result) return []
    return buildSearchResultGroups(search.result.items, { openFile: () => {}, openTask: () => {} })
      .filter((group) => !typeByKind.get(group.kind ?? '')?.ResultView)
  }, [search.result, typeByKind])

  /** 新结果落地时重置折叠策略：命中 > 10 的分组默认折叠；单组且 < 50 全部展开。 */
  React.useEffect(() => {
    const result = search.result
    if (!result) {
      setCollapsedKeys(new Set())
      return
    }
    if (defaultGroups.length === 1 && defaultGroups[0].hits.length < 50) {
      setCollapsedKeys(new Set())
      return
    }
    setCollapsedKeys(new Set(
      defaultGroups.filter((group) => group.hits.length > 10).map((group) => group.key),
    ))
  }, [search.result, defaultGroups])

  /** 搜索词实时快照：openSignal 回调/事件处理器中读取最新值，避免闭包旧值。 */
  const queryRef = React.useRef(query)
  queryRef.current = query
  /** 激活标签页实时快照：on-open 自动识别时读取最新值。 */
  const activeWorkspaceTabRef = React.useRef<WorkspaceTab | null>(activeWorkspaceTab)
  activeWorkspaceTabRef.current = activeWorkspaceTab

  /** 聚焦搜索框（rAF 等待宿主完成显示/挂载）。 */
  const focusInput = React.useCallback(() => {
    requestAnimationFrame(() => {
      searchInputRef.current?.focus()
    })
  }, [])

  /**
   * 按当前激活标签页自动识别搜索上下文：
   * - 任务标签（非草稿）：取任务所属工作区，类型切「任务内容」；
   * - 文件标签：取文件工作区，类型切「文本文件内容」；
   * - 终端标签：直接用自带工作区，类型切「文本文件内容」；
   * - 其他/无激活标签：不动现有绑定。
   */
  const applyActiveTabContext = React.useCallback((): boolean => {
    const tab = activeWorkspaceTabRef.current
    if (!tab) return false
    if (tab.tabType === 'task') {
      if (tab.taskId === DRAFT_TASK_ID) return false
      const task = taskStore.get(tab.taskId)
      if (!task?.workerId || !task.workspace) return false
      setWorkspaceRoot(task.workspace)
      setTypeId(TASK_KIND)
      searchReset()
      return true
    }
    if (tab.tabType === 'file') {
      if (!workspaceRegistry.workerIdOfRoot(tab.workspaceRoot)) return false
      setWorkspaceRoot(tab.workspaceRoot)
      setTypeId(FILE_CONTENT_KIND)
      searchReset()
      return true
    }
    if (tab.tabType === 'terminal') {
      setWorkspaceRoot(tab.workspaceRoot)
      setTypeId(FILE_CONTENT_KIND)
      searchReset()
      return Boolean(tab.workspaceRoot)
    }
    return false
  }, [searchReset])

  /**
   * on-open 流程（由 openSignal 驱动）：
   * 1. 距上次打开超 5 分钟 → 清空搜索词并重置结果；
   * 2. 更新本实例上次打开时间；
   * 3. 搜索框为空（或即将用选区填充）→ 自动识别工作区/类型；
   * 4. 存在有效选区 → 原样填入搜索框，不触发搜索；
   * 5. 聚焦搜索框。
   */
  const lastOpenedAtRef = React.useRef(0)
  const prevOpenSignalRef = React.useRef(0)
  React.useEffect(() => {
    if (openSignal === prevOpenSignalRef.current) return
    prevOpenSignalRef.current = openSignal
    const now = Date.now()
    const stale = now - lastOpenedAtRef.current > SEARCH_PANEL_STALE_MS
    lastOpenedAtRef.current = now
    if (stale) {
      setQuery('')
      queryRef.current = ''
      searchReset()
    }
    const selection = readRecentSelection()
    const willFillSelection = selection.trim().length > 0
    if (queryRef.current.trim() === '' || willFillSelection) {
      applyActiveTabContext()
    }
    if (willFillSelection) {
      setQuery(selection)
      queryRef.current = selection
    }
    focusInput()
  }, [applyActiveTabContext, focusInput, openSignal, searchReset])

  /**
   * 资源管理器跳转：以事件为准落定工作区绑定与类型（旧 `target` 值 files/tasks
   * 兼容映射到新类型 id），并把事件携带的目录写入该类型 `scope` 字段（`type:'path'`）。
   */
  React.useEffect(() => {
    if (!listenWorkspaceSearchRequested) return
    const unsubscribe = domainEventBus.subscribe(
      DOMAIN_EVENTS.WORKSPACE_SEARCH_PANEL_REQUESTED,
      ({ workspaceRoot: requestedWorkspaceRoot, rootPath, target }) => {
        const requestedTypeId = normalizeSearchTypeId(target ?? FILE_CONTENT_KIND)
        const requestedType = resolveSearchType(requestedTypeId, listSearchTypes())
        setWorkspaceRoot(requestedWorkspaceRoot)
        setTypeId(requestedType.id)
        // 目录范围下推：写入该类型 `scope` 字段（文件类声明了该字段；任务类无则忽略）。
        const scopeField = (requestedType.filters ?? []).find((field) => field.key === 'scope')
        if (scopeField && rootPath) {
          setFieldValue(nsKeyOf(requestedType, scopeField), toBusinessAbsolutePath(normalizeWorkspaceRelativePath(rootPath)))
        }
        searchReset()
        focusInput()
      },
    )
    return unsubscribe
  }, [focusInput, listenWorkspaceSearchRequested, searchReset, setFieldValue])

  /** 打开文件（可选定位到行）；弹窗模式随之关闭。 */
  const handleOpenFile = React.useCallback((filePath: string, lineNumber?: number) => {
    if (!workspaceRoot) return
    const businessPath = toBusinessAbsolutePath(normalizeWorkspaceRelativePath(filePath))
    openGlobalFileTab(
      { workspaceRoot, filePath: businessPath },
      lineNumber !== undefined ? { mode: 'readwrite', lineNumber } : { mode: 'readwrite' },
    )
    onRequestClose?.()
  }, [onRequestClose, openGlobalFileTab, workspaceRoot])

  /** 打开任务聊天页；弹窗模式随之关闭。 */
  const handleOpenTask = React.useCallback((taskId: string, title?: string) => {
    openTaskChatTab({ taskId, title: title || `任务 ${taskId.slice(0, 8)}` })
    onRequestClose?.()
  }, [onRequestClose, openTaskChatTab])

  /** 分组折叠切换（键 = SearchResultGroup.key）。 */
  const toggleGroupCollapsed = React.useCallback((key: string) => {
    setCollapsedKeys((current) => {
      const next = new Set(current)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }, [])

  const collapseAll = React.useCallback(() => {
    setCollapsedKeys(new Set(defaultGroups.map((group) => group.key)))
  }, [defaultGroups])

  const expandAll = React.useCallback(() => {
    setCollapsedKeys(new Set())
  }, [])

  const hasCollapsedGroups = collapsedKeys.size > 0
  const toggleCollapseAll = React.useCallback(() => {
    if (hasCollapsedGroups) {
      expandAll()
    } else {
      collapseAll()
    }
  }, [collapseAll, expandAll, hasCollapsedGroups])

  /** 输入框键盘：Enter 触发搜索；聚焦时 Alt+C / Alt+W / Alt+R 切换对应布尔字段。 */
  const toggleBooleanFieldByKey = React.useCallback((fieldKey: string) => {
    const binding = [...inlineBindings, ...moreBindings]
      .find((item) => item.field.key === fieldKey && item.field.type === 'boolean')
    if (binding) {
      setFieldValue(binding.nsKey, !Boolean(filterValues[binding.nsKey]))
    }
  }, [filterValues, inlineBindings, moreBindings, setFieldValue])

  const handleSearchInputKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.altKey && !event.ctrlKey && !event.metaKey) {
      const key = event.key.toLowerCase()
      if (key === 'c') {
        event.preventDefault()
        toggleBooleanFieldByKey('caseSensitive')
        return
      }
      if (key === 'w') {
        event.preventDefault()
        toggleBooleanFieldByKey('wholeWord')
        return
      }
      if (key === 'r') {
        event.preventDefault()
        toggleBooleanFieldByKey('isRegex')
        return
      }
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      runSearch()
    }
  }

  /** 默认渲染器：按字段 `type` 出对应控件（值收集进 `filters` 袋）。 */
  const renderFieldControl = (binding: FieldBinding): React.ReactNode => {
    const { field, nsKey } = binding
    const value = filterValues[nsKey]
    const ariaLabel = field.label
    switch (field.type) {
      case 'boolean':
        return (
          <SearchToggleButton
            key={nsKey}
            label={field.label}
            title={field.help ?? field.label}
            active={Boolean(value)}
            onClick={() => setFieldValue(nsKey, !Boolean(value))}
          />
        )
      case 'select':
        return (
          <Select
            key={nsKey}
            aria-label={ariaLabel}
            size="small"
            allowClear
            value={value === undefined || value === '' ? undefined : value as string}
            placeholder={field.placeholder ?? field.label}
            options={field.options}
            onChange={(next) => setFieldValue(nsKey, next ?? '')}
            style={fieldControlSelectStyle}
          />
        )
      case 'radio':
        return (
          <Radio.Group
            key={nsKey}
            aria-label={ariaLabel}
            size="small"
            value={value as string | undefined}
            options={field.options}
            onChange={(event) => setFieldValue(nsKey, event.target.value)}
          />
        )
      case 'number':
        return (
          <InputNumber
            key={nsKey}
            aria-label={ariaLabel}
            size="small"
            value={typeof value === 'number' ? value : undefined}
            placeholder={field.placeholder ?? field.label}
            onChange={(next) => setFieldValue(nsKey, next ?? '')}
            style={fieldControlNumberStyle}
          />
        )
      case 'textarea':
        return (
          <Input.TextArea
            key={nsKey}
            aria-label={ariaLabel}
            autoSize={{ minRows: 1, maxRows: 4 }}
            value={value === undefined ? '' : String(value)}
            placeholder={field.placeholder ?? field.label}
            onChange={(event) => setFieldValue(nsKey, event.target.value)}
            style={filterInputStyle}
          />
        )
      case 'path':
      case 'text':
      default:
        return (
          <Input
            key={nsKey}
            aria-label={ariaLabel}
            type="text"
            value={value === undefined ? '' : String(value)}
            placeholder={field.placeholder ?? field.label}
            onChange={(event) => setFieldValue(nsKey, event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                runSearch()
              }
            }}
            style={filterInputStyle}
          />
        )
    }
  }

  const result = search.result
  const showNoMatch = search.status === 'done' && result !== null && result.items.length === 0
  const showSearchingHint = searching && !result
  const showIntroHint = !bindingReady || (search.status === 'idle' && !result)
  /** 空态引导文案：区分「无工作区」「工作区无归属」「输入引导」。 */
  const introHint = !workspaceRoot
    ? (workspaceOptions.length ? '请先选择工作区。' : '暂无可搜索的工作区，连接并注册工作区后可搜索。')
    : !workspaceWorkerId
      ? '该工作区未注册或所属 worker 离线，无法搜索。'
      : `输入关键词搜索「${selectedType.label}」`

  /** 弹窗形态：无边框、无背景色的类型按钮行。 */
  const typeButtonRow = (
    <div style={typeButtonRowStyle}>
      {searchTypes.map((type) => (
        <button
          key={type.id}
          type="button"
          aria-pressed={type.id === selectedType.id}
          onClick={() => handleTypeChange(type.id)}
          style={{
            ...typeButtonStyle,
            ...(type.id === selectedType.id ? typeButtonActiveStyle : null),
          }}
        >
          {type.label}
        </button>
      ))}
    </div>
  )

  return (
    <div style={panelStyle}>
      <div style={selectAreaStyle}>
        <Select
          aria-label="工作区"
          value={workspaceRoot || undefined}
          placeholder={workspaceOptions.length ? '选择工作区' : '暂无可搜索的工作区'}
          popupMatchSelectWidth={false}
          onChange={handleWorkspaceChange}
          options={workspaceOptions}
          style={variant === 'modal' ? modalSelectStyle : selectStyle}
        />
        {variant === 'sidebar' ? (
          <Select
            aria-label="搜索类型"
            value={selectedType.id}
            popupMatchSelectWidth={false}
            onChange={handleTypeChange}
            options={searchTypes.map((type) => ({ value: type.id, label: type.label }))}
            style={selectStyle}
          />
        ) : null}
      </div>

      {variant === 'modal' ? typeButtonRow : null}

      <div style={inputAreaStyle}>
        <div style={inputRowStyle}>
          <Input
            ref={searchInputRef}
            type="text"
            value={query}
            placeholder={!bindingReady
              ? '请先选择工作区'
              : `搜索「${selectedType.label}」`}
            status={search.regexInvalid ? 'error' : undefined}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={handleSearchInputKeyDown}
            style={searchInputStyle}
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
            <MoreActionsButton items={moreItems} title="更多操作" />
          )}
        </div>

        {(inlineBindings.length > 0 || groupsWithCustomView.length > 0) ? (
          <div style={optionsRowStyle}>
            {activeTypeGroups.map((type) => {
              if (type.FilterView) {
                const FilterView = type.FilterView
                return (
                  <FilterView
                    key={type.id}
                    fields={type.filters ?? []}
                    values={filterValues}
                    setValue={setFieldValue}
                    workspaceRoot={workspaceRoot}
                  />
                )
              }
              const inline = (type.filters ?? []).filter((field) => (field.section ?? 'inline') !== 'more')
              if (inline.length === 0) return null
              return (
                <div key={type.id} style={optionsGroupStyle}>
                  {allSelected ? <span style={optionsGroupLabelStyle}>{type.label}</span> : null}
                  {inline.map((field) => renderFieldControl({ type, field, nsKey: nsKeyOf(type, field) }))}
                </div>
              )
            })}
          </div>
        ) : null}

        {moreBindings.some((binding) => openMoreFields.has(binding.nsKey)) ? (
          <div style={filtersStyle}>
            {activeTypeGroups.map((type) => {
              if (type.FilterView) return null
              const open = (type.filters ?? [])
                .filter((field) => field.section === 'more')
                .map((field) => ({ type, field, nsKey: nsKeyOf(type, field) }))
                .filter((binding) => openMoreFields.has(binding.nsKey))
              if (open.length === 0) return null
              return (
                <div key={type.id} style={optionsGroupStyle}>
                  {allSelected ? <span style={optionsGroupLabelStyle}>{type.label}</span> : null}
                  {open.map((binding) => renderFieldControl(binding))}
                </div>
              )
            })}
          </div>
        ) : null}

        {search.error ? <div style={errorStyle}>{search.error}</div> : null}
      </div>

      <SidebarScrollArea style={resultsAreaStyle}>
        {showIntroHint ? <div style={centerHintStyle}>{introHint}</div> : null}
        {showSearchingHint ? <div style={centerHintStyle}>搜索中…</div> : null}
        {showNoMatch ? <div style={centerHintStyle}>未找到匹配</div> : null}
        {result && result.items.length > 0 ? (
          <SearchResultsView
            items={result.items}
            types={searchTypes}
            collapsedKeys={collapsedKeys}
            onToggleGroup={toggleGroupCollapsed}
            openFile={handleOpenFile}
            openTask={handleOpenTask}
            pattern={query}
            workspaceRoot={workspaceRoot}
          />
        ) : null}
      </SidebarScrollArea>

      <div style={footerStyle}>
        <span style={footerTextStyle}>{search.summary}</span>
        <div style={footerActionsStyle}>
          <IconButton
            variant="ghost"
            size="sm"
            icon={<ChevronDownIcon size={14} style={hasCollapsedGroups ? undefined : chevronCollapsedTransformStyle} />}
            aria-label={hasCollapsedGroups ? '展开全部' : '折叠全部'}
            title={hasCollapsedGroups ? '展开全部' : '折叠全部'}
            onClick={toggleCollapseAll}
          />
        </div>
      </div>
    </div>
  )
}

/**
 * 输入框内嵌的小型开关按钮（布尔字段默认渲染器）。
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
  flexDirection: 'row',
  gap: 6,
  padding: '10px 10px 0',
  flexShrink: 0,
}

/** 工作区与类型选择器同行并排：各占一半（flex:1），minWidth:0 允许长名截断省略。 */
const selectStyle: React.CSSProperties = {
  flex: 1,
  width: 'auto',
  minWidth: 0,
  fontSize: 'var(--text-xs)',
}

/** 弹窗形态：工作区选择器整行宽度（类型走按钮行）。 */
const modalSelectStyle: React.CSSProperties = {
  flex: 1,
  width: 'auto',
  minWidth: 0,
  fontSize: 'var(--text-xs)',
}

/** 弹窗类型按钮行：无边框、无背景色。 */
const typeButtonRowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'row',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 2,
  padding: '6px 10px 0',
  flexShrink: 0,
}

const typeButtonStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.5,
  padding: '2px 6px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
}

const typeButtonActiveStyle: React.CSSProperties = {
  color: 'var(--accent-blue)',
  fontWeight: 700,
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

/** 选项行（inline 字段）：按类型分组。 */
const optionsRowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
}

const optionsGroupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'row',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 4,
  minWidth: 0,
}

const optionsGroupLabelStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  flexShrink: 0,
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
  minWidth: 160,
}

const fieldControlSelectStyle: React.CSSProperties = {
  minWidth: 120,
  fontSize: 'var(--text-xs)',
}

const fieldControlNumberStyle: React.CSSProperties = {
  width: 120,
  fontSize: 'var(--text-xs)',
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