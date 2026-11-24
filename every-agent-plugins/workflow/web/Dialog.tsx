/**
 * workflow 插件统一弹窗内容组件（wf-dlg-*）。
 *
 * 前置调研结论：@everyagent/plugin-api 是**纯类型包**（零运行时代码），插件侧
 * 没有也无法有跨插件共享的 React 组件；宿主唯一的共用弹窗机制是 ctx.ui.showDialog
 * 的模态壳（PluginDialogHost：遮罩 / 居中 / Esc / 点遮罩取消 / 焦点管理）。壳内的
 * 主体内容此前由 SidebarPanel / ManagePanel / InstancePanel / RunDialog 各自手搓：
 * 三份同构 confirmDialog、三种按钮观感（antd Button / wf-inst-btn 内联红底）、
 * description 在壳头与正文重复渲染两遍、JSON 弹窗 min-width:720px 超出宿主壳
 * 480px 宽被 overflow:hidden 裁切、主体紧贴弹窗左右边缘（壳 body 无 padding）。
 *
 * 本模块 = 插件内的统一弹窗内容层，全部工作流弹窗共用：
 * - 布局件：DialogBody（内边距 + 滚动收口）/ DialogFooter / DialogButton /
 *   DialogError（textarea / pre 以 wf-dlg-textarea / wf-dlg-pre 类直接使用）；
 * - 开窗函数：confirmDialog（确认，danger 默认红）、showManualCopyDialog
 *   （剪贴板被拒的降落伞）、showTextViewDialog（只读长文本 + 复制，查看 JSON 用）。
 *
 * 宽度纪律：宿主壳固定 min(480px, 100vw-48px)，内容一律 width:100% 不自设更宽的
 * min-width（超出壳宽会被裁切）；可滚动中区（textarea / pre）以 flex 收口——
 * flex:1 + min-height 兜底，弹窗永不因内容过高而裁掉页脚。样式走宿主 UI Token
 * （var(--*)，暗色回退值与插件其余 wf-* 同口径），不依赖 antd。
 */
import React from 'react'
import { copyText, copyTextSync } from './clipboard'
import { getPluginContext } from './pluginRuntime'

const DIALOG_STYLE_ID = 'wf-dlg-styles'

/** 统一弹窗样式注入（与插件其余 <style> 注入同策略：幂等，document 缺席静默）。
 *  导出供自绘主体的弹窗（RunDialog 等）组合注入——它们不用 DialogBody 也要能拿到 wf-dlg-*。 */
export function ensureDialogStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(DIALOG_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = DIALOG_STYLE_ID
  style.textContent = `
/* ── 主体容器：内边距对齐壳头（壳 body 无 padding，内容自管 16px）；flex 收口
   使可滚动中区吃满剩余高度、页脚永不被壳的 overflow:hidden 裁掉。 ── */
.wf-dlg { box-sizing: border-box; display: flex; flex-direction: column; gap: 12px; flex: 1; min-height: 0; width: 100%; padding: 2px 16px 14px; font-size: 13px; color: var(--text-primary, #eef1f5); }
.wf-dlg-text { margin: 0; color: var(--text-secondary, #aab3c2); line-height: 1.7; word-break: break-word; }
.wf-dlg-footer { display: flex; justify-content: flex-end; gap: 8px; flex: none; padding-top: 2px; }
/* ── 按钮：与插件面板按钮（wf-manage-btn）同一视觉语言，不依赖 antd ── */
.wf-dlg-btn { display: inline-flex; align-items: center; justify-content: center; gap: 5px; padding: 5px 14px; border: 1px solid var(--border-strong, #3b4658); border-radius: 6px; background: transparent; color: var(--text-secondary, #aab3c2); cursor: pointer; font-size: 12.5px; line-height: 1.4; white-space: nowrap; transition: color 0.15s ease, border-color 0.15s ease, filter 0.15s ease; }
.wf-dlg-btn:hover:not(:disabled) { color: var(--text-primary, #eef1f5); border-color: var(--accent-blue, #61a5ff); }
.wf-dlg-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.wf-dlg-btn--primary { background: var(--accent-green, #41d19c); border-color: var(--accent-green, #41d19c); color: #0b0d12; font-weight: 600; }
.wf-dlg-btn--primary:hover:not(:disabled) { color: #0b0d12; border-color: var(--accent-green, #41d19c); filter: brightness(1.05); }
.wf-dlg-btn--danger { color: var(--accent-red, #ff6b6b); border-color: rgba(255, 107, 107, 0.55); }
.wf-dlg-btn--danger:hover:not(:disabled) { color: var(--accent-red, #ff6b6b); border-color: var(--accent-red, #ff6b6b); filter: brightness(1.08); }
/* ── 错误框：红底描边（与面板 wf-manage-error 同款） ── */
.wf-dlg-error { background: var(--accent-red-dim, rgba(255, 107, 107, 0.14)); border: 1px solid var(--accent-red, #ff6b6b); border-radius: 8px; padding: 8px 10px; color: var(--accent-red, #ff6b6b); font-size: 12.5px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; flex: none; }
/* ── 可滚动中区：flex 收口，内容再高也只在区内滚 ── */
.wf-dlg-textarea { box-sizing: border-box; width: 100%; flex: 1 1 auto; min-height: 10em; resize: vertical; background: var(--bg-primary, #0b0d12); border: 1px solid var(--border, #2a303b); border-radius: 8px; color: var(--text-primary, #eef1f5); padding: 8px 10px; font-size: 12.5px; line-height: 1.6; font-family: var(--font-mono, monospace); }
.wf-dlg-textarea:focus { outline: none; border-color: var(--accent-blue, #61a5ff); }
.wf-dlg-textarea--tall { min-height: 16em; }
.wf-dlg-pre { flex: 1 1 auto; min-height: 6em; margin: 0; overflow: auto; white-space: pre; background: var(--bg-tertiary, #171b24); border: 1px solid var(--border-light, #1b202a); border-radius: 8px; padding: 10px 12px; font-size: 12px; line-height: 1.6; font-family: var(--font-mono, monospace); color: var(--text-primary, #eef1f5); }
/* ── 确认弹窗：警示圆标 + 正文（description 只在此渲染一次，不再给壳头重复） ── */
.wf-dlg-confirm { display: flex; gap: 10px; align-items: flex-start; }
.wf-dlg-confirm__icon { flex: 0 0 auto; display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 999px; background: var(--accent-red-dim, rgba(255, 107, 107, 0.14)); color: var(--accent-red, #ff6b6b); }
.wf-dlg-confirm__icon--muted { background: var(--accent-blue-soft, rgba(97, 165, 255, 0.1)); color: var(--accent-blue, #61a5ff); }
.wf-dlg-confirm__body { flex: 1 1 auto; min-width: 0; padding-top: 3px; }
/* ── 移动端（≤768px）：按钮触控目标加大；textarea 16px 防 iOS 聚焦自动放大 ── */
@media (max-width: 768px) {
  .wf-dlg { padding: 2px 12px 12px; }
  .wf-dlg-btn { min-height: 34px; padding: 6px 16px; font-size: 13px; }
  .wf-dlg-textarea { font-size: 16px; }
}
`
  document.head.appendChild(style)
}

// ─── 布局件 ───────────────────────────────────────────────────────────────

/** 弹窗主体容器：统一内边距 + flex 收口（样式注入随挂载幂等触发）。 */
export function DialogBody({ children, className }: { children: React.ReactNode; className?: string }) {
  React.useEffect(ensureDialogStyles, [])
  return <div className={className ? `wf-dlg ${className}` : 'wf-dlg'}>{children}</div>
}

/** 页脚：右对齐按钮行（不参与滚动，永远贴底可见）。 */
export function DialogFooter({ children }: { children: React.ReactNode }) {
  return <div className="wf-dlg-footer">{children}</div>
}

export interface DialogButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  /** 视觉变体：default 描边 / primary 绿色实心（确认类）/ danger 红色描边（破坏性）。 */
  variant?: 'default' | 'primary' | 'danger'
}

/** 弹窗按钮：三种变体统一观感（与插件面板按钮同一视觉语言，不依赖 antd）。 */
export function DialogButton({ variant = 'default', className, type = 'button', ...rest }: DialogButtonProps) {
  const variantClass = variant === 'default' ? '' : ` wf-dlg-btn--${variant}`
  return <button type={type} className={`wf-dlg-btn${variantClass}${className ? ` ${className}` : ''}`} {...rest} />
}

/** 错误回显框（多行文本原样保留，区内滚动交给外层）。 */
export function DialogError({ children }: { children: React.ReactNode }) {
  return <div className="wf-dlg-error">{children}</div>
}

// ─── 确认弹窗 ─────────────────────────────────────────────────────────────

/**
 * 统一确认弹窗（取代 SidebarPanel / ManagePanel / InstancePanel 三份手搓实现）。
 *
 * description 只在正文渲染一次（不再传给壳头副标题，消除旧版「同一段话显示两
 * 遍」）；danger=true（默认）配警示圆标 + 红色确认钮，false 配蓝色中性圆标 +
 * 绿色主钮。宿主缺席 showDialog（极旧版本）时降级 window.confirm（旧版同款）。
 */
export function confirmDialog(
  title: string,
  description: string,
  okText = '确认',
  danger = true,
): Promise<boolean> {
  ensureDialogStyles()
  const ctx = getPluginContext()
  if (typeof ctx.ui.showDialog !== 'function') {
    return Promise.resolve(window.confirm(`${title}\n\n${description}`))
  }
  return ctx
    .ui.showDialog<boolean>({
      title,
      Content: ({ close }) => (
        <DialogBody>
          <div className="wf-dlg-confirm">
            <span className={danger ? 'wf-dlg-confirm__icon' : 'wf-dlg-confirm__icon wf-dlg-confirm__icon--muted'}>
              <AlertIcon size={15} />
            </span>
            <p className="wf-dlg-text wf-dlg-confirm__body">{description}</p>
          </div>
          <DialogFooter>
            <DialogButton onClick={() => close(false)}>取消</DialogButton>
            <DialogButton variant={danger ? 'danger' : 'primary'} onClick={() => close(true)}>
              {okText}
            </DialogButton>
          </DialogFooter>
        </DialogBody>
      ),
    })
    .then(r => r === true)
}

// ─── 手动复制降落伞 ───────────────────────────────────────────────────────

/**
 * 剪贴板写入失败时的「降落伞」模态：把文本摆进**已全选**的只读文本域，用户直接
 * Ctrl+C（⌘C）即可（自 SidebarPanel 迁入统一弹窗层；插件不得引宿主模块，经
 * ctx.ui.showDialog 挂宿主模态壳）。
 *
 * 「重试复制」按钮本身是一次**新的点击手势**，其同步 copyTextSync 几乎必然成功
 * （这正是非安全上下文下 execCommand 需要的手势）。导出供 SidebarPanel /
 * ManagePanel 复制失败兜底共用。
 */
export function showManualCopyDialog(title: string, text: string): void {
  ensureDialogStyles()
  const ctx = getPluginContext()
  if (typeof ctx.ui.showDialog !== 'function') {
    // 宿主模态缺席：window.prompt 自带全选文本域，可直接 Ctrl+C / ⌘C。
    window.prompt(title, text)
    return
  }
  void ctx.ui.showDialog({
    title,
    description:
      '浏览器拒绝了剪贴板访问：下方文本已全选，可直接按 Ctrl+C（⌘C）复制，或点「重试复制」。',
    Content: ({ close }) => <ManualCopyContent close={close} text={text} />,
  })
}

function ManualCopyContent({ close, text }: { close: (result: null) => void; text: string }) {
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null)
  const [copied, setCopied] = React.useState(false)
  // 打开即全选：用户无需拖选，直接按键复制。
  React.useEffect(() => {
    const node = textareaRef.current
    if (!node) return
    node.focus()
    node.select()
  }, [])
  return (
    <DialogBody>
      <textarea
        ref={textareaRef}
        className="wf-dlg-textarea"
        style={{ wordBreak: 'break-all' }}
        readOnly
        value={text}
        spellCheck={false}
        onFocus={event => event.currentTarget.select()}
      />
      <DialogFooter>
        <DialogButton onClick={() => close(null)}>关闭</DialogButton>
        <DialogButton
          variant="primary"
          onClick={() => {
            // 本次点击手势内同步写；同步路径失败（极少数环境禁用 execCommand）
            // 再退回异步 API 并落 copied 态。
            if (copyTextSync(text)) {
              setCopied(true)
              return
            }
            void copyText(text).then(setCopied)
          }}
        >
          {copied ? '已复制' : '重试复制'}
        </DialogButton>
      </DialogFooter>
    </DialogBody>
  )
}

// ─── 只读长文本视图（查看 JSON 等） ────────────────────────────────────────

/**
 * 只读文本弹窗：等宽 pre（区内滚动，超宽行横向滚动而非裁切）+ 页脚「复制 / 关闭」。
 * 复制走 copyText（异步 API → execCommand 同步兜底），双双失败弹手动复制降落伞
 * ——非安全上下文（局域网 http）也能复制。取代 ManagePanel 内联的「查看 JSON」弹窗。
 */
export function showTextViewDialog(opts: {
  title: string
  /** 壳头副标题（如 `wf_… · 只读视图`）。 */
  description?: string
  /** 展示文本（JSON 需调用方先行 stringify）。 */
  text: string
  /** 复制失败降落伞弹窗的标题（如「复制定义 JSON」）。 */
  copyTitle: string
  /** 成功/失败 toast（调用方注入，保持各自面板的 toast 出口）。 */
  toast: (message: string, type?: 'success' | 'error' | 'info' | 'warning') => void
}): Promise<null | undefined> {
  ensureDialogStyles()
  const ctx = getPluginContext()
  if (typeof ctx.ui.showDialog !== 'function') {
    opts.toast('当前宿主不支持插件对话框，无法查看内容', 'error')
    return Promise.resolve(null)
  }
  return ctx.ui.showDialog<null>({
    title: opts.title,
    description: opts.description,
    Content: ({ close }) => (
      <DialogBody>
        <pre className="wf-dlg-pre">{opts.text}</pre>
        <DialogFooter>
          <DialogButton
            onClick={() => {
              // copyText：异步 API → execCommand 同步兜底（点击手势内调用，
              // 手势仍在，execCommand 大概率成功）；双双失败弹手动复制降落伞。
              void copyText(opts.text).then(ok => {
                if (ok) {
                  opts.toast('已复制到剪贴板', 'success')
                } else {
                  showManualCopyDialog(opts.copyTitle, opts.text)
                }
              })
            }}
          >
            复制
          </DialogButton>
          <DialogButton variant="primary" onClick={() => close(null)}>
            关闭
          </DialogButton>
        </DialogFooter>
      </DialogBody>
    ),
  })
}

// ─── 内部图标（警示圆标；自持 16 系 SVG，stroke=currentColor 继承壳色） ─────

function AlertIcon({ size = 16 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M8 1.8 14.4 13H1.6L8 1.8Z" />
      <path d="M8 6v3.2" />
      <path d="M8 11.2v.01" />
    </svg>
  )
}
