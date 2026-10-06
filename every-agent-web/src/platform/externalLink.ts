/**
 * 外部链接统一打开入口(markdown 链接导航等调用方共用)。
 *
 * - web:新开浏览器标签页(window.open + noopener/noreferrer);
 * - desktop:经 Electron preload 暴露的 everyAgentDesktop.openExternal(IPC →
 *   主进程 shell.openExternal)交系统默认浏览器打开——Electron 窗口内直接
 *   加载外部站点体验差且不安全,统一外部化。
 *
 * 只放行 http(s)/mailto/tel 等无副作用的远程协议;javascript: 等危险协议一律忽略。
 */
import { isDesktop } from './desktopBootstrap'

/** 允许交给外部(浏览器 / 系统默认浏览器)打开的链接协议。 */
const EXTERNAL_LINK_PROTOCOL = /^(https?|mailto|tel):/i

/** 判断 url 是否为可安全外部打开的链接(远程协议)。 */
export function isExternalLinkUrl(url: string): boolean {
  return EXTERNAL_LINK_PROTOCOL.test(url.trim())
}

/** 打开外部链接:desktop 走系统默认浏览器,web 新开浏览器标签页。 */
export function openExternalLink(url: string): void {
  const trimmed = url.trim()
  if (!isExternalLinkUrl(trimmed)) return
  if (isDesktop() && typeof window.everyAgentDesktop?.openExternal === 'function') {
    window.everyAgentDesktop.openExternal(trimmed)
    return
  }
  window.open(trimmed, '_blank', 'noopener,noreferrer')
}
