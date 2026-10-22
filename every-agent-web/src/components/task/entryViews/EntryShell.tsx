/**
 * 折叠行共享壳(EntryShell):trace 族行(kindViews 的 FallbackView → TaskTraceShell)
 * 与工具调用行(toolViews 三视图,步骤 5 迁移)共用的「图标/耗时/名称/摘要 + 折叠
 * chevron + 展开容器」行骨架。壳只负责结构(折叠按钮/静态行形态、拖选保护、
 * is-open 状态类、展开容器挂载),内容全部走槽位:icon/duration/name/summary +
 * children(展开态内容)。
 *
 * 类名策略:壳层子元素统一 `nagent-entry__*`(summary/icon/duration/name/text/
 * chevron/content,规则见 chatPanel.css「Entry 折叠行壳」块,trace 行与工具行共用
 * 同一套);外层容器类由调用方经 className 传入(如 trace 侧
 * "nagent-trace-shell nagent-msg"、工具行 "nagent-tool nagent-tool--call"),
 * 行族专属的容器样式(缩进/展开边框/悬停策略)留在各自容器类上,壳只在展开时
 * 追加 is-open 状态类。
 *
 * open 为壳内部 state(与迁移前的 TraceShell/工具行一致,点击摘要行切换);受控
 * 形态当前无消费方,需要时再开。
 */
import React from 'react'
import { ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { hasActiveTextSelection } from '@/components/task/toolViews/helpers'

export interface EntryShellProps {
  /** 外层容器类(行族标识,如 "nagent-trace-shell nagent-msg");壳在展开时追加 is-open。 */
  className?: string
  /** 外层元素类型(trace 行用 section,工具行用 div);默认 div。 */
  as?: 'div' | 'section'
  /** 外层容器原生 title(悬停完整摘要,trace 行传入「title + summary」拼接文本)。 */
  title?: string
  /** true → 折叠按钮形态(chevron + aria-expanded + 点击切换展开);false(默认)→ 静态行形态(无 chevron、cursor default、不可点击)。 */
  expandable?: boolean
  /** 图标槽:壳以 span.nagent-entry__icon 包裹(对齐/flex 布局由该类提供)。 */
  icon?: React.ReactNode
  /** 耗时槽(trace 行不用,工具行用):壳以 span.nagent-entry__duration 包裹。 */
  duration?: React.ReactNode
  /** 名称槽(trace 行的 title/工具行的工具名):壳以 span.nagent-entry__name 包裹。 */
  name?: React.ReactNode
  /** 摘要槽(trace 行的摘要文本/工具行的内联参数预览):壳以 span.nagent-entry__text 包裹。 */
  summary?: React.ReactNode
  /** 展开容器内容:仅「展开且可展开」时渲染于 div.nagent-entry__content。 */
  children?: React.ReactNode
}

/** 摘要行槽位渲染顺序:icon → duration → name → summary(text)。 */
export function EntryShell({
  className,
  as = 'div',
  title,
  expandable = false,
  icon,
  duration,
  name,
  summary,
  children,
}: EntryShellProps) {
  const [open, setOpen] = React.useState(false)
  const Container = as
  const summaryRowContent = (
    <>
      {icon ? <span className="nagent-entry__icon">{icon}</span> : null}
      {duration ? <span className="nagent-entry__duration">{duration}</span> : null}
      {name ? <span className="nagent-entry__name">{name}</span> : null}
      {summary ? <span className="nagent-entry__text">{summary}</span> : null}
    </>
  )

  const summaryRow = expandable ? (
    <button
      type="button"
      className="nagent-entry__summary"
      aria-expanded={open}
      onClick={() => {
        // 拖选行内文本(选区非空)时不切换折叠,保证摘要/参数可选中复制
        // (与工具行行为一致化;trace 摘要行选中文本后点击同样不误触展开)。
        if (hasActiveTextSelection()) return
        setOpen((current) => !current)
      }}
    >
      {summaryRowContent}
      <span className="nagent-entry__chevron">
        {open ? <ChevronDownIcon size={13} /> : <ChevronRightIcon size={13} />}
      </span>
    </button>
  ) : (
    <div className="nagent-entry__summary nagent-entry__summary--static">{summaryRowContent}</div>
  )

  const openClass = open && expandable ? 'is-open' : ''
  return (
    <Container
      className={className ? [className, openClass].filter(Boolean).join(' ') : (openClass || undefined)}
      title={title}
    >
      {summaryRow}
      {open && expandable ? <div className="nagent-entry__content">{children}</div> : null}
    </Container>
  )
}