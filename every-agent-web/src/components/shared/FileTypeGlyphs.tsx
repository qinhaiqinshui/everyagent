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

/** 图片:紫色图片轮廓(山 + 太阳),png/jpg/gif/svg 等共用。 */
function ImageChip({ size = 16, style }: AppGlyphProps) {
  const color = 'var(--accent-purple)'
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={{ display: 'block', flexShrink: 0, ...style }}
      aria-hidden="true"
    >
      <rect
        x="2.2"
        y="3.2"
        width="11.6"
        height="9.6"
        rx="1.8"
        fill="none"
        stroke={color}
        strokeWidth="1.4"
      />
      <circle cx="5.7" cy="6.3" r="1.1" fill={color} />
      <path
        d="M3.4 11.6 6.8 8.4l2 1.9 2-2.1 2 2.1"
        stroke={color}
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    </svg>
  )
}

/** Docker:青色鲸鱼驮集装箱(容器/镜像身份),线条式。 */
function DockerChip({ size = 16, style }: AppGlyphProps) {
  const color = 'var(--accent-cyan)'
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={{ display: 'block', flexShrink: 0, ...style }}
      aria-hidden="true"
    >
      {/* 上层集装箱(两枚,带内嵌线增强体积感) */}
      <path d="M3.7 6.3h2.6v2.4H3.7z M6.7 6.3h2.6v2.4H6.7z" fill={color} opacity="0.85" />
      {/* 下层集装箱(三枚) */}
      <path d="M2.2 9.1h2.6v2.4H2.2z M5.2 9.1h2.6v2.4H5.2z M8.2 9.1h2.6v2.4H8.2z" fill={color} />
      {/* 鲸鱼身(托起集装箱的曲线船体) */}
      <path
        d="M1.6 12.2h12.1c.9 0 1.6-.5 1.9-1.3l.3-.8c.1-.3-.2-.6-.5-.5l-1.5.5"
        stroke={color}
        strokeWidth="1.2"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
      <path d="M1.6 12.2c-.5 0-.8-.4-.8-.8v-.3h.8v1.1Z" fill={color} />
    </svg>
  )
}

/** Maven pom:琥珀色 m + 左上小角标,呼应 pom.xml 的 Maven 身份。 */
function PomChip({ size = 16, style }: AppGlyphProps) {
  const color = 'var(--accent-amber)'
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={{ display: 'block', flexShrink: 0, ...style }}
      aria-hidden="true"
    >
      <text x="8" y="11.6" textAnchor="middle" fontSize="10.5" fontWeight={900} fill={color}>
        m
      </text>
      <path
        d="M11.6 4.2v1.4"
        stroke={color}
        strokeWidth="1.2"
        strokeLinecap="round"
      />
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
  txt: (p) => <LangChip {...p} color="var(--text-secondary)" sigil="TXT" fontSize={6.2} />,
  zip: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="ZIP" fontSize={6.2} />,
  png: (p) => <ImageChip {...p} />,
  jpg: (p) => <ImageChip {...p} />,
  jpeg: (p) => <ImageChip {...p} />,
  gif: (p) => <ImageChip {...p} />,
  svg: (p) => <ImageChip {...p} />,
  webp: (p) => <ImageChip {...p} />,
  ico: (p) => <ImageChip {...p} />,
  pom: (p) => <PomChip {...p} />,
  // docker 相关:yml/yaml 扩展名已归属 YAML,compose 身份靠文件名前缀特判。
  'dockerfile': (p) => <DockerChip {...p} />,
  // shell 脚本:琥珀色 $ 提示符(sh/bash/zsh/fish 脚本与 bashrc/zshrc/profile 配置)。
  sh: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  bash: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  zsh: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  fish: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  ksh: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  csh: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  bashrc: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  zshrc: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  profile: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  // PowerShell:蓝色 PS 字标(脚本 ps1 / 模块 psm1 / 模块清单 psd1)。
  ps1: (p) => <LangChip {...p} color="var(--accent-blue)" sigil="PS" fontSize={9} />,
  psm1: (p) => <LangChip {...p} color="var(--accent-blue)" sigil="PS" fontSize={9} />,
  psd1: (p) => <LangChip {...p} color="var(--accent-blue)" sigil="PS" fontSize={9} />,
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
  const lowerName = fileName.toLowerCase()
  // 按完整文件名特判:身份在文件名而非扩展名。
  // - pom.xml:Maven 身份(xml 泛型太宽);
  // - Dockerfile / *.dockerfile:容器构建文件;
  // - docker-compose.*(yml/yaml/override) 与 compose.*.yaml / compose.yaml:编排文件。
  const nameRender
    = lowerName === 'pom.xml' ? EXT_RENDERERS.pom
      : lowerName === 'dockerfile' || ext === 'dockerfile' ? EXT_RENDERERS.dockerfile
        : /^docker-compose([-.]|$)/.test(lowerName) || /^compose\.[^.]*\.ya?ml$/.test(lowerName) || lowerName === 'compose.yaml' || lowerName === 'compose.yml' ? EXT_RENDERERS.dockerfile
          : undefined
  const render = nameRender ?? EXT_RENDERERS[ext]
  if (render) {
    return render({ size, style })
  }
  return <FileTextIcon size={size} style={{ color: 'var(--text-muted)', ...style }} />
}
