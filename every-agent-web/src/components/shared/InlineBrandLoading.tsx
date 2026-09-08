import React from 'react'

type InlineBrandLoadingProps = {
  /** N 色块的基础尺寸。 */
  size?: number
  /** 额外类名。 */
  className?: string
}

/**
 * 通用小型品牌加载组件。
 * 使用 N 色块做轻量跳动反馈，适合按钮内、标题内等快速操作场景。
 */
export default function InlineBrandLoading({
  size = 12,
  className,
}: InlineBrandLoadingProps) {
  return (
    <span
      className={['inline-brand-loading', className].filter(Boolean).join(' ')}
      style={{ '--inline-brand-loading-size': `${size}px` } as React.CSSProperties}
      aria-hidden="true"
    >
      <span className="inline-brand-loading__mark">
        N
        <span className="inline-brand-loading__sheen" />
      </span>
    </span>
  )
}
