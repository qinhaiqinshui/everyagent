/**
 * TokenRichText —— 可渲染 opaque token 的公共文本组件（2026-11 公共化）。
 *
 * 输入一段可能含 opaque token 串（`[[[[agent-token::::…]]]]`）的文本，按段切分渲染：
 * 纯文本段原样输出、token 段渲染为内联胶囊（与输入框同款 `.nagent-inline-chip`）。
 * **嵌套支持**：胶囊 label 里再嵌 token 串（如「等待任务」胶囊 label 嵌目标任务标题的
 * rawContent）时递归渲染为胶囊内小胶囊（深度上限 MAX_RENDER_DEPTH，超出拍平为明文）。
 *
 * 消费方（宿主内）：用户消息回放（userMessageView）、侧边栏任务列表标题（TasksPanel）
 * 等一切「rawContent 直接给人看」的场景；插件侧经 `ctx.ui.TokenRichText` 取宿主注入的
 * 同款组件（api 包零运行时代码，只声明类型——实现单点在本模块）。
 *
 * 胶囊点击（interactive，默认开）：按 kind 查 `ui.composer_chip_actions` 扩展点，命中即
 * 回调注册方行为（核心 `system.workspace_file` 亦经此注册）；未命中弹只读详情层。
 */
import React from 'react'
import type { ChatComposerToken } from '@/types'
import { splitComposerRawContent, parseOpaqueTokenText, stripTokensToLabels } from './composerOpaqueToken'
import { getComposerChipView } from './composerChipRenderer'
import { openSlashItemDetail } from '@/components/taskComposer/SlashItemDetailPopover'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import { taskStore } from '@/task/taskStore'
import { workspaceRegistry } from '@/hub/workspaceRegistry'

/** 嵌套胶囊渲染深度上限：超过后 label 内 token 拍平为明文（防恶意/异常深嵌套撑爆渲染树）。 */
const MAX_RENDER_DEPTH = 3

export interface TokenRichTextProps {
  /** 待渲染文本（可含 opaque token 串；嵌套 token 整体成段）。 */
  text: string
  /** 可选的 composer tokens（命中时优先用真实 id，保证点击定位/去重）。 */
  tokens?: ChatComposerToken[]
  /**
   * 外层容器附加类名。缺省 `nagent-msg__content--user-replay`（消息回放布局：
   * pre-wrap + break-word）；任务列表标题等单行截断场景传
   * `nagent-task-title-replay`（nowrap + ellipsis）。
   */
  className?: string
  /**
   * 单行模式（任务列表标题、弹窗列表条目等窄条场景）：根容器 nowrap +
   * ellipsis 自截断，并把单行约束传导到全部后代（文本段 / 嵌套胶囊渲染容器
   * 自带显式 pre-wrap，显式声明优先于继承，必须逐层压回——否则标题被撑成多行）。
   * 默认 false（消息回放多行）。
   */
  singleLine?: boolean
  /** 胶囊是否可交互（点击触发 chip action / 只读详情）。默认 true。 */
  interactive?: boolean
  /** 点击 workspace_file 胶囊打开文件标签页时反查任务工作区根用的任务 ID（可缺省）。 */
  taskId?: string
  /** 嵌套渲染深度（内部递归用，外部不传）。 */
  depth?: number
}

/**
 * 胶囊激活（点击/Enter/空格）：按 kind 查胶囊激活扩展点（ui.composer_chip_actions）。
 * 命中即回调注册方行为；未命中保持现状（弹只读详情层）。
 */
function handleChipActivate(el: HTMLElement, token: ChatComposerToken, taskId?: string) {
  const action = pluginDispatcher.findComposerChipAction(token.kind)
  if (action) {
    action.onActivate({
      token: {
        kind: token.kind,
        label: token.label,
        summary: token.summary,
        opaqueText: token.opaqueText,
        payload: parseOpaqueTokenText(token.opaqueText)?.payload ?? {},
      },
      anchor: el,
      source: 'replay',
      // 消费侧解析工作区根:任务工作区优先、注册表首选兜底。
      workspaceRoot: (taskId ? taskStore.get(taskId)?.workspace : undefined) || workspaceRegistry.primaryRoot() || '',
      taskId,
    })
    return
  }
  // 未命中 → 保持现状:弹只读详情层。
  openSlashItemDetail(el, { token })
}

/** 单个胶囊（label 含嵌套 token 时递归渲染小胶囊）。 */
function ChipSegment({
  token,
  fallbackValue,
  interactive,
  taskId,
  depth,
  singleLine,
}: {
  token?: ChatComposerToken
  fallbackValue: string
  interactive: boolean
  taskId?: string
  depth: number
  singleLine?: boolean
}): React.ReactNode {
  const view = token
    ? getComposerChipView(token)
    : { tokenId: 'tok:' + fallbackValue, label: fallbackValue }
  const label = view.label || fallbackValue
  // label 里再嵌 token 串（嵌套胶囊）：递归渲染为胶囊内小胶囊；超深度上限拍平为明文。
  // singleLine 恒定传入——胶囊本体是单行元素（white-space:nowrap），嵌套内容同样
  // 不允许换行（否则会把胶囊撑成多行）；配合胶囊 max-width 由外向内逐层截断。
  const nested = depth < MAX_RENDER_DEPTH && label.includes('[[[[')
  const labelNode: React.ReactNode = nested
    ? (
        <TokenRichText
          text={label}
          className="nagent-inline-chip__label"
          interactive={false}
          depth={depth + 1}
          singleLine
        />
      )
    : <span className="nagent-inline-chip__label">{label}</span>
  const tooltip = label.includes('[[[[') ? stripTokensToLabels(label) : label
  return (
    <span
      className={
        'nagent-inline-chip nagent-inline-chip--replay'
        + (depth > 0 ? ' nagent-inline-chip--nested' : '')
      }
      title={tooltip}
      role={interactive && depth === 0 ? 'button' : undefined}
      tabIndex={interactive && depth === 0 ? 0 : undefined}
      onClick={(event) => {
        event.stopPropagation()
        if (interactive && depth === 0 && token) {
          handleChipActivate(event.currentTarget as HTMLElement, token, taskId)
        }
      }}
      onKeyDown={(event) => {
        if (interactive && depth === 0 && token && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault()
          handleChipActivate(event.currentTarget as HTMLElement, token, taskId)
        }
      }}
    >
      {labelNode}
    </span>
  )
}

/** 公共 token 富文本渲染组件。空文本返回 null。 */
export default function TokenRichText({
  text,
  tokens,
  className,
  singleLine = false,
  interactive = true,
  taskId,
  depth = 0,
}: TokenRichTextProps): React.ReactNode {
  const segments = splitComposerRawContent(text ?? '', tokens)
  if (segments.length === 0) {
    return null
  }
  return (
    <span
      className={
        'nagent-msg__content nagent-msg__content--user-replay'
        + (singleLine ? ' nagent-msg__content--single-line' : '')
        + (className ? ` ${className}` : '')
      }
    >
      {segments.map((segment, index) =>
        segment.type === 'text' ? (
          <span key={`text:${index}`} className="nagent-msg__inline-text">{segment.value}</span>
        ) : (
          <ChipSegment
            key={`token:${index}:${segment.value}`}
            token={segment.token}
            fallbackValue={segment.value}
            interactive={interactive}
            taskId={taskId}
            depth={depth}
            singleLine={singleLine}
          />
        ),
      )}
    </span>
  )
}
