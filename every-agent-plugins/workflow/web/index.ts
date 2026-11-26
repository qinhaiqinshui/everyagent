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
 * - workflow-manage 工作区标签类型（工作区级定义管理页：列表 + 行操作）；
 * - wf.node trace 类型（任务流中非 AI 节点过程卡片：折叠行摘要 + 展开态全量
 *   input/output/error，WfNodeTraceView + wfNodeTrace.css）；
 * - 统一搜索类型 ×2（searchResultView.tsx：工作流定义 / 工作流实例——kind 与 worker
 *   侧 WorkflowSearchProvider 逐字一致；ResultView 点击跳转 workflow-editor /
 *   workflow-instance 标签页）。
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
 *     workerId?: RPC 定向（config.get；缺省经注册表解析） }
 *   打开方式：ctx.ui.openTab('workflow-editor', { workspaceId, definitionId }, 标题)
 * - workflow-instance（tabTypes.tsx）：{ instanceId: 实例 id（wi_…）；workspaceId?（回开定义用） }
 *   实例运行页：画布只读回放 + instance.events 长轮询 + 决策横幅。
 * - workflow-manage（tabTypes.tsx）：{ workspaceId: 稳定工作区 id }
 *   定义管理页（每工作区一个标签，id=workflow-manage:<workspaceId>，重复打开聚焦）：
 *   打开方式：ctx.ui.openTab('workflow-manage', { workspaceId }, '管理工作流')
 */

import type { Disposable, PluginContext, PluginModule, PluginTraceRecord, SearchTypeDefinition, TraceTypeDefinition, UiSidebarItemDefinition } from '@everyagent/plugin-api'
import React from 'react'
import { AiNodeIcon, CommandNodeIcon, StartNodeIcon, WorkflowIcon } from './icons'
import { setPluginContext } from './pluginRuntime'
import { WorkflowDefinitionResultView, WorkflowInstanceResultView } from './searchResultView'
import { workflowEditorTabType, workflowInstanceTabType, workflowManageTabType } from './tabTypes'
import SidebarPanel from './panels/SidebarPanel'
import WfNodeTraceView, {
  WF_NODE_TRACE_KIND,
  readMetaString,
  summarizeWfNode,
  wfNodeStatus,
} from './WfNodeTraceView'

// ─── 统一搜索类型注册（ctx.ui.registerSearchType；kind 与 worker 侧 ──────────
//  WorkflowSearchProvider 的 kinds 声明逐字一致）──

/** kind 常量（worker 侧 WorkflowSearchProvider.KIND_* 同名）。 */
export const WORKFLOW_DEFINITION_SEARCH_KIND = 'workflow-definition'
export const WORKFLOW_INSTANCE_SEARCH_KIND = 'workflow-instance'

/** 「工作流定义」搜索类型：定义结构化字段内容命中 → ResultView 点击打开定义编辑器。 */
const workflowDefinitionSearchType: SearchTypeDefinition = {
  id: WORKFLOW_DEFINITION_SEARCH_KIND,
  label: '工作流定义',
  pluginId: 'workflow',
  order: 40,
  kinds: [WORKFLOW_DEFINITION_SEARCH_KIND],
  filters: [
    { key: 'isRegex', label: '.*', help: '使用正则表达式', type: 'boolean', section: 'inline' },
    { key: 'caseSensitive', label: 'Aa', help: '区分大小写', type: 'boolean', section: 'inline' },
    { key: 'wholeWord', label: 'ab|', help: '全字匹配', type: 'boolean', section: 'inline' },
  ],
  ResultView: WorkflowDefinitionResultView,
}

/** 「工作流实例」搜索类型：events 过程内容命中 + 状态过滤 → 点击打开实例页。 */
const workflowInstanceSearchType: SearchTypeDefinition = {
  id: WORKFLOW_INSTANCE_SEARCH_KIND,
  label: '工作流实例',
  pluginId: 'workflow',
  order: 41,
  kinds: [WORKFLOW_INSTANCE_SEARCH_KIND],
  filters: [
    { key: 'isRegex', label: '.*', help: '使用正则表达式', type: 'boolean', section: 'inline' },
    { key: 'caseSensitive', label: 'Aa', help: '区分大小写', type: 'boolean', section: 'inline' },
    { key: 'wholeWord', label: 'ab|', help: '全字匹配', type: 'boolean', section: 'inline' },
    {
      key: 'status',
      label: '实例状态',
      type: 'select',
      section: 'inline',
      options: [
        { label: '全部状态', value: '' },
        { label: '已创建', value: 'created' },
        { label: '运行中', value: 'running' },
        { label: '等待用户', value: 'waiting-user' },
        { label: '完成', value: 'done' },
        { label: '失败', value: 'failed' },
        { label: '已取消', value: 'cancelled' },
      ],
    },
  ],
  ResultView: WorkflowInstanceResultView,
}

/** wf.node trace 类型定义（非 AI 节点过程卡片，数据形状/状态推导见 WfNodeTraceView 文件头）。
 *  注：入口 .ts 不写 JSX（esbuild 对 .ts 不启用 JSX loader）——图标与渲染走 React.createElement。 */
const wfNodeTraceDef: TraceTypeDefinition = {  kind: WF_NODE_TRACE_KIND,
  // 图标按节点类型分发 + 状态着色（色走 wfNodeTrace.css 的 wf-node-glyph--*，宿主 token）；
  // 13px = 工具行折叠行图标尺寸（与 trace 图标同列同尺寸）。未知类型回退命令图标。
  getIcon: (trace) => {
    const meta = trace.metadata ?? {}
    const glyphClass = `wf-node-glyph wf-node-glyph--${wfNodeStatus(meta)}`
    const nodeType = readMetaString(meta.nodeType)
    const Glyph = nodeType === 'ai' ? AiNodeIcon : nodeType === 'start' ? StartNodeIcon : CommandNodeIcon
    return React.createElement(Glyph, { size: 13, className: glyphClass })
  },
  // title=节点名（收起态保留：节点名 + summary 两段式），故不 hideTitle。
  canExpand: (trace) => Boolean(trace.metadata && Object.keys(trace.metadata).length > 0),
  getSummary: (trace) => summarizeWfNode(trace as PluginTraceRecord),
  renderContent: (trace) =>
    React.createElement(WfNodeTraceView, { trace: trace as PluginTraceRecord }),
}

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

    // 统一搜索类型（搜索弹窗/侧栏的类型选择器：工作流定义 + 工作流实例）。
    disposables.push(ctx.ui.registerSearchType(workflowDefinitionSearchType))
    disposables.push(ctx.ui.registerSearchType(workflowInstanceSearchType))

    // wf.node trace 卡片（非 AI 节点过程呈现：任务流折叠行 + 展开态全量 input/output/error）。
    disposables.push(ctx.ui.registerTraceType(wfNodeTraceDef))
  },

  // 宿主当前不调用 deactivate，但保留标准出口（热卸载接入时能摘除全部注册项）。
  deactivate() {
    for (const d of disposables.splice(0)) {
      d.dispose()
    }
  },
}

export default workflowPlugin

