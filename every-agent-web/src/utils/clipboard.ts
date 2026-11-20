/**
 * 剪贴板复制工具（全站唯一实现）。
 *
 * 写系统剪贴板只有两条路，二者对「用户手势」的要求不同——这是本模块存在的全部理由：
 * - **异步 Clipboard API**（`navigator.clipboard.writeText`）：只存在于**安全上下文**
 *   （https / localhost / Electron 窗口）。局域网 http 访问下 `navigator.clipboard`
 *   直接是 undefined，只剩 ②；即便存在也可能被拒（权限、文档失焦）。
 * - **同步 `document.execCommand('copy')`**：任何上下文都可用，但**必须在用户手势
 *   （点击事件）所在的同一个 task 内**调用。一旦跨过 `await` 的网络往返（例如先 RPC
 *   取数据再复制），手势失效，它返回 false。
 *
 * 因此两个入口各司其职：
 * - {@link copyText}：通用入口，先异步 API、失败落同步兜底，失败抛
 *   {@link ClipboardWriteError}（调用方据此提示，绝不误报「已复制」）。可用性检查同步在
 *   前，非安全上下文下不会先 await——点击回调内直调仍保住手势。
 * - {@link copyTextSync}：仅用于**点击回调里同步调用**的场合（需要「先取数据再复制」的
 *   入口应把数据预取到点击之前，见 taskQueryService 的 task.dataDir 预取）。
 */
export class ClipboardWriteError extends Error {
  constructor(message = '浏览器拒绝了剪贴板访问') {
    super(message)
    this.name = 'ClipboardWriteError'
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
    // 权限拒绝 / 文档失焦等 → 交给同步兜底。
    return false
  }
}

/**
 * 同步写剪贴板（隐藏 textarea + execCommand('copy')），返回是否成功。
 *
 * **必须在用户手势所处的同一个 task 内调用**（点击回调里直调，中间不要有网络 await）；
 * 手势已失效时 execCommand 返回 false——这是浏览器的硬限制，如实上报即可。
 */
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
    // execCommand 返回 false 时复制实际失败，必须如实返回让 UI 不误报「已复制」。
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(textarea)
  }
}

/**
 * 复制文本到系统剪贴板（通用入口）。失败抛 {@link ClipboardWriteError}。
 *
 * 从 CodeBlock 的内联 copyText 提取共享，供代码块、轮结果复制按钮等复用。
 */
export async function copyText(text: string): Promise<void> {
  const hasAsyncApi = typeof navigator !== 'undefined' && Boolean(navigator.clipboard?.writeText)
  if (!hasAsyncApi) {
    // 非安全上下文(局域网 http):只有同步 execCommand 一条路,且中间不 await——手势必在。
    if (copyTextSync(text)) return
    throw new ClipboardWriteError()
  }
  if (await writeViaClipboardApi(text)) {
    return
  }
  // 异步 API 被拒(权限/失焦)→ 同步兜底;此时若跨过了 await 手势已失效则如实抛错。
  if (copyTextSync(text)) {
    return
  }
  throw new ClipboardWriteError()
}
