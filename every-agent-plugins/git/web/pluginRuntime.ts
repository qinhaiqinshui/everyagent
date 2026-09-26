/**
 * git 插件运行期上下文持有者。
 *
 * 插件 activate 时注入 PluginContext；组件/网关经 getPluginContext() 取用
 * （插件代码不得引用宿主 web 模块，一切宿主能力经 ctx 暴露）。
 */
import type { PluginContext } from '@everyagent/plugin-api'

let pluginCtx: PluginContext | null = null

export function setPluginContext(ctx: PluginContext): void {
  pluginCtx = ctx
}

export function getPluginContext(): PluginContext {
  if (!pluginCtx) {
    throw new Error('git 插件尚未激活（PluginContext 未注入）')
  }
  return pluginCtx
}
