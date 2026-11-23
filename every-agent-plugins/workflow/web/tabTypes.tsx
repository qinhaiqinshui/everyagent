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

// ─── workflow-instance 标签类型（占位注册；步骤 10 填充实现） ───────────────

export const workflowInstanceTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: WORKFLOW_INSTANCE_TAB_TYPE,
  pluginId: 'workflow',
  renderTab: (tab: PluginWorkspaceTab, _ctx: WorkspaceTabRenderContext): ReactNode => {
    const data = tabData(tab, WORKFLOW_INSTANCE_TAB_TYPE)
    return (
      <div
        style={{
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 8,
          background: 'var(--bg-primary, #0b0d12)',
          color: 'var(--text-muted, #6f7a8b)',
          fontSize: 13,
          lineHeight: 1.8,
        }}
      >
        <div style={{ fontWeight: 600, color: 'var(--text-secondary, #aab3c2)' }}>实例运行页（步骤 10 落地）</div>
        <div>instanceId：{data.instanceId || '（未携带）'}</div>
        <div>将复用 WorkflowCanvas（mode=readonly，节点按状态着色）+ instance.events 长轮询 + 决策按钮。</div>
      </div>
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
