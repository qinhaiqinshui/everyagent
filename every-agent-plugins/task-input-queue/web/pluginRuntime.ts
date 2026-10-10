/**
 * task-input-queue 插件运行期上下文持有者（惯例同 task-edit-resend/web/pluginRuntime）。
 * <p>插件 activate 时注入 PluginContext，面板组件经 {@link #getPluginContext()} 取用
 * {@code ctx.ui} 的草稿回填能力（{@code setComposerRawContent} / {@code appendComposerText}）。
 * <p>之所以不从面板 props 拿：composer 上方面板的 props 是宿主的 {@code ComposerPanelCtx}
 * 公共上下文，按约束不携带插件专有/宿主内部能力；{@code ctx.ui} 才是插件侧契约面
 * （见 ARCHITECTURE §11「公共 API 边界」——插件只依赖 `@everyagent/plugin-api` + 运行时 ctx）。
 * <p>返回 null 而非抛错：本插件与宿主同进程加载，未激活即意味着面板本不该存在，
 * 但回填是用户动作，宁可显式提示也不让组件渲染路径炸掉。
 */
import type { PluginContext } from '@everyagent/plugin-api'

let pluginCtx: PluginContext | null = null

export function setPluginContext(ctx: PluginContext): void {
  pluginCtx = ctx
}

/** 取插件上下文；未激活返回 null（调用方须自行降级提示）。 */
export function getPluginContext(): PluginContext | null {
  return pluginCtx
}
