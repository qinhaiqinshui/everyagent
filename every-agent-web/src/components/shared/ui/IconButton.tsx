import React, { forwardRef } from 'react'
import { Button as AntButton } from 'antd'
import type { ButtonProps as AntButtonProps } from 'antd'
import { cn } from './cn'

export type IconButtonVariant = 'default' | 'ghost' | 'primary' | 'danger'
export type IconButtonSize = 'sm' | 'md' | 'lg'

export type IconButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  /** 图标节点（建议 16px SVG）。 */
  icon: React.ReactNode
  /** 无障碍标签，必填（图标按钮无可见文字）。 */
  'aria-label': string
  /** 视觉变体，默认 default。 */
  variant?: IconButtonVariant
  /** 尺寸，默认 md。 */
  size?: IconButtonSize
  /** 危险态（红色）。 */
  danger?: boolean
}

const VARIANT_TO_ANTD: Record<IconButtonVariant, AntButtonProps['type']> = {
  primary: 'primary',
  danger: 'primary',
  ghost: 'text',
  default: 'text',
}

/**
 * 仅含图标的方形按钮（antd Button 实现）。必须提供 aria-label。
 */
const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { icon, variant = 'default', size = 'md', className, type = 'button', danger, ...rest },
  ref,
) {
  return (
    <AntButton
      ref={ref as never}
      type={VARIANT_TO_ANTD[variant]}
      danger={variant === 'danger' ? true : danger}
      size={size === 'lg' ? 'large' : size === 'sm' ? 'small' : 'middle'}
      icon={icon as React.ReactNode}
      aria-label={rest['aria-label']}
      htmlType={type as AntButtonProps['htmlType']}
      className={cn(className)}
      {...(rest as Record<string, unknown>)}
    />
  )
})

export default IconButton
