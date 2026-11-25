/**
 * 工作流定义「自动布局」算法核（编辑器工具栏「自动布局」按钮）。
 *
 * Sugiyama 分层布局的简化实现（主流沿 y 轴自上而下展开），纯函数、零
 * React/画布依赖（几何常量除外），便于脱离 DOM 自测。流程五步：
 *   ① 回边识别——DFS（灰→灰 = 指向栈上祖先）把有环图的「回指边」标为逆向边，
 *      分层只看前向边、逆向边留作绘制时的回线（从下层弯回上层）；
 *      指向 start 的边一律先标回边（start 是执行入口，入边语义上是「回到起点
 *      重跑」），这同时保证 start 无前向入边、分层必落第 0 层（需求：start 固定
 *      第 0 层，即最顶行）。
 *   ② 最长路分层——去回边后必为 DAG（任一有向环至少含一条 DFS 回边），
 *      layer(v) = max(layer(前向前驱)) + 1，前向入度 0 的根层为 0；Kahn 按
 *      定义序取队，结果确定。
 *   ③ 跨层边拆段（虚拟节点）——跨多层的前向边在沿途每层插一个虚拟节点，
 *      把一条长边拆成逐层短边，虚拟节点与真实节点一同参与层内排序、并独占一列。
 *      这是「两条线重叠」的结构性解法：不断开的话，长边从源底锚直落目标顶锚，
 *      落点与中间层同列节点完全重合——既穿过节点矩形，又与同列的短边共线重叠
 *      （长边整段覆盖短边，画布上根本分不清是几条线）。拆段后每条边在每一层都
 *      占独立列，任意两条不同边的「层-列」端点组合必不相同，几何上不可能共线。
 *   ④ 层内排序——初序 = 定义列表序（start 置最左，虚拟节点随其后）；再做若干轮
 *      重心（barycenter）微调：按前驱/后继在邻层的行号均值重排，减少连线交叉；
 *      虚拟节点同样参与均值，长边因此被「拉直」成直上直下的走廊。无邻接的节点
 *      锚在当前行不被推走；稳定排序 + 平局保持现序，结果确定。
 *   ⑤ 层间横向对齐——层内行号只是相对序，直接拿行号当列号会让「只有一个节点的层」
 *      永远落在第 0 列（汇合节点被甩到最左，入边全被拉成横穿全图的近水平长线、叠成
 *      一片）。故按前驱列位的中位偏移整层平移（整数档，网格不乱），末尾归一到最左列
 *      为留白值：汇合/分叉节点落在上下游扇形的中间，长横线跨度大幅缩短。
 *   ⑥ 网格坐标——y = 层深×层距、x = 列号×行距（层深沿垂直方向、层内沿水平方向）；
 *      间距取节点尺寸的合理倍数（NODE_W / NODE_H，见常量注释），60 节点规模不重叠。
 *      孤立节点（无边，自环除外）集中排在主流最深层下方的独立行，不与主流重叠；
 *      虚拟节点只占列、不产出坐标（画布不渲染它们）。
 *
 * 调用方（DefinitionEditorPanel）经 updateDef 一次性写回全部节点 x/y——与
 * 手工拖拽同一条定义更新链（dirty / 保存语义一致），其余字段一律不动。
 */
import type { WorkflowDefinition, WorkflowNodeDef } from '../workflowGateway'
import { NODE_H, NODE_W } from './geometry'

// ─── 间距常量（节点尺寸的合理倍数；60 节点规模不重叠） ─────────────────────

/** 层间距（层深方向，y 轴）：NODE_H 的 ~2.8 倍——给上下向贝塞尔连线留出弧度空间。 */
export const LAYOUT_LAYER_GAP = 180
/** 行间距（层内方向，x 轴）：NODE_W 的 ~1.3 倍——层内节点互不重叠且留出选中余量。 */
export const LAYOUT_ROW_GAP = 240
/** 网格左/上留白（第 0 列/行的起点）。 */
const LAYOUT_MARGIN = 40
/** 重心排序轮数（前向贴前驱 + 后向贴后继各算一轮；两轮后收益递减）。 */
const BARYCENTER_PASSES = 2
/** 虚拟节点 id 前缀（私有区控制符 + 语义前缀）：真实节点 id 来自用户输入，
 *  肉眼可见的 id 不会含控制符，故不会与虚拟节点撞名。 */
const VIRTUAL_PREFIX = '\u0000layout-dummy-'

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
  /** 孤立节点（无边，自环除外）个数——集中排在下方独立行。 */
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

  // ── ③ 跨层边拆段：跨多层的前向边在中间每层插一个虚拟节点（独占一列）。
  //     虚拟节点 id 带不可见前缀（真实节点 id 来自用户输入，正常不会撞上）。
  const segs: Array<{ from: string; to: string }> = []
  const virtualLayer = new Map<string, number>()
  let dummySeq = 0
  edges.forEach((e, i) => {
    if (isBack[i]) return // 回边不参与分层/网格：绘制走画布的回线绕行
    const lf = layerOf.get(e.from) ?? 0
    const lt = layerOf.get(e.to) ?? 0
    let prev = e.from
    for (let l = lf + 1; l < lt; l++) {
      const vid = `${VIRTUAL_PREFIX}${dummySeq++}`
      virtualLayer.set(vid, l)
      segs.push({ from: prev, to: vid })
      prev = vid
    }
    segs.push({ from: prev, to: e.to })
  })

  // ── ④ 层内排序：初序 = 定义序（start 置最左，虚拟节点排本层队尾）→ 重心微调。 ──
  // -1 初值：grid 为空（无 start 且全部孤立）时不产生空层，layerCount=0。
  const maxLayer = Array.from(layerOf.values()).reduce((m, l) => Math.max(m, l), -1)
  const layers: string[][] = Array.from({ length: maxLayer + 1 }, () => [])
  for (const n of nodes) {
    const id = n.id
    if (!gridNodes.has(id)) continue
    const l = layerOf.get(id) ?? 0
    layers[l].push(id)
  }
  // 虚拟节点插队尾（Map 迭代序 = 边处理序，结果确定）；重心轮次会把它们归位。
  for (const [vid, l] of virtualLayer) layers[l].push(vid)
  if (startId !== null) {
    const s = layers[0]?.indexOf(startId) ?? -1
    if (s > 0) {
      layers[0].splice(s, 1)
      layers[0].unshift(startId)
    }
  }
  /** 前向段邻接（层内排序用，只看行号，不看层号——前驱必在更浅层、后继必在更深层）。 */
  const fwdIn = new Map<string, string[]>()
  const fwdOut = new Map<string, string[]>()
  const pushMap = (m: Map<string, string[]>, k: string, v: string): void => {
    const list = m.get(k)
    if (list) list.push(v)
    else m.set(k, [v])
  }
  for (const s of segs) {
    pushMap(fwdOut, s.from, s.to)
    pushMap(fwdIn, s.to, s.from)
  }
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

  // ── ⑤ 层间横向对齐：层内行号只表达相对序，直接 row×行距 会让「只有一个节点的层」
  //     永远落在第 0 列——一个汇合节点被甩到最左，所有入边被拉成横穿全图的近水平长线，
  //     这些长线互相叠在一起（宽图上尤其明显）。改为：每层按前驱列位的中位偏移整体
  //     平移（整数档，保持网格整齐），再全局归一到最左列 = MARGIN。汇合/分叉节点因此
  //     落在上下游扇形的中间，长横线的跨度大幅缩短。
  const colOf = new Map<string, number>()
  layers.forEach((list, k) => {
    let offset = 0
    if (k > 0) {
      const deltas: number[] = []
      list.forEach((id, r) => {
        const nbrs = fwdIn.get(id)
        if (!nbrs || nbrs.length === 0) return
        let sum = 0
        let count = 0
        for (const nb of nbrs) {
          const c = colOf.get(nb)
          if (c === undefined) continue
          sum += c
          count++
        }
        if (count > 0) deltas.push(sum / count - r)
      })
      if (deltas.length > 0) {
        deltas.sort((a, b) => a - b) // 中位：个别离群前驱不至于把整层拽走
        offset = Math.round(deltas[Math.floor(deltas.length / 2)])
      }
    }
    list.forEach((id, r) => colOf.set(id, offset + r))
  })
  const minCol = Math.min(0, ...colOf.values())

  // ── ⑥ 网格坐标：y = 层深×层距、x = 列号×行距；孤立区在主流下方独立行。
  //     虚拟节点只占列（保证长边有独立走廊），不写坐标。
  layers.forEach((list, k) => {
    list.forEach(id => {
      if (virtualLayer.has(id)) return
      positions.set(id, {
        x: LAYOUT_MARGIN + ((colOf.get(id) ?? 0) - minCol) * LAYOUT_ROW_GAP,
        y: LAYOUT_MARGIN + k * LAYOUT_LAYER_GAP,
      })
    })
  })
  // 孤立行起点 = 主流层数×层距（与最深层隔一整行，垂直即不重叠），定义序横排。
  const orphanY = LAYOUT_MARGIN + layers.length * LAYOUT_LAYER_GAP
  orphans.forEach((n, i) => {
    positions.set(n.id, { x: LAYOUT_MARGIN + i * LAYOUT_ROW_GAP, y: orphanY })
  })

  result.layerCount = layers.length
  result.backEdgeCount = isBack.reduce((c, b) => c + (b ? 1 : 0), 0) + selfLoops
  result.orphanCount = orphans.length
  return result
}
