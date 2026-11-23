/**
 * workflow 插件内部图标集——全部内联 SVG + currentColor（跟随宿主主题色）。
 *
 * 纪律：插件不得 `@/` 引宿主源码，图标一律自持（自宿主 components/icon 的
 * AppSvg 构型复制底座）。画布内部用 *Glyph 系列（纯 path 片段，16×16 视口，
 * stroke 继承外层 color），面板/工具栏用 *Icon 系列（独立成形的 Svg 底座）。
 */
import React from 'react'

export type IconProps = {
  size?: number
  color?: string
  className?: string
  style?: React.CSSProperties
}

const baseSvgStyle: React.CSSProperties = {
  display: 'block',
}

const APP_SVG_OUTER_SIZE = 22
const APP_SVG_DRAWING_VIEWBOX_SIZE = 16
const APP_SVG_CANVAS_LAYOUT_SIZE = 20
const APP_SVG_CANVAS_OFFSET = (APP_SVG_OUTER_SIZE - APP_SVG_CANVAS_LAYOUT_SIZE) / 2
const APP_SVG_STROKE_WIDTH = 1.6

/** 活动栏图标底座（与宿主 AppSvg 同构：22 外框 / 16 视口 / 1.6 描边）。 */
function AppSvg({ size = 22, color, className, children }: React.PropsWithChildren<IconProps>) {
  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${APP_SVG_OUTER_SIZE} ${APP_SVG_OUTER_SIZE}`}
      aria-hidden="true"
      className={className}
      style={{ ...baseSvgStyle, ...(color ? { color } : null) }}
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

const glyphBaseStyle: React.CSSProperties = {
  display: 'block',
  flexShrink: 0,
}

/** 通用 16×16 描边图标底座（工具栏/属性面板/调色板）。 */
function Svg({
  size = 16,
  style,
  className,
  color,
  children,
}: React.PropsWithChildren<IconProps>) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      aria-hidden="true"
      className={className}
      style={{ ...glyphBaseStyle, ...(color ? { color } : null), ...style }}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {children}
    </svg>
  )
}

// ── 活动栏 ────────────────────────────────────────────────────────────────

/** 工作流图标（活动栏入口）：节点 + 两段连线。 */
export function WorkflowIcon({ size = 22, color, className }: IconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <rect x="2" y="2.2" width="4.4" height="3.4" rx="1" />
      <rect x="9.6" y="2.2" width="4.4" height="3.4" rx="1" />
      <rect x="5.8" y="10.4" width="4.4" height="3.4" rx="1" />
      <path d="M4.2 5.6V8.2H8V10.4" />
      <path d="M11.8 5.6V8.2H8" />
    </AppSvg>
  )
}

// ── 节点类型 Glyph（画布内嵌：16×16 path 片段，stroke=currentColor） ────────

/** 开始节点 glyph：实心起播三角。 */
export function StartNodeGlyph() {
  return (
    <>
      <path d="M5 3.5L12 8L5 12.5V3.5Z" fill="currentColor" stroke="none" />
    </>
  )
}

/** 命令节点 glyph：终端提示符 >_。 */
export function CommandNodeGlyph() {
  return (
    <>
      <rect x="2" y="3" width="12" height="10" rx="1.5" />
      <path d="M4.6 6.2L6.8 8L4.6 9.8" />
      <path d="M8.2 10.2H11.4" />
    </>
  )
}

/** AI 节点 glyph：四角星芒。 */
export function AiNodeGlyph() {
  return (
    <>
      <path d="M8 2.2L9.3 6.7L13.8 8L9.3 9.3L8 13.8L6.7 9.3L2.2 8L6.7 6.7L8 2.2Z" />
    </>
  )
}

/** 按节点类型取 glyph（未知类型回退圆点）。 */
export function nodeTypeGlyph(type: string): React.ReactNode {
  if (type === 'start') return <StartNodeGlyph />
  if (type === 'command') return <CommandNodeGlyph />
  if (type === 'ai') return <AiNodeGlyph />
  return <circle cx="8" cy="8" r="3" fill="currentColor" stroke="none" />
}

// ── 16×16 通用图标 ────────────────────────────────────────────────────────

/** 开始节点（palette）。 */
export function StartNodeIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M5 3.5L12 8L5 12.5V3.5Z" fill="currentColor" stroke="none" />
    </Svg>
  )
}

/** 命令节点（palette）。 */
export function CommandNodeIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <rect x="2" y="3" width="12" height="10" rx="1.5" />
      <path d="M4.6 6.2L6.8 8L4.6 9.8" />
      <path d="M8.2 10.2H11.4" />
    </Svg>
  )
}

/** AI 节点（palette）。 */
export function AiNodeIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M8 2.2L9.3 6.7L13.8 8L9.3 9.3L8 13.8L6.7 9.3L2.2 8L6.7 6.7L8 2.2Z" />
    </Svg>
  )
}

/** 运行（步骤 9 运行对话框用；先备好）。 */
export function RunIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M4 2.8L12.6 8L4 13.2V2.8Z" />
    </Svg>
  )
}

/** 删除（节点/边/变量/agent 行）。 */
export function DeleteIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M3 4.6H13" />
      <path d="M6.4 4.4V3H9.6V4.4" />
      <path d="M4.4 4.6L5 13H11L11.6 4.6" />
      <path d="M6.7 7V10.6M9.3 7V10.6" />
    </Svg>
  )
}

/** 变量声明。 */
export function VariableIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M5.6 3C4 3 3.6 4.2 3.6 5.6V7L2.6 8L3.6 9V10.4C3.6 11.8 4 13 5.6 13" />
      <path d="M10.4 3C12 3 12.4 4.2 12.4 5.6V7L13.4 8L12.4 9V10.4C12.4 11.8 12 13 10.4 13" />
      <path d="M6.8 8H9.2" />
    </Svg>
  )
}

/** 定义设置（工具栏折叠面板）。 */
export function SettingsIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <circle cx="8" cy="8" r="2" />
      <path d="M8 1.8V3.4M8 12.6V14.2M1.8 8H3.4M12.6 8H14.2M3.6 3.6L4.7 4.7M11.3 11.3L12.4 12.4M12.4 3.6L11.3 4.7M4.7 11.3L3.6 12.4" />
    </Svg>
  )
}

/** 添加（palette 按钮 / 变量与 agent 行）。 */
export function PlusIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M8 3V13M3 8H13" />
    </Svg>
  )
}

/** 边条件。 */
export function ConditionIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M2.6 8H6.2C6.2 5.6 8 4 8 4H13.4" />
      <path d="M2.6 8H6.2C6.2 10.4 8 12 8 12H13.4" />
      <path d="M11.6 6.4L13.4 8L11.6 9.6" />
    </Svg>
  )
}

/** 命名 agent。 */
export function AgentIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <rect x="4" y="6" width="8" height="6.6" rx="1.6" />
      <path d="M8 6V3.8" />
      <circle cx="8" cy="3" r="1" />
      <path d="M6.4 9.2H6.9M9.1 9.2H9.6" />
    </Svg>
  )
}

/** 督导（supervisor）。 */
export function SupervisorIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M3 4.4L8 2.6L13 4.4V7.2C13 10.6 10.9 12.6 8 13.4C5.1 12.6 3 10.6 3 7.2V4.4Z" />
      <path d="M5.8 8L7.4 9.6L10.4 6.6" />
    </Svg>
  )
}

// ── 侧边栏 / 运行对话框（步骤 9） ─────────────────────────────────────────

/** 编辑（定义行入口）。 */
export function EditIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M3.2 12.8L3.8 10.3L10.5 3.6L12.4 5.5L5.7 12.2L3.2 12.8Z" />
      <path d="M9.4 2.6L11.4 4.6L12.6 3.4L10.6 1.4L9.4 2.6Z" />
    </Svg>
  )
}

/** 导出（下载 definition JSON）。 */
export function DownloadIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M8 2.6V10" />
      <path d="M4.8 7.2L8 10.4L11.2 7.2" />
      <path d="M2.8 13.2H13.2" />
    </Svg>
  )
}

/** 导入（上传 definition JSON）。 */
export function UploadIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M8 10.4V3" />
      <path d="M4.8 6.2L8 3L11.2 6.2" />
      <path d="M2.8 13.2H13.2" />
    </Svg>
  )
}

/** 折叠区展开（chevron 向下）。 */
export function ChevronDownIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M4 6L8 10L12 6" />
    </Svg>
  )
}

/** 折叠区收起（chevron 向右）。 */
export function ChevronRightIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M6 4L10 8L6 12" />
    </Svg>
  )
}

/** 取消实例（停止方块）。 */
export function StopIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <rect x="3.6" y="3.6" width="8.8" height="8.8" rx="1.5" />
    </Svg>
  )
}

/** 手动刷新列表。 */
export function RefreshIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M13 8.2A5.1 5.1 0 1 1 11.1 4" />
      <path d="M11.2 1.6L11.3 4.2L13.9 4.1" />
    </Svg>
  )
}

// ── 实例运行页（步骤 10） ─────────────────────────────────────────────────

/** 运行日志/时间线（实例页「运行图 / 运行日志」视图切换）。 */
export function LogIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M2.8 3.6H5M2.8 8H5M2.8 12.4H5" />
      <path d="M7.2 3.6H13.2M7.2 8H13.2M7.2 12.4H10.4" />
    </Svg>
  )
}

/** 重试（waiting-user 决策按钮）。 */
export function RetryIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M3.2 6.2A5.3 5.3 0 1 1 2.9 9.8" />
      <path d="M2.9 3.2L3.2 6.4L6.4 6" />
    </Svg>
  )
}

/** 跳过（waiting-user 决策按钮）。 */
export function SkipIcon({ size = 16, style, className, color }: IconProps) {
  return (
    <Svg size={size} style={style} className={className} color={color}>
      <path d="M3.4 4L7.6 8L3.4 12" />
      <path d="M8.4 4L12.6 8L8.4 12" />
    </Svg>
  )
}
