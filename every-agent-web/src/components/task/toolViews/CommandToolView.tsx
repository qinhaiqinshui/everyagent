/**
 * 平台化命令执行工具(powershell / bash)调用美化视图。
 *
 * 同一渲染结构同时注册两个工具名:Windows 的 powershell 与 Linux/macOS 的 bash
 * (运行时按平台只注册其一,前端视图表同时挂两个名字,命中即用)。
 *
 * 折叠态：工具图标 + 工具名 + 原样命令（command）。
 * 展开态：先展示参数块（完整命令，折叠行内联预览被截断时可在此看全量），
 *         再显示工具返回结果（stdout/stderr 合并文本，按原始换行渲染）；
 *         若状态为失败或 stderr 非空，额外展示错误内容（标红）。
 */

import React from 'react'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { hasActiveTextSelection } from './helpers'
import type { AggregatedToolDetail, ToolViewDefinition, ToolViewProps } from './types'

/** 从聚合结果中提取输出文本（兼容对象与字符串两种形态）。 */
function extractOutput(result: unknown): string {
  if (result && typeof result === 'object') {
    const obj = result as Record<string, unknown>
    const stdout = typeof obj.stdout === 'string' ? obj.stdout : ''
    const stderr = typeof obj.stderr === 'string' ? obj.stderr : ''
    if (stdout || stderr) {
      return (stdout + (stdout && stderr ? '\n' : '') + stderr).trim()
    }
  }
  if (typeof result === 'string') {
    return result.trim()
  }
  return result == null ? '' : JSON.stringify(result, null, 2)
}

function formatPrimitive(v: unknown): string {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return JSON.stringify(v)
}

/** 把参数平铺为 `字段名: 字段值` 行（与 FileWrite/Default 视图同款式）。 */
function flattenArgs(args: Record<string, unknown> | null): string[] {
  if (!args) return []
  return Object.entries(args).map(([k, v]) =>
    `${k}: ${v !== null && typeof v === 'object' ? JSON.stringify(v) : formatPrimitive(v)}`)
}

function ExecEntry({ detail }: { detail: AggregatedToolDetail }) {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  const rawCommand = typeof args.command === 'string' ? args.command : ''
  const inlineCommand = rawCommand.trim() || '（无命令）'
  const argLines = flattenArgs(args)

  const hasError = detail.status === 'error'
  const output = extractOutput(detail.result)

  const [open, setOpen] = React.useState(false)

  return (
    <div className={`nagent-tool nagent-tool--execmd${open ? ' is-open' : ''}`}>
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
        <span className="nagent-tool__name">{detail.toolName || '命令'}</span>
        <span className="nagent-tool__inline-preview">{inlineCommand}</span>
        {open ? (
          <ChevronDownIcon size={13} className="nagent-tool__chevron" />
        ) : (
          <ChevronRightIcon size={13} className="nagent-tool__chevron" />
        )}
      </button>
      {open ? (
        <div className="nagent-tool__detail">
          {argLines.length ? (
            <div className="nagent-tool__result-block">
              <div className="nagent-tool__result-head">
                <span className="nagent-tool__result-title">参数</span>
              </div>
              <pre className="nagent-tool__result-text">{argLines.join('\n')}</pre>
            </div>
          ) : null}
          {output ? (
            <div className="nagent-tool__result-block">
              <pre className="nagent-tool__result-text">{output}</pre>
            </div>
          ) : (
            <div className="nagent-tool__result-block">
              <pre className="nagent-tool__result-text">（无输出）</pre>
            </div>
          )}
        </div>
      ) : null}
    </div>
  )
}

function CommandToolView({ details }: ToolViewProps) {
  return (
    <>
      {details.map((detail, idx) => (
        <ExecEntry key={detail.toolCallId ?? idx} detail={detail} />
      ))}
    </>
  )
}

export const toolViews: ToolViewDefinition[] = [
  { toolName: 'powershell', component: CommandToolView },
  { toolName: 'bash', component: CommandToolView },
]
