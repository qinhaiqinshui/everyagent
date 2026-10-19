/**
 * 工作区路径纯函数工具(自 n 分支 zenfsPathUtils 迁移,去除 ZenFS 语义)。
 *
 * 路径坐标系:全系统统一「工作区相对路径」——空串 = 工作区根,
 * 目录间以 `/` 分隔,无前导 `/`。网关(fs.*)以此坐标系与 worker 交换;
 * UI 展示层用 toBusinessAbsolutePath 补前导 `/`。
 *
 * 工作区外绝对路径(用户/AI 引用的非工作区文件)在业务坐标中用双 `//` 前缀标记:
 * - Windows 盘符路径 `C:/Users/...` → 业务形式 `/C:/Users/...`(单 `/` + 盘符)
 * - Unix 绝对路径 `/home/user/...` → 业务形式 `//home/user/...`(双 `//`)
 * - 工作区相对路径 `src/main.ts` → 业务形式 `/src/main.ts`(单 `/`)
 * 双 `//` 是 Unix 外部路径的专用标记,使其与工作区相对路径(`home/user/...` → `/home/user/...`)
 * 在业务坐标中可区分——否则 normalizeWorkspaceRelativePath 会剥掉 Unix 绝对路径的
 * 前导 `/`,导致两者不可区分(Windows 靠盘符天然可区分,无需双 `/`)。
 *
 * 单前导 `/` 的输入对 toBusinessAbsolutePath 而言**是已业务化的绝对形态**(工作区业务
 * 路径 `/src/main.ts`),函数对其幂等;它与 Unix 机器原始路径(`/home/user/...`)在字面
 * 上不可区分,故机器原始路径(AI 工具参数形态)一律走 machinePathToBusiness 显式转换,
 * 不得复用 toBusinessAbsolutePath(曾把资源管理器子目录新建文件的 `/.everyagent/t.txt`
 * 误判成 Unix 绝对路径叠加成 `//`,worker 侧当机器路径解析成盘符相对路径
 * `C:\.everyagent\t.txt` 报 NOT_FOUND)。
 */

/** 工作区根(展示形态,实际 API 用空串)。 */
export const WORKSPACE_ROOT = '/'

/** Git 元数据目录名。 */
export const GIT_DIR_NAME = '.git'

/** 应用内部保留目录集合(资源树默认隐藏)。 */
export const INTERNAL_DIR_NAMES = new Set<string>([
  GIT_DIR_NAME,
])

/** 规范化工作区相对路径:反斜杠归一、去重复分隔符、去首尾分隔符。 */
export function normalizeWorkspaceRelativePath(value: string): string {
  return value
    .replace(/\\/g, '/')
    .replace(/^\/+/, '')
    .replace(/\/+/g, '/')
    .split('/')
    .filter(Boolean)
    .join('/')
}

/**
 * 把任意形态的**业务路径**规范为「带前导 / 的绝对业务路径」(展示形态)。
 *
 * 输入是业务坐标路径(工作区相对 `src/main.ts`、Windows 外部盘符 `C:/Users/...`、
 * 或已是业务绝对形态的 `/src/main.ts`、`/C:/Users/...`、`//home/user/...`),
 * 对已是业务形态的输入**幂等**:单前导 `/` 只视为工作区业务绝对形态补齐规范,
 * 不再判定 Unix 绝对(否则 `/src/main.ts` → `//src/main.ts` 双标记叠加,后续
 * 还原机器路径时被当成工作区外 Unix 绝对路径)。
 * 机器原始路径(AI 工具参数,单 `/` 前导的 Unix 绝对路径与业务形态不可区分)
 * 必须走 machinePathToBusiness。
 */
export function toBusinessAbsolutePath(value: string): string {
  const normalized = normalizeWorkspaceRelativePath(value)
  // 已是业务形式的 Unix 外部路径(// 前缀)保持双 / 标记(幂等回显)。
  if (value.replace(/\\/g, '/').startsWith('//')) {
    return '//' + normalized
  }
  return WORKSPACE_ROOT + normalized
}

/**
 * 把**机器原始路径**(AI 工具参数形态:工作区相对 `src/main.ts`、Windows 盘符
 * `C:/Users/...`、Unix 绝对 `/home/user/...`)转为业务绝对路径。
 * 与 toBusinessAbsolutePath 的唯一差异:单前导 `/` 且非盘符的输入按 Unix 机器
 * 绝对路径处理(→ 双 `//` 外部标记)——业务函数对同形态输入按已业务化处理(幂等)。
 */
export function machinePathToBusiness(value: string): string {
  if (isUnixAbsolutePath(value)) {
    return '//' + normalizeWorkspaceRelativePath(value)
  }
  return toBusinessAbsolutePath(value)
}

/**
 * 判断是否为 Unix 绝对路径(原值以 / 开头,且非 Windows 盘符路径)。
 * 反斜杠归一后检查:以 / 开头但不是 `X:` 盘符形态。
 */
function isUnixAbsolutePath(value: string): boolean {
  const normalized = value.replace(/\\/g, '/')
  if (!normalized.startsWith('/')) return false
  // 已是业务形式(// 或 /X:/)的不再重复判定
  if (normalized.startsWith('//')) return true
  // 原值以 / 开头且不是盘符(/C:/...)→ Unix 绝对
  return !/^[A-Za-z]:[\\/]/.test(normalized.slice(1))
}

/**
 * 判断是否为工作区外绝对路径(Windows 盘符或 Unix 绝对)。
 * - Windows: 业务形式 `/C:/Users/...`(单 / + 盘符)
 * - Unix: 业务形式 `//home/user/...`(双 //)
 * 工具调用里的这类路径不经工作区相对解析,文件标签页走 fs.readRaw(不经沙箱)打开。
 */
export function isAbsoluteBusinessPath(value: string): boolean {
  const normalized = value.replace(/\\/g, '/')
  // Unix 外部路径:业务形式以 // 开头
  if (normalized.startsWith('//')) return true
  // Windows 外部路径:业务形式 /C:/... → 盘符
  return /^[A-Za-z]:\//.test(normalizeWorkspaceRelativePath(value))
}

/** 判断路径片段是否命中内部保留目录。 */
export function hasInternalSegment(path: string): boolean {
  const normalized = normalizeWorkspaceRelativePath(path)
  if (!normalized) return false
  return normalized.split('/').some((segment) => INTERNAL_DIR_NAMES.has(segment))
}

/** 判断路径是否应从业务可见树中过滤。 */
export function shouldHideWorkspacePath(path: string): boolean {
  if (path === '/plugins' || path.startsWith('/plugins/')) return true
  return hasInternalSegment(path)
}

/**
 * 把「baseDir 相对 target」合并为工作区相对路径,并防 `..` 越出工作区根。
 * 路径坐标系统一为工作区相对路径(无前导 /);markdown 内嵌图片/文件链接的
 * 相对路径解析共用(以 md 文件所在目录为基准)。
 */
export function resolveWorkspaceRelativePath(baseDir: string, target: string): string {
  const segments = normalizeWorkspaceRelativePath(baseDir).split('/').filter(Boolean)
  for (const part of normalizeWorkspaceRelativePath(target).split('/').filter(Boolean)) {
    if (part === '.') continue
    if (part === '..') {
      segments.pop()
      continue
    }
    segments.push(part)
  }
  return segments.join('/')
}
