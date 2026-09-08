import React from 'react'
import { Input } from 'antd'
import { cn } from './cn'

export type TextInputSize = 'sm' | 'md'

export type TextInputProps = Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size'> & {
  /** 尺寸，默认 md。 */
  size?: TextInputSize
}

/** 基础文本输入框（antd Input 实现）。 */
export default function TextInput({ size = 'md', className, ...rest }: TextInputProps) {
  return (
    <Input
      size={size === 'sm' ? 'small' : 'middle'}
      className={cn(className)}
      {...(rest as Record<string, unknown>)}
    />
  )
}
