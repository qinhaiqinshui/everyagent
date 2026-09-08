/**
 * src/utils/title.ts
 *
 * 会话 / 任务标题截断工具。
 *
 * 标题用于「全部 agent」菜单与消息身份微标的展示，过长会挤压布局，
 * 故在提交点就把用户输入截断为一个简短标题（创建 agent 时写入）。
 */

/** 截断标题：超过 max 个字符时只保留前 max 个字符。 */
export function truncateTitle(text: string, max = 30): string {
  return text && text.length > max ? text.slice(0, max) : text
}
