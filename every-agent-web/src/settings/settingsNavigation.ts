/**
 * 设置页跳转意图服务（`ctx.ui.openSettingsPage` 链路的宿主侧落点）。
 *
 * 双通道配合，解决两种时序：
 * - **pending 单槽**：SettingsPanel 尚未挂载（lazy 标签首次打开）时事件无人订阅，
 *   意图先记在模块级槽位（后到覆盖先到），面板挂载时经 `consumePendingSectionJump`
 *   一次性消费（读后清空）。
 * - **领域事件**：面板已挂载/已激活时的即时跳转通知（事件经 queueMicrotask 派发，
 *   面板订阅后按「容器可见 + 段落已在 DOM」条件滚动定位，不满足则重试兜底）。
 *
 * 两通道由同一次 `requestOpenSettingsPage` 一起发出，消费侧幂等配合：
 * 挂载时先消费 pending（若事件晚到/早到被错过，pending 兜底；若两者都命中同一
 * 意图，重复跳转到同一段无害）。sectionId 省略 = 仅打开设置页不定位。
 */
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'

/** pending 跳转意图（模块级单槽，后到覆盖先到；requestedAt 便于调试，不做过期判断）。 */
let pendingSectionJump: { sectionId?: string; requestedAt: number } | null = null

/**
 * 记录设置页跳转意图并广播领域事件。
 *
 * sectionId 省略时事件 payload 以空串表达「仅打开不定位」——payload 类型里
 * sectionId 恒为 string（避免 optional 字段的二义性：undefined 与「未传」不可区分）。
 */
export function requestOpenSettingsPage(sectionId?: string): void {
  pendingSectionJump = sectionId ? { sectionId, requestedAt: Date.now() } : { requestedAt: Date.now() }
  domainEventBus.emit(DOMAIN_EVENTS.SETTINGS_SECTION_JUMP_REQUESTED, { sectionId: sectionId ?? '' })
}

/**
 * 消费 pending 跳转意图（SettingsPanel 挂载时调用一次，读后清空）。
 *
 * 返回 null 表示无待处理意图；返回的 sectionId 为空串时表示仅打开设置页、不定位。
 */
export function consumePendingSectionJump(): { sectionId: string } | null {
  if (!pendingSectionJump) return null
  const intent = { sectionId: pendingSectionJump.sectionId ?? '' }
  pendingSectionJump = null
  return intent
}
