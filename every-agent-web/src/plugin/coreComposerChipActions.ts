/**
 * 核心内置胶囊激活行为注册。
 *
 * 输入框草稿与消息回放里的胶囊点击行为（`ui.composer_chip_actions`）是插件扩展点；
 * 核心自身的 `system.workspace_file`（`@` 文件引用）胶囊同样以**贡献者**身份注册进
 * 同一扩展点（而非在 InlineComposer / AgentMessageThread 里硬编码 kind 分支），
 * 从而与插件胶囊走完全相同的查表 → 回调路径，宿主组件里的 kind 硬编码消失。
 *
 * 与 `coreRoundTailPanels`（核心轮末面板）同一范式：核心内置贡献 = 显式初始化函数 +
 * 注册进插件同款扩展点。
 *
 * 注册时机必须**早于插件 activate()**：main.tsx 在 `loadPlugins()` 之前同步调用。
 * 查表规则是「先注册者胜」（见 PluginDispatcher.findComposerChipAction），故核心在此
 * 同步注册 ⇒ 核心 kind 恒优先，插件不能顶替核心 `system.workspace_file` 的行为。
 *
 * 本扩展点的完整语义见 docs/plugin-guide/web/ui-extensions.md §17/§18。
 */
import type { ChatComposerToken } from '@/types'
import { readWorkspaceFileToken, buildWorkspaceFileTabTarget } from '@/composerToken/workspaceFileToken'
import { openSlashItemDetail } from '@/components/taskComposer/SlashItemDetailPopover'
import { pluginDispatcher } from './PluginDispatcher'

/**
 * 核心贡献的 pluginId。
 * 核心自身贡献的 kind（如 `system.workspace_file`）在查表命中时归此 id。
 */
export const CORE_COMPOSER_CHIP_PLUGIN_ID = 'core'

let initialized = false

/** 注册核心内置胶囊激活行为（幂等：重复调用安全，不会重复注册）。 */
export function initializeCoreComposerChipActions(): void {
  if (initialized) return
  initialized = true
  pluginDispatcher.registerComposerChipAction({
    kind: 'system.workspace_file',
    pluginId: CORE_COMPOSER_CHIP_PLUGIN_ID,
    onActivate: (ctx) => {
      // 复刻迁移前宿主组件里 `system.workspace_file` 的既定语义：从 opaque 串自包含
      // 解析文件引用载荷，有工作区根则打开文件标签页；缺任一条件则回落只读详情层。
      const token: ChatComposerToken = {
        id: '',
        kind: ctx.token.kind,
        label: ctx.token.label,
        summary: ctx.token.summary,
        opaqueText: ctx.token.opaqueText,
      }
      const payload = readWorkspaceFileToken(token)
      const workspaceRoot = ctx.workspaceRoot?.trim() ?? ''
      if (payload && workspaceRoot) {
        const target = buildWorkspaceFileTabTarget(payload)
        // 等价于迁移前的 openGlobalFileTab({ workspaceRoot, filePath: target.filePath })：
        // PluginDispatcher.openFileTab 委托宿主 ShellBridge → Layout.openGlobalFileTab（同名实现）。
        pluginDispatcher.openFileTab(workspaceRoot, target.filePath)
        return
      }
      // 兜底：弹只读详情层（与迁移前一致）。
      openSlashItemDetail(ctx.anchor, { token })
    },
  })
}