import React from 'react'
import ConfirmDialog from '@/components/shared/ConfirmDialog'
import { setRawAccessConfirmHandler, type RawAccessRequest } from '@/platform/fs/rawAccess'

/**
 * Raw 系列越界确认框宿主(架构 §5.5 fs.*Raw 行 / §7.8 kind=authorization 同语义)。
 *
 * 文件标签页打开/保存工作区外文件、worker 返回 SANDBOX_DENIED 时,由平台层
 * (workspaceGateway → rawAccess)回调本宿主弹确认框;用户点「允许」→ 平台层调
 * fs.grantRawRoot 注册**会话级**放行目录(worker 内存态,重启即清)→ 自动重试
 * 原操作一次。拒绝则维持原错误(文件页展示越界提示)。
 *
 * 全局挂载一次(Layout);同一时刻仅一张卡,后到的确认请求入队等待前一张收口
 * (并发打开多个越界文件不互相吞应答)。
 */
export default function RawAccessConfirmHost() {
  const [current, setCurrent] = React.useState<RawAccessRequest | null>(null)
  const queueRef = React.useRef<Array<{ req: RawAccessRequest; resolve: (allowed: boolean) => void }>>([])

  React.useEffect(() => {
    setRawAccessConfirmHandler((req) => new Promise<boolean>((resolve) => {
      const queue = queueRef.current
      queue.push({ req, resolve })
      if (queue.length === 1) {
        setCurrent(req) // 队首展示;非队首等前一张收口后依次弹出
      }
    }))
    return () => {
      // 卸载兜底:未收口的确认全部按拒绝处理
      setRawAccessConfirmHandler(null)
      const pending = queueRef.current
      queueRef.current = []
      pending.forEach((item) => item.resolve(false))
      setCurrent(null)
    }
  }, [])

  const settle = React.useCallback((allowed: boolean) => {
    const queue = queueRef.current
    const head = queue.shift()
    head?.resolve(allowed)
    setCurrent(queue.length > 0 ? queue[0].req : null)
  }, [])

  const opText = current?.op === 'write' ? '保存' : '打开'

  return (
    <ConfirmDialog
      open={current !== null}
      title={`允许${opText}工作区外的文件?`}
      message={`该文件位于工作区与已授权目录之外:\n${current?.path ?? ''}`}
      subtitle="允许后,本次会话内可访问该文件所在目录(仅本机生效,worker 重启后需重新确认)。"
      confirmLabel="允许"
      cancelLabel="取消"
      onConfirm={() => settle(true)}
      onCancel={() => settle(false)}
    />
  )
}
