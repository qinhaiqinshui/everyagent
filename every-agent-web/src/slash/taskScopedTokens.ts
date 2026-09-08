/**
 * src/slash/taskScopedTokens.ts
 *
 * 任务级 token（底部胶囊）辅助模块：负责把任务 meta 里的 `slashTaskTokens`
 * （一组自包含 opaque token 串）解析为 ChatComposerToken，并提供
 * 去重 / 增删 / apply / cancel 的纯函数与 RPC 出口。
 *
 * 说明：本模块不涉及任务存储写入（taskStore 镜像里的 slashTaskTokens 由
 * 后续步骤接线），只提供「解析数组 + 操作本地 token 列表 + 调 RPC」能力。
 */
import type { ChatComposerToken } from '@/types'
import { parseOpaqueTokenText } from '@/composerToken/composerOpaqueToken'
import { hubSession } from '@/hub/session'
import { taskStore } from '@/hub/taskStore'
import { createSnowflakeId } from '@/utils/snowflakeId'

/**
 * 把任务 meta 的 `slashTaskTokens: string[]` 解析为 ChatComposerToken[]。
 * - 每项先用 parseOpaqueTokenText 校验，非法串直接跳过；
 * - 合法项构造 ChatComposerToken（id 用 createSnowflakeId('composer_token')，
 *   kind/label/summary 取自解析结果，opaqueText 为原始串）；
 * - 保持输入顺序，并按 opaqueText 去重（重复只保留首个）。
 */
export function parseTaskScopeTokens(
  metaTokens: string[] | undefined,
): ChatComposerToken[] {
  if (!metaTokens || metaTokens.length === 0) {
    return []
  }
  const seen = new Set<string>()
  const tokens: ChatComposerToken[] = []
  for (const opaque of metaTokens) {
    if (seen.has(opaque)) {
      continue
    }
    const parsed = parseOpaqueTokenText(opaque)
    if (!parsed) {
      continue
    }
    seen.add(opaque)
    tokens.push({
      id: createSnowflakeId('composer_token'),
      kind: parsed.kind,
      label: parsed.label,
      summary: parsed.summary,
      opaqueText: opaque,
    })
  }
  return tokens
}

/**
 * 从 opaque 串中提取 payload.slashId。
 * 仅当 payload.slashId 为字符串且非空时返回，否则返回 undefined。
 */
export function extractSlashId(opaque: string): string | undefined {
  const parsed = parseOpaqueTokenText(opaque)
  const slashId = parsed?.payload?.slashId
  return typeof slashId === 'string' && slashId.trim() !== '' ? slashId : undefined
}

/**
 * 把 token 插入/替换进任务 token 列表（按 opaqueText 去重）。
 * 已存在同 opaqueText 项时整体替换为传入项，否则追加到末尾；返回新数组。
 */
export function upsertTaskToken(
  tokens: ChatComposerToken[],
  token: ChatComposerToken,
): ChatComposerToken[] {
  const index = tokens.findIndex((entry) => entry.opaqueText === token.opaqueText)
  if (index >= 0) {
    const next = tokens.slice()
    next[index] = token
    return next
  }
  return [...tokens, token]
}

/**
 * 从任务 token 列表中删除目标项：匹配 id 或 opaqueText。
 * 返回新数组（原数组不变）。
 */
export function removeTaskToken(
  tokens: ChatComposerToken[],
  target: { id?: string; opaque?: string },
): ChatComposerToken[] {
  return tokens.filter((entry) => {
    if (target.id !== undefined && entry.id === target.id) {
      return false
    }
    if (target.opaque !== undefined && entry.opaqueText === target.opaque) {
      return false
    }
    return true
  })
}

/** 把任务级 token 应用到指定任务（RPC `slash.taskTokens.apply`）。 */
export async function applyTaskToken(params: {
  taskId: string
  id: string
  token: string
}): Promise<void> {
  const owner = taskStore.get(params.taskId)?.workerId
  if (!owner) throw new Error('无法确定任务所属 worker')
  const result = await hubSession.rpcTo(owner, 'slash.taskTokens.apply', params) as {
    applied?: boolean
  }
  if (result.applied !== true) {
    throw new Error(`应用任务级 token 失败：worker 未确认（applied !== true，id=${params.id}）`)
  }
}

/**
 * 取消一个任务级 token（RPC `slash.cancel`）。
 * 取消是用户主动动作，失败不应卡 UI：RPC 抛错/离线时返回 { removed: false }
 * 并 console.warn，不向上抛。
 */
export async function cancelTaskToken(params: {
  id?: string
  token: string
  taskId?: string
}): Promise<{ removed: boolean }> {
  try {
    const owner = params.taskId ? taskStore.get(params.taskId)?.workerId : undefined
    if (!owner) throw new Error('无法确定任务所属 worker')
    const result = await hubSession.rpcTo(owner, 'slash.cancel', params) as {
      removed?: boolean
    }
    return { removed: result.removed === true }
  } catch (error) {
    console.warn('[slash] cancelTaskToken 失败：', error)
    return { removed: false }
  }
}