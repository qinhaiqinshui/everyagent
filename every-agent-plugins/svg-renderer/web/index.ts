// ea: 前端插件入口：默认导出 PluginModule，activate() 里经 ctx.ui.registerOutputBlock 注册 svg-image 输出块
/**
 * svg-renderer —— 前端插件入口（PluginModule，对标 VSCode 扩展入口）。
 *
 * 在 AI 输出消息中识别成对闭合的 <svg-image>…</svg-image> 段（宿主 RichMessageContent
 * 切段逻辑命中已注册 tag），交给本插件注册的 OutputBlockHandler 渲染。
 *
 * 硬约束（详见 docs/plugin-guide/web/overview-and-loading.md）：
 *   - 类型一律 import type ... from '@everyagent/plugin-api'（纯类型包，零运行时代码）；
 *   - bare import 白名单只有 5 项：react / react-dom / react/jsx-runtime / antd / @ant-design/icons，
 *     运行时由宿主 window.__EA_* 全局提供；禁止 '@/...' 形式的宿主内部路径引用；
 *   - 产物必须是 web/index.js（webMain 只决定 hasWebMain，实际路径由宿主硬编码）。
 */

import type { Disposable, PluginContext, PluginModule } from '@everyagent/plugin-api'
import React from 'react'
import SvgBlock from './SvgBlock'

/** 本插件持有的可释放资源；deactivate() 时统一清理（宿主前端目前不自动卸载，留好出口）。 */
const disposables: Disposable[] = []

const svgRendererPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    // 扩展点 ui.output_blocks（ctx.ui.registerOutputBlock）：
    //   - 第一个参数 tag：命中成对闭合标签名（这里是 svg-image）；
    //   - handler(content, ctx)：收到标签内层文本（即 SVG 源码），返回 ReactNode 渲染。
    //   - 返回 Disposable：请收集起来，在 deactivate() 里统一 dispose（见本文件底部）。
    disposables.push(
      ctx.ui.registerOutputBlock('svg-image', (content) =>
        React.createElement(SvgBlock, { svgSource: content }),
      ),
    )
  },

  // 宿主前端当前从不调用 deactivate（loadedPlugins 的 disposables 恒空），但保留标准出口：
  // 一旦将来接入热卸载，这里能把所有注册项从各扩展点注册表里摘除。
  deactivate() {
    for (const d of disposables.splice(0)) {
      d.dispose()
    }
  },
}

export default svgRendererPlugin
