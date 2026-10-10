/**
 * 双击 Shift 呼出的全局搜索弹窗:内嵌与搜索侧边栏同一 SearchPanel 组件,
 * 功能完全一致(独立实例:内部状态与「上次打开时间」按实例隔离)。
 *
 * - 关闭路径:Esc / 点击遮罩 / 命中跳转打开文件或任务 / 再次双击 Shift(toggle 由 Layout 控制);
 * - 首次打开后常驻挂载(display:none 隐藏),搜索词等内部状态跨开关保留;
 * - 每次打开递增 openSignal,触发面板 on-open 流程(过期清空/自动识别/选区填充/聚焦)。
 */
import React from 'react'
import SearchPanel from './SearchPanel'

interface SearchModalProps {
  open: boolean
  onClose: () => void
}

export default function SearchModal({ open, onClose }: SearchModalProps) {
  const [hasOpened, setHasOpened] = React.useState(false)
  const [openSignal, setOpenSignal] = React.useState(0)

  React.useEffect(() => {
    if (!open) return
    setHasOpened(true)
    setOpenSignal((current) => current + 1)
  }, [open])

  /** Esc 关闭(捕获阶段,输入框内同样生效)。 */
  React.useEffect(() => {
    if (!open) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown, true)
    return () => {
      window.removeEventListener('keydown', handleKeyDown, true)
    }
  }, [open, onClose])

  if (!hasOpened) return null

  return (
    <div
      role="presentation"
      style={{ ...overlayStyle, display: open ? 'flex' : 'none' }}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose()
        }
      }}
    >
      <div style={dialogStyle}>
        <SearchPanel openSignal={openSignal} onRequestClose={onClose} variant="modal" />
      </div>
    </div>
  )
}

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  zIndex: 1000,
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'center',
  paddingTop: '8vh',
  background: 'var(--overlay)',
}

const dialogStyle: React.CSSProperties = {
  width: 'min(720px, 94vw)',
  height: 'min(70dvh, 640px)',
  display: 'flex',
  flexDirection: 'column',
  minWidth: 0,
  minHeight: 0,
  borderRadius: 'var(--radius-xl)',
  border: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  boxShadow: '0 12px 40px rgba(0, 0, 0, 0.28)',
  overflow: 'hidden',
}
