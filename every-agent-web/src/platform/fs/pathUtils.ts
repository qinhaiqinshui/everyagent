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

/** 把任意形态的业务路径规范为「带前导 / 的绝对业务路径」(展示形态)。 */
export function toBusinessAbsolutePath(value: string): string {
  const normalized = normalizeWorkspaceRelativePath(value)
  // Unix 绝对路径(原值以 / 开头且非盘符)需用双 // 标记,使其与工作区相对路径可区分。
  // Windows 盘符路径(C:/...)无前导 /,正常补单 / 即可(靠盘符区分)。
  if (isUnixAbsolutePath(value)) {
    return '//' + normalized
  }
  return WORKSPACE_ROOT + normalized
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
