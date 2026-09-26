/**
 * git 插件内部工作区状态 hook：经 ctx.sdk.workspace.list / ctx.events 获取多工作区
 * 注册表与连接就绪态（替代宿主 useHub + workspaceRegistry，插件不引用宿主模块）。
 *
 * 语义：
 * - entries === null：注册表尚未就绪（worker 未连接 / 首次拉取失败）。
 * - ready：存在至少一个已注册工作区（等价于「有可用的已连接 worker」）。
 * - 注册表变化（含 worker 重连后的刷新）经 workspace-registry-changed 事件驱动重取。
 */
import React from 'react'
import type { PluginWorkspaceEntry } from '@everyagent/plugin-api'
import { getPluginContext } from './pluginRuntime'

export function usePluginWorkspaces(): {
  entries: PluginWorkspaceEntry[] | null
  defaultRoot: string
  ready: boolean
} {
  const ctx = getPluginContext()
  const [entries, setEntries] = React.useState<PluginWorkspaceEntry[] | null>(null)
  const [defaultRoot, setDefaultRoot] = React.useState('')

  const refresh = React.useCallback(() => {
    void ctx.sdk.workspace.list()
      .then((list) => setEntries(list))
      .catch(() => setEntries(null))
  }, [ctx])

  React.useEffect(() => {
    refresh()
    const disposable = ctx.events.on('workspace-registry-changed', (payload) => {
      const p = payload as { defaultRoot?: string } | undefined
      if (p?.defaultRoot) {
        setDefaultRoot(p.defaultRoot)
      }
      refresh()
    })
    return () => disposable.dispose()
  }, [ctx, refresh])

  return {
    entries,
    defaultRoot,
    ready: (entries?.length ?? 0) > 0,
  }
}
