/**
 * 剪贴板复制工具（插件本地实现——插件不得 `@/` 引宿主源码，自宿主
 * src/utils/clipboard.ts 同构复制核心两入口；svg-renderer 插件同款自持范式）。
 *
 * 写系统剪贴板只有两条路，二者对「用户手势」的要求不同：
 * - **异步 Clipboard API**（navigator.clipboard.writeText）：只在**安全上下文**
 *   （https / localhost / Electron）存在；局域网 http 访问下是 undefined，且即便
 *   存在也可能被拒（权限、文档失焦）。
 * - **同步 document.execCommand('copy')**：任何上下文可用，但**必须在用户手势
 *   （点击事件）所在的同一个 task 内**调用——一旦跨过 await 的网络往返（如先 RPC
 *   取路径再复制），手势失效，它返回 false。
 *
 * 因此两个入口各司其职：
 * - {@link copyText}：通用入口，先异步 API、失败落同步兜底，失败返回 false（调用方
 *   toast 提示 + 弹手动复制降落伞，绝不误报「已复制」）。
 * - {@link copyTextSync}：仅用于**点击回调里同步调用**的场合——「先取数据再复制」的
 *   入口应把数据预取到点击之前（见 workflowGateway 的 instance.dataDir 预取）。
 */

/** 同步写剪贴板（隐藏 textarea + execCommand），返回是否成功；必须在用户手势 task 内调用。 */
export function copyTextSync(text: string): boolean {
  if (typeof document === 'undefined' || !document.body) {
    return false
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  // readonly 防 iOS 聚焦弹软键盘；fixed + 屏外坐标防页面滚动/焦点跳动。
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '-9999px'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  try {
    textarea.focus()
    textarea.select()
    textarea.setSelectionRange(0, text.length)
    // execCommand 返回 false 时复制实际失败，如实返回让 UI 不误报「已复制」。
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(textarea)
  }
}

/** 异步 Clipboard API 写入；不可用（含非安全上下文）或被拒时返回 false，不抛。 */
async function writeViaClipboardApi(text: string): Promise<boolean> {
  if (typeof navigator === 'undefined' || !navigator.clipboard?.writeText) {
    return false
  }
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

/**
 * 复制文本到系统剪贴板（通用入口），失败返回 false（不抛——插件侧 toast + 手动复制
 * 降落伞由调用方收口）。非安全上下文下不先 await，点击回调内直调仍保住手势。
 */
export async function copyText(text: string): Promise<boolean> {
  const hasAsyncApi = typeof navigator !== 'undefined' && Boolean(navigator.clipboard?.writeText)
  if (!hasAsyncApi) {
    return copyTextSync(text)
  }
  if (await writeViaClipboardApi(text)) {
    return true
  }
  // 异步 API 被拒（权限/失焦）→ 同步兜底；跨过 await 手势已失效则如实返回 false。
  return copyTextSync(text)
}
