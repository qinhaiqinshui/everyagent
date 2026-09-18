/**
 * 重连模态框:任一 hub 连接(目录/worker)处于瞬态重连时弹出,
 * 阻塞用户操作,避免与陈旧数据交互。
 *
 * 传输层(HubClient)在瞬态断连时挂起在途 RPC(不拒绝→不向业务层抛错误),
 * 自动重连 + 重连成功后重放 RPC,业务层全程无感知;
 * 此模态框是 UI 层的唯一可见反馈,重连完成即消失。
 */
import React from 'react'
import { Modal, Spin } from 'antd'
import { useHub } from '@/hub/HubProvider'

export default function ReconnectionModal() {
  const { reconnecting } = useHub()

  return (
    <Modal
      open={reconnecting}
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
