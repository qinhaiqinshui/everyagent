import React from 'react'
import { Checkbox as AntCheckbox } from 'antd'
import { cn } from './cn'

export type CheckboxProps = Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> & {
  /** 标签内容（可为文本或节点）。 */
  label?: React.ReactNode
  /** 半选态。 */
  indeterminate?: boolean
}

/**
 * 基础复选框（antd Checkbox 实现）。
 * antd 的 onChange 事件含 target.checked，与原生行为一致。
 */
export default function Checkbox({ label, indeterminate = false, className, disabled, ...rest }: CheckboxProps) {
  return (
    <AntCheckbox
      indeterminate={indeterminate}
      disabled={disabled}
      className={cn(className)}
      {...(rest as Record<string, unknown>)}
    >
      {label}
    </AntCheckbox>
  )
}
