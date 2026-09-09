import React from 'react'
import { Modal, App } from 'antd'
import { workspaceRegistry, type WorkspaceRegistry } from '@/hub/workspaceRegistry'
import WorkspaceBrowserModal from '@/components/shared/WorkspaceBrowserModal'
import { Button } from '@/components/shared/ui'
import { antdConfirm } from '@/utils/appAntdBridge'

interface MissingItem {
  workerId: string
  root: string
}

function itemKey(item: MissingItem): string {
  return `${item.workerId}\u0000${item.root}`
}

/**
 * 缺失工作区修复弹窗(worker 启动自检,架构 §5.9)。
 *
 * worker 重启时若 workspaces.json 里某个工作区目录已不存在(被用户移动/删除),
 * 注册表条目会被标记 missing;本组件订阅注册表,发现 missing 条目即弹窗要求用户二选一:
 * 1) 删除工作区(连带任务数据);2) 纠正路径(逐层浏览选择移动后的新目录)。
 *
 * 落定走 worker 端 workspaces.resolveMissing RPC;成功即注册表广播回最新快照,弹窗自动关闭。
 * 「稍后处理」仅在当前页面会话内隐藏,未处理条目在页面刷新后再次弹出。
 */
export default function MissingWorkspaceRepairHost() {
  const { message } = App.useApp()
  const [registry, setRegistry] = React.useState<WorkspaceRegistry | null>(workspaceRegistry.current)
  const [dismissed, setDismissed] = React.useState<Set<string>>(() => new Set())
  const [browserFor, setBrowserFor] = React.useState<MissingItem | null>(null)
  const [busy, setBusy] = React.useState(false)

  React.useEffect(() => workspaceRegistry.subscribe((next) => setRegistry(next)), [])

  const missingItems = React.useMemo<MissingItem[]>(() => {
    if (!registry) return []
    const seen = new Set<string>()
    const out: MissingItem[] = []
    for (const entry of registry.workspaces) {
      if (!entry.missing) continue
      const key = itemKey({ workerId: entry.workerId, root: entry.root })
      if (seen.has(key)) continue
      seen.add(key)
      out.push({ workerId: entry.workerId, root: entry.root })
    }
    return out
  }, [registry])

  const current = missingItems.find((item) => !dismissed.has(itemKey(item))) ?? null

  const doResolve = React.useCallback(async (item: MissingItem, action: 'delete' | 'redirect', newRoot?: string) => {
    setBusy(true)
    try {
      await workspaceRegistry.resolveMissing(item.workerId, item.root, action, newRoot)
      message.success(action === 'delete' ? '工作区已删除' : '工作区路径已纠正')
      setDismissed((prev) => {
        const next = new Set(prev)
        next.add(itemKey(item))
        return next
      })
    } catch (error) {
      message.error(error instanceof Error ? error.message : '操作失败')
    } finally {
      setBusy(false)
    }
  }, [message])

  const handleDelete = React.useCallback(() => {
    if (!current) return
    const target = current
    antdConfirm({
      title: '删除工作区',
      content: `删除该工作区注册并连带删除其下全部任务数据(工作区目录文件本身不受影响):${target.root}`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => {
        void doResolve(target, 'delete')
      },
    })
  }, [current, doResolve])

  const handleRedirectConfirm = React.useCallback((newRoot: string) => {
    if (!current) return
    const target = current
    setBrowserFor(null)
    void doResolve(target, 'redirect', newRoot)
  }, [current, doResolve])

  const handleLater = React.useCallback(() => {
    if (!current) return
    setDismissed((prev) => {
      const next = new Set(prev)
      next.add(itemKey(current))
      return next
    })
  }, [current])

  return (
    <>
      <Modal
        open={Boolean(current && !browserFor)}
        title="工作区路径已失效"
        onCancel={handleLater}
        footer={[
          <Button key="later" variant="ghost" size="sm" disabled={busy} onClick={handleLater}>
            稍后处理
          </Button>,
          <Button key="delete" variant="danger" size="sm" loading={busy} onClick={handleDelete}>
            删除工作区
          </Button>,
          <Button key="redirect" variant="primary" size="sm" disabled={busy} onClick={() => setBrowserFor(current)}>
            纠正路径
          </Button>,
        ]}
        width={520}
        maskClosable={false}
        keyboard={false}
        destroyOnClose
      >
        <p style={{ margin: '0 0 8px', fontSize: 13, color: 'var(--text-secondary)' }}>
          worker 启动时发现下面这个工作区目录已不存在(可能被移动或删除):
        </p>
        <code
          style={{
            display: 'block',
            padding: '8px 10px',
            borderRadius: 6,
            background: 'var(--bg-tertiary, rgba(0,0,0,0.04))',
            fontSize: 12,
            wordBreak: 'break-all',
            color: 'var(--text-primary)',
          }}
        >
          {current?.root}
        </code>
        <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--text-secondary)' }}>
          选择「删除工作区」会连带删除该工作区下的任务数据;选择「纠正路径」请指定目录被移动后的新位置。
        </p>
      </Modal>
      <WorkspaceBrowserModal
        open={Boolean(current && browserFor)}
        workerId={browserFor?.workerId ?? current?.workerId}
        title="选择移动后的新目录"
        confirmLabel="使用此目录"
        onCancel={() => setBrowserFor(null)}
        onConfirm={handleRedirectConfirm}
      />
    </>
  )
}
