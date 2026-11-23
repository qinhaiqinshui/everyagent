/**
 * node.delta 瞬态增量累积层（AI 节点流式渲染的数据底座，架构 §7.13 瞬态契约）。
 *
 * 职责：按 instanceId → nodeId 在内存累积 AI 节点运行期间的 thinking/text 增量
 * （node.delta 推送帧到达即追加，由 workflowGateway 的订阅旁路统一喂入）；节点收口
 * （node.output / node.error 落盘事件）或实例终态时清空对应累积——真记录落盘后由
 * runs 数据接管展示，防陈旧残留。
 *
 * 瞬态纪律（与 events 回放数组严格分离）：
 * - node.delta 不落盘（worker 侧瞬态占号，磁盘回放有洞合法）→ 补拉天然不含，
 *   本累积**只由实时推送帧喂入**，任何拉取/回放路径都不进这里；
 * - 生命周期 = 节点运行期：收口即清、实例终态全清、runLog 重拉后非运行节点兜底清；
 * - 纯内存、会话级：页面刷新即空（无需持久化——最终全文在收口后的 runs 记录里）。
 *
 * 本模块为纯数据结构（无插件上下文依赖），订阅关闭不清（重挂载续流），由
 * workflowGateway 的终态钩子与消费方（InstancePanel 的 runLog 重拉信号）负责清理。
 */

/** 单节点累积视图（thinking/text 分开的两个字符串，渲染层可直接流式显示）。 */
export interface NodeDeltaAccumulation {
  /** 思考增量累积（kind='thinking' 串接）。 */
  thinking: string
  /** 正文增量累积（kind='text' 串接）。 */
  text: string
  /** 最近一次追加时刻（epoch ms；收口清理的新旧守卫用）。 */
  updatedAt: number
}

/** 追加输入（gateway 解析出的 node.delta 帧投影；结构子集，避免反向依赖 wire 层）。 */
export interface NodeDeltaAppendInput {
  nodeId: string
  kind: 'thinking' | 'text'
  /** 本次增量文本（追加语义；空串为无害 no-op）。 */
  content: string
  /** 帧时间戳（epoch ms；非法时以本地时钟兜底）。 */
  ts: number
}

/**
 * 单 kind 累积的防御上限（字符数）：AI 单次运行的输出/思考总量受模型侧约束，
 * 正常远低于此；超限即停追加（保尾不保头无意义，保留已累积部分）但仍推进
 * updatedAt（保持活跃信号）。仅防病态增长，不是业务截断规则。
 */
const MAX_CHARS_PER_KIND = 1_000_000

/** instanceId → (nodeId → 累积)。 */
const buffer = new Map<string, Map<string, NodeDeltaAccumulation>>()

/** node.delta 到达即追加（空 nodeId/kind/content 无害跳过）。 */
export function appendNodeDelta(instanceId: string, delta: NodeDeltaAppendInput): void {
  if (!instanceId || !delta || delta.nodeId === '') return
  if (delta.kind !== 'thinking' && delta.kind !== 'text') return
  if (typeof delta.content !== 'string' || delta.content === '') return
  let nodes = buffer.get(instanceId)
  if (!nodes) {
    nodes = new Map()
    buffer.set(instanceId, nodes)
  }
  const acc = nodes.get(delta.nodeId) ?? { thinking: '', text: '', updatedAt: 0 }
  if (acc[delta.kind].length < MAX_CHARS_PER_KIND) {
    acc[delta.kind] = acc[delta.kind] + delta.content
  }
  const ts = typeof delta.ts === 'number' && delta.ts > 0 ? delta.ts : Date.now()
  acc.updatedAt = Math.max(acc.updatedAt, ts)
  nodes.set(delta.nodeId, acc)
}

/**
 * 清单节点累积（收口信号：node.output / node.error 落盘事件到达）。
 *
 * ifNotNewerThan 新旧守卫：仅当累积不晚于该时刻（updatedAt <= ifNotNewerThan）才清
 * ——历史收口事件经初始回放/补拉晚到时，不误清节点**新一轮**运行已开始的增量
 * （同 worker 雪花号与 ts 单调，新 run 的增量必然晚于旧 run 的收口时刻）。
 */
export function clearNodeDelta(instanceId: string, nodeId: string, ifNotNewerThan?: number): void {
  const nodes = buffer.get(instanceId)
  if (!nodes) return
  const acc = nodes.get(nodeId)
  if (!acc) return
  if (typeof ifNotNewerThan === 'number' && acc.updatedAt > ifNotNewerThan) return
  nodes.delete(nodeId)
  if (nodes.size === 0) buffer.delete(instanceId)
}

/** 清整个实例的全部累积（实例终态 / 实例删除）。 */
export function clearInstanceDeltas(instanceId: string): void {
  buffer.delete(instanceId)
}

/**
 * 只保留给定节点集的累积，其余清空（runLog 重拉信号：runs 真记录已落盘的节点，
 * 其瞬态累积视为被取代）。keepNodeIds 传运行中/等待决策节点——重跑场景的进行中
 * 增量不受波及。
 */
export function pruneNodeDeltas(instanceId: string, keepNodeIds: ReadonlySet<string>): void {
  const nodes = buffer.get(instanceId)
  if (!nodes) return
  for (const id of [...nodes.keys()]) {
    if (!keepNodeIds.has(id)) nodes.delete(id)
  }
  if (nodes.size === 0) buffer.delete(instanceId)
}

/** 取快照（浅拷贝；React 状态等「读时复制」消费方用，避免外部直接改内部 Map）。 */
export function snapshotNodeDeltas(instanceId: string): Record<string, NodeDeltaAccumulation> {
  const out: Record<string, NodeDeltaAccumulation> = {}
  const nodes = buffer.get(instanceId)
  if (nodes) {
    for (const [id, acc] of nodes) {
      out[id] = { thinking: acc.thinking, text: acc.text, updatedAt: acc.updatedAt }
    }
  }
  return out
}
