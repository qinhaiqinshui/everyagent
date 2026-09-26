/**
 * update-file-view 插件运行期上下文持有者。
 * 插件 activate 时注入 PluginContext，组件经 getPluginContext() 取用。
 */
import type { PluginContext } from '@everyagent/plugin-api'

let pluginCtx: PluginContext | null = null

export function setPluginContext(ctx: PluginContext): void {
  pluginCtx = ctx
}

export function getPluginContext(): PluginContext {
  if (!pluginCtx) {
    throw new Error('update-file-view 插件尚未激活（PluginContext 未注入）')
  }
  return pluginCtx
}
