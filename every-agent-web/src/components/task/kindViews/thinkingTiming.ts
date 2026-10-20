/**
 * 思考过程耗时口径(纯函数 + 内联色阶,与 toolViews/helpers 的工具耗时色阶并列)。
 *
 * 与工具耗时的异同(同一套「颜色深浅 = 快慢」的表达,数值门槛各自独立):
 * - 同:文本格式化复用 {@link formatToolDuration}(亚秒毫秒/一位小数/整秒/分钟补零),
 *   色阶写法同为「写 --thinking-dur-tint 自定义属性,颜色插值留在样式层」。
 * - 异:工具耗时 ≥10s 才显示(快调用的耗时只是噪音);思考耗时**始终显示**——
 *   它本身就是用户关心的过程信号,且思考常见量级更大,故门槛只作用于**色阶起色**。
 *
 * 起色口径按「常见思考耗时」定:数秒内属正常,10s 起逐步转红,60s 封顶(极慢)。
 */
import type { CSSProperties } from 'react'
import { formatToolDuration } from '@/components/task/toolViews/helpers'

/** 色阶起色阈值(10s):常见思考耗时在此以内,不染色;超过后向正红线性渐变。 */
const THINKING_SLOW_TINT_START_MS = 10_000
/** 封顶阈值(60s):思考超过 1 分钟即「极慢」,恒定最红不再加深。 */
const THINKING_SLOW_TINT_MAX_MS = 60_000
/** 起色百分比 / 封顶百分比(color-mix 里 --tool-slow-red 的占比),与工具耗时同刻度。 */
const THINKING_SLOW_TINT_MIN_PCT = 20
const THINKING_SLOW_TINT_MAX_PCT = 100

/**
 * 思考耗时 → 颜色深浅百分比(0~100),供 CSS 的
 * `color-mix(in srgb, --tool-slow-red P%, --text-muted)` 插值。
 * <10s 返回 0(纯 --text-muted);10s~60s 线性升到封顶;≥60s 恒定封顶。非法/缺失返回 0。
 */
export function thinkingDurationTint(ms: number | null | undefined): number {
  if (ms == null || !Number.isFinite(ms) || ms < THINKING_SLOW_TINT_START_MS) return 0
  const ratio = (ms - THINKING_SLOW_TINT_START_MS) / (THINKING_SLOW_TINT_MAX_MS - THINKING_SLOW_TINT_START_MS)
  const clamped = Math.min(1, Math.max(0, ratio))
  return Math.round(THINKING_SLOW_TINT_MIN_PCT + clamped * (THINKING_SLOW_TINT_MAX_PCT - THINKING_SLOW_TINT_MIN_PCT))
}

/** 思考耗时 → `--thinking-dur-tint` 内联样式(视图只喂数值,色值插值留给 CSS)。 */
export function thinkingDurationTintStyle(ms: number | null | undefined): CSSProperties {
  return { '--thinking-dur-tint': `${thinkingDurationTint(ms)}%` } as CSSProperties
}

/**
 * 思考耗时文本:非法/缺失/非正数返回空串(调用方据此不渲染耗时 span),
 * 否则复用工具耗时的紧凑格式化({@link formatToolDuration})。
 */
export function thinkingDurationText(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms <= 0) return ''
  return formatToolDuration(ms)
}