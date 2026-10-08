/**
 * 移动端键盘增强悬浮球控制器。
 *
 * 职责(与宿主 12 个 UI 扩展点无关——它们没有「全局覆盖层」扩展点,本插件是
 * 扩展点之外自绘全局 UI 的先例,自挂 document.body 并自管生命周期):
 * - 悬浮球:仿 iOS AssistiveTouch,毛玻璃圆球,可拖动、松手吸附左右屏幕边缘、
 *   位置经 ctx.storage 持久化;单击展开/收起方向键面板。
 * - 显隐条件:移动端视口(宽度 <= 768,与宿主 useResponsiveViewport 同口径)
 *   且页面上存在「可见的」终端(.xterm 元素,非 display:none——工作区标签
 *   常驻 DOM、非激活态 display:none,Layout.tsx 按 style 切换,故必须观察
 *   attributes)。观察源:body 级 MutationObserver(排除自身子树) + resize
 *   + workspace-tab-closed 领域事件,统一 rAF 节流刷新。
 * - 按键模拟:向可见终端的 .xterm-helper-textarea 派发合成 KeyboardEvent
 *   (keydown + keyup,携带 legacy keyCode)。xterm 的 keydown 监听不检查
 *   isTrusted,evaluateKeyboardEvent 按 keyCode 37/38/39/40 生成
 *   \x1b[D/A/C/B 转义序列,经宿主 term.input 通道发往 PTY——即等价于用户
 *   敲下物理方向键。单纯发按键,不管焦点与光标在哪。
 */

/** 与 PluginStorage 兼容的窄接口(结构类型,避免依赖类型包)。 */
interface KeypadStorage {
  get<T>(key: string, defaultValue?: T): T | undefined
  set(key: string, value: unknown): void
}

/** 与 ctx.events.on 返回的 Disposable 兼容的窄接口。 */
interface Unsubscribe {
  dispose(): void
}

interface MobileKeypadOptions {
  storage: KeypadStorage
  /** 订阅宿主 workspace-tab-closed 领域事件(标签关闭后立刻刷新显隐)。 */
  subscribeTabClosed: (listener: () => void) => Unsubscribe
}

export interface MobileKeypadController {
  destroy(): void
}

/** 与宿主 useResponsiveViewport 的 MOBILE_MAX_WIDTH 同口径。 */
const MOBILE_MAX_WIDTH = 768
/** 位移超过该值才视为拖动(否则算点击)。 */
const DRAG_THRESHOLD_PX = 8
/** 悬浮球/面板吸附与钳制时离视口边缘的最小留白。 */
const EDGE_MARGIN_PX = 12
/** 长按方向键的连发启动延迟。 */
const LONGPRESS_DELAY_MS = 350
/** 连发间隔。 */
const REPEAT_INTERVAL_MS = 110
const POS_STORAGE_KEY = 'floatball.pos'
/** 球心默认落点(视口右下区域,归一化坐标)。 */
const DEFAULT_POS = { xf: 0.94, yf: 0.78 }

type ArrowDir = 'up' | 'down' | 'left' | 'right'

interface ArrowDef {
  keyCode: number
  key: string
  aria: string
}

const ARROWS: Record<ArrowDir, ArrowDef> = {
  up: { keyCode: 38, key: 'ArrowUp', aria: '上方向键' },
  down: { keyCode: 40, key: 'ArrowDown', aria: '下方向键' },
  left: { keyCode: 37, key: 'ArrowLeft', aria: '左方向键' },
  right: { keyCode: 39, key: 'ArrowRight', aria: '右方向键' },
}

/** 找到当前可见终端的 xterm 输入 textarea;工作区标签常驻 DOM,隐藏标签 offsetWidth 为 0。 */
function findVisibleTerminalTextarea(): HTMLTextAreaElement | null {
  const terms = document.querySelectorAll<HTMLElement>('.xterm')
  for (const term of terms) {
    if (term.offsetWidth <= 0 || term.offsetHeight <= 0) continue
    const textarea = term.querySelector<HTMLTextAreaElement>('.xterm-helper-textarea')
    if (textarea) return textarea
  }
  return null
}

/** 是否处于移动端视口(与宿主 useResponsiveViewport 同口径:宽度 <= 768)。 */
function isMobileViewport(): boolean {
  return window.innerWidth <= MOBILE_MAX_WIDTH
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(Math.max(v, min), max)
}

function clamp01(v: number): number {
  return clamp(v, 0.02, 0.98)
}

/** 方向键三角箭头(SVG path,等腰三角形朝指定方向)。 */
function arrowPath(dir: ArrowDir): string {
  const cx = 12
  const cy = 12
  const r = 6.5
  const half = Math.PI / 5
  // 三个顶点:尖端 + 两个底角,按方向旋转
  const angles: Record<ArrowDir, number> = { up: -90, right: 0, down: 90, left: 180 }
  const deg = angles[dir]
  const rad = (deg * Math.PI) / 180
  const tipX = cx + r * Math.cos(rad)
  const tipY = cy + r * Math.sin(rad)
  const b1X = cx + r * Math.cos(rad + Math.PI - half)
  const b1Y = cy + r * Math.sin(rad + Math.PI - half)
  const b2X = cx + r * Math.cos(rad + Math.PI + half)
  const b2Y = cy + r * Math.sin(rad + Math.PI + half)
  return `M ${tipX.toFixed(2)} ${tipY.toFixed(2)} L ${b1X.toFixed(2)} ${b1Y.toFixed(2)} L ${b2X.toFixed(2)} ${b2Y.toFixed(2)} Z`
}

function createArrowIcon(dir: ArrowDir): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  svg.setAttribute('viewBox', '0 0 24 24')
  svg.setAttribute('width', '26')
  svg.setAttribute('height', '26')
  svg.classList.add('mk-arrow-icon')
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path')
  path.setAttribute('d', arrowPath(dir))
  path.setAttribute('fill', 'currentColor')
  svg.appendChild(path)
  return svg
}

/** 悬浮球图标:四向十字箭头。 */
function createBallIcon(): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  svg.setAttribute('viewBox', '0 0 24 24')
  svg.setAttribute('width', '30')
  svg.setAttribute('height', '30')
  svg.classList.add('mk-ball-icon')
  for (const dir of ['up', 'down', 'left', 'right'] as ArrowDir[]) {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path')
    // 缩小到 0.52 并平移,四个小三角组成十字
    path.setAttribute('d', arrowPath(dir))
    path.setAttribute('fill', 'currentColor')
    path.setAttribute('transform', 'translate(12 12) scale(0.52) translate(-12 -12)')
    svg.appendChild(path)
  }
  return svg
}

export function createMobileKeypad(options: MobileKeypadOptions): MobileKeypadController {
  const { storage } = options

  // ── DOM 组装 ──────────────────────────────────────────────────────────────
  const root = document.createElement('div')
  root.className = 'mk-root mk-hidden'

  const ball = document.createElement('div')
  ball.className = 'mk-ball'
  ball.setAttribute('role', 'button')
  ball.setAttribute('tabindex', '-1')
  ball.setAttribute('aria-label', '键盘增强:展开方向键')
  ball.appendChild(createBallIcon())
  root.appendChild(ball)

  const panel = document.createElement('div')
  panel.className = 'mk-panel mk-hidden'
  panel.setAttribute('role', 'group')
  panel.setAttribute('aria-label', '方向键小键盘')

  const grid: Array<[number, number, 'up' | 'down' | 'left' | 'right' | 'collapse' | 'blank']> = [
    [0, 0, 'blank'], [1, 0, 'up'], [2, 0, 'blank'],
    [0, 1, 'blank'], [1, 1, 'collapse'], [2, 1, 'blank'],
    [0, 2, 'blank'], [1, 2, 'down'], [2, 2, 'blank'],
    // left/right 独立定位在 3x3 网格中行两端之外,用 grid-area 另行放置
  ]
  for (const [col, row, kind] of grid) {
    if (kind === 'blank') {
      const cell = document.createElement('div')
      cell.className = 'mk-cell mk-blank'
      cell.style.gridArea = `${row + 1} / ${col + 1}`
      panel.appendChild(cell)
      continue
    }
    if (kind === 'collapse') {
      const btn = document.createElement('div')
      btn.className = 'mk-key mk-collapse'
      btn.setAttribute('role', 'button')
      btn.setAttribute('aria-label', '收起键盘')
      btn.style.gridArea = `${row + 1} / ${col + 1}`
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
      svg.setAttribute('viewBox', '0 0 24 24')
      svg.setAttribute('width', '22')
      svg.setAttribute('height', '22')
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path')
      path.setAttribute('d', arrowPath('down'))
      path.setAttribute('fill', 'currentColor')
      path.setAttribute('transform', 'translate(12 12) scale(0.8) translate(-12 -12)')
      svg.appendChild(path)
      btn.appendChild(svg)
      btn.addEventListener('pointerdown', (e) => {
        e.stopPropagation()
        e.preventDefault()
        btn.setPointerCapture(e.pointerId)
        setPressedVisual(btn, true)
      })
      btn.addEventListener('pointerup', () => {
        setPressedVisual(btn, false)
        collapse()
      })
      btn.addEventListener('pointercancel', () => setPressedVisual(btn, false))
      panel.appendChild(btn)
      continue
    }
    // 方向键(上/下)
    panel.appendChild(createArrowKey(kind, `${row + 1} / ${col + 1}`))
  }
  // 左右键固定在中行两端
  panel.appendChild(createArrowKey('left', '2 / 1'))
  panel.appendChild(createArrowKey('right', '2 / 3'))

  function createArrowKey(dir: ArrowDir, gridArea: string): HTMLDivElement {
    const btn = document.createElement('div')
    btn.className = 'mk-key'
    btn.setAttribute('role', 'button')
    btn.setAttribute('tabindex', '-1')
    btn.setAttribute('aria-label', ARROWS[dir].aria)
    btn.style.gridArea = gridArea
    btn.appendChild(createArrowIcon(dir))
    btn.addEventListener('pointerdown', (e) => {
      e.stopPropagation()
      // 阻止焦点转移:终端 textarea 保持聚焦,软键盘不因按键而收起
      e.preventDefault()
      btn.setPointerCapture(e.pointerId)
      pressArrow(dir, btn)
    })
    btn.addEventListener('pointerup', () => releaseArrow(btn))
    btn.addEventListener('pointercancel', () => releaseArrow(btn))
    return btn
  }

  root.appendChild(panel)
  document.body.appendChild(root)

  // ── 状态 ─────────────────────────────────────────────────────────────────
  let shown = false
  let expanded = false
  let dragging = false
  let pointerDown = false
  let downX = 0
  let downY = 0
  let ballX = 0
  let ballY = 0
  let rafId: number | null = null
  let repeatTimer: ReturnType<typeof setInterval> | null = null
  let longpressTimer: ReturnType<typeof setTimeout> | null = null
  let destroyed = false

  // ── 按键派发 ─────────────────────────────────────────────────────────────
  function dispatchArrow(def: ArrowDef): void {
    const textarea = findVisibleTerminalTextarea()
    if (!textarea) return
    const init: KeyboardEventInit & { keyCode?: number; which?: number } = {
      key: def.key,
      code: def.key,
      bubbles: true,
      cancelable: true,
      composed: true,
    }
    init.keyCode = def.keyCode
    init.which = def.keyCode
    const down = new KeyboardEvent('keydown', init)
    // 兜底:个别引擎忽略 init 里的 legacy keyCode 时,用实例属性遮蔽原型 getter
    if (down.keyCode !== def.keyCode) {
      Object.defineProperty(down, 'keyCode', { get: () => def.keyCode })
    }
    const up = new KeyboardEvent('keyup', init)
    if (up.keyCode !== def.keyCode) {
      Object.defineProperty(up, 'keyCode', { get: () => def.keyCode })
    }
    textarea.dispatchEvent(down)
    textarea.dispatchEvent(up)
    vibrate()
  }

  function vibrate(): void {
    try {
      navigator.vibrate?.(8)
    } catch {
      /* 不支持触觉反馈的设备直接忽略 */
    }
  }

  function setPressedVisual(btn: HTMLElement, pressed: boolean): void {
    btn.classList.toggle('mk-pressed', pressed)
  }

  /** 单击立即发一次;按住不放(350ms 后)以 110ms 间隔连发。 */
  function pressArrow(dir: ArrowDir, btn: HTMLDivElement): void {
    setPressedVisual(btn, true)
    dispatchArrow(ARROWS[dir])
    stopRepeat()
    longpressTimer = setTimeout(() => {
      repeatTimer = setInterval(() => dispatchArrow(ARROWS[dir]), REPEAT_INTERVAL_MS)
    }, LONGPRESS_DELAY_MS)
  }

  function releaseArrow(btn: HTMLDivElement): void {
    setPressedVisual(btn, false)
    stopRepeat()
  }

  function stopRepeat(): void {
    if (longpressTimer !== null) {
      clearTimeout(longpressTimer)
      longpressTimer = null
    }
    if (repeatTimer !== null) {
      clearInterval(repeatTimer)
      repeatTimer = null
    }
  }

  // ── 悬浮球位置与拖动 ─────────────────────────────────────────────────────
  function placeBall(x: number, y: number): void {
    ballX = x
    ballY = y
    ball.style.left = `${x}px`
    ball.style.top = `${y}px`
  }

  function placePanelCenteredAt(x: number, y: number): void {
    const halfW = panel.offsetWidth / 2
    const halfH = panel.offsetHeight / 2
    const vw = window.innerWidth
    const vh = window.innerHeight
    const px = clamp(x, halfW + EDGE_MARGIN_PX, Math.max(halfW + EDGE_MARGIN_PX, vw - halfW - EDGE_MARGIN_PX))
    const py = clamp(y, halfH + EDGE_MARGIN_PX, Math.max(halfH + EDGE_MARGIN_PX, vh - halfH - EDGE_MARGIN_PX))
    panel.style.left = `${px}px`
    panel.style.top = `${py}px`
  }

  function restorePosition(): void {
    const saved = storage.get<{ xf: number; yf: number } | null>(POS_STORAGE_KEY, null)
    const xf = saved && typeof saved.xf === 'number' ? clamp01(saved.xf) : DEFAULT_POS.xf
    const yf = saved && typeof saved.yf === 'number' ? clamp01(saved.yf) : DEFAULT_POS.yf
    placeBall(xf * window.innerWidth, yf * window.innerHeight)
  }

  function savePosition(): void {
    storage.set(POS_STORAGE_KEY, {
      xf: clamp01(ballX / window.innerWidth),
      yf: clamp01(ballY / window.innerHeight),
    })
  }

  /** 松手后吸附到较近的左右屏幕边缘。 */
  function snapToEdge(): void {
    const vw = window.innerWidth
    const vh = window.innerHeight
    const margin = EDGE_MARGIN_PX + ball.offsetWidth / 2
    const targetX = ballX < vw / 2 ? margin : vw - margin
    ball.classList.add('mk-snapping')
    placeBall(targetX, clamp(ballY, margin, vh - margin))
    window.setTimeout(() => ball.classList.remove('mk-snapping'), 300)
    savePosition()
  }

  ball.addEventListener('pointerdown', (e) => {
    if (!shown) return
    e.preventDefault()
    pointerDown = true
    dragging = false
    downX = e.clientX
    downY = e.clientY
    ball.setPointerCapture(e.pointerId)
  })

  ball.addEventListener('pointermove', (e) => {
    if (!pointerDown) return
    const dx = e.clientX - downX
    const dy = e.clientY - downY
    if (!dragging && Math.hypot(dx, dy) > DRAG_THRESHOLD_PX) {
      dragging = true
      ball.classList.add('mk-dragging')
    }
    if (dragging) {
      const margin = EDGE_MARGIN_PX + ball.offsetWidth / 2
      placeBall(
        clamp(e.clientX, margin, Math.max(margin, window.innerWidth - margin)),
        clamp(e.clientY, margin, Math.max(margin, window.innerHeight - margin)),
      )
    }
  })

  ball.addEventListener('pointerup', () => {
    if (!pointerDown) return
    pointerDown = false
    ball.classList.remove('mk-dragging')
    if (dragging) {
      dragging = false
      snapToEdge()
    } else {
      // 位移未超阈值:视为点击,展开/收起面板
      if (expanded) collapse()
      else expand()
    }
  })

  ball.addEventListener('pointercancel', () => {
    pointerDown = false
    dragging = false
    ball.classList.remove('mk-dragging')
  })

  // ── 展开/收起 ────────────────────────────────────────────────────────────
  function expand(): void {
    expanded = true
    stopRepeat()
    // 先移除 display:none,offsetWidth 同步可读,再以球心为面板中心定位
    panel.classList.remove('mk-hidden')
    placePanelCenteredAt(ballX, ballY)
  }

  function collapse(): void {
    expanded = false
    stopRepeat()
    panel.classList.add('mk-hidden')
  }

  // ── 显隐刷新 ─────────────────────────────────────────────────────────────
  function refresh(): void {
    if (destroyed) return
    const shouldShow = isMobileViewport() && findVisibleTerminalTextarea() !== null
    if (shouldShow === shown) return
    shown = shouldShow
    root.classList.toggle('mk-hidden', !shouldShow)
    if (!shouldShow && expanded) collapse()
  }

  function scheduleRefresh(): void {
    if (rafId !== null || destroyed) return
    rafId = requestAnimationFrame(() => {
      rafId = null
      refresh()
    })
  }

  const observer = new MutationObserver((records) => {
    // 排除自身子树的变化,避免展开/收起、拖动定位触发自我循环
    for (const record of records) {
      if (!root.contains(record.target)) {
        scheduleRefresh()
        return
      }
    }
  })
  observer.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['style', 'class', 'hidden'],
  })

  window.addEventListener('resize', handleViewportChange, { passive: true })
  window.visualViewport?.addEventListener('resize', handleViewportChange)
  const tabClosedDisposable = options.subscribeTabClosed(() => scheduleRefresh())

  /** 视口尺寸变化(旋转/软键盘弹出收起):刷新显隐,并把越界的球拉回可视区。 */
  function handleViewportChange(): void {
    scheduleRefresh()
    if (!shown || destroyed) return
    const margin = EDGE_MARGIN_PX + ball.offsetWidth / 2
    const vw = window.innerWidth
    const vh = window.innerHeight
    const outOfBounds =
      ballX < margin || ballX > vw - margin || ballY < margin || ballY > vh - margin
    if (outOfBounds) snapToEdge()
  }

  restorePosition()
  refresh()

  return {
    destroy() {
      if (destroyed) return
      destroyed = true
      stopRepeat()
      if (rafId !== null) {
        cancelAnimationFrame(rafId)
        rafId = null
      }
      observer.disconnect()
      window.removeEventListener('resize', handleViewportChange)
      window.visualViewport?.removeEventListener('resize', handleViewportChange)
      tabClosedDisposable.dispose()
      root.remove()
    },
  }
}
