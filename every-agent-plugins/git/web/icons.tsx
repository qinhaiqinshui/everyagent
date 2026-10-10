/**
 * git 插件内部图标集（自宿主 components/icon 与 components/shared/AppGlyphs 复制，
 * 插件不引用宿主模块）。统一用内联 SVG + currentColor，跟随宿主主题色。
 */
import React from 'react'

export type IconProps = {
  size?: number
  color?: string
  className?: string
}

const baseSvgStyle: React.CSSProperties = {
  display: 'block',
}

const APP_SVG_OUTER_SIZE = 22
const APP_SVG_DRAWING_VIEWBOX_SIZE = 16
const APP_SVG_CANVAS_LAYOUT_SIZE = 20
const APP_SVG_CANVAS_OFFSET = (APP_SVG_OUTER_SIZE - APP_SVG_CANVAS_LAYOUT_SIZE) / 2
const APP_SVG_STROKE_WIDTH = 1.6

/** 活动栏图标底座（与宿主 AppSvg 同构）。 */
function AppSvg({ size = 22, color, className, children }: React.PropsWithChildren<IconProps>) {
  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${APP_SVG_OUTER_SIZE} ${APP_SVG_OUTER_SIZE}`}
      aria-hidden="true"
      className={className}
      style={{
        ...baseSvgStyle,
        ...(color ? { color } : null),
      }}
    >
      <svg
        x={APP_SVG_CANVAS_OFFSET}
        y={APP_SVG_CANVAS_OFFSET}
        width={APP_SVG_CANVAS_LAYOUT_SIZE}
        height={APP_SVG_CANVAS_LAYOUT_SIZE}
        viewBox={`0 0 ${APP_SVG_DRAWING_VIEWBOX_SIZE} ${APP_SVG_DRAWING_VIEWBOX_SIZE}`}
        preserveAspectRatio="xMidYMid meet"
        fill="none"
        stroke="currentColor"
        strokeWidth={APP_SVG_STROKE_WIDTH}
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {children}
      </svg>
    </svg>
  )
}

/** Git 图标（源代码管理活动栏入口）。 */
export function GitIcon({ size = 22, color, className }: IconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <circle cx="3.2" cy="3.3" r="1.4" />
      <circle cx="12.8" cy="7.2" r="1.4" />
      <circle cx="3.2" cy="12.7" r="1.4" />
      <path d="M3.2 4.7V11.3" />
      <path d="M4.5 4.2L11.3 6.9" />
      <path d="M4.4 11.8L11 8.3" />
    </AppSvg>
  )
}

export type GlyphProps = {
  size?: number
  style?: React.CSSProperties
  className?: string
}

const glyphBaseStyle: React.CSSProperties = {
  display: 'block',
  flexShrink: 0,
}

function Svg({
  size = 16,
  style,
  className,
  children,
  viewBox = '0 0 16 16',
}: GlyphProps & { children: React.ReactNode; viewBox?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox={viewBox}
      aria-hidden="true"
      className={className}
      style={{ ...glyphBaseStyle, ...style }}
      fill="none"
    >
      {children}
    </svg>
  )
}

export function ChevronRightIcon({ size = 16, style, className }: GlyphProps) {
  return (
    <Svg size={size} style={style} className={className}>
      <path d="M6 3.5L10.5 8L6 12.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  )
}

export function ChevronDownIcon({ size = 16, style, className }: GlyphProps) {
  return (
    <Svg size={size} style={style} className={className}>
      <path d="M3.5 6L8 10.5L12.5 6" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  )
}

export function FolderIcon({ size = 16, style, open = false }: GlyphProps & { open?: boolean }) {
  return open ? (
    <Svg size={size} style={style}>
      <path d="M2.7 5.3H6L7.2 6.4H13.3V11.8H2.7V5.3Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M2.7 6.4L3.6 4.4H13.3" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </Svg>
  ) : (
    <Svg size={size} style={style}>
      <path d="M2.7 4.5H6L7.2 5.6H13.3V11.8H2.7V4.5Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </Svg>
  )
}

export function MoreHorizontalIcon({ size = 16, style, className }: GlyphProps) {
  return (
    <Svg size={size} style={style} className={className}>
      <circle cx="3.2" cy="8" r="1.1" fill="currentColor" />
      <circle cx="8" cy="8" r="1.1" fill="currentColor" />
      <circle cx="12.8" cy="8" r="1.1" fill="currentColor" />
    </Svg>
  )
}
