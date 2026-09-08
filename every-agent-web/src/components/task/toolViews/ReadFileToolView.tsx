/**
 * read_file 工具调用美化视图（扩展点示例）。
 *
 * 折叠态：工具图标 + 工具名 + 原样路径（path）+ 其余参数（line_start / line_end 等，
 *         以 key=value 形式跟在 path 后面）。
 * 展开态：直接显示文件内容（结果），字符串按原始换行渲染（white-space: pre-wrap）；
 *         若状态为失败，额外展示错误内容（标红）。
 *
 * 自描述注册：导出 `toolView` 即被目录即注册表自动发现，命中工具名 `read_file` 时
 * 完全接管渲染，未命中工具不受影响。
 */

import React from 'react'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { hasActiveTextSelection } from './helpers'
import type { AggregatedToolDetail, ToolViewDefinition, ToolViewProps } from './types'

/** 把除 path 外的参数格式化为 "key=value" 空格串；未提供的参数不显示。 */
function formatExtraArgs(args: Record<string, unknown>): string {
  return Object.entries(args)
    .filter(([key, value]) => key !== 'path' && value !== undefined && value !== null)
    .map(([key, value]) => `${key}=${typeof value === 'string' ? value : JSON.stringify(value)}`)
    .join(' ')
}

function ReadFileEntry({ detail }: { detail: AggregatedToolDetail }) {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  const fullPath = typeof args.path === 'string' ? args.path : ''
  const extraArgs = formatExtraArgs(args)
  const inlinePreview = [fullPath, extraArgs].filter(Boolean).join(' ')

  const hasError = detail.status === 'error'
  const result = detail.result
  const resultText = typeof result === 'string' ? result : JSON.stringify(result, null, 2)

  const [open, setOpen] = React.useState(false)

  return (
    <div className={`nagent-tool nagent-tool--readfile${open ? ' is-open' : ''}`}>
      <button
        type="button"
        className="nagent-tool__summary"
        aria-expanded={open}
        onClick={() => {
          // 拖选参数文本（选区非空）时不切换折叠，保证参数可选中复制。
          if (hasActiveTextSelection()) return
          setOpen((value) => !value)
        }}
      >
        <WrenchIcon size={13} className={`nagent-tool__icon${hasError ? ' nagent-tool__icon--error' : ''}`} />
        <span className="nagent-tool__name">{detail.toolName || 'read_file'}</span>
        <span className="nagent-tool__inline-preview">{inlinePreview || '（无路径）'}</span>
        {open ? (
          <ChevronDownIcon size={13} className="nagent-tool__chevron" />
        ) : (
          <ChevronRightIcon size={13} className="nagent-tool__chevron" />
        )}
      </button>
      {open ? (
        <div className="nagent-tool__detail">
          {resultText.trim() && !hasError ? (
            <div className="nagent-tool__result-block">
              <pre className="nagent-tool__result-text">{resultText}</pre>
            </div>
          ) : null}
          {hasError ? (
            <div className="nagent-tool__result-block">
              <div className="nagent-tool__result-head">
                <span className="nagent-tool__result-title">错误输出</span>
              </div>
              <pre className="nagent-tool__result-text">{resultText.trim() ? resultText : '（错误）'}</pre>
            </div>
          ) : null}
          {!resultText.trim() && !hasError ? (
            <div className="nagent-tool__result-block">
              <pre className="nagent-tool__result-text">（空内容）</pre>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

function ReadFileToolView({ details }: ToolViewProps) {
  return (
    <>
      {details.map((detail, idx) => (
        <ReadFileEntry key={detail.toolCallId ?? idx} detail={detail} />
      ))}
    </>
  )
}

export const toolView: ToolViewDefinition = {
  toolName: 'read_file',
  component: ReadFileToolView,
}
