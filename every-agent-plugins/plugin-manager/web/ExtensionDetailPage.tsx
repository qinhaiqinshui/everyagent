/**
 * 扩展详情标签页（VSCode 扩展详情页风格）。
 *
 * 由 index.ts 注册为 `extension-detail` 工作区标签类型，侧栏扩展面板点击行后经
 * ctx.ui.openPluginTab('extension-detail', { id }) 打开——同一插件恒为同一标签页
 * （宿主 openPluginTab 以 data.id 构造确定性 tab id，重复点击聚焦不重建）。
 *
 * 布局对标 VSCode 扩展详情页：
 * - 头部：大图标 + 名称 + 作者/版本/来源 + 描述 + 分类标签 + 启用/禁用、卸载动作
 *   + 标识符/版本/来源/状态元信息 + 仓库/主页/许可资源链接。
 * - 下半区：插件目录下 readme.md 渲染（plugin.webSource；README 内相对路径图片经
 *   plugin.asset 转 data URL）。
 *
 * 数据来自共享 pluginStore（与侧栏列表同源，启用/禁用/卸载后两处视图自动同步）；
 * 插件被卸载（从目录消失）时自动关闭本标签页。
 */
import React from 'react'
import { Button, Tag, Typography, Alert, Spin, Empty, Popconfirm } from 'antd'
import { getSdk } from './index'
import {
  usePluginDirectory,
  togglePlugin,
  uninstallPlugin,
  reloadPage,
  clearError,
} from './pluginStore'
import { PluginIcon } from './PluginIcon'
import Markdown, { type MarkdownImageResolver } from './Markdown'
import { UninstallIcon, ReloadIcon } from './icons'

const { Text } = Typography

/** 详情页 props：目标插件 id + 关闭本标签回调（由宿主 renderTab ctx 提供）。 */
export interface ExtensionDetailPageProps {
  pluginId: string
  /** 本标签页 id（卸载成功后关闭自身用）。 */
  tabId: string
  /** 宿主 WorkspaceTabRenderContext.closeTab。 */
  closeTab: (tabId: string) => void
  /** 宿主轻提示（可选）。 */
  showToast?: (message: string, type?: 'success' | 'error' | 'info') => void
}

/** 来源显示文案。 */
function sourceLabel(source: string): string {
  return source === 'builtin' ? '内置' : source === 'external' ? '外部' : source
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

/** 详情页元信息行：label + value。 */
function MetaItem({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 2 }}>{label}</div>
      <div style={{ fontSize: 12.5, color: 'var(--text-primary)', wordBreak: 'break-all' }}>{children}</div>
    </div>
  )
}

/** 扩展详情标签页主体。 */
const ExtensionDetailPage: React.FC<ExtensionDetailPageProps> = ({
  pluginId,
  tabId,
  closeTab,
  showToast,
}) => {
  const directory = usePluginDirectory()
  const plugin = directory.plugins.find((p) => p.id === pluginId) ?? null
  const disabled = directory.disabledIds.includes(pluginId)
  const reloadNeeded = !!directory.reloadNeeded[pluginId]
  const hasReloadNeeded = Object.values(directory.reloadNeeded).some(Boolean)

  const isSelf = pluginId === 'plugin-manager'
  const isExternal = plugin?.source !== 'builtin' && !!plugin
  const isFailed = !!plugin?.status?.startsWith('激活失败')

  // 插件被卸载/消失 → 自动关闭本标签页（含本会话卸载标记）
  React.useEffect(() => {
    if (directory.uninstalledIds.includes(pluginId)) {
      showToast?.('插件已卸载', 'success')
      closeTab(tabId)
    }
  }, [directory.uninstalledIds, pluginId, closeTab, tabId, showToast])

  const handleToggle = React.useCallback(() => {
    void togglePlugin(pluginId, disabled)
  }, [pluginId, disabled])

  const handleUninstall = React.useCallback(async () => {
    // 卸载成功后由 uninstalledIds effect 统一关闭本标签；失败信息在 store.error 展示。
    await uninstallPlugin(pluginId)
  }, [pluginId])

  const sdk = getSdk()
  const [readme, setReadme] = React.useState<string | null>(null)
  const [readmeLoading, setReadmeLoading] = React.useState(true)
  const hasPlugin = !!plugin

  // README 拉取：plugin.webSource 读插件目录 readme.md（worker 侧大小写不敏感回退）。
  // 依赖 hasPlugin 布尔而非 plugin 对象引用——store 刷新不重拉 README。
  React.useEffect(() => {
    if (!sdk || !hasPlugin) {
      setReadme(null)
      setReadmeLoading(false)
      return
    }
    let cancelled = false
    setReadmeLoading(true)
    sdk.rpc(sdk.workerId, 'plugin.webSource', {
      pluginId,
      path: 'readme.md',
    }).then((result) => {
      if (cancelled) return
      setReadme((((result as { content?: string })?.content) ?? '').trim() || null)
    }).catch(() => {
      if (!cancelled) setReadme(null)
    }).finally(() => {
      if (!cancelled) setReadmeLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [sdk, pluginId, hasPlugin])

  // README 内相对路径图片 → plugin.asset data URL；http(s)/data 原样直出。
  const resolveImage = React.useCallback<MarkdownImageResolver>(async (src) => {
    if (/^(https?:)?\/\//i.test(src) || src.startsWith('data:')) return src
    if (!sdk) return null
    try {
      const result = (await sdk.rpc(sdk.workerId, 'plugin.asset', {
        pluginId,
        path: normalizePluginRelativePath(src),
      })) as { mime?: string; contentBase64?: string }
      if (result?.mime && result?.contentBase64) {
        return `data:${result.mime};base64,${result.contentBase64}`
      }
      return null
    } catch {
      return null
    }
  }, [sdk, pluginId])

  // 目录加载失败 / 插件不存在（非卸载场景，如目录尚未拉到）
  if (directory.error && !plugin) {
    return (
      <div style={{ padding: 16, overflowY: 'auto', height: '100%' }}>
        <Alert
          message={directory.error}
          type="error"
          showIcon
          closable
          onClose={clearError}
          action={<Button size="small" onClick={reloadPage}>重新加载</Button>}
        />
      </div>
    )
  }
  if (!plugin) {
    if (directory.loading) {
      return (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
          <Spin />
        </div>
      )
    }
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
        <Empty description={`插件 ${pluginId} 不存在或已卸载`} image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {/* 顶部轻工具条：全局错误/需重载提示 */}
      {(directory.error || hasReloadNeeded) && (
        <Alert
          message={directory.error ?? '插件状态已变更，重新加载页面后生效'}
          type={directory.error ? 'error' : 'warning'}
          showIcon
          closable
          onClose={clearError}
          style={{ margin: '8px 16px 0', borderRadius: 6 }}
          action={
            hasReloadNeeded ? (
              <Button size="small" type="primary" icon={<ReloadIcon />} onClick={reloadPage}>
                重新加载
              </Button>
            ) : undefined
          }
        />
      )}

      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
        {/* 头部：VSCode 扩展详情头（图标 + 标题/作者/描述/标签 + 动作） */}
        <div
          style={{
            padding: '20px 24px 16px',
            background: 'var(--bg-soft)',
            borderBottom: '1px solid var(--border)',
          }}
        >
          <div style={{ display: 'flex', gap: 18, alignItems: 'flex-start', flexWrap: 'wrap', rowGap: 12 }}>
            <PluginIcon size={104} pluginId={plugin.id} iconPath={plugin.icon} alt={plugin.name} />
            <div style={{ flex: '1 1 260px', minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <Text style={{ fontSize: 20, fontWeight: 600 }} title={plugin.name}>
                  {plugin.name}
                </Text>
                {isFailed && (
                  <Tag color="error" style={{ margin: 0 }} title={plugin.status}>加载失败</Tag>
                )}
                {reloadNeeded && (
                  <Tag color="warning" style={{ margin: 0 }}>需重新加载</Tag>
                )}
              </div>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  fontSize: 12.5,
                  color: 'var(--text-secondary)',
                  marginTop: 4,
                  flexWrap: 'wrap',
                }}
              >
                {plugin.author && <span style={{ fontWeight: 500 }}>{plugin.author}</span>}
                {plugin.author && <span>·</span>}
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
                <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 8, lineHeight: 1.7 }}>
                  {plugin.description}
                </div>
              )}
              {(plugin.categories?.length ?? 0) > 0 && (
                <div style={{ display: 'flex', gap: 4, marginTop: 10, flexWrap: 'wrap' }}>
                  {plugin.categories!.map((c) => (
                    <Tag key={c} style={{ fontSize: 11, margin: 0 }}>{c}</Tag>
                  ))}
                </div>
              )}
            </div>
            {/* 动作区：启用/禁用 + 卸载（对标 VSCode Install/Uninstall 按钮位） */}
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch', gap: 8, flexShrink: 0, minWidth: 84 }}>
              {isSelf ? (
                <Tag color="blue" style={{ margin: 0, textAlign: 'center' }}>必需</Tag>
              ) : (
                <>
                  <Button
                    type={!disabled ? 'default' : 'primary'}
                    loading={directory.togglingId === pluginId}
                    onClick={handleToggle}
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
                      onConfirm={handleUninstall}
                    >
                      <Button danger ghost icon={<UninstallIcon />} loading={directory.uninstallingId === pluginId}>
                        卸载
                      </Button>
                    </Popconfirm>
                  )}
                </>
              )}
            </div>
          </div>

          {/* 元信息 + 资源链接 */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
              gap: '12px 18px',
              marginTop: 18,
            }}
          >
            <MetaItem label="标识符">{plugin.id}</MetaItem>
            <MetaItem label="版本">{plugin.version}</MetaItem>
            <MetaItem label="来源">{sourceLabel(plugin.source)}</MetaItem>
            <MetaItem label="状态">{plugin.status || (disabled ? '已禁用' : '已启用')}</MetaItem>
          </div>
          {(plugin.repository || plugin.homepage || plugin.license) && (
            <div style={{ display: 'flex', gap: 14, marginTop: 12, flexWrap: 'wrap', fontSize: 12.5 }}>
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
        <div style={{ padding: '16px 24px 32px', maxWidth: 980 }}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 600,
              color: 'var(--text-secondary)',
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
              borderBottom: '1px solid var(--border)',
              paddingBottom: 6,
              marginBottom: 12,
            }}
          >
            README
          </div>
          {readmeLoading ? (
            <div style={{ display: 'flex', justifyContent: 'center', padding: '32px 0' }}>
              <Spin />
            </div>
          ) : readme ? (
            <Markdown source={readme} resolveImage={resolveImage} />
          ) : (
            <Empty
              description="该插件目录下没有 readme.md"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              style={{ padding: '16px 0' }}
            />
          )}
        </div>
      </div>
    </div>
  )
}

export default ExtensionDetailPage
