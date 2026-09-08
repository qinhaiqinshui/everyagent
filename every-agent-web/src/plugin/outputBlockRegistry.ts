// 输出块渲染注册表(自 n 分支同形迁移)。
//
// n 里 <plan> 等结构化输出标签由浏览器插件注册渲染扩展点;本前端的插件
// 装载已下沉 worker,worker agent 输出以 markdown 为主——注册表保持空,
// RichMessageContent 对未注册标签走纯文本降级渲染。

import type { ReactNode } from 'react'

export interface OutputBlockContext {
  taskId?: string
  messageId?: string
}

export type OutputBlockHandler = (
  content: string,
  context: OutputBlockContext,
) => ReactNode

const handlers = new Map<string, OutputBlockHandler>()

export const outputBlockRegistry = {
  register(tag: string, handler: OutputBlockHandler): void {
    const normalizedTag = tag.trim().toLowerCase()
    if (!normalizedTag) {
      throw new Error('输出块标签不能为空')
    }
    if (handlers.has(normalizedTag)) {
      throw new Error(`重复注册的输出块标签：${normalizedTag}`)
    }
    handlers.set(normalizedTag, handler)
  },
  get(tag: string): OutputBlockHandler | undefined {
    return handlers.get(tag.toLowerCase())
  },
  clear(): void {
    handlers.clear()
  },
}
