/**
 * Raw 系列(fs.readRaw/fs.writeRaw)越界确认放行的前端核心逻辑(架构 §5.5 fs.*Raw 行)。
 *
 * worker 侧对 Raw 系列做了合法根收敛:越界一律返回 rpc.err{SANDBOX_DENIED}。
 * 本模块提供「捕获越界错误码 → 弹用户确认 → 调 fs.grantRawRoot 注册会话放行目录 →
 * 自动重试原操作一次」的包装器(与 §7.8 PermissionGate kind=authorization 的
 * 「拒绝 → 用户确认 → 重试放行」同语义),由网关的 readBytesRaw/writeBytesRaw 消费;
 * 确认框 UI 经 {@link setRawAccessConfirmHandler} 由宿主组件注册(平台层不 import React)。
 */
import { RpcError } from '@/sdk/frames'

/** 触发确认的 Raw 操作类别(文案区分「打开/读取」与「保存/写入」)。 */
export type RawAccessOp = 'read' | 'write'

export interface RawAccessRequest {
  workerId: string
  /** 机器绝对路径(越界被拒的那个路径,worker 侧放行取其所在目录)。 */
  path: string
  op: RawAccessOp
}

/** 宿主注册的确认回调:true=用户允许(随后放行+重试),false/异常=维持原错误。 */
export type RawAccessConfirmHandler = (request: RawAccessRequest) => Promise<boolean>

/** 放行 RPC 执行器(网关注入:fs.grantRawRoot)。 */
export type RawAccessGranter = (workerId: string, path: string) => Promise<unknown>

/** Raw 系列越界错误码(与 worker SandboxViolationException 的 wire 映射一致)。 */
export const RAW_ACCESS_DENIED_CODE = 'SANDBOX_DENIED'

let confirmHandler: RawAccessConfirmHandler | null = null

/** 宿主组件挂载时注册确认框回调;卸载时置 null(维持原错误直抛)。幂等单槽。 */
export function setRawAccessConfirmHandler(handler: RawAccessConfirmHandler | null): void {
  confirmHandler = handler
}

/** 是否为可确认放行的 Raw 越界错误(独立谓词,测试与网关共用)。 */
export function isRawAccessDenial(error: unknown): boolean {
  return error instanceof RpcError && error.code === RAW_ACCESS_DENIED_CODE
}

/**
 * 带越界确认放行的 Raw 调用包装:首次失败且为 SANDBOX_DENIED 时弹用户确认,
 * 允许 → 放行(grant)→ **重试一次**;拒绝/无确认框/重试再失败 → 抛原错误。
 * 重试只在确认成功后发生一次,绝不循环。
 */
export async function withRawAccessAuthorization<T>(
  request: RawAccessRequest,
  grant: RawAccessGranter,
  invoke: () => Promise<T>,
): Promise<T> {
  try {
    return await invoke()
  } catch (error) {
    if (!isRawAccessDenial(error) || !confirmHandler) {
      throw error
    }
    let allowed = false
    try {
      allowed = await confirmHandler(request)
    } catch {
      allowed = false // 确认框异常按拒绝处理,不放行
    }
    if (!allowed) {
      throw error
    }
    await grant(request.workerId, request.path)
    return await invoke()
  }
}
