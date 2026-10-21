/**
 * slash 菜单 icon 净化（审计 M-8 整改）。
 *
 * 背景：slash 候选的 `icon` 字段契约上是「内联 SVG 字符串」，但来源不止宿主内置
 * provider——任意第三方插件可经 worker 的 `WorkerPluginContextImpl.registerSlashProvider`
 * 返回**任意字符串**（icon 契约在注册链上无校验），此前两处渲染点
 * （TaskComposerSurface 的 `/` 菜单、SlashItemDetailPopover 的详情弹层）把它当作
 * 「注册中心内部可信来源」直接 `dangerouslySetInnerHTML` 注入，恶意/被投毒插件可借
 * `<img onerror=…>`、`<svg onload=…>` 等载荷执行脚本。
 *
 * 策略：
 * - icon 一律先经 svg-renderer 的 SVG 净化器（单一来源，`@plugins/svg-renderer/web/sanitize`，
 *   审计 M-5 抽出的可复用模块：白名单元素 + 剥离 on 事件属性 / href / src / style）消毒后才允许注入；
 * - **非 `<svg` 根的形态（`<img …>`、emoji 文本、任意 HTML）直接拒绝**（净化器对非 svg 根
 *   返回 null），不做纯文本渲染：契约明确 icon 为内联 SVG，契约外内容一律视为不可信，
 *   拒绝比兼容渲染更安全，也避免出现第二种渲染形态；调用方拿到 null 渲染占位（空 span）；
 * - 净化结果按原文缓存（icon 字符串在菜单每次渲染重复出现，解析开销不值得反复支付）；
 *   上限 256 条、超限逐出最旧，防恶意插件用海量不同 icon 撑爆内存。
 */
import { sanitizeSvgHtml, type SanitizeDom } from '@plugins/svg-renderer/web/sanitize'

/** 净化结果缓存上限（原文 → 消毒后 markup 或 null）。 */
const CACHE_LIMIT = 256
const cache = new Map<string, string | null>()

/**
 * 净化 slash 条目的 icon 字段（两处渲染点共用的单一入口）。
 *
 * @param raw icon 原文（可空：契约上 icon 可选）
 * @param dom DOM 实现，缺省浏览器全局；node 单测注入替身（注入时旁路缓存）
 * @returns 可安全注入的 SVG markup；输入为空 / 非 SVG / 消毒后无内容 / 环境异常 → null
 *          （调用方据此渲染占位，**绝不**回退原样注入）
 */
export function sanitizeSlashIcon(raw: string | null | undefined, dom?: SanitizeDom): string | null {
  if (typeof raw !== 'string' || raw.trim() === '') {
    return null
  }
  if (!dom) {
    const hit = cache.get(raw)
    if (hit !== undefined) {
      return hit
    }
  }
  let clean: string | null = null
  try {
    clean = sanitizeSvgHtml(raw, dom).html
  } catch {
    // 浏览器环境异常兜底：宁可没图标，不可注入未消毒内容。
    clean = null
  }
  if (!dom) {
    if (cache.size >= CACHE_LIMIT) {
      const oldest = cache.keys().next().value
      if (oldest !== undefined) {
        cache.delete(oldest)
      }
    }
    cache.set(raw, clean)
  }
  return clean
}
