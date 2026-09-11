import type { IconProps } from './types'
import AppSvg from './AppSvg'

export type SearchSidebarIconProps = IconProps

/**
 * 活动栏「搜索」面板图标(放大镜,描边风格对齐其他活动栏图标)。
 */
export function SearchSidebarIcon({ size = 22, color, className }: SearchSidebarIconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <circle cx="6.7" cy="6.7" r="4.3" />
      <path d="M10 10L13.7 13.7" />
    </AppSvg>
  )
}

export default SearchSidebarIcon
