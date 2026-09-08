import type {
  ComposerTokenResolverDefinition,
  TokenKindResolver,
} from '@/composerToken/types'

/**
 * token kind 注册中心（提交解析）。
 *
 * 与 `slashCommandRegistry` 互不回查：slash 层只负责候选列表 + `select`，
 * 本注册中心只负责「按 kind 把 payload 解析成提交给 AI 的文本」。
 * resolver 的入参 `payload` 形状由该来源在 `select` 里构造时自行约定、自行解析（见各 token 实现模块）。
 *
 * 注册路径：各来源不再自行 import 注册，统一由 `Plugin.composerTokenResolvers` 字段声明，
 * 在 `registerPlugin()` 阶段经 `registerDefinition(...)` 登记进本中心；删插件即自动注销。
 */

const registry = new Map<string, TokenKindResolver>()

export const composerTokenRegistry = {
  /** 注册一个 token 类型的提交解析器（同 kind 覆盖）。 */
  register(kind: string, resolver: TokenKindResolver): void {
    registry.set(kind, resolver)
  },
  /** 注册一个可发现的 token 类型定义。 */
  registerDefinition(definition: ComposerTokenResolverDefinition): void {
    registry.set(definition.kind, {
      resolveSubmissionText: definition.resolveSubmissionText,
    })
  },
  /** 注销一个 token 类型。 */
  unregister(kind: string): void {
    registry.delete(kind)
  },
  /** 按 kind 解析提交文本；未知 kind 返回空串（由调用方回退到 opaqueText）。 */
  resolve(kind: string, payload: Record<string, unknown>): string {
    const resolver = registry.get(kind)
    return resolver ? resolver.resolveSubmissionText(payload) : ''
  },
  /** 是否已注册该 kind 的解析器。 */
  has(kind: string): boolean {
    return registry.has(kind)
  },
}
