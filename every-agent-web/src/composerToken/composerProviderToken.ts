import type {
  ChatComposerProvider,
  ChatComposerToken,
} from '@/types'
import { createSnowflakeId } from '@/utils/snowflakeId'
import { buildOpaqueTokenText } from '@/composerToken/composerOpaqueToken'

function cloneProviderMetadata(metadata: Record<string, unknown> | undefined): Record<string, unknown> {
  return metadata ? { ...metadata } : {}
}

/**
 * 把 `@` 候选项转换成结构化 token。
 * 输入增强能力最终进入 `draft.tokens`，而不是退回纯文本拼接。
 */
export function buildDraftTokenFromComposerProvider(provider: ChatComposerProvider): ChatComposerToken {
  const metadata = cloneProviderMetadata(provider.metadata)
  const tokenKind = typeof metadata.tokenKind === 'string' && metadata.tokenKind.trim()
    ? metadata.tokenKind.trim()
    : provider.id
  const tokenId = createSnowflakeId('composer_token')
  const payload = {
    providerId: provider.id,
    trigger: provider.trigger ?? '@',
    ...metadata,
  }

  const label = provider.title
  const summary = provider.description
  return {
    id: tokenId,
    kind: tokenKind,
    label,
    summary,
    opaqueText: buildOpaqueTokenText(tokenKind, { label, summary, payload }),
  }
}
