/**
 * 插件管理面板图标组件。
 *
 * 活动栏图标一律走 AppSvg 底座(与宿主 components/icon/AppSvg、git 插件 icons 同构:
 * 外层 22、内层画布 20 映射 16 viewBox、描边 1.6)。插件禁止引用宿主 web 模块,
 * 故本文件自带一份底座实现,参数与宿主逐项一致。
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

/** 线性小图标底座（16 viewBox，供列表/详情内嵌使用；描边由各图标几何自声明）。 */
function LineSvg({ size = 14, color, className, children }: React.PropsWithChildren<IconProps>) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      aria-hidden="true"
      className={className}
      style={{
        display: 'block',
        flexShrink: 0,
        ...(color ? { color } : null),
      }}
    >
      {children}
    </svg>
  )
}

/**
 * 扩展(Extensions)图标 —— 活动栏入口。
 *
 * 造型与宿主 components/icon/ExtensionsSidebarIcon 逐项一致(三个圆角方块 + 一个菱形),
 * 纯几何描述、不写 fill/stroke,继承底座的 fill=none / stroke=currentColor / 描边 1.6,
 * 与任务、文件、搜索、Git、设置等内置入口同为线性描边风格。
 * (此前用 VSCode 面性 path + fill=currentColor 覆盖底座,是唯一实心图标,风格突兀。)
 * 插件禁止引用宿主 web 模块,故此处复制一份几何。
 */
export function ExtensionIcon({ size = 22, color, className }: IconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <rect x="1.8" y="2.2" width="4.1" height="4.1" rx="0.5" />
      <rect x="1.8" y="9.1" width="4.1" height="4.1" rx="0.5" />
      <rect x="8.7" y="9.1" width="4.1" height="4.1" rx="0.5" />
      <path d="M10.75 1.8L14.2 5.25L10.75 8.7L7.3 5.25L10.75 1.8Z" />
    </AppSvg>
  )
}

/** 搜索图标（工具栏）。 */
export function SearchIcon({ size = 14, color, className }: IconProps) {
  return (
    <LineSvg size={size} color={color} className={className}>
      <circle cx="7" cy="7" r="5" stroke="currentColor" strokeWidth={1.5} fill="none" />
      <path d="M11 11l4 4" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" />
    </LineSvg>
  )
}

/** 重新加载图标（工具栏）。 */
export function ReloadIcon({ size = 14, color, className }: IconProps) {
  return (
    <LineSvg size={size} color={color} className={className}>
      <path
        d="M8 2a6 6 0 1 1-5.6 3.83.5.5 0 0 1 .94.34A5 5 0 1 0 8 3V5l3-2.5L8 0v2z"
        fill="currentColor"
      />
    </LineSvg>
  )
}

/** 安装（下载入托盘）图标（工具栏）。 */
export function InstallIcon({ size = 14, color, className }: IconProps) {
  return (
    <LineSvg size={size} color={color} className={className}>
      <path d="M8 1.5v7" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" />
      <path d="M5 6l3 3 3-3" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" />
      <path d="M2 10.5v2A1.5 1.5 0 0 0 3.5 14h9a1.5 1.5 0 0 0 1.5-1.5v-2" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" />
    </LineSvg>
  )
}

/** 卸载（垃圾桶）图标（详情页动作）。 */
export function UninstallIcon({ size = 14, color, className }: IconProps) {
  return (
    <LineSvg size={size} color={color} className={className}>
      <path d="M2.5 4.5h11" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" />
      <path d="M6 4.5V3a.8.8 0 0 1 .8-.8h2.4A.8.8 0 0 1 10 3v1.5" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" />
      <path d="M4 4.5l.6 8a1 1 0 0 0 1 .9h4.8a1 1 0 0 0 1-.9l.6-8" stroke="currentColor" strokeWidth={1.5} strokeLinejoin="round" fill="none" />
      <path d="M6.6 7v4M9.4 7v4" stroke="currentColor" strokeWidth={1.3} strokeLinecap="round" />
    </LineSvg>
  )
}

/**
 * 默认扩展图标（VSCode 风格占位）——插件未声明 icon / 图标加载失败时的统一底图。
 *
 * 灰底圆角方块 + 中性色扩展几何（与活动栏 ExtensionIcon 同构的三方块 + 菱形），
 * 深浅色主题各自用语义变量（--bg-tertiary 底 / --text-faint 形），全部列表同款，
 * 对标 VSCode 扩展市场无图标时的默认占位图。
 */
export function DefaultPluginIcon({ size = 44 }: { size?: number }) {
  const glyph = Math.round(size * 0.58)
  return (
    <div
      aria-hidden="true"
      style={{
        width: size,
        height: size,
        borderRadius: Math.max(4, Math.round(size * 0.13)),
        background: 'var(--bg-tertiary)',
        border: '1px solid var(--border-light)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
        color: 'var(--text-faint)',
      }}
    >
      <ExtensionIcon size={glyph} />
    </div>
  )
}


