// ea: 前端插件入口：默认导出 PluginModule，activate() 里经 ctx.ui.registerSidebarItem 注册侧边栏
/**
 * {{pluginName}} —— 前端插件入口（PluginModule，对标 VSCode 扩展入口）。
 *
 * 加载链路：宿主前端经 plugin.webSource RPC 取 web/index.js（esbuild 产物），
 * 改写 bare import 后以 blob URL import 本模块，再调用 default export 的 activate(ctx)。
 *
 * 硬约束（详见 docs/plugin-guide/web/overview-and-loading.md）：
 *   - 类型一律 import type ... from '@everyagent/plugin-api'（纯类型包，零运行时代码）；
 *   - bare import 白名单只有 5 项：react / react-dom / react/jsx-runtime / antd / @ant-design/icons，
 *     运行时由宿主 window.__EA_* 全局提供；禁止 '@/...' 形式的宿主内部路径引用；
 *   - 产物必须是 web/index.js（webMain 只决定 hasWebMain，实际路径由宿主硬编码）。
 *
 * 前端共 12 个扩展点（注册方法都挂在 ctx.ui 上；字段与完整示例见
 * docs/plugin-guide/web/ui-extensions.md，相对仓库根）：
 *   01 ui.sidebar_items            → ctx.ui.registerSidebarItem()                    本文件演示
 *   02 ui.workspace_tab_types      → ctx.ui.registerWorkspaceTabType()               自定义工作区标签类型
 *   03 ui.file_sidebar_panels      → ctx.ui.registerFileSidebarPanel()               文件页侧栏面板
 *   04 ui.composer_above_panel     → ctx.ui.registerComposerAbovePanel()             输入框上方面板（单数）
 *   05 ui.tool_call_views          → ctx.ui.registerToolCallView()                   工具调用视图整体接管
 *   06 ui.user_message_actions     → ctx.ui.registerUserMessageAction()              用户消息气泡动作
 *   07 task.submit_contributions   → ctx.ui.registerTaskRunSubmitContributionProvider() 提交前贡献
 *   08 ui.trace_types              → ctx.ui.registerTraceType()                      trace 类型渲染
 *   09 ui.output_blocks            → ctx.ui.registerOutputBlock()                    输出块渲染
 *   10 ui.file_content_editors     → ctx.ui.registerFileContentEditor()              文件内容编辑器
 *   11 ui.file_explorer_actions    → ctx.ui.registerFileExplorerAction()             文件树右键菜单
 *   12 ui.round_tail_panels        → ctx.ui.registerRoundTailPanel()                 轮末展示区
 */

import type { Disposable, PluginContext, PluginModule, UiSidebarItemDefinition } from '@everyagent/plugin-api'
import React from 'react'

/** 本插件持有的可释放资源；deactivate() 时统一清理（宿主前端目前不自动卸载，留好出口）。 */
const disposables: Disposable[] = []

/** 侧边栏图标（ReactNode）。正式插件常用 @ant-design/icons，这里用内联 span 保持示例零依赖。 */
function SidebarIcon() {
  return React.createElement(
    'span',
    { style: { fontSize: 16, lineHeight: 1 } },
    '◆',
  )
}

/** 侧边栏面板：一个带 {{pluginName}} 标题的简单 div。 */
function SidebarPanel() {
  return React.createElement(
    'div',
    { style: { padding: 16, fontSize: 13, lineHeight: 1.7, color: '#333' } },
    React.createElement('h3', { style: { margin: '0 0 8px' } }, '{{pluginName}}'),
    React.createElement(
      'p',
      { style: { margin: 0, color: '#888' } },
      '面板已就绪：编辑 web/index.ts 替换这里的内容，然后在 every-agent-web 下重跑 npm run build:plugins 并刷新页面。',
    ),
  )
}

const {{camelName}}Plugin: PluginModule = {
  activate(ctx: PluginContext) {
    // 扩展点 ui.sidebar_items（ctx.ui.registerSidebarItem）：注册左侧活动栏入口（图标 + 面板）。
    //   - order：float 排序坐标，越小越靠前；内置 tasks=1 / files=2 / search=3 / git=5 /
    //     扩展管理=9，未声明时缺省 100（排在全部内置项之后），支持小数（如 4.5）插空。
    //   - badgeCount：>0 时在图标上显示数字角标；Badge 可换成自管订阅刷新的角标组件。
    //   - 返回 Disposable：请收集起来，在 deactivate() 里统一 dispose（见本文件底部）。
    // 其余 11 个前端扩展点（标签类型 / 文件侧栏 / 输入框面板 / 工具视图 / 消息动作 /
    // 提交贡献 / trace / 输出块 / 文件编辑器 / 右键菜单 / 轮末面板）的注册方法同样挂在
    // ctx.ui 上，字段与示例见 docs/plugin-guide/web/ui-extensions.md。
    const sidebarItem: UiSidebarItemDefinition = {
      id: '{{pluginId}}',
      title: '{{pluginName}}',
      icon: React.createElement(SidebarIcon),
      Panel: SidebarPanel,
      order: 100,
    }
    disposables.push(ctx.ui.registerSidebarItem(sidebarItem))
  },

  // 宿主前端当前从不调用 deactivate（loadedPlugins 的 disposables 恒空），但保留标准出口：
  // 一旦将来接入热卸载，这里能把所有注册项从各扩展点注册表里摘除。
  deactivate() {
    for (const d of disposables.splice(0)) {
      d.dispose()
    }
  },
}

export default {{camelName}}Plugin
