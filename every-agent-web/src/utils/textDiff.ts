export interface DiffLine {
  type: 'context' | 'added' | 'removed'
  leftLineNumber: number | null
  rightLineNumber: number | null
  content: string
}

export interface DiffHunk {
  start: number
  end: number
  lines: DiffLine[]
}

export interface SideBySideDiffRow {
  leftLineNumber: number | null
  rightLineNumber: number | null
  leftContent: string
  rightContent: string
  type: 'context' | 'added' | 'removed' | 'changed'
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
      result.push({
        type: 'context',
        leftLineNumber: i,
        rightLineNumber: j,
        content: beforeLines[i - 1],
      })
      i -= 1
      j -= 1
    } else if (lcs[i - 1][j] >= lcs[i][j - 1]) {
      result.push({
        type: 'removed',
        leftLineNumber: i,
        rightLineNumber: null,
        content: beforeLines[i - 1],
      })
      i -= 1
    } else {
      result.push({
        type: 'added',
        leftLineNumber: null,
        rightLineNumber: j,
        content: afterLines[j - 1],
      })
      j -= 1
    }
  }

  while (i > 0) {
    result.push({
      type: 'removed',
      leftLineNumber: i,
      rightLineNumber: null,
      content: beforeLines[i - 1],
    })
    i -= 1
  }

  while (j > 0) {
    result.push({
      type: 'added',
      leftLineNumber: null,
      rightLineNumber: j,
      content: afterLines[j - 1],
    })
    j -= 1
  }

  return result.reverse()
}

export function buildDiffHunks(lines: DiffLine[], contextLines = 2): DiffHunk[] {
  const changeIndexes = lines
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => line.type !== 'context')
    .map(({ index }) => index)

  if (changeIndexes.length === 0) {
    return lines.length > 0
      ? [{ start: 0, end: lines.length - 1, lines }]
      : []
  }

  const ranges = changeIndexes.map((index) => ({
    start: Math.max(0, index - contextLines),
    end: Math.min(lines.length - 1, index + contextLines),
  }))

  const merged: Array<{ start: number; end: number }> = []
  for (const range of ranges) {
    const prev = merged[merged.length - 1]
    if (!prev || range.start > prev.end + 1) {
      merged.push({ ...range })
    } else {
      prev.end = Math.max(prev.end, range.end)
    }
  }

  return merged.map((range) => ({
    start: range.start,
    end: range.end,
    lines: lines.slice(range.start, range.end + 1),
  }))
}

export function buildSideBySideRows(lines: DiffLine[]): SideBySideDiffRow[] {
  const rows: SideBySideDiffRow[] = []
  let index = 0

  while (index < lines.length) {
    const current = lines[index]

    if (current.type === 'context') {
      rows.push({
        leftLineNumber: current.leftLineNumber,
        rightLineNumber: current.rightLineNumber,
        leftContent: current.content,
        rightContent: current.content,
        type: 'context',
      })
      index += 1
      continue
    }

    /**
     * 连续变更块里，LCS 回溯可能把“同一行替换”输出成：
     * 1. removed -> added
     * 2. added -> removed
     * 3. 多个 removed / added 交错
     * 这里统一先收集整块，再按左右数量配对，避免本应同一行的改动被拆成错位两行。
     */
    const blockStart = index
    while (index < lines.length && lines[index].type !== 'context') {
      index += 1
    }

    const blockLines = lines.slice(blockStart, index)
    const removedLines = blockLines.filter((line) => line.type === 'removed')
    const addedLines = blockLines.filter((line) => line.type === 'added')
    const pairedCount = Math.min(removedLines.length, addedLines.length)

    for (let pairIndex = 0; pairIndex < pairedCount; pairIndex += 1) {
      rows.push({
        leftLineNumber: removedLines[pairIndex].leftLineNumber,
        rightLineNumber: addedLines[pairIndex].rightLineNumber,
        leftContent: removedLines[pairIndex].content,
        rightContent: addedLines[pairIndex].content,
        type: 'changed',
      })
    }

    for (let removedIndex = pairedCount; removedIndex < removedLines.length; removedIndex += 1) {
      rows.push({
        leftLineNumber: removedLines[removedIndex].leftLineNumber,
        rightLineNumber: null,
        leftContent: removedLines[removedIndex].content,
        rightContent: '',
        type: 'removed',
      })
    }

    for (let addedIndex = pairedCount; addedIndex < addedLines.length; addedIndex += 1) {
      rows.push({
        leftLineNumber: null,
        rightLineNumber: addedLines[addedIndex].rightLineNumber,
        leftContent: '',
        rightContent: addedLines[addedIndex].content,
        type: 'added',
      })
    }
  }

  return rows
}

function normalizeLines(text: string): string[] {
  const normalized = text.replace(/\r\n/g, '\n')
  return normalized.length === 0 ? [] : normalized.split('\n')
}

// ---- 回滚(文件差异标签页,反向 update_file)----
// 回滚 = 在当前文件中定位「差异新内容」的唯一出现并原位替换回「差异旧内容」;
// 定位纯靠内容(worker fs.replaceRaw 行尾宽容唯一匹配),不依赖行号——差异生成后
// 该处被其他修改 → 定位不再命中 → 回滚失败,绝不盲改。

/**
 * 连续变更块(回滚单元):DiffLine 数组中一段不含 context 行的最大连续区间。
 * 一个块对应 diff 视图里一段连续的 +/- 行,即「一处变更」。
 */
export interface RollbackBlock {
  /** 块首行下标(含,DiffLine 数组下标)。 */
  start: number
  /** 块尾行下标(含)。 */
  end: number
}

/**
 * 切分连续变更块:与 buildSideBySideRows 的非 context 行分块严格同构
 * (同一 DiffLine 数组推导,块序号一一对应)。
 */
export function buildRollbackBlocks(lines: DiffLine[]): RollbackBlock[] {
  const blocks: RollbackBlock[] = []
  let start = -1
  for (let i = 0; i <= lines.length; i += 1) {
    const isChange = i < lines.length && lines[i].type !== 'context'
    if (isChange && start < 0) {
      start = i
    } else if (!isChange && start >= 0) {
      blocks.push({ start, end: i - 1 })
      start = -1
    }
  }
  return blocks
}

/** 回滚替换对:search 在当前文件中定位,replacement 原位替换之。 */
export interface RollbackReplacePair {
  /** 定位文本:变更块的新文本(added 行)+ 可选上下文行。 */
  search: string
  /** 替换文本:变更块的旧文本(removed 行)+ 相同上下文行。 */
  replacement: string
}

/**
 * 构造单个变更块的回滚替换对(反向 update_file 参数)。
 *
 * - search = 前后各 context 行上下文 + 块内 added 行(变更后的样子);
 *   replacement = 同样的上下文 + 块内 removed 行(变更前的样子)。
 * - 上下行各自按行号稳定排序:LCS 回溯在块内可能交错输出 removed/added,
 *   排序保证与文件真实行序一致。
 * - 纯删除块(无 added 行)靠上下文行锚定插入位置;上下文不足(两端都取不到)
 *   时返回 null——该块无法按内容定位,不可回滚。
 * - 「不唯一」时调用方应增大 context 重试(上下文行编码了变更的真实位置,扩位即消歧)。
 */
export function buildRollbackReplacePair(
  lines: DiffLine[],
  block: RollbackBlock,
  context = 0,
): RollbackReplacePair | null {
  const blockLines = lines.slice(block.start, block.end + 1)
  const oldLines = blockLines
    .filter((line) => line.type === 'removed')
    .sort((a, b) => (a.leftLineNumber ?? 0) - (b.leftLineNumber ?? 0))
    .map((line) => line.content)
  const newLines = blockLines
    .filter((line) => line.type === 'added')
    .sort((a, b) => (a.rightLineNumber ?? 0) - (b.rightLineNumber ?? 0))
    .map((line) => line.content)

  const beforeContext: string[] = []
  for (let i = block.start - 1; i >= 0 && beforeContext.length < context; i -= 1) {
    if (lines[i].type !== 'context') break
    beforeContext.unshift(lines[i].content)
  }
  const afterContext: string[] = []
  for (let i = block.end + 1; i < lines.length && afterContext.length < context; i += 1) {
    if (lines[i].type !== 'context') break
    afterContext.push(lines[i].content)
  }

  const searchLines = [...beforeContext, ...newLines, ...afterContext]
  if (searchLines.length === 0) {
    return null
  }
  return {
    search: searchLines.join('\n'),
    replacement: [...beforeContext, ...oldLines, ...afterContext].join('\n'),
  }
}

function buildLcsMatrix(a: string[], b: string[]): number[][] {
  const matrix = Array.from({ length: a.length + 1 }, () =>
    Array.from<number>({ length: b.length + 1 }).fill(0),
  )

  for (let i = 1; i <= a.length; i += 1) {
    for (let j = 1; j <= b.length; j += 1) {
      if (a[i - 1] === b[j - 1]) {
        matrix[i][j] = matrix[i - 1][j - 1] + 1
      } else {
        matrix[i][j] = Math.max(matrix[i - 1][j], matrix[i][j - 1])
      }
    }
  }

  return matrix
}
