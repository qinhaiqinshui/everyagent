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

