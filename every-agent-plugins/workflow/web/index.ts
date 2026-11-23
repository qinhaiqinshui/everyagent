/**
 * workflow —— 前端插件入口（PluginModule，git 插件同款工程范式）。
 *
 * 加载链路：宿主前端经 plugin.webSource RPC 取 web/index.js（esbuild 产物），
 * 改写 bare import 后动态加载，调用 default export 的 activate(ctx)。
 *
 * 本步（步骤 8）注册：
 * - 侧边栏「工作流」占位面板（order=4；实例/定义列表与新建/运行/导入导出=步骤 9）；
 * - workflow-editor 工作区标签类型（定义编辑器，本步主体）；
 * - workflow-instance 工作区标签类型（占位注册，避免步骤 10 再改 manifest 结构）。
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
 *   实例运行页=步骤 10 实现（画布只读回放 + instance.events 长轮询 + 决策按钮）。
 */

import type { Disposable, PluginContext, PluginModule, UiSidebarItemDefinition } from '@everyagent/plugin-api'
import React from 'react'
import { WorkflowIcon } from './icons'
import { setPluginContext } from './pluginRuntime'
import { workflowEditorTabType, workflowInstanceTabType } from './tabTypes'
import SidebarPanel from './panels/SidebarPanel'

/** 本插件持有的可释放资源；deactivate() 时统一清理（宿主当前不自动卸载，留好出口）。 */
const disposables: Disposable[] = []

const workflowPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    // 注入 module-level ctx（组件/网关取用同一 ctx；git 插件同款）。
    setPluginContext(ctx)

    // 侧边栏「工作流」（order=4：搜索(3) 之后、git(5) 之前；面板=占位版，步骤 9 替换）。
    const sidebarItem: UiSidebarItemDefinition = {
      id: 'workflow',
      title: '工作流',
      icon: React.createElement(WorkflowIcon),
      Panel: SidebarPanel,
      order: 4,
    }
    disposables.push(ctx.ui.registerSidebarItem(sidebarItem))

    // 定义编辑器标签类型 + 实例标签类型（占位，步骤 10 填充）。
    disposables.push(ctx.ui.registerWorkspaceTabType(workflowEditorTabType))
    disposables.push(ctx.ui.registerWorkspaceTabType(workflowInstanceTabType))
  },

  // 宿主当前不调用 deactivate，但保留标准出口（热卸载接入时能摘除全部注册项）。
  deactivate() {
    for (const d of disposables.splice(0)) {
      d.dispose()
    }
  },
}

export default workflowPlugin

