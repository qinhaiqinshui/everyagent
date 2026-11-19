import { describe, expect, it, vi } from 'vitest'
import {
  consumePendingDraftWorkspaceBrowse,
  requestDraftWorkspaceBrowse,
  subscribeDraftWorkspaceBrowse,
} from './taskWorkspaceBrowseIntent'

describe('taskWorkspaceBrowseIntent', () => {
  it('未挂载时(无订阅者)意图留在 pending 槽位,挂载消费后清空', () => {
    requestDraftWorkspaceBrowse()
    expect(consumePendingDraftWorkspaceBrowse()).toBe(true)
    expect(consumePendingDraftWorkspaceBrowse()).toBe(false)
  })

  it('已挂载时订阅侧被通知并消费,重挂不会因陈旧 pending 再次弹窗', () => {
    const listener = vi.fn(() => {
      consumePendingDraftWorkspaceBrowse()
    })
    const unsubscribe = subscribeDraftWorkspaceBrowse(listener)
    requestDraftWorkspaceBrowse()
    expect(listener).toHaveBeenCalledTimes(1)
    expect(consumePendingDraftWorkspaceBrowse()).toBe(false)
    unsubscribe()
  })

  it('退订后不再收到通知', () => {
    const listener = vi.fn()
    const unsubscribe = subscribeDraftWorkspaceBrowse(listener)
    unsubscribe()
    requestDraftWorkspaceBrowse()
    expect(listener).not.toHaveBeenCalled()
    consumePendingDraftWorkspaceBrowse()
  })
})