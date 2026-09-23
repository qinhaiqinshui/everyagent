/**
 * update_file 工具调用的完整视图接管（update-file-view 插件核心组件）。
 *
 * 经 `ui.tool_call_views` 扩展点注册，命中 update_file 即整体接管渲染
 * （与核心内置 toolViews 同地位，优先级高于内置注册表）：
 *
 * 折叠态：工具图标 + 工具名 + 文件名 + 变更统计徽章（[+a/-r]）+ 完整路径 + 折叠箭头
 *         ——折叠时无需展开即可看到改了什么文件、变更量多大。
 * 展开态：头部（图标 + 工具名 + 可点击路径 chip）→ 内嵌 diff（oldcontent → content
 *         行级对比，上下文折叠 +12/-3 着色）→ 结果块（确认文本）→ 错误块。
 *
 * 参数缺失（oldcontent/content 非字符串）时回退为参数块渲染（与核心
 * DefaultToolView 同构的兜底），保证异常/历史调用可诊断。
 *
 * 结构与 FileToolEntry 同构（复用 chatPanel.css 的 nagent-tool__* 类与
 * helpers 的选区守卫/文件名提取），独立演进不反向侵入核心。
 */

import React from 'react'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { toBusinessAbsolutePath } from '@/platform/fs/pathUtils'
import { useTaskWorkspaceRoot } from '@/components/task/TaskWorkspaceContext'
import { extractFileName, hasActiveTextSelection } from '@/components/task/toolViews/helpers'
import { buildLineDiff } from '@/utils/textDiff'
import { UpdateFileDiff } from './UpdateFileDiff'

/** 从 update_file 参数提取可 diff 的 (oldcontent, content) 二元组；不完整返回 null。 */
function extractDiffArgs(args: Record<string, unknown> | null): { oldContent: string; newContent: string } | null {
  if (!args) return null
  const oldContent = typeof args.oldcontent === 'string' ? args.oldcontent : null
  const newContent = typeof args.content === 'string' ? args.content : null
  if (oldContent === null || newContent === null) return null
  return { oldContent, newContent }
}

/** 单条 update_file 调用的接管视图。 */
function UpdateFileEntry({ detail }: { detail: import('@everyagent/plugin-api').PluginToolCallDetail }) {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  const fullPath = typeof args.path === 'string' ? args.path : ''
  const businessPath = fullPath ? toBusinessAbsolutePath(fullPath) : ''
  const diffArgs = extractDiffArgs(args)

  const workspaceRoot = useTaskWorkspaceRoot()
  const { openGlobalFileTab } = useWorkspaceShell()

  const hasError = detail.status === 'error'
  const result = detail.result
  const resultText = typeof result === 'string' ? result : JSON.stringify(result, null, 2)

  const [open, setOpen] = React.useState(false)

  // 变更统计（折叠徽章与展开 diff 共用一次 LCS 计算，避免重复开销）。
  const diffLines = React.useMemo(
    () => (diffArgs ? buildLineDiff(diffArgs.oldContent, diffArgs.newContent) : []),
    [diffArgs],
  )
  const stat = React.useMemo(() => {
    let added = 0
    let removed = 0
    for (const line of diffLines) {
      if (line.type === 'added') added += 1
      else if (line.type === 'removed') removed += 1
    }
    return { added, removed }
  }, [diffLines])

  const handleOpenFile = React.useCallback(() => {
    if (!businessPath || !workspaceRoot || !openGlobalFileTab) return
    openGlobalFileTab({ workspaceRoot, filePath: businessPath }, { mode: 'readwrite' })
  }, [businessPath, workspaceRoot, openGlobalFileTab])

  const canOpen = Boolean(businessPath && workspaceRoot && openGlobalFileTab)

  return (
    <div className={`nagent-tool nagent-tool--filewrite${open ? ' is-open' : ''}`}>
      <button
        type="button"
        className="nagent-tool__summary"
        aria-expanded={open}
        onClick={() => {
          // 拖选参数文本（选区非空）时不切换折叠，保证文本可选中复制。
          if (hasActiveTextSelection()) return
          setOpen((value) => !value)
        }}
      >
        <WrenchIcon size={13} className={`nagent-tool__icon${hasError ? ' nagent-tool__icon--error' : ''}`} />
        <span className="nagent-tool__name">{detail.toolName || 'update_file'}</span>
        <span className="nagent-tool__inline-preview">
          {fullPath ? (
            <>
              <span className="nagent-tool__inline-file-name">{extractFileName(fullPath)}</span>
              <span className="update-file-view__stat" title={`新增 ${stat.added} 行 / 删除 ${stat.removed} 行`}>
                <span className="update-file-view__stat-add">+{stat.added}</span>
                <span className="update-file-view__stat-del">-{stat.removed}</span>
              </span>
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
              <span className="nagent-tool__name">{detail.toolName || 'update_file'}</span>
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
            {diffArgs ? (
              <div className="nagent-tool__result-block">
                <div className="nagent-tool__result-head">
                  <span className="nagent-tool__result-title">变更（{stat.added + stat.removed} 行）</span>
                </div>
                <UpdateFileDiff oldContent={diffArgs.oldContent} newContent={diffArgs.newContent} />
              </div>
            ) : (
              <div className="nagent-tool__result-block">
                <pre className="nagent-tool__result-text">
                  {Object.entries(args)
                    .map(([k, v]) => `${k}: ${typeof v === 'string' ? v : JSON.stringify(v)}`)
                    .join('\n')}
                </pre>
              </div>
            )}
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
          </div>
        </div>
      ) : null}
    </div>
  )
}

/**
 * update_file 工具视图组件（ToolViewProps 形态）。
 * 参数不完整（无法 diff）时由 UpdateFileEntry 内部回退为参数块渲染，
 * 保证异常/历史调用仍可见。
 */
export function UpdateFileToolView({ details }: { details: import('@everyagent/plugin-api').PluginToolCallDetail[] }) {
  return (
    <>
      {details.map((detail, idx) => (
        <UpdateFileEntry key={detail.toolCallId ?? idx} detail={detail} />
      ))}
    </>
  )
}
