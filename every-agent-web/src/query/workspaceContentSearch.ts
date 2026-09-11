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
 *
 * 扩展能力（仿 VSCode 搜索面板，算法层一期）：
 * - 命中片段三段式定位：WorkspaceContentSearchHit 新增 matchIndex（行内起始列，0-based）与
 *   matchText（命中片段文本），同一行多个命中时保留第一个；UI 据此把行渲染为
 *   line.slice(0, matchIndex) + 高亮(matchText) + line.slice(matchIndex + matchText.length)。
 * - glob 包含/排除过滤（对标 VSCode files to include/exclude）：includePatterns / excludePatterns
 *   为逗号或换行分隔的 glob 串（允许空格；`{a,b}` 大括号内的逗号不作为分隔符）；include 为空 = 匹配全部，
 *   exclude 优先于 include，glob 内允许 `!` 前缀（在 include 列表里表示排除）。glob → RegExp：
 *   `**` 后随 `/` → 零或多段目录、`**` → `.*`、`*` → `[^/]*`、`?` → `[^/]`、`{a,b}` 大括号展开成多分支、
 *   其余正则元字符转义；匹配对象为相对 rootPath 的 posix 相对路径与文件名（任一命中即算命中，
 *   保证 `*.ts` 这类仅文件名 glob 能命中子目录内文件）。
 *   过滤发生在读取文件内容之前（不匹配的文件不调 readFileText）。
 * - 轻量中断：shouldStop 每处理一个文件前与逐行循环轮询，返回 true 立即停止并置 truncated
 *   （matchCount 保留已得值），供 UI 实现“新查询取消旧查询”。
 */

/**
 * 统一换行分割（read_file / rg 命令 / 内容搜索共用，保证行号对齐）。
 * 兼容 `\r\n` 与 `\n`；空文本返回 `['']`（长度为 1，调用方按空文本特判 totalLines=0）。
 */
export function splitLines(text: string): string[] {
  return text.split(/\r?\n/)
}

/**
 * glob 过滤器（对标 VSCode files to include/exclude）。
 * include 为空数组表示不过滤（匹配全部）；exclude 优先于 include。
 */
export interface WorkspaceContentSearchGlobFilter {
  /** 包含正则（相对路径 / basename 任一完整命中即算命中）。 */
  include: RegExp[]
  /** 排除正则（相对路径 / basename 任一完整命中即算命中）。 */
  exclude: RegExp[]
}

/** 把逗号 / 换行分隔的 glob 串拆成去空白的非空模式列表（`{a,b}` 大括号内的逗号不作为分隔符）。 */
function parseGlobList(patterns: string | undefined): string[] {
  if (!patterns) {
    return []
  }
  const parts: string[] = []
  let current = ''
  let depth = 0
  for (const ch of patterns) {
    if (ch === '{') {
      depth += 1
      current += ch
    } else if (ch === '}') {
      depth = Math.max(0, depth - 1)
      current += ch
    } else if (depth === 0 && (ch === ',' || ch === '\n' || ch === '\r')) {
      parts.push(current)
      current = ''
    } else {
      current += ch
    }
  }
  parts.push(current)
  return parts
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
}

/**
 * 大括号展开：`{a,b}` 展开成多分支（递归支持嵌套与多组大括号）。
 * 找不到配对 `}` 时原样返回（后续按字面量转义）。
 */
function expandBraces(pattern: string): string[] {
  const open = pattern.indexOf('{')
  if (open === -1) {
    return [pattern]
  }
  let depth = 0
  let close = -1
  for (let i = open; i < pattern.length; i += 1) {
    if (pattern[i] === '{') {
      depth += 1
    } else if (pattern[i] === '}') {
      depth -= 1
      if (depth === 0) {
        close = i
        break
      }
    }
  }
  if (close === -1) {
    return [pattern]
  }
  const prefix = pattern.slice(0, open)
  const body = pattern.slice(open + 1, close)
  const suffix = pattern.slice(close + 1)
  // 顶层逗号切分（大括号内的逗号不切）
  const parts: string[] = []
  let inner = 0
  let current = ''
  for (const ch of body) {
    if (ch === '{') {
      inner += 1
    } else if (ch === '}') {
      inner -= 1
    }
    if (ch === ',' && inner === 0) {
      parts.push(current)
      current = ''
    } else {
      current += ch
    }
  }
  parts.push(current)
  const expanded: string[] = []
  for (const part of parts) {
    for (const rest of expandBraces(prefix + part + suffix)) {
      expanded.push(rest)
    }
  }
  return expanded
}

const REGEX_META = /[.*+?^${}()|[\]\\]/

/** 单个 glob 分支 → 正则源码：`**` 后随 `/` → 零或多段目录、`**` → `.*`、`*` → `[^/]*`、`?` → `[^/]`、其余元字符转义。 */
function globBranchToRegExpSource(branch: string): string {
  let source = ''
  let i = 0
  while (i < branch.length) {
    const ch = branch[i]
    if (ch === '*' && branch[i + 1] === '*') {
      i += 2
      while (branch[i] === '*') {
        i += 1
      }
      if (branch[i] === '/') {
        // `**/` 对标 VSCode：匹配零或多段目录（`src/**/*.ts` 同时命中 `src/a.ts` 与 `src/a/b.ts`）
        source += '(?:.*/)?'
        i += 1
      } else {
        source += '.*'
      }
    } else if (ch === '*') {
      source += '[^/]*'
      i += 1
    } else if (ch === '?') {
      source += '[^/]'
      i += 1
    } else {
      source += REGEX_META.test(ch) ? `\\${ch}` : ch
      i += 1
    }
  }
  return source
}

/** 单个 glob → 完整匹配正则（大小写敏感，无标志位，test 不受 lastIndex 影响）。 */
function globToRegExp(glob: string): RegExp | null {
  const sources = expandBraces(glob)
    .map((branch) => globBranchToRegExpSource(branch))
    .filter((source) => source.length > 0)
  if (sources.length === 0) {
    return null
  }
  return new RegExp(`^(?:${sources.join('|')})$`)
}

/**
 * 编译 include/exclude glob 串（逗号或换行分隔，允许空格）。
 * - `!` 前缀表示排除（include 列表里的 `!foo` 归入 exclude）；
 * - 两边都为空（或全部无效）时返回 null，表示无过滤。
 */
export function compileGlobs(
  includePatterns?: string,
  excludePatterns?: string,
): WorkspaceContentSearchGlobFilter | null {
  const include: RegExp[] = []
  const exclude: RegExp[] = []
  for (const pattern of parseGlobList(includePatterns)) {
    const negated = pattern.startsWith('!')
    const glob = negated ? pattern.slice(1) : pattern
    const regex = globToRegExp(glob)
    if (regex) {
      ;(negated ? exclude : include).push(regex)
    }
  }
  for (const pattern of parseGlobList(excludePatterns)) {
    const glob = pattern.startsWith('!') ? pattern.slice(1) : pattern
    const regex = globToRegExp(glob)
    if (regex) {
      exclude.push(regex)
    }
  }
  return include.length === 0 && exclude.length === 0 ? null : { include, exclude }
}

/** 把 filePath 归一为相对 rootPath 的 posix 风格相对路径（rootPath 即文件本身时取 basename）。 */
function toRelativePosixPath(rootPath: string, filePath: string): string {
  const normalize = (p: string): string => p.replace(/\\/g, '/').replace(/\/+$/, '')
  const root = normalize(rootPath)
  let path = normalize(filePath)
  if (root !== '') {
    if (path === root) {
      return path.split('/').pop() ?? path
    }
    if (path.startsWith(root + '/')) {
      path = path.slice(root.length + 1)
    }
  }
  return path
}

/**
 * 单文件 glob 判定：include 非空须命中任一 include；命中任一 exclude 即排除。
 * filter 为 null 时恒通过。
 */
function matchesGlobFilter(
  filter: WorkspaceContentSearchGlobFilter | null,
  rootPath: string,
  filePath: string,
): boolean {
  if (!filter) {
    return true
  }
  const relPath = toRelativePosixPath(rootPath, filePath)
  const base = relPath.split('/').pop() ?? ''
  const hit = (regex: RegExp): boolean => regex.test(relPath) || regex.test(base)
  if (filter.include.length > 0 && !filter.include.some(hit)) {
    return false
  }
  return !filter.exclude.some(hit)
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
  /** 命中片段在行内的起始列（0-based）；同一行多个命中时保留第一个（UI 逐行渲染）。 */
  matchIndex?: number
  /** 命中片段文本（与 matchIndex 配对）；供 UI 三段式渲染：前段 + 高亮段 + 后段。 */
  matchText?: string
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
  /** 命中总数（触顶时为 maxResults；shouldStop 中断时保留已得值）。 */
  matchCount: number
  /** 是否提前停止：命中数到达上限，或 shouldStop 中断。 */
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
  /**
   * 包含 glob 串（逗号或换行分隔，允许空格；对标 VSCode files to include）。
   * 空 = 匹配全部；非空 = 只保留匹配任一 glob 的文件；`!` 前缀表示排除该 glob。
   */
  includePatterns?: string
  /**
   * 排除 glob 串（逗号或换行分隔，允许空格；对标 VSCode files to exclude）。
   * 匹配任一 glob 的文件被排除，优先于 include。
   */
  excludePatterns?: string
  /**
   * 轻量中断：每处理一个文件前与逐行循环轮询；返回 true 立即停止并置 truncated
   * （matchCount 保留已得值）。供 UI 实现“新查询取消旧查询”。
   */
  shouldStop?: () => boolean
}

/**
 * 工作区内容搜索。行为与平台内容搜索命令（grep / Select-String）的核心算法一致。
 *
 * 扩展（向后兼容，全部参数可选）：
 * - includePatterns / excludePatterns：glob 过滤发生在读取文件内容之前（不匹配的文件不调 readFileText）；
 * - shouldStop：每处理一个文件前与逐行循环轮询，返回 true 立即停止并置 truncated（matchCount 保留已得值）；
 * - 命中条目带 matchIndex / matchText（regex.exec 取第一个命中），供 UI 三段式高亮。
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
    includePatterns,
    excludePatterns,
    shouldStop,
  } = params

  const globFilter = compileGlobs(includePatterns, excludePatterns)
  const filePaths = await walkFiles(rootPath)
  const files: WorkspaceContentSearchFileResult[] = []
  let matchCount = 0
  let truncated = false

  if (matchMode === 'name') {
    for (const filePath of filePaths) {
      if (shouldStop?.()) {
        truncated = true
        break
      }
      if (!matchesGlobFilter(globFilter, rootPath, filePath)) {
        continue
      }
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
      if (shouldStop?.()) {
        truncated = true
        break
      }
      if (!matchesGlobFilter(globFilter, rootPath, filePath)) {
        continue
      }
      const text = await readFileText(filePath)
      const lines = splitLines(text)
      const totalLines = text === '' ? 0 : lines.length
      const matches: WorkspaceContentSearchHit[] = []
      for (let i = 0; i < totalLines; i += 1) {
        if (shouldStop?.()) {
          truncated = true
          break
        }
        regex.lastIndex = 0
        const found = regex.exec(lines[i])
        if (found) {
          const lineNumber = i + 1
          const entry: WorkspaceContentSearchHit = {
            lineNumber,
            line: lines[i],
            matchIndex: found.index,
            matchText: found[0],
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
    matchCount: matchCount > maxResults ? maxResults : matchCount,
    truncated,
    files,
  }
}
