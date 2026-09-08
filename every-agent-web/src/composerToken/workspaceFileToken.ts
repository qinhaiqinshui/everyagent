/**
 * src/composerToken/workspaceFileToken.ts
 *
 * `@` 文件引用 token 的本地工厂（工作区模型，方案 §5.8）。
 * 自 n 分支 plugins/workspace/workspaceFileToken.ts 移植:hub 版没有插件体系,
 * 提交解析器在模块加载时直接登记进 composerTokenRegistry。
 *
 * 关键约束：
 * - 交给 AI 的引用是「工作区相对路径」（无前导 `/`，与 AI 文件工具「相对任务工作区根」
 *   一致），为与相邻中文/正文清晰分隔，其前后各带一个空格（见下方 resolveSubmissionText）。
 *   不读取、也不注入文件内容，后续如何使用文件完全由 AI 自己决定。
 * - `fullPath`（带前导 `/` 的业务绝对路径）仅用于展示与点击打开，不进入 AI 上下文。
 * - 输入框与消息回放都通过 `label`（文件名）原样展示为 chip。
 */

import { buildOpaqueTokenText, parseOpaqueTokenText } from '@/composerToken/composerOpaqueToken'
import { composerTokenRegistry } from '@/composerToken/composerTokenRegistry'
import type { ComposerTokenResolverDefinition } from '@/composerToken/types'
import type { WorkspaceFileEntry } from '@/query/workspaceFileQueryService'
import type { ChatComposerToken } from '@/types'
import { createSnowflakeId } from '@/utils/snowflakeId'

/** 文件引用 token 的固定 kind。 */
export const WORKSPACE_FILE_TOKEN_KIND = 'system.workspace_file'

/** 文件引用 token 的结构化载荷（也作为提交解析的 payload 形状）。 */
export interface WorkspaceFileTokenPayload {
  /** 工作区内相对路径（无前导 `/`，交给 AI 的引用形式）。 */
  path: string
  /** 文件名（相对路径最后一段）。 */
  fileName: string
  /** 完整业务路径（带前导 `/`，仅用于展示与点击打开）。 */
  fullPath: string
}

/** 从文件列举条目构造文件引用 token（自包含：label=文件名、summary=完整路径在顶层明文段）。 */
export function buildWorkspaceFileToken(entry: WorkspaceFileEntry): ChatComposerToken {
  const payload: WorkspaceFileTokenPayload = {
    path: entry.path,
    fileName: entry.name,
    fullPath: entry.fullPath,
  }
  return {
    id: createSnowflakeId('composer_token'),
    kind: WORKSPACE_FILE_TOKEN_KIND,
    label: entry.name,
    summary: entry.fullPath,
    opaqueText: buildOpaqueTokenText(WORKSPACE_FILE_TOKEN_KIND, {
      label: entry.name,
      summary: entry.fullPath,
      payload: { fullPath: entry.fullPath, path: entry.path, fileName: entry.name },
    }),
  }
}

/** 读取文件引用 token 的结构化载荷（从 opaque 串解析，与渲染解耦）。 */
export function readWorkspaceFileToken(token: ChatComposerToken): WorkspaceFileTokenPayload | null {
  const parsed = parseOpaqueTokenText(token.opaqueText)
  if (!parsed || parsed.kind !== WORKSPACE_FILE_TOKEN_KIND) {
    return null
  }
  const p = parsed.payload
  const path = typeof p.path === 'string' ? p.path.trim() : ''
  const fullPath = typeof p.fullPath === 'string' ? p.fullPath.trim() : ''
  if (!path || !fullPath) {
    return null
  }
  return {
    path,
    fileName: typeof p.fileName === 'string' ? p.fileName.trim() : path.split('/').pop() ?? path,
    fullPath,
  }
}

/** 判断某个 token 是否为文件引用 token。 */
export function isWorkspaceFileToken(token: ChatComposerToken): boolean {
  return readWorkspaceFileToken(token) !== null
}

/**
 * 从文件引用 token 载荷构造 `openGlobalFileTab` 的目标参数。
 * 统一承载「点击打开文件」这一语义，供输入框与消息回放复用。
 * 工作区模型下文件以完整业务路径为唯一身份，直接用 fullPath 打开。
 */
export function buildWorkspaceFileTabTarget(payload: WorkspaceFileTokenPayload): {
  filePath: string
} {
  return {
    filePath: payload.fullPath,
  }
}

/** 文件引用 token 的提交解析定义（n 版经插件登记,这里模块加载时直接登记）。 */
export const workspaceFileTokenResolver: ComposerTokenResolverDefinition = {
  kind: WORKSPACE_FILE_TOKEN_KIND,
  // 文件 AI 可见文本 = 工作区相对路径（无前导 `/`，与 AI 文件工具「相对任务工作区根」
  // 一致，避免被当成 Unix 绝对路径；前后各补一个空格，避免与相邻文本粘连）。
  resolveSubmissionText: (payload) => {
    const rel = String(payload.path ?? payload.fullPath ?? '').replace(/^\/+/, '')
    return ` ${rel} `
  },
}

// 模块加载即登记提交解析器:replaceComposerTokensForSubmission 按 kind 命中此处,
// 把 @ 文件胶囊还原成「 工作区相对路径 」明文交给 AI。
composerTokenRegistry.registerDefinition(workspaceFileTokenResolver)
