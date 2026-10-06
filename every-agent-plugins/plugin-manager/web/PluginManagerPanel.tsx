/**
 * 插件管理面板组件。
 *
 * VSCode 扩展视图风格：列表 + 搜索 + 安装入口 + 启用/禁用开关 + 重新加载提示。
 *
 * - 从 worker `plugin.list` RPC 获取完整插件目录（含 #15 上线的 `status` 字段，
 *   激活失败的插件行内展示「加载失败」标识）
 * - 按内置 / 外部分组
 * - 每行：名称、版本、描述、启用状态开关
 * - 启用/禁用 → 调 `plugin.enable` / `plugin.disable` RPC
 * - 安装：选定 .eap 文件 → 经 `fs.write` 上传到工作区暂存目录 → 调
 *   `plugin.install` RPC（worker 机器本地路径）→ 清理暂存 → 刷新列表
 *   （known-issues #19；后端 RPC 原本就有，此处补产品化入口）
 * - 切换成功后行内显示「需要重新加载」提示（不立即生效）
 * - plugin-manager 自身永不可禁用（开关隐藏）
 * - worker 不可达时显示错误提示，不切换开关状态
 */
import React from 'react'
import { Input, Switch, Button, Tag, Typography, Alert, Spin, Empty } from 'antd'
import { getSdk } from './index'

const { Text } = Typography

/** 安装包上传的大小上限（base64 膨胀约 4/3，32MB 原始字节 ≈ 43MB wire 载荷）。 */
const MAX_INSTALL_BYTES = 32 * 1024 * 1024

/** .eap 暂存目录（工作区内隐藏目录，工作区相对路径，无前导 `/`）。 */
const INSTALL_STAGING_DIR = '.everyagent/plugin-install'

/** 搜索图标 SVG。 */
function SearchIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="7" cy="7" r="5" stroke="currentColor" strokeWidth="1.5" fill="none" />
      <path d="M11 11l4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

/** 重新加载图标 SVG。 */
function ReloadIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path
        d="M8 2a6 6 0 1 1-5.6 3.83.5.5 0 0 1 .94.34A5 5 0 1 0 8 3V5l3-2.5L8 0v2z"
        fill="currentColor"
        transform="translate(0 1)"
      />
    </svg>
  )
}

/** 感叹号图标 SVG。 */
function ExclamationIcon({ size = 12 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="8" cy="8" r="7" fill="currentColor" opacity="0.15" />
      <path d="M8 4v5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      <circle cx="8" cy="11.5" r="1" fill="currentColor" />
    </svg>
  )
}

/** 安装（下载入托盘）图标 SVG。 */
function InstallIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M8 1.5v7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      <path d="M5 6l3 3 3-3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M2 10.5v2A1.5 1.5 0 0 0 3.5 14h9a1.5 1.5 0 0 0 1.5-1.5v-2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

/** 把 RPC 错误归一为可展示文案（Error 取 message，对象取 message 字段，其余 String）。 */
function rpcErrorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  if (err && typeof err === 'object' && typeof (err as { message?: unknown }).message === 'string') {
    return (err as { message: string }).message
  }
  return String(err)
}

/** 读文件字节并转 base64（与宿主 workspaceGateway 同款分块拼接，避免超长参数栈溢出）。 */
async function fileToBase64(file: File): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer())
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}

/** plugin.list 返回的单个插件条目。 */
interface PluginEntry {
  id: string
  name: string
  version: string
  description?: string
  author?: string
  source: string
  active: boolean
  /** 加载期实际状态（#15 上线）：已激活 / 激活失败: … / 已禁用(未激活) 等。 */
  status?: string
  hasMain?: boolean
  hasWebMain?: boolean
}

/** plugin.list RPC 返回格式。 */
interface PluginListResult {
  plugins: PluginEntry[]
  disabledIds: string[]
}

/** 行级「需要重新加载」状态：pluginId → boolean。 */
type ReloadNeededMap = Record<string, boolean>

/** 插件分组标题。 */
function GroupHeader({ title, count }: { title: string; count: number }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '6px 12px',
        fontWeight: 600,
        fontSize: 12,
        color: 'var(--text-secondary, #888)',
        textTransform: 'uppercase',
        letterSpacing: '0.05em',
        borderBottom: '1px solid var(--border-color, rgba(255,255,255,0.06))',
      }}
    >
      <span>{title}</span>
      <span style={{ fontWeight: 400 }}>{count}</span>
    </div>
  )
}

/** 单个插件行。 */
function PluginRow({
  plugin,
  disabledIds,
  reloadNeeded,
  onToggle,
  toggling,
}: {
  plugin: PluginEntry
  disabledIds: string[]
  reloadNeeded: boolean
  onToggle: (pluginId: string, nextEnabled: boolean) => void
  toggling: boolean
}) {
  const isSelf = plugin.id === 'plugin-manager'
  const isDisabled = disabledIds.includes(plugin.id)
  // 开关状态：disabledIds 不含此插件 → 启用
  const switchChecked = !isDisabled
  // plugin-manager 自身永不可禁用 → 隐藏开关
  const showSwitch = !isSelf

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '8px 12px',
        borderBottom: '1px solid var(--border-color, rgba(255,255,255,0.06))',
      }}
    >
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <Text style={{ fontWeight: 500, fontSize: 13 }}>{plugin.name}</Text>
          {plugin.version && (
            <Text type="secondary" style={{ fontSize: 11 }}>
              v{plugin.version}
            </Text>
          )}
          {isSelf && <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>核心</Tag>}
          {!plugin.hasMain && plugin.hasWebMain && (
            <Tag style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>Web</Tag>
          )}
          {plugin.status?.startsWith('激活失败') && (
            <Tag
              color="error"
              style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}
              title={plugin.status}
            >
              加载失败
            </Tag>
          )}
        </div>
        {plugin.description && (
          <div
            style={{
              fontSize: 12,
              color: 'var(--text-secondary, #888)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
            title={plugin.description}
          >
            {plugin.description}
          </div>
        )}
        {reloadNeeded && (
          <div style={{ marginTop: 2 }}>
            <Tag icon={<ExclamationIcon />} color="warning" style={{ fontSize: 11, margin: 0 }}>
              需要重新加载
            </Tag>
          </div>
        )}
      </div>
      {showSwitch ? (
        <Switch
          size="small"
          checked={switchChecked}
          loading={toggling}
          onChange={(checked) => onToggle(plugin.id, checked)}
        />
      ) : (
        <Tag color="green" style={{ fontSize: 10, margin: 0 }}>必需</Tag>
      )}
    </div>
  )
}

const PluginManagerPanel: React.FC = () => {
  const [loading, setLoading] = React.useState(true)
  const [plugins, setPlugins] = React.useState<PluginEntry[]>([])
  const [disabledIds, setDisabledIds] = React.useState<string[]>([])
  const [error, setError] = React.useState<string | null>(null)
  const [search, setSearch] = React.useState('')
  const [reloadNeeded, setReloadNeeded] = React.useState<ReloadNeededMap>({})
  const [togglingId, setTogglingId] = React.useState<string | null>(null)
  const [installing, setInstalling] = React.useState(false)
  const [installResult, setInstallResult] = React.useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const fileInputRef = React.useRef<HTMLInputElement | null>(null)

  const sdk = getSdk()

  const fetchPlugins = React.useCallback(async () => {
    if (!sdk) {
      setError('插件 SDK 未初始化')
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const result = (await sdk.rpc(sdk.workerId, 'plugin.list', {})) as PluginListResult
      setPlugins(result?.plugins ?? [])
      setDisabledIds(result?.disabledIds ?? [])
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err)
      setError(`无法获取插件列表：${message}`)
    } finally {
      setLoading(false)
    }
  }, [sdk])

  React.useEffect(() => {
    fetchPlugins()
  }, [fetchPlugins])

  const handleToggle = React.useCallback(
    async (pluginId: string, nextEnabled: boolean) => {
      if (!sdk) return
      setTogglingId(pluginId)
      const prevDisabledIds = disabledIds
      try {
        if (nextEnabled) {
          // 启用：从 disabledIds 移除
          await sdk.rpc(sdk.workerId, 'plugin.enable', { pluginId })
          setDisabledIds((prev) => prev.filter((id) => id !== pluginId))
        } else {
          // 禁用：加入 disabledIds
          await sdk.rpc(sdk.workerId, 'plugin.disable', { pluginId })
          setDisabledIds((prev) => [...prev, pluginId])
        }
        // 切换成功 → 标记需要重新加载
        setReloadNeeded((prev) => ({ ...prev, [pluginId]: true }))
      } catch (err) {
        // RPC 失败 → 不切换状态，显示错误
        const message = err instanceof Error ? err.message : String(err)
        setError(`操作失败：${message}`)
        // 恢复状态（确保 UI 与服务端一致）
        setDisabledIds(prevDisabledIds)
      } finally {
        setTogglingId(null)
      }
    },
    [sdk, disabledIds],
  )

  const handleReload = React.useCallback(() => {
    location.reload()
  }, [])

  /**
   * 安装 .eap（known-issues #19）：浏览器选定文件 → fs.write 上传到工作区暂存目录
   * （`plugin.install` 只收 worker 机器本地路径，工作区是前端唯一可写的落点）→
   * 用暂存文件的机器绝对路径调 `plugin.install` 解包到插件目录 → 清理暂存 → 刷新列表。
   * 新插件重启 worker 后才会出现在 `plugin.list`（生效边界不变）。
   */
  const handleInstallFile = React.useCallback(
    async (file: File | undefined) => {
      if (!file || !sdk) return
      if (!file.name.toLowerCase().endsWith('.eap')) {
        setInstallResult({ type: 'error', text: '仅支持 .eap 插件包' })
        return
      }
      if (file.size > MAX_INSTALL_BYTES) {
        setInstallResult({
          type: 'error',
          text: `插件包过大（${(file.size / 1024 / 1024).toFixed(1)} MB），超过 ${MAX_INSTALL_BYTES / 1024 / 1024} MB 上限`,
        })
        return
      }
      const workspaceRoot = sdk.workspace.rootPath.trim()
      if (!workspaceRoot) {
        setInstallResult({ type: 'error', text: '无法确定工作区根路径，不能上传安装包' })
        return
      }
      setInstalling(true)
      setInstallResult(null)
      try {
        // 1) 上传：经 fs.write 落到工作区内暂存目录（fs.* 按工作区 jailed；worker 侧自动建父目录）
        const stagedPath = `${INSTALL_STAGING_DIR}/${Date.now()}-${file.name}`
        const fsWorkerId = sdk.workspace.workerIdOfRoot(workspaceRoot) ?? sdk.workerId
        await sdk.rpc(fsWorkerId, 'fs.write', {
          workspace: workspaceRoot,
          path: stagedPath,
          contentBase64: await fileToBase64(file),
        })
        // 2) 安装：把 worker 机器上的 .eap 绝对路径交给 plugin.install 解包
        const absPath = `${workspaceRoot.replace(/[\\/]+$/, '')}/${stagedPath}`
        const result = (await sdk.rpc(fsWorkerId, 'plugin.install', { path: absPath })) as { message?: string }
        setInstallResult({ type: 'success', text: result?.message ?? '插件已安装，重启 worker 后生效' })
        // 3) 清理暂存包（尽力而为，失败只留工作区残留文件）
        await sdk.rpc(fsWorkerId, 'fs.delete', { workspace: workspaceRoot, path: stagedPath }).catch(() => undefined)
        // 4) 刷新列表（新插件重启 worker 后出现；此处兜底拉齐既有条目的最新状态）
        await fetchPlugins()
      } catch (err) {
        setInstallResult({ type: 'error', text: `安装失败：${rpcErrorMessage(err)}` })
      } finally {
        setInstalling(false)
      }
    },
    [sdk, fetchPlugins],
  )

  const hasReloadNeeded = Object.values(reloadNeeded).some(Boolean)

  // 搜索过滤
  const filtered = React.useMemo(() => {
    if (!search.trim()) return plugins
    const q = search.toLowerCase().trim()
    return plugins.filter(
      (p) =>
        p.id.toLowerCase().includes(q) ||
        p.name.toLowerCase().includes(q) ||
        (p.description ?? '').toLowerCase().includes(q),
    )
  }, [plugins, search])

  // 分组
  const builtinPlugins = filtered.filter((p) => p.source === 'builtin')
  const externalPlugins = filtered.filter((p) => p.source !== 'builtin')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {/* 顶部工具栏：搜索 + 重新加载 */}
      <div
        style={{
          display: 'flex',
          gap: 8,
          padding: '8px 12px',
          borderBottom: '1px solid var(--border-color, rgba(255,255,255,0.06))',
          alignItems: 'center',
        }}
      >
        <Input
          size="small"
          allowClear
          placeholder="搜索插件..."
          prefix={<SearchIcon />}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ flex: 1 }}
        />
        {/* 安装入口：隐藏 file input（仅 .eap），选中即走上传 → plugin.install 流程 */}
        <input
          ref={fileInputRef}
          type="file"
          accept=".eap"
          style={{ display: 'none' }}
          onChange={(e) => {
            const file = e.target.files?.[0]
            e.target.value = '' // 复位以允许重复选择同一文件
            void handleInstallFile(file)
          }}
        />
        <Button
          size="small"
          icon={<InstallIcon />}
          disabled={installing}
          onClick={() => fileInputRef.current?.click()}
        >
          {installing ? '安装中…' : '安装…'}
        </Button>
        {hasReloadNeeded && (
          <Button size="small" type="primary" icon={<ReloadIcon />} onClick={handleReload}>
            重新加载
          </Button>
        )}
      </div>

      {/* 错误提示 */}
      {error && (
        <Alert
          message={error}
          type="error"
          showIcon
          closable
          onClose={() => setError(null)}
          style={{ margin: '8px 12px', borderRadius: 6 }}
        />
      )}

      {/* 安装结果提示 */}
      {installResult && (
        <Alert
          message={installResult.text}
          type={installResult.type}
          showIcon
          closable
          onClose={() => setInstallResult(null)}
          style={{ margin: '8px 12px', borderRadius: 6 }}
        />
      )}

      {/* 全局重新加载提示 */}
      {hasReloadNeeded && (
        <div
          style={{
            padding: '6px 12px',
            fontSize: 12,
            color: 'var(--accent-yellow, #d7a000)',
            background: 'var(--bg-yellow-faint, rgba(215,160,0,0.08))',
            borderBottom: '1px solid var(--border-color, rgba(255,255,255,0.06))',
          }}
        >
          部分插件状态已变更，重新加载后生效。
        </div>
      )}

      {/* 列表内容 */}
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        {loading ? (
          <div style={{ display: 'flex', justifyContent: 'center', padding: '32px 0' }}>
            <Spin />
          </div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: '32px 0' }}>
            <Empty description={search ? '未找到匹配的插件' : '暂无插件'} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          </div>
        ) : (
          <>
            {builtinPlugins.length > 0 && (
              <>
                <GroupHeader title="内置" count={builtinPlugins.length} />
                {builtinPlugins.map((p) => (
                  <PluginRow
                    key={p.id}
                    plugin={p}
                    disabledIds={disabledIds}
                    reloadNeeded={!!reloadNeeded[p.id]}
                    onToggle={handleToggle}
                    toggling={togglingId === p.id}
                  />
                ))}
              </>
            )}
            {externalPlugins.length > 0 && (
              <>
                <GroupHeader title="外部" count={externalPlugins.length} />
                {externalPlugins.map((p) => (
                  <PluginRow
                    key={p.id}
                    plugin={p}
                    disabledIds={disabledIds}
                    reloadNeeded={!!reloadNeeded[p.id]}
                    onToggle={handleToggle}
                    toggling={togglingId === p.id}
                  />
                ))}
              </>
            )}
          </>
        )}
      </div>
    </div>
  )
}

export default PluginManagerPanel
