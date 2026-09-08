import type { ChatComposerToken } from '@/types'

/**
 * 胶囊视图（草稿与回放共用的唯一真相源）。
 *
 * 渲染层只读取 token 的通用字段（`label`），**零类型分支、不回查任何注册中心、
 * 不解码 payload**。胶囊显示完全由 `token.label` 决定，而 `token.label` 来自
 * opaque 串顶层的 `label` 明文段（见方案 §4.5 / §4.7.1）—— 即便这条 rawContent
 * 是从别处复制粘贴来的、没有 `tokens` 数组，也能渲染出正确胶囊。
 */
export interface ComposerChipView {
  /** 胶囊 DOM 身份（草稿用真实 id，回放/自包含场景可用派生 id）。 */
  tokenId: string
  /** 胶囊显示文字。 */
  label: string
}

/** 从 token 取得统一胶囊视图。不分支类型、不解码 payload。 */
export function getComposerChipView(token: ChatComposerToken): ComposerChipView {
  return { tokenId: token.id, label: token.label || '' }
}
