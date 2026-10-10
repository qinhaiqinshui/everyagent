/**
 * src/query/search.ts
 *
 * 统一搜索 RPC 封装（worker 侧 `search` 方法，架构 §7 契约表）。
 *
 * 与旧三条搜索链（`fs.search` / `fs.find` / `task.search`）相比：
 * - **核心极薄**：入参只有 `workspace` / `pattern` / `kinds?` / `filters?` 四个，
 *   核心不解释 `pattern` 与 `filters` 内容（filters 为不透明袋，键约定
 *   `${kind}.${field}` 命名空间），搜索类型清单完全来自后端 provider 注册表；
 * - **应答统一**：`{matchCount, truncated, items}`，items 为**平铺命中项**，每项带
 *   `kind`（+ 可选 `providerId` / `score`），其余字段随 kind 不同；
 * - **大结果分批**：复用 `rpc.data` 分批（§5.4），onData 按到达顺序累计合并，
 *   末帧 ok 只带 `{matchCount, truncated}` 汇总；
 * - **worker 定向**：`workspace`（工作区根机器绝对路径）经注册表反查所属 worker
 *   后定向发送（多 worker 并行，同一入参与 `fs.*` 的 workspace 口径一致）。
 */
import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { RpcError } from '@/sdk/frames'

/** 统一搜索入参（核心仅认这四项）。 */
export interface UnifiedSearchParams {
  /** 工作区根（worker 机器绝对路径，必填 → 决定 workspaceId 与 owner）。 */
  workspace: string
  /** 搜索词（核心不解释，原样交给各 provider）。 */
  pattern: string
  /** 可选数据过滤：只搜这些 kind；缺省/不传 = 全部已注册类型（含任务）。 */
  kinds?: string[]
  /** 不透明过滤袋（键约定 `${kind}.${field}`，核心不解释值）。 */
  filters?: Record<string, unknown>
}

/**
 * 统一搜索结果项（平铺命中项）。
 *
 * `kind` 判别类别（开放集合）；其余字段随 kind 不同。`[key: string]: unknown`
 * 容纳插件 provider 自定义字段，消费方按 kind 按需强转读取。
 */
export interface UnifiedSearchItem {
  /** 结果类别（`file-content` / `file-name` / `task` / 插件自定）。 */
  kind: string
  /** 结果来源 provider id（缺省/`builtin.*` 内置静默，插件用其自身 id）。 */
  providerId?: string
  /** 可选相关性分数（仅排序提示）。 */
  score?: number
  /** 文件类：文件路径（工作区相对）。 */
  path?: string
  /** 内容类：1-based 行号。 */
  lineNumber?: number
  /** 内容类：命中行正文。 */
  line?: string
  /** 命中片段在行内的起始列（0-based）。 */
  matchIndex?: number
  /** 命中片段文本。 */
  matchText?: string
  /** 任务类：任务 ID。 */
  taskId?: string
  /** 任务类：任务标题。 */
  title?: string
  /** 任务类：状态串。 */
  status?: string
  /** 组头显示名（可选，覆盖默认 key）。 */
  groupLabel?: string
  /** 任务类：轮次序号。 */
  roundIndex?: number
  /** 任务类：命中字段。 */
  field?: string
  /** 其余字段随 kind 不同。 */
  [key: string]: unknown
}

/** 统一搜索应答（归一分批后）。 */
export interface UnifiedSearchResult {
  /** 命中总数（触顶时为已得值）。 */
  matchCount: number
  /** 是否有任一 provider 触顶（各 provider 自身触顶的「或」，核心不据此裁剪）。 */
  truncated: boolean
  /** 平铺命中项。 */
  items: UnifiedSearchItem[]
}

/** 由工作区根反查所属 worker（统一 search 定向发送的依据）；无归属返回 undefined。 */
export function searchWorkerIdOf(workspace: string): string | undefined {
  if (!workspace) return undefined
  return workspaceRegistry.workerIdOfRoot(workspace) ?? undefined
}

/** 把一批 rpc.data 帧规整为平铺结果项（容忍「批内元素为数组」与「直接为项」两种形态）。 */
function collectBatch(batch: unknown, sink: UnifiedSearchItem[]): void {
  if (!Array.isArray(batch)) return
  for (const entry of batch) {
    if (Array.isArray(entry)) {
      for (const item of entry) sink.push(normalizeItem(item))
    } else if (entry && typeof entry === 'object') {
      sink.push(normalizeItem(entry))
    }
  }
}

/** 单项 → 统一结果项（钳住 kind 契约漂移：缺 kind 时按未知类别透传）。 */
function normalizeItem(raw: unknown): UnifiedSearchItem {
  const record = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const kind = typeof record.kind === 'string' && record.kind ? record.kind : 'unknown'
  return { ...record, kind } as UnifiedSearchItem
}

/** RPC 错误 → 可读错误（UNKNOWN_METHOD 提示 worker 版本；其余保留 worker message）。 */
function mapSearchError(error: unknown, workerId: string): Error {
  if (error instanceof RpcError) {
    if (error.code === 'UNKNOWN_METHOD' || /unknown method/i.test(error.message)) {
      return new Error(`目标 worker（${workerId}）不支持统一搜索（search），请升级 worker。`)
    }
    return new Error(error.message)
  }
  if (error instanceof Error) return error
  return new Error(String(error))
}

/**
 * 统一搜索：向工作区所属 worker 发 `search` RPC。
 * 无归属工作区（未注册/worker 离线）抛可读错误；rpc.data 分批按到达顺序合并。
 */
export async function search(params: UnifiedSearchParams): Promise<UnifiedSearchResult> {
  if (!params.workspace) {
    throw new Error('未选择工作区，无法搜索。')
  }
  const workerId = workspaceRegistry.workerIdOfRoot(params.workspace)
  if (!workerId) {
    throw new Error('无法确定该工作区所属 worker（工作区未注册或 worker 离线）。')
  }
  const rpcParams: Record<string, unknown> = {
    workspace: params.workspace,
    pattern: params.pattern,
  }
  if (params.kinds && params.kinds.length > 0) {
    rpcParams.kinds = params.kinds
  }
  if (params.filters && Object.keys(params.filters).length > 0) {
    rpcParams.filters = params.filters
  }
  const batched: UnifiedSearchItem[] = []
  let result: { matchCount?: number; truncated?: boolean; items?: unknown[] } | undefined
  try {
    result = await hubSession.rpcTo(workerId, 'search', rpcParams, {
      timeoutMs: 120_000,
      onData: (batch) => collectBatch(batch, batched),
    }) as { matchCount?: number; truncated?: boolean; items?: unknown[] }
  } catch (error) {
    throw mapSearchError(error, workerId)
  }
  const items = Array.isArray(result?.items)
    ? result.items.map(normalizeItem)
    : batched
  return {
    matchCount: typeof result?.matchCount === 'number' ? result.matchCount : items.length,
    truncated: result?.truncated === true,
    items,
  }
}