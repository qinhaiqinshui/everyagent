/**
 * 全局文本选区追踪:监听 selectionchange 记录最近一次非空选区,
 * 供「打开搜索面板时自动填充选中内容」使用。
 *
 * 读取时优先返回当前仍存活的 DOM 选区;选区已被点击等操作先行清空时
 * (典型场景:点击活动栏图标打开侧边栏,mousedown 先清空了选区),
 * 回退到 maxAgeMs 内记录到的最近一次非空选区。
 * xterm 终端选区是独立实现,不在 window.getSelection() 覆盖范围内。
 */

/** 选区记录有效期:超过该时长的历史选区不再用于填充。 */
const SELECTION_MAX_AGE_MS = 5000

let lastSelectionText = ''
let lastSelectionAt = 0
let listening = false

function ensureListener(): void {
  if (listening || typeof document === 'undefined') return
  listening = true
  document.addEventListener('selectionchange', () => {
    const text = window.getSelection()?.toString() ?? ''
    if (text.trim()) {
      lastSelectionText = text
      lastSelectionAt = Date.now()
    }
  })
}

/**
 * 读取可用于搜索填充的选区文本。
 * 原样返回(含换行与首尾空白);当前 DOM 选区优先,其次回退 maxAgeMs 内
 * 记录到的最近一次非空选区;两者皆无返回空串。
 */
export function readRecentSelection(maxAgeMs: number = SELECTION_MAX_AGE_MS): string {
  ensureListener()
  const live = window.getSelection()?.toString() ?? ''
  if (live.trim()) {
    return live
  }
  if (lastSelectionText.trim() && Date.now() - lastSelectionAt <= maxAgeMs) {
    return lastSelectionText
  }
  return ''
}
