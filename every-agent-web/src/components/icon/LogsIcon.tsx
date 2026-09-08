import type { IconProps } from './types'
import AppSvg from './AppSvg'

export type LogsIconProps = IconProps

export function LogsIcon({ size = 22, color, className }: LogsIconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="M2.8 8H4.7L5.9 5.8L7.5 10.4L8.9 8L10.2 9.3H13.1" />
    </AppSvg>
  )
}

export default LogsIcon
