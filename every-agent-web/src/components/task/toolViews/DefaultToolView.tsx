/**
 * 默认工具视图（= 现有默认样子）。
 *
 * 折叠态：扳手 + 工具名 + 平铺参数预览（字段名: 字段值，无 JSON 的 {} 与键名引号）。
 * 展开态：单独显示工具名 + 参数块 + 结果块，参数与结果均以 `字段名: 字段值` 平铺，
 *         不再用 JSON.stringify 包裹，去掉结构化 JSON 的多余花括号与双引号。
 * 任何未注册专用美化视图的工具都走这里，保证"无扩展点则显示默认样子"。
 */

import React from 'react'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import JsonBlock from '@/components/shared/JsonBlock'
import { hasActiveTextSelection } from './helpers'
import type { AggregatedToolDetail, ToolViewProps } from './types'

function formatPrimitive(v: unknown): string {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return JSON.stringify(v)
}

/**
 * 把任意负载展平为 `字段名: 字段值` 文本行，去掉 JSON 的 {} 与键名引号。
 * 嵌套对象用点路径（a.b.c）、数组用下标（a[0]）表达，避免多层花括号。
 */
function flatten(obj: unknown, parentKey = '', lines: string[] = []): string[] {
  if (obj === null || obj === undefined) {
    if (parentKey) {
      lines.push(`${parentKey}: null`)
    }
    return lines
  }
  if (Array.isArray(obj)) {
    obj.forEach((item, i) => {
      const key = parentKey ? `${parentKey}[${i}]` : `[${i}]`
      if (item !== null && typeof item === 'object') {
        flatten(item, key, lines)
      } else {
        lines.push(`${key}: ${formatPrimitive(item)}`)
      }
    })
    return lines
  }
  if (typeof obj === 'object') {
    const entries = Object.entries(obj as Record<string, unknown>)
    for (const [k, v] of entries) {
      const key = parentKey ? `${parentKey}.${k}` : k
      if (v !== null && typeof v === 'object') {
        flatten(v, key, lines)
      } else {
        lines.push(`${key}: ${formatPrimitive(v)}`)
      }
    }
    return lines
  }
  lines.push(parentKey ? `${parentKey}: ${formatPrimitive(obj)}` : formatPrimitive(obj))
  return lines
}

function flattenArgs(args: Record<string, unknown> | null): string[] {
  if (!args) return []
  return flatten(args)
}

function truncate(text: string, max = 160): string {
  return text.length <= max ? text : `${text.slice(0, Math.max(max - 1, 1))}…`
}

function DefaultEntry({ detail }: { detail: AggregatedToolDetail }) {
  const hasError = detail.status === 'error'
  // status 为空 = 已下发但结果消息尚未到达（live 运行中态）。
  const isRunning = detail.status === undefined

  const argLines = flattenArgs(detail.arguments)
  const inlinePreview = argLines.length ? truncate(argLines.join('  ·  ')) : ''

  // 结果展示：取 result 字段。
  // - 结构化（对象/数组/标量）→ JSON 格式（pretty print），默认不换行、仅内容超出时横向滚动、完整展开无竖向滚动条。
  // - 普通文本（字符串）→ 原样显示，自动换行、仅内容超出时横向滚动、不出现竖向滚动条。
  const isText = typeof detail.result === 'string'
  const resultText = detail.result == null
    ? ''
    : isText
      ? (detail.result as string)
      : JSON.stringify(detail.result, null, 2)
  const resultEmpty = resultText.trim().length === 0

  const [open, setOpen] = React.useState(false)

  return (
    <div className={`nagent-tool nagent-tool--call${open ? ' is-open' : ''}`}>
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
        <span className="nagent-tool__name">{detail.toolName || '工具调用'}</span>
        <span className="nagent-tool__inline-preview">{isRunning ? '运行中…' : inlinePreview}</span>
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
              <span className="nagent-tool__name">{detail.toolName || '工具调用'}</span>
            </div>
            {argLines.length ? (
              <div className="nagent-tool__result-block">
                <div className="nagent-tool__result-head">
                  <span className="nagent-tool__result-title">参数</span>
                </div>
                <pre className="nagent-tool__result-text">{argLines.join('\n')}</pre>
              </div>
            ) : null}
            <div className="nagent-tool__result-block">
              {hasError ? (
                <div className="nagent-tool__result-head">
                  <span className="nagent-tool__result-title">错误输出</span>
                </div>
              ) : (
                <div className="nagent-tool__result-head">
                  <span className="nagent-tool__result-title">结果</span>
                </div>
              )}
              {isText ? (
                <pre className="nagent-tool__result-text nagent-tool__result-text--raw">
                  {resultEmpty ? (isRunning ? '（等待结果…）' : '（无输出）') : resultText}
                </pre>
              ) : (
                resultEmpty ? (
                  <pre className="nagent-tool__result-text">{isRunning ? '（等待结果…）' : '（无输出）'}</pre>
                ) : (
                  <JsonBlock content={resultText} showToolbar={false} wrapLines={false} />
                )
              )}
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}

export default function DefaultToolView({ details }: ToolViewProps) {
  return (
    <>
      {details.map((detail, idx) => (
        <DefaultEntry key={detail.toolCallId ?? idx} detail={detail} />
      ))}
    </>
  )
}
