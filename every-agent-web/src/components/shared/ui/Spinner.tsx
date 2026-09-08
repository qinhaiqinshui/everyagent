import React from 'react'
import { Spin } from 'antd'
import { LoadingOutlined } from '@ant-design/icons'
import { cn } from './cn'

export type InlineSpinnerProps = {
  /** 直径（px）。 */
  size?: number
  /** 环粗细（px），默认按尺寸自适应。 */
  thickness?: number
  /** 弧线颜色，默认沿用 antd 主色。 */
  color?: string
  /** 轨道颜色，默认沿用 antd 主色弱化。 */
  trackColor?: string
  className?: string
}

/**
 * 轻量环形转圈加载指示器（antd Spin 实现）。
 */
export default function InlineSpinner({
  size = 14,
  color,
  className,
}: InlineSpinnerProps) {
  const antSize = size <= 14 ? 'small' : size <= 24 ? 'default' : 'large'
  return (
    <Spin
      size={antSize}
      className={cn(className)}
      indicator={<LoadingOutlined spin style={{ fontSize: size, color }} />}
    />
  )
}
