/**
 * 事件注册表：kind → handler 查表分发。
 *
 * 折叠器核心不关心某 kind 该渲染还是该改内存——该进线程还是改状态是注册方自己知道的事。
 * 核心只看事件自带的 kind（是什么），通过查表找到对应 handler 调用。
 * 未注册 kind → defaultHandler 默认 trace 形状展示（内容零丢失）。
 *
 * handler 接口极简：接收 (event, state, folder)，返回是否状态有变化（驱动重渲染）。
 * handler 通过组合调用 folder 上的公共 helper（insertMessage/appendRoundText/
 * completeMessage/upsertTraceBySeq/insertTrace/truncateAfterSeq/closeStreamingByAgent/
 * mergeAgentMeta 等）完成折叠。
 */
import type { FoldableTaskEvent, TaskEventFolder, TaskThreadState } from './eventFolder'
import {
  readUsage,
  readNum,
  readStr,
  mapAgentStatus,
  DEFAULT_CONTEXT_WINDOW_TOKENS,
} from './eventFolder'

/** 事件处理器接口。 */
export interface EventHandler {
  handle(event: FoldableTaskEvent, state: TaskThreadState, folder: TaskEventFolder): boolean
}

const registry = new Map<string, EventHandler>()

/** 注册一个 kind 的 handler。重复注册同 kind 会覆盖。 */
export function registerEventKind(kind: string, handler: EventHandler): void {
  registry.set(kind, handler)
}

/** 按 kind 获取 handler。 */
export function getHandler(kind: string): EventHandler | undefined {
  return registry.get(kind)
}

// ---- 工具：从 event 提取公共字段 ----

/** 从事件中提取 agentKey（agentId 字段或 payload.agentId）。 */
function agentKeyOf(event: FoldableTaskEvent): string {
  return event.agentId ?? (event.payload?.agentId as string | undefined) ?? ''
}

/** 从事件中提取时间戳。 */
function tsOf(event: FoldableTaskEvent): number {
  return event.ts || Date.now()
}

/** 从 payload.data 中提取 kind 专属数据。 */
function dataOf(event: FoldableTaskEvent): Record<string, unknown> | undefined {
  const data = event.payload?.data
  if (data != null && typeof data === 'object' && !Array.isArray(data)) {
    return data as Record<string, unknown>
  }
  return undefined
}

// ---- 默认 handler（未注册 kind 的降级） ----

/**
 * 默认 handler：未注册 kind 按 trace 形状插入线程项。
 * - title + summary 收起态 / content `<pre>` 展开态 / content 空而 data 非空时 JSON.stringify(data)
 * - 按 seq upsert（同 seq 更新，新 seq 插入）
 * - 效果：未来新增任何 kind → 前端零注册零改动即自动可见、内容零丢失
 */
export function defaultHandler(event: FoldableTaskEvent, state: TaskThreadState, folder: TaskEventFolder): boolean {
  const agentKey = agentKeyOf(event)
  const ts = tsOf(event)
  folder.upsertTraceBySeq(agentKey, event.event, event.payload, ts, event.seq)
  return true
}

// ---- 注册全部现役 kind 的 handler ----

/** user.message → role:'user' 消息 */
registerEventKind('user.message', {
  handle(event, state, folder) {
    const seqKey = String(event.seq)
    if (folder.hasSeq(seqKey)) {
      console.debug('[uref] user.message handler 跳过(同 seq 已折入) seq=', seqKey)
      return false
    }
    const agentKey = agentKeyOf(event)
    const ts = tsOf(event)
    const content = String(event.payload?.content ?? '')
    const data = dataOf(event)
    const rawContent = typeof data?.rawContent === 'string' && data.rawContent.length
      ? data.rawContent : content
    // [uref] @文件引用胶囊丢失排查:折入线程时的 rawContent 解析结果(缺失/为空回退 content → 胶囊丢失)。
    console.debug(
      '[uref] user.message handler 折入 seq=', seqKey,
      'content=', JSON.stringify(content.slice(0, 80)),
      'dataType=', data === undefined ? 'undefined' : 'object',
      'dataRawGiven=', typeof data?.rawContent === 'string' && data.rawContent.length > 0,
      'resolvedRawLen=', rawContent.length,
      'resolvedRawHasToken=', rawContent.includes('[[[['),
    )
    folder.insertMessage(agentKey, {
      messageId: `m-${event.seq}`,
      agentId: agentKey,
      role: 'user',
      content,
      rawContent,
      historyMode: 'thread_only',
      createdAt: ts,
      updatedAt: ts,
      sequence: Number(event.seq),
    }, seqKey)
    return true
  },
})

/** delta → 流式追加 content */
registerEventKind('delta', {
  handle(event, state, folder) {
    folder.appendRoundText('delta', agentKeyOf(event), tsOf(event), event.seq,
      String(event.payload?.content ?? ''))
    return true
  },
})

/** thinking → 流式追加 reasoning */
registerEventKind('thinking', {
  handle(event, state, folder) {
    folder.appendRoundText('thinking', agentKeyOf(event), tsOf(event), event.seq,
      String(event.payload?.content ?? ''))
    return true
  },
})

/** message → 一轮权威终结：完整 content/thinking/toolCalls */
registerEventKind('message', {
  handle(event, state, folder) {
    folder.completeMessage(agentKeyOf(event), event.payload, tsOf(event), event.seq)
    return true
  },
})

/** tool.result → role:'tool' 消息 */
registerEventKind('tool.result', {
  handle(event, state, folder) {
    const seqKey = String(event.seq)
    if (folder.hasSeq(seqKey)) return false
    const agentKey = agentKeyOf(event)
    const ts = tsOf(event)
    const data = dataOf(event)
    const callId = String(data?.callId ?? '')
    folder.closeStreamingByAgent(agentKey)
    folder.insertMessage(agentKey, {
      messageId: `m-${event.seq}`,
      agentId: agentKey,
      role: 'tool',
      content: String(event.payload?.content ?? event.payload?.summary ?? ''),
      historyMode: 'thread_only',
      createdAt: ts,
      updatedAt: ts,
      sequence: Number(event.seq),
      toolCallId: callId,
      toolName: folder.getToolName(callId),
      status: 'success',
      metadata: data?.truncated ? { truncated: true } : undefined,
    }, seqKey)
    return true
  },
})

/** usage → 更新 contextUsage + agentMeta */
registerEventKind('usage', {
  handle(event, state, folder) {
    const agentKey = agentKeyOf(event)
    const ts = tsOf(event)
    const data = dataOf(event) ?? {}
    const round = readUsage(data.round)
    const total = readUsage(data.total)
    if (!agentKey && round) {
      const maxTokens = readNum(data.contextWindowTokens) ?? DEFAULT_CONTEXT_WINDOW_TOKENS
      const model = readStr(data.model)
      state.contextUsage = {
        promptTokens: round.inputTokens,
        completionTokens: round.outputTokens,
        totalTokens: total?.totalTokens ?? round.totalTokens,
        maxTokens,
        usageRatio: maxTokens > 0 ? round.inputTokens / maxTokens : 0,
        lastUpdatedAt: ts,
        requestType: 'chatStream',
        model: model ?? '',
      }
    }
    folder.mergeAgentMeta(agentKey, {
      contextUsed: round?.inputTokens,
      contextWindow: readNum(data.contextWindowTokens),
      model: readStr(data.model),
      inputTokens: total?.inputTokens,
      outputTokens: total?.outputTokens,
      totalTokens: total?.totalTokens,
      updatedAt: ts,
    })
    return true
  },
})

/** agent.started → 更新 agentMeta + agentStates（兜底） */
registerEventKind('agent.started', {
  handle(event, state, folder) {
    const seqKey = String(event.seq)
    if (folder.hasSeq(seqKey)) return false
    const agentKey = agentKeyOf(event)
    const ts = tsOf(event)
    const title = String(event.payload?.title ?? '')
    if (agentKey) folder.setSubTitle(agentKey, title)
    folder.mergeAgentMeta(agentKey, {
      agentId: agentKey,
      title: title !== '' ? title : undefined,
      createdAt: ts,
    })
    if (agentKey) state.agentStates[agentKey] = 'running'
    return true
  },
})

/** agent.done → 更新 agentStates + agentMeta（收口用量） */
registerEventKind('agent.done', {
  handle(event, state, folder) {
    const seqKey = String(event.seq)
    if (folder.hasSeq(seqKey)) return false
    const agentKey = agentKeyOf(event)
    const ts = tsOf(event)
    folder.closeStreamingByAgent(agentKey)
    state.agentStates[agentKey] = 'completed'
    const data = dataOf(event)
    const doneUsage = readUsage(data?.usage)
    folder.mergeAgentMeta(agentKey, {
      inputTokens: doneUsage?.inputTokens,
      outputTokens: doneUsage?.outputTokens,
      totalTokens: doneUsage?.totalTokens,
      updatedAt: ts,
    })
    return true
  },
})

/** agent.status → 更新 agentStates（权威状态事件） */
registerEventKind('agent.status', {
  handle(event, state, folder) {
    const agentKey = agentKeyOf(event)
    const mapped = mapAgentStatus(String(event.payload?.status ?? ''))
    if (mapped) state.agentStates[agentKey] = mapped
    return true
  },
})

/** error → trace（run_error） */
registerEventKind('error', {
  handle(event, state, folder) {
    const seqKey = String(event.seq)
    if (folder.hasSeq(seqKey)) return false
    const agentKey = agentKeyOf(event)
    const ts = tsOf(event)
    const message = String(event.payload?.content ?? event.payload?.message ?? '')
    folder.closeStreamingByAgent(agentKey)
    state.agentStates[agentKey] = 'error'
    folder.insertTrace(ts, {
      traceId: `t-${event.seq}`,
      taskId: state.taskId,
      agentId: agentKey || undefined,
      kind: 'run_error',
      title: agentKey ? '子任务出错' : '任务出错',
      summary: message,
      content: message,
      createdAt: ts,
    }, event.seq)
    return true
  },
})

/** cancelled → trace（system_notice） */
registerEventKind('cancelled', {
  handle(event, state, folder) {
    const seqKey = String(event.seq)
    if (folder.hasSeq(seqKey)) return false
    const ts = tsOf(event)
    const data = dataOf(event)
    folder.closeStreamingByAgent('')
    state.agentStates[''] = 'stopped'
    folder.insertTrace(ts, {
      traceId: `t-${event.seq}`,
      taskId: state.taskId,
      kind: 'system_notice',
      title: '任务已取消',
      summary: `由 ${String(data?.by ?? '用户')} 取消`,
      createdAt: ts,
    }, event.seq)
    return true
  },
})

// ---- trace 族：按 seq upsert ----

/** retry → upsertBySeq trace */
registerEventKind('retry', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** context.compression → upsertBySeq trace */
registerEventKind('context.compression', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** model.failover → upsertBySeq trace */
registerEventKind('model.failover', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** model.switch → upsertBySeq trace */
registerEventKind('model.switch', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** auth.review → upsertBySeq trace */
registerEventKind('auth.review', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** system.notice → upsertBySeq trace */
registerEventKind('system.notice', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

// ---- ask 族：不进线程（卡片由 askStore 单独驱动） ----

/** ask.create → 兜底：该 agent 挂起等待用户 */
registerEventKind('ask.create', {
  handle(event, state, folder) {
    state.agentStates[agentKeyOf(event)] = 'waiting-user'
    return true
  },
})

/** ask.resolved → 兜底：该 agent 恢复执行 */
registerEventKind('ask.resolved', {
  handle(event, state, folder) {
    const data = dataOf(event)
    const resolvedStatus = String(data?.status ?? event.payload?.status ?? '')
    if (resolvedStatus !== 'cancelled') {
      state.agentStates[agentKeyOf(event)] = 'running'
    }
    return true
  },
})

/** ask.state → 不进线程（由 askStore 驱动卡片） */
registerEventKind('ask.state', {
  handle() {
    return true
  },
})

// ---- 消息编辑 / 轮次信号 ----

/** message.edited → 截断 seq >= editedSeq 的所有线程项 */
registerEventKind('message.edited', {
  handle(event, state, folder) {
    const data = dataOf(event)
    const editedSeq = String(data?.seq ?? event.payload?.seq ?? event.seq)
    folder.truncateAfterSeq(editedSeq)
    return true
  },
})

/** round.opened → 瞬态，不进线程 */
registerEventKind('round.opened', {
  handle() {
    return true
  },
})

/** round.closed → 瞬态，不进线程 */
registerEventKind('round.closed', {
  handle() {
    return true
  },
})
