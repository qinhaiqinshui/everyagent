/**
 * update-file-view 插件内部图标（自宿主 components/shared/AppGlyphs 复制，
 * 插件不引用宿主模块）。内联 SVG + currentColor。
 */
import React from 'react'

export type GlyphProps = {
  size?: number
  style?: React.CSSProperties
  className?: string
}

const baseStyle: React.CSSProperties = {
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
      style={{ ...baseStyle, ...style }}
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

export function WrenchIcon({ size = 16, style, className, flipped = false }: GlyphProps & { flipped?: boolean }) {
  const path = (
    <path
      d="M13.7 4.4A4.4 4.4 0 0 0 9.1 10l-4.7 4.7a1.4 1.4 0 0 0 0 2l.9.9a1.4 1.4 0 0 0 2 0l4.7-4.7a4.4 4.4 0 0 0 5.6-4.6l-2.6 1.4-2.4-2.4 1.5-2.9Z"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinejoin="round"
    />
  )
  return (
    <Svg size={size} style={style} className={className} viewBox="0 0 22 22">
      {flipped ? <g transform="translate(22 0) scale(-1 1)">{path}</g> : path}
    </Svg>
  )
}
