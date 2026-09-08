import React from 'react'
import { Modal } from 'antd'
import { cn } from './cn'

export type DialogProps = {
  open: boolean
  onClose: () => void
  title?: React.ReactNode
  children?: React.ReactNode
  footer?: React.ReactNode
  showClose?: boolean
  className?: string
  /** 自定义最大宽度（px），默认 480。 */
  width?: number
}

/**
 * 模态对话框（antd Modal 实现）。
 * 点击遮罩或 Esc 关闭（由 antd 内置处理），打开时聚焦面板。
 */
export default function Dialog({
  open,
  onClose,
  title,
  children,
  footer,
  showClose = true,
  className,
  width,
}: DialogProps) {
  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={title}
      footer={footer}
      closable={showClose}
      width={width}
      className={cn(className)}
      destroyOnClose
      maskClosable
      keyboard
    >
      {children}
    </Modal>
  )
}
