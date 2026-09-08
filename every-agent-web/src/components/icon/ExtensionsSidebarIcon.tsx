import type { IconProps } from './types'
import AppSvg from './AppSvg'

export type ExtensionsSidebarIconProps = IconProps

export function ExtensionsSidebarIcon({ size = 22, color, className }: ExtensionsSidebarIconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <rect x="1.8" y="2.2" width="4.1" height="4.1" rx="0.5" />
      <rect x="1.8" y="9.1" width="4.1" height="4.1" rx="0.5" />
      <rect x="8.7" y="9.1" width="4.1" height="4.1" rx="0.5" />
      <path d="M10.75 1.8L14.2 5.25L10.75 8.7L7.3 5.25L10.75 1.8Z" />
    </AppSvg>
  )
}

export default ExtensionsSidebarIcon
