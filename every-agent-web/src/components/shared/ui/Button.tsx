import React, { forwardRef } from 'react'
import { Button as AntButton } from 'antd'
import type { ButtonProps as AntButtonProps } from 'antd'
import { cn } from './cn'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'text'
export type ButtonSize = 'sm' | 'md' | 'lg'

export type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  /** 视觉变体，默认 secondary。 */
  variant?: ButtonVariant
  /** 尺寸，默认 md。 */
  size?: ButtonSize
  /** 是否占满父容器宽度。 */
  block?: boolean
  /** 危险态（红色）。 */
  danger?: boolean
  /** 加载中态(禁用并显示转圈;透传给 antd Button)。 */
  loading?: boolean
}

const VARIANT_TO_ANTD: Record<ButtonVariant, AntButtonProps['type']> = {
  primary: 'primary',
  danger: 'primary',
  ghost: 'text',
  secondary: 'default',
  text: 'text',
}

/**
 * 基础按钮（antd Button 实现）。
 * 保持原 prop 接口：variant→antd type，size→antd size，原生 type→htmlType。
 */
const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', block = false, className, type = 'button', danger, ...rest },
  ref,
) {
  // 原生 type(button/submit/reset) 只应控制 DOM 提交行为(htmlType)，
  // 不能透传给 antd 的 type(视觉变体，由 variant 决定)，否则会覆盖 variant 映射。
  const { type: _ignoredNativeType, ...restWithoutType } = rest as Record<string, unknown> & { type?: string }
  return (
    <AntButton
      ref={ref as never}
      type={VARIANT_TO_ANTD[variant]}
      danger={variant === 'danger' ? true : danger}
      size={size === 'lg' ? 'large' : size === 'sm' ? 'small' : 'middle'}
      block={block}
      htmlType={type as AntButtonProps['htmlType']}
      className={cn(className)}
      {...(restWithoutType as Record<string, unknown>)}
    />
  )
})

export default Button
