/**
 * Git 变更角标组件。
 * 轮询所有注册工作区的 git status，合计变更文件数。
 * 无变更时不渲染（返回 null），有变更时渲染数字角标。
 */

import React from 'react'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { useHub } from '@/hub/HubProvider'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { gitGateway } from './gitGateway'

export default function GitChangeBadge() {
  const hub = useHub()
  const connected = hub.state === 'open'
  const hasWorker = hub.directory.some((w) => w.online && w.enabled && w.hasApiKey && !w.error && !w.connecting)
  const [count, setCount] = React.useState(0)

  React.useEffect(() => {
    let cancelled = false
    const refresh = async () => {
      const roots = workspaceRegistry.current?.workspaces.map((entry) => entry.root) ?? []
      if (roots.length === 0 || !connected || !hasWorker) {
        if (!cancelled) setCount(0)
        return
      }
      try {
        const counts = await Promise.all(roots.map(async (root) => {
          try {
            const status = await gitGateway.status(root)
            return (['added', 'changed', 'modified', 'removed', 'missing', 'untracked', 'conflicting'] as const)
              .reduce((sum, key) => sum + (status[key]?.length ?? 0), 0)
          } catch {
            return 0
          }
        }))
        if (cancelled) return
        setCount(counts.reduce((sum, c) => sum + c, 0))
      } catch {
        if (!cancelled) setCount(0)
      }
    }
    void refresh()
    const unsub = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_REGISTRY_CHANGED, () => {
      void refresh()
    })
    return () => {
      cancelled = true
      unsub()
    }
  }, [connected, hasWorker, hub.reconnectVersion])

  if (count === 0) return null
  return (
    <span
      style={{
        position: 'absolute',
        top: 2,
        right: 2,
        minWidth: 16,
        height: 16,
        padding: '0 4px',
        borderRadius: 8,
        background: 'var(--accent-blue)',
        color: 'var(--text-on-accent)',
        fontSize: 10,
        fontWeight: 600,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        lineHeight: 1,
        pointerEvents: 'none',
      }}
    >
      {count > 99 ? '99+' : count}
    </span>
  )
}
