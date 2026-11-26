/**
 * 工作流统一搜索结果视图（`ctx.ui.registerSearchType` 的 `ResultView`）：
 * workflow-definition / workflow-instance 两 kind 的区块渲染（SearchResultsView
 * 按 kind 派发到本组件）。
 *
 * 数据 = 统一 search 命中项（PluginSearchItem，fields 由 worker 侧
 * WorkflowSearchProvider 装配：definitionId/name/instanceId/status/field/line/
 * matchIndex/matchText 等）。分组：
 * - 定义：一个定义一组（组头 = 名称 + 「定义」徽标），点击 → workflow-editor 标签页；
 * - 实例：一个实例一组（组头 = 工作流名 + 状态徽标），命中行 = 「字段」前缀 + 命中
 *   文本（命中片段高亮），点击 → workflow-instance 标签页。
 *
 * 打开动作经插件 ctx.ui.openTab（tab.data 契约见 index.ts 头注释）；workspaceId
 * 取自命中项 fields（worker 侧带出，避免前端再反查）。纯展示组件：无本地状态、
 * 无 RPC（只读 props），样式内联（跟随宿主主题 token）。
 */
import React from 'react'
import type { PluginSearchItem, SearchTypeResultViewProps } from '@everyagent/plugin-api'
import { statusVisual, type RenderStatus } from './canvas/renderStates'
import { WorkflowIcon } from './icons'
import { getPluginContext } from './pluginRuntime'
import { WORKFLOW_EDITOR_TAB_TYPE, WORKFLOW_INSTANCE_TAB_TYPE } from './tabKeys'
import { rpcErrorMessage, type WorkflowInstanceStatus } from './workflowGateway'

// ─── fields 读取小工具（worker 侧 fields 袋，宽松容错） ────────────────────

function str(v: unknown): string {
  return typeof v === 'string' ? v : v == null ? '' : String(v)
}

function num(v: unknown): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : -1
}

interface DefFields {
  definitionId: string
  name: string
  field: string
  line: string
  matchIndex: number
  matchText: string
}

function defFieldsOf(item: PluginSearchItem): DefFields {
  return {
    definitionId: str(item.definitionId),
    name: str(item.name),
    field: str(item.field),
    line: str(item.line),
    matchIndex: num(item.matchIndex),
    matchText: str(item.matchText),
  }
}

interface InstFields {
  instanceId: string
  definitionId: string
  definitionName: string
  status: string
  workspaceId: string
  field: string
  line: string
  matchIndex: number
  matchText: string
}

function instFieldsOf(item: PluginSearchItem): InstFields {
  return {
    instanceId: str(item.instanceId),
    definitionId: str(item.definitionId),
    definitionName: str(item.definitionName),
    status: str(item.status),
    workspaceId: str(item.workspaceId),
    field: str(item.field),
    line: str(item.line),
    matchIndex: num(item.matchIndex),
    matchText: str(item.matchText),
  }
}

/** 实例状态 → 画布状态视觉键（waiting-user → waiting）。 */
function toRenderStatus(status: string): RenderStatus {
  switch (status) {
    case 'created': return 'created'
    case 'running': return 'running'
    case 'waiting-user': return 'waiting'
    case 'done': return 'done'
    case 'failed': return 'failed'
    case 'cancelled': return 'cancelled'
    default: return 'created'
  }
}

/** 命中文本渲染：matchIndex/matchText 命中片段高亮（取不到时原样渲染）。 */
function HighlightedLine({ line, matchIndex, matchText }: {
  line: string
  matchIndex: number
  matchText: string
}) {
  if (matchIndex < 0 || !matchText || matchIndex + matchText.length > line.length) {
    return <span style={lineStyle}>{line}</span>
  }
  return (
    <span style={lineStyle}>
      {line.slice(0, matchIndex)}
      <mark style={markStyle}>{line.slice(matchIndex, matchIndex + matchText.length)}</mark>
      {line.slice(matchIndex + matchText.length)}
    </span>
  )
}

// ─── 定义视图 ─────────────────────────────────────────────────────────────

/** kind=workflow-definition 的区块：一个定义一组（组头点击打开定义编辑器）。 */
export function WorkflowDefinitionResultView(props: SearchTypeResultViewProps) {
  const groups = new Map<string, { name: string; workspaceId: string; hits: DefFields[] }>()
  for (const item of props.items) {
    const f = defFieldsOf(item)
    if (!f.definitionId) continue
    let g = groups.get(f.definitionId)
    if (!g) {
      g = { name: f.name || f.definitionId, workspaceId: str(item.workspaceId), hits: [] }
      groups.set(f.definitionId, g)
    }
    g.hits.push(f)
  }
  if (groups.size === 0) return null

  const open = (definitionId: string, name: string, workspaceId: string) => {
    try {
      getPluginContext().ui.openTab(
        WORKFLOW_EDITOR_TAB_TYPE,
        { workspaceId, definitionId },
        name || definitionId,
      )
    } catch (err) {
      console.warn('[workflow] 打开定义编辑器失败:', rpcErrorMessage(err))
    }
  }

  return (
    <div style={blockStyle}>
      {[...groups.entries()].map(([definitionId, g]) => (
        <div key={definitionId}>
          <div
            style={{ ...groupHeaderStyle, cursor: 'pointer' }}
            onClick={() => open(definitionId, g.name, g.workspaceId)}
            title="打开工作流定义"
          >
            <WorkflowIcon size={14} />
            <span style={groupNameStyle}>{g.name}</span>
            <span style={badgeStyle}>定义</span>
            <span style={groupDetailStyle}>{definitionId}</span>
          </div>
          {g.hits.map((hit, i) => (
            <div
              key={`${hit.field}\u0000${hit.matchIndex}\u0000${i}`}
              style={hitRowStyle}
              onClick={() => open(definitionId, g.name, g.workspaceId)}
            >
              <span style={hitPrefixStyle}>{hit.field}</span>
              <HighlightedLine line={hit.line} matchIndex={hit.matchIndex} matchText={hit.matchText} />
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}

// ─── 实例视图 ─────────────────────────────────────────────────────────────

/** kind=workflow-instance 的区块：一个实例一组（组头/命中行点击打开实例页）。 */
export function WorkflowInstanceResultView(props: SearchTypeResultViewProps) {
  const groups = new Map<string, { f: InstFields; hits: InstFields[] }>()
  for (const item of props.items) {
    const f = instFieldsOf(item)
    if (!f.instanceId) continue
    let g = groups.get(f.instanceId)
    if (!g) {
      g = { f, hits: [] }
      groups.set(f.instanceId, g)
    }
    g.hits.push(f)
  }
  if (groups.size === 0) return null

  const open = (f: InstFields) => {
    try {
      getPluginContext().ui.openTab(
        WORKFLOW_INSTANCE_TAB_TYPE,
        { instanceId: f.instanceId, workspaceId: f.workspaceId },
        f.definitionName || f.instanceId,
      )
    } catch (err) {
      console.warn('[workflow] 打开实例页失败:', rpcErrorMessage(err))
    }
  }

  return (
    <div style={blockStyle}>
      {[...groups.entries()].map(([instanceId, g]) => {
        const visual = statusVisual(toRenderStatus(g.f.status) || 'created')
        return (
          <div key={instanceId}>
            <div
              style={{ ...groupHeaderStyle, cursor: 'pointer' }}
              onClick={() => open(g.f)}
              title="打开工作流实例"
            >
              <WorkflowIcon size={14} />
              <span style={groupNameStyle}>{g.f.definitionName || instanceId}</span>
              <span style={{
                ...badgeStyle,
                color: visual.stroke,
                borderColor: visual.stroke,
              }}
              >{visual.label || statusLabelFallback(g.f.status as WorkflowInstanceStatus)}</span>
              <span style={groupDetailStyle}>{instanceId}</span>
            </div>
            {g.hits.map((hit, i) => (
              <div
                key={`${hit.field}\u0000${i}`}
                style={hitRowStyle}
                onClick={() => open(hit)}
              >
                <span style={hitPrefixStyle}>{hit.field}</span>
                <HighlightedLine line={hit.line} matchIndex={hit.matchIndex} matchText={hit.matchText} />
              </div>
            ))}
          </div>
        )
      })}
    </div>
  )
}

/** 未知状态兜底文案（statusVisual.label 不含 created 的展示名时）。 */
function statusLabelFallback(status: WorkflowInstanceStatus): string {
  switch (status) {
    case 'created': return '已创建'
    case 'running': return '运行中'
    case 'waiting-user': return '等待用户'
    case 'done': return '完成'
    case 'failed': return '失败'
    case 'cancelled': return '已取消'
    default: return status
  }
}

// ─── 内联样式（跟随宿主主题 token；不引入插件外 CSS） ─────────────────────

const blockStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  paddingBottom: 6,
}

const groupHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '5px 8px',
  borderRadius: 'var(--radius-md, 6px)',
  color: 'var(--text-primary)',
  fontSize: 13,
  fontWeight: 600,
  userSelect: 'none',
}

const groupNameStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const badgeStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 11,
  fontWeight: 500,
  padding: '0 6px',
  borderRadius: 999,
  border: '1px solid var(--border)',
  color: 'var(--text-secondary)',
}

const groupDetailStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 11,
  color: 'var(--text-tertiary, var(--text-secondary))',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const hitRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 8,
  padding: '2px 8px 2px 28px',
  fontSize: 12,
  cursor: 'pointer',
  color: 'var(--text-secondary)',
  overflow: 'hidden',
}

const hitPrefixStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 11,
  color: 'var(--text-tertiary, var(--text-secondary))',
  minWidth: 72,
  textAlign: 'right',
}

const lineStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const markStyle: React.CSSProperties = {
  background: 'var(--accent-yellow, rgba(255, 213, 79, 0.35))',
  color: 'inherit',
  borderRadius: 2,
  padding: '0 1px',
}
