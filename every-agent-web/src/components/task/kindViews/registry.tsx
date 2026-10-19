/**
 * kind 渲染注册表(「任务线程统一条目与画板纯化」步骤 9 基建/步骤 10 分发切换)。
 *
 * 渲染层按 item.kind 查表分发:分发点(TaskThread)显式写 `getKindView(item.kind)
 * ?? FallbackView`——与 traceTypeRegistry「查不到给 fallback 定义」不同,本注册表查表
 * 未注册返回 **undefined**,兜底选择是分发语义,不埋进注册表。内置 kind 视图经
 * `./builtins` 集中注册(注册即 KindPayloadMap 编译期配对),trace 族不注册专视图
 * 自然落 FallbackView。
 *
 * KindViewProps 保持最小集(item/payload/taskId/mainAgentId):不含 items、不含
 * toolJoin 回调——视图需要兄弟行(tool.result 吸收判定/结果配对)一律经
 * useThreadBoard() context(./threadBoardContext)取,工具配对归渲染层 tool 域
 * 共享模块(./toolDomain),核心与画板零知识。
 */
import React from 'react'
import type { KindPayloadMap, ThreadItem } from '@/task/threadItem'
import { stringifyTraceContent } from '@/plugin/traceTypeRegistry'
import { TaskTraceShell } from './TraceShell'

// ─── 视图契约(KindViewProps 最小集) ──────────────────────────────────────────

/**
 * kind 视图属性(最小集)。
 * `payload` 随注册 kind 类型化:注册 kind 是 KindPayloadMap 已声明键时取对应
 * payload 类型(handler 写形状与视图读形状编译期配对),未知 kind 为 unknown。
 */
export interface KindViewProps<K extends string = string> {
  /** 被渲染的线程条目(五字段 ThreadItem,seq 即 id)。 */
  item: ThreadItem
  /** item.payload(按注册 kind 类型化,见 KindPayloadMap 声明合并接口)。 */
  payload: K extends keyof KindPayloadMap ? KindPayloadMap[K] : unknown
  /** 当前 Task ID(用户消息动作等扩展点用)。 */
  taskId: string
  /** 主 agent 稳定 Id(agentId 归一消费方口径 `item.agentId || mainAgentId`)。 */
  mainAgentId: string
}

/** kind 视图组件。 */
export type KindView<K extends string = string> = React.ComponentType<KindViewProps<K>>

// ─── 注册表(查表未注册返回 undefined;重复注册覆盖;空 kind 抛错) ───────────────

const kindViews = new Map<string, KindView>()

/** 注册 kind 视图。重复注册同 kind 会覆盖。空 kind 抛错(注册即编码错误的显性暴露)。 */
export function registerKindView<K extends string>(kind: K, View: KindView<K>): void {
  if (!kind.trim()) {
    throw new Error('kind 渲染视图 kind 不能为空')
  }
  kindViews.set(kind, View as KindView)
}

/** 按 kind 查视图。未注册返回 undefined(分发点显式 `?? FallbackView` 兜底)。 */
export function getKindView(kind: string): KindView | undefined {
  return kindViews.get(kind)
}

/** 按 kind 注销视图(测试/插件 dispose 侧路清理用);返回是否确实移除。 */
export function unregisterKindView(kind: string): boolean {
  if (!kind.trim()) {
    return false
  }
  return kindViews.delete(kind)
}

// ─── FallbackView(TaskTraceShell 形状兜底,内容零丢失) ─────────────────────────

/** FallbackView 宽松读取归一后的 trace 形状。 */
export interface FallbackTraceShape {
  title: string
  summary?: string
  content?: string
}

/** 判断 payload 对象是否尚有 title/summary/content 之外的字段(宽松读取未展示的信息)。 */
function hasHiddenFields(record: Record<string, unknown>): boolean {
  for (const key of Object.keys(record)) {
    if (key !== 'title' && key !== 'summary' && key !== 'content') {
      return true
    }
  }
  return false
}

/**
 * 从 item + payload 宽松读取 {title, summary, content}:
 * - payload 为对象:title/summary/content 直读(payload.title 缺省回退 item.kind
 *   作标题);content 为空而对象尚有未展示字段(如 trace 族 data)时整体 stringify
 *   兜底为 content——**内容零丢失**(仅 title/summary 的对象不重复 stringify,
 *   已展示字段不算丢失);
 * - payload 非对象(字符串/数字/数组等):整体 stringify 为 content;
 * - payload 为 null/undefined:无 content。
 * 导出供 FallbackView 内部消费与单测直测(纯函数)。
 */
export function readFallbackTraceShape(item: ThreadItem, payload: unknown): FallbackTraceShape {
  if (payload !== null && typeof payload === 'object' && !Array.isArray(payload)) {
    const record = payload as Record<string, unknown>
    const title =
      typeof record.title === 'string' && record.title.trim() ? record.title : item.kind
    const summary =
      typeof record.summary === 'string' && record.summary.trim() ? record.summary : undefined
    let content: string | undefined
    const rawContent = record.content
    if (typeof rawContent === 'string') {
      content = rawContent.trim() ? rawContent : undefined
    } else if (rawContent != null) {
      content = stringifyTraceContent(rawContent)
    }
    if (content === undefined && hasHiddenFields(record)) {
      content = stringifyTraceContent(record)
    }
    return { title, summary, content }
  }
  return {
    title: item.kind,
    summary: undefined,
    content: payload == null ? undefined : stringifyTraceContent(payload),
  }
}

/**
 * FallbackView:未注册 kind 的兜底视图(TaskTraceShell 形状:title/summary/content/pre)。
 * 呈现复用 TaskTraceShell——以宽松读取结果合成兼容 ThreadItem(payload 替换为归一
 * trace 形状;item.seq/ts/agentId/kind 原样保留,error/cancelled 错误色调等旧口径
 * 由 TraceShell 内部映射保持),两层均未注册的新 kind 自动可见、内容零丢失。
 */
export function FallbackView({ item, payload, taskId }: KindViewProps) {
  const shape = readFallbackTraceShape(item, payload)
  const normalized: ThreadItem = {
    ...item,
    payload: {
      title: shape.title,
      ...(shape.summary !== undefined ? { summary: shape.summary } : {}),
      ...(shape.content !== undefined ? { content: shape.content } : {}),
    },
  }
  return <TaskTraceShell item={normalized} taskId={taskId} />
}
