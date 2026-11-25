/**
 * 节点/边运行态 → 颜色与动画映射（画布只消费本表，不含色彩决策）。
 *
 * 色彩语言照抄宿主 TaskStatusDot（components/task/TaskStatusDot.tsx 的
 * buildStatusDotStyle：completed=--accent-green / error=--accent-red /
 * waiting-user=--accent-amber / running=--accent-blue / 其余=text-muted+bg-tertiary）。
 * 插件不能 import 宿主模块，但运行在宿主 DOM 内，CSS 变量直接可用；
 * var() 第二参为色值兜底（照抄宿主 ui-tokens.css 的暗色主题值，防变量缺席）。
 *
 * 兜底色值来源（ui-tokens.css）：green #41d19c / blue #61a5ff / red #ff6b6b /
 * amber #f7bd54 / cyan #54d2e8 / purple #b59cff / muted #6f7a8b /
 * text-secondary #aab3c2 / border-strong #3b4658。
 */

/** 节点运行态（实例视图投影；编辑态无 status=中性灰）。cancelled=实例终态收口中断的在飞运行。 */
export type RenderStatus = 'created' | 'waiting' | 'running' | 'done' | 'failed' | 'skipped' | 'cancelled'

/** 状态视觉（stroke=节点描边与状态点；markerId=画布 defs 里的箭头 marker key）。 */
export interface StatusVisual {
  stroke: string
  /** 状态点填充色（同 stroke 系）。 */
  badgeFill: string
  /** 画布箭头 marker id（marker 不吃 CSS 继承，按状态预置枚举）。 */
  markerId: string
  /** running 专属：呼吸/流动动画开关。 */
  animated: boolean
  label: string
}

export const STATUS_VISUALS: Record<RenderStatus, StatusVisual> = {
  done: {
    stroke: 'var(--accent-green, #41d19c)',
    badgeFill: 'var(--accent-green, #41d19c)',
    markerId: 'wf-arrow-done',
    animated: false,
    label: '完成',
  },
  running: {
    stroke: 'var(--accent-blue, #61a5ff)',
    badgeFill: 'var(--accent-blue, #61a5ff)',
    markerId: 'wf-arrow-running',
    animated: true,
    label: '运行中',
  },
  failed: {
    stroke: 'var(--accent-red, #ff6b6b)',
    badgeFill: 'var(--accent-red, #ff6b6b)',
    markerId: 'wf-arrow-failed',
    animated: false,
    label: '失败',
  },
  waiting: {
    stroke: 'var(--accent-amber, #f7bd54)',
    badgeFill: 'var(--accent-amber, #f7bd54)',
    markerId: 'wf-arrow-waiting',
    animated: false,
    label: '等待',
  },
  created: {
    stroke: 'var(--text-muted, #6f7a8b)',
    badgeFill: 'var(--text-muted, #6f7a8b)',
    markerId: 'wf-arrow',
    animated: false,
    label: '未运行',
  },
  skipped: {
    stroke: 'var(--text-muted, #6f7a8b)',
    badgeFill: 'var(--text-muted, #6f7a8b)',
    markerId: 'wf-arrow',
    animated: false,
    label: '跳过',
  },
  cancelled: {
    // 实例终态收口中断的在飞运行：非节点自身失败，warn 色系与实例级 cancelled 口径一致
    stroke: 'var(--accent-amber, #f7bd54)',
    badgeFill: 'var(--accent-amber, #f7bd54)',
    markerId: 'wf-arrow-waiting',
    animated: false,
    label: '已中断',
  },
}

/** 无 status 的中性视觉（编辑态未知类型兜底 / 运行图未运行节点描边）。
 *  灰取 text-secondary：border-strong(#3b4658) 对深底对比度仅 ~2:1，太不显眼。 */
export const NEUTRAL_STATUS_VISUAL: StatusVisual = {
  stroke: 'var(--text-secondary, #aab3c2)',
  badgeFill: 'var(--text-secondary, #aab3c2)',
  markerId: 'wf-arrow',
  animated: false,
  label: '',
}

/** status 可缺省：undefined → 中性（编辑态）。 */
export function statusVisual(status?: RenderStatus | null): StatusVisual {
  return status ? (STATUS_VISUALS[status] ?? NEUTRAL_STATUS_VISUAL) : NEUTRAL_STATUS_VISUAL
}

/** 节点类型强调色（无 status 时节点描边/图标用类型色区分）。 */
export const NODE_TYPE_ACCENT: Record<string, string> = {
  start: 'var(--accent-green, #41d19c)',
  command: 'var(--accent-cyan, #54d2e8)',
  ai: 'var(--accent-purple, #b59cff)',
}

/** 节点描边优先级：status > 类型色 > 中性边框。 */
export function nodeStrokeColor(type: string, status?: RenderStatus | null): string {
  if (status) return statusVisual(status).stroke
  return NODE_TYPE_ACCENT[type] ?? NEUTRAL_STATUS_VISUAL.stroke
}
