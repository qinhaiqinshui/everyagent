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
import { Select, Modal, Breadcrumb, List, theme } from 'antd'
import { FolderIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { Button } from '@/components/shared/ui'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
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

/** 把绝对路径拆成面包屑分段(每段带累积路径)。 */
function splitCrumb(path: string): Array<{ path: string; name: string }> {
  const norm = path.replace(/[\\/]+$/, '')
  if (!norm) return []
  const parts = norm.split(/[\\/]/).filter(Boolean)
  const out: Array<{ path: string; name: string }> = []
  let acc = ''
  for (const part of parts) {
    acc = acc ? `${acc}/${part}` : part
    out.push({ path: acc, name: part })
  }
  return out
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
        onCancel={() => setBrowserOpen(false)}
        onConfirm={(path) => {
          onPickExternal?.(path)
          setBrowserOpen(false)
        }}
      />
    </div>
  )
}

/**
 * 逐层目录浏览弹窗:第一层显示盘符,选父目录后请求子目录展示;底部「选择此目录」确认。
 * 确认只回传目录(不注册),注册延迟到任务创建时的 task.run。
 */
function WorkspaceBrowserModal({
  open,
  workerId,
  onCancel,
  onConfirm,
}: {
  open: boolean
  workerId?: string
  onCancel: () => void
  onConfirm: (absPath: string) => void
}) {
  const { token } = theme.useToken()
  const [currentPath, setCurrentPath] = React.useState('')
  const [children, setChildren] = React.useState<Array<{ path: string; name: string }>>([])
  const [loading, setLoading] = React.useState(false)
  const [error, setError] = React.useState('')

  const loadDir = React.useCallback(async (dirPath: string) => {
    if (!workerId) {
      setError('请先选择 worker')
      setChildren([])
      setCurrentPath('')
      setLoading(false)
      return
    }
    setLoading(true)
    setError('')
    try {
      const subs = dirPath
        ? await workspaceGateway.browseDir(workerId, dirPath)
        : await workspaceGateway.browseRoots(workerId)
      setChildren(subs)
      setCurrentPath(dirPath)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '读取目录失败(可能无权限)')
    } finally {
      setLoading(false)
    }
  }, [workerId])

  // 弹窗打开时加载第一层盘符;关闭时重置。
  React.useEffect(() => {
    if (open) {
      void loadDir('')
    } else {
      setCurrentPath('')
      setChildren([])
      setError('')
    }
  }, [open, loadDir])

  const crumbs = splitCrumb(currentPath)

  return (
    <Modal
      title="选择工作区目录"
      open={open}
      onCancel={onCancel}
      footer={[
        <Button key="cancel" variant="ghost" size="sm" onClick={onCancel}>
          取消
        </Button>,
        <Button key="ok" variant="primary" size="sm" disabled={!currentPath} onClick={() => onConfirm(currentPath)}>
          选择此目录
        </Button>,
      ]}
      width={520}
      style={{ top: 48 }}
      destroyOnClose
    >
      <div style={{ marginBottom: token.paddingXS }}>
        <Breadcrumb
          items={([
            { title: '此电脑' },
            ...crumbs.map((c, idx) => ({
              title:
                idx === crumbs.length - 1 ? (
                  c.name
                ) : (
                  <a
                    key={c.path}
                    onClick={() => {
                      void loadDir(c.path)
                    }}
                  >
                    {c.name}
                  </a>
                ),
            })),
          ] as Array<{ title: React.ReactNode }>)}
        />
      </div>
      <div
        style={{
          height: 240,
          overflowY: 'auto',
          border: `1px solid ${token.colorBorderSecondary}`,
          borderRadius: token.borderRadius,
          padding: token.paddingXXS,
        }}
      >
        {error ? (
          <div style={{ padding: token.paddingSM, color: token.colorError, fontSize: token.fontSizeSM }}>{error}</div>
        ) : loading ? (
          <div style={{ padding: token.paddingSM, color: token.colorTextTertiary, fontSize: token.fontSizeSM }}>
            加载中…
          </div>
        ) : children.length === 0 ? (
          <div style={{ padding: token.paddingSM, color: token.colorTextTertiary, fontSize: token.fontSizeSM }}>
            该目录下没有子目录
          </div>
        ) : (
          <List
            dataSource={children}
            renderItem={(item) => (
              <List.Item
                style={{ cursor: 'pointer', padding: `${token.paddingXS}px ${token.paddingSM}px` }}
                onClick={() => void loadDir(item.path)}
              >
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: token.paddingXS, width: '100%' }}>
                  <FolderIcon size={14} />
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {item.name}
                  </span>
                  <ChevronRightIcon size={10} style={{ color: token.colorTextTertiary }} />
                </span>
              </List.Item>
            )}
          />
        )}
      </div>
      <div style={{ marginTop: token.paddingXS, fontSize: token.fontSizeSM, color: token.colorTextTertiary }}>
        当前选择：{currentPath || '（请先进入一个目录）'}
      </div>
    </Modal>
  )
}
