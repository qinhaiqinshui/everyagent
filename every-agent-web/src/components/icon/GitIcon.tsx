import type { IconProps } from '@/components/icon/types'
import AppSvg from '@/components/icon/AppSvg'

export type GitIconProps = IconProps

export function GitIcon({ size = 22, color, className }: GitIconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <circle cx="3.2" cy="3.3" r="1.4" />
      <circle cx="12.8" cy="7.2" r="1.4" />
      <circle cx="3.2" cy="12.7" r="1.4" />
      <path d="M3.2 4.7V11.3" />
      <path d="M4.5 4.2L11.3 6.9" />
      <path d="M4.4 11.8L11 8.3" />
    </AppSvg>
  )
}

export default GitIcon
