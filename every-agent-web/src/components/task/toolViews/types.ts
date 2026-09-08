/**
 * 工具调用美化视图的契约类型。
 *
 * 扩展点机制（目录即注册表）：在 `toolViews/` 文件夹下新增一个 `.tsx`，
 * 导出 `toolView: ToolViewDefinition` 即可为某个工具名注册"完全接管渲染"的视图；
 * 不写扩展点的工具回退到 `DefaultToolView`（= 现有默认样子）。
 */

import type { ComponentType } from 'react'
import type { AgentMessageRecord } from '@/types'

/** 工具消息状态（与 AgentMessageRecord.status 对齐）。 */
export type ToolStatus = AgentMessageRecord['status']

/**
 * 按 callId 聚合后的单条工具调用 JSON。
 * 由 `helpers.buildAggregatedToolDetail` 产出，作为美化视图的唯一输入 prop。
 */
export interface AggregatedToolDetail {
  type: 'tool_call'
  /** 工具展示名（已解析）。 */
  toolName: string
  /** 原始工具调用 ID（下发的 call.id / 结果的 toolCallId）。 */
  toolCallId: string | null
  /** 状态：success / error / ... */
  status: ToolStatus
  /** 下发参数（按 callId 取回的 arguments）；无参数时为 null。 */
  arguments: Record<string, unknown> | null
  /** 调用结果：成功且可解析为 JSON 时为对象，否则为原始文本字符串。 */
  result: unknown
}

/**
 * 美化视图组件接收的 props。
 * `details` 是聚合后的 JSON 数组（每条 merged tool 消息一个元素），
 * 单调用工具取 `details[0]` 即可完全接管渲染。
 */
export interface ToolViewProps {
  details: AggregatedToolDetail[]
  /** 主消息记录（merged 的首条 / 下发匹配到的结果消息），供需要原始 content 的场景使用。 */
  record?: AgentMessageRecord
  /** 按 callId 解析下发参数的函数，供视图需要补充参数时使用。 */
  resolveToolCallPayload?: (toolCallId: string) => Record<string, unknown> | undefined
}

/**
 * 工具美化视图定义（自描述，由目录即注册表自动发现）。
 * 一个模块可以注册多个工具名：设置 `toolViews` 数组；单工具场景直接用 `toolName`。
 */
export interface ToolViewDefinition {
  /** 精确工具名；命中后由 component 完全接管该工具的消息渲染。 */
  toolName: string
  /** 接管渲染的组件。 */
  component: ComponentType<ToolViewProps>
}

/**
 * 工具美化视图模块（目录即注册表扫描单元）。
 * 单个 `.tsx` 模块导出 `toolView`（单个）或 `toolViews`（多个）即完成注册。
 */
export interface ToolViewModuleDefinition {
  /** 单个工具名注册。 */
  toolView?: ToolViewDefinition
  /** 多个工具名共享同一组件时使用。 */
  toolViews?: ToolViewDefinition[]
}
