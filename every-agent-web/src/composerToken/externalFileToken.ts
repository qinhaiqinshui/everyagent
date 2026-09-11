/**
 * src/composerToken/externalFileToken.ts
 *
 * `@` 弹窗外部文件引用 token 的本地工厂(工作区外文件/目录)。
 * 风格对齐 workspaceFileToken.ts,但语义关键差异:**不注册** composerTokenRegistry
 * 的提交解析器。
 *
 * 关键语义:
 * - 本 kind 在前端**不做提交解析**:replaceComposerTokensForSubmission 对注册中心
 *   未登记的 kind 兜底「保留原始 opaque 串」(见 composerOpaqueToken 的
 *   resolveTokenReplacement),因此提交上行的是自包含的
 *   `[[[[agent-token::::system.external_file||||…]]]]` 原串。
 * - 原因:外部文件引用指向任务工作区**之外**的绝对路径,是否可读、如何进入 AI
 *   上下文属于授权与越界判定问题(worker 侧 PermissionGate / jailed 路径校验),
 *   前端不应也不能把它折叠成明文路径。由 worker 侧解析 opaque 原串后统一裁决。
 * - 胶囊渲染与 kind 无关(composerChipRenderer 只读 label),输入框与消息回放
 *   都通过 `label`(文件名)原样展示,`summary`(绝对路径)供悬浮/辅助信息使用。
 */

import { buildOpaqueTokenText, parseOpaqueTokenText } from '@/composerToken/composerOpaqueToken'
import type { ChatComposerToken } from '@/types'
import { createSnowflakeId } from '@/utils/snowflakeId'

/** 外部文件引用 token 的固定 kind。 */
export const EXTERNAL_FILE_TOKEN_KIND = 'system.external_file'

/** 外部文件引用 token 的结构化载荷。 */
export interface ExternalFileTokenPayload {
  /** 工作区外条目的绝对路径(原样保留,不折叠、不做相对化)。 */
  absolutePath: string
  /** 文件名(路径最后一段,胶囊 label)。 */
  fileName: string
  /** 条目类别:文件或目录。 */
  kind: 'file' | 'directory'
}

/** 构造外部文件引用 token 的输入条目(来自 `@` 弹窗外部文件列举)。 */
export interface ExternalFileEntry {
  /** 工作区外条目的绝对路径。 */
  absolutePath: string
  /** 文件名(路径最后一段)。 */
  fileName: string
  /** 条目类别:文件或目录。 */
  kind: 'file' | 'directory'
}

/** 从外部文件条目构造引用 token(自包含:label=文件名、summary=绝对路径在顶层明文段)。 */
export function buildExternalFileToken(entry: ExternalFileEntry): ChatComposerToken {
  const payload: ExternalFileTokenPayload = {
    absolutePath: entry.absolutePath,
    fileName: entry.fileName,
    kind: entry.kind,
  }
  return {
    id: createSnowflakeId('composer_token'),
    kind: EXTERNAL_FILE_TOKEN_KIND,
    label: entry.fileName,
    summary: entry.absolutePath,
    opaqueText: buildOpaqueTokenText(EXTERNAL_FILE_TOKEN_KIND, {
      label: entry.fileName,
      summary: entry.absolutePath,
      payload: { absolutePath: entry.absolutePath, fileName: entry.fileName, kind: entry.kind },
    }),
  }
}

/** 读取外部文件引用 token 的结构化载荷(从 opaque 串解析,与渲染解耦);不合法返回 null。 */
export function readExternalFileToken(token: ChatComposerToken): ExternalFileTokenPayload | null {
  const parsed = parseOpaqueTokenText(token.opaqueText)
  if (!parsed || parsed.kind !== EXTERNAL_FILE_TOKEN_KIND) {
    return null
  }
  const p = parsed.payload
  const absolutePath = typeof p.absolutePath === 'string' ? p.absolutePath.trim() : ''
  const fileName = typeof p.fileName === 'string' ? p.fileName.trim() : ''
  if (!absolutePath || !fileName) {
    return null
  }
  if (p.kind !== 'file' && p.kind !== 'directory') {
    return null
  }
  return { absolutePath, fileName, kind: p.kind }
}

/** 判断某个 token 是否为外部文件引用 token。 */
export function isExternalFileToken(token: ChatComposerToken): boolean {
  return readExternalFileToken(token) !== null
}

// 注意:本模块**不**调用 composerTokenRegistry.registerDefinition。
// 未登记的 kind 提交时保留 opaque 原串上行(前端不解析,由 worker 侧解析),
// 这是外部文件引用的既定行为,见文件头注释。勿在此补登记。
