/**
 * git 插件内部 FileTextIcon（自宿主 components/shared/AppGlyphs 复制）。
 * 未知扩展名的兜底中性文件轮廓。
 */
import React from 'react'

interface AppGlyphProps {
  size?: number
  style?: React.CSSProperties
}

export function FileTextIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={{ display: 'block', flexShrink: 0, ...style }}
      aria-hidden="true"
      fill="none"
    >
      <path d="M4.3 2.5H9.3L11.7 4.9V13.4H4.3V2.5Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M9.3 2.5V4.9H11.7" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M6 7.1H10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M6 9.2H10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}
