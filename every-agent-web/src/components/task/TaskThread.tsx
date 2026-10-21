import React, { useEffect, useRef, useState } from 'react'
import type { ThreadItem } from '@/task/threadItem'
import { BrandMark } from '@/components/shared/BrandLoadingBlock'
// 内置 kind 视图注册(模块级副作用,与 eventRegistry 同款范式);分发点见下方 items.map。
import './kindViews/builtins'
import { FallbackView, getKindView } from './kindViews/registry'
import { ThreadBoardProvider } from './kindViews/threadBoardContext'

/**
 * Task 线程组件属性。
 */
export interface TaskThreadProps {
  /** 当前 Task ID。 */
  taskId: string
  /** 已按 seq 升序排好的线程项(五字段 ThreadItem)。 */
  items: ThreadItem[]
  /** 当前是否处于加载中。 */
  loading?: boolean
  /** 主 agent 稳定 Id（渲染层消费 agentId 区分子 agent 输入；透传给各 kind 视图）。 */
  mainAgentId?: string
  /**
   * 本轮生成是否在飞（含点击发送 → 首 token 前空窗 → 流式输出 → 工具执行中 → 收口）。
   * 驱动线程底部的任务状态横幅（TaskThread.ThreadStatusBanner）：任务运行中显示、随消息被向下顶、
   * 文案从一组短语随机轮播；同时用于生成窗口内尚无任何线程项时不落入空态。
   */
  isGenerating?: boolean
  /** 线程项之前渲染（懒加载占位：运行中尾轮的 backward sentinel 等）。 */
  topSlot?: React.ReactNode
  /** 线程项之后渲染（懒加载占位：闭合轮/终态尾轮的 forward sentinel 等）。 */
  bottomSlot?: React.ReactNode
}

/**
 * Task 聊天线程。
 * 步骤 10 定稿:**纯查表分发**——items.map 逐条 `getKindView(item.kind) ?? FallbackView`,
 * key = item.seq(seq 即 id);外层包 ThreadBoardProvider(value = 本次渲染的 items 切片,
 * 供视图经 useThreadBoard() 做兄弟行访问:tool.result 吸收判定/结果配对/终态信号)。
 * 旧 renderMessageGroups 分组渲染(role 分组包裹/连续 tool 合并/buildToolMaps join 索引)
 * 已全部删除,工具配对归 kindViews/toolDomain 渲染层共享模块。
 */
export default function TaskThread({
  taskId,
  items,
  loading = false,
  mainAgentId = '',
  isGenerating = false,
  topSlot,
  bottomSlot,
}: TaskThreadProps) {
  if (loading) {
    return <div className="nagent-empty">正在线程加载中...</div>
  }
  if (items.length === 0 && !isGenerating) {
    return <div className="nagent-empty">当前 Task 还没有消息或追踪记录</div>
  }
  return (
    <div className="nagent-thread">
      {topSlot}
      <ThreadBoardProvider items={items}>
        {items.map((item) => {
          const View = getKindView(item.kind) ?? FallbackView
          // 分发点 kind 动态 → payload 直传 item.payload(类型化取载荷 payloadAs 归
          // 各视图内部;KindPayloadMap 编译期配对发生在注册点,见 kindViews/builtins)。
          return (
            <View
              key={item.seq}
              item={item}
              payload={item.payload}
              taskId={taskId}
              mainAgentId={mainAgentId}
            />
          )
        })}
      </ThreadBoardProvider>
      {bottomSlot}
      <ThreadStatusBanner running={isGenerating} />
    </div>
  )
}

/** 任务运行中的状态横幅。 */
const TASK_STATUS_PHRASES = [
  '推演天机…',
  '运转周天，灵气翻涌…',
  '祭出法宝！',
  '参悟大道，剑意纵横…',
  '撩拨圣女，面红耳赤…',
  '释放大招，山河变色！',
  '镇压诸天',
  '逆天改命',
  '斩妖除魔中…',
  '炼制九转金丹…',
  '布下诛仙大阵！',
  '沟通天地，雷劫将至…',
  '稳固境界，破境在即…',
  '一剑西来，天外飞仙斩尽宵小…',
  '脚踏星河，手摘日月，威压八荒！',
  '凝练神识，元神出窍…',
]

function ThreadStatusBanner({ running }: { running: boolean }) {
  const [phraseIdx, setPhraseIdx] = useState(0)
  const lastIdxRef = useRef(-1)

  useEffect(() => {
    if (!running) return
    let timer: number
    const pickNext = () => {
      let next = lastIdxRef.current
      while (next === lastIdxRef.current && TASK_STATUS_PHRASES.length > 1) {
        next = Math.floor(Math.random() * TASK_STATUS_PHRASES.length)
      }
      lastIdxRef.current = next
      setPhraseIdx(next)
      const delay = 5000 + Math.floor(Math.random() * 5000)
      timer = window.setTimeout(pickNext, delay)
    }
    pickNext()
    return () => window.clearTimeout(timer)
  }, [running])

  if (!running) return null

  return (
    <div className="nagent-thread__status" role="status" aria-live="polite">
      <span className="nagent-thread__status-logo" aria-hidden="true">
        <BrandMark size={12} animated={false} />
      </span>
      <span className="nagent-thread__status-text" data-text={TASK_STATUS_PHRASES[phraseIdx]}>
        {TASK_STATUS_PHRASES[phraseIdx]}
      </span>
    </div>
  )
}
