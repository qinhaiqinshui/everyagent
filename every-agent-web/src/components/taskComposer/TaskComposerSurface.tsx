import React from 'react'
import type {
  ChatComposerDraftState,
  ChatComposerToken,
} from '@/types'
import { ArrowRightIcon } from '../shared/AppGlyphs'
import {
  listWorkspaceFiles,
  type WorkspaceFileEntry,
} from '@/query/workspaceFileQueryService'
import { buildWorkspaceFileToken } from '@/composerToken/workspaceFileToken'
import { isOpaqueTokenText } from '@/composerToken/composerOpaqueToken'
import {
  cancelTaskToken,
  extractSlashId,
} from '@/slash/taskScopedTokens'
import InlineComposer, { type InlineComposerHandle, type InlineComposerChange } from './InlineComposer'
import { Button } from '@/components/shared/ui'
import { FileIcon, FolderIcon } from '../shared/AppGlyphs'
import {
  slashCommandRegistry,
  type SlashCommandItem,
} from '@/slash/slashCommandRegistry'

/** 当前草稿变更（由内联编辑器上报，与 InlineComposer 同源）。 */
export type ComposerDraftChange = InlineComposerChange

/**
 * 把内联编辑器上报的变更合并进草稿状态：
 * - `insertedToken` 追加到 token 列表（去重）；
 * - `insertedTokens` 批量追加到 token 列表（粘贴含多个胶囊时，去重）；
 * - `removedTokenId` 从 token 列表删除（点击胶囊 × 时）；
 * - `text` / `rawContent` 直接覆盖（两者同源：rawContent 含内联胶囊，text 为其纯文本视图）。
 */
export function applyComposerDraftChange(
  current: ChatComposerDraftState,
  next: ComposerDraftChange,
): ChatComposerDraftState {
  let tokens = current.tokens
  if (next.removedTokenId) {
    tokens = tokens.filter((token) => token.id !== next.removedTokenId)
  }
  let lastInsertedId: string | undefined
  if (next.insertedTokens && next.insertedTokens.length > 0) {
    const existing = new Set(tokens.map((token) => token.id))
    const appended: ChatComposerToken[] = []
    for (const token of next.insertedTokens) {
      if (!existing.has(token.id)) {
        appended.push(token)
        existing.add(token.id)
        lastInsertedId = token.id
      }
    }
    if (appended.length > 0) {
      tokens = [...tokens, ...appended]
    }
  }
  if (next.insertedToken && !tokens.some((token) => token.id === next.insertedToken!.id)) {
    tokens = [...tokens, next.insertedToken]
    lastInsertedId = next.insertedToken.id
  }
  return {
    ...current,
    text: next.text,
    rawContent: next.rawContent,
    tokens,
    activeTokenId: lastInsertedId ?? current.activeTokenId,
  }
}

/**
 * 通用 Task 输入区属性。
 * 该组件只承载输入展示和交互，不负责读取业务数据。
 */
export interface TaskComposerSurfaceProps {
  /** 顶部可选头部内容。 */
  header?: React.ReactNode
  /** 输入框上方可选内容（如任务队列列表），由父组件 dispatch 扩展点后传入。 */
  abovePanel?: React.ReactNode
  /** 提交按钮上方的可选控制区。 */
  footerControls?: React.ReactNode
  /** 当前是否移动端。 */
  isMobile?: boolean
  /** 当前草稿状态（rawContent 含内联 opaque token，tokens 为胶囊列表）。 */
  draft: ChatComposerDraftState
  /** 输入框占位文案。 */
  placeholder: string
  /** 当前任务 ID（`@` 文件列举经 resolver 取范围；草稿态为 undefined）。 */
  taskId?: string
  /** `@` 文件引用的工作区根(任务自身工作区/草稿选择值;缺省由查询服务取注册表首选根)。 */
  workspace?: string
  /** 新建任务(草稿态)所选 worker,用于 / 命令按所选 worker 定向;老任务不传(按 taskId 归属)。 */
  workerId?: string
  /** 提交按钮文案。 */
  submitLabel: string
  /** 是否禁用提交。 */
  submitDisabled: boolean
  /** 输入区提示文案。 */
  hint?: string
  /** 输入区错误文案。 */
  error?: string
  /** 是否渲染内置发送按钮（启动台将按钮与模型选择同行时关闭）。默认 true。 */
  showSendButton?: boolean
  /**
   * 外部接收编辑器当前光标文本偏移（供 composer 权限「插入到光标位置」定位）。可选。
   * 父组件持有该 ref，本组件在每次光标变化时写入最新偏移。
   */
  composerCaretRef?: React.MutableRefObject<number>
  /**
   * 选中 position === 'bottom' 的 `/` 项时回调宿主（宿主负责 applyTaskToken + 更新自身列表）。
   * 允许返回 Promise：多个 bottom 结果（如「无人值守」联动「AI 审议」）会**逐个 await**，
   * 保证前一个胶囊先落盘（RPC 完成）再 apply 下一个，避免并行 RPC 的 meta 写竞态与中间态回显抖动。
   */
  onAddTaskScopeToken?: (payload: { id: string; token: string }) => void | Promise<void>
  /** 草稿内容变化（输入 / 插入 / 删除胶囊）。 */
  onChangeDraft: (next: ComposerDraftChange) => void
  /** 提交当前草稿。 */
  onSubmit: () => void
}

/**
 * 检测当前 caret 位置是否处于一个 `@` 文件引用触发上下文中。
 * 仅支持单 `@`(无 `@@` 全局模式):`@` 前一字符须为空白/开头。
 */
function detectAtTrigger(
  text: string,
  caret: number,
): { atIndex: number; query: string } | null {
  if (caret <= 0 || caret > text.length) {
    return null
  }
  let at = -1
  for (let j = caret - 1; j >= 0; j--) {
    const ch = text[j]
    if (ch === '@') {
      at = j
      break
    }
    if (ch === ' ' || ch === '\n' || ch === '\t' || ch === '　') {
      break
    }
  }
  if (at < 0) {
    return null
  }
  const prev = at - 1
  if (prev >= 0) {
    const pc = text[prev]
    if (!(pc === ' ' || pc === '\n' || pc === '\t' || pc === '　')) {
      return null
    }
  }
  const query = text.slice(at + 1, caret)
  if (/\s/.test(query)) {
    return null
  }
  return { atIndex: at, query }
}

/**
 * 检测当前 caret 位置是否处于一个 `/` 命令触发上下文中。
 */
function detectSlashTrigger(
  text: string,
  caret: number,
): { slashIndex: number; query: string } | null {
  if (caret <= 0 || caret > text.length) {
    return null
  }
  let slash = -1
  for (let j = caret - 1; j >= 0; j--) {
    const ch = text[j]
    if (ch === '/') {
      slash = j
      break
    }
    if (ch === ' ' || ch === '\n' || ch === '\t' || ch === '　') {
      break
    }
  }
  if (slash < 0) {
    return null
  }
  const prev = slash - 1
  if (prev >= 0) {
    const pc = text[prev]
    if (!(pc === ' ' || pc === '\n' || pc === '\t' || pc === '　')) {
      return null
    }
  }
  const query = text.slice(slash + 1, caret)
  if (/\s/.test(query)) {
    return null
  }
  return { slashIndex: slash, query }
}

/**
 * 通用 Task 输入区内容（nagent 面板风格）。
 * 新任务入口与 Task 聊天页都复用同一套输入能力展示结构。
 *
 * 输入区为内联富文本：命令 / 技能 / 文件胶囊渲染在触发位置的光标处，
 * 而不是输入框顶部的独立胶囊条。
 *
 * `/` 菜单内容来自独立注册中心 `slashCommandRegistry`（与 task 解耦），
 * 渲染时按 `group` 动态分组，渲染组件不感知条目是 skill 还是其它内容。
 */
export default function TaskComposerSurface({
  header,
  abovePanel,
  footerControls,
  isMobile = false,
  draft,
  placeholder,
  taskId,
  workspace,
  workerId,
  submitLabel,
  submitDisabled,
  hint,
  error,
  showSendButton = true,
  composerCaretRef,
  onAddTaskScopeToken,
  onChangeDraft,
  onSubmit,
}: TaskComposerSurfaceProps) {
  const editorRef = React.useRef<InlineComposerHandle | null>(null)
  const editorTextRef = React.useRef(draft.text)
  const editorCaretRef = React.useRef(0)
  const [slashOpen, setSlashOpen] = React.useState(false)
  const [slashQuery, setSlashQuery] = React.useState('')
  const [slashActiveIndex, setSlashActiveIndex] = React.useState(0)
  const [slashStart, setSlashStart] = React.useState(-1)
  /** `/` 菜单候选项（来自注册中心）。 */
  const [slashItems, setSlashItems] = React.useState<SlashCommandItem[]>([])
  const [slashLoading, setSlashLoading] = React.useState(false)

  // ── `@` 文件引用面板状态 ───────────────────────────────────────────────
  const [atOpen, setAtOpen] = React.useState(false)
  const [atQuery, setAtQuery] = React.useState('')
  const [atStart, setAtStart] = React.useState(-1)
  const [atActiveIndex, setAtActiveIndex] = React.useState(0)
  const [atBrowse, setAtBrowse] = React.useState<{ relativePath: string } | null>(null)
  const [atResults, setAtResults] = React.useState<WorkspaceFileEntry[]>([])
  const [atLoading, setAtLoading] = React.useState(false)
  const atSearchTimer = React.useRef<number | null>(null)

  /** `/` 弹层滚动容器与各选项元素引用（键盘/鼠标切换高亮时把选中项滚进可视区，见下方 useEffect）。 */
  const slashPopRef = React.useRef<HTMLDivElement | null>(null)
  const slashOptionRefs = React.useRef<Array<HTMLButtonElement | null>>([])
  /** `@` 文件引用弹层滚动容器与各选项元素引用（同类滚动跟随）。 */
  const atPopRef = React.useRef<HTMLDivElement | null>(null)
  const atOptionRefs = React.useRef<Array<HTMLButtonElement | null>>([])

  /** 根据文本 + caret 重新评估 `/` 触发状态。 */
  const updateSlashTrigger = React.useCallback((text: string, caret: number) => {
    const hit = detectSlashTrigger(text, caret)
    if (!hit) {
      setSlashOpen(false)
      setSlashQuery('')
      setSlashStart(-1)
      return
    }
    setAtOpen(false)
    setAtBrowse(null)
    setAtQuery('')
    setSlashOpen(true)
    setSlashStart(hit.slashIndex)
    setSlashQuery(hit.query)
    setSlashActiveIndex((current) => (
      slashOpen && slashStart === hit.slashIndex && slashQuery === hit.query ? current : 0
    ))
  }, [slashOpen, slashQuery, slashStart])

  /** 根据文本 + caret 重新评估 `@` 触发状态。 */
  const updateAtTrigger = React.useCallback((text: string, caret: number) => {
    const hit = detectAtTrigger(text, caret)
    if (!hit) {
      setAtOpen(false)
      setAtBrowse(null)
      setAtQuery('')
      return
    }
    setSlashOpen(false)
    setSlashQuery('')
    setSlashStart(-1)
    setAtOpen(true)
    setAtStart(hit.atIndex)
    setAtQuery(hit.query)
    setAtActiveIndex((current) => (
      atOpen && atStart === hit.atIndex && atQuery === hit.query ? current : 0
    ))
  }, [atOpen, atQuery, atStart])

  // `/` 菜单候选项：打开时从注册中心加载（与 task 解耦，只取一次）。
  React.useEffect(() => {
    if (!slashOpen) {
      setSlashItems([])
      setSlashLoading(false)
      return
    }
    let cancelled = false
    setSlashLoading(true)
    slashCommandRegistry.list(workerId)
      .then((items) => {
        if (cancelled) return
        setSlashItems(items)
        setSlashLoading(false)
      })
      .catch((error) => {
        if (cancelled) return
        setSlashLoading(false)
        throw error
      })
    return () => {
      cancelled = true
    }
  }, [slashOpen, workerId])

  React.useEffect(() => {
    if (draft.text.length === 0 && slashOpen) {
      setSlashOpen(false)
      setSlashQuery('')
      setSlashStart(-1)
    }
  }, [draft.text, slashOpen])

  React.useEffect(() => {
    if (!atOpen) {
      setAtResults([])
      setAtLoading(false)
      return
    }
    const browsing = atBrowse !== null
    const searching = atQuery.trim().length > 0
    if (!browsing && !searching) {
      setAtResults([])
      setAtLoading(false)
      return
    }
    setAtLoading(true)
    if (atSearchTimer.current) {
      window.clearTimeout(atSearchTimer.current)
    }
    const query = atQuery.trim()
    atSearchTimer.current = window.setTimeout(() => {
      listWorkspaceFiles({
        taskId,
        workspace,
        relativePath: atBrowse?.relativePath ?? '',
        query,
      })
        .then((list) => {
          const merged = [...list]
          merged.sort((left, right) => {
            if (left.kind !== right.kind) {
              return left.kind === 'directory' ? -1 : 1
            }
            return left.name.localeCompare(right.name, 'zh-CN')
          })
          setAtResults(merged)
          setAtLoading(false)
        })
        .catch((error) => {
          setAtLoading(false)
          throw error
        })
    }, 200)
    return () => {
      if (atSearchTimer.current) {
        window.clearTimeout(atSearchTimer.current)
      }
    }
  }, [atOpen, atQuery, atBrowse, taskId, workspace])

  const atActiveList: WorkspaceFileEntry[] = atResults
  const atSafeActiveIndex = Math.min(atActiveIndex, Math.max(0, atActiveList.length - 1))

  // `/` 菜单：按 query 过滤（标题 / id 命中）。
  const slashMatches = React.useMemo<SlashCommandItem[]>(() => {
    if (!slashOpen) {
      return []
    }
    const query = slashQuery.toLowerCase()
    return slashItems.filter((item) => (
      !query
      || item.title.toLowerCase().includes(query)
      || item.id.toLowerCase().includes(query)
    ))
  }, [slashOpen, slashQuery, slashItems])

  // 按 `group` 分组（保持注册/过滤顺序；无 group 的条目归入一个不显示组名的分组）。
  const slashGroups = React.useMemo(() => {
    const groups: Array<{ name: string | null; items: SlashCommandItem[] }> = []
    const indexOf = new Map<string, number>()
    for (const item of slashMatches) {
      const key = item.group ?? ''
      let idx = indexOf.get(key)
      if (idx === undefined) {
        idx = groups.length
        indexOf.set(key, idx)
        groups.push({ name: item.group ?? null, items: [] })
      }
      groups[idx].items.push(item)
    }
    return groups
  }, [slashMatches])

  /**
   * 按「分组展平」后的视觉顺序排列的条目：键盘导航下标必须与渲染高亮用同一套顺序。
   * 不能直接用 `slashMatches`（注册/provider 的扁平顺序）——worker 侧 provider 是
   * ConcurrentHashMap，返回顺序不保证同一 group 连续，扁平顺序可能与分组视觉顺序不一致，
   * 导致方向键"跳过"同组条目（如「AI 审议」被 Skills 组条目在扁平序中隔开）。
   */
  const slashOrdered = React.useMemo<SlashCommandItem[]>(
    () => slashGroups.flatMap((group) => group.items),
    [slashGroups],
  )

  // 键盘/鼠标切换高亮时，把当前高亮项滚动进 `/` 弹层可视区——弹层有
  // max-height:320px + overflow-y:auto，光标一路按到底部时若不滚动，高亮项会停在可视区外。
  // block:'nearest' 只在项超出容器可视范围时才滚动，正常浏览不打扰。
  React.useEffect(() => {
    const el = slashOptionRefs.current[slashActiveIndex]
    el?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  }, [slashActiveIndex, slashOrdered])

  // `@` 文件引用弹层同样跟随滚动（同类缺陷一并修复）。
  React.useEffect(() => {
    const el = atOptionRefs.current[atSafeActiveIndex]
    el?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  }, [atSafeActiveIndex, atResults])

  const selectEntryAsToken = React.useCallback((entry: WorkspaceFileEntry) => {
    const token = buildWorkspaceFileToken(entry)
    // 删除触发用的 `@query` 片段，再在光标处插入文件引用胶囊。
    editorRef.current?.insertToken(token, atQuery.length + 1)
    setAtOpen(false)
    setAtBrowse(null)
    setAtQuery('')
    setAtActiveIndex(0)
  }, [atQuery])

  const selectFileEntry = React.useCallback((entry: WorkspaceFileEntry) => {
    selectEntryAsToken(entry)
  }, [selectEntryAsToken])

  const applyAtActiveSelection = React.useCallback(() => {
    const item = atActiveList[atSafeActiveIndex]
    if (!item) {
      return
    }
    selectFileEntry(item)
  }, [atActiveList, atSafeActiveIndex, selectFileEntry])

  /** 选中某个 `/` 菜单项：按 select 返回的 position 分流（支持一次返回多个结果）。
   *  - 'bottom' → 清掉 `/query` 触发段（不写内联胶囊），对每个 bottom 结果循环回调宿主新增底部 token
   *    （各自用 result.id 归属条目，如「无人值守」一次返回「无人值守」+「AI 审议」两个胶囊）；
   *  - 'inline'  → 与现状一致：在 `/` 触发位置原地插入 opaque 胶囊或纯文本。
   *  slash 层只做形态判断（isOpaqueTokenText）与位置分流，不解析 payload、不分支类型。 */
  const applySlashSelection = React.useCallback(async (entry: SlashCommandItem) => {
    setSlashOpen(false)
    setSlashQuery('')
    setSlashActiveIndex(0)
    const deleteBefore = slashStart >= 0 ? slashQuery.length + 1 : 0
    const raw = await entry.select(entry)
    const results = Array.isArray(raw) ? raw : [raw]
    let slashSegmentConsumed = false
    for (const result of results) {
      const text = result.token
      if (result.position === 'bottom') {
        // 清掉 `/query` 触发段（不写内联胶囊，仅一次）；底部 token 由宿主持久化处理。
        if (!slashSegmentConsumed) {
          editorRef.current?.insertText('', deleteBefore)
          slashSegmentConsumed = true
        }
        if (isOpaqueTokenText(text)) {
          // 逐个 await:联动双胶囊(无人值守 + AI 审议)按顺序落盘,避免并行 RPC 竞态导致丢胶囊。
          await onAddTaskScopeToken?.({ id: result.id ?? entry.id, token: text })
        }
        continue
      }
      // inline：保持现状（多 inline 极少见，仍逐个在光标处插入）
      if (isOpaqueTokenText(text)) {
        editorRef.current?.insertOpaqueToken(text, slashSegmentConsumed ? 0 : deleteBefore)
      } else {
        editorRef.current?.insertText(text, slashSegmentConsumed ? 0 : deleteBefore)
      }
      slashSegmentConsumed = true
    }
  }, [slashStart, slashQuery, onAddTaskScopeToken])

  /** 内联胶囊 ✕ 删除上报：对含 slashId 的 token 触发 cancelTaskToken（不传 taskId，内联不持久化）。 */
  const handleRemoveInlineToken = React.useCallback((token: ChatComposerToken) => {
    const slashId = extractSlashId(token.opaqueText)
    if (slashId) {
      void cancelTaskToken({ id: slashId, token: token.opaqueText })
    }
  }, [])

  const handleSubmit = React.useCallback(() => {
    onSubmit()
  }, [onSubmit])

  const handleCaretChange = React.useCallback((text: string, caret: number) => {
    editorTextRef.current = text
    editorCaretRef.current = caret
    if (composerCaretRef) composerCaretRef.current = caret
    updateAtTrigger(text, caret)
    updateSlashTrigger(text, caret)
  }, [composerCaretRef, updateAtTrigger, updateSlashTrigger])

  const handleKeyDown = React.useCallback((event: React.KeyboardEvent<HTMLDivElement>) => {
    if (atOpen && atActiveList.length > 0) {
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        setAtActiveIndex((index) => Math.min(index + 1, Math.max(0, atActiveList.length - 1)))
        return
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault()
        setAtActiveIndex((index) => Math.max(index - 1, 0))
        return
      }
      if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
        event.preventDefault()
        applyAtActiveSelection()
        return
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        setAtOpen(false)
        setAtBrowse(null)
        setAtQuery('')
        return
      }
    }
    if (slashOpen && slashOrdered.length > 0) {
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        setSlashActiveIndex((index) => Math.min(index + 1, slashOrdered.length - 1))
        return
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault()
        setSlashActiveIndex((index) => Math.max(index - 1, 0))
        return
      }
      if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
        event.preventDefault()
        const chosen = slashOrdered[slashActiveIndex]
        if (chosen) {
          void applySlashSelection(chosen)
        }
        return
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        setSlashOpen(false)
        return
      }
    }
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      if (slashOpen) {
        setSlashOpen(false)
      }
      if (submitDisabled) {
        return
      }
      event.preventDefault()
      handleSubmit()
    }
  }, [
    atOpen,
    atActiveList,
    slashOpen,
    slashOrdered,
    slashActiveIndex,
    submitDisabled,
    applyAtActiveSelection,
    applySlashSelection,
    handleSubmit,
  ])

  const handleBlur = React.useCallback(() => {
    setSlashOpen(false)
    setAtOpen(false)
    setAtBrowse(null)
  }, [])

  return (
    <>
      {abovePanel ? (
        <div className="nagent-composer__above">{abovePanel}</div>
      ) : null}
      <div className="nagent-composer">
        {header ? (
          <div>{header}</div>
        ) : null}
        <div className="nagent-composer__input-wrap">
        {slashOpen && (slashMatches.length > 0 || slashLoading) ? (
          <div ref={slashPopRef} className="nagent-composer__slash-pop ui-menu ui-menu--popup" role="listbox">
            {slashLoading && slashMatches.length === 0 ? (
              <div className="nagent-composer__slash-empty">加载中…</div>
            ) : null}
            {slashGroups.map((group) => (
              <div className="nagent-composer__slash-group" key={group.name ?? ''}>
                {group.name ? (
                  <div className="nagent-composer__slash-group-title">{group.name}</div>
                ) : null}
                {group.items.map((entry) => {
                  const index = slashOrdered.indexOf(entry)
                  return (
                    <button
                      key={entry.id}
                      ref={(el) => {
                        slashOptionRefs.current[index] = el
                      }}
                      type="button"
                      role="option"
                      aria-selected={index === slashActiveIndex}
                      onMouseEnter={() => setSlashActiveIndex(index)}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => void applySlashSelection(entry)}
                      className={
                        'ui-menu__item'
                        + (index === slashActiveIndex ? ' is-active' : '')
                      }
                    >
                      {entry.icon ? (
                        <span
                          className="ui-menu__icon"
                          // 注册中心内部可信来源（skill / 插件注册），非用户输入。
                          dangerouslySetInnerHTML={{ __html: entry.icon }}
                        />
                      ) : null}
                      <span className="ui-menu__label">{entry.title}</span>
                      {entry.subtitle ? (
                        <span className="ui-menu__desc">{entry.subtitle}</span>
                      ) : null}
                    </button>
                  )
                })}
              </div>
            ))}
          </div>
        ) : null}
        {atOpen ? (
          <div ref={atPopRef} className="nagent-composer__at-pop ui-menu ui-menu--popup" role="listbox" aria-label="文件引用选择">
            <div className="nagent-composer__at-header">
              <div className="nagent-composer__at-title">文件引用</div>
              <div className="nagent-composer__at-hint">
                {atBrowse ? (atBrowse.relativePath || '当前目录') : '工作区搜索'}
              </div>
            </div>
            <>
              {atLoading ? (
                <div className="nagent-composer__slash-empty">搜索中…</div>
              ) : null}
              {!atLoading && atResults.length === 0 ? (
                <div className="nagent-composer__slash-empty nagent-composer__slash-empty--at">
                  {atQuery.trim() ? '无匹配文件' : (atBrowse ? '该目录为空' : '无文件')}
                </div>
              ) : null}
              {atResults.map((entry, index) => (
                <button
                  key={entry.fullPath}
                  ref={(el) => {
                    atOptionRefs.current[index] = el
                  }}
                    type="button"
                    role="option"
                    aria-selected={index === atSafeActiveIndex}
                    onMouseEnter={() => setAtActiveIndex(index)}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => selectFileEntry(entry)}
                      className={
                        'ui-menu__item'
                        + (index === atSafeActiveIndex ? ' is-active' : '')
                      }
                    >
                      <span className="ui-menu__icon">
                        {entry.kind === 'directory' ? <FolderIcon size={14} /> : <FileIcon size={14} />}
                      </span>
                      <span className="ui-menu__label">{entry.name}</span>
                      <span className="ui-menu__desc">{entry.fullPath}</span>
                    </button>
                ))}
            </>
          </div>
        ) : null}

        <InlineComposer
          ref={editorRef}
          rawContent={draft.rawContent}
          tokens={draft.tokens}
          placeholder={placeholder}
          onChange={onChangeDraft}
          onCaretChange={handleCaretChange}
          onKeyDown={handleKeyDown}
          onBlur={handleBlur}
          onRemoveToken={handleRemoveInlineToken}
        />
      </div>
      {footerControls ? (
        <div>{footerControls}</div>
      ) : null}
      {error ? (
        <div className="nagent-error">
          {error}
        </div>
      ) : null}
      {showSendButton || hint ? (
        <div className="nagent-composer__row">
          {hint ? (
            <span className="nagent-composer__hint">{hint}</span>
          ) : null}
          {showSendButton ? (
            <Button
              variant="primary"
              size="sm"
              onClick={handleSubmit}
              disabled={submitDisabled}
              className="nagent-composer__send"
            >
              <ArrowRightIcon size={14} />
              {submitLabel}
            </Button>
          ) : null}
        </div>
      ) : null}
    </div>
    </>
  )
}
