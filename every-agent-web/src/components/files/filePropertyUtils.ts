import { normalizeWorkspaceRelativePath } from '@/platform/fs/pathUtils'

/**
 * 文件属性展示的共享纯函数：字节/时间格式化、md 判定、路径拼接、字符统计。
 * 文件树属性弹窗与文件标签页属性弹窗共用，保证两处展示逻辑一致。
 */

/** 字节大小格式化为可读文本(B/KB/MB)。 */
export function formatPropertyBytes(value: number): string {
  if (!value || value < 0) return '0B'
  if (value < 1024) return `${value}B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)}KB`
  return `${(value / 1024 / 1024).toFixed(1)}MB`
}

/** 时间戳(毫秒)格式化为「年-月-日 时:分」;0/无效 = 未知。 */
export function formatPropertyTime(value: number): string {
  if (!value || Number.isNaN(value)) return '未知'
  const date = new Date(value)
  const pad = (num: number) => String(num).padStart(2, '0')
  const year = date.getFullYear()
  const month = pad(date.getMonth() + 1)
  const day = pad(date.getDate())
  const hours = pad(date.getHours())
  const minutes = pad(date.getMinutes())
  return `${year}-${month}-${day} ${hours}:${minutes}`
}

/** 是否文本文件(大小写不敏感;按扩展名白名单 + 常见无扩展名文本文件名判定)。 */
export function isTextFileName(name: string): boolean {
  const normalized = name.trim().toLowerCase()
  const dot = normalized.lastIndexOf('.')
  // 有扩展名(dot 不在首位,排除 .gitignore 这类点开头文件)
  if (dot > 0) {
    const ext = normalized.slice(dot)
    if (TEXT_EXTENSIONS.has(ext)) return true
  }
  return TEXT_FILENAMES.has(normalized)
}

/**
 * 文本文件扩展名白名单(小写、含点)。
 * 覆盖常见文档/标记、数据/配置、源码/脚本——这些文件以文本方式读取/编辑并展示字符统计。
 */
const TEXT_EXTENSIONS = new Set<string>([
  // 文档 / 标记
  '.md', '.markdown', '.mdown', '.mkd', '.txt', '.text', '.rst', '.adoc', '.asciidoc',
  '.tex', '.rtf', '.csv', '.tsv', '.log', '.org', '.wiki',
  // 数据 / 配置 / 标记
  '.json', '.json5', '.jsonc', '.geojson', '.xml', '.html', '.htm', '.xhtml',
  '.css', '.scss', '.sass', '.less', '.yaml', '.yml', '.toml', '.ini', '.cfg',
  '.conf', '.config', '.properties', '.env', '.editorconfig', '.npmrc', '.yarnrc',
  '.babelrc', '.eslintrc', '.prettierrc', '.stylelintrc', '.svg', '.xaml', '.dtd',
  '.xsd', '.wsdl', '.rss', '.atom', '.manifest', '.lock',
  // 源码 / 脚本
  '.js', '.mjs', '.cjs', '.jsx', '.ts', '.tsx', '.mts', '.cts', '.py', '.pyw',
  '.pyi', '.rb', '.php', '.phtml', '.go', '.rs', '.java', '.kt', '.kts', '.scala',
  '.groovy', '.gradle', '.c', '.h', '.cc', '.cpp', '.cxx', '.hpp', '.hh', '.cs',
  '.fs', '.fsx', '.vb', '.swift', '.m', '.mm', '.dart', '.lua', '.pl', '.pm',
  '.r', '.jl', '.ex', '.exs', '.erl', '.hrl', '.hs', '.clj', '.cljs', '.cljc',
  '.edn', '.scm', '.ss', '.lisp', '.el', '.sh', '.bash', '.zsh', '.fish', '.ps1',
  '.psm1', '.psd1', '.bat', '.cmd', '.sql', '.graphql', '.gql', '.proto', '.vue',
  '.svelte', '.astro', '.sol', '.zig', '.nim', '.v', '.coffee', '.styl',
])

/**
 * 常见无扩展名文本文件名白名单(小写,含点开头文件)。
 * Dockerfile / Makefile / LICENSE / README / .gitignore 等无「扩展名」概念,单独枚举。
 */
const TEXT_FILENAMES = new Set<string>([
  'dockerfile', 'makefile', 'gemfile', 'rakefile', 'procfile', 'vagrantfile',
  'jenkinsfile', 'berksfile', 'license', 'licence', 'copying', 'notice', 'authors',
  'changelog', 'changes', 'readme',
  '.gitignore', '.gitattributes', '.gitmodules', '.npmignore', '.dockerignore',
  '.editorconfig', '.env', '.eslintignore', '.prettierignore',
])

/** 拼接工作区磁盘完整路径:根 + 工作区相对路径(去掉业务路径前导 '/')。 */
export function joinWorkspaceDiskPath(workspaceRoot: string, path: string): string {
  const root = workspaceRoot.replace(/[\\/]+$/, '')
  const rel = normalizeWorkspaceRelativePath(path)
  return rel ? `${root}/${rel}` : root
}

/** 内容总字符数(含符号/空白/换行等一切字符)。 */
export function countTotalChars(content: string): number {
  return content.length
}

/**
 * 纯文字字符数:去掉空白符、标点/符号类 Unicode 字符后剩余的字符数。
 * 以 Unicode 属性 \p{P}(标点) 与 \p{S}(符号) 表达「符号」,辅以 \s 空白;
 * 保留文字本身(中英文、数字等)。emoji 属 \p{S}(符号),不计入纯文字。
 */
export function countPlainTextChars(content: string): number {
  return content.replace(/[\s\p{P}\p{S}]/gu, '').length
}
