/**
 * 时长格式化工具：把毫秒转成面向用户的简短中文描述。
 * 供「重试等待时长」「超时提示」等场景复用，避免各处重复实现。
 */

/**
 * 把毫秒格式化为「X 秒」/「X 分 Y 秒」/「X 分钟」。
 * - 不足 1 秒按 1 秒计（避免出现「0 秒后重试」这类无意义文案）。
 */
export function formatDurationMs(ms: number): string {
  const totalSeconds = Math.max(1, Math.round(ms / 1000))
  if (totalSeconds < 60) {
    return `${totalSeconds} 秒`
  }
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return seconds === 0 ? `${minutes} 分钟` : `${minutes} 分 ${seconds} 秒`
}

/**
 * 把剩余毫秒格式化为倒计时文案。
 * - 0 毫秒显示为「0 秒」，用于重试倒计时结束时的最终刷新。
 * - 其它值按向上取整，避免倒计时过早跳到下一档。
 */
export function formatCountdownMs(ms: number): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000))
  if (totalSeconds < 60) {
    return `${totalSeconds} 秒`
  }
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return seconds === 0 ? `${minutes} 分钟` : `${minutes} 分 ${seconds} 秒`
}
