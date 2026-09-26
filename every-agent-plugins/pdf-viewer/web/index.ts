/**
 * PDF 预览插件——PluginModule 入口(纯 Web 插件,无 worker 端)。
 *
 * 经 builtInPlugins.ts 自动发现加载,通过 ctx.ui.registerFileContentEditor
 * 注册 .pdf 文件的内容编辑器;核心编辑器注册表按扩展名匹配时自动命中。
 *
 * 浏览器与桌面(Electron)行为一致:在文件标签页内以原生 <iframe> 渲染 PDF
 * (Chromium 内置 PDF Viewer),不新开浏览器标签页。
 */

import type { PluginContext, PluginModule, PluginFileContentEditorDescriptor } from '@everyagent/plugin-api'
import { descriptor } from './PdfFileEditor'

const pdfViewerPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    // descriptor 直接使用 plugin-api 的 PluginFileContentEditorDescriptor 类型，
    // 与 ctx.ui.registerFileContentEditor 签名一致，无需强转。
    ctx.ui.registerFileContentEditor(descriptor)
  },
}

export default pdfViewerPlugin
