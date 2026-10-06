/**
 * 插件图标加载（plugin.asset RPC + 模块级缓存 + 默认图标回退）。
 *
 * - plugin.list 下发的 `icon` 是插件目录内相对路径；有值时经 `plugin.asset`
 *   RPC 读字节（mime + base64）拼 data URL。
 * - 无 icon 字段 / RPC 失败（文件缺失、旧 worker 无此方法）一律回退 null，
 *   由 UI 渲染默认扩展图标——仓内插件当前均未配图标，默认图标即常态展示。
 * - 模块级缓存避免列表反复挂载时重复 RPC；key 含 icon 路径，插件换图标后自动失效。
 */
import React from 'react'
import type { PluginSdk } from '@everyagent/plugin-api'

/** plugin.asset RPC 返回格式。 */
interface PluginAssetResult {
  mime?: string
  contentBase64?: string
}

/** 图标缓存：key = `${pluginId}|${iconPath}`，value = data URL（null = 已确认无可用图标）。 */
const iconCache = new Map<string, string | null>()

/** 图标请求去重：进行中的请求共享同一 Promise。 */
const inflight = new Map<string, Promise<string | null>>()

/**
 * 拉取插件图标并缓存；失败返回 null（UI 回退默认图标，不抛错）。
 */
async function fetchPluginIcon(
  sdk: PluginSdk,
  workerId: string,
  pluginId: string,
  iconPath: string,
): Promise<string | null> {
  if (!iconPath) return null
  const key = `${pluginId}|${iconPath}`
  const cached = iconCache.get(key)
  if (cached !== undefined) return cached
  const pending = inflight.get(key)
  if (pending) return pending
  const promise = (async () => {
    try {
      const result = (await sdk.rpc(workerId, 'plugin.asset', {
        pluginId,
        path: iconPath,
      })) as PluginAssetResult
      const dataUrl = result?.mime && result?.contentBase64
        ? `data:${result.mime};base64,${result.contentBase64}`
        : null
      iconCache.set(key, dataUrl)
      return dataUrl
    } catch {
      iconCache.set(key, null)
      return null
    } finally {
      inflight.delete(key)
    }
  })()
  inflight.set(key, promise)
  return promise
}

/**
 * 插件图标 hook：有 icon 声明时异步拉取，其余状态恒为 null（默认图标）。
 */
export function usePluginIcon(
  sdk: PluginSdk | null,
  workerId: string,
  pluginId: string,
  iconPath: string | undefined,
): { dataUrl: string | null; loading: boolean } {
  const [dataUrl, setDataUrl] = React.useState<string | null>(() =>
    iconPath ? iconCache.get(`${pluginId}|${iconPath}`) ?? null : null,
  )
  const [loading, setLoading] = React.useState(false)

  React.useEffect(() => {
    if (!sdk || !iconPath) {
      setDataUrl(null)
      setLoading(false)
      return
    }
    const key = `${pluginId}|${iconPath}`
    const cached = iconCache.get(key)
    if (cached !== undefined) {
      setDataUrl(cached)
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    fetchPluginIcon(sdk, workerId, pluginId, iconPath).then((url) => {
      if (!cancelled) {
        setDataUrl(url)
        setLoading(false)
      }
    })
    return () => {
      cancelled = true
    }
  }, [sdk, workerId, pluginId, iconPath])

  return { dataUrl, loading }
}
