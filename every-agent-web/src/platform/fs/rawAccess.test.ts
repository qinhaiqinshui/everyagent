import { describe, expect, it, vi, afterEach } from 'vitest'
import { RpcError } from '@/sdk/frames'
import {
  RAW_ACCESS_DENIED_CODE,
  isRawAccessDenial,
  setRawAccessConfirmHandler,
  withRawAccessAuthorization,
  type RawAccessRequest,
} from './rawAccess'

/**
 * Raw 系列越界确认放行逻辑(§5.5 fs.*Raw)单元测试:
 * SANDBOX_DENIED → 用户确认 → fs.grantRawRoot → 重试一次;拒绝/其他错误码/重试再败
 * 均维持原错误,不循环放行。
 */

const request: RawAccessRequest = { workerId: 'w1', path: 'C:/outside/file.txt', op: 'read' }

function denial(): RpcError {
  return new RpcError(RAW_ACCESS_DENIED_CODE, '路径越界: C:/outside/file.txt')
}

describe('isRawAccessDenial', () => {
  it('仅识别 SANDBOX_DENIED 的 RpcError', () => {
    expect(isRawAccessDenial(denial())).toBe(true)
    expect(isRawAccessDenial(new RpcError('NOT_FOUND', 'x'))).toBe(false)
    expect(isRawAccessDenial(new Error('普通错误'))).toBe(false)
    expect(isRawAccessDenial(null)).toBe(false)
  })
})

describe('withRawAccessAuthorization', () => {
  afterEach(() => {
    setRawAccessConfirmHandler(null)
  })

  it('首次成功不弹确认、不放行', async () => {
    const confirm = vi.fn().mockResolvedValue(true)
    const grant = vi.fn().mockResolvedValue(undefined)
    const invoke = vi.fn().mockResolvedValue('ok')
    setRawAccessConfirmHandler(confirm)

    await expect(withRawAccessAuthorization(request, grant, invoke)).resolves.toBe('ok')

    expect(invoke).toHaveBeenCalledTimes(1)
    expect(confirm).not.toHaveBeenCalled()
    expect(grant).not.toHaveBeenCalled()
  })

  it('越界被拒 → 用户允许 → 放行同路径 → 重试成功', async () => {
    const confirm = vi.fn().mockResolvedValue(true)
    const grant = vi.fn().mockResolvedValue(undefined)
    const invoke = vi.fn()
      .mockRejectedValueOnce(denial())
      .mockResolvedValueOnce('retried')
    setRawAccessConfirmHandler(confirm)

    await expect(withRawAccessAuthorization(request, grant, invoke)).resolves.toBe('retried')

    expect(confirm).toHaveBeenCalledWith(request)
    expect(grant).toHaveBeenCalledWith('w1', 'C:/outside/file.txt')
    expect(invoke).toHaveBeenCalledTimes(2)
  })

  it('用户拒绝 → 维持原错误,不调用放行、不重试', async () => {
    const confirm = vi.fn().mockResolvedValue(false)
    const grant = vi.fn()
    const error = denial()
    const invoke = vi.fn().mockRejectedValue(error)
    setRawAccessConfirmHandler(confirm)

    await expect(withRawAccessAuthorization(request, grant, invoke)).rejects.toBe(error)

    expect(grant).not.toHaveBeenCalled()
    expect(invoke).toHaveBeenCalledTimes(1)
  })

  it('非越界错误码直接透传,不弹确认', async () => {
    const confirm = vi.fn()
    const grant = vi.fn()
    const error = new RpcError('NOT_FOUND', '文件不存在')
    const invoke = vi.fn().mockRejectedValue(error)
    setRawAccessConfirmHandler(confirm)

    await expect(withRawAccessAuthorization(request, grant, invoke)).rejects.toBe(error)

    expect(confirm).not.toHaveBeenCalled()
    expect(grant).not.toHaveBeenCalled()
  })

  it('无确认框宿主(未注册)时透传越界错误', async () => {
    const grant = vi.fn()
    const error = denial()
    const invoke = vi.fn().mockRejectedValue(error)

    await expect(withRawAccessAuthorization(request, grant, invoke)).rejects.toBe(error)
    expect(invoke).toHaveBeenCalledTimes(1)
  })

  it('放行后重试仍失败 → 抛第二次错误,绝不三次调用', async () => {
    const confirm = vi.fn().mockResolvedValue(true)
    const grant = vi.fn().mockResolvedValue(undefined)
    const second = new RpcError('INTERNAL', '重试仍失败')
    const invoke = vi.fn()
      .mockRejectedValueOnce(denial())
      .mockRejectedValueOnce(second)
    setRawAccessConfirmHandler(confirm)

    await expect(withRawAccessAuthorization(request, grant, invoke)).rejects.toBe(second)
    expect(invoke).toHaveBeenCalledTimes(2)
    expect(grant).toHaveBeenCalledTimes(1)
  })

  it('确认框自身异常按拒绝处理', async () => {
    const confirm = vi.fn().mockRejectedValue(new Error('UI 崩溃'))
    const grant = vi.fn()
    const error = denial()
    const invoke = vi.fn().mockRejectedValue(error)
    setRawAccessConfirmHandler(confirm)

    await expect(withRawAccessAuthorization(request, grant, invoke)).rejects.toBe(error)
    expect(grant).not.toHaveBeenCalled()
  })
})
