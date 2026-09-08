/**
 * src/slash/remoteSlashProvider.ts
 *
 * 把 worker 的 `/` 候选(经 `slash.list` RPC)接入本地 slashCommandRegistry。
 *
 * 选中行为与老项目一致:slash 层只判断 `isOpaqueTokenText(select返回值)`,
 * opaque → 插胶囊,纯文本 → 原样插入;select 的数据(insertText)由后端构造
 * (skill 条目即 `system.skill` opaque token,payload 含 skillId/title)。
 * 提交后 opaque 串原样到达后端,由 SlashTokenResolveAdvisor 解析为技能名。
 */
import { slashCommandRegistry } from '@/slash/slashCommandRegistry'
import type { SlashCommandItem, SlashSelectionResult } from '@/slash/types'
import {
  listRemoteSlashItems,
  selectRemoteSlashItem,
  type RemoteSlashItem,
} from '@/query/slashGateway'

/**
 * 远程条目 → 本地 SlashCommandItem。
 * select 改为异步走 `slash.select` RPC（worker 注入 slashId 并可能返回 bottom 位置）；
 * RPC 失败时回退 `{ token: item.insertText, position: 'inline' }`，避免输入框卡死。
 */
function toLocalItem(item: RemoteSlashItem, workerId: string): SlashCommandItem {
  return {
    id: item.id,
    title: item.title,
    subtitle: item.subtitle,
    icon: item.icon,
    group: item.group,
    defaultSelected: item.defaultSelected === true,
    select: async (): Promise<SlashSelectionResult | SlashSelectionResult[]> => {
      try {
        const result = await selectRemoteSlashItem(item.id, { workerId })
        return result.results.map((entry) => ({
          id: entry.id,
          token: entry.token,
          position: entry.position,
        }))
      } catch (error) {
        console.warn(`[slash] selectRemoteSlashItem 失败，回退 insertText:`, error)
        return [{ token: item.insertText, position: 'inline' }]
      }
    },
  }
}

/** 注册「worker 数据源」provider;进程内幂等(同 id 覆盖)。 */
export function registerRemoteSlashProvider(): void {
  slashCommandRegistry.registerProvider('worker', async (workerId?: string) => {
    const items = await listRemoteSlashItems(workerId ?? '')
    return items.map((item) => toLocalItem(item, workerId ?? ''))
  })
}
