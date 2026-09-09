/**
 * 草稿工作区选择器(多工作区并行,架构 §5.9/D16):注册表下拉 + 弹窗逐层浏览选目录。
 * 任务必须挂靠工作区(worker 端 task.run 新建时强校验),未选择时提交按钮禁用。
 *
 * 「浏览选择目录」不要求手输绝对路径:弹窗从盘符开始逐层浏览,
 * 选了父目录后再向后端请求子目录展示(方案 B:依赖运行 worker 进程文件系统权限)。
 *
 * 延迟注册:浏览确认只把目录记为待选(不上报 workspaces.add),避免多次浏览
 * 留下一堆空工作区;真正注册发生在任务创建时(worker task.run resolve 即注册)。
 */
import React from 'react'
import { Select } from 'antd'
import WorkspaceBrowserModal from '@/components/shared/WorkspaceBrowserModal'
import type { WorkspaceEntry } from '@/hub/workspaceRegistry'

interface TaskWorkspacePickerProps {
  workspaces: WorkspaceEntry[]
  selected: string
  onSelect?: (root: string) => void
  /** 浏览弹窗确认待选目录(尚未注册;注册延迟到任务创建时的 task.run)。 */
  onPickExternal?: (path: string) => void
  /** 浏览目录定向 worker(新建草稿所选 worker;缺省为空时浏览接口抛「请先选择 worker」)。 */
  workerId?: string
}

/** 下拉末尾「浏览并注册新工作区」哨兵值（非真实 workspace root）。 */
const BROWSE_WORKSPACE_VALUE = '__browse_workspace__'

/** 选择器选项里展示的短名:取路径末段(盘符根/斜杠根退化为全路径)。 */
function displayRootLabel(root: string): string {
  const trimmed = root.replace(/[\\/]+$/, '')
  return trimmed.split(/[\\/]/).pop() || trimmed
}

export default function TaskWorkspacePicker({ workspaces, selected, onSelect, onPickExternal, workerId }: TaskWorkspacePickerProps) {
  const [browserOpen, setBrowserOpen] = React.useState(false)
  const inRegistry = workspaces.some((entry) => entry.root === selected)

  return (
    <div className="task-workspace-picker">
      <div className="task-workspace-picker__row" style={{ display: 'flex', minWidth: 'fit-content' }}>
        <Select
          className="task-workspace-picker__select"
          bordered={false}
          suffixIcon={inRegistry ? null : undefined}
          listHeight={100000}
          dropdownMatchSelectWidth={false}
          style={{ minWidth: 'fit-content' }}
          value={selected || undefined}
          placeholder={workspaces.length ? '选择工作区…' : '工作区加载中…'}
          onChange={(value) => {
            if (value === BROWSE_WORKSPACE_VALUE) {
              setBrowserOpen(true)
              return
            }
            if (value) onSelect?.(value as string)
          }}
          // onChange 只在选中值「变化」时触发:一旦浏览弹窗未确认被取消,
          // rc-select 内部值会停留在哨兵值,再次点击「＋ 新工作区」将因值未变化
          // 而不触发 onChange,弹窗无法重新打开。onSelect 每次点击选项都触发,
          // 用它兜底保证「再次点击新建工作区」总能重新打开目录浏览。
          onSelect={(value) => {
            if (value === BROWSE_WORKSPACE_VALUE) {
              setBrowserOpen(true)
            }
          }}
          options={[
            ...workspaces.map((entry) => ({
              value: entry.root,
              label: displayRootLabel(entry.root),
              title: entry.root,
            })),
            // 待选未注册目录(延迟注册):补一个选项让当前选择可见,发送时才落注册表。
            ...(selected && !inRegistry ? [{
              value: selected,
              label: `${displayRootLabel(selected)}（未注册）`,
              title: selected,
            }] : []),
            { value: BROWSE_WORKSPACE_VALUE, label: '＋ 新工作区' },
          ]}
        />
      </div>
      <WorkspaceBrowserModal
        open={browserOpen}
        workerId={workerId}
        title="选择工作区目录"
        onCancel={() => setBrowserOpen(false)}
        onConfirm={(path) => {
          onPickExternal?.(path)
          setBrowserOpen(false)
        }}
      />
    </div>
  )
}
