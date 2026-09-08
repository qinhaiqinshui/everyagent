import React from 'react'
import type { ThemeMode } from '@/types'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { loadThemeMode } from '@/settings/localSettings'

/**
 * 主题模式查询 Hook。
 * 主题是纯前端偏好,存 localStorage(n 分支存 ZenFS 设置文件,已改)。
 */
export function useThemeMode(initialThemeMode: ThemeMode): ThemeMode {
  const [themeMode, setThemeMode] = React.useState<ThemeMode>(initialThemeMode)

  React.useEffect(() => {
    setThemeMode(loadThemeMode())
    const unsubscribe = domainEventBus.subscribe(DOMAIN_EVENTS.SETTINGS_THEME_PATCHED, ({ themeMode: nextThemeMode }) => {
      setThemeMode(nextThemeMode)
    })
    return unsubscribe
  }, [])

  return themeMode
}
