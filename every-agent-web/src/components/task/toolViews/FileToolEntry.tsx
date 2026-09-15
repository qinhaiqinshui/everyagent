/**
 * 文件类工具（create_file / update_file / read_file）共享的 entry 渲染组件。
 *
 * 三个工具的折叠态仅 inline-preview 文本不同（read_file 多带 line_start/line_end 等
 * 参数），展开态结构完全一致，故抽出共享组件：
 *
 * 折叠态：工具图标 + 工具名 + inline-preview（由调用方计算）+ 折叠箭头。
 * 展开态：result-item-head（图标 + 工具名 + 可点击文件路径 chip）→ 参数块（content 排末尾）
 *         → 结果块（read_file 即文件内容；create/update 为确认文本）→ 错误块。
 *
 * 展开态工具名后的文件路径为可点击 chip，点击经 useWorkspaceShell().openGlobalFileTab
 * 打开文件标签页（readwrite 模式，方便用户直接改 AI 写的文件）；workspaceRoot 缺失时
 * 降级为纯文本不可点，避免历史/未关联工作区场景报错。
 */

import React from 'react'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { toBusinessAbsolutePath } from '@/platform/fs/pathUtils'
import { useTaskWorkspaceRoot } from '../TaskWorkspaceContext'
import { hasActiveTextSelection } from './helpers'
import type { AggregatedToolDetail } from './types'

/** content 字段始终排在参数列表最后，避免开头被巨量正文占据。 */
const LAST_ARGS = new Set(['content'])

function formatPrimitive(v: unknown): string {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return JSON.stringify(v)
}

/** 把参数展平为 `k: v` 文本行；content 等巨量字段排末尾。 */
function flattenArgs(args: Record<string, unknown> | null): string[] {
  if (!args) return []
  const tail: string[] = []
  const lines: string[] = []
  for (const [k, v] of Object.entries(args)) {
    const line = v !== null && typeof v === 'object'
      ? `${k}: ${JSON.stringify(v)}`
      : `${k}: ${formatPrimitive(v)}`
    if (LAST_ARGS.has(k)) {
      tail.push(line)
    } else {
      lines.push(line)
    }
  }
  return [...lines, ...tail]
}

export interface FileToolEntryProps {
  detail: AggregatedToolDetail
  /** 折叠行内联预览文本（路径 + 可选附加参数），由调用方按工具计算。 */
  inlinePreview: string
}

export function FileToolEntry({ detail, inlinePreview }: FileToolEntryProps) {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  const fullPath = typeof args.path === 'string' ? args.path : ''
  const businessPath = fullPath ? toBusinessAbsolutePath(fullPath) : ''

  const workspaceRoot = useTaskWorkspaceRoot()
  const { openGlobalFileTab } = useWorkspaceShell()

  const hasError = detail.status === 'error'
  const result = detail.result
  const resultText = typeof result === 'string' ? result : JSON.stringify(result, null, 2)

  const argLines = flattenArgs(args)
  const [open, setOpen] = React.useState(false)

  const handleOpenFile = React.useCallback(() => {
    if (!businessPath || !workspaceRoot || !openGlobalFileTab) return
    openGlobalFileTab({ workspaceRoot, filePath: businessPath }, { mode: 'readwrite' })
  }, [businessPath, workspaceRoot, openGlobalFileTab])

  const canOpen = Boolean(businessPath && workspaceRoot && openGlobalFileTab)

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
        <span className="nagent-tool__name">{detail.toolName || '文件工具'}</span>
        <span className="nagent-tool__inline-preview">{inlinePreview || '（无路径）'}</span>
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
              <span className="nagent-tool__name">{detail.toolName || '文件工具'}</span>
              {businessPath ? (
                canOpen ? (
                  <button
                    type="button"
                    className="nagent-tool__detail-path"
                    title={`打开文件：${businessPath}`}
                    onClick={(e) => {
                      e.stopPropagation()
                      handleOpenFile()
                    }}
                  >
                    {businessPath}
                  </button>
                ) : (
                  <span className="nagent-tool__detail-path nagent-tool__detail-path--static" title="未关联工作区，无法打开">{businessPath}</span>
                )
              ) : null}
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
