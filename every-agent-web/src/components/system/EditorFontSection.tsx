/**
 * 设置页「编辑器字体」段：字体族 + 字号（作用于全站文本文件编辑器）。
 *
 * 字体只提供预设下拉（不含自定义输入）；字号为步进控件。
 *
 * 对标 VSCode 的 editor.fontFamily / editor.fontSize——是编辑器设置，不进颜色主题。
 * 设置经 `editorFont` 存取（worker preferences 事实源 + localStorage 缓存），
 * 应用层按 `--editor-font-family` / `--reading-font-family` / `--editor-font-size` 落地。
 */
import React from 'react'
import { Button, Select } from '@/components/shared/ui'
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

/** 下拉哨兵值：预设「默认（等宽）」在设置里存空串，但空串作 antd Select 的 value 会被
 *  当成未选中（显示 placeholder），故下拉用显式哨兵值，存取时再映射回空串。 */
const SELECT_DEFAULT = '__default__'

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
  // 字体只提供预设（无自定义输入）：把历史遗留的非预设值归一化回默认，避免下拉出现
  // 「值不在选项里」的悬空显示。只跑一次，不覆盖用户后续选择。
  const normalizedRef = React.useRef(false)
  React.useEffect(() => {
    if (normalizedRef.current) return
    normalizedRef.current = true
    if (!EDITOR_FONT_PRESETS.some((p) => p.value === font.fontFamily)) {
      apply({ ...font, fontFamily: '' })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const selectValue = font.fontFamily === '' ? SELECT_DEFAULT : font.fontFamily

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
          value={selectValue}
          options={EDITOR_FONT_PRESETS.map((p) => ({
            value: p.value === '' ? SELECT_DEFAULT : p.value,
            label: p.label,
          }))}
          onChange={(value: string) => {
            // 注意：Select 是 antd 封装，onChange 回调给的是**值**（不是 DOM 事件）。
            apply({ ...font, fontFamily: value === SELECT_DEFAULT ? '' : value })
          }}
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