/** 单个 token 类型的提交解析定义。 */
export interface TokenKindResolver {
  /** 提交给 AI 前，把 payload 解析成替换文本。解析逻辑由注册来源自己提供。 */
  resolveSubmissionText: (payload: Record<string, unknown>) => string
}

/** 可被发现并注册的 composer token 提交解析器定义。 */
export interface ComposerTokenResolverDefinition extends TokenKindResolver {
  kind: string
}
