import React from 'react'
import { Modal, Breadcrumb, List, theme } from 'antd'
import { FolderIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { Button } from '@/components/shared/ui'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'

/**
 * 逐层目录浏览弹窗(worker 侧 fs.browse):第一层显示盘符/根,选父目录后请求子目录展示;
 * 底部「选择此目录」确认。用于「新建工作区选目录」与「缺失工作区纠正路径」两处。
 * 确认只回传目录绝对路径,是否注册由调用方决定。
 */
export default function WorkspaceBrowserModal({
  open,
  workerId,
  title = '选择目录',
  confirmLabel = '选择此目录',
  onCancel,
  onConfirm,
}: {
  open: boolean
  workerId?: string
  title?: string
  confirmLabel?: string
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
      title={title}
      open={open}
      onCancel={onCancel}
      footer={[
        <Button key="cancel" variant="ghost" size="sm" onClick={onCancel}>
          取消
        </Button>,
        <Button key="ok" variant="primary" size="sm" disabled={!currentPath} onClick={() => onConfirm(currentPath)}>
          {confirmLabel}
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
