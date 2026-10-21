/**
 * tool 域共享模块纯函数单测(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * 覆盖:resultsFor 按 callId 配对(未到 null/对位/同 callId 首达优先)、
 * absorbedIntoMessage 吸收判定(被下发吸收 true/孤儿 false/缺 callId 防御)、
 * detailOfCall/detailOfOrphan 的载荷聚合(参数解析/结果 JSON 解析/工具名优先级)、
 * interruptedAfterMessage 可见终态信号(cancelled 任务级/同 agent error/异 agent 不误伤/
 * seq 更早不误伤/空画板安全)。
 */
import { describe, expect, it } from 'vitest'
import type { LLMToolCall } from '@/types'
import type { ThreadItem, ToolResultPayload } from '@/task/threadItem'
import {
  absorbedIntoMessage,
  detailOfCall,
  detailOfOrphan,
  interruptedAfterMessage,
  resultsFor,
} from './toolDomain'

function callOf(id: string, name = 'read_file', args = ''): LLMToolCall {
  return { id, type: 'function', function: { name, arguments: args } }
}

function messageItem(seq: string, toolCalls: LLMToolCall[], agentId = 'main'): ThreadItem {
  return {
    seq,
    ts: 1,
    agentId,
    kind: 'message',
    payload: { role: 'assistant', content: '', toolCalls },
  }
}

function toolResultItem(seq: string, payload: ToolResultPayload, agentId = 'main'): ThreadItem {
  return { seq, ts: 1, agentId, kind: 'tool.result', payload }
}

function resultOf(callId: string, extra: Partial<ToolResultPayload> = {}): ToolResultPayload {
  return { callId, content: 'ok', status: 'success', ...extra }
}

describe('resultsFor(message 视图批壳配对)', () => {
  it('按 callId 对位配对;结果未到 → null', () => {
    const calls = [callOf('c1'), callOf('c2'), callOf('c3')]
    const items = [toolResultItem('20', resultOf('c2'))]
    expect(resultsFor(calls, items)).toEqual([null, resultOf('c2'), null])
  })

  it('同 callId 多条结果首达优先(与旧 buildToolMaps 语义一致)', () => {
    const calls = [callOf('c1')]
    const items = [
      toolResultItem('20', resultOf('c1', { content: 'first' })),
      toolResultItem('30', resultOf('c1', { content: 'second' })),
    ]
    expect(resultsFor(calls, items)[0]?.content).toBe('first')
  })

  it('空批/空画板安全;callId 前后空白参与匹配(载荷写入方已归一)', () => {
    expect(resultsFor([], [])).toEqual([])
    const items = [toolResultItem('20', resultOf(' c1 '))]
    expect(resultsFor([callOf('c1')], items)[0]?.callId).toBe(' c1 ')
  })

  it('批内同名多调用不折叠:10 个 read_file 各自独立配对', () => {
    const calls = Array.from({ length: 10 }, (_, i) => callOf(`c${i}`, 'read_file'))
    const items = calls
      .filter((_, i) => i % 2 === 0)
      .map((c, i) => toolResultItem(String(20 + i), resultOf(c.id)))
    const results = resultsFor(calls, items)
    expect(results).toHaveLength(10)
    expect(results.filter((r) => r == null)).toHaveLength(5)
  })
})

describe('absorbedIntoMessage(tool.result 吸收判定)', () => {
  it('画板内存在含该 callId 的 message 下发 → true(视图 return null)', () => {
    const message = messageItem('10', [callOf('c1'), callOf('c2')])
    const result = toolResultItem('20', resultOf('c2'))
    expect(absorbedIntoMessage(result, [message, result])).toBe(true)
  })

  it('孤儿(callId 无对应下发)→ false(独立兜底渲染不丢)', () => {
    const message = messageItem('10', [callOf('c1')])
    const orphan = toolResultItem('20', resultOf('c-other'))
    expect(absorbedIntoMessage(orphan, [message, orphan])).toBe(false)
  })

  it('缺 callId 的防御分支 → false(不吸收,交孤儿兜底不丢内容)', () => {
    const item = toolResultItem('20', { callId: '', content: 'x' })
    expect(absorbedIntoMessage(item, [messageItem('10', [callOf('c1')]), item])).toBe(false)
  })

  it('非 message/tool.result 的兄弟行不参与判定', () => {
    const trace: ThreadItem = { seq: '15', ts: 1, agentId: 'main', kind: 'retry', payload: { title: 't' } }
    const orphan = toolResultItem('20', resultOf('c1'))
    expect(absorbedIntoMessage(orphan, [trace, orphan])).toBe(false)
  })
})

describe('detailOfCall / detailOfOrphan(载荷 → 美化视图输入)', () => {
  it('参数 JSON 解析,空/非法参数 → null;结果 JSON 可解析取解析值', () => {
    const detail = detailOfCall(
      callOf('c1', 'read_file', '{"path":"a.md"}'),
      resultOf('c1', { content: '{"lines":3}' }),
    )
    expect(detail.arguments).toEqual({ path: 'a.md' })
    expect(detail.result).toEqual({ lines: 3 })
    expect(detail.status).toBe('success')
    expect(detail.toolName).toBe('read_file')
  })

  it('结果未到(null)→ result null/status undefined;结果 toolName 优先于下发名', () => {
    expect(detailOfCall(callOf('c1', 'dispatch-name'), null).result).toBeNull()
    expect(detailOfCall(callOf('c1', 'dispatch-name'), null).status).toBeUndefined()
    const detail = detailOfCall(callOf('c1', 'dispatch-name'), resultOf('c1', { toolName: 'backfill-name' }))
    expect(detail.toolName).toBe('backfill-name')
  })

  it('非 JSON 结果文本保留原文;参数解析失败 → null', () => {
    const detail = detailOfCall(
      callOf('c1', 'shell', 'not-json'),
      resultOf('c1', { content: 'plain output' }),
    )
    expect(detail.arguments).toBeNull()
    expect(detail.result).toBe('plain output')
  })

  it('孤儿详情:无下发锚点,参数 null,工具名回填缺失时回退「工具」', () => {
    expect(detailOfOrphan(resultOf('c1', { toolName: 'known' })).toolName).toBe('known')
    const unknown = detailOfOrphan(resultOf('c1', { content: 'raw' }))
    expect(unknown.toolName).toBe('工具')
    expect(unknown.arguments).toBeNull()
    expect(unknown.result).toBe('raw')
  })
})

describe('interruptedAfterMessage(批中断可见终态信号)', () => {
  const message = messageItem('10', [callOf('c1')], 'main')

  it('seq 更晚的任务级 cancelled → true(所有 agent 的批都中断)', () => {
    const cancelled: ThreadItem = {
      seq: '30', ts: 1, agentId: '', kind: 'cancelled', payload: { title: '任务已取消' },
    }
    expect(interruptedAfterMessage(message, [message, cancelled], 'main')).toBe(true)
  })

  it('seq 更晚的同 agent error → true;异 agent error 不误伤', () => {
    const errorSame: ThreadItem = {
      seq: '30', ts: 1, agentId: 'main', kind: 'error', payload: { title: '任务出错' },
    }
    expect(interruptedAfterMessage(message, [message, errorSame], 'main')).toBe(true)
    const errorOther: ThreadItem = {
      seq: '30', ts: 1, agentId: 'sub-1', kind: 'error', payload: { title: '子任务出错' },
    }
    expect(interruptedAfterMessage(message, [message, errorOther], 'main')).toBe(false)
  })

  it('seq 更早的终态信号不误伤;空画板安全', () => {
    const earlierError: ThreadItem = {
      seq: '5', ts: 1, agentId: 'main', kind: 'error', payload: { title: '旧错' },
    }
    expect(interruptedAfterMessage(message, [earlierError, message], 'main')).toBe(false)
    expect(interruptedAfterMessage(message, [], 'main')).toBe(false)
  })

  it('agentId 空串经 mainAgentId 归一后比较(消息缺 agentId 的早期形态)', () => {
    const earlyMessage = messageItem('10', [callOf('c1')], '')
    const error: ThreadItem = {
      seq: '30', ts: 1, agentId: 'main', kind: 'error', payload: { title: '任务出错' },
    }
    expect(interruptedAfterMessage(earlyMessage, [earlyMessage, error], 'main')).toBe(true)
  })
})
