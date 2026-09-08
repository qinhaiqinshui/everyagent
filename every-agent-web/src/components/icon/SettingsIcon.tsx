import type { IconProps } from './types'
import AppSvg from './AppSvg'

export type SettingsIconProps = IconProps

export function SettingsIcon({ size = 22, color, className }: SettingsIconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <path d="M8 1.9L9.2 2.3L9.9 3.5L11.3 3.8L12.5 3.2L13.4 4.1L12.8 5.3L13.1 6.7L14.3 7.4V8.6L13.1 9.3L12.8 10.7L13.4 11.9L12.5 12.8L11.3 12.2L9.9 12.5L9.2 13.7L8 14.1L6.8 13.7L6.1 12.5L4.7 12.2L3.5 12.8L2.6 11.9L3.2 10.7L2.9 9.3L1.7 8.6V7.4L2.9 6.7L3.2 5.3L2.6 4.1L3.5 3.2L4.7 3.8L6.1 3.5L6.8 2.3L8 1.9Z" />
      <circle cx="8" cy="8" r="2.5" />
    </AppSvg>
  )
}

export default SettingsIcon
