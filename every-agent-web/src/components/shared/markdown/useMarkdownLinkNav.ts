/**
 * src/components/shared/markdown/useMarkdownLinkNav.ts
 *
 * markdown 链接统一导航 hook（md 预览与聊天消息渲染共用，自 MarkdownPreview 抽取）。
 *
 * 分发规则：
 * - `#锚点` → 交 onAnchor 消费（如预览的页内标题滚动）；未提供时拦截点击但不动作
 *   （避免浏览器改 hash 扰动 SPA 路由）；
 * - http(s)/mailto/tel 外链 → 统一外链入口（web 新开浏览器标签页 / desktop 系统默认浏览器）；
 * - 其余带协议前缀（如 ftp:）→ toast 提示后忽略，不当作工作区路径解析；
 * - 相对路径 → 以 baseDir（预览 = md 所在目录，聊天 = 工作区根）解析为工作区相对路径，
 *   经网关存在性校验后打开文件标签页；指向目录 / 文件不存在 / 缺工作区上下文以 toast 报错。
 *
 * 返回 true = 已处理（渲染器拦截浏览器默认行为），false = 放行浏览器默认行为。
 * 壳层 context 缺失（无 Provider 的测试等环境）时整体降级为不接管（恒返回 false）。
 */
import { useCallback } from 'react'
import { useWorkspaceShellOptional } from '@/components/app/WorkspaceShellContext'
import { useAppUiOptional } from '@/components/app/AppUiContext'
import { resolveWorkspaceRelativePath, toBusinessAbsolutePath, decodeMarkdownUri } from '@/platform/fs/pathUtils'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { isExternalLinkUrl, openExternalLink } from '@/platform/externalLink'

/** 链接导航回调：入参为 href，返回 true 表示已处理（拦截默认行为）。 */
export type MarkdownLinkNavigator = (href: string) => boolean

export function useMarkdownLinkNav(options: {
  /** 所属工作区根；缺省时相对路径链接以 toast 报错。 */
  workspaceRoot?: string
  /** 相对路径解析基准（工作区相对目录，空串 = 工作区根）。 */
  baseDir?: string
  /** `#锚点` 消费方（如预览的页内滚动）；未提供时锚点点击被拦截但不动作。 */
  onAnchor?: (anchor: string) => void
}): MarkdownLinkNavigator {
  const { workspaceRoot, baseDir = '', onAnchor } = options
  const shell = useWorkspaceShellOptional()
  const ui = useAppUiOptional()
  const showToast = ui?.showToast ?? (() => {})
  const openGlobalFileTab = shell?.openGlobalFileTab

  /** 工作区文件链接：剥离 #fragment、按 baseDir 解析相对路径，存在性校验后开文件标签页。 */
  const openWorkspaceFileLink = useCallback((rawHref: string) => {
    // fragment(目标文件内锚点)当前标签页体系无法承载——文件定位只到行号;这里只管打开文件。
    // 目的地被 CommonMark URI 规范化过(中文 → %XX 编码),先解码再解析。
    const pathPart = rawHref.split('#')[0] ?? ''
    const target = decodeMarkdownUri(pathPart)
    const rel = resolveWorkspaceRelativePath(baseDir, target)
    if (!rel) return
    const businessPath = toBusinessAbsolutePath(rel)
    if (!workspaceRoot || !openGlobalFileTab) {
      showToast(`缺少工作区上下文，无法打开文件链接：${businessPath}`, 'error')
      return
    }
    void workspaceGateway.statRaw(workspaceRoot, rel)
      .then((stat) => {
        if (stat.isDirectory) {
          showToast(`链接指向的是目录而非文件：${businessPath}`, 'error')
          return
        }
        openGlobalFileTab({ workspaceRoot, filePath: businessPath })
      })
      .catch(() => {
        showToast(`链接指向的文件不存在：${businessPath}`, 'error')
      })
  }, [baseDir, openGlobalFileTab, showToast, workspaceRoot])

  return useCallback((href: string): boolean => {
    const trimmed = href.trim()
    if (!trimmed) return false
    if (trimmed.startsWith('#')) {
      const anchor = trimmed.slice(1)
      if (anchor && onAnchor) onAnchor(anchor)
      return true
    }
    if (isExternalLinkUrl(trimmed)) {
      openExternalLink(trimmed)
      return true
    }
    // 带协议前缀但不在放行名单(如 ftp:)的链接:提示后忽略,不当作工作区文件路径解析。
    if (/^[a-z][a-z0-9+.-]*:/i.test(trimmed)) {
      showToast(`暂不支持打开该协议的链接：${trimmed}`, 'error')
      return true
    }
    openWorkspaceFileLink(trimmed)
    return true
  }, [onAnchor, openWorkspaceFileLink, showToast])
}
