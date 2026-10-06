/**
 * 插件管理面板组件（VSCode 扩展视图风格）。
 *
 * 结构分两层，对标 VSCode 的 Extensions 视图：
 * - 列表层：搜索 + 分组（内置/外部）+ VSCode 风格扩展行——左侧图标（未配置 icon 时
 *   统一用默认扩展图标）、名称、描述、作者/版本/来源行、右侧启用开关；点击行进入详情。
 * - 详情层：头部像 VSCode 扩展详情页（大图标 + 名称 + 作者/版本/来源 + 描述 + 分类标签
 *   + 启用/禁用、卸载动作 + 元信息与资源链接），下半区渲染插件目录下的 readme.md。
 *
 * 数据链路：
 * - 列表：worker `plugin.list` RPC（含 #15 的 `status` 与展示元数据
 *   `icon/repository/license/homepage/categories`）。
 * - 启用/禁用：`plugin.enable` / `plugin.disable`；切换成功后行内提示「需要重新加载」。
 * - 卸载：`plugin.uninstall`（仅外部插件），成功后本地隐藏该行（重启 worker 后从目录消失）。
 * - README：`plugin.webSource` 读插件目录 `readme.md`（worker 侧大小写不敏感回退，
 *   Linux 上也能读到 `README.md`）；README 内相对路径图片经 `plugin.asset` 转 data URL。
 * - 安装：选定 .eap 文件 → 经 `fs.write` 上传到工作区暂存目录 → 调 `plugin.install`
 *   RPC（worker 机器本地路径）→ 清理暂存 → 刷新列表（known-issues #19）。
 * - plugin-manager 自身永不可禁用/卸载（动作区显示「必需」）。
 * - worker 不可达时显示错误提示，不切换开关状态。
 */
import React from 'react'
import { Input, Switch, Button, Tag, Typography, Alert, Spin, Empty, Popconfirm } from 'antd'
import { getSdk } from './index'
import { usePluginIcon } from './pluginAssets'
import Markdown, { type MarkdownImageResolver } from './Markdown'
import {
  DefaultPluginIcon,
  SearchIcon,
  ReloadIcon,
  InstallIcon,
  BackIcon,
  UninstallIcon,
} from './icons'

const { Text } = Typography

/** 安装包上传的大小上限（base64 膨胀约 4/3，32MB 原始字节 ≈ 43MB wire 载荷）。 */
const MAX_INSTALL_BYTES = 32 * 1024 * 1024

/** .eap 暂存目录（工作区内隐藏目录，工作区相对路径，无前导 `/`）。 */
const INSTALL_STAGING_DIR = '.everyagent/plugin-install'

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

/** 把相对路径归一为插件目录内 posix 风格路径（处理 ./ 与 ../ 段）。 */
function normalizePluginRelativePath(raw: string): string {
  const parts: string[] = []
  for (const seg of raw.replace(/\\/g, '/').split('/')) {
    if (!seg || seg === '.') continue
    if (seg === '..') {
      parts.pop()
      continue
    }
    parts.push(seg)
  }
  return parts.join('/')
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
  /** 展示元数据（扩展面板 VSCode 风格列表/详情页用）。 */
  icon?: string
  repository?: string
  license?: string
  homepage?: string
  categories?: string[]
}

/** plugin.list RPC 返回格式。 */
interface PluginListResult {
  plugins: PluginEntry[]
  disabledIds: string[]
}

/** plugin.webSource RPC 返回格式（README 文本）。 */
interface PluginWebSourceResult {
  content?: string
}

/** 行级「需要重新加载」状态：pluginId → boolean。 */
type ReloadNeededMap = Record<string, boolean>

/** 来源显示文案。 */
function sourceLabel(source: string): string {
  return source === 'builtin' ? '内置' : source === 'external' ? '外部' : source
}

/** ── 插件图标（icon 声明 → plugin.asset；否则默认扩展图标） ───────────────── */

function PluginIcon({ size, plugin }: { size: number; plugin: PluginEntry }) {
  const sdk = getSdk()
  const { dataUrl } = usePluginIcon(sdk, sdk?.workerId ?? '', plugin.id, plugin.icon)
  if (dataUrl) {
    return (
      <img
        src={dataUrl}
        alt={plugin.name}
        style={{
          width: size,
          height: size,
          borderRadius: Math.max(4, Math.round(size * 0.13)),
          border: '1px solid var(--border-light)',
          objectFit: 'cover',
          flexShrink: 0,
          display: 'block',
          background: 'var(--bg-tertiary)',
        }}
      />
    )
  }
  return <DefaultPluginIcon size={size} />
}

/** ── 列表层：分组标题 + VSCode 风格扩展行 ────────────────────────────────── */

/** 插件分组标题。 */
function GroupHeader({ title, count }: { title: string; count: number }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '8px 12px 4px',
        fontWeight: 600,
        fontSize: 11,
        color: 'var(--text-secondary, #888)',
        textTransform: 'uppercase',
        letterSpacing: '0.06em',
      }}
    >
      <span>{title}</span>
      <span style={{ fontWeight: 400, color: 'var(--text-faint)' }}>{count}</span>
    </div>
  )
}

/** 单个扩展行（VSCode 扩展视图布局：图标 + 名称/描述/作者行 + 右侧开关）。 */
function PluginRow({
  plugin,
  disabled,
  reloadNeeded,
  onOpen,
  onToggle,
  toggling,
}: {
  plugin: PluginEntry
  disabled: boolean
  reloadNeeded: boolean
  onOpen: (pluginId: string) => void
  onToggle: (pluginId: string, nextEnabled: boolean) => void
  toggling: boolean
}) {
  const isSelf = plugin.id === 'plugin-manager'
  const isFailed = !!plugin.status?.startsWith('激活失败')
  const isDeclarative = plugin.status === '声明式插件'

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onOpen(plugin.id)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onOpen(plugin.id)
        }
      }}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '8px 10px 8px 12px',
        borderRadius: 6,
        margin: '0 6px',
        cursor: 'pointer',
        transition: 'background 0.12s ease',
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.background = 'var(--bg-hover)'
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.background = 'transparent'
      }}
    >
      <PluginIcon size={44} plugin={plugin} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <Text
            ellipsis
            style={{ fontWeight: 600, fontSize: 13, flex: '0 1 auto' }}
            title={plugin.name}
          >
            {plugin.name}
          </Text>
          {isFailed && (
            <Tag color="error" style={{ fontSize: 10, lineHeight: '16px', margin: 0 }} title={plugin.status}>
              加载失败
            </Tag>
          )}
          {reloadNeeded && (
            <Tag color="warning" style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>
              需重新加载
            </Tag>
          )}
        </div>
        {plugin.description && (
          <div
            style={{
              fontSize: 12,
              color: 'var(--text-secondary)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              marginTop: 1,
            }}
            title={plugin.description}
          >
            {plugin.description}
          </div>
        )}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 4,
            fontSize: 11,
            color: 'var(--text-muted)',
            marginTop: 2,
            overflow: 'hidden',
            whiteSpace: 'nowrap',
          }}
        >
          {plugin.author && <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{plugin.author}</span>}
          {plugin.author && <span>·</span>}
          <span>v{plugin.version}</span>
          <span>·</span>
          <span>{sourceLabel(plugin.source)}</span>
          {!plugin.hasMain && plugin.hasWebMain && (
            <Tag style={{ fontSize: 10, lineHeight: '14px', margin: 0, padding: '0 4px' }}>Web</Tag>
          )}
          {disabled && !isFailed && (
            <span style={{ color: 'var(--text-faint)' }}>· 已禁用</span>
          )}
          {isDeclarative && (
            <span style={{ color: 'var(--text-faint)' }}>· 声明式</span>
          )}
        </div>
      </div>
      {isSelf ? (
        <Tag color="blue" style={{ fontSize: 10, margin: 0 }}>核心</Tag>
      ) : (
        <span onClick={(e) => e.stopPropagation()}>
          <Switch
            size="small"
            checked={!disabled}
            loading={toggling}
            onChange={(checked) => onToggle(plugin.id, checked)}
          />
        </span>
      )}
    </div>
  )
}

/** ── 详情层：头部（VSCode 扩展详情头）+ README ───────────────────────────── */

/** 详情页元信息行：label + value。 */
function MetaItem({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 2 }}>{label}</div>
      <div style={{ fontSize: 12.5, color: 'var(--text-primary)', wordBreak: 'break-all' }}>{children}</div>
    </div>
  )
}

/** 扩展详情页：头部插件信息 + README 渲染。 */
function PluginDetail({
  plugin,
  disabled,
  reloadNeeded,
  onBack,
  onToggle,
  onUninstall,
  toggling,
  uninstalling,
}: {
  plugin: PluginEntry
  disabled: boolean
  reloadNeeded: boolean
  onBack: () => void
  onToggle: (pluginId: string, nextEnabled: boolean) => void
  onUninstall: (pluginId: string) => void
  toggling: boolean
  uninstalling: boolean
}) {
  const sdk = getSdk()
  const [readme, setReadme] = React.useState<string | null>(null)
  const [readmeLoading, setReadmeLoading] = React.useState(true)
  const isSelf = plugin.id === 'plugin-manager'
  const isExternal = plugin.source !== 'builtin'
  const isFailed = !!plugin.status?.startsWith('激活失败')

  // README 拉取：plugin.webSource 读插件目录 readme.md（worker 侧大小写不敏感回退）。
  React.useEffect(() => {
    if (!sdk) {
      setReadmeLoading(false)
      return
    }
    let cancelled = false
    setReadmeLoading(true)
    sdk.rpc(sdk.workerId, 'plugin.webSource', {
      pluginId: plugin.id,
      path: 'readme.md',
    }).then((result) => {
      if (cancelled) return
      setReadme(((result as PluginWebSourceResult)?.content ?? '').trim() || null)
    }).catch(() => {
      if (!cancelled) setReadme(null)
    }).finally(() => {
      if (!cancelled) setReadmeLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [sdk, plugin.id])

  // README 内相对路径图片 → plugin.asset data URL；http(s)/data 原样直出。
  const resolveImage = React.useCallback<MarkdownImageResolver>(async (src) => {
    if (/^(https?:)?\/\//i.test(src) || src.startsWith('data:')) return src
    if (!sdk) return null
    try {
      const result = (await sdk.rpc(sdk.workerId, 'plugin.asset', {
        pluginId: plugin.id,
        path: normalizePluginRelativePath(src),
      })) as { mime?: string; contentBase64?: string }
      if (result?.mime && result?.contentBase64) {
        return `data:${result.mime};base64,${result.contentBase64}`
      }
      return null
    } catch {
      return null
    }
  }, [sdk, plugin.id])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {/* 详情工具栏：返回 + 名称 */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          padding: '6px 10px',
          borderBottom: '1px solid var(--border)',
        }}
      >
        <Button
          size="small"
          type="text"
          icon={<BackIcon />}
          onClick={onBack}
          aria-label="返回扩展列表"
        />
        <Text ellipsis style={{ fontSize: 12.5, fontWeight: 600, minWidth: 0 }} title={plugin.name}>
          {plugin.name}
        </Text>
      </div>

      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
        {/* 头部：VSCode 扩展详情头（图标 + 标题/作者/描述/标签 + 动作） */}
        <div
          style={{
            padding: '14px 14px 12px',
            background: 'var(--bg-soft)',
            borderBottom: '1px solid var(--border)',
          }}
        >
          <div style={{ display: 'flex', gap: 14, alignItems: 'flex-start', flexWrap: 'wrap', rowGap: 10 }}>
            <PluginIcon size={88} plugin={plugin} />
            <div style={{ flex: '1 1 160px', minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Text ellipsis style={{ fontSize: 17, fontWeight: 600, minWidth: 0 }} title={plugin.name}>
                  {plugin.name}
                </Text>
                {isFailed && (
                  <Tag color="error" style={{ margin: 0 }} title={plugin.status}>加载失败</Tag>
                )}
              </div>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  fontSize: 12,
                  color: 'var(--text-secondary)',
                  marginTop: 3,
                  flexWrap: 'wrap',
                }}
              >
                {plugin.author && <span style={{ fontWeight: 500 }}>{plugin.author}</span>}
                <span>·</span>
                <span>v{plugin.version}</span>
                <span>·</span>
                <span>{sourceLabel(plugin.source)}</span>
                {!plugin.hasMain && plugin.hasWebMain && (
                  <Tag style={{ fontSize: 10, lineHeight: '16px', margin: 0, padding: '0 4px' }}>Web</Tag>
                )}
                {plugin.hasMain && plugin.hasWebMain && (
                  <Tag style={{ fontSize: 10, lineHeight: '16px', margin: 0, padding: '0 4px' }}>Java + Web</Tag>
                )}
              </div>
              {plugin.description && (
                <div style={{ fontSize: 12.5, color: 'var(--text-secondary)', marginTop: 6, lineHeight: 1.6 }}>
                  {plugin.description}
                </div>
              )}
              {(plugin.categories?.length ?? 0) > 0 && (
                <div style={{ display: 'flex', gap: 4, marginTop: 8, flexWrap: 'wrap' }}>
                  {plugin.categories!.map((c) => (
                    <Tag key={c} style={{ fontSize: 11, margin: 0 }}>{c}</Tag>
                  ))}
                </div>
              )}
            </div>
            {/* 动作区：启用/禁用 + 卸载（对标 VSCode Install/Uninstall 按钮位） */}
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 6, flexShrink: 0 }}>
              {isSelf ? (
                <Tag color="blue" style={{ margin: 0 }}>必需</Tag>
              ) : (
                <>
                  <Button
                    size="small"
                    type={!disabled ? 'default' : 'primary'}
                    loading={toggling}
                    onClick={() => onToggle(plugin.id, disabled)}
                  >
                    {disabled ? '启用' : '禁用'}
                  </Button>
                  {isExternal && (
                    <Popconfirm
                      title="卸载该插件？"
                      description="将从插件目录删除，重启 worker 后完全生效。"
                      okText="卸载"
                      okButtonProps={{ danger: true }}
                      cancelText="取消"
                      onConfirm={() => onUninstall(plugin.id)}
                    >
                      <Button size="small" danger ghost icon={<UninstallIcon />} loading={uninstalling}>
                        卸载
                      </Button>
                    </Popconfirm>
                  )}
                </>
              )}
              {reloadNeeded && (
                <Tag color="warning" style={{ fontSize: 10, margin: 0 }}>需重新加载</Tag>
              )}
            </div>
          </div>

          {/* 元信息 + 资源链接 */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))',
              gap: '10px 14px',
              marginTop: 14,
            }}
          >
            <MetaItem label="标识符">{plugin.id}</MetaItem>
            <MetaItem label="版本">{plugin.version}</MetaItem>
            <MetaItem label="来源">{sourceLabel(plugin.source)}</MetaItem>
            <MetaItem label="状态">{plugin.status || (disabled ? '已禁用' : '已启用')}</MetaItem>
          </div>
          {(plugin.repository || plugin.homepage || plugin.license) && (
            <div style={{ display: 'flex', gap: 12, marginTop: 10, flexWrap: 'wrap', fontSize: 12 }}>
              {plugin.repository && (
                <a href={plugin.repository} target="_blank" rel="noreferrer noopener" style={{ color: 'var(--accent-blue)' }}>
                  仓库
                </a>
              )}
              {plugin.homepage && (
                <a href={plugin.homepage} target="_blank" rel="noreferrer noopener" style={{ color: 'var(--accent-blue)' }}>
                  主页
                </a>
              )}
              {plugin.license && (
                <span style={{ color: 'var(--text-secondary)' }}>许可：{plugin.license}</span>
              )}
            </div>
          )}
        </div>

        {/* README 区：插件目录 readme.md 渲染 */}
        <div style={{ padding: '12px 14px 20px' }}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 600,
              color: 'var(--text-secondary)',
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
              borderBottom: '1px solid var(--border)',
              paddingBottom: 6,
              marginBottom: 10,
            }}
          >
            README
          </div>
          {readmeLoading ? (
            <div style={{ display: 'flex', justifyContent: 'center', padding: '24px 0' }}>
              <Spin />
            </div>
          ) : readme ? (
            <Markdown source={readme} resolveImage={resolveImage} />
          ) : (
            <Empty
              description="该插件目录下没有 readme.md"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              style={{ padding: '12px 0' }}
            />
          )}
        </div>
      </div>
    </div>
  )
}

/** ── 面板容器 ────────────────────────────────────────────────────────────── */

const PluginManagerPanel: React.FC = () => {
  const [loading, setLoading] = React.useState(true)
  const [plugins, setPlugins] = React.useState<PluginEntry[]>([])
  const [disabledIds, setDisabledIds] = React.useState<string[]>([])
  const [error, setError] = React.useState<string | null>(null)
  const [search, setSearch] = React.useState('')
  const [reloadNeeded, setReloadNeeded] = React.useState<ReloadNeededMap>({})
  const [togglingId, setTogglingId] = React.useState<string | null>(null)
  const [uninstallingId, setUninstallingId] = React.useState<string | null>(null)
  const [uninstalledIds, setUninstalledIds] = React.useState<string[]>([])
  /** 详情页当前插件 id（null = 列表层）。 */
  const [selectedId, setSelectedId] = React.useState<string | null>(null)
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

  /** 卸载（仅外部插件）：成功后本地隐藏该行并回到列表（重启 worker 后从目录消失）。 */
  const handleUninstall = React.useCallback(
    async (pluginId: string) => {
      if (!sdk) return
      setUninstallingId(pluginId)
      try {
        await sdk.rpc(sdk.workerId, 'plugin.uninstall', { pluginId })
        setUninstalledIds((prev) => [...prev, pluginId])
        setReloadNeeded((prev) => ({ ...prev, [pluginId]: true }))
        setSelectedId(null)
      } catch (err) {
        setError(`卸载失败：${rpcErrorMessage(err)}`)
      } finally {
        setUninstallingId(null)
      }
    },
    [sdk],
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
        const absPath = `${workspaceRoot.replace(/[\\\/]+$/, '')}/${stagedPath}`
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

  // 搜索过滤（含卸载隐藏）
  const visiblePlugins = React.useMemo(
    () => plugins.filter((p) => !uninstalledIds.includes(p.id)),
    [plugins, uninstalledIds],
  )
  const filtered = React.useMemo(() => {
    if (!search.trim()) return visiblePlugins
    const q = search.toLowerCase().trim()
    return visiblePlugins.filter(
      (p) =>
        p.id.toLowerCase().includes(q) ||
        p.name.toLowerCase().includes(q) ||
        (p.description ?? '').toLowerCase().includes(q) ||
        (p.author ?? '').toLowerCase().includes(q),
    )
  }, [visiblePlugins, search])

  // 分组
  const builtinPlugins = filtered.filter((p) => p.source === 'builtin')
  const externalPlugins = filtered.filter((p) => p.source !== 'builtin')

  // 详情页当前插件（卸载后回列表；找不到时回退列表层）
  const selectedPlugin = selectedId ? plugins.find((p) => p.id === selectedId) ?? null : null
  React.useEffect(() => {
    if (selectedId && (!selectedPlugin || uninstalledIds.includes(selectedId))) {
      setSelectedId(null)
    }
  }, [selectedId, selectedPlugin, uninstalledIds])

  const disabledSet = React.useMemo(() => new Set(disabledIds), [disabledIds])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {/* 顶部工具栏：搜索 + 安装 + 重新加载 */}
      <div
        style={{
          display: 'flex',
          gap: 8,
          padding: '8px 12px',
          borderBottom: '1px solid var(--border)',
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
        <Button
          size="small"
          type={hasReloadNeeded ? 'primary' : 'default'}
          icon={<ReloadIcon />}
          onClick={handleReload}
          title="部分插件状态已变更，重新加载页面后生效"
        >
          重新加载
        </Button>
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

      {/* 内容区：列表层 / 详情层 */}
      {selectedPlugin ? (
        <PluginDetail
          plugin={selectedPlugin}
          disabled={disabledSet.has(selectedPlugin.id)}
          reloadNeeded={!!reloadNeeded[selectedPlugin.id]}
          onBack={() => setSelectedId(null)}
          onToggle={handleToggle}
          onUninstall={handleUninstall}
          toggling={togglingId === selectedPlugin.id}
          uninstalling={uninstallingId === selectedPlugin.id}
        />
      ) : (
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', paddingTop: 2 }}>
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
                      disabled={disabledSet.has(p.id)}
                      reloadNeeded={!!reloadNeeded[p.id]}
                      onOpen={setSelectedId}
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
                      disabled={disabledSet.has(p.id)}
                      reloadNeeded={!!reloadNeeded[p.id]}
                      onOpen={setSelectedId}
                      onToggle={handleToggle}
                      toggling={togglingId === p.id}
                    />
                  ))}
                </>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}

export default PluginManagerPanel
