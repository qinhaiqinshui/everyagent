import React from 'react'
import { taskQueryService, type TaskListItemSnapshot } from '@/query/taskQueryService'
import SidebarScrollArea from '@/components/shared/SidebarScrollArea'
import { getDefaultRuntimeService } from '@/task'
import { taskStore } from '@/hub/taskStore'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import ListRowActions, { type ListRowActionsHandle } from '@/components/shared/ui/ListRowActions'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import { useLongPress } from '@/hooks/useLongPress'
import type { TaskListGroup } from '@/plugin/types'
import ContextBattery from '@/components/task/ContextBattery'
import { ChevronDownIcon, MoreHorizontalIcon, PlusIcon } from '@/components/shared/AppGlyphs'
import { Button, IconButton } from '@/components/shared/ui'
import ActionMenu from '@/components/shared/ui/ActionMenu'
import ConfirmDialog from '@/components/shared/ConfirmDialog'
import { Checkbox } from 'antd'

const runtimeService = getDefaultRuntimeService()

/** 触底续拉阈值:距滚动视口底部该像素内视为触底,触发下一页加载。 */
const LOAD_MORE_THRESHOLD = 120

/** 工作区组标签短名:根路径末段(盘符根/斜杠根退化为全路径,完整根见组头 title)。 */
function workspaceGroupLabel(root: string): string {
  const trimmed = root.replace(/[\\/]+$/, '')
  return trimmed.split(/[\\/]/).pop() || trimmed
}

/**
 * 将时间戳格式化为「年-月-日 时:分:秒」,24 小时制。
 * 使用本地时区组件手动拼接,避免 toLocaleString 的本地化差异(如 12 小时制 AM/PM)。
 */
function formatTaskTime(value: number | string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (num: number) => String(num).padStart(2, '0')
  const year = date.getFullYear()
  const month = pad(date.getMonth() + 1)
  const day = pad(date.getDate())
  const hours = pad(date.getHours())
  const minutes = pad(date.getMinutes())
  const seconds = pad(date.getSeconds())
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

/**
 * 相对时间文案:
 * - 1 分钟内 → 刚刚
 * - 1 小时内 → x 分钟前
 * - 24 小时内 → x 小时前
 * - 7 天内(含)→ x 天前
 * - 超过 7 天或无效时间 → 返回 null(调用方回退到绝对时间)。
 * 未来时间(时钟偏差)按「刚刚」处理。
 */
function formatRelativeTime(value: number | string): string | null {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const diff = Date.now() - date.getTime()
  if (diff < 60_000) return '刚刚'
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days <= 7) return `${days} 天前`
  return null
}

/** Task 面板打开动作输入。 */
interface TaskListSelection {
  /** 任务 ID。 */
  taskId: string
  /** 展示标题。 */
  displayTitle: string
}

/**
 * Task 面板。
 * 负责展示全局 Task 列表（按挂靠工作区分组、可折叠，架构 D16），
 * 并把打开动作统一导向 Task 聊天页。
 */
export default function TasksPanel({
  onSelect,
  onCreateNewTask,
  onDeletedTasks,
  embedded = false,
  activeTaskId,
}: {
  onSelect: (task: TaskListSelection) => void
  onCreateNewTask?: (preset?: { workspace?: string; workerId?: string }) => void
  onDeletedTasks?: (taskIds: string[]) => void
  embedded?: boolean
  activeTaskId?: string
}) {
  const [tasks, setTasks] = React.useState<TaskListItemSnapshot[]>([])
  const [error, setError] = React.useState('')
  /** 当前处于批量删除模式的工作区组 key；null = 未启用。批量删除只作用于该组任务。 */
  const [batchGroupKey, setBatchGroupKey] = React.useState<string | null>(null)
  const [selectedTaskIds, setSelectedTaskIds] = React.useState<string[]>([])
  const [deleting, setDeleting] = React.useState(false)
  const [confirmOpen, setConfirmOpen] = React.useState(false)
  /** 单任务删除确认弹窗的目标任务；null = 未打开。 */
  const [singleDeleteTarget, setSingleDeleteTarget] = React.useState<TaskListItemSnapshot | null>(null)

  const reload = React.useCallback(() => {
    try {
      setTasks(taskQueryService.listTaskListItems())
      setError('')
    } catch (reloadError) {
      setTasks([])
      setError(reloadError instanceof Error ? reloadError.message : '任务列表加载失败')
    }
  }, [])

  // 触底续拉(分页):默认只加载最近 PAGE_SIZE 个,滑动触底再向 worker 拉下一页。
  const loadingMoreRef = React.useRef(false)
  const [loadingMore, setLoadingMore] = React.useState(false)
  const listViewportRef = React.useRef<HTMLDivElement | null>(null)
  const sentinelRef = React.useRef<HTMLDivElement | null>(null)

  const handleLoadMore = React.useCallback(() => {
    if (loadingMoreRef.current) return
    if (!taskStore.hasMore()) return
    loadingMoreRef.current = true
    setLoadingMore(true)
    void taskStore.loadMore().finally(() => {
      loadingMoreRef.current = false
      setLoadingMore(false)
    })
  }, [])

  const handleListScroll = React.useCallback((event: React.UIEvent<HTMLDivElement>) => {
    const node = event.currentTarget
    const distanceFromBottom = node.scrollHeight - (node.scrollTop + node.clientHeight)
    if (distanceFromBottom <= LOAD_MORE_THRESHOLD) {
      handleLoadMore()
    }
  }, [handleLoadMore])

  // 触底哨兵:当哨兵进入滚动视口(含首屏内容未撑满、以及用户滑到底)即续拉。
  // 依赖 tasks 重建观察器,每次列表变化后立即重判一次,保证「内容未满一屏」时也能逐页补齐。
  React.useEffect(() => {
    const viewport = listViewportRef.current
    const sentinel = sentinelRef.current
    if (!viewport || !sentinel || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        handleLoadMore()
      }
    }, { root: viewport, rootMargin: `0px 0px ${LOAD_MORE_THRESHOLD}px 0px` })
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [tasks, handleLoadMore])

  // 工作区分组(D16):任务按挂靠工作区分组展示,无"当前工作区"过滤,多工作区任务并陈。
  const [registry, setRegistry] = React.useState(workspaceRegistry.current)
  React.useEffect(() => workspaceRegistry.subscribe(setRegistry), [])

  React.useEffect(() => {
    reload()
  }, [reload])

  React.useEffect(() => {
    /**
     * hub 版:任务列表来自 taskStore 镜像(tasks.list + tasks 频道),
     * store 每次变更推送快照,这里整体重载列表即可。
     */
    taskStore.start()
    return taskStore.subscribe(reload)
  }, [reload])

  React.useEffect(() => {
    setSelectedTaskIds((current) => current.filter((taskId) => {
      const task = tasks.find((item) => item.taskId === taskId)
      return Boolean(task && task.status !== 'running')
    }))
  }, [tasks])

  const [collapsedGroups, setCollapsedGroups] = React.useState<Set<string>>(new Set())

  /**
   * 任务列表分组：按挂靠工作区内置分组（D16，无插件路径）。
   * 组序=注册表顺序（默认工作区在前），只保留非空组；registry 未加载返回 null
   * 回退扁平列表（防首帧全部误归尾组）。workspace 为空（旧任务）或已不在
   * 注册表（workspaces.remove 不删任务）的任务合并于尾组「未挂靠工作区」。
   */
  const groups = React.useMemo<TaskListGroup<TaskListItemSnapshot>[] | null>(() => {
    if (!registry) return null
    const knownRoots = new Set(registry.workspaces.map((entry) => entry.root))
    const out: TaskListGroup<TaskListItemSnapshot>[] = []
    for (const entry of registry.workspaces) {
      const groupTasks = tasks.filter((task) => task.workspace === entry.root)
      if (groupTasks.length === 0) continue
      out.push({
        key: `ws:${entry.root}`,
        label: workspaceGroupLabel(entry.root),
        title: entry.root,
        tasks: groupTasks,
        ...(onCreateNewTask
          ? {
              actions: [{
                id: `new:${entry.root}`,
                label: '在此工作区新建任务',
                onSelect: () => onCreateNewTask({ workspace: entry.root, workerId: entry.workerId }),
              }],
            }
          : null),
      })
    }
    const unattached = tasks.filter((task) => !task.workspace || !knownRoots.has(task.workspace))
    if (unattached.length > 0) {
      out.push({ key: 'ws:__none__', label: '未挂靠工作区', tasks: unattached })
    }
    return out
  }, [tasks, registry, onCreateNewTask])

  const handleToggleGroupCollapse = React.useCallback((groupKey: string) => {
    setCollapsedGroups((current) => {
      const next = new Set(current)
      if (next.has(groupKey)) {
        next.delete(groupKey)
      } else {
        next.add(groupKey)
      }
      return next
    })
  }, [])

  // 当前批量删除作用的工作区组：批量删除只针对该组任务。
  const batchGroup = React.useMemo(
    () => (batchGroupKey ? groups?.find((group) => group.key === batchGroupKey) ?? null : null),
    [batchGroupKey, groups],
  )

  /** 当前批删组内可删除（非 running）的任务。 */
  const batchGroupSelectableTasks = React.useMemo(
    () => batchGroup?.tasks.filter((task) => task.status !== 'running') ?? [],
    [batchGroup],
  )

  /** 当前批删组内被选中的任务。 */
  const batchGroupSelectedTasks = React.useMemo(
    () => batchGroup?.tasks.filter((task) => selectedTaskIds.includes(task.taskId)) ?? [],
    [batchGroup, selectedTaskIds],
  )

  // 批删组消失或组内无可删除任务时自动退出批量删除模式。
  React.useEffect(() => {
    if (!batchGroupKey) return
    if (!batchGroup || batchGroupSelectableTasks.length === 0) {
      setBatchGroupKey(null)
      setSelectedTaskIds([])
    }
  }, [batchGroupKey, batchGroup, batchGroupSelectableTasks])

  const allSelectableChecked = batchGroupSelectableTasks.length > 0
    && batchGroupSelectableTasks.every((task) => selectedTaskIds.includes(task.taskId))

  const handleToggleSelectAll = React.useCallback(() => {
    const ids = batchGroupSelectableTasks.map((task) => task.taskId)
    setSelectedTaskIds((current) => {
      const allChecked = ids.length > 0 && ids.every((id) => current.includes(id))
      return allChecked
        ? current.filter((id) => !ids.includes(id))
        : [...new Set([...current, ...ids])]
    })
  }, [batchGroupSelectableTasks])

  const handleToggleTask = React.useCallback((task: TaskListItemSnapshot) => {
    if (task.status === 'running') {
      return
    }
    setSelectedTaskIds((current) => (
      current.includes(task.taskId)
        ? current.filter((taskId) => taskId !== task.taskId)
        : [...current, task.taskId]
    ))
  }, [])

  const handleDeleteSelected = React.useCallback(async () => {
    if (batchGroupSelectedTasks.length === 0 || deleting) {
      return
    }
    setDeleting(true)
    setError('')
    const deletedTaskIds: string[] = []
    try {
      for (const task of batchGroupSelectedTasks) {
        await runtimeService.deleteTask(task.taskId)
        deletedTaskIds.push(task.taskId)
      }
      setBatchGroupKey(null)
      setSelectedTaskIds([])
      onDeletedTasks?.(deletedTaskIds)
      await reload()
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : '删除任务失败')
      setSelectedTaskIds((current) => current.filter((taskId) => !deletedTaskIds.includes(taskId)))
      if (deletedTaskIds.length > 0) {
        onDeletedTasks?.(deletedTaskIds)
      }
      await reload()
    } finally {
      setDeleting(false)
    }
  }, [batchGroupSelectedTasks, deleting, onDeletedTasks, reload])

  const handleDeleteTask = React.useCallback(async (taskId: string) => {
    if (deleting) {
      return
    }
    setDeleting(true)
    setError('')
    try {
      await runtimeService.deleteTask(taskId)
      onDeletedTasks?.([taskId])
      await reload()
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : '删除任务失败')
      await reload()
    } finally {
      setDeleting(false)
    }
  }, [deleting, onDeletedTasks, reload])

  const handleOpenTask = React.useCallback((task: TaskListItemSnapshot) => {
    onSelect({
      taskId: task.taskId,
      displayTitle: task.displayTitle,
    })
  }, [onSelect])

  /** 已切换到「显示绝对时间」的任务集合(点击时间触发;再点切回相对时间)。 */
  const [absoluteTimeTaskIds, setAbsoluteTimeTaskIds] = React.useState<Set<string>>(new Set())

  const handleToggleTime = React.useCallback((event: React.MouseEvent, taskId: string) => {
    event.stopPropagation() // 不触发行的打开/选中
    setAbsoluteTimeTaskIds((current) => {
      const next = new Set(current)
      if (next.has(taskId)) {
        next.delete(taskId)
      } else {
        next.add(taskId)
      }
      return next
    })
  }, [])

  const isMobile = useResponsiveViewport().isMobile
  const actionRefs = React.useRef(new Map<string, ListRowActionsHandle | null>())
  const { wasLongPressed, ...longPressHandlers } = useLongPress({
    isMobile,
    delay: 500,
    onLongPress: (node, point) => {
      const taskId = node.getAttribute('data-task-id')
      if (taskId) {
        actionRefs.current.get(taskId)?.openMenu(node, point)
      }
    },
  })

  const renderTaskCard = (task: TaskListItemSnapshot, groupKey: string) => {
    // 高亮只跟随父级当前任务，避免本地状态滞后造成旧任务残留边框。
    const isHighlighted = task.taskId === activeTaskId
    // 是否处于批量删除模式：仅当任务所属组是当前批删组时显示勾选行。
    const batchActive = batchGroupKey === groupKey
    return (
    <div
      key={task.taskId}
      className="ui-row"
      data-task-id={task.taskId}
      onClick={batchActive ? undefined : () => {
        if (wasLongPressed()) {
          return
        }
        if (isMobile) {
          // 移动端单击即打开（触屏不触发 onDoubleClick）
          handleOpenTask(task)
        }
      }}
      onDoubleClick={batchActive ? undefined : () => handleOpenTask(task)}
      onMouseDown={(e) => {
        // 阻止浏览器「双击选中文字」的默认行为:双击打开任务时,卡片内文字不应被高亮选中。
        // e.detail 为本次按压的连击计数,>1 表示双击/三击的第二次及以后按压;preventDefault 只拦默认选字,不影响 click/dblclick 触发。
        if (e.detail > 1) e.preventDefault()
      }}
      {...longPressHandlers}
      style={{
        ...taskCardStyle,
        ...(isHighlighted
          ? activeTaskCardStyle
          : null),
        ...(batchActive ? null : browseTaskCardStyle),
      }}
    >
      {batchActive ? (
        <div style={taskCardSelectionRowStyle}>
          <label
            style={{
              ...taskCheckboxLabelStyle,
              ...(task.status === 'running' ? disabledCheckboxLabelStyle : null),
            }}
          >
            <Checkbox
              checked={selectedTaskIds.includes(task.taskId)}
              disabled={task.status === 'running' || deleting}
              onChange={() => handleToggleTask(task)}
            />
            <span>{task.status === 'running' ? '进行中不可删除' : '选择删除'}</span>
          </label>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onSelect({
              taskId: task.taskId,
              displayTitle: task.displayTitle,
            })}
          >
            打开
          </Button>
        </div>
      ) : null}
      <div style={taskCardHeaderStyle}>
        <div style={taskTitleStyle} title={task.displayTitle}>
          {task.displayTitle}
        </div>
        <span
          style={{
            ...statusPillStyle,
            ...(task.statusTone === 'active'
              ? activeStatusPillStyle
              : task.statusTone === 'completed'
                ? completedStatusPillStyle
                : task.statusTone === 'stopped'
                ? stoppedStatusPillStyle
                : task.statusTone === 'error'
                ? errorStatusPillStyle
                : idleStatusPillStyle),
          }}
        >
          {task.statusLabel}
        </span>
        {!batchActive ? (
          <span style={{ marginLeft: 'auto' }}>
            <ListRowActions
              ref={(el) => {
                actionRefs.current.set(task.taskId, el)
              }}
              items={[
                {
                  key: 'delete',
                  label: '删除',
                  danger: true,
                  disabled: task.status === 'running' || deleting,
                  onSelect: () => setSingleDeleteTarget(task),
                },
              ]}
              title="任务更多操作"
              isMobile={isMobile}
            />
          </span>
        ) : null}
      </div>
      <div style={taskMetaRowStyle}>
        <span style={taskIdStyle} title={task.taskId}>
          {task.taskId}
        </span>
        <span
          style={{ ...taskMetaStyle, cursor: 'help' }}
          title={formatTaskTime(task.updatedAt)}
          onClick={(event) => handleToggleTime(event, task.taskId)}
        >
          {(() => {
            const relative = formatRelativeTime(task.updatedAt)
            // 超过 7 天默认绝对时间;点击切换相对/绝对展示。
            return relative !== null && !absoluteTimeTaskIds.has(task.taskId)
              ? relative
              : formatTaskTime(task.updatedAt)
          })()}
        </span>
        {task.contextUsage ? (
          <span style={ctxIndicatorStyle}>
            <ContextBattery taskId={task.taskId} monitor={task.contextUsage} />
          </span>
        ) : null}
      </div>
    </div>
  )
  }

  return (
    <div
      style={{
        ...shellStyle,
        ...(embedded ? embeddedShellStyle : standaloneShellStyle),
      }}
    >
      <SidebarScrollArea
        style={listStyle}
        viewportRef={listViewportRef}
        onScroll={handleListScroll}
      >
        {error ? (
          <div style={errorStyle}>{error}</div>
        ) : null}
        {groups === null
          ? tasks.map((task) => renderTaskCard(task, ''))
          : groups.map((group) => {
            const collapsed = collapsedGroups.has(group.key)
            const groupBatchActive = group.key === batchGroupKey
            return (
              <div key={group.key} style={groupSectionStyle}>
                <div style={groupHeaderStyle}>
                  <span
                    style={groupChevronButtonStyle}
                    title={collapsed ? '展开' : '折叠'}
                    role="button"
                    aria-expanded={!collapsed}
                    onClick={() => handleToggleGroupCollapse(group.key)}
                  >
                    <ChevronDownIcon
                      size={13}
                      style={collapsed ? { ...groupChevronStyle, ...groupChevronCollapsedStyle } : groupChevronStyle}
                    />
                  </span>
                  <span style={groupLabelStyle} title={group.title ?? group.label}>{group.label}</span>
                  {group.actions?.map((action) => (
                    <IconButton
                      key={action.id}
                      variant="ghost"
                      icon={<PlusIcon size={13} />}
                      style={groupAddButtonWrapStyle}
                      title={action.label}
                      aria-label={action.label}
                      onClick={(event) => {
                        event.stopPropagation()
                        action.onSelect()
                      }}
                    />
                  ))}
                  {groupBatchActive ? (
                    <span style={batchControlsStyle}>
                      {batchGroupSelectableTasks.length > 0 ? (
                        <label style={selectAllLabelStyle}>
                          <Checkbox
                            checked={allSelectableChecked}
                            onChange={handleToggleSelectAll}
                          />
                          <span>全选</span>
                        </label>
                      ) : null}
                      <Button
                        variant="danger"
                        size="sm"
                        disabled={batchGroupSelectedTasks.length === 0 || deleting}
                        onClick={() => {
                          setConfirmOpen(true)
                        }}
                      >
                        {deleting ? '删除中...' : `删除${batchGroupSelectedTasks.length > 0 ? ` (${batchGroupSelectedTasks.length})` : ''}`}
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={deleting}
                        onClick={() => {
                          setBatchGroupKey(null)
                          setSelectedTaskIds([])
                        }}
                      >
                        取消
                      </Button>
                    </span>
                  ) : null}
                  {group.actions?.length ? (
                    <ActionMenu
                      ariaLabel="更多操作"
                      align="end"
                      triggerIcon={<MoreHorizontalIcon size={13} />}
                      items={[{
                        key: 'batch-delete',
                        label: '批量删除',
                        danger: true,
                        disabled: group.tasks.every((task) => task.status === 'running')
                          || deleting
                          || batchGroupKey !== null,
                        onSelect: () => {
                          setSelectedTaskIds([])
                          setBatchGroupKey(group.key)
                        },
                      }]}
                    />
                  ) : null}
                </div>
                {collapsed ? null : (
                  <div style={groupTasksStyle}>
                    {group.tasks.map((task) => renderTaskCard(task, group.key))}
                  </div>
                )}
              </div>
            )
          })}
        {!error && tasks.length === 0 ? (
          <div style={emptyStyle}>暂无任务</div>
        ) : null}
        <div ref={sentinelRef} style={loadMoreFooterStyle}>
          {loadingMore
            ? '加载中…'
            : taskStore.hasMore()
              ? '上滑加载更多'
              : tasks.length > 0
                ? '已加载全部任务'
                : ''}
        </div>
      </SidebarScrollArea>
      <ConfirmDialog
        open={confirmOpen}
        title="批量删除任务"
        message={`确定要删除选中组内的 ${batchGroupSelectedTasks.length} 个任务吗？操作不可恢复。`}
        confirmLabel={`删除${batchGroupSelectedTasks.length > 0 ? ` (${batchGroupSelectedTasks.length})` : ''}`}
        danger
        onConfirm={async () => {
          setConfirmOpen(false)
          await handleDeleteSelected()
        }}
        onCancel={() => setConfirmOpen(false)}
      />
      <ConfirmDialog
        open={Boolean(singleDeleteTarget)}
        title="删除任务"
        message={`确定要删除任务「${singleDeleteTarget?.displayTitle ?? ''}」吗？该操作不可恢复。`}
        confirmLabel={deleting ? '删除中...' : '删除'}
        danger
        onConfirm={async () => {
          const target = singleDeleteTarget
          setSingleDeleteTarget(null)
          if (target) {
            await handleDeleteTask(target.taskId)
          }
        }}
        onCancel={() => setSingleDeleteTarget(null)}
      />
    </div>
  )
}

const shellStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  minHeight: 0,
}

const standaloneShellStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-lg)',
  background: 'var(--bg-secondary)',
  padding: 10,
}

const embeddedShellStyle: React.CSSProperties = {
  height: '100%',
  background: 'var(--bg-secondary)',
}

const batchControlsStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  flexShrink: 0,
}

/** 组头「在此工作区新建任务」图标按钮：仅负责推到右侧，视觉与「批量删除更多操作」一致（IconButton text 风格）。 */
const groupAddButtonWrapStyle: React.CSSProperties = {
  marginLeft: 'auto',
  flexShrink: 0,
}

const secondaryButtonStyle: React.CSSProperties = {
  border: '1px solid var(--border-light)',
  borderRadius: 999,
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  padding: '4px 10px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  cursor: 'pointer',
  flexShrink: 0,
}

const deleteButtonStyle: React.CSSProperties = {
  border: '1px solid color-mix(in srgb, var(--accent-red) 30%, var(--border-light))',
  borderRadius: 999,
  background: 'color-mix(in srgb, var(--accent-red-dim) 72%, var(--bg-primary))',
  color: 'var(--accent-red)',
  padding: '4px 10px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  cursor: 'pointer',
  flexShrink: 0,
}

const disabledButtonStyle: React.CSSProperties = {
  opacity: 0.5,
  cursor: 'not-allowed',
}

const selectAllLabelStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  userSelect: 'none',
}

const checkboxStyle: React.CSSProperties = {
  margin: 0,
}

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  minHeight: 0,
  overflowY: 'auto',
  paddingRight: 2,
}

const groupSectionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '8px 6px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border-light)',
  background: 'var(--bg-primary)',
}

const groupHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
}

/** 折叠/展开触发图标:仅点击该图标才折叠或展开。 */
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

const groupLabelStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
  flex: '0 1 auto',
}

const groupTasksStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
}

const taskCardStyle: React.CSSProperties = {
  // 默认态不画可见边框，避免已打开但未激活的任务仍然像“被选中”。
  // 用 longhand（borderWidth/borderStyle/borderColor）而非 `border` shorthand，
  // 否则与 activeTaskCardStyle 的 `borderColor` 混用时 React style diff 不彻底：
  // 切回默认态时 `border` 这个 key 没变不会重设，浏览器里 border-width/border-style
  // 残留、border-color 被清空，导致旧任务卡片看起来仍有边框。
  borderWidth: 1,
  borderStyle: 'solid',
  borderColor: 'transparent',
  borderRadius: 'var(--radius-md)',
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  padding: '10px 12px',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
}

const browseTaskCardStyle: React.CSSProperties = {
  cursor: 'pointer',
}

const activeTaskCardStyle: React.CSSProperties = {
  borderColor: 'var(--accent-blue)',
  background: 'var(--accent-blue-dim)',
}

const taskCardSelectionRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
}

const taskCheckboxLabelStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  userSelect: 'none',
}

const disabledCheckboxLabelStyle: React.CSSProperties = {
  opacity: 0.7,
}

const openTaskButtonStyle: React.CSSProperties = {
  border: '1px solid var(--border-light)',
  borderRadius: 999,
  background: 'var(--bg-secondary)',
  color: 'var(--text-primary)',
  padding: '2px 10px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  cursor: 'pointer',
  flexShrink: 0,
}

const taskCardHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 8,
  marginBottom: 6,
}

const taskTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  minWidth: 0,
  flex: 1,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const taskMetaRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'nowrap',
  minWidth: 0,
}

const taskIdStyle: React.CSSProperties = {
  minWidth: 0,
  flex: '1 1 auto',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  fontFamily: 'var(--font-mono, monospace)',
}

const taskMetaStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  flexShrink: 0,
  whiteSpace: 'nowrap',
}

const statusPillStyle: React.CSSProperties = {
  borderRadius: 999,
  padding: '2px 8px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  flexShrink: 0,
}

const activeStatusPillStyle: React.CSSProperties = {
  background: 'var(--accent-blue-dim)',
  color: 'var(--accent-blue)',
}

const idleStatusPillStyle: React.CSSProperties = {
  background: 'var(--bg-secondary)',
  color: 'var(--text-muted)',
}

const completedStatusPillStyle: React.CSSProperties = {
  background: 'var(--accent-green-dim)',
  color: 'var(--accent-green)',
}

const stoppedStatusPillStyle: React.CSSProperties = {
  background: 'color-mix(in srgb, var(--accent-amber) 14%, transparent)',
  color: 'var(--accent-amber)',
}

const errorStatusPillStyle: React.CSSProperties = {
  background: 'var(--accent-red-dim)',
  color: 'var(--accent-red)',
}

const emptyStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  padding: '8px 4px',
}

const errorStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-red)',
  padding: '8px 4px',
}

/** 触底续拉页脚(哨兵挂载点):弱化文案,不干扰列表主体。 */
const loadMoreFooterStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  textAlign: 'center',
  padding: '6px 4px',
  userSelect: 'none',
}

const ctxIndicatorStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 5,
  flexShrink: 0,
  marginLeft: 'auto',
}
