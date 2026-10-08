/**
 * update-file-view 插件内部工具函数（自宿主复制，插件不引用宿主模块）：
 * - buildLineDiff：行级 LCS diff（公共前后缀裁剪 + 规模保护）。
 * - buildDiffRows：把原始 diff 行规整为「展示行」——修正 +/- 顺序，并对成对替换行做行内词级高亮。
 * - extractFileName / hasActiveTextSelection：复制自 components/task/toolViews/helpers。
 * - toBusinessAbsolutePath / normalizeWorkspaceRelativePath：复制自 platform/fs/pathUtils。
 */

// ─── 行级 diff（LCS） ─────────────────────────────────────────────────────

export interface DiffLine {
  type: 'context' | 'added' | 'removed'
  /** 变更前（旧）行号，1-based；added 行为 null。 */
  leftLineNumber: number | null
  /** 变更后（新）行号，1-based；removed 行为 null。 */
  rightLineNumber: number | null
  content: string
}

/**
 * LCS 矩阵规模上限（单元格数）。update_file 的 oldcontent/content 是文件片段，
 * 绝大多数只有几十行；一旦两侧行数乘积超限（如整文件级替换），LCS 的 O(n·m)
 * 时间与内存会明显拖住渲染，此时降级为「整块替换」（旧行全删 + 新行全增），
 * 结果仍然正确，只是不够精简。
 */
const MAX_LCS_CELLS = 400_000

/**
 * 生成行级 diff。
 *
 * 先裁掉公共前后缀：片段替换通常只有局部差异，裁剪后进入 LCS 的规模大幅下降，
 * 同时天然得到变更的起始行号；再对中段跑 LCS 回溯。
 */
export function buildLineDiff(beforeText: string, afterText: string): DiffLine[] {
  const beforeLines = normalizeLines(beforeText)
  const afterLines = normalizeLines(afterText)

  const maxHead = Math.min(beforeLines.length, afterLines.length)
  let head = 0
  while (head < maxHead && beforeLines[head] === afterLines[head]) head += 1

  const maxTail = Math.min(beforeLines.length, afterLines.length) - head
  let tail = 0
  while (
    tail < maxTail
    && beforeLines[beforeLines.length - 1 - tail] === afterLines[afterLines.length - 1 - tail]
  ) tail += 1

  const result: DiffLine[] = []
  for (let i = 0; i < head; i += 1) {
    result.push({ type: 'context', leftLineNumber: i + 1, rightLineNumber: i + 1, content: beforeLines[i] })
  }

  const oldMid = beforeLines.slice(head, beforeLines.length - tail)
  const newMid = afterLines.slice(head, afterLines.length - tail)
  appendMidDiff(result, oldMid, newMid, head)

  for (let i = 0; i < tail; i += 1) {
    const oldIdx = beforeLines.length - tail + i
    const newIdx = afterLines.length - tail + i
    result.push({
      type: 'context',
      leftLineNumber: oldIdx + 1,
      rightLineNumber: newIdx + 1,
      content: beforeLines[oldIdx],
    })
  }

  return result
}

/** 中段 diff：规模可控时走 LCS，超限则整块替换。`offset` 为已裁剪的前缀行数。 */
function appendMidDiff(out: DiffLine[], oldMid: string[], newMid: string[], offset: number): void {
  if (oldMid.length === 0 && newMid.length === 0) return

  if (oldMid.length * newMid.length > MAX_LCS_CELLS) {
    oldMid.forEach((content, i) => out.push({
      type: 'removed', leftLineNumber: offset + i + 1, rightLineNumber: null, content,
    }))
    newMid.forEach((content, i) => out.push({
      type: 'added', leftLineNumber: null, rightLineNumber: offset + i + 1, content,
    }))
    return
  }

  const lcs = buildLcsMatrix(oldMid, newMid)
  const reversed: DiffLine[] = []
  let i = oldMid.length
  let j = newMid.length

  while (i > 0 && j > 0) {
    if (oldMid[i - 1] === newMid[j - 1]) {
      reversed.push({ type: 'context', leftLineNumber: offset + i, rightLineNumber: offset + j, content: oldMid[i - 1] })
      i -= 1
      j -= 1
    } else if (lcs[i - 1][j] >= lcs[i][j - 1]) {
      reversed.push({ type: 'removed', leftLineNumber: offset + i, rightLineNumber: null, content: oldMid[i - 1] })
      i -= 1
    } else {
      reversed.push({ type: 'added', leftLineNumber: null, rightLineNumber: offset + j, content: newMid[j - 1] })
      j -= 1
    }
  }
  while (i > 0) {
    reversed.push({ type: 'removed', leftLineNumber: offset + i, rightLineNumber: null, content: oldMid[i - 1] })
    i -= 1
  }
  while (j > 0) {
    reversed.push({ type: 'added', leftLineNumber: null, rightLineNumber: offset + j, content: newMid[j - 1] })
    j -= 1
  }

  for (let k = reversed.length - 1; k >= 0; k -= 1) out.push(reversed[k])
}

function normalizeLines(text: string): string[] {
  if (text === '') return []
  return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
}

function buildLcsMatrix(a: string[], b: string[]): number[][] {
  const rows = a.length + 1
  const cols = b.length + 1
  const matrix: number[][] = Array.from({ length: rows }, () => new Array<number>(cols).fill(0))
  for (let i = 1; i < rows; i += 1) {
    for (let j = 1; j < cols; j += 1) {
      if (a[i - 1] === b[j - 1]) {
        matrix[i][j] = matrix[i - 1][j - 1] + 1
      } else {
        matrix[i][j] = Math.max(matrix[i - 1][j], matrix[i][j - 1])
      }
    }
  }
  return matrix
}

// ─── 展示行模型（修正顺序 + 行内高亮） ────────────────────────────────────

/** 行内片段：`changed` 标记该片段属于本行的实际改动，用于词级高亮。 */
export interface InlinePart {
  text: string
  changed: boolean
}

/**
 * 一条展示行。`changed` 表示「同一行被改写」的成对行（旧值 + 新值各占一行渲染），
 * 其余三种与 DiffLine 一一对应。
 */
export interface DiffRow {
  kind: 'context' | 'added' | 'removed' | 'changed'
  oldLine: number | null
  newLine: number | null
  oldText: string
  newText: string
  oldParts: InlinePart[]
  newParts: InlinePart[]
}

/**
 * 把原始 diff 行规整为展示行。
 *
 * LCS 回溯会把「同一行的替换」输出成 added→removed 的倒序（git 惯例是先删后增），
 * 甚至多行交错。这里按「连续变更块」整块收集，再把旧行与新行按序配对：
 * 配对成功的成 `changed` 行（附带词级高亮），落单的归为纯删除 / 纯新增。
 */
export function buildDiffRows(diffLines: DiffLine[]): DiffRow[] {
  const rows: DiffRow[] = []
  let index = 0

  while (index < diffLines.length) {
    const current = diffLines[index]
    if (current.type === 'context') {
      rows.push({
        kind: 'context',
        oldLine: current.leftLineNumber,
        newLine: current.rightLineNumber,
        oldText: current.content,
        newText: current.content,
        oldParts: [{ text: current.content, changed: false }],
        newParts: [{ text: current.content, changed: false }],
      })
      index += 1
      continue
    }

    let end = index
    while (end < diffLines.length && diffLines[end].type !== 'context') end += 1
    const block = diffLines.slice(index, end)
    index = end

    const removed = block.filter((line) => line.type === 'removed').sort(byLineNumber('left'))
    const added = block.filter((line) => line.type === 'added').sort(byLineNumber('right'))
    const paired = Math.min(removed.length, added.length)

    for (let k = 0; k < paired; k += 1) {
      const oldLine = removed[k]
      const newLine = added[k]
      const inline = buildInlineParts(oldLine.content, newLine.content)
      rows.push({
        kind: 'changed',
        oldLine: oldLine.leftLineNumber,
        newLine: newLine.rightLineNumber,
        oldText: oldLine.content,
        newText: newLine.content,
        oldParts: inline.oldParts,
        newParts: inline.newParts,
      })
    }
    for (let k = paired; k < removed.length; k += 1) {
      rows.push({
        kind: 'removed',
        oldLine: removed[k].leftLineNumber,
        newLine: null,
        oldText: removed[k].content,
        newText: '',
        oldParts: [{ text: removed[k].content, changed: true }],
        newParts: [],
      })
    }
    for (let k = paired; k < added.length; k += 1) {
      rows.push({
        kind: 'added',
        oldLine: null,
        newLine: added[k].rightLineNumber,
        oldText: '',
        newText: added[k].content,
        oldParts: [],
        newParts: [{ text: added[k].content, changed: true }],
      })
    }
  }

  return rows
}

/** 变更块内按行号升序排（LCS 回溯在交错场景下行号未必有序）。 */
function byLineNumber(side: 'left' | 'right'): (a: DiffLine, b: DiffLine) => number {
  return (a, b) => (side === 'left'
    ? (a.leftLineNumber ?? 0) - (b.leftLineNumber ?? 0)
    : (a.rightLineNumber ?? 0) - (b.rightLineNumber ?? 0))
}

/** 行内 token 数上限：超长的单行不做词级 diff（收益低且开销大），整行高亮即可。 */
const MAX_INLINE_TOKENS = 600

/** 对同一行的旧值 / 新值做词级 diff，产出两侧高亮片段。 */
function buildInlineParts(oldText: string, newText: string): { oldParts: InlinePart[]; newParts: InlinePart[] } {
  const oldTokens = tokenizeInlineText(oldText)
  const newTokens = tokenizeInlineText(newText)
  if (oldTokens.length > MAX_INLINE_TOKENS || newTokens.length > MAX_INLINE_TOKENS) {
    return {
      oldParts: [{ text: oldText, changed: true }],
      newParts: [{ text: newText, changed: true }],
    }
  }

  const tokenDiff = buildLineDiff(oldTokens.join('\n'), newTokens.join('\n'))
  const oldParts: InlinePart[] = []
  const newParts: InlinePart[] = []
  for (const line of tokenDiff) {
    if (line.type === 'context') {
      oldParts.push({ text: line.content, changed: false })
      newParts.push({ text: line.content, changed: false })
    } else if (line.type === 'removed') {
      oldParts.push({ text: line.content, changed: true })
    } else {
      newParts.push({ text: line.content, changed: true })
    }
  }
  return { oldParts: mergeInlineParts(oldParts), newParts: mergeInlineParts(newParts) }
}

/** 切成词级 diff 可用的 token（单词 / 空白 / 标点各成一块，结构字符不丢失）。 */
function tokenizeInlineText(text: string): string[] {
  const tokens = text.match(/(\s+|[A-Za-z0-9_]+|[^\sA-Za-z0-9_])/g)
  return tokens && tokens.length > 0 ? tokens : [text]
}

/** 合并相邻同状态片段，避免渲染过碎。 */
function mergeInlineParts(parts: InlinePart[]): InlinePart[] {
  const merged: InlinePart[] = []
  for (const part of parts) {
    const previous = merged[merged.length - 1]
    if (previous && previous.changed === part.changed) {
      previous.text += part.text
      continue
    }
    merged.push({ ...part })
  }
  return merged
}

/** 变更行数统计：新增 / 删除各算一次（`changed` 行两边各计一行）。 */
export function countChanges(diffLines: DiffLine[]): { added: number; removed: number } {
  let added = 0
  let removed = 0
  for (const line of diffLines) {
    if (line.type === 'added') added += 1
    else if (line.type === 'removed') removed += 1
  }
  return { added, removed }
}

// ─── 文件名提取 / 选区守卫（复制自 @/components/task/toolViews/helpers） ─────

/** 提取路径最后一段文件名（分隔符 / 或 \）。 */
export function extractFileName(path: string): string {
  const segments = path.split(/[\\/]+/).filter(Boolean)
  return segments.length > 0 ? segments[segments.length - 1] : path
}

/** 是否存在非空文本选区（拖选文本时不触发折叠切换，保证可选中复制）。 */
export function hasActiveTextSelection(): boolean {
  const selection = typeof window !== 'undefined' ? window.getSelection() : null
  return !!selection && !selection.isCollapsed && selection.toString().length > 0
}

// ─── 路径工具（复制自 @/platform/fs/pathUtils） ───────────────────────────

const WORKSPACE_ROOT = '/'

/** 规范化工作区相对路径：反斜杠归一、去重复分隔符、去首尾分隔符。 */
export function normalizeWorkspaceRelativePath(value: string): string {
  return value
    .replace(/\\/g, '/')
    .replace(/^\/+/, '')
    .replace(/\/+/g, '/')
    .split('/')
    .filter(Boolean)
    .join('/')
}

/** 把任意形态的业务路径规范为「带前导 / 的绝对业务路径」（展示形态）。 */
export function toBusinessAbsolutePath(value: string): string {
  return WORKSPACE_ROOT + normalizeWorkspaceRelativePath(value)
}

/** 取父目录（工作区相对路径；根级返回空串）。复制自宿主 workspaceGateway，插件不引用宿主模块。 */
export function dirname(path: string): string {
  const normalized = normalizeWorkspaceRelativePath(path)
  const index = normalized.lastIndexOf('/')
  return index < 0 ? '' : normalized.slice(0, index)
}

/** 取末段文件名（按规范化后路径）。复制自宿主 workspaceGateway，插件不引用宿主模块。 */
export function basename(path: string): string {
  const normalized = normalizeWorkspaceRelativePath(path)
  const index = normalized.lastIndexOf('/')
  return index < 0 ? normalized : normalized.slice(index + 1)
}
