import type { IconProps } from './types'
import AppSvg from './AppSvg'

export type TaskChatIconProps = IconProps

export function TaskChatIcon({ size = 22, color, className }: TaskChatIconProps) {
  return (
    <AppSvg size={size} color={color} className={className}>
      <path d="M2.5 4.2C2.5 3.5 3.0 3 3.7 3H12.3C13.0 3 13.5 3.5 13.5 4.2V10.5C13.5 11.2 13.0 11.7 12.3 11.7H6.6L3.9 13.8V11.7H3.7C3.0 11.7 2.5 11.2 2.5 10.5V4.2Z" />
    </AppSvg>
  )
}

export default TaskChatIcon
