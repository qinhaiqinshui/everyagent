/**
 * 核心内置编辑器动作菜单项注册。
 *
 * 编辑器内容区动作菜单（`ui.editor_context_menu_actions`）是插件扩展点；核心自身的
 * 「复制」项同样以**贡献者**身份注册进同一扩展点（而非在 FileTabPage 里硬编码内置项），
 * 从而与插件项走完全相同的 order 排序 → isVisible 过滤 → invoke 路径，宿主组件零特判。
 *
 * 与 `coreComposerChipActions`（核心胶囊激活行为）、`coreRoundTailPanels`（核心轮末
 * 面板）同一范式：核心内置贡献 = 显式初始化函数 + 注册进插件同款扩展点。
 *
 * 注册时机必须**早于插件 activate()**：main.tsx 在 `loadPlugins()` 之前同步调用。
 * 动机（为什么必须有这一项）：
 *   1. 桌面右键被接管（preventDefault + 统一菜单）后，原生菜单里的「复制」随之丢失；
 *   2. 触屏（iOS/Android Safari）长按选中的原生 callout 在滚动/交互后会消失，此时
 *      统一菜单是唯一入口——没有复制项，移动端将无法复制选中内容。
 * order=10（< 插件缺省 100）⇒ 恒排在插件项之前，与原生菜单「复制居首」的惯例一致。
 *
 * 本扩展点的完整语义见 docs/plugin-guide/web/ui-extensions.md §16。
 */
import { CopyIcon } from '@/components/shared/AppGlyphs'
import { antdMessage } from '@/utils/appAntdBridge'
import { pluginDispatcher } from './PluginDispatcher'

/** 核心贡献的动作 id 前缀（与核心胶囊 pluginId 'core' 同口径）。 */
const CORE_EDITOR_ACTION_PREFIX = 'core.'

let initialized = false

/**
 * 把文本写入系统剪贴板；返回是否成功。
 *
 * 优先走异步剪贴板 API——仅在**安全上下文**（https / localhost）可用；本应用常经
 * 局域网 http 访问（手机访问开发机/自建主机的典型形态），此时 `navigator.clipboard`
 * 为 undefined，必须走 `execCommand('copy')` 兜底：临时 textarea 离屏挂载 → select →
 * 复制 → 移除。iOS Safari 上该方法在本次点击手势内有效（菜单项 onSelect 即手势内）。
 */
async function writeClipboardText(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 权限拒绝等 → 落到 execCommand 兜底。
  }
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    // readonly 防 iOS 聚焦弹软键盘；fixed + 屏外坐标防页面滚动跳动。
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.top = '-9999px'
    document.body.appendChild(textarea)
    textarea.select()
    textarea.setSelectionRange(0, text.length)
    const copied = document.execCommand('copy')
    document.body.removeChild(textarea)
    return copied
  } catch {
    return false
  }
}

/** 注册核心内置编辑器动作（幂等：重复调用安全，不会重复注册）。 */
export function initializeCoreEditorContextMenuActions(): void {
  if (initialized) return
  initialized = true
  pluginDispatcher.registerEditorContextMenuAction({
    id: `${CORE_EDITOR_ACTION_PREFIX}copy-selection`,
    label: '复制',
    icon: <CopyIcon size={13} />,
    order: 10,
    // 宿主闸已保证菜单只在有选区时构建，这里显式重复同一判据（防御性，保持自洽）。
    // 触屏设备隐藏：移动端与系统原生选择菜单错位共存（见 FileTabPage 触屏触发逻辑），
    // 系统菜单已自带「拷贝」，双菜单各有一个复制入口属冗余。
    isVisible: (ctx) =>
      ctx.selection.text.length > 0
      && !window.matchMedia('(hover: none) and (pointer: coarse)').matches,
    invoke: (ctx) => {
      const text = ctx.selection.text
      if (text.length === 0) return
      // invoke 为同步签名，异步复制结果经 toast 反馈（antd message 为模块级入口，
      // 非 React 上下文可调；见 utils/appAntdBridge）。
      void writeClipboardText(text).then((copied) => {
        if (copied) {
          antdMessage('success', '已复制选中内容')
        } else {
          antdMessage('error', '复制失败：浏览器拒绝了剪贴板访问')
        }
      })
    },
  })
}
