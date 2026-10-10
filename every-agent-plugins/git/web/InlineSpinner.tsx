/**
 * git 插件内部轻量加载指示器（自宿主 components/shared/ui/Spinner 复制，
 * 插件不引用宿主模块；antd Spin 实现）。
 */
import React from 'react'
import { Spin } from 'antd'
import { LoadingOutlined } from '@ant-design/icons'

export type InlineSpinnerProps = {
  size?: number
  thickness?: number
  color?: string
  trackColor?: string
  className?: string
}

export default function InlineSpinner({ size = 14, color, className }: InlineSpinnerProps) {
  const antSize = size <= 14 ? 'small' : size <= 24 ? 'default' : 'large'
  return (
    <Spin
      size={antSize}
      className={className}
      indicator={<LoadingOutlined spin style={{ fontSize: size, color }} />}
    />
  )
}
