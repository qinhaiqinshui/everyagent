import React from 'react'
import { Select as AntSelect, type SelectProps as AntSelectProps } from 'antd'
import { cn } from './cn'

export type SelectOption = {
  value: string
  label: React.ReactNode
  disabled?: boolean
}

/**
 * 基础下拉选择的 props。
 *
 * **值语义（与运行期一致）**：底层是 antd `Select`，`onChange` 回调收到的是**选中值**
 * （`string`），**不是** DOM 事件——写 `onChange={(value) => ...}`，别写 `event.target.value`。
 *
 * 历史坑：本类型曾写成 `Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'size'>`
 * （DOM `<select>` 口径），`onChange` 因此被声明成事件形参，但运行期是值语义；按声明写
 * `event.target.value` 会在运行期炸（`Cannot read properties of undefined`）。现改为直接
 * 派生自 antd 的 `SelectProps<string>`，把透传项（`allowClear` / `popupMatchSelectWidth` /
 * `placeholder` 等）与 `onChange` 一并交给 antd 的类型，杜绝同类偏差。
 */
export type SelectProps = Omit<
  AntSelectProps<string>,
  'size' | 'options' | 'onChange' | 'value' | 'defaultValue' | 'children'
> & {
  /** 便捷选项；也可直接传入 <option> 子节点。 */
  options?: SelectOption[]
  /** 控件尺寸，默认使用标准表单密度。 */
  size?: 'sm' | 'md'
  /** 值变更回调（收选中值，不是 DOM 事件）。 */
  onChange?: (value: string) => void
  /** 受控值。 */
  value?: string
  /** 非受控默认值。 */
  defaultValue?: string
  children?: React.ReactNode
}

/** 基础下拉选择（antd Select 实现）。 */
export default function Select({ options, size = 'md', className, children, ...rest }: SelectProps) {
  return (
    <AntSelect
      size={size === 'sm' ? 'small' : 'middle'}
      className={cn(className)}
      options={options?.map((opt) => ({ value: opt.value, label: opt.label, disabled: opt.disabled }))}
      {...rest}
    >
      {children}
    </AntSelect>
  )
}
