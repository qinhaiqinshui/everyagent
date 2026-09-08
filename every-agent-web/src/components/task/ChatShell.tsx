import React from 'react'
import WorkspacePageShell, { type WorkspacePageShellHandle } from '@/components/shared/WorkspacePageShell'

export interface ChatShellProps {
  /** 顶部头部区域。 */
  header?: React.ReactNode
  /** 统一线程区域（仅这一块随内容滚动）。 */
  thread: React.ReactNode
  /** 线程滚动容器引用。 */
  threadScrollRef?: React.Ref<HTMLDivElement>
  /** 外层页面壳引用，用于自动跟随滚动等控制。 */
  shellRef?: React.Ref<WorkspacePageShellHandle>
  /** 底部输入区（固定不随页面滚动）。 */
  composer: React.ReactNode
  /** 是否启用自动跟随到底部。 */
  enableAutoFollow?: boolean
  /** 线程区悬浮层（如「滚动到底部」按钮），absolute 定位在滚动容器上。 */
  overlay?: React.ReactNode
}

/**
 * Task 聊天展示壳。
 * 只负责组织头部、线程和输入区，不读取业务数据。
 *
 * 布局约定：顶部 header 由 WorkspacePageShell 固定，
 * 中间 thread 在一个独立的可滚动容器内，底部 composer 固定在最下方。
 */
export default function ChatShell({
  header,
  thread,
  threadScrollRef,
  shellRef,
  composer,
  enableAutoFollow = false,
  overlay,
}: ChatShellProps) {
  return (
    <WorkspacePageShell
      header={header}
      bodyPadding={0}
      shellStyle={shellStyle}
      bodyStyle={bodyStyle}
      bodyInnerStyle={bodyInnerStyle}
      shellRef={shellRef}
      enableAutoFollow={enableAutoFollow}
    >
      <div className="nagent-chat">
        <div className="nagent-chat__thread-wrap">
          <div ref={threadScrollRef} className="nagent-chat__scroll">
            {thread}
          </div>
          {overlay}
        </div>
        <div className="nagent-chat__composer">
          {composer}
        </div>
      </div>
    </WorkspacePageShell>
  )
}

const shellStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
  background: 'var(--bg-primary)',
}

const bodyStyle: React.CSSProperties = {
  overflow: 'hidden',
  background: 'var(--bg-primary)',
}

const bodyInnerStyle: React.CSSProperties = {
  flex: 1,
  height: '100%',
  minHeight: 0,
  minWidth: 0,
  overflow: 'hidden',
}
