import React from 'react'

export type AppGlyphProps = {
  size?: number
  style?: React.CSSProperties
  className?: string
  flipped?: boolean
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
}: AppGlyphProps & { children: React.ReactNode; viewBox?: string }) {
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

export function CloseIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M4 4L12 12" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M12 4L4 12" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </Svg>
  )
}

export function ChevronRightIcon({ size = 16, style, className }: AppGlyphProps) {
  return (
    <Svg size={size} style={style} className={className}>
      <path d="M6 3.5L10.5 8L6 12.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  )
}

export function ChevronDownIcon({ size = 16, style, className }: AppGlyphProps) {
  return (
    <Svg size={size} style={style} className={className}>
      <path d="M3.5 6L8 10.5L12.5 6" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  )
}

export function ArrowRightIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M3.2 8H12.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <path d="M8.9 4.7L12.2 8L8.9 11.3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  )
}

/** 问号图标（圆形轮廓 + 问号），用于「AI 等待你的回答」类交互弹窗标题。 */
export function QuestionMarkIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <circle cx="8" cy="8" r="5.6" stroke="currentColor" strokeWidth="1.4" />
      <path
        d="M6.2 6.3C6.2 5.1 7.1 4.4 8 4.4C8.9 4.4 9.8 5.1 9.8 6.3C9.8 7.1 9.3 7.5 8.6 7.9C8.1 8.2 8 8.4 8 9V9.3"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
      />
      <circle cx="8" cy="11" r="0.7" fill="currentColor" />
    </Svg>
  )
}

export function ArrowDownIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M8 3.2V12.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <path d="M4.7 8.9L8 12.2L11.3 8.9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  )
}

export function CopyIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <rect x="5.3" y="3.5" width="6.2" height="8" rx="1.2" stroke="currentColor" strokeWidth="1.4" />
      <path d="M4.2 5V11A1.2 1.2 0 0 0 5.4 12.2H10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </Svg>
  )
}

export function CheckIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M3.8 8.2L6.7 11.1L12.2 5.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  )
}

export function AlertTriangleIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M8 2.6L13.2 12H2.8L8 2.6Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M8 5.8V8.8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      <circle cx="8" cy="11" r="0.8" fill="currentColor" />
    </Svg>
  )
}

export function StopIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <rect x="4" y="4" width="8" height="8" rx="1.6" fill="currentColor" />
    </Svg>
  )
}

export function CircleIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <circle cx="8" cy="8" r="4.6" stroke="currentColor" strokeWidth="1.5" />
    </Svg>
  )
}

export function DotCircleIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <circle cx="8" cy="8" r="4.6" stroke="currentColor" strokeWidth="1.5" />
      <circle cx="8" cy="8" r="2.4" fill="currentColor" />
    </Svg>
  )
}

export function DoubleCircleIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <circle cx="8" cy="8" r="4.8" stroke="currentColor" strokeWidth="1.4" />
      <circle cx="8" cy="8" r="2.6" stroke="currentColor" strokeWidth="1.4" />
    </Svg>
  )
}

export function SpinnerIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <circle cx="8" cy="8" r="4.8" stroke="currentColor" strokeWidth="1.4" opacity="0.25" />
      <path d="M8 3.2A4.8 4.8 0 0 1 12.1 5.4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </Svg>
  )
}

export function FileIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M4.5 2.7H9.4L11.8 5.1V13.2H4.5V2.7Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M9.4 2.7V5.1H11.8" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </Svg>
  )
}

export function FileTextIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M4.3 2.5H9.3L11.7 4.9V13.4H4.3V2.5Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M9.3 2.5V4.9H11.7" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M6 7.1H10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M6 9.2H10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </Svg>
  )
}

export function FolderIcon({ size = 16, style, open = false }: AppGlyphProps & { open?: boolean }) {
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

/** 新建文件图标（文件轮廓 + 加号）。 */
export function FilePlusIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M4.5 2.7H9.4L11.8 5.1V13.2H4.5V2.7Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M9.4 2.7V5.1H11.8" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M8.15 8.2V11.2" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M6.65 9.7H9.65" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </Svg>
  )
}

/** 新建目录图标（文件夹轮廓 + 加号）。 */
export function FolderPlusIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M2.7 4.5H6L7.2 5.6H13.3V11.8H2.7V4.5Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M8 7.3V10.3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M6.5 8.8H9.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </Svg>
  )
}

/** 下载图标（托盘 + 向下箭头）。 */
export function DownloadIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M8 2.6V9.2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      <path d="M5.2 6.6L8 9.4L10.8 6.6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M3.4 11.6H12.6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </Svg>
  )
}

/** 上传图标（托盘 + 向上箭头）。 */
export function UploadIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M8 9.4V2.8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      <path d="M5.2 5.4L8 2.6L10.8 5.4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M3.4 11.6H12.6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </Svg>
  )
}

export function SparkIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M8 2.3L9.2 6.8L13.7 8L9.2 9.2L8 13.7L6.8 9.2L2.3 8L6.8 6.8L8 2.3Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
    </Svg>
  )
}

export function DiamondInfoIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M8 2.6L13.3 8L8 13.4L2.7 8L8 2.6Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <circle cx="8" cy="5.9" r="0.8" fill="currentColor" />
      <path d="M8 7.4V10.3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </Svg>
  )
}

export function LogLinesIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M4 4.5H12" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M4 8H12" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M4 11.5H9.8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </Svg>
  )
}

export function HourglassIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M4.5 2.8H11.5V4.4L9 7.1L11.5 9.7V12.2H4.5V9.7L7 7.1L4.5 4.4V2.8Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M6.2 4.1H9.8" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
      <path d="M6.2 10.9H9.8" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
    </Svg>
  )
}

export function BrainIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M6.2 3.3C4.5 3.3 3.2 4.6 3.2 6.3C3.2 7 3.4 7.5 3.8 8C3.4 8.5 3.2 9 3.2 9.7C3.2 11.4 4.5 12.7 6.2 12.7C7 12.7 7.6 12.4 8 11.9C8.4 12.4 9 12.7 9.8 12.7C11.5 12.7 12.8 11.4 12.8 9.7C12.8 9 12.6 8.5 12.2 8C12.6 7.5 12.8 7 12.8 6.3C12.8 4.6 11.5 3.3 9.8 3.3C9 3.3 8.4 3.6 8 4.1C7.6 3.6 7 3.3 6.2 3.3Z" stroke="currentColor" strokeWidth="1.3" />
      <path d="M8 4.2V11.8" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
      <path d="M5.4 6.2H6.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
      <path d="M9.5 6.2H10.6" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
      <path d="M5.4 9.7H6.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
      <path d="M9.5 9.7H10.6" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
    </Svg>
  )
}

export function ChatBubbleIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M3.2 4.2H12.8V9.8H7.4L4.8 12V9.8H3.2V4.2Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </Svg>
  )
}

export function BoltIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M8.9 2.5L4.5 8H7.5L6.9 13.5L11.5 7.5H8.5L8.9 2.5Z" fill="currentColor" />
    </Svg>
  )
}

export function ReplyArrowIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M6.2 5.1L3.5 8L6.2 10.9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M4 8H9.4C10.9 8 12.1 9.2 12.1 10.7V11.3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </Svg>
  )
}

export function MagnifierCheckIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <circle cx="6.8" cy="6.8" r="3.3" stroke="currentColor" strokeWidth="1.4" />
      <path d="M9.4 9.4L12.5 12.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M5.4 6.8L6.5 7.9L8.4 5.9" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  )
}

export function SearchIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <circle cx="6.8" cy="6.8" r="3.3" stroke="currentColor" strokeWidth="1.4" />
      <path d="M9.4 9.4L12.5 12.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </Svg>
  )
}

/** 横向更多操作图标。 */
export function MoreHorizontalIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <circle cx="4" cy="8" r="1.2" fill="currentColor" />
      <circle cx="8" cy="8" r="1.2" fill="currentColor" />
      <circle cx="12" cy="8" r="1.2" fill="currentColor" />
    </Svg>
  )
}

/** 加号图标（新建/添加）。 */
export function PlusIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M8 3.2V12.8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M3.2 8H12.8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </Svg>
  )
}

export function FlaskIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path d="M6.1 2.8H9.9" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M7 2.8V6L4.2 10.8A1.4 1.4 0 0 0 5.4 12.9H10.6A1.4 1.4 0 0 0 11.8 10.8L9 6V2.8" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M5.6 9.2H10.4" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
    </Svg>
  )
}

export function WrenchIcon({ size = 16, style, className, flipped = false }: AppGlyphProps) {
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
      {/* flipped：沿 viewBox 中线水平镜像（22 0 平移 + scale(-1 1)），与下发扳手方向相反 */}
      {flipped ? <g transform="translate(22 0) scale(-1 1)">{path}</g> : path}
    </Svg>
  )
}

/**
 * 盾牌校验图标（AI 安全审议 auth.review trace 用）：盾牌轮廓 + 对勾。
 * 语义：AI 安全审议已对授权请求给出结论（放行/拒绝/升级）。
 */
export function ShieldCheckIcon({ size = 16, style }: AppGlyphProps) {
  return (
    <Svg size={size} style={style}>
      <path
        d="M8 2.5L13.1 4.2V7.6C13.1 10.7 10.9 13.2 8 14.1C5.1 13.2 2.9 10.7 2.9 7.6V4.2L8 2.5Z"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinejoin="round"
      />
      <path d="M5.9 8L7.2 9.3L10.1 6.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  )
}
