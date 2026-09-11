/**
 * src/components/shared/FileTypeGlyphs.tsx
 *
 * 常见文件后缀的 SVG 图标集:按扩展名渲染「无底色语言 sigil」——主题色粗体
 * 字符(TS / JS / # / M↓ / …),不加徽章底色,观感轻盈;未知扩展名退化为中性
 * 文件轮廓。
 *
 * - 颜色取主题 CSS 变量(--accent-*),深浅色主题自动跟随;
 * - 与旧方案(扩展名→文件名着色)的区别:类型信息由图标承载,文件名统一用
 *   正常文本色,列表更易读;
 * - sigil 用 SVG <text>(粗体),两字符 6.8px / 单字符 8.5px 在 13px 渲染
 *   尺寸下保持可读;Java 用主题色咖啡杯线条画。
 */
import React from 'react'
import { FileTextIcon } from './AppGlyphs'

interface AppGlyphProps {
  size?: number
  style?: React.CSSProperties
}

/** 单个语言 sigil:无底色,主题色粗体字符撑满视窗(基线随字号自动居中)。 */
function LangChip({ size = 16, style, color, sigil, fontSize }: {
  color: string
  sigil: string
  fontSize: number
  size?: number
  style?: React.CSSProperties
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={{ display: 'block', flexShrink: 0, ...style }}
      aria-hidden="true"
    >
      <text
        x="8"
        y={8 + fontSize * 0.36}
        textAnchor="middle"
        fontSize={fontSize}
        fontWeight={900}
        fill={color}
        letterSpacing={sigil.length > 1 ? -0.4 : 0}
      >
        {sigil}
      </text>
    </svg>
  )
}

/** Java:无底色,主题色咖啡杯(杯身 + 杯柄 + 热气),放大撑满视窗。 */
function JavaChip({ size = 16, style }: AppGlyphProps) {
  const cupColor = 'var(--accent-red)'
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={{ display: 'block', flexShrink: 0, ...style }}
      aria-hidden="true"
    >
      <path
        d="M4.2 7.8h6v2.4a2.6 2.6 0 0 1-2.6 2.6H6.8a2.6 2.6 0 0 1-2.6-2.6V7.8Z"
        fill={cupColor}
      />
      <path
        d="M10.4 8.4h0.7a1.5 1.5 0 0 1 0 3h-0.7"
        stroke={cupColor}
        strokeWidth="1.2"
        strokeLinecap="round"
        fill="none"
      />
      <path
        d="M6.3 4.4v1.5M9.1 4.4v1.5"
        stroke={cupColor}
        strokeWidth="1.2"
        strokeLinecap="round"
      />
    </svg>
  )
}

/** Markdown:无底色,主题色粗体 M + 右侧小下箭头(经典 M↓ 记号简化版),放大撑满视窗。 */
function MarkdownChip({ size = 16, style }: AppGlyphProps) {
  const color = 'var(--accent-cyan)'
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={{ display: 'block', flexShrink: 0, ...style }}
      aria-hidden="true"
    >
      <text x="6.4" y="11.8" textAnchor="middle" fontSize="10" fontWeight={900} fill={color}>
        M
      </text>
      <path
        d="M10.9 7.9v3.4m0 0-1.2-1.2m1.2 1.2 1.2-1.2"
        stroke={color}
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    </svg>
  )
}

/** 扩展名 → 图标渲染器(色值沿用旧 FILE_TONE_BY_EXT 的主题变量,观感延续)。 */
const EXT_RENDERERS: Record<string, (props: AppGlyphProps) => React.ReactElement> = {
  ts: (p) => <LangChip {...p} color="var(--accent-blue)" sigil="TS" fontSize={9} />,
  tsx: (p) => <LangChip {...p} color="var(--accent-blue)" sigil="TS" fontSize={9} />,
  js: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="JS" fontSize={9} />,
  jsx: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="JS" fontSize={9} />,
  mjs: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="JS" fontSize={9} />,
  cjs: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="JS" fontSize={9} />,
  json: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="{}" fontSize={9} />,
  html: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="<>" fontSize={8.5} />,
  htm: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="<>" fontSize={8.5} />,
  css: (p) => <LangChip {...p} color="var(--accent-purple)" sigil="#" fontSize={12} />,
  scss: (p) => <LangChip {...p} color="var(--accent-purple)" sigil="#" fontSize={12} />,
  less: (p) => <LangChip {...p} color="var(--accent-purple)" sigil="#" fontSize={12} />,
  md: (p) => <MarkdownChip {...p} />,
  markdown: (p) => <MarkdownChip {...p} />,
  yml: (p) => <LangChip {...p} color="var(--accent-green)" sigil="Y" fontSize={11} />,
  yaml: (p) => <LangChip {...p} color="var(--accent-green)" sigil="Y" fontSize={11} />,
  py: (p) => <LangChip {...p} color="var(--accent-green)" sigil="PY" fontSize={9} />,
  go: (p) => <LangChip {...p} color="var(--accent-cyan)" sigil="GO" fontSize={9} />,
  rs: (p) => <LangChip {...p} color="var(--accent-red)" sigil="RS" fontSize={9} />,
  java: (p) => <JavaChip {...p} />,
}

/**
 * 按文件名渲染文件类型图标:已知扩展名 → 语言徽章;未知 → 中性文件轮廓
 * (跟随文字色,muted 弱化,避免与命中内容抢焦点)。
 */
export function FileTypeIcon({ fileName, size = 16, style }: {
  /** 完整文件名(取最后一个 '.' 之后判断扩展名,无后缀按未知处理)。 */
  fileName: string
  size?: number
  style?: React.CSSProperties
}) {
  const dotIndex = fileName.lastIndexOf('.')
  const ext = dotIndex >= 0 ? fileName.slice(dotIndex + 1).toLowerCase() : ''
  const render = EXT_RENDERERS[ext]
  if (render) {
    return render({ size, style })
  }
  return <FileTextIcon size={size} style={{ color: 'var(--text-muted)', ...style }} />
}
