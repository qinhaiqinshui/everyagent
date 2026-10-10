/**
 * 格式化 token 数量（万 / k 缩写）。
 *
 * 供上下文电池、agent 悬停信息卡等紧凑位置展示。
 */
export function formatTokenCount(value: number | undefined): string {
  const count = value ?? 0
  if (count >= 10000) return `${(count / 10000).toFixed(count >= 100000 ? 0 : 1)}万`
  if (count >= 1000) return `${(count / 1000).toFixed(count >= 10000 ? 0 : 1)}k`
  return String(count)
}
