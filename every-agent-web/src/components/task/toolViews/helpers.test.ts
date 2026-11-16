/**
 * 工具开始时间点(展开态「开始于」元信息行)的单测。
 *
 * 覆盖:formatToolStartTime/formatToolStartFullTime 的本地时区 24 小时制手动拼接
 * (夹具用 new Date(年,月,日,时,分,秒) 本地构造,任意时区下断言恒定)、
 * 非法/缺失值返回空串,以及 ToolStartMeta 纯展示组件的渲染(renderToString,
 * node 环境口径与 builtinViews.test.ts 一致):有值渲染时钟文本 + 悬停完整时间,
 * 缺失渲染 null(老事件不占位)。
 */
import { describe, expect, it } from 'vitest'
import React from 'react'
import { renderToString } from 'react-dom/server'
import { formatToolStartFullTime, formatToolStartTime } from './helpers'
import { ToolStartMeta } from './ToolStartMeta'

describe('formatToolStartTime / formatToolStartFullTime(时间点格式化)', () => {
  // 本地构造(时/分/秒补零路径都走到):任意执行时区下断言恒定。
  const ts = new Date(2025, 0, 2, 3, 4, 5, 678).getTime()

  it('24 小时制时钟文本 HH:mm:ss(手动拼接,无本地化差异);完整文本补年月日', () => {
    expect(formatToolStartTime(ts)).toBe('03:04:05')
    expect(formatToolStartFullTime(ts)).toBe('2025-01-02 03:04:05')
  })

  it('两位数时分秒不补零;跨天日期各段补零', () => {
    const ts2 = new Date(2025, 10, 20, 13, 45, 9).getTime()
    expect(formatToolStartTime(ts2)).toBe('13:45:09')
    expect(formatToolStartFullTime(ts2)).toBe('2025-11-20 13:45:09')
  })

  it('非法/缺失/负值返回空串(视图据此不渲染时间点行)', () => {
    expect(formatToolStartTime(undefined)).toBe('')
    expect(formatToolStartTime(Number.NaN)).toBe('')
    expect(formatToolStartTime(Number.POSITIVE_INFINITY)).toBe('')
    expect(formatToolStartTime(-1)).toBe('')
    expect(formatToolStartFullTime(undefined)).toBe('')
    expect(formatToolStartFullTime(-1)).toBe('')
  })
})

describe('ToolStartMeta(展开态时间点元信息行)', () => {
  const ts = new Date(2025, 0, 2, 3, 4, 5).getTime()

  it('有 startedAt:渲染「开始于」+ 时钟文本,悬停 title 为完整年月日', () => {
    const html = renderToString(React.createElement(ToolStartMeta, { startedAt: ts }))
    expect(html).toContain('nagent-tool__meta')
    expect(html).toContain('开始于')
    expect(html).toContain('03:04:05')
    expect(html).toContain('title="2025-01-02 03:04:05"')
  })

  it('startedAt 缺失/非法:渲染 null(老事件不渲染,不占位)', () => {
    expect(renderToString(React.createElement(ToolStartMeta, { startedAt: undefined }))).toBe('')
    expect(renderToString(React.createElement(ToolStartMeta, { startedAt: Number.NaN }))).toBe('')
  })
})
