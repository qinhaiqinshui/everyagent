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
 * 定位文件实际所属工作区（AI 可能经授权操作了工作区外的文件），找到后打开；
 * 探测未命中且为工作区外绝对路径时,弹授权确认框(§7.17「用户显式选择=已授权」)
 * 将其所在目录注册为该工作区外部授权根后打开——AI 本任务的在途授权根会随任务收口
 * evict 失效,工作区级外部授权根则长期有效,使文件标签页此后始终可读可写;
 * 相对路径未命中才 toast。
 */

import React from 'react'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { useAppUi } from '@/components/app/AppUiContext'
import { toBusinessAbsolutePath, isAbsoluteBusinessPath } from '@/platform/fs/pathUtils'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { antdConfirm } from '@/utils/appAntdBridge'
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
   * 中所有工作区根，用 stat 逐个探测，返回第一个能找到文件的工作区根。
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
      const stat = await workspaceGateway.stat(root, businessPath).catch(() => null)
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
    // 探测未命中且为工作区外绝对路径:授权其所在目录为该工作区外部授权根(§7.17,
    // 用户显式确认=已授权)后打开。AI 本任务的在途授权根会在任务收口 evict 失效,
    // 故此处注册工作区级授权根,使文件标签页此后始终可读可写。
    if (isAbsoluteBusinessPath(businessPath) && fallbackWorkspaceRoot) {
      const workerId = workspaceRegistry.workerIdOfRoot(fallbackWorkspaceRoot)
      if (!workerId) {
        showToast('无法确定该工作区所属 worker(工作区未注册或 worker 离线)', 'error')
        return
      }
      const authorized = await confirmExternalAuthorization(businessPath)
      if (!authorized) return
      try {
        await workspaceRegistry.addExternalRoot(workerId, fallbackWorkspaceRoot, fullPath || businessPath)
      } catch (grantError) {
        showToast(`授权失败：${grantError instanceof Error ? grantError.message : String(grantError)}`, 'error')
        return
      }
      openGlobalFileTab({ workspaceRoot: fallbackWorkspaceRoot, filePath: businessPath }, { mode: 'readwrite' })
      return
    }
    showToast(`未能在已注册工作区中找到该文件：${businessPath}`, 'error')
  }, [businessPath, fullPath, fallbackWorkspaceRoot, openGlobalFileTab, resolveFileWorkspace, showToast])

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
              {businessPath ? (
                canOpen ? (
                  <button
                    type="button"
                    className="nagent-tool__detail-path"
                    title={`打开文件：${businessPath}`}
                    onClick={(e) => {
                      e.stopPropagation()
                      handleOpenFile()
                    }}
                  >
                    {businessPath}
                  </button>
                ) : (
                  <span className="nagent-tool__detail-path nagent-tool__detail-path--static" title="未关联工作区，无法打开">{businessPath}</span>
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

/**
 * 工作区外文件打开前的授权确认(antd Modal.confirm 的 Promise 封装):
 * 确认 = 显式授权该文件所在目录为工作区外部授权根(§7.17,完全读写,工作区级长期有效);
 * 取消 = 不打开。antdConfirm 无 Promise 返回值,此处以 onOk/onCancel 回传布尔结论。
 */
function confirmExternalAuthorization(businessPath: string): Promise<boolean> {
  const normalized = businessPath.replace(/\\/g, '/').replace(/^\/+/, '')
  const idx = normalized.lastIndexOf('/')
  const parentDir = idx > 0 ? normalized.slice(0, idx) : normalized
  return new Promise((resolve) => {
    antdConfirm({
      title: '打开工作区外文件',
      content: (
        <div style={{ whiteSpace: 'pre-wrap' }}>
          该文件位于工作区之外。授权后将允许读写其所在目录（工作区级，长期有效）：{'\n'}
          {parentDir}
          {'\n\n'}是否授权并打开？
        </div>
      ),
      okText: '授权并打开',
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
}
