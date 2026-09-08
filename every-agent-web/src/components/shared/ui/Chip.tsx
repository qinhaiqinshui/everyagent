import React from 'react'
import { Tag } from 'antd'
import { cn } from './cn'

export type ChipVariant = 'neutral' | 'skill' | 'command' | 'file'

export type ChipProps = React.HTMLAttributes<HTMLSpanElement> & {
  /** 视觉变体，默认 neutral。 */
  variant?: ChipVariant
  /** 前置图标节点（建议 14px SVG）。 */
  leadingIcon?: React.ReactNode
  /** 移除回调；提供后展示关闭按钮。 */
  onRemove?: () => void
}

const VARIANT_COLOR: Record<ChipVariant, string> = {
  neutral: 'default',
  skill: 'geekblue',
  command: 'blue',
  file: 'cyan',
}

/**
 * 芯片 / 标签药丸（antd Tag 实现）。用于 skill / command / file 等可扫描标记。
 */
export default function Chip({
  variant = 'neutral',
  leadingIcon,
  onRemove,
  className,
  children,
  ...rest
}: ChipProps) {
  return (
    <Tag
      color={VARIANT_COLOR[variant]}
      icon={leadingIcon as React.ReactNode}
      closable={Boolean(onRemove)}
      onClose={(event) => {
        event.preventDefault()
        onRemove?.()
      }}
      className={cn(className)}
      bordered={false}
      {...(rest as Record<string, unknown>)}
    >
      {children}
    </Tag>
  )
}
