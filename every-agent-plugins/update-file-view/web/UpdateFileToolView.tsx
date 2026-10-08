/**
 * update_file 工具调用的完整视图接管（update-file-view 插件核心组件）。
 *
 * 经 `ui.tool_call_views` 扩展点注册，命中 update_file 即整体接管渲染
 * （与核心内置 toolViews 同地位，优先级高于内置注册表）：
 *
 * 折叠态：工具图标 + 工具名 + 文件名 + 变更统计徽章（+a −r，无变更时显示「无变更」）
 *         + 完整路径 + 折叠箭头 —— 不展开即可看到改了哪个文件、改动量多大。
 * 展开态：头部（图标 + 工具名 + 可点击路径 chip）→ 内嵌 diff（oldcontent → content
 *         行级对比，先删后增 + 词级高亮，一律完整展开）→ 结果块 → 错误块。
 *
 * 变更统计只呈现一处（折叠态徽章），diff 区内不再重复「N 行变更」；
 * LCS 也只算一次，折叠徽章与 diff 表共用同一份 diffLines / rows。
 *
 * 参数缺失（oldcontent/content 非字符串）时回退为参数块渲染（超长值截断，
 * 避免整篇正文占据展开区），保证异常/历史调用仍可诊断。
 *
 * 展开态路径 chip 点击时**遍历注册表工作区根定位文件实际所属工作区**再打开
 * （ctx.sdk.workspace.rootPath 是插件加载时 sys.info 回填的 worker 默认工作区根，
 * 任务改动的文件绝大多数不在默认工作区下，直接用它打开必得 [NOT_FOUND]）——
 * 与核心 FileToolEntry.resolveFileWorkspace 同口径，见 handleOpenFile 注释。
 *
 * 结构与 FileToolEntry 同构（复用 chatPanel.css 的 nagent-tool__* 类与插件内
 * helpers 的选区守卫/文件名提取），独立演进不反向侵入核心。
 */

import React from 'react'
import { App } from 'antd'
import type { PluginToolCallDetail } from '@everyagent/plugin-api'
import { WrenchIcon, ChevronDownIcon, ChevronRightIcon } from './icons'
import { getPluginContext } from './pluginRuntime'
import {
  toBusinessAbsolutePath,
  basename,
  dirname,
  extractFileName,
  hasActiveTextSelection,
  buildLineDiff,
  buildDiffRows,
  countChanges,
} from './utils'
import { UpdateFileDiff } from './UpdateFileDiff'

/** 回退参数块里单个值的最大展示长度（超出截断，避免整篇正文挤占展开区）。 */
const ARG_VALUE_LIMIT = 400

/** 从 update_file 参数提取可 diff 的 (oldcontent, content) 二元组；不完整返回 null。 */
function extractDiffArgs(args: Record<string, unknown>): { oldContent: string; newContent: string } | null {
  const oldContent = typeof args.oldcontent === 'string' ? args.oldcontent : null
  const newContent = typeof args.content === 'string' ? args.content : null
  if (oldContent === null || newContent === null) return null
  return { oldContent, newContent }
}

/** 参数展平为 `k: v` 文本行，超长值截断（仅用于无法 diff 时的兜底展示）。 */
function formatArgLines(args: Record<string, unknown>): string[] {
  return Object.entries(args).map(([key, value]) => {
    const raw = typeof value === 'string' ? value : JSON.stringify(value)
    return `${key}: ${raw.length > ARG_VALUE_LIMIT ? `${raw.slice(0, ARG_VALUE_LIMIT)}…（共 ${raw.length} 字符）` : raw}`
  })
}

/** 单条 update_file 调用的接管视图。 */
function UpdateFileEntry({ detail }: { detail: PluginToolCallDetail }) {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  const fullPath = typeof args.path === 'string' ? args.path : ''
  const businessPath = fullPath ? toBusinessAbsolutePath(fullPath) : ''
  const diffArgs = extractDiffArgs(args)

  const ctx = getPluginContext()
  // antd App 上下文轻提示（宿主整树包在 <AntApp> 内；与 git 插件 GitSidebarPanel 同模式）。
  const { message } = App.useApp()

  const hasError = detail.status === 'error'
  const result = detail.result
  const resultText = typeof result === 'string' ? result : JSON.stringify(result, null, 2)

  const [open, setOpen] = React.useState(false)

  // diff 只算一次：折叠态徽章与展开态 diff 表共用同一份结果。
  const diff = React.useMemo(() => {
    if (!diffArgs) return null
    const diffLines = buildLineDiff(diffArgs.oldContent, diffArgs.newContent)
    return {
      rows: buildDiffRows(diffLines),
      stat: countChanges(diffLines),
      oldContent: diffArgs.oldContent,
      newContent: diffArgs.newContent,
    }
  }, [diffArgs])

  /**
   * 解析文件实际所属的工作区根（与核心 FileToolEntry.resolveFileWorkspace 同口径）。
   *
   * 插件拿不到任务工作区根——`ctx.sdk.workspace.rootPath` 是插件加载时 sys.info
   * 回填的 worker **默认工作区根**（见 plugin-guide/web/context-api.md §sdk.workspace），
   * 任务改动的文件绝大多数不在默认工作区下，直接拿它打开文件标签页必得
   * `[NOT_FOUND] 路径不存在`。改为遍历注册表全部工作区根，用 fs.listDir 探测
   * 目标所在父目录，返回第一个能找到该文件（非目录）的工作区根；注册表按最后
   * 活动时间排序，刚跑过任务的工作区天然靠前。
   */
  const resolveFileWorkspaceRoot = React.useCallback(async (): Promise<string | null> => {
    const parent = dirname(fullPath)
    const fileName = basename(fullPath)
    if (!fileName) return null
    let entries: Awaited<ReturnType<typeof ctx.sdk.workspace.list>> = []
    try {
      entries = await ctx.sdk.workspace.list()
    } catch {
      return null
    }
    for (const entry of entries) {
      if (!entry.root || entry.missing) continue
      try {
        const rows = await ctx.fs.listDir(entry.root, parent)
        if (rows.some((row) => row.name === fileName && !row.isDirectory)) {
          return entry.root
        }
      } catch {
        // 该工作区根下不存在（或不可达），继续尝试下一个。
      }
    }
    return null
  }, [ctx, fullPath])

  const handleOpenFile = React.useCallback(async () => {
    if (!businessPath) return
    const root = await resolveFileWorkspaceRoot()
    if (!root) {
      message.error(`未能在已注册工作区中找到该文件：${businessPath}`)
      return
    }
    ctx.ui.openFileTab(root, businessPath, { mode: 'readwrite' })
  }, [businessPath, ctx, message, resolveFileWorkspaceRoot])

  return (
    <div className={`nagent-tool nagent-tool--filewrite${open ? ' is-open' : ''}`}>
      <button
        type="button"
        className="nagent-tool__summary"
        aria-expanded={open}
        onClick={() => {
          // 拖选文本（选区非空）时不切换折叠，保证文本可选中复制。
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
              {diff ? <StatBadge added={diff.stat.added} removed={diff.stat.removed} /> : null}
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
                <button
                  type="button"
                  className="nagent-tool__detail-path"
                  title={`打开文件：${businessPath}`}
                  onClick={(e) => {
                    e.stopPropagation()
                    void handleOpenFile()
                  }}
                >
                  {businessPath}
                </button>
              ) : null}
            </div>
            {diff ? (
              <div className="nagent-tool__result-block">
                <UpdateFileDiff
                  rows={diff.rows}
                  added={diff.stat.added}
                  removed={diff.stat.removed}
                  unapplied={hasError}
                />
              </div>
            ) : (
              <div className="nagent-tool__result-block">
                <div className="nagent-tool__result-head">
                  <span className="nagent-tool__result-title">参数</span>
                </div>
                <pre className="nagent-tool__result-text">{formatArgLines(args).join('\n')}</pre>
              </div>
            )}
            {resultText.trim() && !hasError ? (
              <div className="nagent-tool__result-block">
                <div className="nagent-tool__result-head">
                  <span className="nagent-tool__result-title">结果</span>
                </div>
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

/** 折叠态变更统计徽章：+新增 / −删除；无实际变更时给出明确信号而非「+0 −0」。 */
function StatBadge({ added, removed }: { added: number; removed: number }) {
  if (added === 0 && removed === 0) {
    return <span className="update-file-view__stat update-file-view__stat--none" title="替换内容与原片段一致">无变更</span>
  }
  return (
    <span className="update-file-view__stat" title={`新增 ${added} 行 / 删除 ${removed} 行`}>
      {added > 0 ? <span className="update-file-view__stat-add">+{added}</span> : null}
      {removed > 0 ? <span className="update-file-view__stat-del">−{removed}</span> : null}
    </span>
  )
}

/**
 * update_file 工具视图组件（ToolViewProps 形态）。
 * 参数不完整（无法 diff）时由 UpdateFileEntry 内部回退为参数块渲染，
 * 保证异常/历史调用仍可见。
 */
export function UpdateFileToolView({ details }: { details: PluginToolCallDetail[] }) {
  return (
    <>
      {details.map((detail, idx) => (
        <UpdateFileEntry key={detail.toolCallId ?? idx} detail={detail} />
      ))}
    </>
  )
}
