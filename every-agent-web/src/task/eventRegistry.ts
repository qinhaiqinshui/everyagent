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

/**
 * 从 event 中提取 agent 键(主 agent = mainAgentId)。
 * 委托 {@link TaskEventFolder#agentKeyOf}:payload.agentId 缺失(旧 wire 形态主 agent
 * 事件不带 agentId)归一到 mainAgentId——键域统一由 task 层传入的固定 id 决定,
 * 不使用「空串=主」魔法。
 */
function agentKeyOf(event: FoldableTaskEvent, folder: TaskEventFolder): string {
  return folder.agentKeyOf(event)
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

// ---- 工具结果状态 ----

/**
 * 工具结果状态：worker 在 `tool.result` 的 `data.status` 里下发（判定收口在 worker 的单点：
 * shell 退出码 / 超时 / 工具抛异常走结构化记账，见 plugin-api `ToolOutcomeLog`）。
 *
 * 无该字段（本改动之前落盘的历史记录）一律按成功——**不做文案匹配推断**：
 * 那会让「读/grep 到失败文案源码」的命令被自指误判为失败（实测复现过）。
 */
function readToolStatus(rawStatus: unknown): 'success' | 'error' {
  return rawStatus === 'error' ? 'error' : 'success'
}

// ---- 默认 handler（未注册 kind 的降级） ----

/**
 * 默认 handler：未注册 kind 按 trace 形状插入线程项。
 * - title + summary 收起态 / content `<pre>` 展开态 / content 空而 data 非空时 JSON.stringify(data)
 * - 按 seq upsert（同 seq 更新，新 seq 插入）
 * - 效果：未来新增任何 kind → 前端零注册零改动即自动可见、内容零丢失
 */
export function defaultHandler(event: FoldableTaskEvent, state: TaskThreadState, folder: TaskEventFolder): boolean {
  const agentKey = agentKeyOf(event, folder)
  const ts = tsOf(event)
  folder.upsertTraceBySeq(agentKey, event.event, event.payload, ts, event.seq)
  return true
}

// ---- 注册全部现役 kind 的 handler ----

/** user.message → role:'user' 消息(主/子 agent 共用同一事件名;foldRole 仅主 agent 打标) */
registerEventKind('user.message', {
  handle(event, state, folder) {
    const seqKey = String(event.seq)
    const content = String(event.payload?.content ?? '')
    const data = dataOf(event)
    const rawContent = typeof data?.rawContent === 'string' && data.rawContent.length
      ? data.rawContent : content
    const agentKey = agentKeyOf(event, folder)
    const ts = tsOf(event)
    // 主 agent:user回合折叠窗口起点(foldRole='user'),有合成骨架时先升级再跳过。
    if (folder.isMain(agentKey)) {
      if (folder.hasSeq(seqKey)) {
        // 真实事件后到但骨架先入(loadRoundsIntoFolder(full) 的 reset 竞态):合成骨架可被
        // 权威 payload 原地升级(骨架可能缺 data.rawContent → @文件胶囊丢失,真实事件是唯一权威)。
        return folder.upgradeSyntheticUser(seqKey, {
          content,
          rawContent,
          agentId: agentKey,
          ts,
        })
      }
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
    }
    // 子 agent 的输入指令:同样以 role:'user' 消息插入(与主 agent 共用渲染组件),
    // 但 foldRole 不打标(不作为折叠窗口起点,不扰乱折叠扫描/轮次骨架)。
    // agentId 区分主/子——渲染层据此区分样式(子 agent 气泡用低调背景色)且不带编辑重发扩展点。
    if (folder.hasSeq(seqKey)) return false
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
    folder.appendRoundText('delta', agentKeyOf(event, folder), tsOf(event), event.seq,
      String(event.payload?.content ?? ''))
    return true
  },
})

/** thinking → 流式追加 reasoning */
registerEventKind('thinking', {
  handle(event, state, folder) {
    folder.appendRoundText('thinking', agentKeyOf(event, folder), tsOf(event), event.seq,
      String(event.payload?.content ?? ''))
    return true
  },
})

/** message → 一轮权威终结：完整 content/thinking/toolCalls */
registerEventKind('message', {
  handle(event, state, folder) {
    folder.completeMessage(agentKeyOf(event, folder), event.payload, tsOf(event), event.seq)
    return true
  },
})

/** tool.result → role:'tool' 消息 */
registerEventKind('tool.result', {
  handle(event, state, folder) {
    const seqKey = String(event.seq)
    if (folder.hasSeq(seqKey)) return false
    const agentKey = agentKeyOf(event, folder)
    const ts = tsOf(event)
    const data = dataOf(event)
    const callId = String(data?.callId ?? '')
    const content = String(event.payload?.content ?? event.payload?.summary ?? '')
    folder.closeStreamingByAgent(agentKey)
    folder.insertMessage(agentKey, {
      messageId: `m-${event.seq}`,
      agentId: agentKey,
      role: 'tool',
      content,
      historyMode: 'thread_only',
      createdAt: ts,
      updatedAt: ts,
      sequence: Number(event.seq),
      toolCallId: callId,
      toolName: folder.getToolName(callId),
      // 权威状态来自 worker 的 data.status（判定收口在 worker：退出码/超时/抛异常的结构化记账）。
      status: readToolStatus(data?.status),
    }, seqKey)
    return true
  },
})

/** usage → 更新 agentMeta(每 agent 最近一轮占用/累计);任务级电池走 taskStore 的 worker 聚合值 */
registerEventKind('usage', {
  handle(event, state, folder) {
    const agentKey = agentKeyOf(event, folder)
    const ts = tsOf(event)
    const data = dataOf(event) ?? {}
    const round = readUsage(data.round)
    const total = readUsage(data.total)
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
    const agentKey = agentKeyOf(event, folder)
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
    const agentKey = agentKeyOf(event, folder)
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
    const agentKey = agentKeyOf(event, folder)
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
    const agentKey = agentKeyOf(event, folder)
    const ts = tsOf(event)
    const message = String(event.payload?.content ?? event.payload?.message ?? '')
    folder.closeStreamingByAgent(agentKey)
    state.agentStates[agentKey] = 'error'
    folder.insertTrace(ts, {
      traceId: `t-${event.seq}`,
      taskId: state.taskId,
      agentId: agentKey,
      kind: 'run_error',
      // 主/子判定唯一依据 = isMain(agentKey === mainAgentId):wire 全事件必带真实
      // agentId(主=mainAgentId),不允许用「键是否空串」判主(agentKeyOf 会把缺失
      // 归一到 mainKey,mainAgentId 灌入后键恒非空,空串判据会把主错标成子)。
      title: folder.isMain(agentKey) ? '任务出错' : '子任务出错',
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
    folder.closeStreamingByAgent(folder.mainKey())
    state.agentStates[folder.mainKey()] = 'stopped'
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
    folder.upsertTraceBySeq(agentKeyOf(event, folder), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** context.compression → upsertBySeq trace */
registerEventKind('context.compression', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event, folder), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** model.failover → upsertBySeq trace */
registerEventKind('model.failover', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event, folder), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** model.switch → upsertBySeq trace */
registerEventKind('model.switch', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event, folder), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** auth.review → upsertBySeq trace */
registerEventKind('auth.review', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event, folder), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

/** system.notice → upsertBySeq trace */
registerEventKind('system.notice', {
  handle(event, state, folder) {
    folder.upsertTraceBySeq(agentKeyOf(event, folder), event.event, event.payload, tsOf(event), event.seq)
    return true
  },
})

// ---- ask 族：不进线程（卡片由 askStore 单独驱动） ----

/** ask.create → 兜底：该 agent 挂起等待用户 */
registerEventKind('ask.create', {
  handle(event, state, folder) {
    state.agentStates[agentKeyOf(event, folder)] = 'waiting-user'
    return true
  },
})

/** ask.resolved → 兜底：该 agent 恢复执行 */
registerEventKind('ask.resolved', {
  handle(event, state, folder) {
    const data = dataOf(event)
    const resolvedStatus = String(data?.status ?? event.payload?.status ?? '')
    if (resolvedStatus !== 'cancelled') {
      state.agentStates[agentKeyOf(event, folder)] = 'running'
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
