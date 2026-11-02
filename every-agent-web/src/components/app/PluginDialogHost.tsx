/**
 * 插件模态对话框全局宿主。
 *
 * 对标 UserInteractionHost（ask_user 弹窗）：Layout 全局挂载一次，订阅
 * plugin/dialogBridge store，有活跃请求时渲染遮罩 + 居中浮层 + 插件 Content。
 *
 * 宿主职责（壳）：遮罩点击取消、Esc 取消、打开时程序聚焦浮层根（键盘事件有落点）。
 * 插件职责（Content）：主体内容渲染与交互，经 props.close(result) 收口。
 *
 * 与 composer_above_panel 的分工：
 * - 本组件承接**瞬态模态交互**（showDialog），全局挂载不依附任何 composer/标签页；
 * - composer_above_panel 承接**跟随某个输入框的常驻面板**（如输入队列列表），
 *   两者互补不替代。
 */
import React from 'react'
import { dialogState, resolveDialog, subscribeDialog } from '@/plugin/dialogBridge'
import './pluginDialogHost.css'

export default function PluginDialogHost() {
  const dialog = React.useSyncExternalStore(subscribeDialog, dialogState)
  const rootRef = React.useRef<HTMLDivElement | null>(null)

  // 打开时聚焦浮层根：Esc 等键盘事件挂在根上才有响应（对齐旧 picker 的聚焦时机）。
  React.useEffect(() => {
    if (dialog) {
      rootRef.current?.focus()
    }
  }, [dialog])

  if (!dialog) {
    return null
  }

  const { Content } = dialog

  return (
    <div
      className="plugin-dialog-overlay"
      onMouseDown={(event) => {
        // 点遮罩本身 = 取消（浮层内部的点击不冒泡到这里）
        if (event.target === event.currentTarget) {
          resolveDialog(null)
        }
      }}
    >
      <div
        ref={rootRef}
        className="plugin-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={dialog.title}
        tabIndex={-1}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault()
            resolveDialog(null)
          }
        }}
      >
        <div className="plugin-dialog__header">
          <div className="plugin-dialog__title">{dialog.title}</div>
          {dialog.description ? (
            <div className="plugin-dialog__desc">{dialog.description}</div>
          ) : null}
        </div>
        <div className="plugin-dialog__body">
          <Content close={(result) => resolveDialog(result)} />
        </div>
      </div>
    </div>
  )
}
