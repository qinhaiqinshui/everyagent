/**
 * AI 安全审议插件自带三态图标（auth.review trace 折叠行标识）。
 *
 * 视觉与宿主 every-agent-web AppGlyphs 16 系对齐：viewBox="0 0 16 16"、
 * stroke="currentColor"（继承壳色）、strokeWidth 1.4、fill=none、display:block + flexShrink:0。
 * 插件 bundle 不依赖宿主产物（external react 由前端运行时全局注入），故自带图标副本而非 import 宿主：
 * - 盾形轮廓与内部对勾 path 取自宿主 AppGlyphs.ShieldCheckIcon（同款视觉）；
 * - 问号 path 取自宿主 AppGlyphs.QuestionMarkIcon（同款视觉，问号弧线 1.3 与宿主一致）；
 * - 盾叉为盾形轮廓 + 两条短交叉线（圆头端点，风格与对勾一致）。
 *
 * 默认 size=14：与宿主 resolveTraceIcon 的 string 通道渲染尺寸 `<Glyph size={14}/>` 对齐，
 * getIcon 走 ReactNode 通道直渲染时即为本默认尺寸。
 */
import type { ReactNode } from 'react'

/** 盾形轮廓 path（取自宿主 AppGlyphs.ShieldCheckIcon，同款视觉）。 */
const SHIELD_PATH = 'M8 2.5L13.1 4.2V7.6C13.1 10.7 10.9 13.2 8 14.1C5.1 13.2 2.9 10.7 2.9 7.6V4.2L8 2.5Z'

/** 16 系图标公共 svg 骨架（与宿主 AppGlyphs 的 Svg 包装同款）。 */
function GlyphSvg({ size, children }: { size: number; children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      aria-hidden="true"
      style={{ display: 'block', flexShrink: 0 }}
      fill="none"
    >
      {children}
    </svg>
  )
}

/** 盾 + 勾：审议通过（ALLOW，及结论缺省兜底）。盾形轮廓与勾形 path 与宿主 ShieldCheckIcon 同款。 */
export function ShieldCheckIcon({ size = 14 }: { size?: number }) {
  return (
    <GlyphSvg size={size}>
      <path d={SHIELD_PATH} stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path
        d="M5.9 8L7.2 9.3L10.1 6.5"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </GlyphSvg>
  )
}

/** 盾 + 叉：审议拒绝（DENY）。盾形轮廓同宿主，内部换两条短交叉线（圆头端点，风格与勾一致）。 */
export function ShieldXIcon({ size = 14 }: { size?: number }) {
  return (
    <GlyphSvg size={size}>
      <path d={SHIELD_PATH} stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M6.15 6.65L9.85 9.55" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M9.85 6.65L6.15 9.55" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </GlyphSvg>
  )
}

/** 问号：审议升级转人工（ESCALATE）。path 复用宿主 AppGlyphs.QuestionMarkIcon 同款视觉。 */
export function QuestionMarkIcon({ size = 14 }: { size?: number }) {
  return (
    <GlyphSvg size={size}>
      <circle cx="8" cy="8" r="5.6" stroke="currentColor" strokeWidth="1.4" />
      <path
        d="M6.2 6.3C6.2 5.1 7.1 4.4 8 4.4C8.9 4.4 9.8 5.1 9.8 6.3C9.8 7.1 9.3 7.5 8.6 7.9C8.1 8.2 8 8.4 8 9V9.3"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
      />
      <circle cx="8" cy="11" r="0.7" fill="currentColor" />
    </GlyphSvg>
  )
}