/**
 * 重连模态框:任一 hub 连接(目录/worker)持续重连超过宽限期时弹出,
 * 阻塞用户操作,避免与陈旧数据交互。
 *
 * 传输层(HubClient)在瞬态断连时挂起在途 RPC(不拒绝→不向业务层抛错误),
 * 自动重连 + 重连成功后重放 RPC,业务层全程无感知;切页/锁屏恢复后的
 * 瞬态断连通常秒级自愈(心跳判死 + 零退避首试),不应弹窗打扰——
 * 故这里加宽限期:重连在 GRACE_MS 内完成则全程无感,超过(确认断连且
 * 短时间恢复不了)才弹窗。
 */
import React from 'react'
import { Modal, Spin } from 'antd'
import { useHub } from '@/hub/HubProvider'

/** 宽限期:重连在该时长内自愈则不弹窗(瞬态断连无感),超过才弹窗阻塞操作。 */
const GRACE_MS = 2_000

export default function ReconnectionModal() {
  const { reconnecting } = useHub()
  const [visible, setVisible] = React.useState(false)

  React.useEffect(() => {
    if (!reconnecting) {
      setVisible(false)
      return
    }
    const timer = setTimeout(() => setVisible(true), GRACE_MS)
    return () => clearTimeout(timer)
  }, [reconnecting])

  return (
    <Modal
      open={visible}
      closable={false}
      maskClosable={false}
      keyboard={false}
      footer={null}
      centered
      width={320}
      styles={{
        body: { textAlign: 'center', padding: '32px 24px' },
        mask: { backdropFilter: 'blur(2px)' },
      }}
    >
      <Spin size="large" />
      <p style={{ marginTop: 16, marginBottom: 0, fontSize: 14, color: 'var(--text-secondary)' }}>
        正在重新连接…
      </p>
    </Modal>
  )
}
