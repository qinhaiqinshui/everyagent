import React from 'react'
import type {
  ChatComposerDraftState,
  ChatComposerToken,
  LLMConfigProfile,
} from '@/types'
import type { ComposerDraftChange } from './TaskComposerSurface'
import { BrandMark } from '../shared/BrandLoadingBlock'
import { ArrowRightIcon } from '../shared/AppGlyphs'
import HScrollArea from '../shared/HScrollArea'
import TaskModelControls from './TaskModelControls'
import TaskWorkspacePicker from './TaskWorkspacePicker'
import TaskWorkerPicker from './TaskWorkerPicker'
import TaskComposerSurface from './TaskComposerSurface'
import { extractSlashId } from '@/slash/taskScopedTokens'
import ChatShell from '@/components/task/ChatShell'
import { Button } from '@/components/shared/ui'
import type { WorkspaceEntry } from '@/hub/workspaceRegistry'
import type { WorkerInfo } from '@/hub/session'

/**
 * Task 启动台草稿面板属性。
 */
export interface TaskDraftComposerPanelProps {
  /** 当前是否移动端。 */
  isMobile: boolean
  /** 当前选中的模型配置 ID。 */
  selectedLlmConfigId: string
  /** 可用模型配置列表。 */
  llmProfiles: LLMConfigProfile[]
  /** 当前任务草稿状态。 */
  draft: ChatComposerDraftState
  /** 可选 worker 候选(已由调用方筛好「已连接可用」)。 */
  workers?: WorkerInfo[]
  /** 当前选中的 worker(空 = 未选择,提交被禁用)。 */
  selectedWorker?: string
  /** 切换草稿 worker。 */
  onSelectWorker?: (workerId: string) => void
  /** 可选工作区注册表(新任务挂靠目录,多工作区并行)。 */
  workspaces?: WorkspaceEntry[]
  /** 当前选中的工作区根(空 = 未选择,提交被禁用)。 */
  selectedWorkspace?: string
  /** 切换草稿工作区。 */
  onSelectWorkspace?: (root: string) => void
  /** 浏览弹窗确认待选目录(未注册;注册延迟到任务创建时的 task.run)。 */
  onPickWorkspace?: (path: string) => void
  /** 当前所选 worker(透传:slash 定向 + 浏览目录定向)。 */
  workerId?: string
  /** 提交按钮文案。 */
  submitLabel: string
  /** 是否禁用提交按钮。 */
  submitDisabled: boolean
  /** 输入区错误文案。 */
  error?: string
  /** 启动台非阻断装载问题。 */
  launcherIssues?: string[]
  /** 重置当前草稿。 */
  onResetDraft?: () => void
  /** 选择模型配置。 */
  onSelectLlmConfig: (llmConfigId: string) => void
  /** 草稿内容变化（输入 / 插入 / 删除胶囊）。 */
  onChangeDraft: (next: ComposerDraftChange) => void
  /** 在草稿文本中删除 `@query` 片段并插入一个文件引用 token。 */
  onSelectWorkspaceFile?: (
    token: ChatComposerToken,
    removeRange: { start: number; end: number },
  ) => void
  /** 提交当前任务草稿。 */
  onSubmitTask: () => void
  /**
   * 外部接收编辑器当前光标文本偏移（供 composer 权限「插入到光标位置」定位）。可选。
   * 父组件持有该 ref，本组件在每次光标变化时写入最新偏移。
   */
  composerCaretRef?: React.MutableRefObject<number>
  /** 任务级底部 token（胶囊）列表（草稿本地持有）。 */
  taskScopeTokens?: ChatComposerToken[]
  /** 添加任务级 token（选中 bottom 项）。允许返回 Promise（联动多胶囊逐个 await）。 */
  onAddTaskScopeToken?: (payload: { id: string; token: string }) => void | Promise<void>
  /** 移除任务级 token（底部 ✕，内联 ✕ 由 TaskComposerSurface 内部处理）。 */
  onRemoveTaskScopeToken?: (payload: { id?: string; token: string }) => void
}

/**
 * Task 启动台草稿面板。
 * 复用与老任务相同的 ChatShell 窗口结构（顶栏 + 空线程 + 输入区），
 * 只是尚未创建 Task，因此线程为空、输入区带模型选择。
 *
 * hub 版:没有插件体系,`ui.composer_footer_controls` 扩展位不再渲染,
 * 其余布局与 n 分支一致。
 */
export default function TaskDraftComposerPanel({
  isMobile,
  selectedLlmConfigId,
  llmProfiles,
  draft,
  workers = [],
  selectedWorker = '',
  onSelectWorker,
  workspaces = [],
  selectedWorkspace = '',
  onSelectWorkspace,
  onPickWorkspace,
  workerId,
  submitLabel,
  submitDisabled,
  error,
  launcherIssues = [],
  onResetDraft,
  onSelectLlmConfig,
  onChangeDraft,
  onSubmitTask,
  composerCaretRef,
  taskScopeTokens,
  onAddTaskScopeToken,
  onRemoveTaskScopeToken,
}: TaskDraftComposerPanelProps) {
  return (
    <ChatShell
      header={(
        <div className="nagent-header">
          <span className="nagent-header__title" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <BrandMark size={14} animated={false} />
            Agent
          </span>
        </div>
      )}
      thread={null}
      composer={(
        <div className="nagent-composer-dock">
          <TaskComposerSurface
            draft={draft}
            workspace={selectedWorkspace || undefined}
            workerId={workerId}
            isMobile={isMobile}
            placeholder="输入你想让当前任务处理的内容"
            submitLabel={submitLabel}
            submitDisabled={submitDisabled}
            showSendButton={false}
            error={error || launcherIssues[0] || ''}
            onChangeDraft={onChangeDraft}
            onSubmit={onSubmitTask}
            composerCaretRef={composerCaretRef}
            onAddTaskScopeToken={onAddTaskScopeToken}
            footerControls={(
              <div className="task-composer-footer">
                <HScrollArea className="task-composer-footer__scroll">
                  <div className="task-composer-footer__controls">
                    <TaskWorkerPicker
                      workers={workers}
                      selected={selectedWorker}
                      onSelect={onSelectWorker}
                    />
                    <TaskWorkspacePicker
                      workspaces={workspaces}
                      selected={selectedWorkspace}
                      onSelect={onSelectWorkspace}
                      onPickExternal={onPickWorkspace}
                      workerId={workerId}
                    />
                    <TaskModelControls
                      selectedLlmConfigId={selectedLlmConfigId}
                      llmProfiles={llmProfiles}
                      isMobile={isMobile}
                      onSelectLlmConfig={onSelectLlmConfig}
                    />
                    {taskScopeTokens?.map((token) => (
                      <span key={token.id} className="nagent-inline-chip nagent-inline-chip--scope">
                        <span className="nagent-inline-chip__label">{token.label}</span>
                        <span
                          className="nagent-inline-chip__close"
                          contentEditable={false}
                          data-close="1"
                          aria-label="取消"
                          onClick={() => onRemoveTaskScopeToken?.({ id: extractSlashId(token.opaqueText), token: token.opaqueText })}
                        >✕</span>
                      </span>
                    ))}
                  </div>
                </HScrollArea>
                <Button
                  type="button"
                  variant="primary"
                  size="sm"
                  onClick={onSubmitTask}
                  disabled={submitDisabled}
                  className="nagent-composer__send task-composer-footer__send"
                >
                  <ArrowRightIcon size={14} />
                  <span className="task-composer-footer__send-label">
                    {submitLabel}
                  </span>
                </Button>
              </div>
            )}
          />
        </div>
      )}
    />
  )
}

