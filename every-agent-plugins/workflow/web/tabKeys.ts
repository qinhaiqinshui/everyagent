/**
 * workflow 插件的标签类型 key 常量（.ts 入口与 .tsx tab 定义共用；不含 JSX）。
 *
 * - workflow-editor：定义编辑器（步骤 8 主体实现 panels/DefinitionEditorPanel.tsx）；
 * - workflow-instance：实例运行页（步骤 10 实现；本步占位注册避免后续改 manifest）；
 * - workflow-manage：工作区级定义管理页（panels/ManagePanel.tsx）——增删改查/
 *   导入/导出/运行；tab.data.workspaceId 定归属（每个工作区一个管理标签页）。
 */
export const WORKFLOW_EDITOR_TAB_TYPE = 'workflow-editor'
export const WORKFLOW_INSTANCE_TAB_TYPE = 'workflow-instance'
export const WORKFLOW_MANAGE_TAB_TYPE = 'workflow-manage'

