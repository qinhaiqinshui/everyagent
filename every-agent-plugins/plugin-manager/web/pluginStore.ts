/**
 * 插件目录共享 store（模块级，单例）。
 *
 * 侧栏扩展面板（PluginManagerPanel）与扩展详情标签页（ExtensionDetailPage）
 * 是两个独立挂载的组件实例，共享本 store 保证视图一致：
 * - 数据源：worker `plugin.list` RPC（含 status 与展示元数据）。
 * - 动作：toggle（启用/禁用）/ uninstall（外部插件）/ installEap（.eap 上传安装），
 *   全部走 RPC 并在成功后更新本地状态 + 标记「需重新加载」。
 * - 订阅：React 组件经 usePluginDirectory()（useSyncExternalStore）消费，
 *   动作后所有挂载视图自动重渲染。
 *
 * 状态语义（与 plugin-manager 插件文档一致）：
 * - disabledIds：worker 侧 .disabled-plugins 名单（名单变更重启 worker 后生效）。
 * - uninstalledIds：本会话内已卸载条目（目录已删，plugin.list 不再返回；本地隐藏兜底）。
 * - reloadNeeded：启用/禁用/卸载后置位，侧栏/详情显示「需重新加载」标记。
 */
import React from 'react'
import type { PluginSdk } from '@everyagent/plugin-api'
import { getSdk } from './index'

/** plugin.list 返回的单个插件条目。 */
export interface PluginEntry {
  id: string
  name: string
  version: string
  description?: string
  author?: string
  source: string
  active: boolean
  /** 加载期实际状态：已激活 / 激活失败: … / 已禁用(未激活) 等。 */
  status?: string
  hasMain?: boolean
  hasWebMain?: boolean
  /** 展示元数据（VSCode 风格列表/详情页用）。 */
  icon?: string
  repository?: string
  license?: string
  homepage?: string
  categories?: string[]
}

/** 安装包上传的大小上限（base64 膨胀约 4/3，32MB 原始字节 ≈ 43MB wire 载荷）。 */
const MAX_INSTALL_BYTES = 32 * 1024 * 1024

/** .eap 暂存目录（工作区内隐藏目录，工作区相对路径，无前导 `/`）。 */
const INSTALL_STAGING_DIR = '.everyagent/plugin-install'

/** store 快照（不可变，每次变更整体替换）。 */
export interface DirectorySnapshot {
  plugins: PluginEntry[]
  disabledIds: string[]
  /** 初始加载中（首次 fetch 未完成）。 */
  loading: boolean
  /** 最近一次操作/加载错误文案（null = 无）。 */
  error: string | null
  /** 行级「需要重新加载」标记：pluginId → true。 */
  reloadNeeded: Record<string, boolean>
  /** 本会话已卸载的插件 id（本地隐藏，plugin.list 已不返回）。 */
  uninstalledIds: string[]
  /** 正在切换启用态的插件 id。 */
  togglingId: string | null
  /** 正在卸载的插件 id。 */
  uninstallingId: string | null
  /** 正在安装 .eap。 */
  installing: boolean
  /** 最近一次安装结果（成功/失败文案；null = 无）。 */
  installResult: { type: 'success' | 'error'; text: string } | null
}

const initialSnapshot: DirectorySnapshot = {
  plugins: [],
  disabledIds: [],
  loading: false,
  error: null,
  reloadNeeded: {},
  uninstalledIds: [],
  togglingId: null,
  uninstallingId: null,
  installing: false,
  installResult: null,
}

/** 把 RPC 错误归一为可展示文案。 */
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

// ─── store 核心（订阅 + 不可变快照） ────────────────────────────────────────

let snapshot: DirectorySnapshot = initialSnapshot
const listeners = new Set<() => void>()

function setSnapshot(patch: Partial<DirectorySnapshot>) {
  snapshot = { ...snapshot, ...patch }
  for (const listener of listeners) {
    listener()
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function getSnapshot(): DirectorySnapshot {
  return snapshot
}

/** 是否已成功拉取过一次目录（避免重复首拉）。 */
let loadedOnce = false

/** 拉取插件目录（plugin.list）；loading 只在首次拉取时置位。 */
export async function refreshDirectory(): Promise<void> {
  const sdk: PluginSdk | null = getSdk()
  if (!sdk) {
    setSnapshot({ error: '插件 SDK 未初始化', loading: false })
    return
  }
  const first = !loadedOnce
  setSnapshot(first ? { loading: true, error: null } : { error: null })
  try {
    const result = (await sdk.rpc(sdk.workerId, 'plugin.list', {})) as {
      plugins?: PluginEntry[]
      disabledIds?: string[]
    }
    loadedOnce = true
    setSnapshot({
      plugins: result?.plugins ?? [],
      disabledIds: result?.disabledIds ?? [],
      loading: false,
      error: null,
    })
  } catch (err) {
    setSnapshot({ loading: false, error: `无法获取插件列表：${rpcErrorMessage(err)}` })
  }
}

/** 组件挂载时确保目录已加载（幂等；未加载过则触发一次拉取）。 */
export function ensureDirectoryLoaded(): void {
  if (!loadedOnce && !snapshot.loading) {
    void refreshDirectory()
  }
}

/** 手动刷新入口（侧栏「重新加载」按钮之外的显式刷新：重拉目录）。 */
export function reloadDirectory(): void {
  void refreshDirectory()
}

/** 清除错误提示。 */
export function clearError(): void {
  setSnapshot({ error: null })
}

/** 清除安装结果提示。 */
export function clearInstallResult(): void {
  setSnapshot({ installResult: null })
}

/** 刷新整个前端页面（启用/禁用/卸载后的生效动作）。 */
export function reloadPage(): void {
  location.reload()
}

// ─── 动作 ──────────────────────────────────────────────────────────────────

/**
 * 启用/禁用插件：RPC 成功后更新 disabledIds 并标记「需重新加载」；
 * 失败回滚本地状态并置 error。
 */
export async function togglePlugin(pluginId: string, nextEnabled: boolean): Promise<void> {
  const sdk = getSdk()
  if (!sdk) return
  setSnapshot({ togglingId: pluginId })
  const prevDisabled = snapshot.disabledIds
  try {
    if (nextEnabled) {
      await sdk.rpc(sdk.workerId, 'plugin.enable', { pluginId })
      setSnapshot({
        disabledIds: prevDisabled.filter((id) => id !== pluginId),
        reloadNeeded: { ...snapshot.reloadNeeded, [pluginId]: true },
      })
    } else {
      await sdk.rpc(sdk.workerId, 'plugin.disable', { pluginId })
      setSnapshot({
        disabledIds: [...prevDisabled, pluginId],
        reloadNeeded: { ...snapshot.reloadNeeded, [pluginId]: true },
      })
    }
  } catch (err) {
    // RPC 失败：回滚名单状态，显示错误
    setSnapshot({
      disabledIds: prevDisabled,
      error: `操作失败：${rpcErrorMessage(err)}`,
    })
  } finally {
    setSnapshot({ togglingId: null })
  }
}

/**
 * 卸载外部插件：目录删除成功后本地隐藏（plugin.list 重启后不再返回）。
 * 返回是否成功（成功方自行决定是否关闭详情标签页）。
 */
export async function uninstallPlugin(pluginId: string): Promise<boolean> {
  const sdk = getSdk()
  if (!sdk) return false
  setSnapshot({ uninstallingId: pluginId })
  try {
    await sdk.rpc(sdk.workerId, 'plugin.uninstall', { pluginId })
    setSnapshot({
      uninstalledIds: [...snapshot.uninstalledIds, pluginId],
      reloadNeeded: { ...snapshot.reloadNeeded, [pluginId]: true },
    })
    return true
  } catch (err) {
    setSnapshot({ error: `卸载失败：${rpcErrorMessage(err)}` })
    return false
  } finally {
    setSnapshot({ uninstallingId: null })
  }
}

/**
 * 安装 .eap（known-issues #19）：浏览器选定文件 → fs.write 上传到工作区暂存目录
 * （`plugin.install` 只收 worker 机器本地路径，工作区是前端唯一可写的落点）→
 * 用暂存文件的机器绝对路径调 `plugin.install` 解包到插件目录 → 清理暂存 → 刷新列表。
 * 新插件重启 worker 后才会出现在 `plugin.list`（生效边界不变）。
 */
export async function installEapFile(file: File | undefined): Promise<void> {
  const sdk = getSdk()
  if (!file || !sdk) return
  if (!file.name.toLowerCase().endsWith('.eap')) {
    setSnapshot({ installResult: { type: 'error', text: '仅支持 .eap 插件包' } })
    return
  }
  if (file.size > MAX_INSTALL_BYTES) {
    setSnapshot({
      installResult: {
        type: 'error',
        text: `插件包过大（${(file.size / 1024 / 1024).toFixed(1)} MB），超过 ${MAX_INSTALL_BYTES / 1024 / 1024} MB 上限`,
      },
    })
    return
  }
  const workspaceRoot = sdk.workspace.rootPath.trim()
  if (!workspaceRoot) {
    setSnapshot({ installResult: { type: 'error', text: '无法确定工作区根路径，不能上传安装包' } })
    return
  }
  setSnapshot({ installing: true, installResult: null })
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
    setSnapshot({
      installResult: { type: 'success', text: result?.message ?? '插件已安装，重启 worker 后生效' },
    })
    // 3) 清理暂存包（尽力而为，失败只留工作区残留文件）
    await sdk.rpc(fsWorkerId, 'fs.delete', { workspace: workspaceRoot, path: stagedPath }).catch(() => undefined)
    // 4) 刷新列表（新插件重启 worker 后出现；此处兜底拉齐既有条目的最新状态）
    await refreshDirectory()
  } catch (err) {
    setSnapshot({ installResult: { type: 'error', text: `安装失败：${rpcErrorMessage(err)}` } })
  } finally {
    setSnapshot({ installing: false })
  }
}

// ─── React 绑定 ────────────────────────────────────────────────────────────

/** 订阅插件目录 store 的 hook（useSyncExternalStore）。 */
export function usePluginDirectory(): DirectorySnapshot {
  return React.useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}

/** 按插件 id 查目录条目（store 快照直查，无订阅；用于标签标题/图标同步读取）。 */
export function findPluginEntry(pluginId: string | undefined): PluginEntry | null {
  if (!pluginId) return null
  return snapshot.plugins.find((p) => p.id === pluginId) ?? null
}
