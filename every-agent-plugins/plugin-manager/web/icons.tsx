/**
 * 插件管理面板图标组件。
 */
import React from 'react'

/** VSCode 风格的扩展(Extensions)图标。 */
export function ExtensionIcon({ size = 16, className }: { size?: number; className?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      className={className}
      aria-hidden="true"
    >
      <path
        d="M2 2.5a.5.5 0 0 1 .5-.5h4.379a.5.5 0 0 1 .353.146l1.06 1.061a.5.5 0 0 0 .708 0l1.06-1.06A.5.5 0 0 1 10.5 2H13.5a.5.5 0 0 1 .5.5v3.379a.5.5 0 0 1-.146.353l-1.061 1.06a.5.5 0 0 0 0 .708l1.061 1.06A.5.5 0 0 1 14 9.621V13.5a.5.5 0 0 1-.5.5h-3.379a.5.5 0 0 1-.353-.146l-1.06-1.061a.5.5 0 0 0-.708 0l-1.06 1.061A.5.5 0 0 1 6.621 14H2.5a.5.5 0 0 1-.5-.5V9.621a.5.5 0 0 1 .146-.353l1.061-1.06a.5.5 0 0 0 0-.708L2.146 6.439A.5.5 0 0 1 2 6.086V2.5Z"
        fill="currentColor"
      />
    </svg>
  )
}
