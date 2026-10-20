/**
 * 设置页的插件贡献段（`ui.settings_sections` 扩展点）。
 *
 * 宿主订阅扩展点版本号（插件 activate() 晚于首屏），按 order 升序渲染在内置段之后。
 * 每段包一层与内置段同款的 `<section>`（标题取 def.title），组件无 props。
 */
import React from 'react'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import { settingsSectionStyle, settingsSectionTitleStyle } from './settingsSectionStyles'

export default function PluginSettingsSections() {
  const extensionsVersion = React.useSyncExternalStore(
    pluginDispatcher.subscribeExtensionsChanged,
    pluginDispatcher.getExtensionsVersion,
  )
  const sections = React.useMemo(
    () =>
      [...pluginDispatcher.listRegisteredSettingsSections()].sort(
        (a, b) => (a.order ?? 100) - (b.order ?? 100),
      ),
    [extensionsVersion],
  )

  if (sections.length === 0) return null

  return (
    <>
      {sections.map((def) => (
        <section key={def.id} style={settingsSectionStyle}>
          <h3 style={settingsSectionTitleStyle}>{def.title}</h3>
          <def.Component />
        </section>
      ))}
    </>
  )
}