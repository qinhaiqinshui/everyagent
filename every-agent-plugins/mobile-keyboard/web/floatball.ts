/**
 * 移动端悬浮球控制器(全局虚拟鼠标 + 键盘方向键)。
 *
 * 职责(与宿主 UI 扩展点无关——扩展点没有「全局覆盖层」,本插件是扩展点之外
 * 自绘全局 UI 的先例,自挂 document.body 并自管生命周期):
 * - 显隐:移动端视口(宽度 <= 768,与宿主 useResponsiveViewport 同口径)即常显,
 *   不再限定终端标签;resize 时刷新显隐并把越界的球拉回可视区。
 * - 禁用态(默认):视为停用——半露出屏幕右缘上部(另一半在视口外不可见,
 *   尽可能不遮挡内容),半透明;点击后才「启用」。
 * - 收起态(已启用):鼠标图标本体(无背景),可拖动、松手吸附左右屏幕边缘;
 *   点击展开。
 * - 展开态:四段圆弧围成圆环(内缘紧贴中心键,无空白环),中心为鼠标图标;
 *   虚拟光标箭头显示在圆环之外(斜上方,贴近右屏时翻到右侧,带滞回):
 *   · 上/下圆弧:模拟键盘 ArrowUp/ArrowDown(合成 KeyboardEvent 派发给焦点
 *     元素;xterm 持有焦点时转成方向键转义序列发往 PTY,无人消费则无效果);
 *   · 左圆弧 = 鼠标左键:按下在光标位置合成 mousedown,手指继续滑动时光标
 *     跟随手指(向上偏移避免被手指遮挡)并持续合成 mousemove(buttons=1),
 *     松开合成 mouseup——宿主「按住左键拖动」(框选/拖动移动)由此驱动;
 *   · 右圆弧 = 鼠标右键:点击在光标位置合成 contextmenu(弹宿主右键菜单);
 *   · 中心鼠标图标:点击收起;拖动移动整个悬浮球(光标随动,只合成
 *     buttons=0 的 mousemove——纯移动鼠标位置,不触发选中/框选)。
 * - 合成事件 isTrusted=false,不触发浏览器默认行为;宿主组件(文件树等)的
 *   应用层监听(React onMouseDown / document mousemove/mouseup)不受影响。
 */

interface MobileKeypadOptions {
  // 预留:暂无配置项(位置不再持久化,默认禁用态贴右缘上部)。
}

export interface MobileKeypadController {
  destroy(): void
}

/** 与宿主 useResponsiveViewport 的 MOBILE_MAX_WIDTH 同口径。 */
const MOBILE_MAX_WIDTH = 768
/** 位移超过该值才视为拖动(否则算点击)。 */
const DRAG_THRESHOLD_PX = 8
/** 悬浮球吸附与钳制时离视口边缘的最小留白。 */
const EDGE_MARGIN_PX = 12
/** 长按方向键的连发启动延迟。 */
const LONGPRESS_DELAY_MS = 350
/** 连发间隔。 */
const REPEAT_INTERVAL_MS = 110
/** 禁用态半露位置:球心贴屏幕右缘,纵向取视口上部(比例)。 */
const DISABLED_Y_FRACTION = 0.12

/** 圆环面板直径(viewBox 同尺寸;整体为初版的二分之一)。 */
const RING_SIZE_PX = 100
/** 圆环外半径(留 1.5px 给面板边沿)。 */
const RING_OUTER_PX = 48.5
/** 圆环内半径 = 中心键半径:圆弧内缘紧贴中心鼠标键,不留空白环。 */
const RING_INNER_PX = 16
/** 收起态触控半径(视觉仅为图标,无背景)。 */
const BALL_RADIUS_PX = 22
/** 展开态闲置时,光标离球心的距离(务必 > 圆环外半径,显示在圆弧之外)。 */
const CURSOR_IDLE_DIST_PX = RING_OUTER_PX + 26
/**
 * 光标闲置位左右侧切换的滞回带(px):球距右缘 < ON 阈值时光标翻到右侧,
 * 翻回左侧需球继续左移超出 ON 阈值 + 本滞回带——单一阈值会在切换点附近
 * 随手指微动来回翻转,导致该区域内容无法被稳定指向。
 */
const CURSOR_SIDE_HYSTERESIS_PX = 72
/** 按住左键滑动时,光标相对手指的偏移(上移避免被手指与悬浮球遮挡)。 */
const CURSOR_FINGER_OFFSET = { dx: -16, dy: -66 }

type ArrowDir = 'up' | 'down'

interface KeyDef {
  key: string
  keyCode: number
  aria: string
}

const ARROWS: Record<ArrowDir, KeyDef> = {
  up: { keyCode: 38, key: 'ArrowUp', aria: '上方向键' },
  down: { keyCode: 40, key: 'ArrowDown', aria: '下方向键' },
}

/** 是否处于移动端视口(与宿主 useResponsiveViewport 同口径:宽度 <= 768)。 */
function isMobileViewport(): boolean {
  return window.innerWidth <= MOBILE_MAX_WIDTH
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(Math.max(v, min), max)
}

const SVG_NS = 'http://www.w3.org/2000/svg'

function createSvg(width: number, height: number, viewBox = '0 0 24 24'): SVGSVGElement {
  const svg = document.createElementNS(SVG_NS, 'svg')
  svg.setAttribute('viewBox', viewBox)
  svg.setAttribute('width', String(width))
  svg.setAttribute('height', String(height))
  return svg
}

/** 方向键三角箭头(SVG path,等腰三角形朝指定方向)。 */
function arrowPath(dir: 'up' | 'down' | 'left' | 'right'): string {
  const cx = 12
  const cy = 12
  const r = 6.5
  const half = Math.PI / 5
  const angles = { up: -90, right: 0, down: 90, left: 180 } as const
  const rad = (angles[dir] * Math.PI) / 180
  const tipX = cx + r * Math.cos(rad)
  const tipY = cy + r * Math.sin(rad)
  const b1X = cx + r * Math.cos(rad + Math.PI - half)
  const b1Y = cy + r * Math.sin(rad + Math.PI - half)
  const b2X = cx + r * Math.cos(rad + Math.PI + half)
  const b2Y = cy + r * Math.sin(rad + Math.PI + half)
  return `M ${tipX.toFixed(2)} ${tipY.toFixed(2)} L ${b1X.toFixed(2)} ${b1Y.toFixed(2)} L ${b2X.toFixed(2)} ${b2Y.toFixed(2)} Z`
}

function createArrowIcon(dir: 'up' | 'down' | 'left' | 'right', size: number): SVGSVGElement {
  const svg = createSvg(size, size)
  const path = document.createElementNS(SVG_NS, 'path')
  path.setAttribute('d', arrowPath(dir))
  path.setAttribute('fill', 'currentColor')
  svg.appendChild(path)
  return svg
}

/**
 * 鼠标外形图标(滚轮中线 + 左右键分割线);side 指定时高亮对应按键半区,
 * 用于左/右圆弧上的「鼠标左键 / 鼠标右键」图标。
 */
function createMouseIcon(size: number, side?: 'left' | 'right'): SVGSVGElement {
  const svg = createSvg(size, size)
  const body = document.createElementNS(SVG_NS, 'rect')
  body.setAttribute('x', '7')
  body.setAttribute('y', '3')
  body.setAttribute('width', '10')
  body.setAttribute('height', '18')
  body.setAttribute('rx', '5')
  body.setAttribute('fill', 'none')
  body.setAttribute('stroke', 'currentColor')
  body.setAttribute('stroke-width', '1.6')
  svg.appendChild(body)
  if (side) {
    // 高亮按键半区:左键为左上四分之一圆角区,右键镜像
    const fill = document.createElementNS(SVG_NS, 'path')
    fill.setAttribute('d', side === 'left'
      ? 'M12 3 L12 10 L7 10 L7 8 A5 5 0 0 1 12 3 Z'
      : 'M12 3 A5 5 0 0 1 17 8 L17 10 L12 10 Z')
    fill.setAttribute('fill', 'currentColor')
    svg.appendChild(fill)
  }
  const vline = document.createElementNS(SVG_NS, 'line')
  vline.setAttribute('x1', '12')
  vline.setAttribute('y1', '3')
  vline.setAttribute('x2', '12')
  vline.setAttribute('y2', '10')
  vline.setAttribute('stroke', 'currentColor')
  vline.setAttribute('stroke-width', '1.2')
  svg.appendChild(vline)
  const hline = document.createElementNS(SVG_NS, 'line')
  hline.setAttribute('x1', '7')
  hline.setAttribute('y1', '10')
  hline.setAttribute('x2', '17')
  hline.setAttribute('y2', '10')
  hline.setAttribute('stroke', 'currentColor')
  hline.setAttribute('stroke-width', '1.2')
  svg.appendChild(hline)
  return svg
}

/** 虚拟光标箭头:经典鼠标指针,尖端位于 (0,0),便于按尖端坐标直接定位。 */
function createCursorIcon(): SVGSVGElement {
  const svg = createSvg(22, 22)
  const path = document.createElementNS(SVG_NS, 'path')
  path.setAttribute('d', 'M0 0 L0 17 L4.2 13.4 L7 19 L9.2 18 L6.4 12.4 L12 12.4 Z')
  path.setAttribute('fill', '#ffffff')
  path.setAttribute('stroke', '#111111')
  path.setAttribute('stroke-width', '1.1')
  svg.appendChild(path)
  return svg
}

/** 极坐标(角度制,0° = 正上方,顺时针为正) → 直角坐标。 */
function polar(cx: number, cy: number, r: number, deg: number): [number, number] {
  const rad = (deg * Math.PI) / 180
  return [cx + r * Math.sin(rad), cy - r * Math.cos(rad)]
}

/** 圆环扇区(圆弧段)路径:a0 → a1 顺时针,0° = 正上方。 */
function arcSectorPath(cx: number, cy: number, rOuter: number, rInner: number, a0: number, a1: number): string {
  const [x0, y0] = polar(cx, cy, rOuter, a0)
  const [x1, y1] = polar(cx, cy, rOuter, a1)
  const [x2, y2] = polar(cx, cy, rInner, a1)
  const [x3, y3] = polar(cx, cy, rInner, a0)
  const large = a1 - a0 > 180 ? 1 : 0
  return `M ${x0.toFixed(2)} ${y0.toFixed(2)} A ${rOuter} ${rOuter} 0 ${large} 1 ${x1.toFixed(2)} ${y1.toFixed(2)} L ${x2.toFixed(2)} ${y2.toFixed(2)} A ${rInner} ${rInner} 0 ${large} 0 ${x3.toFixed(2)} ${y3.toFixed(2)} Z`
}

export function createMobileKeypad(_options: MobileKeypadOptions): MobileKeypadController {
  // ── DOM 组装 ──────────────────────────────────────────────────────────────
  const root = document.createElement('div')
  root.className = 'mk-root mk-hidden'

  // 虚拟光标箭头(展开态显示在圆环之外;pointer-events:none 不参与命中)
  const cursor = document.createElement('div')
  cursor.className = 'mk-cursor mk-hidden'
  cursor.appendChild(createCursorIcon())
  root.appendChild(cursor)

  // 收起态悬浮球(鼠标图标)
  const ball = document.createElement('div')
  ball.className = 'mk-ball'
  ball.setAttribute('role', 'button')
  // 不设 tabindex(含 -1):触屏覆盖层无需键盘可达性,且 -1 仍会被点击聚焦,
  // 会把焦点从终端 textarea 抢走,导致方向键合成事件发错目标。
  ball.setAttribute('aria-label', '虚拟鼠标:展开')
  ball.appendChild(createMouseIcon(26))
  root.appendChild(ball)

  // 展开态圆环面板
  const panel = document.createElement('div')
  panel.className = 'mk-panel mk-hidden'
  panel.setAttribute('role', 'group')
  panel.setAttribute('aria-label', '虚拟鼠标与方向键')

  const ring = createSvg(RING_SIZE_PX, RING_SIZE_PX, `0 0 ${RING_SIZE_PX} ${RING_SIZE_PX}`)
  ring.classList.add('mk-ring')

  /** 在圆环上创建一段圆弧按钮,返回 path 元素。 */
  function createArc(a0: number, a1: number, aria: string): SVGPathElement {
    const path = document.createElementNS(SVG_NS, 'path')
    path.setAttribute('d', arcSectorPath(RING_SIZE_PX / 2, RING_SIZE_PX / 2, RING_OUTER_PX, RING_INNER_PX, a0, a1))
    path.setAttribute('class', 'mk-arc')
    path.setAttribute('role', 'button')
    // focusable=false:阻止 Chrome(SVG2)点击聚焦 SVG 元素,防抢宿主焦点
    path.setAttribute('focusable', 'false')
    path.setAttribute('aria-label', aria)
    ring.appendChild(path)
    return path
  }

  /** 在圆环某方位放置不可交互的图标(半径取环带中线)。 */
  function placeRingIcon(icon: SVGSVGElement, deg: number): void {
    const [x, y] = polar(RING_SIZE_PX / 2, RING_SIZE_PX / 2, (RING_OUTER_PX + RING_INNER_PX) / 2, deg)
    const size = Number(icon.getAttribute('width') ?? 22)
    icon.setAttribute('x', (x - size / 2).toFixed(2))
    icon.setAttribute('y', (y - size / 2).toFixed(2))
    icon.classList.add('mk-arc-icon')
    ring.appendChild(icon)
  }

  const arcUp = createArc(-42, 42, ARROWS.up.aria)
  const arcRight = createArc(48, 132, '鼠标右键')
  const arcDown = createArc(138, 222, ARROWS.down.aria)
  const arcLeft = createArc(228, 312, '鼠标左键')
  placeRingIcon(createArrowIcon('up', 14), 0)
  placeRingIcon(createMouseIcon(14, 'right'), 90)
  placeRingIcon(createArrowIcon('down', 14), 180)
  placeRingIcon(createMouseIcon(14, 'left'), 270)
  panel.appendChild(ring)

  // 中心鼠标图标:点击收起;拖动移动整个悬浮球(光标随动)
  const center = document.createElement('div')
  center.className = 'mk-center'
  center.setAttribute('role', 'button')
  // 同 ball:不设 tabindex,避免点击时抢走宿主(终端输入框等)的焦点。
  center.setAttribute('aria-label', '拖动移动鼠标位置,点击收起')
  center.appendChild(createMouseIcon(18))
  panel.appendChild(center)

  root.appendChild(panel)
  document.body.appendChild(root)

  // ── 焦点归还守卫 ─────────────────────────────────────────────────────────
  // 移动端浏览器对 pointerdown.preventDefault 阻止焦点转移的支持不一致
  // (Chrome 还会按 SVG2 规范聚焦被点击的 SVG 圆弧):点击键盘元素后焦点可能
  // 被抢进覆盖层,之后 dispatchKey 的目标 document.activeElement 就变成了
  // 键盘自己——终端连续按方向键失灵。capture 阶段统一守卫:pointerdown 记录
  // 按下时的焦点,pointerup/cancel 后若焦点已落入键盘内部则归还原元素,
  // 无论浏览器行为如何都保证「点键盘不改变页面焦点」。
  let focusBeforePointerDown: HTMLElement | null = null
  // 宿主侧最后聚焦元素(focusin 持续跟踪,排除键盘内部与 body):activeElement
  // 被浏览器 tap 后续默认行为打到 body/键盘内部时,作为合成按键的兜底目标。
  let lastHostFocus: HTMLElement | null = null

  const handleFocusIn = (e: FocusEvent) => {
    const el = e.target
    if (el instanceof HTMLElement && !root.contains(el) && el !== document.body) {
      lastHostFocus = el
    }
  }
  document.addEventListener('focusin', handleFocusIn)

  root.addEventListener('pointerdown', (e) => {
    focusBeforePointerDown =
      document.activeElement instanceof HTMLElement && !root.contains(document.activeElement)
        ? document.activeElement
        : null
    e.preventDefault()
  }, { capture: true })

  // tap 的 click 默认行为(在 pointerup 之后派发)会把已归还的焦点再打到 body,
  // 捕获阶段取消之;键盘元素的点击语义全在 pointerup 上判定,取消 click 无副作用。
  root.addEventListener('click', (e) => {
    e.preventDefault()
  }, { capture: true })

  root.addEventListener('pointerup', () => {
    const active = document.activeElement
    if (active instanceof HTMLElement && root.contains(active)) {
      const toRestore = focusBeforePointerDown
      if (toRestore && toRestore.isConnected) {
        toRestore.focus({ preventScroll: true })
      } else {
        active.blur()
      }
    }
    focusBeforePointerDown = null
  }, { capture: true })

  root.addEventListener('pointercancel', () => {
    const active = document.activeElement
    if (active instanceof HTMLElement && root.contains(active)) {
      const toRestore = focusBeforePointerDown
      if (toRestore && toRestore.isConnected) {
        toRestore.focus({ preventScroll: true })
      } else {
        active.blur()
      }
    }
    focusBeforePointerDown = null
  }, { capture: true })

  // ── 状态 ─────────────────────────────────────────────────────────────────
  let shown = false
  let expanded = false
  let destroyed = false
  // 禁用/启用:默认禁用(半露右缘上部,点击启用);justEnabled 标记本次按下
  // 恰为「启用」点击,松手时不再展开面板(启用与展开分开两次点击)。
  let enabled = false
  let justEnabled = false
  // 悬浮球(收起态/中心图标)拖动
  let pointerDown = false
  let dragging = false
  let downX = 0
  let downY = 0
  // 抓取偏移:按下时手指相对球心的偏移。拖动用「手指坐标 + 抓取偏移」定位球,
  // 保持球与手指的相对位置——起拖不瞬移;任何坐标系常数偏差也被一并抵消。
  let grabDx = 0
  let grabDy = 0
  // 球心(展开态 = 面板中心)视口坐标
  let ballX = 0
  let ballY = 0
  // 虚拟光标尖端视口坐标
  let cursorX = 0
  let cursorY = 0
  // 左键按住状态
  let leftHeld = false
  let lastFingerX = 0
  let lastFingerY = 0
  let rafId: number | null = null
  let repeatTimer: ReturnType<typeof setInterval> | null = null
  let longpressTimer: ReturnType<typeof setTimeout> | null = null

  // ── 合成事件派发 ──────────────────────────────────────────────────────────
  /**
   * 在指定视口坐标合成鼠标事件:命中元素 = elementFromPoint(光标尖端),
   * 事件 bubbles 到 document——宿主 React onMouseDown 与 document 级
   * mousemove/mouseup 监听(文件树拖动会话)由此被驱动。
   */
  function dispatchMouseAt(
    type: 'mousedown' | 'mousemove' | 'mouseup' | 'contextmenu',
    x: number,
    y: number,
    init: { button: number; buttons: number },
  ): void {
    const hit = document.elementFromPoint(x, y)
    const target: EventTarget = hit ?? document.body
    target.dispatchEvent(new MouseEvent(type, {
      bubbles: true,
      cancelable: true,
      composed: true,
      view: window,
      clientX: x,
      clientY: y,
      screenX: x,
      screenY: y,
      button: init.button,
      buttons: init.buttons,
    }))
  }

  /**
   * 把一次按键(keydown + keyup)如实派发给宿主焦点元素,不管那是什么组件、
   * 有没有焦点——事件沿冒泡路径走一圈,没人消费就什么都不会发生(与真实
   * 按键落在无监听的页面上等价)。
   *
   * 目标解析:activeElement 有效(键盘外且非 body)时用它;若已被浏览器 tap
   * 后续默认行为打到 body 或键盘内部,则回退到 focusin 跟踪的宿主最后焦点
   * ——xterm 的 keydown 监听挂在自己 textarea 上,合成事件直接派发给该元素
   * 即被消费,不依赖它此刻真实持有焦点。
   */
  function resolveKeyTarget(): EventTarget {
    const active = document.activeElement
    if (active instanceof HTMLElement && !root.contains(active) && active !== document.body) {
      return active
    }
    if (lastHostFocus && lastHostFocus.isConnected) {
      return lastHostFocus
    }
    return document.body
  }

  function dispatchKey(def: KeyDef): void {
    const target: EventTarget = resolveKeyTarget()
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
    if (down.keyCode !== def.keyCode) {
      Object.defineProperty(down, 'keyCode', { get: () => def.keyCode })
    }
    const up = new KeyboardEvent('keyup', init)
    if (up.keyCode !== def.keyCode) {
      Object.defineProperty(up, 'keyCode', { get: () => def.keyCode })
    }
    target.dispatchEvent(down)
    target.dispatchEvent(up)
    vibrate()
  }

  function vibrate(): void {
    try {
      navigator.vibrate?.(8)
    } catch {
      /* 不支持触觉反馈的设备直接忽略 */
    }
  }

  function setPressedVisual(el: Element, pressed: boolean): void {
    el.classList.toggle('mk-pressed', pressed)
  }

  /** 单击立即发一次;按住不放(350ms 后)以 110ms 间隔连发。 */
  function pressArrow(def: KeyDef, el: Element): void {
    setPressedVisual(el, true)
    dispatchKey(def)
    stopRepeat()
    longpressTimer = setTimeout(() => {
      repeatTimer = setInterval(() => dispatchKey(def), REPEAT_INTERVAL_MS)
    }, LONGPRESS_DELAY_MS)
  }

  function releaseArrow(el: Element): void {
    setPressedVisual(el, false)
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

  // ── 定位 ─────────────────────────────────────────────────────────────────
  /** 球与面板共用同一中心点坐标(left/top = 中心,CSS translate(-50%,-50%))。 */
  function placeBall(x: number, y: number): void {
    ballX = x
    ballY = y
    ball.style.left = `${x}px`
    ball.style.top = `${y}px`
    panel.style.left = `${x}px`
    panel.style.top = `${y}px`
  }

  /** 光标按尖端坐标定位(箭头 SVG 尖端在 (0,0),无需额外平移)。 */
  function placeCursor(x: number, y: number): void {
    cursorX = clamp(x, 2, window.innerWidth - 2)
    cursorY = clamp(y, 2, window.innerHeight - 2)
    cursor.style.left = `${cursorX}px`
    cursor.style.top = `${cursorY}px`
  }

  /** 光标闲置位当前在右侧(true)/左侧(false):滞回状态,跨展开/收起保持。 */
  let cursorOnRight = false

  /**
   * 闲置光标位置:圆环之外左上方;球贴近右屏时光标翻到右上方(指向右侧内容带)。
   * 左右切换带滞回:翻到右侧的进入阈值小于翻回左侧的退出阈值,阈值附近往返
   * 移动不来回翻转——箭头在右侧时需球向左移出滞回带才转回左侧。
   */
  function resetCursorToIdle(): void {
    const d = CURSOR_IDLE_DIST_PX / Math.SQRT2
    const distFromRight = window.innerWidth - ballX
    if (cursorOnRight) {
      if (distFromRight > CURSOR_IDLE_DIST_PX + RING_OUTER_PX + CURSOR_SIDE_HYSTERESIS_PX) {
        cursorOnRight = false
      }
    } else if (distFromRight < CURSOR_IDLE_DIST_PX + RING_OUTER_PX) {
      cursorOnRight = true
    }
    const dx = cursorOnRight ? d : -d
    placeCursor(ballX + dx, ballY - d)
  }

 /** 禁用态(默认):球心贴屏幕右缘(露一半),纵向取视口上部;半透明弱化。 */
  function placeDisabled(): void {
    enabled = false
    ball.classList.add('mk-disabled')
    const margin = EDGE_MARGIN_PX + BALL_RADIUS_PX
    placeBall(
      window.innerWidth,
      clamp(window.innerHeight * DISABLED_Y_FRACTION, margin, Math.max(margin, window.innerHeight - margin)),
    )
  }

  /** 启用:球拉回完全可见,恢复常规收起态外观。 */
  function enableBall(): void {
    if (enabled) return
    enabled = true
    ball.classList.remove('mk-disabled')
    const margin = EDGE_MARGIN_PX + BALL_RADIUS_PX
    placeBall(
      clamp(ballX, margin, Math.max(margin, window.innerWidth - margin)),
      clamp(ballY, margin, Math.max(margin, window.innerHeight - margin)),
    )
  }

  /** 松手后吸附到较近的左右屏幕边缘(收起态)。 */
  function snapToEdge(): void {
    const vw = window.innerWidth
    const vh = window.innerHeight
    const margin = EDGE_MARGIN_PX + BALL_RADIUS_PX
    const targetX = ballX < vw / 2 ? margin : vw - margin
    ball.classList.add('mk-snapping')
    placeBall(targetX, clamp(ballY, margin, vh - margin))
    window.setTimeout(() => ball.classList.remove('mk-snapping'), 300)
  }

  // ── 收起态悬浮球:点击启用(禁用态)/拖动定位 / 点击展开 ───────────────────
  ball.addEventListener('pointerdown', (e) => {
    if (!shown) return
    e.preventDefault()
    // 禁用态首击:先启用(球拉回完全可见),本次松手不展开
    if (!enabled) {
      enableBall()
      justEnabled = true
    } else {
      justEnabled = false
    }
    pointerDown = true
    dragging = false
    downX = e.clientX
    downY = e.clientY
    grabDx = ballX - e.clientX
    grabDy = ballY - e.clientY
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
      const margin = EDGE_MARGIN_PX + BALL_RADIUS_PX
      placeBall(
        clamp(e.clientX + grabDx, margin, Math.max(margin, window.innerWidth - margin)),
        clamp(e.clientY + grabDy, margin, Math.max(margin, window.innerHeight - margin)),
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
    } else if (!justEnabled) {
      // 已启用的普通点击:展开面板;「启用」那次点击不展开
      expand()
    }
    justEnabled = false
  })

  ball.addEventListener('pointercancel', () => {
    pointerDown = false
    dragging = false
    ball.classList.remove('mk-dragging')
  })

  // ── 中心鼠标图标:拖动移动光标 / 点击收起 ─────────────────────────────────
  center.addEventListener('pointerdown', (e) => {
    e.preventDefault()
    e.stopPropagation()
    pointerDown = true
    dragging = false
    downX = e.clientX
    downY = e.clientY
    grabDx = ballX - e.clientX
    grabDy = ballY - e.clientY
    center.setPointerCapture(e.pointerId)
  })

  center.addEventListener('pointermove', (e) => {
    if (!pointerDown) return
    const dx = e.clientX - downX
    const dy = e.clientY - downY
    if (!dragging && Math.hypot(dx, dy) > DRAG_THRESHOLD_PX) {
      dragging = true
      center.classList.add('mk-dragging')
    }
    if (dragging) {
      // 展开态钳制:整个圆环保持在视口内
      const margin = EDGE_MARGIN_PX + RING_OUTER_PX
      placeBall(
        clamp(e.clientX + grabDx, margin, Math.max(margin, window.innerWidth - margin)),
        clamp(e.clientY + grabDy, margin, Math.max(margin, window.innerHeight - margin)),
      )
      resetCursorToIdle()
      // 纯移动鼠标位置:buttons=0,不触发选中/框选
      dispatchMouseAt('mousemove', cursorX, cursorY, { button: 0, buttons: 0 })
    }
  })

  center.addEventListener('pointerup', () => {
    if (!pointerDown) return
    pointerDown = false
    center.classList.remove('mk-dragging')
    if (dragging) {
      dragging = false
    } else {
      collapse()
    }
  })

  center.addEventListener('pointercancel', () => {
    pointerDown = false
    dragging = false
    center.classList.remove('mk-dragging')
  })

  // ── 圆弧按键 ─────────────────────────────────────────────────────────────
  function bindArrowArc(arc: SVGPathElement, def: KeyDef): void {
    arc.addEventListener('pointerdown', (e) => {
      e.stopPropagation()
      e.preventDefault()
      arc.setPointerCapture(e.pointerId)
      pressArrow(def, arc)
    })
    arc.addEventListener('pointerup', () => releaseArrow(arc))
    arc.addEventListener('pointercancel', () => releaseArrow(arc))
  }
  bindArrowArc(arcUp, ARROWS.up)
  bindArrowArc(arcDown, ARROWS.down)

  // 左圆弧 = 鼠标左键:按住后手指直接滑动即「按住左键拖动」
  arcLeft.addEventListener('pointerdown', (e) => {
    e.stopPropagation()
    e.preventDefault()
    arcLeft.setPointerCapture(e.pointerId)
    leftHeld = true
    lastFingerX = e.clientX
    lastFingerY = e.clientY
    grabDx = ballX - e.clientX
    grabDy = ballY - e.clientY
    setPressedVisual(arcLeft, true)
    dispatchMouseAt('mousedown', cursorX, cursorY, { button: 0, buttons: 1 })
  })
  arcLeft.addEventListener('pointermove', (e) => {
    if (!leftHeld) return
    lastFingerX = e.clientX
    lastFingerY = e.clientY
    // 按住左键拖动:整个悬浮球跟随手指移动(保持抓取偏移,起拖不瞬移),与虚拟光标一起
    const margin = EDGE_MARGIN_PX + RING_OUTER_PX
    placeBall(
      clamp(e.clientX + grabDx, margin, Math.max(margin, window.innerWidth - margin)),
      clamp(e.clientY + grabDy, margin, Math.max(margin, window.innerHeight - margin)),
    )
    // 光标跟随手指(向上偏移避免被手指与悬浮球遮挡),持续合成 mousemove(buttons=1)
    placeCursor(e.clientX + CURSOR_FINGER_OFFSET.dx, e.clientY + CURSOR_FINGER_OFFSET.dy)
    dispatchMouseAt('mousemove', cursorX, cursorY, { button: 0, buttons: 1 })
  })
  const releaseLeft = () => {
    if (!leftHeld) return
    leftHeld = false
    setPressedVisual(arcLeft, false)
    dispatchMouseAt('mouseup', cursorX, cursorY, { button: 0, buttons: 0 })
    // 松手后光标回到圆环外的闲置位
    resetCursorToIdle()
  }
  arcLeft.addEventListener('pointerup', releaseLeft)
  arcLeft.addEventListener('pointercancel', releaseLeft)

  // 右圆弧 = 鼠标右键:点击在光标位置合成 contextmenu
  arcRight.addEventListener('pointerdown', (e) => {
    e.stopPropagation()
    e.preventDefault()
    arcRight.setPointerCapture(e.pointerId)
    setPressedVisual(arcRight, true)
  })
  arcRight.addEventListener('pointerup', () => {
    setPressedVisual(arcRight, false)
    dispatchMouseAt('contextmenu', cursorX, cursorY, { button: 2, buttons: 2 })
  })
  arcRight.addEventListener('pointercancel', () => setPressedVisual(arcRight, false))

  // ── 展开/收起 ────────────────────────────────────────────────────────────
  function expand(): void {
    expanded = true
    stopRepeat()
    // 展开态钳制:从收起态边缘位置展开时,把整个圆环拉回视口内
    const margin = EDGE_MARGIN_PX + RING_OUTER_PX
    placeBall(
      clamp(ballX, margin, Math.max(margin, window.innerWidth - margin)),
      clamp(ballY, margin, Math.max(margin, window.innerHeight - margin)),
    )
    ball.classList.add('mk-hidden')
    panel.classList.remove('mk-hidden')
    cursor.classList.remove('mk-hidden')
    resetCursorToIdle()
  }

  function collapse(): void {
    expanded = false
    stopRepeat()
    panel.classList.add('mk-hidden')
    cursor.classList.add('mk-hidden')
    ball.classList.remove('mk-hidden')
  }

  // ── 显隐刷新 ─────────────────────────────────────────────────────────────
  function refresh(): void {
    if (destroyed) return
    // 全局常显:移动端视口即显示,不再限定标签类型
    const shouldShow = isMobileViewport()
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

  window.addEventListener('resize', handleViewportChange, { passive: true })
  window.visualViewport?.addEventListener('resize', handleViewportChange)

  /** 视口尺寸变化(旋转/软键盘弹出收起):刷新显隐;禁用态重贴右缘上部,
   *  启用态把越界的球拉回可视区。 */
  function handleViewportChange(): void {
    scheduleRefresh()
    if (!shown || destroyed) return
    if (!enabled) {
      if (!expanded) placeDisabled()
      return
    }
    const margin = EDGE_MARGIN_PX + (expanded ? RING_OUTER_PX : BALL_RADIUS_PX)
    const vw = window.innerWidth
    const vh = window.innerHeight
    const outOfBounds =
      ballX < margin || ballX > vw - margin || ballY < margin || ballY > vh - margin
    if (outOfBounds) {
      if (expanded) {
        placeBall(clamp(ballX, margin, vw - margin), clamp(ballY, margin, vh - margin))
        resetCursorToIdle()
      } else {
        snapToEdge()
      }
    }
  }

  placeDisabled()
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
      window.removeEventListener('resize', handleViewportChange)
      window.visualViewport?.removeEventListener('resize', handleViewportChange)
      document.removeEventListener('focusin', handleFocusIn)
      root.remove()
    },
  }
}
