/**
 * 任务内容搜索(worker 侧 task.search 的前端封装)。
 *
 * 数据源:任务按工作区归类落盘 workspaces/<workspaceId>/tasks/<taskId>/,
 * worker 用内置 rg 搜索轮次索引 rounds.jsonl 并经「JSON 转义消除」后处理
 * (解析 JSON → user/finalReply 干净文本二次匹配)返回结构化结果。
 *
 * 应答与 workspaceGateway.search 同构 {matchCount, truncated, files},
 * files 项为任务级 {taskId, title, workspace, workspaceId, status, matches};
 * 大结果复用 rpc.data 分批 + 末帧 ok 汇总(与 fs.search 一致)。
 */
import { hubSession } from '@/hub/session'

/** 单条任务内容命中(与 WorkspaceContentSearchHit 同构 + 轮次/字段定位)。 */
export interface TaskContentSearchHit {
  /** 轮次序号(rounds.jsonl 行的 index 字段)。 */
  roundIndex: number
  /** 命中字段:user = 用户输入, finalReply = AI 最终回复。 */
  field: 'user' | 'finalReply'
  /** 命中行文本(已去除 JSON 转义的干净正文)。 */
  line: string
  /** 命中片段在行内的起始列(0-based)。 */
  matchIndex?: number
  /** 命中片段文本。 */
  matchText?: string
}

/** 单个任务的搜索结果(命中按任务聚合)。 */
export interface TaskContentSearchTaskResult {
  /** 任务 ID(点击命中用于打开任务聊天页)。 */
  taskId: string
  /** 任务标题。 */
  title: string
  /** 任务挂靠工作区根(展示/兼容键)。 */
  workspace: string
  /** 任务挂靠工作区稳定 id。 */
  workspaceId: string
  /** worker 原始状态串(done/running/...)。 */
  status: string
  matches: TaskContentSearchHit[]
}

/** 搜索结果(与 WorkspaceContentSearchResult 同构,files 语义 = 任务)。 */
export interface TaskContentSearchResult {
  /** 命中总数(触顶时为 maxResults)。 */
  matchCount: number
  /** 是否提前停止:命中数到达上限,或 worker 侧超时/异常。 */
  truncated: boolean
  /** 按任务聚合的命中结果。 */
  files: TaskContentSearchTaskResult[]
}

/** task.search 入参(worker 侧 rg + 后处理的 pattern 语义与 fs.search 一致)。 */
export interface TaskContentSearchParams {
  /** 任务所属工作区稳定 id(worker 迁移后恒有;用于定位任务目录)。 */
  workspaceId: string
  /** 搜索词:isRegex=true 按正则解释,否则字面量(worker 侧 --fixed-strings)。 */
  pattern: string
  isRegex: boolean
  caseSensitive: boolean
  wholeWord: boolean
  /** 命中上限,默认 500。 */
  maxResults?: number
}

/** task.search 应答文件项(worker 输出,与 TaskContentSearchTaskResult 同构)。 */
interface TaskSearchFileItemWire {
  taskId: string
  title: string
  workspace: string
  workspaceId: string
  status: string
  matches?: Array<{
    roundIndex: number
    field: string
    line: string
    matchIndex?: number
    matchText?: string
  }>
}

/** worker 任务项 → 前端搜索结果形状(字段同名映射,钳住契约漂移;field 非法时忽略该条)。 */
function toTaskResult(item: TaskSearchFileItemWire): TaskContentSearchTaskResult {
  const matches: TaskContentSearchHit[] = (item.matches ?? [])
    .filter((hit) => hit.field === 'user' || hit.field === 'finalReply')
    .map((hit) => ({
      roundIndex: hit.roundIndex,
      field: hit.field as TaskContentSearchHit['field'],
      line: hit.line,
      matchIndex: hit.matchIndex,
      matchText: hit.matchText,
    }))
  return {
    taskId: item.taskId,
    title: item.title,
    workspace: item.workspace,
    workspaceId: item.workspaceId,
    status: item.status,
    matches,
  }
}

/**
 * 任务内容搜索:向目标 worker 发 task.search RPC。
 * workspaceId 为空时 worker 回退全量任务(前端应避免);worker 不支持(UNKNOWN_METHOD)
 * 时抛 RpcError,由调用方按能力缺失提示。
 */
export async function searchTasksContent(
  workerId: string,
  params: TaskContentSearchParams,
): Promise<TaskContentSearchResult> {
  if (!workerId) {
    throw new Error('请先选择 worker')
  }
  const batched: TaskContentSearchTaskResult[] = []
  const result = await hubSession.rpcTo(workerId, 'task.search', {
    workspaceId: params.workspaceId,
    pattern: params.pattern,
    isRegex: params.isRegex,
    caseSensitive: params.caseSensitive,
    wholeWord: params.wholeWord,
    maxResults: params.maxResults ?? 500,
  }, {
    timeoutMs: 120_000,
    onData: (batch) => {
      for (const item of batch as TaskSearchFileItemWire[]) {
        batched.push(toTaskResult(item))
      }
    },
  }) as { matchCount?: number; truncated?: boolean; files?: TaskSearchFileItemWire[] }

  const files = Array.isArray(result.files)
    ? result.files.map(toTaskResult)
    : batched
  return {
    matchCount: typeof result.matchCount === 'number' ? result.matchCount : 0,
    truncated: result.truncated === true,
    files,
  }
}