/**
 * workflow 侧边栏面板——**步骤 8 占位版**。
 *
 * 步骤 9 将替换为完整面板（上=实例列表【状态色点，点击 openTab 实例页】、
 * 下=定义列表【增删改入口、+ 新建】、运行对话框与导入导出入口）。
 * 占位版仅提供「新建工作流定义」入口（打开 workflow-editor 标签，definitionId 空=新建），
 * 便于步骤 8 的编辑器人工验证。
 */
import { getPluginContext } from '../pluginRuntime'
import { WORKFLOW_EDITOR_TAB_TYPE } from '../tabKeys'

export default function SidebarPanel() {
  const ctx = getPluginContext()
  return (
    <div style={{ padding: 14, fontSize: 13, lineHeight: 1.8, color: 'var(--text-secondary, #aab3c2)' }}>
      <div style={{ fontWeight: 600, color: 'var(--text-primary, #eef1f5)', marginBottom: 6 }}>工作流</div>
      <p style={{ margin: '0 0 12px', fontSize: 12, color: 'var(--text-muted, #6f7a8b)' }}>
        实例列表 / 定义列表 / 运行对话框 / 导入导出将在步骤 9 落地。
      </p>
      <button
        type="button"
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          padding: '5px 12px',
          border: '1px solid var(--border-strong, #3b4658)',
          borderRadius: 6,
          background: 'transparent',
          color: 'var(--text-secondary, #aab3c2)',
          cursor: 'pointer',
          fontSize: 12.5,
        }}
        onClick={() => {
          // 新建入口：归属工作区=插件加载时的工作区（ctx.sdk.workspace.id 为稳定 id）。
          ctx.ui.openTab(
            WORKFLOW_EDITOR_TAB_TYPE,
            { workspaceId: ctx.sdk.workspace.id, definitionId: '' },
            '工作流（新建）',
          )
        }}
      >
        ＋ 新建工作流定义
      </button>
    </div>
  )
}
