import React from 'react'
import { Input } from 'antd'
import { cn } from './cn'

export type TextAreaProps = React.TextareaHTMLAttributes<HTMLTextAreaElement>

/** 基础多行文本输入（antd Input.TextArea 实现）。 */
export default function TextArea({ className, ...rest }: TextAreaProps) {
  return (
    <Input.TextArea
      className={cn(className)}
      {...(rest as Record<string, unknown>)}
    />
  )
}
