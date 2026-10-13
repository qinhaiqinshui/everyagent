/**
 * 核心内置轮末面板注册。
 *
 * 轮末工具栏的「轮末展示区」(`ui.round_tail_panels`)是插件扩展点；核心自身的
 * 「复制本轮结果」按钮同样以**贡献者**身份注册进同一扩展点（而非在 TaskRoundsPanel
 * 里硬编码），从而与插件面板走完全相同的渲染路径与栅格 —— 两者同处轮末工具栏行、
 * 共享同一套对齐规则，host 不硬编码任何一块轮末内容。
 *
 * 与 `traceTypeRegistry.initializeTraceTypes()` 同一范式：核心内置贡献 = 显式初始化
 * 函数 + 注册进插件同款扩展点。
 *
 * 注册时机必须**早于插件 activate()**：main.tsx 在 `loadPlugins()` 之前同步调用，
 * 而插件注册在异步的 activate 里、晚于首屏渲染，故核心面板恒排在插件面板之前
 * （即工具栏最左：先「复制」后「文件变更」）。
 */
import { pluginDispatcher } from './PluginDispatcher'
import RoundCopyPanel from '@/components/task/RoundCopyPanel'

/**
 * 核心「复制本轮结果」面板的 pluginId。
 * 同时用作 React key 与 host 侧栅格判定（该面板是按钮而非面板，见 TaskRoundsPanel）。
 */
export const CORE_ROUND_COPY_PANEL_ID = 'core.round-copy'

let initialized = false

/** 注册核心内置轮末面板（幂等：重复调用安全，不会重复注册）。 */
export function initializeCoreRoundTailPanels(): void {
  if (initialized) return
  initialized = true
  pluginDispatcher.registerRoundTailPanel({
    pluginId: CORE_ROUND_COPY_PANEL_ID,
    Component: RoundCopyPanel,
  })
}