/**
 * 自研 SVG 工作流画布基座（无外部库——bare import 白名单约束）。
 *
 * 能力（数据驱动，位置即 NodeDef.x/y——位置存定义）：
 * - 节点矩形（类型图标 + 标题 + 简略信息）+ 贝塞尔边（from 右锚点 → to 左锚点）；
 * - 选中态（节点/边高亮）、背景点按取消选中；
 * - mode='edit'：节点拖拽（pointerdown→capture→pointermove 改 x/y）、从右锚点拖出
 *   连线到目标节点（pointerup 命中测试）、Delete 删除由宿主面板处理（本组件只回调
 *   选中）；触屏可用——拖拽目标挂 touch-action:none 阻断浏览器滚动抢占手势；
 * - mode='readonly'：禁一切编辑交互（实例视图复用基座，步骤 10；tap 选中可用）；
 * - 桌面 Ctrl+拖拽平移画布（按住 Ctrl 光标变手型；节点/连线/选中等交互让位）；
 * - 触屏平移：空白背景单指=滚动容器原生滚动；任意位置（含节点上）双指拖动=平移
 *   画布（多指跟踪在滚动容器层，节点拖拽/连线让位，驱动指抬起接力到剩余触点）；
 * - 节点选中是 tap 语义：pointerdown 不选，pointerup 位移 ≤ TAP_SLOP 才回调
 *   onSelect——触屏按下即弹属性抽屉会吞掉随后的平移/拖动手势；
 * - 画布尺寸随内容自适应：svg 宽高 = 固定下限与节点包围盒+留白的较大者，节点
 *   拖/落在任何位置都可滚到（修复「节点拖出固定画布后不可见」）；
 * - 运行态着色/动画：node.status → renderStates 色彩（TaskStatusDot 色彩语言）。
 *
 * 坐标系：svg 与定义坐标 1:1（无缩放），外层 div 滚动平移；client → svg 坐标经
 * getBoundingClientRect 换算（无 viewBox 缩放故为线性平移）。
 *
 * 指针实现：pointerdown 上对 svg setPointerCapture，后续 pointermove/up/cancel
 * 挂在 svg 的 React props 上（capture 重定向后事件仍派发到 svg 并冒泡）——
 * 不再用 window mousemove/mouseup（触屏不可用，且多画布实例并存时全局监听易串扰）。
 */
import React from 'react'
import { nodeTypeGlyph } from '../icons'
import { nodeStrokeColor, statusVisual, NEUTRAL_STATUS_VISUAL, type RenderStatus } from './renderStates'

// ─── 几何常量 ─────────────────────────────────────────────────────────────

export const NODE_W = 180
export const NODE_H = 64
/** 画布最小尺寸（历史固定值，内容不足时兜底）。 */
const CANVAS_W = 3200
const CANVAS_H = 2400
/** 内容自适应留白：节点包围盒右侧/底部的拖放余量。 */
const CANVAS_PAD = 160
const EDGE_GAP = 60
/** tap 判定阈值（px）：pointerup 与 down 的 client 位移不超过它才算点按选中。 */
const TAP_SLOP = 8

// ─── 视图模型（props） ────────────────────────────────────────────────────

export interface CanvasNodeView {
  id: string
  type: string
  name: string
  x: number
  y: number
  /** 简略信息行（命令首行 / agent 名等），由宿主面板组装。 */
  subtitle?: string
  /** 运行态（readonly/实例视图投影；缺省=编辑态中性）。 */
  status?: RenderStatus
}

export interface CanvasEdgeView {
  id: string
  from: string
  to: string
  /** 条件短标签（conditionLabel 产物；空=不显示）。 */
  conditionLabel?: string
  /** 边运行态（激活=绿等；缺省=默认灰）。 */
  status?: RenderStatus
}

export type CanvasSelection = { kind: 'node' | 'edge'; id: string } | null

export interface WorkflowCanvasProps {
  nodes: CanvasNodeView[]
  edges: CanvasEdgeView[]
  mode: 'edit' | 'readonly'
  selection: CanvasSelection
  onSelect: (selection: CanvasSelection) => void
  /** edit 限定：拖拽落点回调（每帧回调，宿主直接改定义状态）。 */
  onNodeMove?: (id: string, x: number, y: number) => void
  /** edit 限定：连线完成（from 右锚点 → to 节点；基础去重/self 校验在此做）。 */
  onCreateEdge?: (from: string, to: string) => void
  /** 画布可视中心回写（palette 新节点落点用；宿主每次读 current）。 */
  viewportCenterRef?: React.MutableRefObject<{ x: number; y: number } | null>
  className?: string
  style?: React.CSSProperties
}

// ─── 样式注入（运行动画与画布底纹；插件 CSS 走宿主 <style> 注入，避免产物耦合） ──

const CANVAS_STYLE_ID = 'wf-canvas-styles'

function ensureCanvasStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(CANVAS_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = CANVAS_STYLE_ID
  style.textContent = `
.wf-canvas-scroll { overflow: auto; width: 100%; height: 100%; background: var(--bg-primary, #0b0d12); touch-action: pan-x pan-y; }
.wf-canvas-surface { background-image: radial-gradient(circle, var(--border-light, #1b202a) 1px, transparent 1px); background-size: 24px 24px; }
.wf-node-hit { cursor: grab; }
.wf-node-hit:active { cursor: grabbing; }
.wf-anchor-out { cursor: crosshair; }
/* Ctrl+拖拽平移画布：按住 Ctrl 光标变手型（盖过节点/锚点自身 cursor），拖动中变 grabbing。 */
.wf-canvas-pan { cursor: grab; }
.wf-canvas-pan * { cursor: grab !important; }
.wf-canvas-panning, .wf-canvas-panning * { cursor: grabbing !important; }
.wf-canvas-panning { user-select: none; -webkit-user-select: none; }
/* 触屏：编辑态节点/锚点为拖拽手势目标，阻断浏览器滚动抢占（祖先容器的
   pan-x pan-y 与本元素的 none 取更严者，单指原生滚动不受影响）；
   双指手势不被浏览器 pinch-zoom 接管，交给容器层做画布平移。 */
.wf-node-hit, .wf-anchor-out { touch-action: none; -webkit-user-select: none; user-select: none; }
@keyframes wf-status-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }
.wf-status-running { animation: wf-status-pulse 1.1s ease-in-out infinite; }
@keyframes wf-edge-flow { to { stroke-dashoffset: -14; } }
.wf-edge-running { stroke-dasharray: 7 7; animation: wf-edge-flow 0.6s linear infinite; }
`
  document.head.appendChild(style)
}

// ─── 组件 ─────────────────────────────────────────────────────────────────

export default function WorkflowCanvas({
  nodes,
  edges,
  mode,
  selection,
  onSelect,
  onNodeMove,
  onCreateEdge,
  viewportCenterRef,
  className,
  style,
}: WorkflowCanvasProps) {
  ensureCanvasStyles()
  const svgRef = React.useRef<SVGSVGElement | null>(null)
  const scrollRef = React.useRef<HTMLDivElement | null>(null)
  /** 拖拽态（pointer capture 期间由 svg 的 move/up/cancel 处理器消费，不进 state 防重渲染抖动）。 */
  const dragRef = React.useRef<{ id: string; dx: number; dy: number; pointerId: number; startX: number; startY: number } | null>(null)
  /** tap 候选（节点按下 → up 时位移 ≤ TAP_SLOP 才选中；readonly 无拖拽也走此判定）。 */
  const tapRef = React.useRef<{ id: string; pointerId: number; startX: number; startY: number } | null>(null)
  /** 连线态源节点（pendingEdge state 只为渲染预览线）。 */
  const connectRef = React.useRef<{ from: string; pointerId: number } | null>(null)
  const [pendingEdge, setPendingEdge] = React.useState<{ from: string; x: number; y: number } | null>(null)
  const [hoverAnchor, setHoverAnchor] = React.useState<string | null>(null)
  /** 活动触点（非鼠标）：双指平移检测；坐标随手更新供接力平移取基准。 */
  const touchPointsRef = React.useRef<Map<number, { x: number; y: number }>>(new Map())
  /** 多指平移态：第二指落下启动，驱动指针位移取反滚动容器；驱动指抬起接力到剩余触点。 */
  const multiPanRef = React.useRef<{ pointerId: number; startX: number; startY: number; scrollLeft: number; scrollTop: number } | null>(null)

  // ── Ctrl+拖拽平移画布（桌面）：按住 Ctrl 光标变手型，拖动滚动容器。 ──
  const [ctrlHeld, setCtrlHeld] = React.useState(false)
  const [panning, setPanning] = React.useState(false)
  /** 平移态（capture 到滚动容器；startX/Y 为按下 client 坐标 + 当时滚动位）。 */
  const panRef = React.useRef<{ pointerId: number; startX: number; startY: number; scrollLeft: number; scrollTop: number } | null>(null)
  React.useEffect(() => {
    const down = (e: KeyboardEvent) => { if (e.key === 'Control') setCtrlHeld(true) }
    const up = (e: KeyboardEvent) => { if (e.key === 'Control') setCtrlHeld(false) }
    const blur = () => setCtrlHeld(false) // 窗口失焦（Alt+Tab 等）：防 Ctrl 态卡住
    window.addEventListener('keydown', down)
    window.addEventListener('keyup', up)
    window.addEventListener('blur', blur)
    return () => {
      window.removeEventListener('keydown', down)
      window.removeEventListener('keyup', up)
      window.removeEventListener('blur', blur)
    }
  }, [])

  const editable = mode === 'edit'
  const nodeById = React.useMemo(() => {
    const m = new Map<string, CanvasNodeView>()
    for (const n of nodes) m.set(n.id, n)
    return m
  }, [nodes])

  // 画布尺寸随内容自适应：节点包围盒 + 留白与固定下限取大者。节点可拖/落在
  // 任意位置（onNodeMove 只钳负向下限），画布随之扩展——滚动容器总能滚到每个节点。
  const contentW = React.useMemo(
    () => nodes.reduce((m, n) => Math.max(m, n.x + NODE_W), CANVAS_W) + CANVAS_PAD,
    [nodes],
  )
  const contentH = React.useMemo(
    () => nodes.reduce((m, n) => Math.max(m, n.y + NODE_H), CANVAS_H) + CANVAS_PAD,
    [nodes],
  )

  /** client 坐标 → svg 定义坐标（1:1 无缩放，线性平移）。 */
  const toSvgPoint = React.useCallback((clientX: number, clientY: number): { x: number; y: number } => {
    const svg = svgRef.current
    if (!svg) return { x: 0, y: 0 }
    const rect = svg.getBoundingClientRect()
    return { x: clientX - rect.left, y: clientY - rect.top }
  }, [])

  /** pointerdown 上捕获指针到 svg：后续 move/up/cancel 持续派发（触屏手指移出元素不丢事件）。 */
  const capturePointer = React.useCallback((event: React.PointerEvent): boolean => {
    const svg = svgRef.current
    if (!svg) return false
    try {
      svg.setPointerCapture(event.pointerId)
    } catch {
      // 指针已释放等异常：放弃捕获，交互自然终止（up 处理器空转一次）。
      return false
    }
    return true
  }, [])

  // 可视中心回写：palette「新节点落画布中心」的落点依据（滚动容器视口几何）。
  React.useLayoutEffect(() => {
    const el = scrollRef.current
    if (el && viewportCenterRef) {
      viewportCenterRef.current = {
        x: Math.max(0, Math.round(el.scrollLeft + el.clientWidth / 2 - NODE_W / 2)),
        y: Math.max(0, Math.round(el.scrollTop + el.clientHeight / 2 - NODE_H / 2)),
      }
    }
  })

  /** 节点主体按下：记录 tap 候选 + （edit）启动拖拽（Ctrl 或多指平移中=平移画布意图，跳过）。
   *  选中延迟到 pointerup（位移 ≤ TAP_SLOP）——触屏按下即选中会立刻弹属性抽屉，
   *  吞掉用户随后的拖动/平移手势（移动端编辑器核心体感问题）。 */
  const handleNodePointerDown = (event: React.PointerEvent, node: CanvasNodeView) => {
    if (event.button !== 0 || event.ctrlKey || multiPanRef.current) return
    tapRef.current = { id: node.id, pointerId: event.pointerId, startX: event.clientX, startY: event.clientY }
    if (!editable || !onNodeMove) return
    event.preventDefault()
    if (!capturePointer(event)) return
    const p = toSvgPoint(event.clientX, event.clientY)
    dragRef.current = {
      id: node.id,
      dx: p.x - node.x,
      dy: p.y - node.y,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
    }
  }

  /** 右锚点按下：启动连线（预览线跟随指针，pointerup 命中目标节点即回调；Ctrl/多指平移跳过）。 */
  const handleAnchorPointerDown = (event: React.PointerEvent, node: CanvasNodeView) => {
    if (!editable || !onCreateEdge || event.button !== 0 || event.ctrlKey || multiPanRef.current) return
    event.preventDefault()
    event.stopPropagation()
    if (!capturePointer(event)) return
    connectRef.current = { from: node.id, pointerId: event.pointerId }
    const p = toSvgPoint(event.clientX, event.clientY)
    setPendingEdge({ from: node.id, x: p.x, y: p.y })
  }

  /** svg 上的拖拽/连线移动（capture 重定向后仍冒泡到 svg 的 React props）。 */
  const handleSvgPointerMove = (event: React.PointerEvent) => {
    const d = dragRef.current
    if (d && onNodeMove && d.pointerId === event.pointerId) {
      const q = toSvgPoint(event.clientX, event.clientY)
      onNodeMove(d.id, Math.max(0, Math.round(q.x - d.dx)), Math.max(0, Math.round(q.y - d.dy)))
    }
    const c = connectRef.current
    if (c && c.pointerId === event.pointerId) {
      const q = toSvgPoint(event.clientX, event.clientY)
      setPendingEdge({ from: c.from, x: q.x, y: q.y })
    }
  }

  /** 拖拽/连线收口（连线做目标命中测试；去重/自环由命中排除；tap 候选位移达标才选中）。 */
  const handleSvgPointerUp = (event: React.PointerEvent) => {
    const d = dragRef.current
    if (d && d.pointerId === event.pointerId) {
      dragRef.current = null
    }
    const t = tapRef.current
    if (t && t.pointerId === event.pointerId) {
      tapRef.current = null
      if (Math.hypot(event.clientX - t.startX, event.clientY - t.startY) <= TAP_SLOP) {
        onSelect({ kind: 'node', id: t.id })
      }
    }
    const c = connectRef.current
    if (c && c.pointerId === event.pointerId) {
      connectRef.current = null
      setPendingEdge(null)
      if (onCreateEdge) {
        const q = toSvgPoint(event.clientX, event.clientY)
        // 命中测试：点落在某节点矩形内（非源节点、且不与既有同向边重复）。
        const target = nodes.find(
          n => n.id !== c.from
            && q.x >= n.x && q.x <= n.x + NODE_W
            && q.y >= n.y && q.y <= n.y + NODE_H,
        )
        const duplicated = target && edges.some(e => e.from === c.from && e.to === target.id)
        if (target && !duplicated) {
          onCreateEdge(c.from, target.id)
        }
      }
    }
  }

  /** 指针中断（触屏来电/手势被系统接管等）：静默终止交互，不留半截预览线。 */
  const handleSvgPointerCancel = (event: React.PointerEvent) => {
    const d = dragRef.current
    if (d && d.pointerId === event.pointerId) dragRef.current = null
    const t = tapRef.current
    if (t && t.pointerId === event.pointerId) tapRef.current = null
    const c = connectRef.current
    if (c && c.pointerId === event.pointerId) {
      connectRef.current = null
      setPendingEdge(null)
    }
  }

  // ── 平移处理器（滚动容器上，capture 到容器自身） ──

  // ── 多指平移（触屏）：第二指落下即启动——节点拖拽/连线/tap 让位，任意落点
  //    （含节点上）双指拖动都是平移画布；驱动指针抬起则接力到剩余触点继续平移。 ──

  /** 启动/重置多指平移：以指定指针当前坐标 + 当前滚动位为基准。 */
  const startMultiPan = (pointerId: number): void => {
    const el = scrollRef.current
    const pt = touchPointsRef.current.get(pointerId)
    if (!el || !pt) return
    dragRef.current = null
    connectRef.current = null
    tapRef.current = null
    setPendingEdge(null)
    multiPanRef.current = {
      pointerId,
      startX: pt.x,
      startY: pt.y,
      scrollLeft: el.scrollLeft,
      scrollTop: el.scrollTop,
    }
    setPanning(true)
  }

  /** 触点抬起：驱动指针则接力到剩余触点（仍有触点 → 换基准续平移；否则收口）。 */
  const releaseTouchPoint = (pointerId: number): void => {
    touchPointsRef.current.delete(pointerId)
    const mp = multiPanRef.current
    if (!mp || mp.pointerId !== pointerId) return
    const el = scrollRef.current
    const next = touchPointsRef.current.entries().next()
    if (el && !next.done) {
      const [pid, pt] = next.value
      multiPanRef.current = {
        pointerId: pid,
        startX: pt.x,
        startY: pt.y,
        scrollLeft: el.scrollLeft,
        scrollTop: el.scrollTop,
      }
    } else {
      multiPanRef.current = null
      setPanning(false)
    }
  }

  /** Ctrl+按下：捕获指针到滚动容器，记录起点滚动位（同时抑制背景取消选中）。 */
  const handlePanPointerDown = (event: React.PointerEvent) => {
    if (!event.ctrlKey || event.button !== 0) return
    const el = scrollRef.current
    if (!el) return
    event.preventDefault()
    try {
      el.setPointerCapture(event.pointerId)
    } catch {
      return // 指针已释放等：放弃，交互自然终止
    }
    panRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      scrollLeft: el.scrollLeft,
      scrollTop: el.scrollTop,
    }
    setPanning(true)
  }

  /** 平移移动：滚动量 = 指针位移取反（拖右看左，与常规画布一致）。 */
  const handlePanPointerMove = (event: React.PointerEvent) => {
    const p = panRef.current
    const el = scrollRef.current
    if (!p || !el || p.pointerId !== event.pointerId) return
    el.scrollLeft = p.scrollLeft - (event.clientX - p.startX)
    el.scrollTop = p.scrollTop - (event.clientY - p.startY)
  }

  /** 平移收口（含指针中断）：释放态即可，capture 随指针 up 自动解除。 */
  const handlePanPointerEnd = (event: React.PointerEvent) => {
    if (panRef.current?.pointerId !== event.pointerId) return
    panRef.current = null
    setPanning(false)
  }

  // ── 边几何 ──
  const anchorOut = (n: CanvasNodeView) => ({ x: n.x + NODE_W, y: n.y + NODE_H / 2 })
  const anchorIn = (n: CanvasNodeView) => ({ x: n.x, y: n.y + NODE_H / 2 })

  const edgePath = (from: CanvasNodeView, to: CanvasNodeView): string => {
    const s = anchorOut(from)
    const t = anchorIn(to)
    const dx = Math.max(EDGE_GAP, Math.abs(t.x - s.x) / 2)
    return `M ${s.x} ${s.y} C ${s.x + dx} ${s.y}, ${t.x - dx} ${t.y}, ${t.x} ${t.y}`
  }

  const midPoint = (from: CanvasNodeView, to: CanvasNodeView): { x: number; y: number } => {
    // 三次贝塞尔 t=0.5：0.125·P0 + 0.375·P1 + 0.375·P2 + 0.125·P3
    const s = anchorOut(from)
    const t = anchorIn(to)
    const dx = Math.max(EDGE_GAP, Math.abs(t.x - s.x) / 2)
    return {
      x: 0.125 * s.x + 0.375 * (s.x + dx) + 0.375 * (t.x - dx) + 0.125 * t.x,
      y: 0.125 * s.y + 0.375 * s.y + 0.375 * t.y + 0.125 * t.y,
    }
  }

  return (
    <div
      className={`wf-canvas-scroll${ctrlHeld ? ' wf-canvas-pan' : ''}${panning ? ' wf-canvas-panning' : ''}${className ? ` ${className}` : ''}`}
      style={style}
      ref={scrollRef}
      onPointerDown={(e) => {
        // 触点跟踪（非鼠标）：第二指落下 → 多指平移接管（先于背景点按取消选中，
        // 平移意图不该动选中态）。
        if (e.pointerType !== 'mouse') {
          touchPointsRef.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
          if (touchPointsRef.current.size >= 2) startMultiPan(e.pointerId)
          else if (multiPanRef.current) startMultiPan(e.pointerId) // 平移中误触补充指：刷新基准防跳变
        }
        // Ctrl 按下：平移意图（handlePanPointerDown 接管），不触发背景取消选中
        if (e.ctrlKey) return
        // 背景空白处点按：取消选中（滚动条拖动/背景平移不触发——target 是 svg 空白才算）
        if (e.target === svgRef.current || (e.target as Element).classList?.contains('wf-canvas-surface')) {
          onSelect(null)
        }
      }}
      onPointerDownCapture={handlePanPointerDown}
      onPointerMove={(e) => {
        // 触点坐标随手更新（接力平移取基准用）
        if (touchPointsRef.current.has(e.pointerId)) {
          touchPointsRef.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
        }
        // 多指平移驱动：驱动指针位移取反滚动（拖右看左，与 Ctrl 平移一致）
        const mp = multiPanRef.current
        if (mp && mp.pointerId === e.pointerId && scrollRef.current) {
          scrollRef.current.scrollLeft = mp.scrollLeft - (e.clientX - mp.startX)
          scrollRef.current.scrollTop = mp.scrollTop - (e.clientY - mp.startY)
          return
        }
        handlePanPointerMove(e)
      }}
      onPointerUp={(e) => {
        if (e.pointerType !== 'mouse') releaseTouchPoint(e.pointerId)
        handlePanPointerEnd(e)
      }}
      onPointerCancel={(e) => {
        if (e.pointerType !== 'mouse') releaseTouchPoint(e.pointerId)
        handlePanPointerEnd(e)
      }}
    >
      <svg
        ref={svgRef}
        className="wf-canvas-surface"
        width={contentW}
        height={contentH}
        style={{ display: 'block' }}
        onPointerMove={handleSvgPointerMove}
        onPointerUp={handleSvgPointerUp}
        onPointerCancel={handleSvgPointerCancel}
      >
        <defs>
          {/* 箭头 marker 按 stroke 色预置（marker 不吃引用方 CSS 继承）。 */}
          <marker id="wf-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 1 L 9 5 L 0 9 z" style={{ fill: 'var(--text-muted, #6f7a8b)' }} />
          </marker>
          <marker id="wf-arrow-sel" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 1 L 9 5 L 0 9 z" style={{ fill: 'var(--accent-blue, #61a5ff)' }} />
          </marker>
          <marker id="wf-arrow-done" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 1 L 9 5 L 0 9 z" style={{ fill: 'var(--accent-green, #41d19c)' }} />
          </marker>
          <marker id="wf-arrow-running" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 1 L 9 5 L 0 9 z" style={{ fill: 'var(--accent-blue, #61a5ff)' }} />
          </marker>
          <marker id="wf-arrow-failed" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 1 L 9 5 L 0 9 z" style={{ fill: 'var(--accent-red, #ff6b6b)' }} />
          </marker>
          <marker id="wf-arrow-waiting" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 1 L 9 5 L 0 9 z" style={{ fill: 'var(--accent-amber, #f7bd54)' }} />
          </marker>
        </defs>

        {/* ── 边（先画在下层）── */}
        {edges.map((edge) => {
          const from = nodeById.get(edge.from)
          const to = nodeById.get(edge.to)
          if (!from || !to) return null
          const selected = selection?.kind === 'edge' && selection.id === edge.id
          const visual = statusVisual(edge.status)
          const d = edgePath(from, to)
          const mid = midPoint(from, to)
          const stroke = selected
            ? 'var(--accent-blue, #61a5ff)'
            : edge.status
              ? visual.stroke
              : 'var(--border-strong, #3b4658)'
          const markerId = selected ? 'wf-arrow-sel' : visual.markerId
          return (
            <g key={edge.id}>
              {/* 宽透明命中线： easier click target（tap 同样命中） */}
              <path
                d={d}
                fill="none"
                stroke="transparent"
                strokeWidth={16}
                style={{ pointerEvents: 'stroke', cursor: editable ? 'pointer' : 'default' }}
                onPointerDown={(e) => {
                  if (e.button !== 0) return
                  e.stopPropagation()
                  onSelect({ kind: 'edge', id: edge.id })
                }}
              />
              <path
                d={d}
                fill="none"
                stroke={stroke}
                strokeWidth={selected ? 2.4 : 1.6}
                markerEnd={`url(#${markerId})`}
                style={{ pointerEvents: 'none' }}
                className={edge.status === 'running' ? 'wf-edge-running' : undefined}
              />
              {edge.conditionLabel ? (
                <text
                  x={mid.x}
                  y={mid.y - 6}
                  textAnchor="middle"
                  fontSize={11}
                  style={{
                    fill: selected ? 'var(--accent-blue, #61a5ff)' : 'var(--text-secondary, #aab3c2)',
                    stroke: 'var(--bg-primary, #0b0d12)',
                    strokeWidth: 3,
                    paintOrder: 'stroke',
                    pointerEvents: 'none',
                  }}
                >
                  {edge.conditionLabel}
                </text>
              ) : null}
            </g>
          )
        })}

        {/* ── 连线预览（拖出中）── */}
        {pendingEdge ? (() => {
          const from = nodeById.get(pendingEdge.from)
          if (!from) return null
          const s = anchorOut(from)
          const dx = Math.max(EDGE_GAP, Math.abs(pendingEdge.x - s.x) / 2)
          return (
            <path
              d={`M ${s.x} ${s.y} C ${s.x + dx} ${s.y}, ${pendingEdge.x - dx} ${pendingEdge.y}, ${pendingEdge.x} ${pendingEdge.y}`}
              fill="none"
              stroke="var(--accent-blue, #61a5ff)"
              strokeWidth={1.8}
              strokeDasharray="6 5"
              style={{ pointerEvents: 'none' }}
            />
          )
        })() : null}

        {/* ── 节点 ── */}
        {nodes.map((node) => {
          const selected = selection?.kind === 'node' && selection.id === node.id
          const visual = statusVisual(node.status)
          // 运行图（readonly）未运行节点用中性灰（对齐图例「灰=跳过/未运行」），不借用
          // 类型色——类型色与状态色撞义（未运行 start 显绿=完成）；编辑态保持类型色优先。
          const stroke = !editable && !node.status
            ? NEUTRAL_STATUS_VISUAL.stroke
            : nodeStrokeColor(node.type, node.status)
          const iconColor = node.status ? visual.stroke : stroke
          return (
            <g
              key={node.id}
              transform={`translate(${node.x}, ${node.y})`}
              className={editable ? 'wf-node-hit' : undefined}
              onPointerDown={(e) => handleNodePointerDown(e, node)}
            >
              {/* 选中光环 */}
              {selected ? (
                <rect
                  x={-4}
                  y={-4}
                  width={NODE_W + 8}
                  height={NODE_H + 8}
                  rx={11}
                  fill="none"
                  stroke="var(--accent-blue, #61a5ff)"
                  strokeWidth={1}
                  strokeDasharray="4 3"
                  style={{ pointerEvents: 'none' }}
                />
              ) : null}
              <rect
                width={NODE_W}
                height={NODE_H}
                rx={8}
                style={{
                  fill: 'var(--bg-secondary, #11141b)',
                  stroke,
                  strokeWidth: selected ? 2 : 1.4,
                }}
              />
              {/* 类型图标（16×16 glyph，缩进 10/居中） */}
              <g transform="translate(10, 12)" color={iconColor} style={{ pointerEvents: 'none' }}>
                <g
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={1.6}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  {nodeTypeGlyph(node.type)}
                </g>
              </g>
              {/* 标题 + 副标题 */}
              <text
                x={32}
                y={26}
                fontSize={13}
                fontWeight={500}
                style={{ fill: 'var(--text-primary, #eef1f5)', pointerEvents: 'none' }}
              >
                {truncate(node.name || node.id, 15)}
              </text>
              <text
                x={32}
                y={44}
                fontSize={11}
                style={{ fill: 'var(--text-muted, #6f7a8b)', pointerEvents: 'none' }}
              >
                {truncate(node.subtitle ?? '', 20)}
              </text>
              {/* 运行态徽点（readonly/实例视图；running 呼吸动画） */}
              {node.status ? (
                <circle
                  cx={NODE_W - 14}
                  cy={14}
                  r={4.5}
                  className={visual.animated ? 'wf-status-running' : undefined}
                  style={{ fill: visual.badgeFill, pointerEvents: 'none' }}
                />
              ) : null}
              {/* 右侧出锚点（edit 限定；hover/触按加深；外层透明大圆扩触屏命中区） */}
              {editable ? (
                <g
                  className="wf-anchor-out"
                  onPointerEnter={() => setHoverAnchor(node.id)}
                  onPointerLeave={() => setHoverAnchor(cur => (cur === node.id ? null : cur))}
                  onPointerDown={(e) => handleAnchorPointerDown(e, node)}
                >
                  <circle
                    cx={NODE_W}
                    cy={NODE_H / 2}
                    r={hoverAnchor === node.id ? 6 : 4.5}
                    style={{
                      fill: hoverAnchor === node.id
                        ? 'var(--accent-blue, #61a5ff)'
                        : 'var(--bg-secondary, #11141b)',
                      stroke: 'var(--accent-blue, #61a5ff)',
                      strokeWidth: 1.5,
                      pointerEvents: 'none',
                    }}
                  />
                  <circle
                    cx={NODE_W}
                    cy={NODE_H / 2}
                    r={14}
                    fill="transparent"
                    style={{ pointerEvents: 'all' }}
                  />
                </g>
              ) : null}
              {/* 左侧入锚点（视觉提示，非交互） */}
              {editable ? (
                <circle
                  cx={0}
                  cy={NODE_H / 2}
                  r={3}
                  style={{
                    fill: 'var(--bg-secondary, #11141b)',
                    stroke: 'var(--border-strong, #3b4658)',
                    strokeWidth: 1.2,
                    pointerEvents: 'none',
                  }}
                />
              ) : null}
            </g>
          )
        })}
      </svg>
    </div>
  )
}

/** 标题/副标题截断（SVG text 无 ellipsis，手动按字符截）。 */
function truncate(s: string, max: number): string {
  return s.length > max ? `${s.slice(0, Math.max(0, max - 1))}…` : s
}
