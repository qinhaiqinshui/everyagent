/**
 * 文件类工具（create_file / update_file / read_file）共享的 entry 渲染组件。
 *
 * 三个工具的折叠态仅附加参数文本不同（read_file 多带 line_start/line_end 等参数），
 * 展开态结构完全一致，故抽出共享组件：
 *
 * 折叠态：工具图标 + 工具名 + inline-preview（文件名 + 可选附加文本 + 间距 + 完整路径；
 * 文件名由 args.path 提取，附加文本由调用方计算，如 read_file 的 [1-80] 行号区间）+ 折叠箭头。
 * 展开态：result-item-head（图标 + 工具名 + 可点击文件路径 chip）→ 参数块（content 排末尾）
 *         → 结果块（read_file 即文件内容；create/update 为确认文本）→ 错误块。
 *
 * 展开态工具名后的文件路径为可点击 chip，点击经 useWorkspaceShell().openGlobalFileTab
 * 打开文件标签页（readwrite 模式，方便用户直接改 AI 写的文件）；workspaceRoot 缺失时
 * 降级为纯文本不可点，避免历史/未关联工作区场景报错。点击时遍历注册表工作区根
 * 用 statRaw 探测文件实际所属工作区（不经沙箱，用户操作），找到后打开；
 * 探测未命中且为工作区外绝对路径时也以读写模式打开（文件标签页读取统一走
 * fs.readRaw 不经沙箱，用户操作非 AI 工具，直接按机器绝对路径读盘）;相对路径未命中才 toast。
 *
 * 路径**展示**一律用工具参数里的原始 `args.path`（与折叠态同一份文本：工作区相对路径
 * 就是相对路径、盘符路径就是盘符路径），不得把 businessPath 当展示文本——businessPath
 * 是统一加前导 `/` 的内部坐标（工作区外绝对路径会变成 `/C:/Users/...`），只用于
 * 网关探测与 openGlobalFileTab，直接展示会凭空空出一个 `/` 前缀。
 */

import React from 'react'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { useAppUi } from '@/components/app/AppUiContext'
import { toBusinessAbsolutePath } from '@/platform/fs/pathUtils'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { useTaskWorkspaceRoot } from '../TaskWorkspaceContext'
import { extractFileName, hasActiveTextSelection } from './helpers'
import type { AggregatedToolDetail } from './types'

/** content 字段始终排在参数列表最后，避免开头被巨量正文占据。 */
const LAST_ARGS = new Set(['content'])

function formatPrimitive(v: unknown): string {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return JSON.stringify(v)
}

/** 把参数展平为 `k: v` 文本行；content 等巨量字段排末尾。 */
function flattenArgs(args: Record<string, unknown> | null): string[] {
  if (!args) return []
  const tail: string[] = []
  const lines: string[] = []
  for (const [k, v] of Object.entries(args)) {
    const line = v !== null && typeof v === 'object'
      ? `${k}: ${JSON.stringify(v)}`
      : `${k}: ${formatPrimitive(v)}`
    if (LAST_ARGS.has(k)) {
      tail.push(line)
    } else {
      lines.push(line)
    }
  }
  return [...lines, ...tail]
}

export interface FileToolEntryProps {
  detail: AggregatedToolDetail
  /** 折叠行附加文本（如 read_file 的 [1-80] 行号区间），渲染在文件名与完整路径之间。 */
  inlineExtras?: string
}

export function FileToolEntry({ detail, inlineExtras }: FileToolEntryProps) {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  // fullPath = 工具参数原样路径,既作展示文本(折叠态/展开态 chip 同源),也作探测入参;
  // businessPath = 统一加前导 `/` 的内部坐标(仅网关探测与 openGlobalFileTab 使用,不展示)。
  const fullPath = typeof args.path === 'string' ? args.path : ''
  const businessPath = fullPath ? toBusinessAbsolutePath(fullPath) : ''

  const taskWorkspaceRoot = useTaskWorkspaceRoot()
  // 任务工作区根缺失时回退到注册表首项（与 FileDiffPanel.handleOpenFileInTab 同口径）。
  const fallbackWorkspaceRoot = taskWorkspaceRoot ?? workspaceRegistry.primaryRoot() ?? ''
  const { openGlobalFileTab } = useWorkspaceShell()
  const { showToast } = useAppUi()

  const hasError = detail.status === 'error'
  const result = detail.result
  const resultText = typeof result === 'string' ? result : JSON.stringify(result, null, 2)

  const argLines = flattenArgs(args)
  const [open, setOpen] = React.useState(false)

  /**
   * 查找文件实际所属的工作区根。任务工作区根不一定包含该文件（AI 可能经授权
   * 操作了工作区外的文件，或任务工作区根与文件实际位置不一致）。遍历注册表
   * 中所有工作区根，用 statRaw 逐个探测（不经沙箱，用户操作），返回第一个能找到文件的工作区根。
   */
  const resolveFileWorkspace = React.useCallback(async (): Promise<string | null> => {
    const candidates: string[] = []
    if (fallbackWorkspaceRoot) candidates.push(fallbackWorkspaceRoot)
    // 补充注册表中其他工作区根（去重，fallbackWorkspaceRoot 已排首位优先尝试）。
    for (const entry of workspaceRegistry.current?.workspaces ?? []) {
      if (entry.root && !candidates.includes(entry.root)) {
        candidates.push(entry.root)
      }
    }
    for (const root of candidates) {
      const stat = await workspaceGateway.statRaw(root, businessPath).catch(() => null)
      if (stat && !stat.isDirectory) {
        return root
      }
    }
    return null
  }, [businessPath, fallbackWorkspaceRoot])

  const handleOpenFile = React.useCallback(async () => {
    if (!businessPath || !openGlobalFileTab) return
    const root = await resolveFileWorkspace()
    if (root) {
      openGlobalFileTab({ workspaceRoot: root, filePath: businessPath }, { mode: 'readwrite' })
      return
    }
    // 探测未命中:文件标签页读取统一走 fs.readRaw(不经沙箱,用户操作),
    // 外部绝对路径也能打开;相对路径未命中(工作区内文件不存在)才 toast。
    if (fallbackWorkspaceRoot) {
      openGlobalFileTab({ workspaceRoot: fallbackWorkspaceRoot, filePath: businessPath }, { mode: 'readwrite' })
      return
    }
    showToast(`未能在已注册工作区中找到该文件：${fullPath}`, 'error')
  }, [businessPath, fallbackWorkspaceRoot, fullPath, openGlobalFileTab, resolveFileWorkspace, showToast])

  const canOpen = Boolean(businessPath && fallbackWorkspaceRoot && openGlobalFileTab)

  return (
    <div className={`nagent-tool nagent-tool--filewrite${open ? ' is-open' : ''}`}>
      <button
        type="button"
        className="nagent-tool__summary"
        aria-expanded={open}
        onClick={() => {
          // 拖选参数文本（选区非空）时不切换折叠，保证参数可选中复制。
          if (hasActiveTextSelection()) return
          setOpen((value) => !value)
        }}
      >
        <WrenchIcon size={13} className={`nagent-tool__icon${hasError ? ' nagent-tool__icon--error' : ''}`} />
        <span className="nagent-tool__name">{detail.toolName || '文件工具'}</span>
        <span className="nagent-tool__inline-preview">
          {fullPath ? (
            <>
              <span className="nagent-tool__inline-file-name">{extractFileName(fullPath)}</span>
              {inlineExtras ? <span className="nagent-tool__inline-file-lines">{inlineExtras}</span> : null}
              <span className="nagent-tool__inline-file-path">{fullPath}</span>
            </>
          ) : (
            '（无路径）'
          )}
        </span>
        {open ? (
          <ChevronDownIcon size={13} className="nagent-tool__chevron" />
        ) : (
          <ChevronRightIcon size={13} className="nagent-tool__chevron" />
        )}
      </button>
      {open ? (
        <div className="nagent-tool__detail">
          <div className="nagent-tool__result-item">
            <div className="nagent-tool__result-item-head">
              <WrenchIcon size={12} className={`nagent-tool__icon${hasError ? ' nagent-tool__icon--error' : ''}`} />
              <span className="nagent-tool__name">{detail.toolName || '文件工具'}</span>
              {fullPath ? (
                canOpen ? (
                  <button
                    type="button"
                    className="nagent-tool__detail-path"
                    title={`打开文件：${fullPath}`}
                    onClick={(e) => {
                      e.stopPropagation()
                      handleOpenFile()
                    }}
                  >
                    {fullPath}
                  </button>
                ) : (
                  <span className="nagent-tool__detail-path nagent-tool__detail-path--static" title="未关联工作区，无法打开">{fullPath}</span>
                )
              ) : null}
            </div>
            {argLines.length ? (
              <div className="nagent-tool__result-block">
                <div className="nagent-tool__result-head">
                  <span className="nagent-tool__result-title">参数</span>
                </div>
                <pre className="nagent-tool__result-text">{argLines.join('\n')}</pre>
              </div>
            ) : null}
            {resultText.trim() && !hasError ? (
              <div className="nagent-tool__result-block">
                <pre className="nagent-tool__result-text">{resultText}</pre>
              </div>
            ) : null}
            {hasError ? (
              <div className="nagent-tool__result-block">
                <div className="nagent-tool__result-head">
                  <span className="nagent-tool__result-title">错误输出</span>
                </div>
                <pre className="nagent-tool__result-text">{resultText.trim() ? resultText : '（错误）'}</pre>
              </div>
            ) : null}
            {!resultText.trim() && !hasError ? (
              <div className="nagent-tool__result-block">
                <pre className="nagent-tool__result-text">（无输出）</pre>
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  )
}
