/**
 * 画布连线路由（避障绕行 + 回线 + 平行边错开）：把「每条线怎么弯」从 WorkflowCanvas
 * 抽成纯函数，与 autoLayout 同款——零 React/DOM 依赖，便于脱离 DOM 自测与复用
 * （定义编辑器与实例只读画布共用同一套走线）。
 *
 * 为什么需要它：自上而下的分层图里有两类「看不出谁连谁」的连线——
 *  ① 跨层边（条件跳过中间某步直连下游）：两端落在同一列的不同层，单条贝塞尔退化成
 *     一条竖线——从源底锚直插目标顶锚，既穿过中间层的同列节点矩形，又与该列的短边
 *     整段共线（几条线叠成一条，画布上分不清是几条）；
 *  ② 回指边（重试/回到上一步）与自环：目标在上方，而锚点是「底出顶入」，单条贝塞尔
 *     同样退化成穿过节点的竖线。
 *
 * 处理策略：默认仍画与主流方向一致的贝塞尔（相邻层、无遮挡——绝大多数边，观感不变）；
 * 一旦该曲线会压到别的节点矩形（①）或目标在上方（②），改走「绕行」：
 *   源底锚 →（源行下方的横向带）→（列间空隙里的纵向通道）→（目标行上方的横向带）
 *   → 垂直下探目标顶锚；回指边/自环走同一套折线，方向相反（侧向回线）。
 *
 * 两条线不叠在一起：横向带与纵向通道都登记在册（used），新线只要与之横向邻近且区间
 * 重叠就换下一档——同一个空隙按 CHANNEL_STEP 并排、同一个锚点的横向带按 BAND_STEP
 * 逐档错开；实在挤不下才退一步允许并行（宁可两条线并行，也不让线穿过节点——穿节点
 * 更看不清）。通道候选取自节点矩形的列间空隙（分层网格里天然是宽 ROW_GAP-NODE_W 的
 * 空档）+ 最左/最右列外侧，按「离天然直线最近」排序：绕行代价最小。
 */
import { NODE_H, NODE_W } from './geometry'

// ─── 几何常量 ─────────────────────────────────────────────────────────────

/** 正向边贝塞尔控制点的最小纵向偏移（短边也要有弧度，与历史观感一致）。 */
export const EDGE_CURVE_MIN_DY = 60
/** 绕行折线的横向带离源/目标节点边缘的间距（落进层间空隙）。 */
const ROUTE_LEAD = 34
/** 绕行折线的转角圆角半径。 */
const ROUTE_RADIUS = 16
/** 回线通道相对端点列右缘的间距。 */
const LOOP_GAP = 34
/** 横向带与节点矩形的净空（节点矩形外扩量）。 */
const BAND_CLEARANCE = 16
/** 纵向通道与节点矩形的净空（窄于横向带：线宽 1.6px，留 10px 已足够分辨）。 */
const CHANNEL_CLEARANCE = 10
/** 平行边（同一 from→to 多条）彼此的横向错开量。 */
const PARALLEL_GAP = 26
/** 纵向通道并排档距（同一空隙里可并行多条线）。 */
const CHANNEL_STEP = 16
/** 每个空隙的并排档数（0 与 ±CHANNEL_STEP；空隙 60px 时可容三条线）。 */
const CHANNEL_SLOTS = 1
/** 列外侧通道基点相对列缘的偏移。 */
const CHANNEL_OFFSET = 30
/** 横向带逐档错开的档距与档数（多路汇入/汇出同一节点时不整段压在同一条横线上）。 */
const BAND_STEP = 9
const BAND_LANES = 5
/** 判定「两条线重叠」的容差：横向带按档距一半、通道按并排档距、长距离贝塞尔走廊按 8px。 */
const BAND_TOL = 6
const CHANNEL_TOL = 12
const CORRIDOR_TOL = 8
/** 贝塞尔「长距离走廊」的判定阈值：横向跨度超过三列（720px，即中间至少跨过一整列
 *  节点）才会与另一条长边的中段重叠——相邻层/近邻层的边即便同源同目标，也只是各自
 *  张成扇形弧线，不必登记走廊（否则会把普通扇出/扇入改成绕行折线，反而突兀）。 */
const LONG_RUN = 720
/** 目标锚点至少要比源锚点高这么多才按回线处理（否则算退化正向边）。 */
const LOOP_MIN_ASCENT = 8
/** 坐标系左边界保护（通道不能落在 svg 负坐标区，画布滚不到）。 */
const MIN_X = 8
/** 候选通道试算上限（按「离天然直线最近」排序，够用即止——稠密图上避免退化成
 *  O(节点²×边) 的最坏情况）。 */
const MAX_CHANNELS = 36
/** 浮点比较容差。 */
const EPS = 0.5

/** 路由只需要节点的 id 与左上角坐标（即 NodeDef.x/y；尺寸取 geometry 常量）。 */
export interface RouteNode {
  id: string
  x: number
  y: number
}

/** 路由只需要边 id 与两端节点 id。 */
export interface RouteEdge {
  id: string
  from: string
  to: string
}

/** 单条边的路由产物：svg path 的 d + 条件标签落点。 */
export interface EdgeRoute {
  d: string
  mid: { x: number; y: number }
}

interface Point {
  x: number
  y: number
}

interface Rect {
  id: string
  x: number
  y: number
  w: number
  h: number
}

/** 轴对齐走线段（横/竖；十字交叉不算重叠，故只登记轴对齐段）。 */
interface Segment {
  x1: number
  y1: number
  x2: number
  y2: number
}

// ─── 主入口 ───────────────────────────────────────────────────────────────

/**
 * 计算全部边的路由（按入参边序依次处理——顺序确定，且「先到先得」占用通道）；
 * 返回 edgeId → 路由。引用不存在节点的边不出现在结果里（调用方跳过即可）。
 */
export function computeEdgeRoutes(nodes: RouteNode[], edges: RouteEdge[]): Map<string, EdgeRoute> {
  const routes = new Map<string, EdgeRoute>()
  const rects: Rect[] = nodes.map(n => ({ id: n.id, x: n.x, y: n.y, w: NODE_W, h: NODE_H }))
  const byId = new Map<string, Rect>(rects.map(r => [r.id, r] as const))
  const parallel = parallelOffsets(edges)
  const used: Segment[] = []
  for (const edge of edges) {
    const from = byId.get(edge.from)
    const to = byId.get(edge.to)
    if (!from || !to) continue
    routes.set(edge.id, routeOne(from, to, rects, used, parallel.get(edge.id) ?? 0))
  }
  return routes
}

/** 同一对 (from→to) 的多条平行边：各自横向错开，避免整段重合。 */
function parallelOffsets(edges: RouteEdge[]): Map<string, number> {
  const groups = new Map<string, string[]>()
  for (const e of edges) {
    const key = `${e.from}\u0000${e.to}`
    const list = groups.get(key)
    if (list) list.push(e.id)
    else groups.set(key, [e.id])
  }
  const offsets = new Map<string, number>()
  for (const ids of groups.values()) {
    if (ids.length < 2) continue
    ids.forEach((id, i) => offsets.set(id, (i - (ids.length - 1) / 2) * PARALLEL_GAP))
  }
  return offsets
}

// ─── 单边路由 ─────────────────────────────────────────────────────────────

function routeOne(from: Rect, to: Rect, rects: Rect[], used: Segment[], lateral: number): EdgeRoute {
  const s = anchorOut(from)
  const t = anchorIn(to)
  if (t.y < s.y + LOOP_MIN_ASCENT) return loopRoute(from, to, s, t, rects, used, lateral)
  const dy = Math.max(EDGE_CURVE_MIN_DY, (t.y - s.y) / 2)
  const c1 = { x: s.x + lateral, y: s.y + dy }
  const c2 = { x: t.x + lateral, y: t.y - dy }
  const bezier = `M ${fmt(s.x)} ${fmt(s.y)} C ${fmt(c1.x)} ${fmt(c1.y)}, ${fmt(c2.x)} ${fmt(c2.y)}, ${fmt(t.x)} ${fmt(t.y)}`
  const bezierRoute: EdgeRoute = { d: bezier, mid: bezierPoint(s, c1, c2, t, 0.5) }
  const corridor = bezierCorridor(s, c1, c2, t)
  const blocked =
    samplesHit(bezierSamples(s, c1, c2, t), blockers(rects, from.id, to.id, 4)) ||
    (corridor !== null && busyNear(used, corridor, CORRIDOR_TOL))
  if (!blocked) {
    if (corridor) used.push(corridor)
    return bezierRoute
  }
  return routedRoute(from, to, s, t, rects, used) ?? bezierRoute
}

/**
 * 贝塞尔边的「占用走廊」：横向跨度大于两列（或纵向跨度大于两层）的边，其中段几乎是
 * 一条水平（竖直）线——多条同样汇入/离开同一个节点的长边会把中段叠在一起（宽图上
 * 十几条线糊成一条）。把这条中段当作占用段登记，后来的长边就会改走逐档错开的绕行线。
 */
function bezierCorridor(s: Point, c1: Point, c2: Point, t: Point): Segment | null {
  const dx = Math.abs(t.x - s.x)
  const dy = Math.abs(t.y - s.y)
  const mid = bezierPoint(s, c1, c2, t, 0.5)
  if (dx >= LONG_RUN && dx > dy) return seg(Math.min(s.x, t.x), mid.y, Math.max(s.x, t.x), mid.y)
  if (dy >= LONG_RUN && dy > dx) return seg(mid.x, Math.min(s.y, t.y), mid.x, Math.max(s.y, t.y))
  return null
}

/** 正向绕行：横向带 → 纵向通道 → 横向带 → 下探目标顶锚（逐档找不被占用的走线）。 */
function routedRoute(from: Rect, to: Rect, s: Point, t: Point, rects: Rect[], used: Segment[]): EdgeRoute | null {
  const bandBlockers = blockers(rects, from.id, to.id, BAND_CLEARANCE)
  const channelBlockers = blockers(rects, from.id, to.id, CHANNEL_CLEARANCE)
  const candidates = channelCandidates(rects, s.x, t.x)
  const attempt = (strict: boolean): EdgeRoute | null => {
    for (let lane = 0; lane <= BAND_LANES; lane++) {
      const yA = s.y + ROUTE_LEAD + lane * BAND_STEP // 源行下方的横向带
      const yB = t.y - ROUTE_LEAD - lane * BAND_STEP // 目标行上方的横向带
      if (yB - yA < ROUTE_RADIUS * 2) break
      for (const x of candidates) {
        const bandA = seg(s.x, yA, x, yA)
        const channel = seg(x, yA, x, yB)
        const bandB = seg(x, yB, t.x, yB)
        if (segmentHits(bandA, bandBlockers)) continue
        if (segmentHits(channel, channelBlockers)) continue
        if (segmentHits(bandB, bandBlockers)) continue
        if (strict) {
          if (busyNear(used, bandA, BAND_TOL) || busyNear(used, channel, CHANNEL_TOL) || busyNear(used, bandB, BAND_TOL)) continue
        }
        used.push(bandA, channel, bandB)
        return {
          d: roundedPath([s, { x: s.x, y: yA }, { x, y: yA }, { x, y: yB }, { x: t.x, y: yB }, t], ROUTE_RADIUS),
          mid: { x, y: (yA + yB) / 2 },
        }
      }
    }
    return null
  }
  return attempt(true) ?? attempt(false)
}

/**
 * 回线（回指边 + 自环）：源底锚下探 → 走端点列外侧的纵向通道 → 目标行上方横向带回折 →
 * 垂直下探目标顶锚。优先走右侧（图向右铺开，右侧空间稳定），右侧无空位再试左侧。
 */
function loopRoute(from: Rect, to: Rect, s: Point, t: Point, rects: Rect[], used: Segment[], lateral: number): EdgeRoute {
  const bandBlockers = blockers(rects, from.id, to.id, BAND_CLEARANCE)
  const channelBlockers = blockers(rects, from.id, to.id, CHANNEL_CLEARANCE)
  const rightBase = Math.max(from.x, to.x) + NODE_W + LOOP_GAP + lateral
  const leftBase = Math.min(from.x, to.x) - LOOP_GAP - lateral
  const candidates: number[] = []
  for (let k = 0; k < 8; k++) candidates.push(rightBase + k * CHANNEL_STEP)
  for (let k = 0; k < 8; k++) candidates.push(leftBase - k * CHANNEL_STEP)
  const attempt = (strict: boolean): EdgeRoute | null => {
    for (let lane = 0; lane <= BAND_LANES; lane++) {
      const downY = s.y + ROUTE_LEAD + lane * BAND_STEP
      const upY = t.y - ROUTE_LEAD - lane * BAND_STEP
      for (const x of candidates) {
        if (x < MIN_X) continue
        const stubA = seg(s.x, s.y, s.x, downY)
        const bandA = seg(s.x, downY, x, downY)
        const channel = seg(x, downY, x, upY)
        const bandB = seg(x, upY, t.x, upY)
        const stubB = seg(t.x, upY, t.x, t.y)
        if (segmentHits(stubA, channelBlockers) || segmentHits(bandA, bandBlockers)) continue
        if (segmentHits(channel, channelBlockers) || segmentHits(bandB, bandBlockers)) continue
        if (segmentHits(stubB, channelBlockers)) continue
        if (strict) {
          if (busyNear(used, bandA, BAND_TOL) || busyNear(used, channel, CHANNEL_TOL) || busyNear(used, bandB, BAND_TOL)) continue
        }
        used.push(bandA, channel, bandB)
        return {
          d: roundedPath(
            [s, { x: s.x, y: downY }, { x, y: downY }, { x, y: upY }, { x: t.x, y: upY }, t],
            ROUTE_RADIUS,
          ),
          mid: { x, y: (downY + upY) / 2 },
        }
      }
    }
    return null
  }
  const routed = attempt(true) ?? attempt(false)
  if (routed) return routed
  // 兜底：无可用通道时仍用贝塞尔连线（至少方向可见）
  const dy = Math.max(EDGE_CURVE_MIN_DY, Math.abs(t.y - s.y) / 2)
  const c1 = { x: s.x + lateral, y: s.y + dy }
  const c2 = { x: t.x + lateral, y: t.y - dy }
  return {
    d: `M ${fmt(s.x)} ${fmt(s.y)} C ${fmt(c1.x)} ${fmt(c1.y)}, ${fmt(c2.x)} ${fmt(c2.y)}, ${fmt(t.x)} ${fmt(t.y)}`,
    mid: bezierPoint(s, c1, c2, t, 0.5),
  }
}

// ─── 通道候选与占用 ───────────────────────────────────────────────────────

/**
 * 纵向通道候选：取所有节点矩形横向区间合并后的「列间空隙」——相邻两列之间的空隙取
 * 正中、最左/最右列外侧留 CHANNEL_OFFSET 外沿通道；每个基点再按 CHANNEL_STEP 左右
 * 并排若干档（同一空隙可并行多条线）。按「离天然直线最近」排序（越近绕行代价越小），
 * 同距时取右侧（图向右铺开，观感自然）。
 */
function channelCandidates(rects: Rect[], sx: number, tx: number): number[] {
  const spans: Array<[number, number]> = rects
    .map(r => [r.x, r.x + r.w] as [number, number])
    .sort((a, b) => a[0] - b[0])
  const merged: Array<[number, number]> = []
  for (const span of spans) {
    const last = merged[merged.length - 1]
    if (last && span[0] <= last[1] + 1) last[1] = Math.max(last[1], span[1])
    else merged.push([span[0], span[1]])
  }
  const bases = new Set<number>()
  merged.forEach((span, i) => {
    bases.add(span[1] + CHANNEL_OFFSET) // 本列右侧空隙
    const next = merged[i + 1]
    // 空隙窄于两侧净空需求时跳过（放不下一条通道，交给外沿候选）
    if (next && next[0] - span[1] > CHANNEL_CLEARANCE * 2 + 4) bases.add((span[1] + next[0]) / 2)
  })
  bases.add(merged[0][0] - CHANNEL_OFFSET) // 最左列外侧（空间无界，可多档外推）
  bases.add(merged[merged.length - 1][1] + CHANNEL_OFFSET) // 最右列外侧
  const xs = new Set<number>()
  for (const base of bases) {
    for (let k = 0; k <= CHANNEL_SLOTS; k++) {
      xs.add(base + k * CHANNEL_STEP)
      if (k > 0) xs.add(base - k * CHANNEL_STEP)
    }
  }
  const preferred = (sx + tx) / 2
  return [...xs]
    .filter(x => x >= MIN_X)
    .sort((a, b) => Math.abs(a - preferred) - Math.abs(b - preferred) || b - a)
    .slice(0, MAX_CHANNELS)
}

/** 该段是否与已占用的走线重叠（同向、横向邻近且区间重叠；十字交叉不算）。 */
function busyNear(used: Segment[], seg: Segment, tol: number): boolean {
  const horizontal = Math.abs(seg.y2 - seg.y1) < EPS
  for (const u of used) {
    if (horizontal !== (Math.abs(u.y2 - u.y1) < EPS)) continue // 横竖交叉：允许
    if (horizontal) {
      if (Math.abs(seg.y1 - u.y1) >= tol) continue
      if (Math.min(seg.x1, seg.x2) > Math.max(u.x1, u.x2) - EPS) continue
      if (Math.max(seg.x1, seg.x2) < Math.min(u.x1, u.x2) + EPS) continue
    } else {
      if (Math.abs(seg.x1 - u.x1) >= tol) continue
      if (Math.min(seg.y1, seg.y2) > Math.max(u.y1, u.y2) - EPS) continue
      if (Math.max(seg.y1, seg.y2) < Math.min(u.y1, u.y2) + EPS) continue
    }
    return true
  }
  return false
}

// ─── 碰撞检测 ─────────────────────────────────────────────────────────────

function anchorOut(n: Rect): Point {
  return { x: n.x + n.w / 2, y: n.y + n.h }
}

function anchorIn(n: Rect): Point {
  return { x: n.x + n.w / 2, y: n.y }
}

/** 轴对齐线段（横/竖）。 */
function seg(x1: number, y1: number, x2: number, y2: number): Segment {
  return { x1, y1, x2, y2 }
}

/** 除两端节点外的全部节点矩形（外扩 clearance；两端节点必然贴着自己的连线，不算遮挡）。 */
function blockers(rects: Rect[], fromId: string, toId: string, clearance: number): Rect[] {
  const out: Rect[] = []
  for (const r of rects) {
    if (r.id === fromId || r.id === toId) continue
    out.push({ id: r.id, x: r.x - clearance, y: r.y - clearance, w: r.w + clearance * 2, h: r.h + clearance * 2 })
  }
  return out
}

/** 轴对齐线段与矩形是否相交（端点含边界）。 */
function segmentHits(segment: Segment, rects: Rect[]): boolean {
  const minX = Math.min(segment.x1, segment.x2)
  const maxX = Math.max(segment.x1, segment.x2)
  const minY = Math.min(segment.y1, segment.y2)
  const maxY = Math.max(segment.y1, segment.y2)
  for (const r of rects) {
    if (maxX < r.x || minX > r.x + r.w || maxY < r.y || minY > r.y + r.h) continue
    return true
  }
  return false
}

/** 采样点是否落在任一矩形内（贝塞尔曲线近似检测）。 */
function samplesHit(points: Point[], rects: Rect[]): boolean {
  for (const p of points) {
    for (const r of rects) {
      if (p.x > r.x && p.x < r.x + r.w && p.y > r.y && p.y < r.y + r.h) return true
    }
  }
  return false
}

// ─── 曲线工具 ─────────────────────────────────────────────────────────────

function bezierSamples(p0: Point, p1: Point, p2: Point, p3: Point, count = 48): Point[] {
  const out: Point[] = []
  for (let i = 1; i < count; i++) out.push(bezierPoint(p0, p1, p2, p3, i / count))
  return out
}

/** 三次贝塞尔取点（t=0.5 即条件标签落点）。 */
function bezierPoint(p0: Point, p1: Point, p2: Point, p3: Point, u: number): Point {
  const mu = 1 - u
  const a = mu * mu * mu
  const b = 3 * mu * mu * u
  const c = 3 * mu * u * u
  const d = u * u * u
  return { x: a * p0.x + b * p1.x + c * p2.x + d * p3.x, y: a * p0.y + b * p1.y + c * p2.y + d * p3.y }
}

/** 折线 → svg path（转角用二次贝塞尔倒圆角；相邻重合点自动跳过）。 */
function roundedPath(raw: Point[], radius: number): string {
  const pts = dedupe(raw)
  if (pts.length < 2) return ''
  const parts = [`M ${fmt(pts[0].x)} ${fmt(pts[0].y)}`]
  for (let i = 1; i < pts.length - 1; i++) {
    const prev = pts[i - 1]
    const cur = pts[i]
    const next = pts[i + 1]
    const r = Math.min(radius, dist(prev, cur) / 2, dist(next, cur) / 2)
    const a = towards(cur, prev, r)
    const b = towards(cur, next, r)
    parts.push(`L ${fmt(a.x)} ${fmt(a.y)}`, `Q ${fmt(cur.x)} ${fmt(cur.y)} ${fmt(b.x)} ${fmt(b.y)}`)
  }
  const last = pts[pts.length - 1]
  parts.push(`L ${fmt(last.x)} ${fmt(last.y)}`)
  return parts.join(' ')
}

function dedupe(points: Point[]): Point[] {
  const out: Point[] = []
  for (const p of points) {
    const prev = out[out.length - 1]
    if (prev && Math.abs(prev.x - p.x) < EPS && Math.abs(prev.y - p.y) < EPS) continue
    out.push(p)
  }
  return out
}

function dist(a: Point, b: Point): number {
  return Math.hypot(b.x - a.x, b.y - a.y)
}

/** 从 from 沿 from→to 方向走 len 的点（折线倒角用）。 */
function towards(from: Point, to: Point, len: number): Point {
  const d = dist(from, to)
  if (d < EPS) return { x: from.x, y: from.y }
  return { x: from.x + ((to.x - from.x) / d) * len, y: from.y + ((to.y - from.y) / d) * len }
}

/** path 数值格式：一位小数（path 体积可控且亚像素误差不可见）。 */
function fmt(n: number): number {
  return Math.round(n * 10) / 10
}