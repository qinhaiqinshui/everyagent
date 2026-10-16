/**
 * 选区引用 token 的前端契约常量与工具（kind / payload 类型 / 胶囊 label / 回跳 payload 读取）。
 *
 * 本文件不碰 opaque 线格式（编码由宿主 `ctx.ui.appendComposerToken` 单点负责），
 * 只承载「插件自己认识」的结构：kind 字符串、payload 形状、label 生成与安全读取。
 */

/** 文件选区引用胶囊的 kind；与后端 `SelectionReferenceToken.KIND` 逐字一致。 */
export const SELECTION_KIND = 'file.selection'

/** 右键「引用到AI对话框」时构造、随 opaque token 一起提交给 worker 的 payload 形状。 */
export interface SelectionPayload {
  /** 所属工作区根（回切标签用，原样透传）。 */
  workspaceRoot: string
  /** 标签身份里的原样路径（保证回切命中同一标签）。 */
  filePath: string
  /** 归一化工作区相对路径（交给 AI 的引用口径）。 */
  relativePath: string
  /** 文件名。 */
  fileName: string
  /** 选区起始行（1-based；无法确定为 null）。 */
  startLine: number | null
  /** 选区结束行（1-based；无法确定为 null）。 */
  endLine: number | null
  /** 选中原文（不截断）。 */
  text: string
}

/** label 最多取前 10 个「字」（按码点切，中文 / emoji 安全）。 */
const LABEL_MAX_CHARS = 10

/**
 * 由选中原文生成胶囊显示文字：去换行（`\r\n` / `\r` / `\n` → 空格）、trim、取前 10 个字，
 * 超出加 `…`。结果为空时兜底返回非空文案——`ctx.ui.appendComposerToken` 对空 label 会抛错。
 */
export function buildSelectionLabel(text: string): string {
  const flat = (text ?? '').replace(/\r\n|\r|\n/g, ' ').trim()
  if (flat.length === 0) {
    return '选中内容'
  }
  const chars = Array.from(flat)
  if (chars.length <= LABEL_MAX_CHARS) {
    return flat
  }
  return chars.slice(0, LABEL_MAX_CHARS).join('') + '…'
}

/**
 * 从胶囊 payload（宿主解码后的不透明袋）中安全读取回跳所需字段。
 *
 * 缺 `workspaceRoot` / `filePath`（或非字符串 / 空串）→ 返回 null（调用方静默 no-op，不抛错）；
 * `startLine` 非有限数字 → null（仅打开文件、不定位行）。
 *
 * 注意：回跳用的是 payload 自带的 `workspaceRoot` + `filePath`，**不是** 激活上下文里的
 * `workspaceRoot`——后者是消费侧解析出的工作区根，可能与原文件所属工作区根不同。
 */
export function readSelectionPayload(
  payload: Record<string, unknown>,
): { workspaceRoot: string; filePath: string; startLine: number | null } | null {
  if (!payload) {
    return null
  }
  const workspaceRoot = payload.workspaceRoot
  const filePath = payload.filePath
  if (typeof workspaceRoot !== 'string' || workspaceRoot.length === 0) {
    return null
  }
  if (typeof filePath !== 'string' || filePath.length === 0) {
    return null
  }
  const rawStart = payload.startLine
  const startLine = typeof rawStart === 'number' && Number.isFinite(rawStart) ? rawStart : null
  return { workspaceRoot, filePath, startLine }
}