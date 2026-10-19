/**
 * 内置 kind 视图单测(「任务线程统一条目与画板纯化」步骤 10,renderToString/node 口径)。
 *
 * 覆盖:message 视图批壳(调用行摘要/WrenchIcon 状态图标 class/图标后耗时/
 * 批内同名不折叠/无正文纯下发轮无占位文案/权威耗时冻结)、运行态读秒(tool.started
 * 瞬态点亮 running 旋转)、中断态(可见终态信号 interrupted 黄/skipped 灰,图标
 * 颜色承载状态,不显示中文文案)、user.message
 * 回放胶囊段与子 agent 低调样式、孤儿 tool.result 兜底、error/cancelled kind 直配
 * (run_error 错误色调/system_notice)、TaskThread 查表分发 + FallbackView 兜底。
 * 纯函数段(buildUserMessageReplaySegments)直测;渲染断言与 registry.test.ts 同款
 * react-dom/server 方案(零新依赖)。
 */
import { afterEach, describe, expect, it } from 'vitest'
import React from 'react'
import { renderToString } from 'react-dom/server'
import type { LLMToolCall } from '@/types'
import type { ThreadItem, ToolResultPayload } from '@/task/threadItem'
import { initializeTraceTypes } from '@/plugin/traceTypeRegistry'
import { buildOpaqueTokenText } from '@/composerToken/composerOpaqueToken'
import { clearTaskToolStarted, recordToolStarted } from '@/task/toolStartedRuntime'
import TaskThread from '@/components/task/TaskThread'
import { ThreadBoardProvider } from './threadBoardContext'
import { MessageView, formatToolDuration } from './messageView'
import { UserMessageView, buildUserMessageReplaySegments } from './userMessageView'
import { ToolResultView } from './toolResultView'
import { ErrorView, CancelledView } from './statusViews'

const TASK_ID = 'task-builtin-views'
const MAIN = 'agent-main'

function callOf(id: string, name: string, args: string): LLMToolCall {
  return { id, type: 'function', function: { name, arguments: args } }
}

function messageItem(seq: string, payload: Record<string, unknown>, agentId = MAIN): ThreadItem {
  return { seq, ts: 1730000000000, agentId, kind: 'message', payload: { role: 'assistant', content: '', ...payload } }
}

function toolResultItem(seq: string, payload: ToolResultPayload): ThreadItem {
  return { seq, ts: 1730000000000, agentId: MAIN, kind: 'tool.result', payload }
}

/** 带画板 Provider 渲染(视图经 useThreadBoard() 做兄弟行访问)。 */
function renderBoarded(view: React.ReactElement, items: readonly ThreadItem[]): string {
  return renderToString(React.createElement(ThreadBoardProvider, { items, children: view }))
}

function renderMessage(item: ThreadItem, items: readonly ThreadItem[]): string {
  return renderBoarded(
    React.createElement(MessageView, {
      item,
      payload: item.payload as never,
      taskId: TASK_ID,
      mainAgentId: MAIN,
    }),
    items,
  )
}

afterEach(() => {
  // tool.started 瞬态映射按 task 域隔离,每用例清空避免跨用例串扰。
  clearTaskToolStarted(TASK_ID)
})

describe('message 视图批壳(批壳+调用行模型)', () => {
  const calls = [
    callOf('c1', 'read_file', '{"path":"src/a.ts"}'),
    callOf('c2', 'powershell', '{"command":"# 列出文件\\nls"}'),
    callOf('c3', 'read_file', '{"path":"src/b.ts"}'),
  ]
  const items: ThreadItem[] = [
    messageItem('10', {
      content: '',
      toolCalls: calls,
    }),
    toolResultItem('20', { callId: 'c1', content: 'a 内容', status: 'success', durationMs: 1500 }),
    toolResultItem('30', { callId: 'c2', content: 'boom', status: 'error', durationMs: 25000 }),
  ]

  it('批壳默认展开,直接显示调用行(无批头统计)', () => {
    const html = renderMessage(items[0], items)
    expect(html).toContain('nagent-tool-batch')
    expect(html).not.toContain('个调用')
    expect(html).toContain('nagent-tool-batch__rows')
  })

  it('调用行:等宽工具名 + per-tool 参数摘要(path/command 首行注释两段)', () => {
    const html = renderMessage(items[0], items)
    expect(html).toContain('src/a.ts')
    expect(html).toContain('列出文件')
    expect(html).toContain('nagent-tool-batch__name')
    expect(html).toContain('nagent-tool-batch__preview-comment')
  })

  it('状态点:done ✓绿 / error ✗红 / queued 未开始;图标后权威耗时(durationMs 冻结)', () => {
    const html = renderMessage(items[0], items)
    expect(html).toContain('nagent-tool-state--done')
    expect(html).toContain('nagent-tool-state--error')
    expect(html).toContain('nagent-tool-state--queued')
    // ✓ 字符已移除,改用 WrenchIcon 绿色
    // ✗ 字符已移除,改用 WrenchIcon 红色
    // 权威耗时:c1=1500ms → 1.5s;c2=25000ms → 25s;排队行无耗时也不显示状态文案
    expect(html).toContain('1.5s')
    expect(html).toContain('25s')
    expect(html).not.toContain('排队中')
  })

  it('批内同名多调用不折叠(两个 read_file 两行,无「×N」聚合徽章)', () => {
    const html = renderMessage(items[0], items)
    expect((html.match(/nagent-tool-state--/g) ?? []).length).toBe(3)
    expect(html).not.toContain('×2')
    expect(html).not.toContain('nagent-tool-group')
  })

  it('无正文纯下发轮:批壳即该行全部内容,无占位文案/正文容器', () => {
    const html = renderMessage(items[0], items)
    expect(html).toContain('nagent-tool-batch__rows')
    expect(html).not.toContain('nagent-msg__content')
    expect(html).not.toContain('（无文本）')
    expect(html).not.toContain('无占位')
  })

  it('已定稿空消息(无正文/思考/工具调用)保持隐藏', () => {
    const empty = messageItem('10', {})
    expect(renderMessage(empty, [empty])).toBe('')
  })

  it('thinking 折叠头 + 正文渲染现状迁移(streaming 中思考展开)', () => {
    const thinking = messageItem('10', { content: '正文', thinking: '想一下', streaming: true })
    const html = renderMessage(thinking, [thinking])
    expect(html).toContain('nagent-msg__reasoning-content--streaming')
    expect(html).toContain('想一下')
    expect(html).toContain('nagent-msg__content')
  })

  it('formatToolDuration 紧凑耗时口径(一位小数/整秒/分钟补零/非法值)', () => {
    expect(formatToolDuration(0)).toBe('0.0s')
    expect(formatToolDuration(1500)).toBe('1.5s')
    expect(formatToolDuration(9900)).toBe('9.9s')
    expect(formatToolDuration(25000)).toBe('25s')
    expect(formatToolDuration(67800)).toBe('1m07s')
    expect(formatToolDuration(120000)).toBe('2m')
    expect(formatToolDuration(undefined)).toBe('')
    expect(formatToolDuration(-5)).toBe('')
  })
})

describe('message 视图运行态读秒与中断态', () => {
  it('tool.started 瞬态按 callId 直配点亮 running + 读秒(单调用直渲视图摘要行)', () => {
    const item = messageItem('10', { toolCalls: [callOf('c1', 'shell', '{"command":"ping"}')] })
    recordToolStarted(TASK_ID, { callId: 'c1', name: 'shell', arguments: '{}', startedAt: Date.now() - 400 })
    const html = renderMessage(item, [item])
    // 单调用不套批壳,直接渲染 per-tool 视图(DefaultToolView)
    expect(html).not.toContain('nagent-tool-batch')
    expect(html).toContain('nagent-tool--call')
    expect(html).toContain('nagent-tool-state--running')
    expect(html).toContain('nagent-tool__duration')
    // 运行中只显示时间(不显示「运行中」中文提示)
    expect(html).not.toContain('运行中')
    // 历史回放(无瞬态)→ queued,不误点亮
    clearTaskToolStarted(TASK_ID)
    const replay = renderMessage(item, [item])
    expect(replay).toContain('nagent-tool-state--queued')
    expect(replay).not.toContain('运行中')
  })

  it('批中断(任务级 cancelled 信号):已 started 标 interrupted 黄,未 started 标 skipped 灰显', () => {
    const item = messageItem('10', { toolCalls: [callOf('c1', 'shell', '{}'), callOf('c2', 'shell', '{}')] })
    recordToolStarted(TASK_ID, { callId: 'c1', name: 'shell', arguments: '{}', startedAt: Date.now() })
    const cancelled: ThreadItem = {
      seq: '30', ts: 1, agentId: '', kind: 'cancelled', payload: { title: '任务已取消' },
    }
    const html = renderMessage(item, [item, cancelled])
    expect(html).toContain('nagent-tool-state--interrupted')
    expect(html).toContain('nagent-tool-state--skipped')
    expect(html).toContain('nagent-tool-batch--interrupted')
    // 状态由图标颜色/动效承载,不再显示中文状态文案
    expect(html).not.toContain('执行中被中断')
    expect(html).not.toContain('未执行')
    expect(html).not.toContain('运行中')
    expect(html).not.toContain('排队中')
  })

  it('批内全部结果到齐后终态信号不影响完成态展示', () => {
    const item = messageItem('10', { toolCalls: [callOf('c1', 'shell', '{}')] })
    const result = toolResultItem('20', { callId: 'c1', content: 'ok', status: 'success', durationMs: 900 })
    const cancelled: ThreadItem = {
      seq: '30', ts: 1, agentId: '', kind: 'cancelled', payload: { title: '任务已取消' },
    }
    const html = renderMessage(item, [item, result, cancelled])
    expect(html).toContain('nagent-tool-state--done')
    expect(html).toContain('0.9s')
  })
})

describe('user.message 视图(回放胶囊段)', () => {
  const opaque = buildOpaqueTokenText('system.workspace_file', {
    label: 'a.md',
    payload: { path: 'a.md' },
  })

  it('buildUserMessageReplaySegments:rawContent 自包含切分出胶囊段(label 回放)', () => {
    const segments = buildUserMessageReplaySegments({
      content: '看下 a.md 谢谢',
      rawContent: `看下 ${opaque} 谢谢`,
    })
    expect(segments.map((s) => s.type)).toEqual(['text', 'token', 'text'])
    const token = segments[1]
    expect(token?.token?.kind).toBe('system.workspace_file')
    expect(token?.token?.label).toBe('a.md')
    expect(token?.value).toBe('a.md')
  })

  it('rawContent 缺失回退 content 纯文本段;空消息无段', () => {
    expect(buildUserMessageReplaySegments({ content: '纯文本' })).toEqual([{ type: 'text', value: '纯文本' }])
    expect(buildUserMessageReplaySegments({})).toEqual([])
  })

  it('渲染:胶囊 chip 类 + label;子 agent 输入低调样式', () => {
    const mainItem: ThreadItem = {
      seq: '10', ts: 1, agentId: MAIN, kind: 'user.message',
      payload: { content: '看下 a.md', rawContent: `看下 ${opaque}` },
    }
    const html = renderBoarded(
      React.createElement(UserMessageView, {
        item: mainItem,
        payload: mainItem.payload as never,
        taskId: TASK_ID,
        mainAgentId: MAIN,
      }),
      [mainItem],
    )
    expect(html).toContain('nagent-inline-chip--replay')
    expect(html).toContain('a.md')

    const subItem: ThreadItem = {
      ...mainItem,
      agentId: 'agent-sub-9',
    }
    const subHtml = renderBoarded(
      React.createElement(UserMessageView, {
        item: subItem,
        payload: subItem.payload as never,
        taskId: TASK_ID,
        mainAgentId: MAIN,
      }),
      [subItem],
    )
    expect(subHtml).toContain('nagent-msg__bubble--sub-agent')
  })
})

describe('tool.result 视图(吸收判定)', () => {
  it('被 message 下发块吸收 → 渲染为空(不重复出块)', () => {
    const message = messageItem('10', { toolCalls: [callOf('c1', 'shell', '{}')] })
    const result = toolResultItem('20', { callId: 'c1', content: 'ok', status: 'success' })
    const html = renderBoarded(
      React.createElement(ToolResultView, {
        item: result, payload: result.payload as never, taskId: TASK_ID, mainAgentId: MAIN,
      }),
      [message, result],
    )
    expect(html).toBe('')
  })

  it('孤儿(callId 无对应下发)→ 独立兜底条目不丢(工具名/结果入口可见)', () => {
    const orphan = toolResultItem('20', { callId: 'c-lost', content: '迟到结果', status: 'success', toolName: 'shell' })
    const html = renderBoarded(
      React.createElement(ToolResultView, {
        item: orphan, payload: orphan.payload as never, taskId: TASK_ID, mainAgentId: MAIN,
      }),
      [orphan],
    )
    expect(html).toContain('nagent-msg--tool')
    expect(html).toContain('shell')
  })
})

describe('error / cancelled 视图(kind 直配)', () => {
  it('error → run_error 错误色调(alert 图标 tone)', () => {
    initializeTraceTypes()
    const item: ThreadItem = {
      seq: '10', ts: 1, agentId: MAIN, kind: 'error',
      payload: { title: '任务出错', summary: 'boom', content: 'boom' },
    }
    const html = renderBoarded(
      React.createElement(ErrorView, { item, payload: item.payload as never, taskId: TASK_ID, mainAgentId: MAIN }),
      [item],
    )
    expect(html).toContain('nagent-trace-shell--error')
    expect(html).toContain('任务出错')
  })

  it('cancelled → system_notice 形态,静态摘要行(无 content 不可展开)', () => {
    initializeTraceTypes()
    const item: ThreadItem = {
      seq: '10', ts: 1, agentId: '', kind: 'cancelled',
      payload: { title: '任务已取消', summary: '由 用户 取消' },
    }
    const html = renderBoarded(
      React.createElement(CancelledView, { item, payload: item.payload as never, taskId: TASK_ID, mainAgentId: MAIN }),
      [item],
    )
    expect(html).toContain('nagent-trace-shell')
    expect(html).toContain('任务已取消')
    expect(html).toContain('nagent-trace-shell__summary--static')
  })
})

describe('TaskThread 分发切换(查表 + 兜底)', () => {
  it('items.map 查表分发:内置 kind 命中;未注册 trace 族落 FallbackView;被吸收 tool.result 不出块', () => {
    const userItem: ThreadItem = {
      seq: '10', ts: 1, agentId: MAIN, kind: 'user.message', payload: { content: '问题' },
    }
    const message = messageItem('20', { content: '我用工具查', toolCalls: [callOf('c1', 'shell', '{"command":"ls"}')] })
    const result = toolResultItem('30', { callId: 'c1', content: 'ok', status: 'success', durationMs: 1200 })
    const unknown: ThreadItem = {
      seq: '40', ts: 1, agentId: MAIN, kind: 'future.trace', payload: { title: '未来事件', content: '零丢失' },
    }
    const html = renderToString(
      React.createElement(TaskThread, {
        taskId: TASK_ID,
        items: [userItem, message, result, unknown],
        mainAgentId: MAIN,
      }),
    )
    // user.message 命中(user 气泡)
    expect(html).toContain('nagent-msg--user')
    // message 命中(单调用直渲 per-tool 视图,不套批壳) + 被吸收的 tool.result 不重复出块
    expect(html).not.toContain('nagent-tool-batch')
    expect(html).toContain('nagent-tool--call')
    expect(html).toContain('1.2s')
    // 未注册 kind → FallbackView(TaskTraceShell 形状,内容零丢失)
    expect(html).toContain('nagent-trace-shell')
    expect(html).toContain('未来事件')
    expect(html).toContain('零丢失')
    // key=seq 的分发不因同 kind 多条冲突(此处隐含:四条 items 全部渲染)
    expect(html).toContain('我用工具查')
  })

  it('items 为空且非生成中 → 空态;isGenerating 时不落空态(横幅由运行态驱动)', () => {
    expect(renderToString(React.createElement(TaskThread, { taskId: TASK_ID, items: [] })))
      .toContain('当前 Task 还没有消息或追踪记录')
    const generating = renderToString(
      React.createElement(TaskThread, { taskId: TASK_ID, items: [], isGenerating: true }),
    )
    expect(generating).not.toContain('当前 Task 还没有消息或追踪记录')
  })
})
