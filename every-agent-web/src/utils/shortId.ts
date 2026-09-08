/**
 * 把较长的 agentId 截成短标签，用于会话下拉、消息「更多」栏等紧凑位置展示。
 * 长度不超过 8 时原样返回，否则取前 8 位并追加省略号。
 */
export function shortAgentId(id: string): string {
  const trimmed = id.trim()
  if (trimmed.length <= 8) {
    return trimmed
  }
  return `${trimmed.slice(0, 8)}…`
}
