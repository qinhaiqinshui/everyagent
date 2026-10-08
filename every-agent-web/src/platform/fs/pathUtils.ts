/**
 * 工作区路径纯函数工具(自 n 分支 zenfsPathUtils 迁移,去除 ZenFS 语义)。
 *
 * 路径坐标系:全系统统一「工作区相对路径」——空串 = 工作区根,
 * 目录间以 `/` 分隔,无前导 `/`。网关(fs.*)以此坐标系与 worker 交换;
 * UI 展示层用 toBusinessAbsolutePath 补前导 `/`。
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
  return WORKSPACE_ROOT + normalizeWorkspaceRelativePath(value)
}

/**
 * 判断是否为工作区外绝对路径(Windows 盘符形态,如 C:/Windows/win.ini)。
 * 工具调用里的这类路径不经工作区相对解析,打不开时需走越界兜底(授权根/只读打开)。
 */
export function isAbsoluteBusinessPath(value: string): boolean {
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
