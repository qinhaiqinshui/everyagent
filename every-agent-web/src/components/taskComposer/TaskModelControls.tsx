import React from 'react'
import { Select } from 'antd'
import type { LLMConfigProfile } from '@/types'
import './TaskModelControls.css'

/**
 * Task 模型选择控件。
 *
 * hub 版适配说明：n 架构中思考强度由 getProviderReasoningCapabilities 动态探测，
 * 该函数在 hub 版前端不存在（思考强度由 hub 端决定）。此处改为由调用方通过
 * reasoningEffortOptions 显式传入；不传则不渲染思考强度下拉。
 */
export interface ReasoningEffortOption {
  value: string
  label: string
}

/** 与 workspace 选择器保持一致的 Select 外观：无边框、选中后无箭头、宽度自适应、下拉完整显示。
 *  弹层宽度处理见 TaskModelControls.css：桌面保持自适应（内容完整显示），移动端限制最大宽度并横向滚动。 */
const sharedSelectProps = {
  bordered: false,
  size: 'small' as const,
  dropdownMatchSelectWidth: false,
  listHeight: 100000,
  style: { minWidth: 'fit-content' },
  classNames: {
    popup: {
      root: 'task-model-select-dropdown',
    },
  },
}

export default function TaskModelControls({
  selectedLlmConfigId,
  selectedReasoningEffort,
  llmProfiles,
  isMobile,
  onSelectLlmConfig,
  onSelectReasoningEffort,
  reasoningEffortOptions = [],
}: {
  /** 当前选中的模型配置 ID。 */
  selectedLlmConfigId: string
  /** 当前选中的思考强度。 */
  selectedReasoningEffort?: string
  /** 可选模型配置列表。 */
  llmProfiles: LLMConfigProfile[]
  /** 当前是否移动端。 */
  isMobile: boolean
  /** 选择模型配置。 */
  onSelectLlmConfig: (llmConfigId: string) => void
  /** 选择思考强度。 */
  onSelectReasoningEffort?: (reasoningEffort: string) => void
  /** 思考强度可选项（hub 版由调用方提供，默认无）。 */
  reasoningEffortOptions?: ReasoningEffortOption[]
}) {
  const llmDropdownOptions = llmProfiles.map((profile) => ({
    value: profile.id,
    label: `${profile.id} · ${profile.model}`,
  }))
  // 选中后只显示 configId(下拉列表仍展示「configId · model」便于辨识)。
  const renderSelectedModelLabel = (value?: string) => {
    if (value == null) return undefined
    // 选项 value 即 configId(profile.id),选中后直接展示 configId。
    return value
  }
  const reasoningEffortDropdownOptions = reasoningEffortOptions.map((option) => ({
    value: option.value,
    // 下拉列表里展示「中文 · 英文 value」，与模型选择器「configId · 模型」同构。
    label: `${option.label} · ${option.value}`,
  }))
  return (
    <div
      className="task-launcher-compact-toolbar"
      style={{
        ...composerControlsStyle,
        display: 'flex',
        // 模型配置 / 思考强度始终同一行（移动端也不换行），空间不足由下拉自身收缩。
        flexWrap: 'nowrap',
        alignItems: 'center',
        minWidth: 0,
      }}
    >
      <div className="task-launcher-compact-control" style={compactAutoControlStyle}>
        <Select
          {...sharedSelectProps}
          value={selectedLlmConfigId || undefined}
          suffixIcon={selectedLlmConfigId ? null : undefined}
          placeholder="请选择模型配置"
          labelRender={(info) => renderSelectedModelLabel(info.value as string)}
          onChange={(value) => onSelectLlmConfig(value as string)}
          options={llmDropdownOptions}
        />
      </div>
      {reasoningEffortDropdownOptions.length > 0 && onSelectReasoningEffort && (
        <div className="task-launcher-compact-control" style={compactAutoControlStyle}>
          <Select
            {...sharedSelectProps}
            value={selectedReasoningEffort || undefined}
            suffixIcon={selectedReasoningEffort ? null : undefined}
            placeholder="思考强度"
            onChange={(value) => onSelectReasoningEffort(value as string)}
            options={reasoningEffortDropdownOptions}
          />
        </div>
      )}
    </div>
  )
}

const composerControlsStyle: React.CSSProperties = {
  marginBottom: 0,
  gap: 8,
  flex: '0 0 auto',
}

const compactAutoControlStyle: React.CSSProperties = {
  flex: '0 0 auto',
  minWidth: 0,
}
