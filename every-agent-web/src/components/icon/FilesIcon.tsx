import type { IconProps } from './types'
import AppSvg from './AppSvg'

export type FilesIconProps = IconProps

export function FilesIcon({ size = 22, color, className }: FilesIconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <path d="M2.6 1.8H10.7L13.3 4.4V14.2H2.6V1.8Z" />
      <path d="M10.7 1.8V4.4H13.3" />
      <path d="M5 7.4H11" />
      <path d="M5 10.4H9.9" />
    </AppSvg>
  )
}

export default FilesIcon
