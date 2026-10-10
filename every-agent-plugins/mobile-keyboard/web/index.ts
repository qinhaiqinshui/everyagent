/**
 * 移动端键盘增强插件——PluginModule 入口(纯 Web 插件,无 worker 端)。
 *
 * 经 worker plugin.list 发现、plugin.webSource RPC 拉取 esbuild 预编译产物动态加载。
 * 激活后往 body 挂一个仿 AssistiveTouch 的悬浮球,仅在「移动端视口 + 存在可见终端」
 * 时显示;点开是方向键小键盘,按键以合成 KeyboardEvent 派发给 xterm 的内部 textarea,
 * 由 xterm 生成方向键转义序列发往 PTY。单纯模拟按键,不感知焦点与光标位置。
 */

import type { PluginModule } from '@everyagent/plugin-api'
import { createMobileKeypad } from './floatball'
import type { MobileKeypadController } from './floatball'
import './floatball.css'

let keypad: MobileKeypadController | null = null

const mobileKeyboardPlugin: PluginModule = {
  activate(ctx) {
    keypad = createMobileKeypad({
      storage: ctx.storage,
      getActiveTabType: () => ctx.ui.getActiveTab()?.tabType ?? null,
      subscribeTabActivated: (listener) => ctx.events.on('workspace-tab-activated', listener),
      subscribeTabClosed: (listener) => ctx.events.on('workspace-tab-closed', listener),
    })
  },
  deactivate() {
    keypad?.destroy()
    keypad = null
  },
}

export default mobileKeyboardPlugin
