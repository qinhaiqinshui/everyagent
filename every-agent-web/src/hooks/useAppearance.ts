/**
 * 外观（主题 id + 基底 type）查询 Hook。
 *
 * 持久化在 worker 侧(preferences.json)，localStorage 仅作首帧缓存；订阅四类变更后
 * 重新解析并返回：主题 id 变更（领域事件）、自定义主题列表变更、编辑器字体变更、
 * 插件扩展点变更（插件加载出新主题后需重解析当前 id 的真实 type）。
 */
import React from 'react'
import type { ThemeMode } from '@/types'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import { loadThemeId, loadThemeType } from '@/settings/localSettings'
import { subscribeThemeStore } from '@/settings/themeStore'
import { subscribeEditorFont } from '@/settings/editorFont'
import { resolveThemeById } from '@/theme/resolveThemes'

export interface AppearanceState {
  /** 当前主题 id。 */
  themeId: string
  /** 基底（深 / 浅），供 antd / 终端 / color-scheme 使用。 */
  type: ThemeMode
}

function resolveAppearance(): AppearanceState {
  const id = loadThemeId()
  const theme = resolveThemeById(id)
  // 插件主题尚未加载时解析会回落到内建（id 不匹配）→ 用缓存的 type 兜底避免闪烁。
  return { themeId: id, type: theme.id === id ? theme.type : loadThemeType() }
}

export function useAppearance(initialType: ThemeMode): AppearanceState {
  const [state, setState] = React.useState<AppearanceState>(() => {
    const resolved = resolveAppearance()
    return resolved.type ? resolved : { themeId: resolved.themeId, type: initialType }
  })

  React.useEffect(() => {
    const refresh = () => setState(resolveAppearance())
    refresh()
    const unsubs: Array<() => void> = [
      domainEventBus.subscribe(DOMAIN_EVENTS.SETTINGS_THEME_PATCHED, refresh),
      subscribeThemeStore(refresh),
      subscribeEditorFont(refresh),
      pluginDispatcher.subscribeExtensionsChanged(refresh),
    ]
    return () => {
      for (const unsubscribe of unsubs) unsubscribe()
    }
  }, [])

  return state
}