/**
 * 核心内置用户消息动作注册。
 *
 * 用户消息气泡旁的动作区（`ui.user_message_actions`）是插件扩展点；核心自身的
 * 「复制原始内容」按钮同样以**贡献者**身份注册进同一扩展点（而非在
 * AgentMessageThread 里硬编码），从而与插件动作（如 task-edit-resend 的编辑按钮）
 * 走完全相同的渲染路径——宿主遍历 listRegisteredUserMessageActions 渲染，
 * 不感知动作业务语义。
 *
 * 与 `coreRoundTailPanels`（核心轮末「复制本轮结果」面板）、`coreComposerChipActions`
 * （核心胶囊激活行为）、`coreEditorContextMenuActions`（核心编辑器右键「复制」项）
 * 同一范式：核心内置贡献 = 显式初始化函数 + 注册进插件同款扩展点。
 *
 * 注册时机必须**早于插件 activate()**：main.tsx 在 `loadPlugins()` 之前同步调用，
 * 故核心动作恒排在插件动作之前（用户消息动作区最左）。
 *
 * 动机：用户消息的 rawContent 含 `[[[[...]]]]` opaque token 串，复制到剪贴板后
 * 粘贴回输入框时 InlineComposer 的 handlePaste 会解析 opaque 串并还原胶囊（见
 * InlineComposer §paste 文本分支注释）。此前粘贴逻辑已就绪，但缺少复制入口。
 */
import { pluginDispatcher } from './PluginDispatcher'
import CopyRawContentButton from '@/components/task/CopyRawContentButton'

/**
 * 核心「复制原始内容」动作的 id。
 * 全局唯一动作 id（与核心编辑器动作前缀 `core.` 同口径）。
 */
export const CORE_COPY_RAW_CONTENT_ACTION_ID = 'core.copy-raw-content'

let initialized = false

/** 注册核心内置用户消息动作（幂等：重复调用安全，不会重复注册）。 */
export function initializeCoreUserMessageActions(): void {
  if (initialized) return
  initialized = true
  pluginDispatcher.registerUserMessageAction({
    id: CORE_COPY_RAW_CONTENT_ACTION_ID,
    Component: CopyRawContentButton,
  })
}
