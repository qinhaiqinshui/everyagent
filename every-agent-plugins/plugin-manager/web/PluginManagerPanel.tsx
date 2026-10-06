/**
 * 侧边栏「扩展」面板（VSCode 扩展视图风格列表）。
 *
 * 只负责列表：搜索 + 分组（内置/外部）+ 扩展行（图标 + 名称 + 描述 + 作者/版本/来源行
 * + 启用开关）。点击行不再在面板内展开详情，而是经 ctx.ui.openPluginTab 打开
 * `extension-detail` 工作区标签页（同一插件恒为同一标签页，重复点击聚焦——对标
 * VSCode 在编辑器区打开扩展详情页）。
 *
 * 数据与动作来自共享 pluginStore（与详情标签页同源，切换/卸载后两处视图自动同步）。
 * 安装入口（.eap 上传）与「重新加载」保留在面板工具栏。
 */
import React from 'react'
import { Input, Switch, Button, Tag, Typography, Alert, Spin, Empty } from 'antd'
import { getUi } from './index'
import {
  usePluginDirectory,
  ensureDirectoryLoaded,
  togglePlugin,
  installEapFile,
  reloadPage,
  reloadDirectory,
  clearError,
  clearInstallResult,
  type PluginEntry,
} from './pluginStore'
import { PluginIcon } from './PluginIcon'
import { SearchIcon, ReloadIcon, InstallIcon } from './icons'
import { EXTENSION_DETAIL_TAB_TYPE } from './tabTypeKey'

const { Text } = Typography

/** 来源显示文案。 */
function sourceLabel(source: string): string {
  return source === 'builtin' ? '内置' : source === 'external' ? '外部' : source
}

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

/** 点击扩展行：打开（或聚焦）该插件的详情标签页。 */
function openDetailTab(plugin: PluginEntry): void {
  const ui = getUi()
  if (!ui) return
  ui.openPluginTab(EXTENSION_DETAIL_TAB_TYPE, { id: plugin.id }, plugin.name)
}

/** 单个扩展行（VSCode 扩展视图布局：图标 + 名称/描述/作者行 + 右侧开关）。 */
function PluginRow({
  plugin,
  disabled,
  reloadNeeded,
  onToggle,
  toggling,
}: {
  plugin: PluginEntry
  disabled: boolean
  reloadNeeded: boolean
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
      onClick={() => openDetailTab(plugin)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          openDetailTab(plugin)
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
      <PluginIcon size={44} pluginId={plugin.id} iconPath={plugin.icon} alt={plugin.name} />
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

/** 侧边栏扩展面板容器。 */
const PluginManagerPanel: React.FC = () => {
  const directory = usePluginDirectory()
  const [search, setSearch] = React.useState('')
  const fileInputRef = React.useRef<HTMLInputElement | null>(null)

  // 挂载时确保目录已加载（幂等）
  React.useEffect(() => {
    ensureDirectoryLoaded()
  }, [])

  const handleToggle = React.useCallback((pluginId: string, nextEnabled: boolean) => {
    void togglePlugin(pluginId, nextEnabled)
  }, [])

  const hasReloadNeeded = Object.values(directory.reloadNeeded).some(Boolean)

  // 搜索过滤（含卸载隐藏）
  const visiblePlugins = React.useMemo(
    () => directory.plugins.filter((p) => !directory.uninstalledIds.includes(p.id)),
    [directory.plugins, directory.uninstalledIds],
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
  const disabledSet = React.useMemo(() => new Set(directory.disabledIds), [directory.disabledIds])

  const renderRow = (p: PluginEntry) => (
    <PluginRow
      key={p.id}
      plugin={p}
      disabled={disabledSet.has(p.id)}
      reloadNeeded={!!directory.reloadNeeded[p.id]}
      onToggle={handleToggle}
      toggling={directory.togglingId === p.id}
    />
  )

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
            void installEapFile(file)
          }}
        />
        <Button
          size="small"
          icon={<InstallIcon />}
          disabled={directory.installing}
          onClick={() => fileInputRef.current?.click()}
        >
          {directory.installing ? '安装中…' : '安装…'}
        </Button>
        <Button
          size="small"
          type={hasReloadNeeded ? 'primary' : 'default'}
          icon={<ReloadIcon />}
          onClick={reloadPage}
          title="部分插件状态已变更，重新加载页面后生效"
        >
          重新加载
        </Button>
      </div>

      {/* 错误提示 */}
      {directory.error && (
        <Alert
          message={directory.error}
          type="error"
          showIcon
          closable
          onClose={clearError}
          action={<Button size="small" onClick={reloadDirectory}>重试</Button>}
          style={{ margin: '8px 12px', borderRadius: 6 }}
        />
      )}

      {/* 安装结果提示 */}
      {directory.installResult && (
        <Alert
          message={directory.installResult.text}
          type={directory.installResult.type}
          showIcon
          closable
          onClose={clearInstallResult}
          style={{ margin: '8px 12px', borderRadius: 6 }}
        />
      )}

      {/* 列表（点击行打开详情标签页） */}
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto', paddingTop: 2 }}>
        {directory.loading ? (
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
                {builtinPlugins.map(renderRow)}
              </>
            )}
            {externalPlugins.length > 0 && (
              <>
                <GroupHeader title="外部" count={externalPlugins.length} />
                {externalPlugins.map(renderRow)}
              </>
            )}
          </>
        )}
      </div>
    </div>
  )
}

export default PluginManagerPanel
