/**
 * src/components/shared/FileTypeGlyphs.tsx
 *
 * 常见文件后缀的 SVG 图标集:按扩展名渲染「语言徽章」(主题色圆角方块 + 白色
 * sigil),对标 VSCode 的 TS/JS 方块图标风格;未知扩展名退化为中性文件轮廓。
 *
 * - 颜色取主题 CSS 变量(--accent-*),深浅色主题自动跟随;
 * - 与旧方案(扩展名→文件名着色)的区别:类型信息由图标承载,文件名统一用
 *   正常文本色,列表更易读;
 * - 徽章内 sigil 用 SVG <text>(粗体),两字符 6.3px / 单字符 8px 在 13px
 *   渲染尺寸下保持可读;Java 用白色咖啡杯线条画。
 */
import React from 'react'
import { FileTextIcon } from './AppGlyphs'

interface AppGlyphProps {
  size?: number
  style?: React.CSSProperties
}

/** 单个语言徽章:圆角方块(主题色底) + 居中白色 sigil 文本。 */
function LangChip({ size = 16, style, fill, sigil, fontSize }: {
  fill: string
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
      <rect x="1.5" y="2" width="13" height="12" rx="2.5" fill={fill} />
      <text
        x="8"
        y="10.7"
        textAnchor="middle"
        fontSize={fontSize}
        fontWeight={700}
        fill="#fff"
        letterSpacing={sigil.length > 1 ? -0.3 : 0}
      >
        {sigil}
      </text>
    </svg>
  )
}

/** Java:红色徽章 + 白色咖啡杯(杯身 + 杯柄 + 热气)。 */
function JavaChip({ size = 16, style }: AppGlyphProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={{ display: 'block', flexShrink: 0, ...style }}
      aria-hidden="true"
    >
      <rect x="1.5" y="2" width="13" height="12" rx="2.5" fill="var(--accent-red)" />
      <path
        d="M5.2 7.4h5v2.6a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2V7.4Z"
        fill="#fff"
      />
      <path
        d="M10.2 8h0.9a1.5 1.5 0 0 1 0 3h-0.9"
        stroke="#fff"
        strokeWidth="1.1"
        strokeLinecap="round"
        fill="none"
      />
      <path
        d="M6.5 4.9v1.2M8.9 4.9v1.2"
        stroke="#fff"
        strokeWidth="1.1"
        strokeLinecap="round"
      />
    </svg>
  )
}

/** Markdown:青色徽章 + 白色粗体 M + 右侧小下箭头(经典 M↓ 记号简化版)。 */
function MarkdownChip({ size = 16, style }: AppGlyphProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={{ display: 'block', flexShrink: 0, ...style }}
      aria-hidden="true"
    >
      <rect x="1.5" y="2" width="13" height="12" rx="2.5" fill="var(--accent-cyan)" />
      <text x="7" y="10.8" textAnchor="middle" fontSize="7.5" fontWeight={700} fill="#fff">
        M
      </text>
      <path
        d="M10.6 8.4v2.4m0 0-1-1m1 1 1-1"
        stroke="#fff"
        strokeWidth="1.1"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    </svg>
  )
}

/** 扩展名 → 图标渲染器(色值沿用旧 FILE_TONE_BY_EXT 的主题变量,观感延续)。 */
const EXT_RENDERERS: Record<string, (props: AppGlyphProps) => React.ReactElement> = {
  ts: (p) => <LangChip {...p} fill="var(--accent-blue)" sigil="TS" fontSize={6.3} />,
  tsx: (p) => <LangChip {...p} fill="var(--accent-blue)" sigil="TS" fontSize={6.3} />,
  js: (p) => <LangChip {...p} fill="var(--accent-amber)" sigil="JS" fontSize={6.3} />,
  jsx: (p) => <LangChip {...p} fill="var(--accent-amber)" sigil="JS" fontSize={6.3} />,
  mjs: (p) => <LangChip {...p} fill="var(--accent-amber)" sigil="JS" fontSize={6.3} />,
  cjs: (p) => <LangChip {...p} fill="var(--accent-amber)" sigil="JS" fontSize={6.3} />,
  json: (p) => <LangChip {...p} fill="var(--accent-amber)" sigil="{}" fontSize={6.2} />,
  html: (p) => <LangChip {...p} fill="var(--accent-amber)" sigil="<>" fontSize={5.8} />,
  htm: (p) => <LangChip {...p} fill="var(--accent-amber)" sigil="<>" fontSize={5.8} />,
  css: (p) => <LangChip {...p} fill="var(--accent-purple)" sigil="#" fontSize={8} />,
  scss: (p) => <LangChip {...p} fill="var(--accent-purple)" sigil="#" fontSize={8} />,
  less: (p) => <LangChip {...p} fill="var(--accent-purple)" sigil="#" fontSize={8} />,
  md: (p) => <MarkdownChip {...p} />,
  markdown: (p) => <MarkdownChip {...p} />,
  yml: (p) => <LangChip {...p} fill="var(--accent-green)" sigil="Y" fontSize={8} />,
  yaml: (p) => <LangChip {...p} fill="var(--accent-green)" sigil="Y" fontSize={8} />,
  py: (p) => <LangChip {...p} fill="var(--accent-green)" sigil="PY" fontSize={6.3} />,
  go: (p) => <LangChip {...p} fill="var(--accent-cyan)" sigil="GO" fontSize={6.3} />,
  rs: (p) => <LangChip {...p} fill="var(--accent-red)" sigil="RS" fontSize={6.3} />,
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
