/**
 * 终端网关:经 hub 管道对 worker 的 term.* RPC(term.open / input / resize / close)。
 *
 * 与 workspaceGateway 同源:按 workspaceRoot 反查来源 worker 后定向 RPC。
 * 路径归一化与 workspaceGateway 一致:normalizeWorkspaceRelativePath + toWorkerPath
 * (业务路径 /every-agent-web → worker 路径 every-agent-web;根 / → .)。
 * 终端实时输出不走 RPC,而走 stream 频道(u.<K>.term.<termId>.stream),
 * worker 定向推送 term.output / term.exited —— 频道订阅与帧分派在 TerminalPage
 * 组件内完成,本网关只负责命令式 RPC(term.open/input/resize/close)。
 */
import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { normalizeWorkspaceRelativePath } from '@/platform/fs/pathUtils'

/** worker 侧路径以 '.' 表示根;空串归一为 '.'(与 workspaceGateway.toWorkerPath 同款)。 */
function toWorkerPath(path: string): string {
  const normalized = normalizeWorkspaceRelativePath(path)
  return normalized || '.'
}

export const terminalGateway = {
  /**
   * 在 worker 侧打开 PTY 会话(term.open)。
   * 调用方须在调用前已 sub 频道 u.<K>.term.<termId>.stream(避免丢首帧)。
   */
  async open(
    workspaceRoot: string,
    termId: string,
    path: string,
    cols: number,
    rows: number,
    shell?: string,
  ): Promise<{ termId: string; pid: number }> {
    const workerId = workspaceRegistry.workerIdOfRoot(workspaceRoot)
    if (!workerId) throw new Error('无法确定该工作区所属 worker')
    const result = await hubSession.rpcTo(workerId, 'term.open', {
      workspace: workspaceRoot,
      termId,
      path: toWorkerPath(path),
      cols,
      rows,
      ...(shell ? { shell } : {}),
    })
    return { termId: String(result.termId), pid: Number(result.pid) }
  },

  /** data 为 base64 字符串(前端 TextEncoder→base64 编码,UTF-8 安全)。 */
  async input(workspaceRoot: string, termId: string, data: string): Promise<void> {
    const workerId = workspaceRegistry.workerIdOfRoot(workspaceRoot)
    if (!workerId) throw new Error('无法确定该工作区所属 worker')
    await hubSession.rpcTo(workerId, 'term.input', { workspace: workspaceRoot, termId, data })
  },

  async resize(workspaceRoot: string, termId: string, cols: number, rows: number): Promise<void> {
    const workerId = workspaceRegistry.workerIdOfRoot(workspaceRoot)
    if (!workerId) throw new Error('无法确定该工作区所属 worker')
    await hubSession.rpcTo(workerId, 'term.resize', { workspace: workspaceRoot, termId, cols, rows })
  },

  /** 关闭终端(worker 离线 / RPC 失败时静默,不阻断标签卸载)。 */
  async close(workspaceRoot: string, termId: string): Promise<void> {
    const workerId = workspaceRegistry.workerIdOfRoot(workspaceRoot)
    if (!workerId) return
    await hubSession.rpcTo(workerId, 'term.close', { workspace: workspaceRoot, termId }).catch(() => {})
  },
}
