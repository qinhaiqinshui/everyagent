/**
 * 插件图标组件（列表行 / 详情页头图 / 标签页图标共用）。
 *
 * icon 声明 → plugin.asset RPC 拉 base64 → data URL；无声明/加载失败一律
 * 回退默认扩展图标（仓内插件当前均未配图标，默认图标即常态展示）。
 */
import React from 'react'
import { getSdk } from './index'
import { usePluginIcon } from './pluginAssets'
import { DefaultPluginIcon } from './icons'

/** 插件图标：iconPath 有值时异步拉取，期间与失败均显示默认扩展图标。 */
export function PluginIcon({
  size,
  pluginId,
  iconPath,
  alt,
}: {
  size: number
  pluginId: string
  iconPath?: string
  alt?: string
}) {
  const sdk = getSdk()
  const { dataUrl } = usePluginIcon(sdk, sdk?.workerId ?? '', pluginId, iconPath)
  if (dataUrl) {
    return (
      <img
        src={dataUrl}
        alt={alt ?? pluginId}
        style={{
          width: size,
          height: size,
          borderRadius: Math.max(4, Math.round(size * 0.13)),
          border: '1px solid var(--border-light)',
          objectFit: 'cover',
          flexShrink: 0,
          display: 'block',
          background: 'var(--bg-tertiary)',
        }}
      />
    )
  }
  return <DefaultPluginIcon size={size} />
}
