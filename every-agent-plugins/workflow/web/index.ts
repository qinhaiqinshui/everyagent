/**
 * workflow —— 前端插件入口（PluginModule，git 插件同款工程范式）。
 *
 * 加载链路：宿主前端经 plugin.webSource RPC 取 web/index.js（esbuild 产物），
 * 改写 bare import 后动态加载，调用 default export 的 activate(ctx)。
 *
 * 注册：
 * - 侧边栏「工作流」面板（order=4：实例列表 + 组头 ▶ 运行 / 管理 / ⋯ 刷新——
 *   定义不在侧栏展示，定义的增删改查/导入/导出全部在管理标签页）；
 * - workflow-editor 工作区标签类型（定义编辑器：画布 + 属性面板 + 变量/出错策略）；
 * - workflow-instance 工作区标签类型（实例运行页 + 运行日志页）；
 * - workflow-manage 工作区标签类型（工作区级定义管理页：列表 + 行操作）。
 *
 * 硬约束（docs/plugin-guide/web/overview-and-loading.md）：
 * - 类型一律 import type ... from '@everyagent/plugin-api'（纯类型包）；
 * - bare import 白名单：react / react-dom / react/jsx-runtime / antd /
 *   @ant-design/icons / react-markdown / remark-gfm；禁止 '@/...' 宿主内部路径；
 * - 产物必须是 web/index.js（改 web 后必须重跑 build:plugins——宿主读预编译产物）；
 * - 入口 .ts 不写 JSX（esbuild 对 .ts 不启用 JSX loader）——含 JSX 的实现一律放 .tsx 子模块。
 *
 * ── tab.data 参数约定（openTab 的 data 袋，宿主透明中转、本插件自解释） ──
 * - workflow-editor（tabTypes.tsx）：{ workspaceId: 稳定工作区 id（workflow.* RPC 的
 *     workspace 参数）；definitionId?: 定义 id，空/缺省=新建（保存时后端赋 wf id）；
 *     workspaceRoot?/workerId?: 富输入框与 RPC 定向（缺省经注册表解析） }
 *   打开方式：ctx.ui.openTab('workflow-editor', { workspaceId, definitionId }, 标题)
 * - workflow-instance（tabTypes.tsx）：{ instanceId: 实例 id（wi_…）；workspaceId?（回开定义用） }
 *   实例运行页：画布只读回放 + instance.events 长轮询 + 决策横幅。
 * - workflow-manage（tabTypes.tsx）：{ workspaceId: 稳定工作区 id }
 *   定义管理页（每工作区一个标签，id=workflow-manage:<workspaceId>，重复打开聚焦）：
 *   打开方式：ctx.ui.openTab('workflow-manage', { workspaceId }, '管理工作流')
 */

import type { Disposable, PluginContext, PluginModule, UiSidebarItemDefinition } from '@everyagent/plugin-api'
import React from 'react'
import { WorkflowIcon } from './icons'
import { setPluginContext } from './pluginRuntime'
import { workflowEditorTabType, workflowInstanceTabType, workflowManageTabType } from './tabTypes'
import SidebarPanel from './panels/SidebarPanel'

/** 本插件持有的可释放资源；deactivate() 时统一清理（宿主当前不自动卸载，留好出口）。 */
const disposables: Disposable[] = []

const workflowPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    // 注入 module-level ctx（组件/网关取用同一 ctx；git 插件同款）。
    setPluginContext(ctx)

    // 侧边栏「工作流」（order=4：搜索(3) 之后、git(5) 之前）。
    const sidebarItem: UiSidebarItemDefinition = {
      id: 'workflow',
      title: '工作流',
      icon: React.createElement(WorkflowIcon),
      Panel: SidebarPanel,
      order: 4,
    }
    disposables.push(ctx.ui.registerSidebarItem(sidebarItem))

    // 定义编辑器 / 实例运行页 / 定义管理页标签类型（懒加载分包，见 lazy.tsx）。
    disposables.push(ctx.ui.registerWorkspaceTabType(workflowEditorTabType))
    disposables.push(ctx.ui.registerWorkspaceTabType(workflowInstanceTabType))
    disposables.push(ctx.ui.registerWorkspaceTabType(workflowManageTabType))
  },

  // 宿主当前不调用 deactivate，但保留标准出口（热卸载接入时能摘除全部注册项）。
  deactivate() {
    for (const d of disposables.splice(0)) {
      d.dispose()
    }
  },
}

export default workflowPlugin

