import React from 'react'
import { Badge, Menu, theme } from 'antd'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { getPendingRuntimeUserInteractions } from '@/hub/askStore'
import FloatingActionContainer from '@/components/shared/FloatingActionContainer'

interface PendingInteractionSummary {
  id: string
  prompt: string
}

/**
 * 常驻「待回答」入口。
 *
 * ask_user 点「稍后再答」只会收起弹层、不会取消等待中的请求，
 * 而唯一的回进入口是 8 秒后自动消失的轻提示。本组件读取运行时里所有
 * 尚未回答的交互请求，在窗口右侧中部常驻一个可点开的入口，让用户随时能重新作答。
 *
 * 锚定在窗口右侧中部、贴着右边框：收起时是一个右侧悬浮胶囊；点开时菜单向左
 * 展开（横向布局），胶囊本身保持在原位置不跳动。不占右下角 composer 的操作区。
 */
export default function PendingUserInteractionIndicator() {
  const { token } = theme.useToken()
  const [pending, setPending] = React.useState<PendingInteractionSummary[]>([])
  const [menuOpen, setMenuOpen] = React.useState(false)
  const rootRef = React.useRef<HTMLDivElement | null>(null)

  const refresh = React.useCallback(() => {
    const summaries = getPendingRuntimeUserInteractions().map((request) => ({
      id: request.id,
      // 多问题交互可能未提供总标题，回退到第一个问题的文本
      prompt: request.prompt || request.questions?.[0]?.prompt || '待回答问题',
    }))
    setPending(summaries)
    if (summaries.length === 0) {
      setMenuOpen(false)
    }
  }, [])

  React.useEffect(() => {
    refresh()
    const unsubscribers = [
      domainEventBus.subscribe(DOMAIN_EVENTS.USER_INTERACTION_REQUESTED, refresh),
      domainEventBus.subscribe(DOMAIN_EVENTS.USER_INTERACTION_RESOLVED, refresh),
      domainEventBus.subscribe(DOMAIN_EVENTS.USER_INTERACTION_CLEARED, refresh),
    ]
    return () => {
      unsubscribers.forEach((unsubscribe) => unsubscribe())
    }
  }, [refresh])

  React.useEffect(() => {
    if (!menuOpen) return
    const handlePointerDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setMenuOpen(false)
      }
    }
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', handlePointerDown)
    document.addEventListener('keydown', handleEscape)
    return () => {
      document.removeEventListener('mousedown', handlePointerDown)
      document.removeEventListener('keydown', handleEscape)
    }
  }, [menuOpen])

  const openInteraction = React.useCallback((interactionId: string) => {
    domainEventBus.emit(DOMAIN_EVENTS.WORKSPACE_OPEN_USER_INTERACTION_REQUESTED, {
      interactionId,
    })
    setMenuOpen(false)
  }, [])

  if (pending.length === 0) {
    return null
  }

  return (
    <div ref={rootRef}>
      <FloatingActionContainer
        defaultRight={8}
        defaultTop="50%"
        zIndex={130}
        rootStyle={transparentContainerRootStyle}
        contentStyle={containerContentStyle}
      >
        {menuOpen ? (
          <div style={menuStyle}>
            <div style={menuHeaderStyle}>
              待回答 · {pending.length}
            </div>
            <Menu
              mode="vertical"
              selectable={false}
              style={menuListStyle}
              onClick={({ key }) => openInteraction(key)}
              items={pending.map((item) => ({
                key: item.id,
                title: item.prompt,
                icon: <Badge dot color={token.colorPrimary} />,
                label: <span style={menuItemTextStyle}>{item.prompt}</span>,
              }))}
            />
          </div>
        ) : null}

        <button
          type="button"
          onClick={() => setMenuOpen((open) => !open)}
          style={pillStyle}
          aria-haspopup="menu"
          aria-expanded={menuOpen}
        >
          <Badge dot color={token.colorPrimary} />
          <span style={pillTextStyle}>待回答</span>
          <Badge count={pending.length} size="small" color={token.colorPrimary} />
        </button>
      </FloatingActionContainer>
    </div>
  )
}

/** 中和 FloatingActionContainer 自带的半透明面板背景，仅保留其悬浮定位能力，胶囊外观由自身样式决定。
 * 关键点：`.floating-action-container` 全局被钉死在 ~52px 宽，本组件展开菜单时需要更宽的空间，
 * 必须把根容器宽度放开，否则菜单会被 flex 压成一条竖线。 */
const transparentContainerRootStyle: React.CSSProperties = {
  width: 'auto',
  minWidth: 'auto',
  maxWidth: 'none',
  background: 'transparent',
  boxShadow: 'none',
  padding: 0,
  borderRadius: 0,
  backdropFilter: 'none',
  border: 'none',
  pointerEvents: 'auto',
}

/** 横向排列：菜单在左、胶囊在右，垂直居中对齐，胶囊贴右缘保持不动。 */
const containerContentStyle: React.CSSProperties = {
  flexDirection: 'row',
  alignItems: 'center',
  gap: 8,
}

const pillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 8,
  height: 'auto',
  padding: '8px 14px',
  borderRadius: 'var(--radius-xl)',
  border: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-primary)',
  boxShadow: 'var(--shadow-md)',
  fontSize: 'var(--text-sm)',
  fontWeight: 600,
  cursor: 'pointer',
  overflow: 'visible',
  minWidth: 'max-content',
  boxSizing: 'border-box',
  fontFamily: 'inherit',
  lineHeight: 1,
}

const pillTextStyle: React.CSSProperties = {
  whiteSpace: 'nowrap',
}

const menuStyle: React.CSSProperties = {
  width: 'min(320px, calc(100vw - 140px))',
  maxHeight: 'min(50vh, 360px)',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  borderRadius: 'var(--radius-lg)',
  border: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  boxShadow: 'var(--shadow-lg)',
}

const menuHeaderStyle: React.CSSProperties = {
  padding: '10px 14px',
  borderBottom: '1px solid var(--border-light)',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--text-secondary)',
}

const menuListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  overflowY: 'auto',
  background: 'transparent',
  borderInlineEnd: 'none',
}

const menuItemTextStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  lineHeight: 1.5,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}
