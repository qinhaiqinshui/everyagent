/**
 * 工具调用渲染分发器。
 *
 * 把 merged tool 消息构建为聚合后的 JSON 数组（每条一个 `AggregatedToolDetail`），
 * 再按工具名把数组切成若干连续组，每组从注册表取对应工具的美化视图组件渲染；
 * 未注册则回退默认视图。美化视图完全接管内部渲染,核心只负责装配数据 + 选择视图。
 *
 * 多调用聚合：同一工具连续多条（同组 details.length > 1）时，
 * 加一层可折叠聚合壳（group header：工具名 + ×N 徽章 + 首条内联预览），
 * 整组折叠时只显示一行；整组展开后由内部 View 接管渲染每条 entry（各自仍可独立折叠）。
 * 单调用（details.length === 1）时保持原样不加壳，避免视觉噪音。
 *
 * 混合工具批：当一次 AI 下发多个不同工具（如 powershell + read_file）时，
 * 按工具名拆成多个组、各组选用自己的美化视图，
 * 避免“按首条工具名选视图”导致后序专用工具（如 read_file）被兜底成默认视图。
 */

import React from 'react'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import type { AgentMessageRecord } from '@/types'
import { getToolView } from './registry'
import { buildAggregatedToolDetail, hasActiveTextSelection, truncateInlineText } from './helpers'
import type { AggregatedToolDetail, ToolViewProps } from './types'

interface ToolCallViewProps {
  messages: AgentMessageRecord[]
  resolvedNames: string[]
  resolveToolCallPayload?: (toolCallId: string) => Record<string, unknown> | undefined
}

/** 聚合壳内联预览:取首条 detail 的 path / command / 文本参数摘要,无参时回退。 */
function buildGroupInlinePreview(detail: AggregatedToolDetail): string {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  // 优先 path / command 这些"一眼就知道是哪个文件/哪个命令"的标量参数。
  const preferred = args.path ?? args.command
  if (typeof preferred === 'string' && preferred.trim()) {
    return truncateInlineText(preferred, 160)
  }
  // 退化:把若干标量参数以 "k=v · k=v" 形式铺平(够短),完全无参时给个占位。
  const scalars = Object.entries(args)
    .filter(([, v]) => v !== null && (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean'))
    .slice(0, 3)
    .map(([k, v]) => `${k}=${typeof v === 'string' ? v : JSON.stringify(v)}`)
    .join(' · ')
  return scalars ? truncateInlineText(scalars, 160) : ''
}

/**
 * 给定聚合后的 details，选择对应美化视图并按单/多调用决定加壳，最终渲染。
 * 这是 tool 消息路径（历史/兜底）与 assistant 下发路径（live）共用的唯一渲染出口，
 * 保证两条路径视觉一致。
 *
 * details 可能是混合工具批（如一次下发 powershell + read_file）。为让每个工具都
 * 走自己的美化视图（read_file 保持与单独下发一致的渲染），先把数组按工具名切成
 * 若干连续组：组内同名、共用同一视图；不同名各成一组，各组独立选视图/加壳。
 */
export function renderAggregatedToolDetails(
  details: AggregatedToolDetail[],
  resolveToolCallPayload?: (toolCallId: string) => Record<string, unknown> | undefined,
  record?: AgentMessageRecord,
): React.ReactNode {
  if (details.length === 0) return null

  // 按工具名把调用切成若干连续组：组内同名、共用同一个美化视图。
  const runs: AggregatedToolDetail[][] = []
  for (const detail of details) {
    const lastRun = runs[runs.length - 1]
    if (lastRun && lastRun[0]?.toolName === detail.toolName) {
      lastRun.push(detail)
    } else {
      runs.push([detail])
    }
  }

  return (
    <>
      {runs.map((run, idx) => {
        const toolName = run[0]?.toolName ?? '工具'
        const View = getToolView(toolName)

        // 单调用:不走聚合壳,直接渲染对应视图,行为与改造前一致。
        if (run.length <= 1) {
          return (
            <View
              key={`${toolName}:${idx}`}
              details={run}
              record={record}
              resolveToolCallPayload={resolveToolCallPayload}
            />
          )
        }

        // 同工具多调用:加聚合壳。整体是否展开由 group 自身的状态控制,
        // 内部 View 仍按各自 entry 独立折叠。
        return (
          <GroupedToolCallView
            key={`${toolName}:${idx}`}
            details={run}
            View={View}
            record={record}
            resolveToolCallPayload={resolveToolCallPayload}
          />
        )
      })}
    </>
  )
}

export default function ToolCallView({
  messages,
  resolvedNames,
  resolveToolCallPayload,
}: ToolCallViewProps) {
  const details = React.useMemo<ToolViewProps['details']>(
    () =>
      messages.map((m, idx) => {
        const callId = m.toolCallId?.trim() ?? ''
        const rawArgs = callId ? resolveToolCallPayload?.(callId) : undefined
        const args = rawArgs && Object.keys(rawArgs).length > 0 ? rawArgs : null
        const name = resolvedNames[idx] || m.toolName?.trim() || '工具'
        return buildAggregatedToolDetail(m, name, args)
      }),
    [messages, resolvedNames, resolveToolCallPayload],
  )

  return renderAggregatedToolDetails(details, resolveToolCallPayload, messages[0])
}

interface GroupedToolCallViewProps {
  details: AggregatedToolDetail[]
  View: React.ComponentType<ToolViewProps>
  record?: AgentMessageRecord
  resolveToolCallPayload?: (toolCallId: string) => Record<string, unknown> | undefined
}

function GroupedToolCallView({
  details,
  View,
  record,
  resolveToolCallPayload,
}: GroupedToolCallViewProps) {
  const [open, setOpen] = React.useState(false)

  const toolName = details[0]?.toolName ?? '工具'
  const inlinePreview = buildGroupInlinePreview(details[0])
  const count = details.length
  // 整组任一 entry 报错则图标转红,提示用户可能需要展开检查。
  const hasError = details.some((d) => d.status === 'error')

  return (
    <div className={`nagent-tool-group${open ? ' is-open' : ''}`}>
      <button
        type="button"
        className="nagent-tool-group__summary"
        aria-expanded={open}
        aria-label={`${toolName} 共 ${count} 次调用，点击${open ? '折叠' : '展开'}`}
        onClick={() => {
          // 拖选参数文本（选区非空）时不切换折叠，保证参数可选中复制。
          if (hasActiveTextSelection()) return
          setOpen((value) => !value)
        }}
      >
        <WrenchIcon size={13} className={`nagent-tool-group__icon${hasError ? ' nagent-tool-group__icon--error' : ''}`} />
        <span className="nagent-tool-group__name">{toolName}</span>
        <span className="nagent-tool-group__count" aria-label={`共 ${count} 次`}>
          ×{count}
        </span>
        <span className="nagent-tool-group__inline-preview">{inlinePreview}</span>
        {open ? (
          <ChevronDownIcon size={13} className="nagent-tool-group__chevron" />
        ) : (
          <ChevronRightIcon size={13} className="nagent-tool-group__chevron" />
        )}
      </button>
      {open ? (
        <div className="nagent-tool-group__detail">
          <View
            details={details}
            record={record}
            resolveToolCallPayload={resolveToolCallPayload}
          />
        </div>
      ) : null}
    </div>
  )
}
