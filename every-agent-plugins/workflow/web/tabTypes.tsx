/**
 * workflow 插件的工作区标签类型定义（workflow-editor / workflow-instance）。
 *
 * tab.data 参数约定见 index.ts 头注释（openTab 的 data 袋，宿主透明中转）：
 * - workflow-editor：{ workspaceId, definitionId?, workspaceRoot?, workerId? }
 * - workflow-instance：{ instanceId, workspaceId? }
 */
import type {
  PluginWorkspaceTab,
  UiWorkspaceTabTypeDefinition,
  WorkspaceTabRenderContext,
} from '@everyagent/plugin-api'
import type { ReactNode } from 'react'
import { WorkflowIcon } from './icons'
import { WORKFLOW_EDITOR_TAB_TYPE, WORKFLOW_INSTANCE_TAB_TYPE } from './tabKeys'
import { createLazyRouteComponent } from './lazy'

/** 定义编辑器面板（懒加载：编辑器较重，不拖慢插件初始激活）。 */
const LazyDefinitionEditorPanel = createLazyRouteComponent(() => import('./panels/DefinitionEditorPanel'))

/** plugin 标签判别（plugin 标签 tabType='plugin' + pluginTabType=注册 key，git 插件同款强转）。 */
function pluginTabMatches(tab: PluginWorkspaceTab, key: string): boolean {
  const pluginTab = tab as PluginWorkspaceTab & { pluginTabType?: string }
  return pluginTab.tabType === 'plugin' && pluginTab.pluginTabType === key
}

function tabData(tab: PluginWorkspaceTab, key: string): Record<string, string> {
  return pluginTabMatches(tab, key) ? (tab.data ?? {}) : {}
}

// ─── workflow-editor 标签类型（定义编辑器） ────────────────────────────────

export const workflowEditorTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: WORKFLOW_EDITOR_TAB_TYPE,
  pluginId: 'workflow',
  renderTab: (tab: PluginWorkspaceTab, _ctx: WorkspaceTabRenderContext): ReactNode => {
    const data = tabData(tab, WORKFLOW_EDITOR_TAB_TYPE)
    return (
      <LazyDefinitionEditorPanel
        workspaceId={data.workspaceId ?? ''}
        definitionId={data.definitionId || undefined}
        workspaceRoot={data.workspaceRoot || undefined}
        workerId={data.workerId || undefined}
      />
    )
  },
  renderIcon: () => <WorkflowIcon size={15} />,
  getLabel: (tab: PluginWorkspaceTab) =>
    pluginTabMatches(tab, WORKFLOW_EDITOR_TAB_TYPE) ? (tab.title ?? '工作流编辑器') : '',
  getTitle: (tab: PluginWorkspaceTab) => {
    const data = tabData(tab, WORKFLOW_EDITOR_TAB_TYPE)
    return data.definitionId ? `工作流定义 ${data.definitionId}` : '工作流定义（新建）'
  },
  getCloseAriaLabel: (tab: PluginWorkspaceTab) => `关闭工作流编辑器 ${tab.title ?? ''}`,
  getSidebarActivityId: () => 'workflow',
}

// ─── workflow-instance 标签类型（实例运行页，步骤 10） ─────────────────────

/** 实例运行页面板（懒加载：画布/日志视图较重，不拖慢插件初始激活）。 */
const LazyInstancePanel = createLazyRouteComponent(() => import('./panels/InstancePanel'))

export const workflowInstanceTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: WORKFLOW_INSTANCE_TAB_TYPE,
  pluginId: 'workflow',
  renderTab: (tab: PluginWorkspaceTab, _ctx: WorkspaceTabRenderContext): ReactNode => {
    const data = tabData(tab, WORKFLOW_INSTANCE_TAB_TYPE)
    return (
      <LazyInstancePanel
        instanceId={data.instanceId ?? ''}
        workspaceId={data.workspaceId || undefined}
      />
    )
  },
  renderIcon: () => <WorkflowIcon size={15} />,
  getLabel: (tab: PluginWorkspaceTab) =>
    pluginTabMatches(tab, WORKFLOW_INSTANCE_TAB_TYPE) ? (tab.title ?? '工作流实例') : '',
  getTitle: (tab: PluginWorkspaceTab) => {
    const data = tabData(tab, WORKFLOW_INSTANCE_TAB_TYPE)
    return data.instanceId ? `工作流实例 ${data.instanceId}` : '工作流实例'
  },
  getCloseAriaLabel: (tab: PluginWorkspaceTab) => `关闭工作流实例 ${tab.title ?? ''}`,
  getSidebarActivityId: () => 'workflow',
}
