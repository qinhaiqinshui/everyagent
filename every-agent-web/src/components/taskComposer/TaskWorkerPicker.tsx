/**
 * 草稿 worker 选择器(多 worker 显式归属):新建任务必须显式选择 worker,
 * 所选 worker 决定工作区/模型/slash/浏览目录的数据源(全链路按 worker 定向)。
 *
 * 候选列表已由父组件筛好「已连接可用」(online && enabled && hasApiKey && !error
 * && !connecting),本组件只做展示:显示 workerId;无候选时显示「暂无可用 worker」占位。
 */
import React from 'react'
import { Select } from 'antd'
import type { WorkerInfo } from '@/hub/session'

interface TaskWorkerPickerProps {
  workers: WorkerInfo[]
  selected: string
  onSelect?: (workerId: string) => void
}

export default function TaskWorkerPicker({ workers, selected, onSelect }: TaskWorkerPickerProps) {
  return (
    <div className="task-workspace-picker">
      <div className="task-workspace-picker__row" style={{ display: 'flex', minWidth: 'fit-content' }}>
        <Select
          className="task-workspace-picker__select"
          bordered={false}
          suffixIcon={selected ? null : undefined}
          listHeight={100000}
          dropdownMatchSelectWidth={false}
          style={{ minWidth: 'fit-content' }}
          value={selected || undefined}
          placeholder={workers.length ? '选择 worker…' : '暂无可用 worker'}
          onChange={(value) => {
            if (value) onSelect?.(value as string)
          }}
          options={workers.map((worker) => ({
            value: worker.workerId,
            label: worker.workerId,
          }))}
        />
      </div>
    </div>
  )
}
