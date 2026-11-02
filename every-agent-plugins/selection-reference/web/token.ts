/**
 * 选区引用 token 的前端契约常量与工具（kind / payload 类型 / 胶囊 label / 回跳 payload 读取）。
 *
 * 本文件不碰 opaque 线格式（编码由宿主 `ctx.ui.appendComposerToken` 单点负责），
 * 只承载「插件自己认识」的结构：kind 字符串、payload 形状、label 生成与安全读取。
 */

/** 文件选区引用胶囊的 kind；与后端 `SelectionReferenceToken.KIND` 逐字一致。 */
export const SELECTION_KIND = 'file.selection'

/**
 * 宿主 `@` 文件引用胶囊的 kind（`system.workspace_file`）。
 *
 * 资源管理器右键「引用到AI对话框」复用该 kind 生成与 `@` 引用完全等价的胶囊：
 * 提交解析（还原为「 工作区相对路径 」明文）由宿主前端 composerTokenRegistry 内置，
 * 胶囊点击（打开文件标签）由宿主 coreComposerChipActions 内置——本插件均无需注册。
 */
export const WORKSPACE_FILE_KIND = 'system.workspace_file'

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

// ─── 文件资源管理器节点引用（复用宿主 @ 引用 kind） ────────────────────────

/** 宿主 `@` 文件引用 token 的 payload 形状（见宿主 workspaceFileToken.ts）。 */
export interface WorkspaceFileTokenPayload {
  /** 工作区内相对路径（无前导 `/`，交给 AI 的引用形式）。 */
  path: string
  /** 文件名（相对路径最后一段）。 */
  fileName: string
  /** 完整业务路径（带前导 `/`，仅用于展示与点击打开）。 */
  fullPath: string
}

/**
 * 由资源管理器节点上下文构造 `@` 文件引用胶囊的 payload 与展示字段。
 *
 * `FileExplorerActionContext.path` 是带前导 `/` 的业务绝对路径（如 `/src/Foo.java`），
 * 与 `@` 引用 payload 的口径转换：
 * - `fullPath` = 节点路径原样（已是业务绝对形式）；
 * - `path` = 去前导 `/` 的工作区相对路径（交给 AI）；
 * - `label` = 节点名（胶囊展示，与 `@` 引用一致）。
 *
 * 归一化不依赖宿主 pathUtils：反斜杠归一、压重复分隔符、去首尾分隔符。
 */
export function buildWorkspaceFileTokenInput(nodePath: string, nodeName: string): {
  label: string
  summary: string
  payload: WorkspaceFileTokenPayload
} {
  const fullPath = toBusinessPath(nodePath)
  const rel = toWorkspaceRelativePath(nodePath)
  return {
    label: nodeName || rel.split('/').pop() || '文件',
    summary: fullPath,
    payload: {
      path: rel,
      fileName: nodeName || rel.split('/').pop() || '',
      fullPath,
    },
  }
}

/** 归一化为业务绝对路径：反斜杠归一、压重复分隔符、补单个前导 `/`。 */
function toBusinessPath(value: string): string {
  const rel = toWorkspaceRelativePath(value)
  return rel ? `/${rel}` : '/'
}

/**
 * 归一化为工作区相对路径：反斜杠归一、去重复分隔符、去首尾分隔符。
 * （对齐宿主 normalizeWorkspaceRelativePath 的输出形态，插件内自实现以免依赖宿主内部路径。）
 */
function toWorkspaceRelativePath(value: string): string {
  return (value ?? '')
    .replace(/\\/g, '/')
    .replace(/\/+/g, '/')
    .split('/')
    .filter(Boolean)
    .join('/')
}