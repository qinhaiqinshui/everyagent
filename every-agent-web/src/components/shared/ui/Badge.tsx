import React from 'react'
import { Tag } from 'antd'
import { cn } from './cn'

export type BadgeVariant = 'neutral' | 'info' | 'success' | 'warn' | 'danger'

export type BadgeProps = React.HTMLAttributes<HTMLSpanElement> & {
  /** 视觉变体，默认 neutral。 */
  variant?: BadgeVariant
}

const VARIANT_COLOR: Record<BadgeVariant, string> = {
  neutral: 'default',
  info: 'blue',
  success: 'green',
  warn: 'orange',
  danger: 'red',
}

/**
 * 状态徽标（小药丸，antd Tag 实现）。用于标记数量、状态、分类。
 */
export default function Badge({ variant = 'neutral', className, ...rest }: BadgeProps) {
  return (
    <Tag color={VARIANT_COLOR[variant]} className={cn(className)} bordered={false} {...(rest as Record<string, unknown>)} />
  )
}
