/**
 * Git 插件——PluginModule 入口（纯 Web 插件）。
 *
 * 经 builtInPlugins.ts 自动发现加载，注册：
 * - 侧边栏「源代码管理」面板 + 变更角标
 * - git-history 工作区标签类型
 * - 文件树右键「显示 Git 历史」菜单项
 *
 * 一切宿主能力经 ctx 暴露（不引用宿主 web 模块）：导航走 ctx.ui.openPluginTab，
 * 本插件内部组件经 pluginRuntime 的 module-level ctx 取用同一 ctx。
 */

import React from 'react'
import type { PluginModule } from '@everyagent/plugin-api'
import { GitIcon } from './icons'
import GitSidebarPanel from './GitSidebarPanel'
import GitChangeBadge from './GitChangeBadge'
import { gitHistoryTabType } from './GitHistoryTabType'
import { setPluginContext } from './pluginRuntime'
import { normalizeWorkspaceRelativePath } from './pathUtils'

const gitPlugin: PluginModule = {
  activate(ctx) {
    // 注入 module-level ctx，供本插件内部组件/网关取用。
    setPluginContext(ctx)

    // 注册侧边栏面板
    ctx.ui.registerSidebarItem({
      id: 'git',
      title: '源代码管理',
      icon: React.createElement(GitIcon),
      Panel: GitSidebarPanel,
      Badge: GitChangeBadge,
      // 活动栏排序：落在内置「搜索」(3) 之后、「扩展」(9) 与「设置」(10) 之前。
      order: 5,
    })

    // 注册 git-history 标签类型
    ctx.ui.registerWorkspaceTabType(gitHistoryTabType)

    // 注册文件树右键「显示 Git 历史」
    ctx.ui.registerFileExplorerAction({
      id: 'git-show-history',
      label: '显示 Git 历史',
      icon: React.createElement(GitIcon),
      invoke: (actionCtx) => {
        ctx.ui.openPluginTab('git-history', {
          workspaceRoot: actionCtx.workspaceRoot,
          path: normalizeWorkspaceRelativePath(actionCtx.path),
          name: actionCtx.name,
        }, `Git 历史：${actionCtx.name}`)
      },
    })
  },
}

export default gitPlugin
