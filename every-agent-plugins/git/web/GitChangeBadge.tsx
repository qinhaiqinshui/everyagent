/**
 * Git 变更角标组件。
 * 统计所有注册工作区的 git status 变更文件数,有变更时渲染数字角标。
 *
 * 刷新时机(事件驱动、零定时器,见 useGitUiRefreshTriggers):
 * - 挂载/工作区注册表变化;
 * - 打开「源代码管理」面板 / 应用内与 agent 写文件后(仅前台)/ 从后台切回前台;
 * - 面板内 git 操作(commit/discard 等)完成后经 'git-plugin:status-refreshed' 对齐。
 *
 * 并发合并:agent 连续写文件会触发成串 workspace-file-changed,refresh 请求经
 * 「在跑置忙 + 置脏重跑」串行合并(无定时器),风暴期间至多一次在途请求 +
 * 收尾补跑一次,不重复打 git.status RPC。
 */

import React from 'react'
import type { PluginWorkspaceEntry } from '@everyagent/plugin-api'
import { getPluginContext } from './pluginRuntime'
import { gitGateway } from './gitGateway'
import { useGitUiRefreshTriggers } from './useGitUiRefreshTriggers'

const STATUS_KEYS = ['added', 'changed', 'modified', 'removed', 'missing', 'untracked', 'conflicting'] as const

export default function GitChangeBadge() {
  const ctx = getPluginContext()
  const [roots, setRoots] = React.useState<string[]>([])
  const [ready, setReady] = React.useState(false)
  const [count, setCount] = React.useState(0)

  /** roots 快照:触发器回调与在途请求读最新注册表,避免闭包陈旧。 */
  const rootsRef = React.useRef<string[]>([])
  /** 在途请求标志 + 脏标记:串行合并刷新(见文件头注释)。 */
  const inFlightRef = React.useRef(false)
  const dirtyRef = React.useRef(false)

  React.useEffect(() => {
    const refreshRoots = () => {
      void ctx.sdk.workspace.list()
        .then((entries: PluginWorkspaceEntry[]) => {
          setRoots(entries.map((entry) => entry.root))
          setReady(entries.length > 0)
        })
        .catch(() => {
          setRoots([])
          setReady(false)
        })
    }
    refreshRoots()
    const disposable = ctx.events.on('workspace-registry-changed', () => refreshRoots())
    return () => disposable.dispose()
  }, [ctx])

  const runFetch = React.useCallback(async () => {
    const currentRoots = rootsRef.current
    if (currentRoots.length === 0) {
      setCount(0)
      return
    }
    try {
      const counts = await Promise.all(currentRoots.map(async (root) => {
        try {
          const status = await gitGateway.status(root)
          return STATUS_KEYS.reduce((sum, key) => sum + (status[key]?.length ?? 0), 0)
        } catch {
          return 0
        }
      }))
      setCount(counts.reduce((sum, c) => sum + c, 0))
    } catch {
      setCount(0)
    }
  }, [])

  const requestRefresh = React.useCallback(() => {
    if (inFlightRef.current) {
      dirtyRef.current = true
      return
    }
    inFlightRef.current = true
    void (async () => {
      try {
        do {
          dirtyRef.current = false
          await runFetch()
        } while (dirtyRef.current)
      } finally {
        inFlightRef.current = false
      }
    })()
  }, [runFetch])

  // 注册表变化即刷新(roots 引用仅在列表真正变化时更新)。
  React.useEffect(() => {
    rootsRef.current = roots
    requestRefresh()
  }, [roots, ready, requestRefresh])

  // 打开面板 / 前台文件事件 / 切回前台。
  useGitUiRefreshTriggers(requestRefresh)

  // 面板内 git 操作完成后对齐角标(面板 refresh 统一 emit)。
  React.useEffect(() => {
    const disposable = ctx.events.on('git-plugin:status-refreshed', () => requestRefresh())
    return () => disposable.dispose()
  }, [ctx, requestRefresh])

  if (count === 0) return null
  return (
    // 定位几何由宿主统一收口(SidebarActivityBar 的 pluginBadgeAnchorStyle),此处只管内容与配色。
    <span
      style={{
        color: 'var(--accent-blue)',
        fontSize: 10,
        fontWeight: 600,
        lineHeight: 1,
      }}
    >
      {count > 99 ? '99+' : count}
    </span>
  )
}

