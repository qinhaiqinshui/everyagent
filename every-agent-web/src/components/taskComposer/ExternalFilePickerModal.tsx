import React from 'react'
import { Modal, Breadcrumb, List, theme } from 'antd'
import { FolderIcon, FileIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import { Button } from '@/components/shared/ui'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import type { ExternalFileEntry } from '@/composerToken/externalFileToken'

/**
 * 「@ 弹窗 → 选择工作区外文件/文件夹」浏览弹窗(worker 侧 fs.browse includeFiles 形态)。
 *
 * 与 WorkspaceBrowserModal(新建工作区选目录)同源同风格,差异:
 * - 初始目录 = 工作区根(props.workspaceRoot,缺省取注册表首选根),而非盘符根列表;
 * - 「上一级」可逐级上跳并跨出工作区:当前目录为盘符根(如 C:\)时再上一级进入
 *   「根列表视图」(全部盘符,fs.browse 无 path 形态,isRoot=true);根列表视图再无上级;
 * - 可选文件:条目按 kind 渲染 Folder/File 图标,目录点击 = 进入,文件点击 = 选中回调;
 * - 底部「选择当前目录」把当前目录整体作为引用(根列表视图时禁用)。
 *
 * 降级:响应无 supportsFiles === true(老 worker)时只会有目录条目,kind 缺失按
 * directory 处理,即退化为纯目录选择器,不报错。
 *
 * 确认只回传条目(绝对路径/名称/kind),token 构造与关闭弹窗由调用方完成。
 */
export default function ExternalFilePickerModal({
  open,
  onClose,
  onPick,
  workspaceRoot,
  workerId,
}: {
  open: boolean
  onClose: () => void
  onPick: (entry: ExternalFileEntry) => void
  /** 初始目录(通常为任务工作区根);缺省用注册表首选根。 */
  workspaceRoot?: string
  /** 定向 RPC 的 worker;缺省按工作区根反查(与 @ 搜索的解析同源)。 */
  workerId?: string
}) {
  const { token } = theme.useToken()
  /** 当前目录绝对路径;空串表示根列表视图(全部盘符,再无上级)。 */
  const [currentPath, setCurrentPath] = React.useState('')
  const [entries, setEntries] = React.useState<Array<{ path: string; name: string; kind?: 'file' | 'directory' }>>([])
  const [loading, setLoading] = React.useState(false)
  const [error, setError] = React.useState('')
  /** 最近一次响应是否支持文件条目(老 worker 缺省 → 纯目录降级)。 */
  const [supportsFiles, setSupportsFiles] = React.useState(false)

  /** 解析定向 worker:显式 props 优先,否则按(兜底后的)工作区根反查。 */
  const resolveWorkerId = React.useCallback((): string => {
    if (workerId) return workerId
    const root = workspaceRoot?.trim() || workspaceRegistry.primaryRoot() || ''
    return root ? (workspaceRegistry.workerIdOfRoot(root) ?? '') : ''
  }, [workerId, workspaceRoot])

  const loadDir = React.useCallback(async (dirPath: string) => {
    const resolvedWorkerId = resolveWorkerId()
    if (!resolvedWorkerId) {
      setError('无法确定该工作区所属 worker(工作区未注册或 worker 离线)')
      setEntries([])
      setCurrentPath('')
      setLoading(false)
      return
    }
    setLoading(true)
    setError('')
    try {
      const res = await workspaceGateway.browseDirEntries(resolvedWorkerId, dirPath)
      setEntries(res.entries)
      setSupportsFiles(res.supportsFiles === true)
      // 目录视图以 worker 回显的规范化路径为准;根列表视图(空请求)保持空串。
      setCurrentPath(res.isRoot || !res.path ? '' : res.path)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '读取目录失败(可能无权限)')
    } finally {
      setLoading(false)
    }
  }, [resolveWorkerId])

  // 打开时落在工作区根;根缺省(无工作区但有 worker)时直接进根列表视图。
  React.useEffect(() => {
    if (open) {
      void loadDir(workspaceRoot?.trim() || workspaceRegistry.primaryRoot() || '')
    } else {
      setCurrentPath('')
      setEntries([])
      setError('')
      setLoading(false)
      setSupportsFiles(false)
    }
  }, [open, loadDir, workspaceRoot])

  const crumbs = splitCrumb(currentPath)
  // 上一级落点:还有祖先段 → 祖先目录;已是盘符根(仅 1 段)→ 根列表视图;
  // 根列表视图(currentPath 为空)无上级,按钮禁用。
  const parentPath = crumbs.length > 1 ? crumbs[crumbs.length - 2].path : ''
  const currentName = crumbs.length > 0 ? crumbs[crumbs.length - 1].name : currentPath

  return (
    <Modal
      title="选择工作区外文件/文件夹"
      open={open}
      onCancel={onClose}
      footer={[
        <Button key="cancel" variant="ghost" size="sm" onClick={onClose}>
          取消
        </Button>,
        <Button
          key="ok"
          variant="primary"
          size="sm"
          disabled={!currentPath}
          onClick={() => onPick({ absolutePath: currentPath, fileName: currentName, kind: 'directory' })}
        >
          选择当前目录
        </Button>,
      ]}
      width={520}
      style={{ top: 48 }}
      destroyOnClose
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: token.paddingXS, marginBottom: token.paddingXS }}>
        <div style={{ flex: 1, minWidth: 0 }}>
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
        <Button
          variant="ghost"
          size="sm"
          disabled={!currentPath}
          onClick={() => void loadDir(parentPath)}
        >
          上一级
        </Button>
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
        ) : entries.length === 0 ? (
          <div style={{ padding: token.paddingSM, color: token.colorTextTertiary, fontSize: token.fontSizeSM }}>
            {currentPath ? '该目录为空' : '未发现可用盘符'}
          </div>
        ) : (
          <List
            dataSource={entries}
            renderItem={(item) => {
              // 老 worker 条目无 kind:一律按目录处理(降级为目录选择器)。
              const isDir = (item.kind ?? 'directory') === 'directory'
              return (
                <List.Item
                  style={{ cursor: 'pointer', padding: `${token.paddingXS}px ${token.paddingSM}px` }}
                  onClick={() => {
                    if (isDir) {
                      void loadDir(item.path)
                    } else {
                      onPick({ absolutePath: item.path, fileName: item.name, kind: 'file' })
                    }
                  }}
                >
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: token.paddingXS, width: '100%' }}>
                    {isDir ? <FolderIcon size={14} /> : <FileIcon size={14} />}
                    <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {item.name}
                    </span>
                    {isDir ? <ChevronRightIcon size={10} style={{ color: token.colorTextTertiary }} /> : null}
                  </span>
                </List.Item>
              )
            }}
          />
        )}
      </div>
      <div style={{ marginTop: token.paddingXS, fontSize: token.fontSizeSM, color: token.colorTextTertiary }}>
        当前选择：{currentPath || '（请先进入一个目录）'}
        {currentPath && !supportsFiles ? '（当前 worker 仅支持选择目录）' : ''}
      </div>
    </Modal>
  )
}

/**
 * 把绝对路径拆成面包屑分段(每段带累积路径)。对齐 WorkspaceBrowserModal 的
 * splitCrumb,并补两处衔接:盘符段累积为 `C:/` 形态(与盘符根再上一级进根列表
 * 视图的判定衔接)、Unix 根保留前导 `/`(段路径始终可回传 fs.browse)。
 */
function splitCrumb(path: string): Array<{ path: string; name: string }> {
  const norm = path.replace(/[\\/]+$/, '')
  if (!norm) return []
  const unixRoot = path.startsWith('/')
  const parts = norm.split(/[\\/]/).filter(Boolean)
  const out: Array<{ path: string; name: string }> = []
  let acc = ''
  for (const part of parts) {
    if (!acc) {
      acc = /^[A-Za-z]:$/.test(part) ? `${part}/` : (unixRoot ? `/${part}` : part)
    } else {
      acc = `${acc}/${part}`
    }
    out.push({ path: acc, name: part })
  }
  return out
}
