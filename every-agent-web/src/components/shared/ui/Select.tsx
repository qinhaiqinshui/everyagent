import React from 'react'
import { Select as AntSelect } from 'antd'
import { cn } from './cn'

export type SelectOption = {
  value: string
  label: React.ReactNode
  disabled?: boolean
}

export type SelectProps = Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'size'> & {
  /** 便捷选项；也可直接传入 <option> 子节点。 */
  options?: SelectOption[]
  /** 控件尺寸，默认使用标准表单密度。 */
  size?: 'sm' | 'md'
}

/** 基础下拉选择（antd Select 实现）。 */
export default function Select({ options, size = 'md', className, children, ...rest }: SelectProps) {
  return (
    <AntSelect
      size={size === 'sm' ? 'small' : 'middle'}
      className={cn(className)}
      options={options?.map((opt) => ({ value: opt.value, label: opt.label, disabled: opt.disabled }))}
      {...(rest as Record<string, unknown>)}
    >
      {children}
    </AntSelect>
  )
}
