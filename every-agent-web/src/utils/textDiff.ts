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
