import React from 'react'
import type { ThemeMode } from '@/types'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { loadThemeMode } from '@/settings/localSettings'

/**
 * 主题模式查询 Hook。
 * 主题持久化在 worker 侧(preferences.json),localStorage 仅作首帧缓存;
 * worker 连接就绪后经 userPreferences 服务自动同步校正。
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
