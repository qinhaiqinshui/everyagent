/**
 * Git 视图外部刷新触发器(对齐 VS Code git 扩展的刷新时机,事件驱动、零定时器):
 * - sidebar-panel-shown(panelId === 'git'):点击打开「源代码管理」面板时;
 * - workspace-file-changed:应用内/agent 工具写文件后——页面不可见(后台)时跳过,
 *   不发刷新请求,等切回前台由 visibilitychange 统一补刷;
 * - window focus / visibilitychange → visible:从后台切回前台时(覆盖命令行等
 *   进程外 git 操作后切回应用的场景,VS Code 同款时机)。
 *
 * 触发后由调用方自行调用 git.status(角标与面板共用本 hook,各自刷新各自状态)。
 */
import React from 'react'
import { getPluginContext } from './pluginRuntime'

export function useGitUiRefreshTriggers(requestRefresh: () => void) {
  const ctx = getPluginContext()
  React.useEffect(() => {
    const onFileChanged = () => {
      // 后台不发请求:隐藏期间的变更统一延迟到切回前台时刷新。
      if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
      requestRefresh()
    }
    const onPanelShown = (payload: unknown) => {
      if ((payload as { panelId?: string } | undefined)?.panelId !== 'git') return
      requestRefresh()
    }
    const onForeground = () => requestRefresh()
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') requestRefresh()
    }
    const disposables = [
      ctx.events.on('workspace-file-changed', onFileChanged),
      ctx.events.on('sidebar-panel-shown', onPanelShown),
    ]
    window.addEventListener('focus', onForeground)
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      for (const disposable of disposables) disposable.dispose()
      window.removeEventListener('focus', onForeground)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [ctx, requestRefresh])
}
