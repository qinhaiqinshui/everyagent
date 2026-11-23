/**
 * 自研 SVG 工作流画布基座（无外部库——bare import 白名单约束）。
 *
 * 能力（数据驱动，位置即 NodeDef.x/y——位置存定义）：
 * - 节点矩形（类型图标 + 标题 + 简略信息）+ 贝塞尔边（from 右锚点 → to 左锚点）；
 * - 选中态（节点/边高亮）、背景点击取消选中；
 * - mode='edit'：节点拖拽（mousedown→window mousemove 改 x/y）、从右锚点拖出连线
 *   到目标节点（mouseup 命中测试）、Delete 删除由宿主面板处理（本组件只回调选中）；
 * - mode='readonly'：禁一切交互（实例视图复用基座，步骤 10）；
 * - 运行态着色/动画：node.status → renderStates 色彩（TaskStatusDot 色彩语言）。
 *
 * 坐标系：svg 与定义坐标 1:1（无缩放），外层 div 滚动平移；client → svg 坐标经
 * getBoundingClientRect 换算（无 viewBox 缩放故为线性平移）。
 */
import React from 'react'
import { nodeTypeGlyph } from '../icons'
import { nodeStrokeColor, statusVisual, type RenderStatus } from './renderStates'

// ─── 几何常量 ─────────────────────────────────────────────────────────────

export const NODE_W = 180
export const NODE_H = 64
const CANVAS_W = 3200
const CANVAS_H = 2400
const EDGE_GAP = 60

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
.wf-canvas-scroll { overflow: auto; width: 100%; height: 100%; background: var(--bg-primary, #0b0d12); }
.wf-canvas-surface { background-image: radial-gradient(circle, var(--border-light, #1b202a) 1px, transparent 1px); background-size: 24px 24px; }
.wf-node-hit { cursor: grab; }
.wf-node-hit:active { cursor: grabbing; }
.wf-anchor-out { cursor: crosshair; }
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
  /** 拖拽态（window listener 闭包持有，不进 state 防重渲染抖动）。 */
  const dragRef = React.useRef<{ id: string; dx: number; dy: number } | null>(null)
  /** 连线态源节点（pendingEdge state 只为渲染预览线）。 */
  const connectRef = React.useRef<{ from: string } | null>(null)
  const [pendingEdge, setPendingEdge] = React.useState<{ from: string; x: number; y: number } | null>(null)
  const [hoverAnchor, setHoverAnchor] = React.useState<string | null>(null)

  const editable = mode === 'edit'
  const nodeById = React.useMemo(() => {
    const m = new Map<string, CanvasNodeView>()
    for (const n of nodes) m.set(n.id, n)
    return m
  }, [nodes])

  /** client 坐标 → svg 定义坐标（1:1 无缩放，线性平移）。 */
  const toSvgPoint = React.useCallback((clientX: number, clientY: number): { x: number; y: number } => {
    const svg = svgRef.current
    if (!svg) return { x: 0, y: 0 }
    const rect = svg.getBoundingClientRect()
    return { x: clientX - rect.left, y: clientY - rect.top }
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

  /** 节点主体按下：选中 + （edit）启动拖拽。 */
  const handleNodeMouseDown = (event: React.MouseEvent, node: CanvasNodeView) => {
    if (event.button !== 0) return
    onSelect({ kind: 'node', id: node.id })
    if (!editable || !onNodeMove) return
    event.preventDefault()
    const p = toSvgPoint(event.clientX, event.clientY)
    dragRef.current = { id: node.id, dx: p.x - node.x, dy: p.y - node.y }
    const onMove = (ev: MouseEvent) => {
      const d = dragRef.current
      if (!d) return
      const q = toSvgPoint(ev.clientX, ev.clientY)
      onNodeMove(d.id, Math.max(0, Math.round(q.x - d.dx)), Math.max(0, Math.round(q.y - d.dy)))
    }
    const onUp = () => {
      dragRef.current = null
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  /** 右锚点按下：启动连线（预览线跟随光标，mouseup 命中目标节点即回调）。 */
  const handleAnchorMouseDown = (event: React.MouseEvent, node: CanvasNodeView) => {
    if (!editable || !onCreateEdge || event.button !== 0) return
    event.preventDefault()
    event.stopPropagation()
    connectRef.current = { from: node.id }
    const p = toSvgPoint(event.clientX, event.clientY)
    setPendingEdge({ from: node.id, x: p.x, y: p.y })
    const onMove = (ev: MouseEvent) => {
      const c = connectRef.current
      if (!c) return
      const q = toSvgPoint(ev.clientX, ev.clientY)
      setPendingEdge({ from: c.from, x: q.x, y: q.y })
    }
    const onUp = (ev: MouseEvent) => {
      const c = connectRef.current
      connectRef.current = null
      setPendingEdge(null)
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      if (!c) return
      const q = toSvgPoint(ev.clientX, ev.clientY)
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
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
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
      className={`wf-canvas-scroll${className ? ` ${className}` : ''}`}
      style={style}
      ref={scrollRef}
      onMouseDown={(e) => {
        // 背景空白处点击：取消选中（拖拽滚动条不触发——mousedown 目标是 svg 才算）
        if (e.target === svgRef.current || (e.target as Element).classList?.contains('wf-canvas-surface')) {
          onSelect(null)
        }
      }}
    >
      <svg
        ref={svgRef}
        className="wf-canvas-surface"
        width={CANVAS_W}
        height={CANVAS_H}
        style={{ display: 'block' }}
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
              {/* 宽透明命中线： easier click target */}
              <path
                d={d}
                fill="none"
                stroke="transparent"
                strokeWidth={16}
                style={{ pointerEvents: 'stroke', cursor: editable ? 'pointer' : 'default' }}
                onMouseDown={(e) => {
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
          const stroke = nodeStrokeColor(node.type, node.status)
          const iconColor = node.status ? visual.stroke : stroke
          return (
            <g
              key={node.id}
              transform={`translate(${node.x}, ${node.y})`}
              className={editable ? 'wf-node-hit' : undefined}
              onMouseDown={(e) => handleNodeMouseDown(e, node)}
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
              {/* 左侧类型色条 */}
              <rect
                x={0}
                y={8}
                width={3}
                height={NODE_H - 16}
                rx={1.5}
                style={{ fill: stroke, pointerEvents: 'none' }}
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
              {/* 右侧出锚点（edit 限定；hover 加深） */}
              {editable ? (
                <circle
                  className="wf-anchor-out"
                  cx={NODE_W}
                  cy={NODE_H / 2}
                  r={hoverAnchor === node.id ? 6 : 4.5}
                  style={{
                    fill: hoverAnchor === node.id
                      ? 'var(--accent-blue, #61a5ff)'
                      : 'var(--bg-secondary, #11141b)',
                    stroke: 'var(--accent-blue, #61a5ff)',
                    strokeWidth: 1.5,
                  }}
                  onMouseEnter={() => setHoverAnchor(node.id)}
                  onMouseLeave={() => setHoverAnchor(cur => (cur === node.id ? null : cur))}
                  onMouseDown={(e) => handleAnchorMouseDown(e, node)}
                />
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
