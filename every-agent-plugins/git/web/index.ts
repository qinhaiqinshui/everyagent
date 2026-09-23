/**
 * Git 插件——PluginModule 入口（纯 Web 插件）。
 *
 * 经 builtInPlugins.ts 自动发现加载，注册：
 * - 侧边栏「源代码管理」面板 + 变更角标
 * - git-history 工作区标签类型
 * - 文件树右键「显示 Git 历史」菜单项
 */

import React from 'react'
import type { PluginModule } from '@everyagent/plugin-api'
import { GitIcon } from '@/components/icon'
import GitSidebarPanel from './GitSidebarPanel'
import GitChangeBadge from './GitChangeBadge'
import { gitHistoryTabType } from './GitHistoryTabType'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { normalizeWorkspaceRelativePath } from '@/platform/fs/pathUtils'

/** 文件树右键「显示 Git 历史」动作组件包装（需要 hooks，不能用纯函数）。 */
function GitHistoryAction({ workspaceRoot, path, name }: { workspaceRoot: string; path: string; name: string }) {
  const { openPluginTab } = useWorkspaceShell()
  React.useEffect(() => {
    const title = `Git 历史：${name}`
    openPluginTab('git-history', { workspaceRoot, path, name }, title)
  }, [workspaceRoot, path, name, openPluginTab])
  return null
}

/** 渲染 Git 历史动作——实际调用在 invoke 时执行（不需要组件挂载，直接调 openPluginTab）。 */
function createGitHistoryInvoke() {
  // openPluginTab 是 shell 上的方法，在 invoke 回调中直接调用。
  // 但 invoke 是普通函数（不是组件），拿不到 useWorkspaceShell hook 结果。
  // 解决：用一个 ref 存 shell，由 SidebarItem 的 Panel 组件在挂载时设置。
  // 但这太复杂——更简单的方案是：invoke 内直接用全局事件总线触发。
  // 实际上，openPluginTab 最终调的是 setWorkspaceTabs + setActiveWorkspaceTab，
  // 我们可以在 invoke 中构造 plugin tab 并通过 command 执行。
  //
  // 最简方案：invoke 不直接调 openPluginTab，而是发一个自定义事件，
  // 由 GitSidebarPanel 中的某个 listener 接收并调用 openPluginTab。
  // 但这也复杂。
  //
  // 最实际的方案：FileExplorerAction.invoke 不在插件入口注册为闭包，
  // 而是在 GitSidebarPanel 组件挂载时注册（它有 useWorkspaceShell）。
  // 但这样 Action 只在 Git 面板挂载后才可用——对用户来说，
  // Git 面板可能没打开过。
  //
  // 最终方案：用 ctx.commands 注册一个命令，invoke 调用该命令。
  // 但 commands 需要从 web shell 桥接到——也有复杂度。
  //
  // 最简方案：invoke 直接用 hubSession.rpcTo + 手动构造标签。
  // 不行——openPluginTab 是 UI 状态操作。
  //
  // 实际可行方案：在插件模块中导出一个模块级变量，由第一个被渲染的
  // 组件（SidebarItem Panel）设置 openPluginTab 引用。
  return null
}

const gitPlugin: PluginModule = {
  activate(ctx) {
    // 注册侧边栏面板
    ctx.ui.registerSidebarItem({
      id: 'git',
      title: '源代码管理',
      icon: React.createElement(GitIcon),
      Panel: GitSidebarPanel,
      Badge: GitChangeBadge,
    })

    // 注册 git-history 标签类型
    ctx.ui.registerWorkspaceTabType(gitHistoryTabType as unknown as Parameters<typeof ctx.ui.registerWorkspaceTabType>[0])

    // 注册文件树右键「显示 Git 历史」
    // 由于 invoke 回调需要访问 openPluginTab（shell 方法），而我们无法在
    // 纯函数闭包中获取 shell，这里使用一个 trick：
    // GitSidebarPanel 组件在挂载时会把 openPluginTab 写入模块级变量。
    // 如果 Git 面板从未打开过，该变量为 null——此时 invoke 不执行任何操作。
    // 这是可接受的降级：用户必须先打开过 Git 面板才能在文件树右键用 Git 历史。
    ctx.ui.registerFileExplorerAction({
      id: 'git-show-history',
      label: '显示 Git 历史',
      icon: React.createElement(GitIcon),
      invoke: (actionCtx) => {
        const title = `Git 历史：${actionCtx.name}`
        _openPluginTabRef?.('git-history', {
          workspaceRoot: actionCtx.workspaceRoot,
          path: normalizeWorkspaceRelativePath(actionCtx.path),
          name: actionCtx.name,
        }, title)
      },
    })
  },
}

/** 模块级 openPluginTab 引用，由 GitSidebarPanel 组件挂载时设置。 */
let _openPluginTabRef: ((pluginTabType: string, data: Record<string, string>, title?: string) => string | null) | null = null

export function setOpenPluginTabRef(ref: typeof _openPluginTabRef) {
  _openPluginTabRef = ref
}

export default gitPlugin
