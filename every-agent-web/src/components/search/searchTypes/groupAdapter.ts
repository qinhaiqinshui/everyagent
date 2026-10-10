/**
 * 默认结果适配器：统一平铺命中项 `UnifiedSearchItem[]` → `SearchResultGroup[]`。
 *
 * 结果按 `(kind, groupKey)` 分组（groupKey 随 kind 不同：文件类 = 路径，任务类 = 任务 ID）：
 * - `file-content`：一个文件一组，命中行 = 行号前缀 + 高亮正文，点击打开定位到行；
 * - `file-name`：一个文件一组（叶子组，无命中行），组头即文件名，点击打开文件；
 * - `task`：一个任务一组，命中行 = 「#轮次 字段」前缀 + 高亮正文，点击打开任务聊天页；
 * - **未知 kind 兜底**：组头名 = `groupLabel ?? groupKey`，来源标记用 `providerId`。
 *
 * 分组结果交给既有 `SearchResultTreeView`（单点渲染，不重写树）。
 */
import type { UnifiedSearchItem } from '@/query/search'
import type { SearchResultGroup, SearchResultGroupHeader, SearchResultHit } from '../resultTree/model'

/** 适配器打开动作回调（打开文件定位行 / 打开任务）。 */
export interface GroupAdapterCallbacks {
  /** 打开文件（工作区相对/业务路径 + 可选行号）。 */
  openFile: (path: string, lineNumber?: number) => void
  /** 打开任务聊天页。 */
  openTask: (taskId: string, title?: string) => void
}

/** 文件路径 → 文件名 + 相对目录（组头与扁平文件行共用的拆分）。 */
function splitPath(path: string): { fileName: string; dirPath: string } {
  const slashIndex = path.lastIndexOf('/')
  return {
    fileName: slashIndex >= 0 ? path.slice(slashIndex + 1) : path,
    dirPath: slashIndex > 0 ? path.slice(0, slashIndex) : '',
  }
}

/** 命中字段展示名（任务类）。 */
function fieldLabel(field: string | undefined): string {
  if (field === 'user') return '用户输入'
  if (field === 'finalReply') return 'AI 回复'
  return field ?? ''
}

/** worker 状态串 → 短标签（仅展示；未知原样显示）。 */
function statusLabel(status: string): string {
  switch (status) {
    case 'done':
      return '完成'
    case 'running':
      return '运行中'
    case 'waiting-user':
      return '等待用户'
    case 'failed':
      return '失败'
    case 'cancelled':
      return '已取消'
    default:
      return status
  }
}

function toStringValue(value: unknown): string {
  return typeof value === 'string' ? value : value == null ? '' : String(value)
}

function toNumberValue(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/** 命中行的通用字段装配（file-content / 未知 kind 各自的正文差异在调用处补齐）。 */
function buildHit(
  item: UnifiedSearchItem,
  options: { prefix?: SearchResultHit['prefix']; title?: string; onOpen: () => void },
): SearchResultHit {
  return {
    label: toStringValue(item.line),
    line: typeof item.lineNumber === 'number' ? item.lineNumber : undefined,
    ...(options.prefix ? { prefix: options.prefix } : {}),
    matchIndex: toNumberValue(item.matchIndex, -1),
    matchText: toStringValue(item.matchText),
    ...(options.title ? { title: options.title } : {}),
    onOpen: options.onOpen,
  }
}

/**
 * 平铺命中项 → 通用结果分组。
 *
 * 分组键 = `${kind}\u0000${groupKey}`（避免不同 kind 的同名 groupKey 互相污染）；
 * 组的 `kind` 透传给消费方（用于按类型派发 `ResultView`）。
 */
export function buildSearchResultGroups(
  items: UnifiedSearchItem[],
  callbacks: GroupAdapterCallbacks,
): SearchResultGroup[] {
  const groups = new Map<string, SearchResultGroup>()
  const order: string[] = []

  const ensureGroup = (
    kind: string,
    groupKey: string,
    header: SearchResultGroupHeader,
  ): SearchResultGroup => {
    const key = `${kind}\u0000${groupKey}`
    let group = groups.get(key)
    if (!group) {
      group = { key, kind, header, hits: [] }
      groups.set(key, group)
      order.push(key)
    }
    return group
  }

  for (const item of items) {
    switch (item.kind) {
      case 'file-content': {
        const path = toStringValue(item.path)
        const { fileName, dirPath } = splitPath(path)
        const group = ensureGroup('file-content', path, {
          title: path,
          name: fileName,
          detail: dirPath ? { text: dirPath, grow: true } : undefined,
          icon: { kind: 'file', fileName },
          ...(item.providerId ? { providerId: item.providerId } : {}),
        })
        group.hits.push(buildHit(item, {
          prefix: { text: String(item.lineNumber ?? ''), minWidth: 30 },
          title: `${path}:${item.lineNumber}`,
          onOpen: () => callbacks.openFile(path, item.lineNumber),
        }))
        break
      }
      case 'file-name': {
        const path = toStringValue(item.path)
        const { fileName, dirPath } = splitPath(path)
        ensureGroup('file-name', path, {
          title: path,
          name: fileName,
          detail: dirPath ? { text: dirPath, grow: true } : undefined,
          icon: { kind: 'file', fileName },
          ...(item.providerId ? { providerId: item.providerId } : {}),
          onOpen: () => callbacks.openFile(path),
        })
        break
      }
      case 'task': {
        const taskId = toStringValue(item.taskId)
        const title = toStringValue(item.title) || taskId
        const groupLabel = item.groupLabel ? toStringValue(item.groupLabel) : undefined
        const status = toStringValue(item.status)
        const group = ensureGroup('task', taskId, {
          title,
          name: groupLabel ?? title,
          detail: status ? { text: statusLabel(status) } : undefined,
          ...(item.providerId ? { providerId: item.providerId } : {}),
        })
        group.hits.push(buildHit(item, {
          prefix: { text: `#${item.roundIndex ?? ''} ${fieldLabel(item.field)}`, minWidth: 84 },
          title: `${title} · 第 ${item.roundIndex ?? ''} 轮 ${fieldLabel(item.field)}`,
          onOpen: () => callbacks.openTask(taskId, title),
        }))
        break
      }
      default: {
        // 未知 kind 兜底：组头名 = groupLabel ?? groupKey，来源标记用 providerId。
        const groupKey = toStringValue(
          item.groupLabel ?? item.path ?? item.taskId ?? item.id ?? item.kind,
        ) || item.kind
        const path = toStringValue(item.path)
        const fileName = path ? splitPath(path).fileName : groupKey
        const group = ensureGroup(item.kind, groupKey, {
          title: item.groupLabel ? toStringValue(item.groupLabel) : groupKey,
          name: item.groupLabel ? toStringValue(item.groupLabel) : groupKey,
          icon: path ? { kind: 'file', fileName } : undefined,
          ...(item.providerId ? { providerId: item.providerId } : {}),
          ...(path ? { onOpen: () => callbacks.openFile(path, item.lineNumber) } : {}),
        })
        // 有正文的未知项补一行命中行；纯位置项（如 file-name 式）退化为叶子组（组头点击打开）。
        const line = toStringValue(item.line ?? item.label ?? item.text ?? item.name)
        if (line) {
          group.hits.push(buildHit(item, {
            ...(path ? { title: path } : {}),
            onOpen: () => {
              if (path) callbacks.openFile(path, item.lineNumber)
            },
          }))
        }
        break
      }
    }
  }

  return order.map((key) => groups.get(key)!)
}