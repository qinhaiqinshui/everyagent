/**
 * 设置页「外观」段：全局颜色主题选择 + 自定义主题管理。
 *
 * 主题只定义颜色（字体 / 字号见「编辑器字体」段）。可覆盖的 token 白名单与分组见
 * `@/theme/themeTokens`（THEME_TOKEN_GROUPS）；每项留空 = 继承 `type` 基底。
 *
 * 当前主题持久化经 `saveThemeId`（主题 id + 基底 type）；自定义主题经 `themeStore`。
 * 全部变更经 `useAppearance` / `subscribeThemeStore` 触发的重渲染即时反映到界面。
 */
import React from 'react'
import type { ThemeDefinition } from '@everyagent/plugin-api'
import { Button, Select, TextInput } from '@/components/shared/ui'
import { useAppearance } from '@/hooks/useAppearance'
import { loadThemeId, saveThemeId } from '@/settings/localSettings'
import { loadCustomThemes, saveCustomThemes } from '@/settings/themeStore'
import { resolveThemes } from '@/theme/resolveThemes'
import { THEME_TOKEN_GROUPS, type ThemeTokenName } from '@/theme/themeTokens'

function currentComputed(token: ThemeTokenName): string {
  try {
    return getComputedStyle(document.documentElement).getPropertyValue(token).trim()
  } catch {
    return ''
  }
}

function newCustomThemeId(): string {
  return `custom.${Date.now().toString(36)}`
}

/** 色值输入行：文本输入（支持任意 CSS 颜色表达式）+ 色块预览 + 继承提示。 */
function ColorRow({
  token,
  value,
  onChange,
}: {
  token: ThemeTokenName
  value: string
  onChange: (next: string) => void
}) {
  const inherited = currentComputed(token)
  return (
    <div style={colorRowStyle}>
      <span
        style={{
          ...swatchStyle,
          background: value.trim() || inherited || 'transparent',
        }}
        title={value.trim() || inherited}
      />
      <span style={colorTokenStyle}>{token}</span>
      <TextInput
        size="sm"
        value={value}
        placeholder={inherited ? `继承（${inherited}）` : '继承'}
        onChange={(event) => onChange(event.target.value)}
        spellCheck={false}
      />
    </div>
  )
}

/** 自定义主题编辑器（新建 / 编辑）。 */
function CustomThemeEditor({
  draft,
  isNew,
  onChange,
  onSave,
  onCancel,
  onDelete,
}: {
  draft: ThemeDefinition
  isNew: boolean
  onChange: (next: ThemeDefinition) => void
  onSave: () => void
  onCancel: () => void
  onDelete?: () => void
}) {
  const colors = draft.colors ?? {}
  const setColor = (token: ThemeTokenName, value: string) => {
    const nextColors = { ...colors }
    if (value.trim()) nextColors[token] = value
    else delete nextColors[token]
    onChange({ ...draft, colors: nextColors })
  }
  return (
    <div style={editorStyle}>
      <div style={fieldStyle}>
        <label style={labelStyle}>主题名称</label>
        <TextInput
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="例如：我的护眼主题"
        />
      </div>
      <div style={fieldStyle}>
        <label style={labelStyle}>基底（深 / 浅）</label>
        <Select
          size="sm"
          value={draft.type}
          options={[
            { value: 'dark', label: '深色' },
            { value: 'light', label: '浅色' },
          ]}
          onChange={(value: string) =>
            onChange({ ...draft, type: value === 'light' ? 'light' : 'dark' })
          }
        />
      </div>
      <p style={hintStyle}>
        下方色值留空 = 继承基底（显示当前值）；填任意合法 CSS 颜色（如 <code>#f5ecd8</code> /
        <code>rgba(0,0,0,.1)</code> / <code>var(--accent-blue)</code>）即覆盖该 token。
      </p>
      <div style={groupsWrapStyle}>
        {THEME_TOKEN_GROUPS.map((group) => (
          <details key={group.title} style={groupStyle}>
            <summary style={summaryStyle}>
              {group.title}
              <span style={groupCountStyle}>
                {group.tokens.filter((token) => colors[token]?.trim()).length}/{group.tokens.length}
              </span>
            </summary>
            <div style={colorListStyle}>
              {group.tokens.map((token) => (
                <ColorRow key={token} token={token} value={colors[token] ?? ''} onChange={(v) => setColor(token, v)} />
              ))}
            </div>
          </details>
        ))}
      </div>
      <div style={actionsStyle}>
        <Button type="button" variant="primary" onClick={onSave} disabled={!draft.name.trim()}>
          保存
        </Button>
        <Button type="button" variant="secondary" onClick={onCancel}>
          取消
        </Button>
        {!isNew && onDelete ? (
          <Button type="button" variant="danger" onClick={onDelete}>
            删除
          </Button>
        ) : null}
      </div>
    </div>
  )
}

export default function AppearanceSection() {
  // 订阅外观变更（主题 id / 自定义主题列表 / 插件扩展点），任一变化即重渲染。
  const appearance = useAppearance('dark')
  const themes = React.useMemo(() => resolveThemes(), [appearance])
  const customThemes = React.useMemo(() => loadCustomThemes(), [appearance])
  const [editing, setEditing] = React.useState<ThemeDefinition | null>(null)
  const [editingIsNew, setEditingIsNew] = React.useState(false)

  const currentId = loadThemeId()

  const selectTheme = (theme: ThemeDefinition) => {
    void saveThemeId(theme.id, theme.type)
  }

  const startNew = () => {
    setEditing({ id: newCustomThemeId(), name: '', type: 'light', colors: {} })
    setEditingIsNew(true)
  }

  const startEdit = (theme: ThemeDefinition) => {
    setEditing({ ...theme, colors: { ...(theme.colors ?? {}) } })
    setEditingIsNew(false)
  }

  const persist = (nextCustom: ThemeDefinition[]) => {
    void saveCustomThemes(nextCustom)
  }

  const saveEditing = () => {
    if (!editing) return
    const existing = loadCustomThemes()
    const idx = existing.findIndex((t) => t.id === editing.id)
    const normalized: ThemeDefinition = {
      id: editing.id,
      name: editing.name.trim(),
      type: editing.type,
      ...(editing.colors && Object.keys(editing.colors).length > 0 ? { colors: editing.colors } : {}),
    }
    const next = idx >= 0 ? existing.map((t, i) => (i === idx ? normalized : t)) : [...existing, normalized]
    persist(next)
    setEditing(null)
    // 若正在编辑的是当前选中主题，重应用以立即生效。
    if (loadThemeId() === normalized.id) void saveThemeId(normalized.id, normalized.type)
  }

  const deleteEditing = () => {
    if (!editing) return
    const next = loadCustomThemes().filter((t) => t.id !== editing.id)
    persist(next)
    setEditing(null)
  }

  const isCustomId = (id: string) => id.startsWith('custom.')

  return (
    <>
      <p style={hintStyle}>
        颜色主题作用于整个界面（侧栏 / 标题栏 / 消息 / 编辑器等）。主题只定义颜色；
        字体与字号见下方「编辑器字体」。内置「深色 / 浅色」为基底主题，插件可贡献更多主题。
      </p>

      <div style={themeGridStyle}>
        {themes.map((theme) => {
          const active = theme.id === currentId
          return (
            <button
              key={theme.id}
              type="button"
              onClick={() => selectTheme(theme)}
              style={{ ...themeCardStyle, ...(active ? themeCardActiveStyle : null) }}
              title={theme.id}
            >
              <span style={themeCardNameStyle}>{theme.name}</span>
              <span style={themeCardMetaStyle}>
                {theme.type === 'dark' ? '深色' : '浅色'}
                {isCustomId(theme.id) ? ' · 自定义' : ''}
              </span>
            </button>
          )
        })}
      </div>

      <div style={actionsStyle}>
        <Button type="button" variant="secondary" onClick={startNew}>
          新建自定义主题
        </Button>
      </div>

      {customThemes.length > 0 ? (
        <div style={customListStyle}>
          <span style={labelStyle}>自定义主题</span>
          {customThemes.map((theme) => (
            <div key={theme.id} style={customRowStyle}>
              <span style={customNameStyle}>{theme.name}</span>
              <Button type="button" variant="ghost" onClick={() => startEdit(theme)}>
                编辑
              </Button>
            </div>
          ))}
        </div>
      ) : null}

      {editing ? (
        <CustomThemeEditor
          draft={editing}
          isNew={editingIsNew}
          onChange={setEditing}
          onSave={saveEditing}
          onCancel={() => setEditing(null)}
          onDelete={editingIsNew ? undefined : deleteEditing}
        />
      ) : null}
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

const themeGridStyle: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 8,
}

const themeCardStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  alignItems: 'flex-start',
  minWidth: 132,
  padding: '8px 12px',
  borderRadius: 'var(--radius-md)',
  // 拆成长写：active 态只改 borderColor，若基类用 `border` 简写会与长写混用
  // （React 在重渲染时移除 borderColor 而 border 仍在 → 告警并可能产生样式 bug）。
  borderWidth: 1,
  borderStyle: 'solid',
  borderColor: 'var(--border)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-primary)',
  cursor: 'pointer',
}

const themeCardActiveStyle: React.CSSProperties = {
  borderColor: 'var(--accent-blue)',
  boxShadow: 'var(--focus-ring)',
}

const themeCardNameStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 600,
}

const themeCardMetaStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const customListStyle: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 8,
}

const customRowStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '2px 4px 2px 10px',
  border: '1px solid var(--border-light)',
  borderRadius: 999,
  background: 'var(--bg-tertiary)',
}

const customNameStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-primary)',
}

const editorStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  padding: 14,
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-lg)',
  background: 'var(--bg-secondary)',
  maxWidth: 720,
}

const fieldStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  maxWidth: 320,
}

const labelStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const actionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap',
}

const groupsWrapStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
}

const groupStyle: React.CSSProperties = {
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-md)',
  padding: '6px 10px',
  background: 'var(--bg-primary)',
}

const summaryStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  cursor: 'pointer',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-secondary)',
  fontWeight: 600,
}

const groupCountStyle: React.CSSProperties = {
  fontSize: 'var(--text-2xs)',
  color: 'var(--text-muted)',
  fontWeight: 400,
}

const colorListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  paddingTop: 8,
}

const colorRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '16px 190px minmax(120px, 1fr)',
  alignItems: 'center',
  gap: 8,
}

const swatchStyle: React.CSSProperties = {
  width: 14,
  height: 14,
  borderRadius: 3,
  border: '1px solid var(--border-strong)',
}

const colorTokenStyle: React.CSSProperties = {
  fontSize: 'var(--text-2xs)',
  color: 'var(--text-muted)',
  fontFamily: 'var(--font-mono)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}