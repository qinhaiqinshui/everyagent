/**
 * 工具美化视图统一出口。
 *
 * 步骤 10 批壳模型后,「连续同名 ×N」聚合壳(GroupedToolCallView/renderAggregatedToolDetails)
 * 退役删除:批壳调用行与孤儿 tool.result 视图直接经 getToolView 消费 per-tool 美化视图
 * (registry 与各专用视图不动)。
 */

export { getToolView, hasToolView } from './registry'
export type { ToolViewProps, ToolViewDefinition, AggregatedToolDetail, ToolStatus } from './types'
