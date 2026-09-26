/**
 * update-file-view 插件内部工具函数（自宿主复制，插件不引用宿主模块）：
 * - buildLineDiff：行级 LCS diff（复制自 utils/textDiff）。
 * - extractFileName / hasActiveTextSelection：复制自 components/task/toolViews/helpers。
 * - toBusinessAbsolutePath / normalizeWorkspaceRelativePath：复制自 platform/fs/pathUtils。
 */

// ─── buildLineDiff（复制自 @/utils/textDiff） ─────────────────────────────

export interface DiffLine {
  type: 'context' | 'added' | 'removed'
  leftLineNumber: number | null
  rightLineNumber: number | null
  content: string
}

export function buildLineDiff(beforeText: string, afterText: string): DiffLine[] {
  const beforeLines = normalizeLines(beforeText)
  const afterLines = normalizeLines(afterText)
  const lcs = buildLcsMatrix(beforeLines, afterLines)

  const result: DiffLine[] = []
  let i = beforeLines.length
  let j = afterLines.length

  while (i > 0 && j > 0) {
    if (beforeLines[i - 1] === afterLines[j - 1]) {
      result.push({ type: 'context', leftLineNumber: i, rightLineNumber: j, content: beforeLines[i - 1] })
      i -= 1
      j -= 1
    } else if (lcs[i - 1][j] >= lcs[i][j - 1]) {
      result.push({ type: 'removed', leftLineNumber: i, rightLineNumber: null, content: beforeLines[i - 1] })
      i -= 1
    } else {
      result.push({ type: 'added', leftLineNumber: null, rightLineNumber: j, content: afterLines[j - 1] })
      j -= 1
    }
  }

  while (i > 0) {
    result.push({ type: 'removed', leftLineNumber: i, rightLineNumber: null, content: beforeLines[i - 1] })
    i -= 1
  }

  while (j > 0) {
    result.push({ type: 'added', leftLineNumber: null, rightLineNumber: j, content: afterLines[j - 1] })
    j -= 1
  }

  return result.reverse()
}

function normalizeLines(text: string): string[] {
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
