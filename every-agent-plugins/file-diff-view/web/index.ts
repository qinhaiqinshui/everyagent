/**
 * file-diff-view 插件——PluginModule 入口（纯 Web 插件，无 worker 端）。
 *
 * 经 builtInPlugins.ts 自动发现加载，通过 ctx.ui.registerToolCallDetailView
 * 注册 update_file 的展开态详情接管：用工具参数 oldcontent → content 生成行级
 * diff，内嵌在工具展开详情里，展开即直接看变更，无需打开文件对比。
 *
 * 语义契约：render 返回 content 时 FileToolEntry 以其替代默认参数块
 * （头部路径 chip 与错误块仍由核心渲染）；参数缺失 / 非字符串时返回 null，
 * 回落核心默认渲染（保证异常场景可诊断）。JSX 构建在 UpdateFileDiff.tsx，
 * 本入口保持纯逻辑（与 auth-review / pdf-viewer 入口同构）。
 */

import type { PluginContext, PluginModule } from '@everyagent/plugin-api'
import { buildUpdateFileDiffEnhancement } from './UpdateFileDiff'

const fileDiffViewPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    ctx.ui.registerToolCallDetailView({
      pluginId: 'file-diff-view',
      toolName: 'update_file',
      render: ({ args }) => buildUpdateFileDiffEnhancement(args),
    })
  },
}

export default fileDiffViewPlugin
