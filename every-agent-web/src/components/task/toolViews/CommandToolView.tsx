/**
 * 平台化命令执行工具(powershell / bash)调用美化视图。
 *
 * 同一渲染结构同时注册两个工具名:Windows 的 powershell 与 Linux/macOS 的 bash
 * (运行时按平台只注册其一,前端视图表同时挂两个名字,命中即用)。
 *
 * 折叠态：工具图标 + 工具名 + 原样命令（command）。
 *         若命令首行是注释（意图说明），内联预览拆分为「正文样式注释 + 等宽命令」两段，
 *         注释以 AI 正文样式（无衬线 + 正文色）呈现，与等宽 muted 的命令体形成层次。
 *         图标为终端图标（TerminalIcon，powershell/bash 专属，区别于默认扳手）：
 *         颜色走 nagent-tool-state--* 单一来源，工具执行失败时转红（detail.status
 *         === 'error'，来自 worker tool.result 事件的 data.status；由 worker
 *         按结构化结果记账判定（shell 退出码 / 超时 / 工具抛异常，见 plugin-api
 *         ToolOutcomeLog））；running 态为光标硬闪烁而非旋转（chatPanel.css）。
 * 展开态：先展示参数块（完整命令，折叠行内联预览被截断时可在此看全量），
 *         再显示工具返回结果（stdout/stderr 合并文本，按原始换行渲染）。
 *         stderr 已由 worker 合并进结果文本（以 [stderr] 段标记），此处不再单独分支。
 */

import React from 'react'
import { TerminalIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { hasActiveTextSelection, splitLeadingComment, toolStateClass, toolDurationText } from './helpers'
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
  // 首行是注释(意图说明)时拆分为「正文样式注释 + 等宽命令」两段,否则整段原样等宽。
  const preview = splitLeadingComment(rawCommand.trim())
  const inlineCommand = rawCommand.trim() || '（无命令）'
  const argLines = flattenArgs(args)

  const hasError = detail.status === 'error'
  const output = extractOutput(detail.result)

  const stateClass = toolStateClass(detail)
  const durationText = toolDurationText(detail)
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
        <TerminalIcon size={13} className={`nagent-tool__icon${stateClass ? ` ${stateClass}` : ''}`} />
        {durationText ? <span className="nagent-tool__duration">{durationText}</span> : null}
        <span className="nagent-tool__name">{detail.toolName || '命令'}</span>
        <span className="nagent-tool__inline-preview">
          {preview ? (
            <>
              <span className="nagent-tool__inline-preview-comment">{preview.comment}</span>
              {preview.rest ? ` ${preview.rest}` : null}
            </>
          ) : (
            inlineCommand
          )}
        </span>
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
