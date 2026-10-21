/**
 * 编辑器字体 / 字号设置（独立于颜色主题，对标 VSCode `editor.fontFamily` / `editor.fontSize`）。
 *
 * 作用于全站文本文件编辑器（CodeMirror / Markdown 预览），经 `ui-tokens.css` 的
 * `--editor-font-family` / `--editor-font-size` 落地，宿主在 documentElement 上行内覆盖。
 *
 * 持久化口径与主题一致：worker 侧 `preferences.json` 为事实源（pref key
 * `editor.fontFamily` / `editor.fontSize`），localStorage 作首帧缓存。
 */
import { hubSession } from '@/hub/session'

const PREF_KEY_FAMILY = 'editor.fontFamily'
const PREF_KEY_SIZE = 'editor.fontSize'
const LS_KEY = 'ea.web.editorFont'

/** 内置字体栈选项（设置页「编辑器字体」下拉；**仅预设、无自定义输入**）。值为 CSS
 *  font-family 串；空串 = 用 `ui-tokens.css` 默认（等宽）。 */
export const EDITOR_FONT_PRESETS: ReadonlyArray<{ label: string; value: string }> = [
  { label: '默认（等宽）', value: '' },
  { label: 'Cascadia Code', value: '"Cascadia Code", "Fira Code", Consolas, monospace' },
  { label: 'Consolas', value: 'Consolas, "Courier New", monospace' },
  { label: '思源宋体 / 衬线（阅读）', value: '"Source Han Serif SC", "Noto Serif SC", Georgia, "Songti SC", serif' },
  { label: '霞鹜文楷（阅读）', value: '"LXGW WenKai", "KaiTi", "STKaiti", serif' },
  { label: '系统默认', value: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif" },
]

export interface EditorFontSettings {
  /** CSS font-family 串；空串 = 用 `ui-tokens.css` 默认（等宽）。 */
  fontFamily: string
  /** 字号，含单位（如 `14px`）；空串 = 默认。 */
  fontSize: string
}

export const DEFAULT_EDITOR_FONT: EditorFontSettings = { fontFamily: '', fontSize: '14px' }

const listeners = new Set<() => void>()

export function subscribeEditorFont(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function notify(): void {
  for (const listener of [...listeners]) listener()
}

export function loadEditorFont(): EditorFontSettings {
  try {
    const raw = localStorage.getItem(LS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<EditorFontSettings>
      return {
        fontFamily: typeof parsed.fontFamily === 'string' ? parsed.fontFamily : DEFAULT_EDITOR_FONT.fontFamily,
        fontSize: typeof parsed.fontSize === 'string' && parsed.fontSize.trim() ? parsed.fontSize : DEFAULT_EDITOR_FONT.fontSize,
      }
    }
  } catch {
    // 忽略
  }
  return { ...DEFAULT_EDITOR_FONT }
}

function writeCache(settings: EditorFontSettings): void {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(settings))
  } catch {
    // 忽略
  }
  notify()
}

function firstConnectedWorkerId(): string {
  for (const [workerId, client] of hubSession.workerClients) {
    if (client.state === 'open') return workerId
  }
  return ''
}

/** 保存编辑器字体设置：写 worker(事实源) + 更新缓存 + 通知。 */
export async function saveEditorFont(settings: EditorFontSettings): Promise<void> {
  const next: EditorFontSettings = {
    fontFamily: settings.fontFamily ?? '',
    fontSize: settings.fontSize?.trim() ? settings.fontSize : DEFAULT_EDITOR_FONT.fontSize,
  }
  writeCache(next)
  const workerId = firstConnectedWorkerId()
  if (workerId) {
    hubSession.rpcTo(workerId, 'pref.set', { key: PREF_KEY_FAMILY, value: next.fontFamily }).catch(() => {})
    hubSession.rpcTo(workerId, 'pref.set', { key: PREF_KEY_SIZE, value: next.fontSize }).catch(() => {})
  }
}

/** 从 worker 偏校正编辑器字体设置；返回是否发生了变化。 */
export function syncEditorFontFromWorker(prefs: Record<string, unknown>): boolean {
  const family = typeof prefs[PREF_KEY_FAMILY] === 'string' ? (prefs[PREF_KEY_FAMILY] as string) : undefined
  const size = typeof prefs[PREF_KEY_SIZE] === 'string' ? (prefs[PREF_KEY_SIZE] as string) : undefined
  if (family === undefined && size === undefined) return false
  const current = loadEditorFont()
  const next: EditorFontSettings = {
    fontFamily: family ?? current.fontFamily,
    fontSize: size && size.trim() ? size : current.fontSize,
  }
  if (next.fontFamily === current.fontFamily && next.fontSize === current.fontSize) return false
  writeCache(next)
  return true
}