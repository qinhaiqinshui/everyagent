/**
 * git 插件内部文件类型图标集（自宿主 components/shared/FileTypeGlyphs 复制，
 * 插件不引用宿主模块）。按扩展名渲染语言徽章，未知退化为中性文件轮廓。
 */
import React from 'react'
import { FileTextIcon } from './FileTextIcon'

interface AppGlyphProps {
  size?: number
  style?: React.CSSProperties
}

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

function ImageChip({ size = 16, style }: AppGlyphProps) {
  const color = 'var(--accent-purple)'
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" style={{ display: 'block', flexShrink: 0, ...style }} aria-hidden="true">
      <rect x="2.2" y="3.2" width="11.6" height="9.6" rx="1.8" fill="none" stroke={color} strokeWidth="1.4" />
      <circle cx="5.7" cy="6.3" r="1.1" fill={color} />
      <path d="M3.4 11.6 6.8 8.4l2 1.9 2-2.1 2 2.1" stroke={color} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" fill="none" />
    </svg>
  )
}

function DatabaseChip({ size = 16, style }: AppGlyphProps) {
  const color = 'var(--accent-purple)'
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" style={{ display: 'block', flexShrink: 0, ...style }} aria-hidden="true">
      <path d="M3.2 4.4v7.2c0 1.2 2.2 2.2 4.8 2.2s4.8-1 4.8-2.2V4.4" stroke={color} strokeWidth="1.3" fill="none" />
      <ellipse cx="8" cy="4.4" rx="4.8" ry="2" stroke={color} strokeWidth="1.3" fill="none" />
      <path d="M3.2 8c.6 1 2.4 1.7 4.8 1.7S12.2 9 12.8 8" stroke={color} strokeWidth="1" fill="none" opacity="0.8" />
    </svg>
  )
}

function DockerChip({ size = 16, style }: AppGlyphProps) {
  const color = 'var(--accent-cyan)'
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" style={{ display: 'block', flexShrink: 0, ...style }} aria-hidden="true">
      <path d="M3.7 6.3h2.6v2.4H3.7z M6.7 6.3h2.6v2.4H6.7z" fill={color} opacity="0.85" />
      <path d="M2.2 9.1h2.6v2.4H2.2z M5.2 9.1h2.6v2.4H5.2z M8.2 9.1h2.6v2.4H8.2z" fill={color} />
      <path d="M1.6 12.2h12.1c.9 0 1.6-.5 1.9-1.3l.3-.8c.1-.3-.2-.6-.5-.5l-1.5.5" stroke={color} strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" fill="none" />
      <path d="M1.6 12.2c-.5 0-.8-.4-.8-.8v-.3h.8v1.1Z" fill={color} />
    </svg>
  )
}

function PomChip({ size = 16, style }: AppGlyphProps) {
  const color = 'var(--accent-amber)'
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" style={{ display: 'block', flexShrink: 0, ...style }} aria-hidden="true">
      <text x="8" y="11.6" textAnchor="middle" fontSize="10.5" fontWeight={900} fill={color}>m</text>
      <path d="M11.6 4.2v1.4" stroke={color} strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  )
}

function JavaChip({ size = 16, style }: AppGlyphProps) {
  const cupColor = 'var(--accent-red)'
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" style={{ display: 'block', flexShrink: 0, ...style }} aria-hidden="true">
      <path d="M4.2 7.8h6v2.4a2.6 2.6 0 0 1-2.6 2.6H6.8a2.6 2.6 0 0 1-2.6-2.6V7.8Z" fill={cupColor} />
      <path d="M10.4 8.4h0.7a1.5 1.5 0 0 1 0 3h-0.7" stroke={cupColor} strokeWidth="1.2" strokeLinecap="round" fill="none" />
      <path d="M6.3 4.4v1.5M9.1 4.4v1.5" stroke={cupColor} strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  )
}

function MarkdownChip({ size = 16, style }: AppGlyphProps) {
  const color = 'var(--accent-cyan)'
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" style={{ display: 'block', flexShrink: 0, ...style }} aria-hidden="true">
      <text x="6.4" y="11.8" textAnchor="middle" fontSize="10" fontWeight={900} fill={color}>M</text>
      <path d="M10.9 7.9v3.4m0 0-1.2-1.2m1.2 1.2 1.2-1.2" stroke={color} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" fill="none" />
    </svg>
  )
}

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
  dockerfile: (p) => <DockerChip {...p} />,
  sh: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  bash: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  zsh: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  fish: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  ksh: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  csh: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  bashrc: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  zshrc: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  profile: (p) => <LangChip {...p} color="var(--accent-amber)" sigil="$" fontSize={12} />,
  ps1: (p) => <LangChip {...p} color="var(--accent-blue)" sigil="PS" fontSize={9} />,
  psm1: (p) => <LangChip {...p} color="var(--accent-blue)" sigil="PS" fontSize={9} />,
  psd1: (p) => <LangChip {...p} color="var(--accent-blue)" sigil="PS" fontSize={9} />,
  sql: (p) => <DatabaseChip {...p} />,
}

export function FileTypeIcon({ fileName, size = 16, style, fallback }: {
  fileName: string
  size?: number
  style?: React.CSSProperties
  fallback?: React.ReactNode
}) {
  const dotIndex = fileName.lastIndexOf('.')
  const ext = dotIndex >= 0 ? fileName.slice(dotIndex + 1).toLowerCase() : ''
  const lowerName = fileName.toLowerCase()
  const nameRender
    = lowerName === 'pom.xml' ? EXT_RENDERERS.pom
      : lowerName === 'dockerfile' || ext === 'dockerfile' ? EXT_RENDERERS.dockerfile
        : /^docker-compose([-.]|$)/.test(lowerName) || /^compose\.[^.]*\.ya?ml$/.test(lowerName) || lowerName === 'compose.yaml' || lowerName === 'compose.yml' ? EXT_RENDERERS.dockerfile
          : undefined
  const render = nameRender ?? EXT_RENDERERS[ext]
  if (render) {
    return render({ size, style })
  }
  if (fallback != null) {
    return <>{fallback}</>
  }
  return <FileTextIcon size={size} style={{ color: 'var(--text-muted)', ...style }} />
}
