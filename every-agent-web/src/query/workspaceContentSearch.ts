/**
 * src/query/workspaceContentSearch.ts
 *
 * 工作区内容搜索的纯算法，供 UI（文件树「搜索」）与 AI 命令（rg）共用。
 *
 * 这里只负责「给定一批文件路径与读取能力，按正则匹配并产出命中行号 + 上下文」，
 * 不关心权限校验与具体 IO 实现——文件枚举（walkFiles）与文本读取（readFileText）
 * 由调用方注入：
 * - AI 工具注入经 fileAccessGateway 的回调（带权限校验 / skill 渐进式披露）；
 * - UI 注入经 workspaceGateway 的回调（人工操作，直读，走 worker 的 fs.* RPC）。
 *
 * 算法与平台内容搜索命令（Linux/macOS 经 bash 执行 grep、Windows 经 powershell 执行 Select-String）核心行为一致：
 * 固定大小写敏感（RegExp 不带 i 标志，由调用方编译）、
 * 逐行匹配、1-based 行号、before/after 上下文（≈ rg -B / -A）、内部结果上限触顶停止。
 */

/**
 * 统一换行分割（read_file / rg 命令 / 内容搜索共用，保证行号对齐）。
 * 兼容 `\r\n` 与 `\n`；空文本返回 `['']`（长度为 1，调用方按空文本特判 totalLines=0）。
 */
export function splitLines(text: string): string[] {
  return text.split(/\r?\n/)
}

/** 命中行的上下文行（行号 + 文本，结构化，便于 UI 渲染）。 */
export interface WorkspaceContentSearchContextLine {
  /** 1-based 行号。 */
  lineNumber: number
  /** 该行文本。 */
  line: string
}

/** 单条命中。 */
export interface WorkspaceContentSearchHit {
  /** 1-based 行号。 */
  lineNumber: number
  /** 命中行文本。 */
  line: string
  /** 命中行之前的上下文（beforeContext > 0 时填充）。 */
  before?: WorkspaceContentSearchContextLine[]
  /** 命中行之后的上下文（afterContext > 0 时填充）。 */
  after?: WorkspaceContentSearchContextLine[]
}

/** 单个文件的搜索结果。name 模式仅命中文件名时不带 matches。 */
export interface WorkspaceContentSearchFileResult {
  /** 文件路径。 */
  path: string
  /** content 模式下的命中列表；name 模式无此字段。 */
  matches?: WorkspaceContentSearchHit[]
}

/** 搜索结果。 */
export interface WorkspaceContentSearchResult {
  /** 命中总数（触顶时为 maxResults）。 */
  matchCount: number
  /** 是否因命中数到达上限而提前停止。 */
  truncated: boolean
  /** 按文件聚合的命中结果。 */
  files: WorkspaceContentSearchFileResult[]
}

/** 搜索参数。 */
export interface WorkspaceContentSearchParams {
  /** 搜索根路径（文件或目录）。 */
  rootPath: string
  /** 已编译的正则（大小写敏感由调用方决定，建议不带 i 标志）。 */
  regex: RegExp
  /** 匹配维度：content 逐行搜内容 / name 按文件名匹配。 */
  matchMode: 'content' | 'name'
  /** 命中行之前附带多少行（仅 content 模式生效），默认 0。 */
  beforeContext?: number
  /** 命中行之后附带多少行（仅 content 模式生效），默认 0。 */
  afterContext?: number
  /** 递归枚举目录下全部文件路径（不含目录）。由调用方注入。 */
  walkFiles: (rootPath: string) => Promise<string[]>
  /** 读取单个文件文本内容。由调用方注入。 */
  readFileText: (filePath: string) => Promise<string>
  /** 内部结果上限，触顶停止并标 truncated，默认 1000。 */
  maxResults?: number
}

/**
 * 工作区内容搜索。行为与平台内容搜索命令（grep / Select-String）的核心算法一致。
 */
export async function searchWorkspaceContent(
  params: WorkspaceContentSearchParams,
): Promise<WorkspaceContentSearchResult> {
  const {
    rootPath,
    regex,
    matchMode,
    beforeContext = 0,
    afterContext = 0,
    walkFiles,
    readFileText,
    maxResults = 1000,
  } = params

  const filePaths = await walkFiles(rootPath)
  const files: WorkspaceContentSearchFileResult[] = []
  let matchCount = 0
  let truncated = false

  if (matchMode === 'name') {
    for (const filePath of filePaths) {
      const base = filePath.split('/').pop() ?? ''
      regex.lastIndex = 0
      if (regex.test(base)) {
        files.push({ path: filePath })
        matchCount += 1
        if (matchCount >= maxResults) {
          truncated = true
          break
        }
      }
    }
  } else {
    for (const filePath of filePaths) {
      if (truncated) {
        break
      }
      const text = await readFileText(filePath)
      const lines = splitLines(text)
      const totalLines = text === '' ? 0 : lines.length
      const matches: WorkspaceContentSearchHit[] = []
      for (let i = 0; i < totalLines; i += 1) {
        regex.lastIndex = 0
        if (regex.test(lines[i])) {
          const lineNumber = i + 1
          const entry: WorkspaceContentSearchHit = {
            lineNumber,
            line: lines[i],
          }
          if (beforeContext > 0) {
            const start = Math.max(1, lineNumber - beforeContext)
            const before: WorkspaceContentSearchContextLine[] = []
            for (let j = start; j < lineNumber; j += 1) {
              before.push({ lineNumber: j, line: lines[j - 1] })
            }
            entry.before = before
          }
          if (afterContext > 0) {
            const end = Math.min(totalLines, lineNumber + afterContext)
            const after: WorkspaceContentSearchContextLine[] = []
            for (let j = lineNumber + 1; j <= end; j += 1) {
              after.push({ lineNumber: j, line: lines[j - 1] })
            }
            entry.after = after
          }
          matches.push(entry)
          matchCount += 1
          if (matchCount >= maxResults) {
            truncated = true
            break
          }
        }
      }
      if (matches.length > 0) {
        files.push({ path: filePath, matches })
      }
    }
  }

  return {
    matchCount: truncated ? maxResults : matchCount,
    truncated,
    files,
  }
}
