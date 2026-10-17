/**
 * 移动端虚拟鼠标插件——PluginModule 入口(纯 Web 插件,无 worker 端)。
 *
 * 经 worker plugin.list 发现、plugin.webSource RPC 拉取 esbuild 预编译产物动态加载。
 * 激活后往 body 挂一个仿 AssistiveTouch 的悬浮球,移动端视口(宽度 <= 768)常显:
 * 收起态为鼠标图标圆球;展开态为四段圆弧围成的圆环——上/下模拟键盘方向键
 * (合成 KeyboardEvent 派发给焦点元素,终端场景由 xterm 转成转义序列发往 PTY),
 * 左/右模拟鼠标左/右键(在虚拟光标位置合成 mousedown/mousemove/mouseup/contextmenu,
 * 驱动宿主文件树的框选与拖动移动),中心鼠标图标拖动即移动虚拟光标位置。
 */

import type { PluginModule } from '@everyagent/plugin-api'
import { createMobileKeypad } from './floatball'
import type { MobileKeypadController } from './floatball'
import './floatball.css'

let keypad: MobileKeypadController | null = null

const mobileKeyboardPlugin: PluginModule = {
  activate() {
    keypad = createMobileKeypad({})
  },
  deactivate() {
    keypad?.destroy()
    keypad = null
  },
}

export default mobileKeyboardPlugin
