/**
 * 文件写入类工具（create_file / update_file）调用美化视图（扩展点示例）。
 *
 * 折叠态：工具图标 + 工具名 + 原样路径（path）。
 * 展开态：先展示参数（path / oldcontent 等，但绝不 dump 巨大的 content），
 *         再显示工具返回结果（纯文本确认，如「已创建并保存到：<path>」，按原始换行渲染）；
 *         若状态为失败，额外展示错误内容（标红）。
 *
 * 自描述注册：导出 `toolViews` 即被目录即注册表自动发现，命中 create_file /
 * update_file 时完全接管渲染，未命中工具不受影响。
 */

import React from 'react'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { hasActiveTextSelection } from './helpers'
import type { AggregatedToolDetail, ToolViewDefinition, ToolViewProps } from './types'

// content 字段始终排在参数列表最后，避免开头被巨量正文占据。
const LAST_ARG = 'content'

function formatPrimitive(v: unknown): string {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return JSON.stringify(v)
}

function flattenArgs(args: Record<string, unknown> | null): string[] {
  if (!args) return []
  const contentLine: string[] = []
  const lines: string[] = []
  for (const [k, v] of Object.entries(args)) {
    const line = v !== null && typeof v === 'object'
      ? `${k}: ${JSON.stringify(v)}`
      : `${k}: ${formatPrimitive(v)}`
    if (k === LAST_ARG) {
      contentLine.push(line)
    } else {
      lines.push(line)
    }
  }
  return [...lines, ...contentLine]
}

function FileWriteEntry({ detail }: { detail: AggregatedToolDetail }) {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  const fullPath = typeof args.path === 'string' ? args.path : ''

  const hasError = detail.status === 'error'
  const result = detail.result
  const resultText = typeof result === 'string' ? result : JSON.stringify(result, null, 2)

  const argLines = flattenArgs(args)
  const [open, setOpen] = React.useState(false)

  return (
    <div className={`nagent-tool nagent-tool--filewrite${open ? ' is-open' : ''}`}>
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
        <span className="nagent-tool__name">{detail.toolName || '文件写入'}</span>
        <span className="nagent-tool__inline-preview">{fullPath || '（无路径）'}</span>
        {open ? (
          <ChevronDownIcon size={13} className="nagent-tool__chevron" />
        ) : (
          <ChevronRightIcon size={13} className="nagent-tool__chevron" />
        )}
      </button>
      {open ? (
        <div className="nagent-tool__detail">
          <div className="nagent-tool__result-item">
            <div className="nagent-tool__result-item-head">
              <WrenchIcon size={12} className={`nagent-tool__icon${hasError ? ' nagent-tool__icon--error' : ''}`} />
              <span className="nagent-tool__name">{detail.toolName || '文件写入'}</span>
            </div>
            {argLines.length ? (
              <div className="nagent-tool__result-block">
                <div className="nagent-tool__result-head">
                  <span className="nagent-tool__result-title">参数</span>
                </div>
                <pre className="nagent-tool__result-text">{argLines.join('\n')}</pre>
              </div>
            ) : null}
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
                <pre className="nagent-tool__result-text">（无输出）</pre>
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  )
}

function FileWriteToolView({ details }: ToolViewProps) {
  return (
    <>
      {details.map((detail, idx) => (
        <FileWriteEntry key={detail.toolCallId ?? idx} detail={detail} />
      ))}
    </>
  )
}

export const toolViews: ToolViewDefinition[] = [
  { toolName: 'create_file', component: FileWriteToolView },
  { toolName: 'update_file', component: FileWriteToolView },
]
