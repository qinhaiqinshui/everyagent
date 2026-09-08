import React from 'react'
import { createRoot, type Root } from 'react-dom/client'
import type { ChatComposerToken } from '@/types'
import type { SlashCommandItem } from '@/slash/slashCommandRegistry'

/**
 * 胶囊详情弹层（纯展示）。
 *
 * 设计约束（方案 §4.11）：
 * - 只渲染 `icon` / `title` / `subtitle` / `group`（live 项有全部字段；已发送 token 仅 `label + kind`）。
 * - **不挂任何类型专属动作**：无「打开文件」按钮，不 import 任何文件/技能 API，不按类型分支。slash 层对文件零感知。
 * - 浮层遵循项目浮层约定：portal 到 `document.body` + 内联 `position: fixed`，
 *   不把 `position` 写进 `.ui-menu--popup`；点击外部 / Esc 关闭。
 */

/** 弹层入参：直接收 token（草稿点击经 data-token-id 取得；回放点击直接传 token）。 */
export interface SlashItemDetailInfo {
  /** 被点击的 token（自包含：label/kind 来自 opaque 顶层）。 */
  token: ChatComposerToken
  /** 可选 live 项：提供 icon/subtitle/group 完整字段（草稿实时候选场景）。 */
  item?: SlashCommandItem
}

let portalContainer: HTMLDivElement | null = null
let portalRoot: Root | null = null
let currentCloser: (() => void) | null = null

/** 取得（或创建）挂到 body 的 portal 根。 */
function ensurePortal(): Root {
  if (!portalRoot) {
    portalContainer = document.createElement('div')
    portalContainer.className = 'nagent-slash-detail-root'
    document.body.appendChild(portalContainer)
    portalRoot = createRoot(portalContainer)
  }
  return portalRoot
}

interface DetailCardProps {
  info: SlashItemDetailInfo
  anchorRect: DOMRect
  onClose: () => void
}

function DetailCard({ info, anchorRect, onClose }: DetailCardProps): React.ReactElement {
  const { item, token } = info
  // 浮层定位：跟随触发元素下方，避免超出右边界（最小宽度兜底）。
  const left = Math.max(8, Math.min(anchorRect.left, window.innerWidth - 288))
  const top = anchorRect.bottom + 6
  const surfaceStyle: React.CSSProperties = {
    position: 'fixed',
    left,
    top,
    zIndex: 1000,
    maxWidth: 280,
  }
  return (
    <div
      className="ui-menu ui-menu--popup nagent-slash-detail"
      style={surfaceStyle}
      role="dialog"
      aria-label="条目详情"
      onClick={(event) => event.stopPropagation()}
    >
      {item?.icon ? (
        <div
          className="nagent-slash-detail__icon"
          // 来源为注册中心内部可信 SVG（skill / 插件），非用户输入。
          dangerouslySetInnerHTML={{ __html: item.icon }}
        />
      ) : null}
      <div className="nagent-slash-detail__title">{item?.title ?? token.label}</div>
      {item?.subtitle ? (
        <div className="nagent-slash-detail__subtitle">{item.subtitle}</div>
      ) : null}
      {item?.group ? (
        <div className="nagent-slash-detail__group">{item.group}</div>
      ) : null}
      {!item ? (
        <div className="nagent-slash-detail__type">类型：{token.kind}</div>
      ) : null}
      <button type="button" className="nagent-slash-detail__close" onClick={onClose} aria-label="关闭">
        ✕
      </button>
    </div>
  )
}

/** 在触发元素附近打开详情弹层（纯展示）。重复调用会先关闭上一个。 */
export function openSlashItemDetail(anchor: HTMLElement, info: SlashItemDetailInfo): void {
  currentCloser?.()
  const root = ensurePortal()
  const anchorRect = anchor.getBoundingClientRect()
  const close = () => {
    currentCloser = null
    if (portalRoot) portalRoot.render(null)
    document.removeEventListener('mousedown', onDocDown, true)
    document.removeEventListener('keydown', onKeyDown, true)
  }
  const onDocDown = (event: MouseEvent) => {
    const target = event.target as Node
    if (portalContainer && portalContainer.contains(target)) return
    if (anchor.contains(target)) return
    close()
  }
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape') close()
  }
  currentCloser = close
  document.addEventListener('mousedown', onDocDown, true)
  document.addEventListener('keydown', onKeyDown, true)
  root.render(<DetailCard info={info} anchorRect={anchorRect} onClose={close} />)
}
