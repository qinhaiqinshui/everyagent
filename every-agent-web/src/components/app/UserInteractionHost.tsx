import React from 'react'
import { Radio } from 'antd'
import ConfirmDialog from '@/components/shared/ConfirmDialog'
import { QuestionMarkIcon } from '@/components/shared/AppGlyphs'
import { Button, TextInput } from '@/components/shared/ui'
import { useAppUi } from '@/components/app/AppUiContext'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import {
  getAskDraft,
  getPendingRuntimeUserInteraction,
  saveAskDraft,
  submitRuntimeUserInteractionResult,
} from '@/hub/askStore'
import {
  USER_INTERACTION_OTHER_OPTION_ID,
  type UserInteractionOption,
  type UserInteractionQuestion,
  type UserInteractionQuestionAnswer,
  type UserInteractionRequest,
} from '@/types'

/**
 * 全局用户交互宿主。
 * 负责承接 ask_user 这类系统级交互请求，并把回答提交回运行时。
 *
 * 支持的交互形态：
 * - 多问题单选题（request.questions）：一次交互逐题展示（每弹窗只显示一题），每题单选，均含「其他」输入框；
 * - 单问题单选题（responseMode single_choice + options）：内部确认场景（如超限询问，无「其他」）；
 * - 确认（responseMode confirm）：内部授权场景。
 * - 授权（responseMode authorization）：危险操作授权三选一（拒绝 / 本轮运行内允许 / 本任务全程允许），
 *   答案以稳定 token（deny/run/task）经 ask.reply 回传 worker PermissionGate。
 */
export default function UserInteractionHost() {
  const { showToast } = useAppUi()
  const [activeInteractionId, setActiveInteractionId] = React.useState<string | null>(null)
  const [request, setRequest] = React.useState<UserInteractionRequest | null>(null)
  /**
   * 最新活跃交互 ID 的 ref（与 state 同步维护）。
   * 事件订阅固定注册一次（不随 state 重建），处理回调内经 ref 读取最新值，
   * 避免订阅销毁/重建间隙内 RESOLVED 事件丢失导致弹窗不关闭。
   */
  const activeInteractionIdRef = React.useRef<string | null>(null)
  /** 每题选中的选项 ID（questionId → optionIds）。 */
  const [questionSelections, setQuestionSelections] = React.useState<Record<string, string[]>>({})
  /** 每题「其他」输入框内容（questionId → text）。 */
  const [questionOtherTexts, setQuestionOtherTexts] = React.useState<Record<string, string>>({})
  /** 当前展示的问题下标（多问题逐题展示时推进）。 */
  const [currentQuestionIndex, setCurrentQuestionIndex] = React.useState(0)
  const [submitting, setSubmitting] = React.useState(false)

  /**
   * 解析出待渲染的单选题列表：
   * - questions 存在 → 多问题单选题（ask_user 主路径）；
   * - 否则 single_choice → 合成单问题（内部「继续/中止」等确认场景，options 不含「其他」）。
   * authorization（危险操作授权）不走问题表单，返回空（底部三按钮直接作答）。
   */
  const resolveQuestions = React.useCallback((currentRequest: UserInteractionRequest): UserInteractionQuestion[] => {
    if (currentRequest.responseMode === 'authorization') {
      return []
    }
    if (currentRequest.questions && currentRequest.questions.length > 0) {
      return currentRequest.questions
    }
    if (currentRequest.responseMode === 'single_choice') {
      return [{
        id: 'question_1',
        prompt: currentRequest.prompt,
        details: currentRequest.details,
        options: currentRequest.options ?? [],
      }]
    }
    return []
  }, [])

  /**
   * 按交互请求重置本地编辑态。
   * 若 askStore 中已有实时缓存草稿(稍后再答/窗口隐藏后再打开),则从缓存恢复
   * 各题已选选项、「其他」输入内容与当前题目下标,保证答案不丢失。
   */
  const resetDraftByRequest = React.useCallback((nextRequest: UserInteractionRequest | null) => {
    const questions = nextRequest ? resolveQuestions(nextRequest) : []
    const draft = nextRequest ? getAskDraft(nextRequest.id) : null
    setQuestionSelections(Object.fromEntries(
      questions.map((question) => [question.id, draft?.selections?.[question.id] ?? []]),
    ))
    setQuestionOtherTexts(Object.fromEntries(
      questions.map((question) => [question.id, draft?.otherTexts?.[question.id] ?? '']),
    ))
    const restoredIndex = draft?.currentQuestionIndex ?? 0
    setCurrentQuestionIndex(
      Number.isInteger(restoredIndex) && restoredIndex >= 0 && restoredIndex < questions.length ? restoredIndex : 0,
    )
  }, [resolveQuestions])

  /**
   * 打开指定交互请求。
   */
  const openInteraction = React.useCallback((interactionId: string) => {
    const nextRequest = getPendingRuntimeUserInteraction(interactionId)
    if (!nextRequest) {
      showToast('当前交互请求已结束', 'info')
      activeInteractionIdRef.current = null
      setActiveInteractionId(null)
      setRequest(null)
      resetDraftByRequest(null)
      return
    }
    activeInteractionIdRef.current = interactionId
    setActiveInteractionId(interactionId)
    setRequest(nextRequest)
    resetDraftByRequest(nextRequest)
  }, [resetDraftByRequest, showToast])

  /**
   * 关闭当前弹层，但不取消等待中的请求。
   */
  const closeDialog = React.useCallback(() => {
    activeInteractionIdRef.current = null
    setActiveInteractionId(null)
    setRequest(null)
    resetDraftByRequest(null)
  }, [resetDraftByRequest])

  React.useEffect(() => {
    const unsubscribeRequested = domainEventBus.subscribe(DOMAIN_EVENTS.USER_INTERACTION_REQUESTED, ({ request: nextRequest }) => {
      openInteraction(nextRequest.id)
    })
    const unsubscribeOpenRequested = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_OPEN_USER_INTERACTION_REQUESTED, ({ interactionId }) => {
      openInteraction(interactionId)
    })
    const unsubscribeResolved = domainEventBus.subscribe(DOMAIN_EVENTS.USER_INTERACTION_RESOLVED, ({ result }) => {
      // 经 ref 读取最新活跃交互 ID：订阅常驻一次注册，闭包不随 activeInteractionId 状态重建，
      // 保证超时/提交/取消结案事件永不在订阅间隙中丢失（丢失会导致弹窗残留不关闭）。
      if (result.interactionId === activeInteractionIdRef.current) {
        closeDialog()
      }
    })
    const unsubscribeCleared = domainEventBus.subscribe(DOMAIN_EVENTS.USER_INTERACTION_CLEARED, ({ interactionId }) => {
      if (interactionId === activeInteractionIdRef.current) {
        closeDialog()
      }
    })
    return () => {
      unsubscribeRequested()
      unsubscribeOpenRequested()
      unsubscribeResolved()
      unsubscribeCleared()
    }
  }, [closeDialog, openInteraction])

  /**
   * 选中某题的某个选项（单选：直接替换当前选中项）。
   */
  const selectQuestionOption = React.useCallback((questionId: string, optionId: string) => {
    setQuestionSelections((current) => ({ ...current, [questionId]: [optionId] }))
  }, [])

  /**
   * 更新某题「其他」输入框内容。
   */
  const updateQuestionOtherText = React.useCallback((questionId: string, value: string) => {
    setQuestionOtherTexts((current) => ({ ...current, [questionId]: value }))
  }, [])

  /**
   * 实时缓存作答草稿：选择/输入/切题一变化就写回 askStore。
   * 这样「稍后再答」、弹层被其他操作隐藏等场景下重开同一 ask 时答案不丢。
   */
  React.useEffect(() => {
    if (!activeInteractionId || !request) return
    // 仅选择题路径有表单草稿需要缓存;确认/授权直接提交,无需缓存。
    const questions = resolveQuestions(request)
    if (questions.length === 0) return
    saveAskDraft(activeInteractionId, {
      selections: questionSelections,
      otherTexts: questionOtherTexts,
      currentQuestionIndex,
    })
  }, [activeInteractionId, currentQuestionIndex, questionOtherTexts, questionSelections, request, resolveQuestions])

  /**
   * 按当前草稿组装选择题答案列表。
   */
  const buildAnswers = React.useCallback((currentRequest: UserInteractionRequest): UserInteractionQuestionAnswer[] => {
    return resolveQuestions(currentRequest).map((question) => {
      const selectedIds = questionSelections[question.id] ?? []
      const selectedOptions = selectedIds
        .map((id) => question.options.find((option) => option.id === id))
        .filter((option): option is UserInteractionOption => Boolean(option))
        .map((option) => ({ id: option.id, label: option.label, description: option.description }))
      const otherSelected = selectedIds.includes(USER_INTERACTION_OTHER_OPTION_ID)
      return {
        questionId: question.id,
        prompt: question.prompt,
        selectedOptionIds: selectedIds,
        selectedOptions,
        otherSelected,
        otherText: otherSelected ? (questionOtherTexts[question.id] ?? '').trim() : undefined,
      }
    })
  }, [questionOtherTexts, questionSelections, resolveQuestions])

  /**
   * 判断单个问题是否已完整作答：必须已选一项；选「其他」时必须填写自定义内容。
   */
  const isQuestionAnswered = React.useCallback((question: UserInteractionQuestion): boolean => {
    const selectedIds = questionSelections[question.id] ?? []
    if (selectedIds.length === 0) {
      return false
    }
    if (selectedIds.includes(USER_INTERACTION_OTHER_OPTION_ID) && !(questionOtherTexts[question.id] ?? '').trim()) {
      return false
    }
    return true
  }, [questionOtherTexts, questionSelections])

  /**
   * 找出第一个未作答的问题下标；全部答完返回 -1。
   */
  const findFirstUnansweredIndex = React.useCallback((questions: UserInteractionQuestion[]): number => {
    return questions.findIndex((question) => !isQuestionAnswered(question))
  }, [isQuestionAnswered])

  /**
   * 提交当前交互结果。
   */
  const submitCurrentRequest = React.useCallback(async (override?: { confirmed?: boolean; authorizeOption?: string }) => {
    if (!request || !activeInteractionId) {
      return
    }
    try {
      setSubmitting(true)
      // 确认模式：走 override 的确认结果
      if (request.responseMode === 'confirm') {
        if (typeof override?.confirmed !== 'boolean') {
          throw new Error('缺少确认结果')
        }
        submitRuntimeUserInteractionResult(activeInteractionId, {
          responseMode: 'confirm',
          confirmed: override.confirmed,
          raw: {
            confirmed: override.confirmed,
          },
        })
        closeDialog()
        return
      }
      // 授权模式：三选一(run/task/deny),以稳定 token 回传
      if (request.responseMode === 'authorization') {
        const optionId = override?.authorizeOption ?? 'deny'
        submitRuntimeUserInteractionResult(activeInteractionId, {
          responseMode: 'authorization',
          selectedOptionIds: [optionId],
          raw: {
            authorizeOption: optionId,
          },
        })
        closeDialog()
        return
      }

      // 选择题（单问题/多问题）
      const questions = resolveQuestions(request)
      if (questions.length === 0) {
        throw new Error('不支持的交互模式')
      }
      // 提交时全量校验：未答完则提示并跳到第一道未答题。
      const firstUnansweredIndex = findFirstUnansweredIndex(questions)
      if (firstUnansweredIndex >= 0) {
        const unanswered = questions[firstUnansweredIndex]
        setCurrentQuestionIndex(firstUnansweredIndex)
        showToast(
          questions.length > 1
            ? `还有未完成的题目：第 ${firstUnansweredIndex + 1} 题「${unanswered.prompt}」尚未作答`
            : '请先选择一个选项后再提交',
          'error',
        )
        return
      }
      const answers = buildAnswers(request)
      // 内部单问题单选题（如超限「继续/中止」）读 selectedOptionIds 判断，需回填兼容
      const isLegacySingleChoice = questions.length === 1 && request.responseMode === 'single_choice'
      submitRuntimeUserInteractionResult(activeInteractionId, {
        ...(isLegacySingleChoice ? {
          responseMode: request.responseMode,
          selectedOptionIds: answers[0].selectedOptionIds,
        } : {}),
        answers,
        raw: {
          ...(isLegacySingleChoice ? { selectedOptionIds: answers[0].selectedOptionIds } : {}),
          answers,
        },
      })
      closeDialog()
    } catch (error) {
      showToast(error instanceof Error ? error.message : '提交回答失败', 'error')
    } finally {
      setSubmitting(false)
    }
  }, [activeInteractionId, buildAnswers, closeDialog, findFirstUnansweredIndex, request, resolveQuestions, showToast])

  /**
   * 上一题（多问题逐题展示）：自由回看，不校验当前题是否已答。
   */
  const goPreviousQuestion = React.useCallback(() => {
    setCurrentQuestionIndex((index) => Math.max(0, index - 1))
  }, [])

  /**
   * 下一题（多问题逐题展示）：自由前进查看，不校验当前题是否已答。
   */
  const goNextQuestion = React.useCallback(() => {
    setCurrentQuestionIndex((index) => index + 1)
  }, [])

  /**
   * 主按钮动作（选择题路径）：
   * - 多问题且非最后一题 → 直接进入下一题（不强制作答当前题）；
   * - 最后一题 / 单问题 → 提交整个交互（提交时统一校验所有题）。
   */
  const handlePrimaryAction = React.useCallback(() => {
    if (!request) {
      return
    }
    const questions = resolveQuestions(request)
    if (questions.length > 1 && currentQuestionIndex < questions.length - 1) {
      setCurrentQuestionIndex((index) => index + 1)
      return
    }
    void submitCurrentRequest()
  }, [currentQuestionIndex, request, resolveQuestions, submitCurrentRequest])

  if (!request) {
    return null
  }

  const questions = resolveQuestions(request)
  const dialogTitle = request.prompt || questions[0]?.prompt || 'AI 等待你的回答'
  const currentQuestion = questions[currentQuestionIndex] ?? null
  // 选择题路径：头部标题展示当前题目的问题文本；确认/授权等无 questions 时回落为请求主问题。
  const headerTitle = currentQuestion ? currentQuestion.prompt : dialogTitle
  // 多问题逐题展示：问题进度放在头部标题行右侧。
  const headerExtra = questions.length > 1
    ? `问题 ${currentQuestionIndex + 1} / ${questions.length}`
    : undefined
  const isConfirmMode = request.responseMode === 'confirm'
  const isAuthorizationMode = request.responseMode === 'authorization'
  const authOption = (id: 'deny' | 'run' | 'task') =>
    request.options?.find((option) => option.id === id)
  const currentSelectedIds = currentQuestion ? (questionSelections[currentQuestion.id] ?? []) : []
  const otherSelected = currentSelectedIds.includes(USER_INTERACTION_OTHER_OPTION_ID)
  const otherOption = currentQuestion?.options.find((option) => option.id === USER_INTERACTION_OTHER_OPTION_ID)
  const isLastQuestion = currentQuestionIndex >= questions.length - 1
  // 多问题逐题展示：非最后一题主按钮为「下一题」，最后一题/单问题为「提交回答」
  const primaryLabel = isConfirmMode
    ? (request.submitLabel ?? '确认')
    : (questions.length > 1 && !isLastQuestion ? '下一题' : (request.submitLabel ?? '提交回答'))

  return (
    <ConfirmDialog
      open
      title={headerTitle}
      headerExtra={headerExtra}
      icon={<QuestionMarkIcon size={16} />}
      subtitle={isAuthorizationMode ? '请选择授权生效范围（拒绝即取消该操作）' : undefined}
      message={request.details}
      confirmLabel={primaryLabel}
      cancelLabel="稍后再答"
      hideDefaultMessageBlock={!request.details}
      onCancel={closeDialog}
      onConfirm={isConfirmMode ? () => submitCurrentRequest() : handlePrimaryAction}
      actions={isConfirmMode ? (
        <div style={confirmActionRowStyle}>
          <Button variant="secondary" onClick={closeDialog}>
            稍后再答
          </Button>
          <Button
            variant="secondary"
            onClick={() => { void submitCurrentRequest({ confirmed: false }) }}
            disabled={submitting}
          >
            否
          </Button>
          <Button
            variant="primary"
            onClick={() => { void submitCurrentRequest({ confirmed: true }) }}
            disabled={submitting}
          >
            {request.submitLabel ?? '确认'}
          </Button>
        </div>
      ) : isAuthorizationMode ? (
        <div style={confirmActionRowStyle}>
          <Button variant="secondary" onClick={closeDialog}>
            稍后再答
          </Button>
          <Button
            variant="danger"
            onClick={() => { void submitCurrentRequest({ authorizeOption: 'deny' }) }}
            disabled={submitting}
          >
            {authOption('deny')?.label ?? '拒绝'}
          </Button>
          <Button
            variant="secondary"
            onClick={() => { void submitCurrentRequest({ authorizeOption: 'run' }) }}
            disabled={submitting}
          >
            {authOption('run')?.label ?? '本轮运行内允许'}
          </Button>
          <Button
            variant="primary"
            onClick={() => { void submitCurrentRequest({ authorizeOption: 'task' }) }}
            disabled={submitting}
          >
            {authOption('task')?.label ?? '本任务全程允许'}
          </Button>
        </div>
      ) : questions.length > 1 ? (
        // 多问题逐题展示：可自由切换上一题/下一题（不校验当前题是否已答），最后一题提交时统一校验。
        <div style={questionNavRowStyle}>
          <Button variant="secondary" onClick={closeDialog} disabled={submitting}>
            稍后再答
          </Button>
          <div style={navSpacerStyle} />
          <Button
            variant="secondary"
            onClick={goPreviousQuestion}
            disabled={submitting || currentQuestionIndex === 0}
          >
            上一题
          </Button>
          {isLastQuestion ? (
            <Button
              variant="primary"
              onClick={() => { void submitCurrentRequest() }}
              disabled={submitting}
            >
              {request.submitLabel ?? '提交回答'}
            </Button>
          ) : (
            <Button variant="primary" onClick={goNextQuestion} disabled={submitting}>
              下一题
            </Button>
          )}
        </div>
      ) : undefined}
      body={(
        <div style={bodyStyle}>
          {request.constraints && request.constraints.length > 0 ? (
            <div style={infoBlockStyle}>
              <div style={infoTitleStyle}>回答约束</div>
              {request.constraints.map((constraint) => (
                <div key={constraint} style={infoLineStyle}>{constraint}</div>
              ))}
            </div>
          ) : null}

          {currentQuestion ? (
            <React.Fragment key={currentQuestion.id}>
              {currentQuestion.details ? <div style={questionDetailsStyle}>{currentQuestion.details}</div> : null}
              <div style={optionListStyle}>
                {currentQuestion.options.map((option) => {
                  const checked = currentSelectedIds.includes(option.id)
                  return (
                    <label key={option.id} style={optionItemStyle}>
                      <Radio
                        name={`interaction-${request.id}-${currentQuestion.id}`}
                        checked={checked}
                        onChange={() => selectQuestionOption(currentQuestion.id, option.id)}
                      />
                      <div style={optionTextStyle}>
                        <div style={optionLabelStyle}>{option.label}</div>
                        {option.description ? <div style={optionDescriptionStyle}>{option.description}</div> : null}
                      </div>
                    </label>
                  )
                })}
              </div>
              {otherSelected ? (
                <TextInput
                  type="text"
                  value={questionOtherTexts[currentQuestion.id] ?? ''}
                  onChange={(event) => updateQuestionOtherText(currentQuestion.id, event.target.value)}
                  placeholder={currentQuestion.otherPlaceholder ?? `请输入${otherOption?.label ?? '其他'}内容`}
                />
              ) : null}
            </React.Fragment>
          ) : null}
        </div>
      )}
    />
  )
}

const bodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
}

const infoBlockStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '12px 14px',
  borderRadius: 12,
  border: '1px solid var(--border-light)',
  background: 'var(--bg-primary)',
}

const infoTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--text-primary)',
}

const infoLineStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

const questionDetailsStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

const optionListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
}

const optionItemStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
  padding: '12px 14px',
  borderRadius: 12,
  border: '1px solid var(--border-light)',
  background: 'var(--bg-primary)',
  cursor: 'pointer',
}

const optionTextStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  minWidth: 0,
}

const optionLabelStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 600,
  color: 'var(--text-primary)',
}

const optionDescriptionStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

const confirmActionRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 10,
  width: '100%',
}

const questionNavRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  width: '100%',
}

const navSpacerStyle: React.CSSProperties = {
  flex: 1,
}
