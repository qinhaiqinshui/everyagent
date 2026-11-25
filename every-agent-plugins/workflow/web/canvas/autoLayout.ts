/**
 * 工作流定义「自动布局」算法核（编辑器工具栏「自动布局」按钮）。
 *
 * Sugiyama 分层布局的简化实现，纯函数、零 React/画布依赖（几何常量除外），
 * 便于脱离 DOM 自测。流程四步：
 *   ① 回边识别——DFS（灰→灰 = 指向栈上祖先）把有环图的「回指边」标为逆向边，
 *      分层只看前向边、逆向边留作绘制时的回线（从右下层弯回左上层）；
 *      指向 start 的边一律先标回边（start 是执行入口，入边语义上是「回到起点
 *      重跑」），这同时保证 start 无前向入边、分层必落第 0 层（需求：start 固定
 *      第 0 层）。
 *   ② 最长路分层——去回边后必为 DAG（任一有向环至少含一条 DFS 回边），
 *      layer(v) = max(layer(前向前驱)) + 1，前向入度 0 的根层为 0；Kahn 按
 *      定义序取队，结果确定。
 *   ③ 层内排序——初序 = 定义列表序（start 置顶）；再做若干轮重心（barycenter）
 *      微调：按前驱/后继在邻层的行号均值重排，减少连线交叉；无邻接的节点锚在
 *      当前行不被推走；稳定排序 + 平局保持现序，结果确定。
 *   ④ 网格坐标——x = 层深×列距、y = 层内序×行距；间距取节点尺寸的合理倍数
 *      （NODE_W / NODE_H，见常量注释），60 节点规模不重叠。孤立节点（无边，
 *      自环除外）集中排在主流最深列右侧的独立列，不与主流重叠。
 *
 * 调用方（DefinitionEditorPanel）经 updateDef 一次性写回全部节点 x/y——与
 * 手工拖拽同一条定义更新链（dirty / 保存语义一致），其余字段一律不动。
 */
import type { WorkflowDefinition, WorkflowNodeDef } from '../workflowGateway'
import { NODE_H, NODE_W } from './geometry'

// ─── 间距常量（节点尺寸的合理倍数；60 节点规模不重叠） ─────────────────────

/** 列间距（层深方向）：NODE_W 的 ~1.7 倍——给左右向贝塞尔连线留出弧度空间。 */
export const LAYOUT_COL_GAP = 300
/** 行间距（层内方向）：NODE_H 的 ~1.9 倍——层内节点互不重叠且留出选中余量。 */
export const LAYOUT_ROW_GAP = 120
/** 网格左/上留白（第 0 列/行的起点）。 */
const LAYOUT_MARGIN = 40
/** 重心排序轮数（前向贴前驱 + 后向贴后继各算一轮；两轮后收益递减）。 */
const BARYCENTER_PASSES = 2

/** 布局产物：节点坐标（整数；画布与定义坐标 1:1，无缩放）。 */
export interface LayoutPosition {
  x: number
  y: number
}

/** 布局结果（positions 覆盖全部有效节点；stats 供调用方 toast 汇报）。 */
export interface AutoLayoutResult {
  /** nodeId → 坐标。 */
  positions: Map<string, LayoutPosition>
  /** 主流分层列数（不含孤立区；无边图为 0）。 */
  layerCount: number
  /** 识别为逆向的回边条数（含自环；不参与分层，绘制时为回线）。 */
  backEdgeCount: number
  /** 孤立节点（无边，自环除外）个数——集中排在右侧独立列。 */
  orphanCount: number
}

// ─── 主入口 ───────────────────────────────────────────────────────────────

/** 计算自动布局：输入定义，输出全部节点的新坐标（不修改入参）。 */
export function computeAutoLayout(def: WorkflowDefinition): AutoLayoutResult {
  const nodes: WorkflowNodeDef[] = (def.nodes ?? []).filter(n => !!n && !!n.id)
  const positions = new Map<string, LayoutPosition>()
  const result: AutoLayoutResult = { positions, layerCount: 0, backEdgeCount: 0, orphanCount: 0 }
  if (nodes.length === 0) return result

  const orderIndex = new Map<string, number>(nodes.map((n, i) => [n.id, i] as const))
  const startId: string | null = nodes.find(n => n.type === 'start')?.id ?? null

  // ── 边整理：悬空边（引用不存在节点）不参与；自环天然是回边（计数、不入分层）。 ──
  let selfLoops = 0
  const edges: Array<{ from: string; to: string }> = []
  for (const e of def.edges ?? []) {
    if (!e || !e.from || !e.to) continue
    if (!orderIndex.has(e.from) || !orderIndex.has(e.to)) continue // 悬空：跳过
    if (e.from === e.to) {
      selfLoops++
      continue
    }
    edges.push({ from: e.from, to: e.to })
  }

  // ── 参与网格的节点：有边（自环除外）者 + start（start 无边也放第 0 层，不进孤立区）。 ──
  const connected = new Set<string>()
  for (const e of edges) {
    connected.add(e.from)
    connected.add(e.to)
  }
  const gridNodes = new Set<string>(connected)
  if (startId !== null) gridNodes.add(startId)
  const orphans = nodes.filter(n => !gridNodes.has(n.id))

  // ── ① 回边识别。先标「指向 start」的边（见文件头注释），再 DFS 分类其余环边。 ──
  const isBack: boolean[] = new Array<boolean>(edges.length).fill(false)
  edges.forEach((e, i) => {
    if (e.to === startId) isBack[i] = true
  })
  /** 前向邻接表（node → 出边下标；回边不在内）。 */
  const adjacency = new Map<string, number[]>()
  edges.forEach((e, i) => {
    if (isBack[i]) return
    const list = adjacency.get(e.from)
    if (list) list.push(i)
    else adjacency.set(e.from, [i])
  })
  const WHITE = 0
  const GRAY = 1
  const BLACK = 2
  const color = new Map<string, number>(nodes.map(n => [n.id, WHITE] as const))
  const visit = (u: string): void => {
    color.set(u, GRAY)
    for (const i of adjacency.get(u) ?? []) {
      const v = edges[i].to
      const c = color.get(v) ?? WHITE
      if (c === GRAY) {
        isBack[i] = true // 指向栈上祖先 = 回指边 → 逆向，不参与分层
      } else if (c === WHITE) {
        visit(v)
      }
      // BLACK → 前向/交叉边，保持正向
    }
    color.set(u, BLACK)
  }
  // DFS 根顺序：先原始入度（自环除外）0 的节点——它们不可能是任何环的成员，
  // 从它们出发先定主流骨架；之后从剩余白点（纯环）按定义序续扫，保证每条边都被分类。
  const origIndeg = new Map<string, number>()
  for (const e of edges) {
    origIndeg.set(e.to, (origIndeg.get(e.to) ?? 0) + 1)
  }
  const seenRoots = new Set<string>()
  const dfsRoots: string[] = []
  const pushDfsRoot = (id: string): void => {
    if (seenRoots.has(id) || (origIndeg.get(id) ?? 0) > 0) return
    seenRoots.add(id)
    dfsRoots.push(id)
  }
  if (startId !== null) pushDfsRoot(startId)
  for (const n of nodes) pushDfsRoot(n.id)
  for (const r of dfsRoots) visit(r)
  for (const n of nodes) {
    if (gridNodes.has(n.id) && (color.get(n.id) ?? WHITE) === WHITE) visit(n.id)
  }

  // ── ② 最长路分层（Kahn；同批按定义序入队 → 结果确定）。 ──
  const fwdIndeg = new Map<string, number>()
  edges.forEach((e, i) => {
    if (isBack[i]) return
    fwdIndeg.set(e.to, (fwdIndeg.get(e.to) ?? 0) + 1)
  })
  const layerOf = new Map<string, number>()
  const queue: string[] = []
  const enqueueRoots = (): void => {
    // start 最先，再按定义序（同一轮内序即队列序，结果确定）。
    const roots = [startId, ...nodes.map(n => n.id)].filter(
      (id): id is string => id !== null && gridNodes.has(id) && (fwdIndeg.get(id) ?? 0) === 0 && !layerOf.has(id),
    )
    for (const r of roots) {
      layerOf.set(r, 0)
      queue.push(r)
    }
  }
  enqueueRoots()
  let qi = 0
  while (qi < queue.length) {
    const u = queue[qi++]
    const lu = layerOf.get(u) ?? 0
    for (const i of adjacency.get(u) ?? []) {
      if (isBack[i]) continue // 邻接表建于 DFS 之前：DFS 期间新标的回边仍在表内，此处跳过
      const v = edges[i].to
      const cand = lu + 1
      if (cand > (layerOf.get(v) ?? -1)) layerOf.set(v, cand)
      const d = (fwdIndeg.get(v) ?? 0) - 1
      fwdIndeg.set(v, d)
      if (d === 0) queue.push(v)
    }
  }
  // 兜底：grid 节点理论上必被 Kahn 覆盖（DAG）；防御性补 0 层防漏坐标。
  for (const n of nodes) {
    if (gridNodes.has(n.id) && !layerOf.has(n.id)) layerOf.set(n.id, 0)
  }

  // ── ③ 层内排序：初序 = 定义序（start 置顶）→ 重心微调（减少连线交叉）。 ──
  // -1 初值：grid 为空（无 start 且全部孤立）时不产生空层，layerCount=0。
  const maxLayer = Array.from(layerOf.values()).reduce((m, l) => Math.max(m, l), -1)
  const layers: string[][] = Array.from({ length: maxLayer + 1 }, () => [])
  for (const n of nodes) {
    const id = n.id
    if (!gridNodes.has(id)) continue
    const l = layerOf.get(id) ?? 0
    layers[l].push(id)
  }
  if (startId !== null) {
    const s = layers[0]?.indexOf(startId) ?? -1
    if (s > 0) {
      layers[0].splice(s, 1)
      layers[0].unshift(startId)
    }
  }
  /** 前向边邻接（层内排序用，只看行号，不看层号——前驱必在更浅层、后继必在更深层）。 */
  const fwdIn = new Map<string, string[]>()
  const fwdOut = new Map<string, string[]>()
  const pushMap = (m: Map<string, string[]>, k: string, v: string): void => {
    const list = m.get(k)
    if (list) list.push(v)
    else m.set(k, [v])
  }
  edges.forEach((e, i) => {
    if (isBack[i]) return
    pushMap(fwdOut, e.from, e.to)
    pushMap(fwdIn, e.to, e.from)
  })
  /** node → 当前行号（层内序；重心迭代中持续更新）。 */
  const row = new Map<string, number>()
  layers.forEach(list => list.forEach((id, r) => row.set(id, r)))

  /** 单层重心重排（dir='in'=贴前驱行号 / 'out'=贴后继行号）。 */
  const resort = (k: number, dir: 'in' | 'out'): void => {
    const list = layers[k]
    if (!list || list.length < 2) return
    const bary = (id: string): number => {
      const nbrs = dir === 'in' ? fwdIn.get(id) : fwdOut.get(id)
      if (!nbrs || nbrs.length === 0) return row.get(id) ?? 0 // 无邻接：锚在当前行
      let sum = 0
      for (const nb of nbrs) sum += row.get(nb) ?? 0
      return sum / nbrs.length
    }
    const keyed = list.map(id => ({ id, b: bary(id) }))
    keyed.sort((a, b) => a.b - b.b) // Array.sort 稳定：平局保持现序（定义序保底）
    const next = keyed.map(k2 => k2.id)
    layers[k] = next
    next.forEach((id, r) => row.set(id, r))
  }
  for (let p = 0; p < BARYCENTER_PASSES; p++) {
    for (let k = 1; k < layers.length; k++) resort(k, 'in') // 前向：贴前驱
    for (let k = layers.length - 2; k >= 0; k--) resort(k, 'out') // 后向：贴后继
  }

  // ── ④ 网格坐标：x = 层深×列距、y = 层内序×行距；孤立区在主流右侧独立列。 ──
  layers.forEach((list, k) => {
    list.forEach((id, r) => {
      positions.set(id, { x: LAYOUT_MARGIN + k * LAYOUT_COL_GAP, y: LAYOUT_MARGIN + r * LAYOUT_ROW_GAP })
    })
  })
  // 孤立列起点 = 主流列数×列距（与最深列隔一整列，水平即不重叠），定义序堆叠。
  const orphanX = LAYOUT_MARGIN + layers.length * LAYOUT_COL_GAP
  orphans.forEach((n, i) => {
    positions.set(n.id, { x: orphanX, y: LAYOUT_MARGIN + i * LAYOUT_ROW_GAP })
  })

  result.layerCount = layers.length
  result.backEdgeCount = isBack.reduce((c, b) => c + (b ? 1 : 0), 0) + selfLoops
  result.orphanCount = orphans.length
  return result
}
