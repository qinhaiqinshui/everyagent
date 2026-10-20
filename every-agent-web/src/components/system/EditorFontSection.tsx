/**
 * 设置页「编辑器字体」段：字体族 + 字号（作用于全站文本文件编辑器）。
 *
 * 对标 VSCode 的 editor.fontFamily / editor.fontSize——是编辑器设置，不进颜色主题。
 * 设置经 `editorFont` 存取（worker preferences 事实源 + localStorage 缓存），
 * 应用层按 `--editor-font-family` / `--reading-font-family` / `--editor-font-size` 落地。
 */
import React from 'react'
import { Button, Select, TextInput } from '@/components/shared/ui'
import {
  DEFAULT_EDITOR_FONT,
  EDITOR_FONT_PRESETS,
  loadEditorFont,
  saveEditorFont,
  subscribeEditorFont,
  type EditorFontSettings,
} from '@/settings/editorFont'

const SIZE_STEP = 1
const SIZE_MIN = 10
const SIZE_MAX = 32

function parseSize(fontSize: string): number {
  const n = Number.parseFloat(fontSize)
  return Number.isFinite(n) ? n : Number.parseFloat(DEFAULT_EDITOR_FONT.fontSize)
}

export default function EditorFontSection() {
  const [font, setFont] = React.useState<EditorFontSettings>(loadEditorFont)

  React.useEffect(() => subscribeEditorFont(() => setFont(loadEditorFont())), [])

  const apply = (next: EditorFontSettings) => {
    setFont(next)
    void saveEditorFont(next)
  }

  const size = parseSize(font.fontSize)
  const isPreset = EDITOR_FONT_PRESETS.some((p) => p.value === font.fontFamily)

  const setSize = (delta: number) => {
    const clamped = Math.min(SIZE_MAX, Math.max(SIZE_MIN, size + delta))
    apply({ ...font, fontSize: `${clamped}px` })
  }

  return (
    <>
      <p style={hintStyle}>
        字体与字号作用于全站文本文件编辑器（.txt / .md / 代码等），与颜色主题相互独立。
      </p>
      <div style={fieldStyle}>
        <label style={labelStyle}>字体</label>
        <Select
          size="sm"
          value={isPreset ? font.fontFamily : '__custom__'}
          options={[
            ...EDITOR_FONT_PRESETS.map((p) => ({ value: p.value, label: p.label })),
            { value: '__custom__', label: '自定义…' },
          ]}
          onChange={(event) => {
            const value = event.target.value
            // 「自定义…」只切换下拉显示（值已非预设即显示它），不覆写现有字体串。
            if (value === '__custom__') return
            apply({ ...font, fontFamily: value })
          }}
        />
        <TextInput
          size="sm"
          value={font.fontFamily}
          placeholder="CSS font-family，如 &quot;LXGW WenKai&quot;, serif"
          onChange={(event) => apply({ ...font, fontFamily: event.target.value })}
          spellCheck={false}
        />
      </div>
      <div style={fieldStyle}>
        <label style={labelStyle}>字号</label>
        <div style={sizeRowStyle}>
          <Button type="button" variant="secondary" onClick={() => setSize(-SIZE_STEP)} disabled={size <= SIZE_MIN}>
            −
          </Button>
          <span style={sizeValueStyle}>{size}px</span>
          <Button type="button" variant="secondary" onClick={() => setSize(SIZE_STEP)} disabled={size >= SIZE_MAX}>
            ＋
          </Button>
          <Button type="button" variant="ghost" onClick={() => apply({ ...DEFAULT_EDITOR_FONT, fontFamily: font.fontFamily })}>
            重置字号
          </Button>
        </div>
      </div>
    </>
  )
}

const hintStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  maxWidth: 640,
  lineHeight: 1.6,
}

const fieldStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  maxWidth: 420,
}

const labelStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const sizeRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
}

const sizeValueStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  color: 'var(--text-primary)',
  fontFamily: 'var(--font-mono)',
  minWidth: 44,
  textAlign: 'center',
}