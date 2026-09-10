/**
 * Git 侧边栏面板(hub 版,VS Code Source Control 范式)。
 *
 * 与旧 GitPage 的区别:本面板只承载「源代码管理」侧栏视图(提交框 + 变更文件树
 * + 未初始化引导),**不内联 diff**——点击任意变更文件经 openDiffTab 在
 * 主区打开独立 diff 标签页(架构 §13.6 遥控视图:凭证走 worker 本机 git 配置)。
 *
 * 多工作区(架构 §5.9/D16):与资源管理器同布局——顶部「工作区(N)」标题与
 * 添加工作区入口,每个注册工作区一张分组卡片,git 状态/提交/变更树均落本组
 * 工作区;不做暂存(staging):无 staged/unstaged 二分(按需求)。
 *
 * 选中模型对齐 VS Code SCM:默认**无勾选框、提交=全部更改**;文件级操作
 * (查看差异/放弃更改/删除未跟踪文件)经**右键菜单**触达;右键「多选」进入
 * 多选模式后树才显示勾选框,提交/放弃仅作用于勾选集;勾选集绑定在树行上——
 * 状态刷新后剪除已不在变更列表中的路径(文件消失即取消勾选,计数永不失真)。
 */
import React from 'react'
import { Tree, Input, Button, Badge, Modal, Form, Checkbox, App } from 'antd'
import type { TreeDataNode } from 'antd'
import { InlineSpinner } from '@/components/shared/ui'
import { GitIcon } from '@/components/icon'
import { ChevronDownIcon } from '@/components/shared/AppGlyphs'
import { useHub } from '@/hub/HubProvider'
import { workspaceRegistry, type WorkspaceEntry } from '@/hub/workspaceRegistry'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import {
  gitGateway,
  GitNotInitializedError,
  GitAuthRequiredError,
  type GitCredential,
  type GitRemote,
} from '@/platform/git/gitGateway'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { antdConfirm } from '@/utils/appAntdBridge'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import SidebarScrollArea from '@/components/shared/SidebarScrollArea'
import MoreActionsButton, { type MoreActionItem } from '@/components/shared/MoreActionsButton'
import { MenuList, type MenuListItem } from '@/components/shared/ui/Menu'

interface GitStatusResult {
  branch?: string
  added?: string[]
  changed?: string[]
  modified?: string[]
  removed?: string[]
  missing?: string[]
  untracked?: string[]
  conflicting?: string[]
  /** 当前分支领先上游(已提交未推送)的提交数;无上游/非跟踪分支为 0。 */
  ahead?: number
  /** 当前分支落后上游的提交数;无上游/非跟踪分支为 0。 */
  behind?: number
}

interface GitLogCommit {
  id: string
  shortId: string
  author: string
  email?: string
  ts: number
  message: string
}

interface GitDiffResult {
  diff: string
  empty?: boolean
}

type StatusArrayKey = 'added' | 'changed' | 'modified' | 'removed' | 'missing' | 'untracked' | 'conflicting'

const STATUS_BADGE: Record<StatusArrayKey, { letter: string; tone: string; label: string }> = {
  conflicting: { letter: '!', tone: 'var(--accent-red)', label: '冲突' },
  modified: { letter: 'M', tone: 'var(--accent-yellow, #d7a000)', label: '已修改' },
  changed: { letter: 'C', tone: 'var(--accent-yellow, #d7a000)', label: '已变更' },
  added: { letter: 'A', tone: 'var(--accent-green)', label: '已暂存' },
  removed: { letter: 'D', tone: 'var(--accent-red)', label: '已删除' },
  missing: { letter: 'D', tone: 'var(--accent-red)', label: '已丢失' },
  untracked: { letter: 'U', tone: 'var(--accent-blue)', label: '未跟踪' },
}

const STATUS_ORDER: StatusArrayKey[] = [
  'conflicting',
  'modified',
  'changed',
  'added',
  'removed',
  'missing',
  'untracked',
]

interface ChangeLeaf {
  path: string
  statusKey: StatusArrayKey
}

function collectLeaves(status: GitStatusResult): ChangeLeaf[] {
  const seen = new Map<string, StatusArrayKey>()
  for (const key of STATUS_ORDER) {
    for (const path of status[key] ?? []) {
      if (!seen.has(path)) seen.set(path, key)
    }
  }
  return Array.from(seen.entries())
    .map(([path, statusKey]) => ({ path, statusKey }))
    .sort((a, b) => a.path.localeCompare(b.path))
}

function buildTreeData(leaves: ChangeLeaf[]): TreeDataNode[] {
  interface TreeNode {
    node: TreeDataNode
    children: Map<string, TreeNode>
    isLeaf: boolean
  }
  const root = new Map<string, TreeNode>()

  const ensureDir = (parent: Map<string, TreeNode>, name: string): TreeNode => {
    let entry = parent.get(name)
    if (!entry) {
      const node: TreeDataNode = { key: `dir:${name}`, title: name, isLeaf: false }
      entry = { node, children: new Map(), isLeaf: false }
      parent.set(name, entry)
    }
    return entry
  }

  for (const leaf of leaves) {
    const parts = leaf.path.split('/')
    const fileName = parts[parts.length - 1]
    let cursor = root
    let prefix = ''
    for (let i = 0; i < parts.length - 1; i++) {
      const dirName = parts[i]
      prefix = prefix ? `${prefix}/${dirName}` : dirName
      const dir = ensureDir(cursor, dirName)
      dir.node.key = `dir:${prefix}`
      cursor = dir.children
    }
    const badge = STATUS_BADGE[leaf.statusKey]
    const leafNode: TreeDataNode = {
      key: `file:${leaf.path}`,
      isLeaf: true,
      title: (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <span
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 16,
              height: 16,
              borderRadius: 3,
              fontSize: 11,
              fontWeight: 700,
              color: '#fff',
              background: badge.tone,
              flexShrink: 0,
            }}
            title={badge.label}
          >
            {badge.letter}
          </span>
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{fileName}</span>
        </span>
      ),
    }
    cursor.set(fileName, { node: leafNode, children: new Map(), isLeaf: true })
  }

  const toDataNode = (map: Map<string, TreeNode>): TreeDataNode[] =>
    Array.from(map.values())
      .map((entry) => ({
        ...entry.node,
        children: entry.isLeaf ? undefined : toDataNode(entry.children),
      }))
      .sort((a, b) => {
        const aLeaf = !a.children
        const bLeaf = !b.children
        if (aLeaf !== bLeaf) return aLeaf ? 1 : -1
        return String(a.title).localeCompare(String(b.title))
      })

  return toDataNode(root)
}

/**
 * Git 侧边栏面板:与资源管理器同布局,列出注册表全部工作区,每个工作区一张分组卡片。
 */
export default function GitSidebarPanel(_props: { embedded?: boolean }) {
  const hub = useHub()
  const [registry, setRegistry] = React.useState(workspaceRegistry.current)

  React.useEffect(() => workspaceRegistry.subscribe(setRegistry), [])

  const connected = hub.state === 'open'
  const hasWorker = hub.directory.some((w) => w.online && w.enabled && w.hasApiKey && !w.error && !w.connecting)
  const entries = registry?.workspaces ?? []

  return (
    <div style={panelStyle}>
      <SidebarScrollArea style={bodyStyle}>
        <div style={sectionStyle}>
          {!connected || !hasWorker ? (
            <div style={emptyStyle}>
              <GitIcon size={32} />
              <p style={emptyTextStyle}>未连接 worker——在设置页配置 hub 连接后使用 Git。</p>
            </div>
          ) : registry ? null : <div style={emptyStyle}>连接后可查看工作区注册表。</div>}
          {entries.map((entry) => (
            <GitWorkspaceGroupPanel
              key={entry.root}
              entry={entry}
              isDefault={entry.root === registry?.defaultRoot}
            />
          ))}
        </div>
      </SidebarScrollArea>
    </div>
  )
}

/**
 * 单个工作区分组卡片:分支/提交区/变更树/未初始化引导全部落本工作区,
 * 操作按钮(刷新/拉取/推送/更多)作用于本组,与资源管理器分组卡片同布局。
 */
function GitWorkspaceGroupPanel({
  entry,
  isDefault,
}: {
  entry: WorkspaceEntry
  isDefault: boolean
}) {
  const workspaceRoot = entry.root
  const hub = useHub()
  const { openDiffTab } = useWorkspaceShell()
  const { message, modal } = App.useApp()
  const [initialized, setInitialized] = React.useState<boolean | null>(null)
  const [status, setStatus] = React.useState<GitStatusResult | null>(null)
  const [selectedPaths, setSelectedPaths] = React.useState<Set<string>>(new Set())
  const [commitMessage, setCommitMessage] = React.useState('')
  const [busy, setBusy] = React.useState<'status' | 'commit' | 'pull' | 'push' | 'init' | 'clone' | 'discard' | 'delete' | null>('status')
  const [diffLoadingPath, setDiffLoadingPath] = React.useState<string | null>(null)
  /**
   * 卡片折叠态:折叠时仅保留头部行(工作区名 + 分支 + 操作按钮),隐藏提交区/更改树。
   * 默认折叠;检测到「从无变更 → 有变更」的转变时自动展开——切回面板/操作后刷新
   * 出变更的工作区一眼可见。用户在仍有变更时手动折叠不被打扰(ref 仍为 true 不触发
   * 展开);变更清零后再出现新变更会再次展开。
   */
  const [collapsed, setCollapsed] = React.useState(true)
  const prevHadChangesRef = React.useRef<boolean | null>(null)
  /** 多选模式:树显示勾选框,提交/放弃仅作用于勾选集(右键「多选」进入)。 */
  const [multiSelect, setMultiSelect] = React.useState(false)
  /** 右键菜单:触发点坐标 + 目标文件(路径 + 状态类别);null = 关闭。 */
  const [contextMenu, setContextMenu] = React.useState<{
    x: number
    y: number
    path: string
    statusKey: StatusArrayKey
  } | null>(null)
  /** 更改树容器(右键菜单 MenuList 的锚元素)。 */
  const treeRef = React.useRef<HTMLDivElement | null>(null)

  const connected = hub.state === 'open'
  const hasWorker = hub.directory.some((w) => w.online && w.enabled && w.hasApiKey && !w.error && !w.connecting)

  const refresh = React.useCallback(async () => {
    if (!workspaceRoot) return
    setBusy('status')
    try {
      const [statusResult, ] = await Promise.all([
        gitGateway.status(workspaceRoot),
        gitGateway.log(workspaceRoot, 30).catch(() => []),
      ])
      setInitialized(true)
      setStatus(statusResult)
    } catch (refreshError) {
      if (refreshError instanceof GitNotInitializedError) {
        setInitialized(false)
        setStatus(null)
        return
      }
      setInitialized(null)
      setStatus(null)
      message.error(refreshError instanceof Error ? refreshError.message : '读取 git 状态失败')
    } finally {
      setBusy(null)
    }
  }, [workspaceRoot, message])

  React.useEffect(() => {
    if (connected && hasWorker) {
      void refresh()
    }
  }, [connected, hasWorker, refresh, hub.resyncVersion])

  // 侧边栏切到本面板时主动刷新,确保状态反映最新落盘。
  React.useEffect(() => {
    const unsubscribe = domainEventBus.subscribe(DOMAIN_EVENTS.SIDEBAR_PANEL_SHOWN, (payload) => {
      if (payload.panelId !== 'git') return
      if (connected && hasWorker) {
        void refresh()
      }
    })
    return unsubscribe
  }, [refresh, connected, hasWorker])

  const leaves = React.useMemo(() => (status ? collectLeaves(status) : []), [status])
  const treeData = React.useMemo(() => buildTreeData(leaves), [leaves])

  /**
   * 勾选集绑定在树行上:状态刷新后剪除已不在变更列表中的勾选路径。典型:新增
   * (未跟踪)文件被勾选后又删除——git status 对其彻底不可见,勾选集若残留该
   * 陈旧路径,提交计数会大于更改列表实际项数,且把已不存在的路径发给 worker,
   * 使 git add 因 unmatched pathspec 整体失败、提交被阻断。
   */
  const leafPaths = React.useMemo(() => new Set(leaves.map((leaf) => leaf.path)), [leaves])
  React.useEffect(() => {
    setSelectedPaths((current) => {
      if (current.size === 0) return current
      const next = new Set(Array.from(current).filter((path) => leafPaths.has(path)))
      return next.size === current.size ? current : next
    })
  }, [leafPaths])

  /** 变更从无到有 → 自动展开本工作区分组(语义见 collapsed 声明处注释)。 */
  React.useEffect(() => {
    const hasChanges = leaves.length > 0
    if (hasChanges && prevHadChangesRef.current !== true) {
      setCollapsed(false)
    }
    prevHadChangesRef.current = hasChanges
  }, [leaves.length])

  /** 双击变更文件:经 openDiffTab 在主区打开独立 diff 标签页(替代旧内联 diff)。 */
  const openFileDiff = React.useCallback(async (path: string) => {
    setDiffLoadingPath(path)
    try {
      const change = await gitGateway.diff(workspaceRoot, path)
      // 防御:filePath 已由 gitGateway 校验为 string;changeType 归一避免旧 worker 缺字段时污染 diff 标签。
      const slashIndex = change.filePath.lastIndexOf('/')
      openDiffTab({
        filePath: change.filePath,
        changeType: change.changeType === 'created' ? 'created' : 'updated',
        beforeContent: change.beforeContent ?? '',
        afterContent: change.afterContent ?? '',
        fileName: slashIndex >= 0 ? change.filePath.slice(slashIndex + 1) : change.filePath,
        title: `Git: ${change.filePath}`,
        workspaceRoot,
      })
    } catch (diffError) {
      message.error(diffError instanceof Error ? diffError.message : '读取差异失败')
    } finally {
      setDiffLoadingPath(null)
    }
  }, [openDiffTab, workspaceRoot, message])

  const handleInit = React.useCallback(async () => {
    setBusy('init')
    try {
      await gitGateway.init(workspaceRoot)
      message.success('已在此工作区初始化仓库')
      await refresh()
    } catch (initError) {
      message.error(initError instanceof Error ? initError.message : '初始化仓库失败')
    } finally {
      setBusy(null)
    }
  }, [refresh, workspaceRoot, message])

  const handleClone = React.useCallback(async (url: string, dir: string, credential?: GitCredential) => {
    setBusy('clone')
    try {
      if (dir) {
        const rows = await workspaceGateway.listDir(workspaceRoot, dir).catch(() => [])
        if (rows.length > 0) {
          throw new Error('目标子目录非空:请选择工作区内的空目录,或留空克隆到根,或改用「初始化本地仓库」')
        }
      }
      await gitGateway.clone(workspaceRoot, url, dir || undefined, credential)
      message.success(`已克隆仓库到 ${dir || '工作区根'}`)
      await refresh()
    } catch (cloneError) {
      if (cloneError instanceof GitAuthRequiredError) {
        // 远端需要认证:弹凭证输入窗,提交后按保存与否重试
        const host = cloneError.host
        setCredentialPrompt({
          host,
          url,
          action: 'clone',
          retry: async (cred, save) => {
            // 先克隆(此时工作区根仍为空,避免先落盘凭证到 .everyagent/ 导致目标目录非空)
            await gitGateway.clone(workspaceRoot, url, dir || undefined, cred ?? undefined)
            if (save && cred) {
              // 克隆完成后再持久化凭证
              await gitGateway.saveCredential(workspaceRoot, cred.username, cred.password, url)
            }
            await refresh()
          },
        })
        return
      }
      message.error(cloneError instanceof Error ? cloneError.message : '克隆仓库失败')
    } finally {
      setBusy(null)
    }
  }, [refresh, workspaceRoot, message])

  /**
   * 提交:默认模式提交**全部更改**(不带 paths,worker `add -A -- .`);
   * 多选模式仅提交勾选集。成功后清空勾选并退出多选。
   */
  const handleCommit = React.useCallback(async () => {
    const messageText = commitMessage.trim()
    if (!messageText) {
      message.error('请填写提交说明')
      return
    }
    if (multiSelect && selectedPaths.size === 0) {
      message.error('多选模式下请先勾选要提交的文件')
      return
    }
    setBusy('commit')
    try {
      const paths = multiSelect ? Array.from(selectedPaths) : undefined
      const result = await gitGateway.commit(workspaceRoot, messageText, paths && paths.length > 0 ? paths : undefined)
      message.success(`已提交 ${result?.shortId ?? ''}`)
      setCommitMessage('')
      setSelectedPaths(new Set())
      setMultiSelect(false)
      await refresh()
    } catch (commitError) {
      message.error(commitError instanceof Error ? commitError.message : '提交失败')
    } finally {
      setBusy(null)
    }
  }, [commitMessage, multiSelect, selectedPaths, refresh, workspaceRoot, message])

  const handlePull = React.useCallback(async (credential?: GitCredential) => {
    setBusy('pull')
    try {
      const result = await gitGateway.pull(workspaceRoot, credential)
      message.success(result?.successful ? '拉取成功' : `拉取完成:${result?.mergeStatus ?? '未知状态'}`)
      await refresh()
    } catch (pullError) {
      if (pullError instanceof GitAuthRequiredError) {
        const host = pullError.host
        setCredentialPrompt({
          host,
          url: '',
          action: 'pull',
          retry: async (cred, save) => {
            // 先拉取(带内联凭证),完成后再持久化
            await gitGateway.pull(workspaceRoot, cred ?? undefined)
            if (save && cred) {
              await gitGateway.saveCredential(workspaceRoot, cred.username, cred.password, host)
            }
            await refresh()
          },
        })
        return
      }
      message.error(pullError instanceof Error ? pullError.message : '拉取失败')
    } finally {
      setBusy(null)
    }
  }, [refresh, workspaceRoot, message])

  /** 放弃指定文件的更改(恢复为 HEAD 内容,不可撤销)。多选模式下右键勾选行 = 批量。 */
  const handleDiscard = React.useCallback(async (paths: string[]) => {
    if (paths.length === 0) return
    const confirmed = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '放弃更改',
        content: `放弃 ${paths.length} 个文件的更改?此操作不可撤销。`,
        okText: '放弃',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!confirmed) return
    setBusy('discard')
    try {
      const result = await gitGateway.discard(workspaceRoot, paths)
      const discardedCount = result.discarded?.length ?? 0
      const skippedCount = result.skipped?.length ?? 0
      message.success(`已放弃 ${discardedCount} 个文件的更改${skippedCount > 0 ? `,${skippedCount} 个未跟踪文件跳过` : ''}`)
      setSelectedPaths(new Set())
      setMultiSelect(false)
      await refresh()
    } catch (discardError) {
      message.error(discardError instanceof Error ? discardError.message : '放弃更改失败')
    } finally {
      setBusy(null)
    }
  }, [refresh, workspaceRoot, message, modal])

  /** 删除未跟踪文件(对齐 VS Code:未跟踪文件没有「放弃更改」,只有删除)。 */
  const handleDeleteUntracked = React.useCallback(async (path: string) => {
    const confirmed = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '删除文件',
        content: `删除未跟踪文件 ${path}?该文件从未提交,删除后无法从 git 恢复。`,
        okText: '删除',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!confirmed) return
    setBusy('delete')
    try {
      await workspaceGateway.deletePath(workspaceRoot, path)
      message.success(`已删除 ${path}`)
      setSelectedPaths((current) => {
        if (!current.has(path)) return current
        const next = new Set(current)
        next.delete(path)
        return next
      })
      await refresh()
    } catch (deleteError) {
      message.error(deleteError instanceof Error ? deleteError.message : '删除文件失败')
    } finally {
      setBusy(null)
    }
  }, [refresh, workspaceRoot, message, modal])

  /** 进入多选模式(右键「多选」):树显示勾选框并预勾选触发行。 */
  const enterMultiSelect = React.useCallback((path: string) => {
    setMultiSelect(true)
    setSelectedPaths((current) => {
      if (current.has(path)) return current
      const next = new Set(current)
      next.add(path)
      return next
    })
  }, [])

  /** 退出多选模式:清空勾选,回到「提交=全部更改」默认态。 */
  const exitMultiSelect = React.useCallback(() => {
    setMultiSelect(false)
    setSelectedPaths(new Set())
  }, [])

  const [remoteModalOpen, setRemoteModalOpen] = React.useState(false)
  const [remoteForm] = Form.useForm<{ name: string; url: string }>()

  /**
   * 凭证输入弹窗(AUTH_REQUIRED 触发):打开时记录待重试操作;
   * 提交后统一先以内联凭证执行 git 操作(克隆场景下工作区根此时仍为空),
   * 成功后再据「保存」勾选落盘凭证到 .everyagent/,避免先落盘使克隆目标目录非空。
   */
  const [credentialPrompt, setCredentialPrompt] = React.useState<{
    host: string
    url: string
    action: 'clone' | 'pull' | 'push'
    retry: (credential: GitCredential | null, save: boolean) => Promise<void>
  } | null>(null)
  const [credentialForm] = Form.useForm<{ username: string; password: string; save: boolean }>()
  const [credentialSubmitting, setCredentialSubmitting] = React.useState(false)

  const handleCredentialOk = React.useCallback(async () => {
    if (!credentialPrompt) return
    try {
      setCredentialSubmitting(true)
      const values = await credentialForm.validateFields()
      const credential: GitCredential = { username: values.username.trim(), password: values.password }
      await credentialPrompt.retry(credential, Boolean(values.save))
      setCredentialPrompt(null)
      message.success(credentialPrompt.action === 'clone' ? '克隆成功' : '操作成功')
    } catch (credError) {
      message.error(credError instanceof Error ? credError.message : '凭证提交失败')
    } finally {
      setCredentialSubmitting(false)
    }
  }, [credentialForm, credentialPrompt, message])

  const handleCredentialCancel = React.useCallback(() => {
    setCredentialPrompt(null)
  }, [])

  const ensureRemoteThenPush = React.useCallback(async (credential?: GitCredential) => {
    setBusy('push')
    try {
      const remotes: GitRemote[] = await gitGateway.listRemotes(workspaceRoot)
      if (remotes.length === 0) {
        setRemoteModalOpen(true)
        setBusy(null)
        return
      }
      const result = await gitGateway.push(workspaceRoot, credential)
      const updates = result?.updates ?? []
      message.success(updates.length > 0
        ? `已推送:${updates.map((u) => `${u.ref}(${u.status})`).join(', ')}`
        : '没有需要推送的更新')
      await refresh()
    } catch (pushError) {
      if (pushError instanceof GitAuthRequiredError) {
        const host = pushError.host
        setCredentialPrompt({
          host,
          url: '',
          action: 'push',
          retry: async (cred, save) => {
            // 先推送(带内联凭证),完成后再持久化
            await gitGateway.push(workspaceRoot, cred ?? undefined)
            if (save && cred) {
              await gitGateway.saveCredential(workspaceRoot, cred.username, cred.password, host)
            }
            await refresh()
          },
        })
        return
      }
      message.error(pushError instanceof Error ? pushError.message : '推送失败')
    } finally {
      setBusy((b) => (b === 'push' ? null : b))
    }
  }, [refresh, workspaceRoot, message])

  const handleRemoteModalOk = React.useCallback(async () => {
    const values = await remoteForm.validateFields()
    setBusy('push')
    try {
      await gitGateway.addRemote(workspaceRoot, values.url.trim(), values.name.trim() || 'origin')
      setRemoteModalOpen(false)
      const result = await gitGateway.push(workspaceRoot)
      const updates = result?.updates ?? []
      message.success(updates.length > 0
        ? `已推送:${updates.map((u) => `${u.ref}(${u.status})`).join(', ')}`
        : '没有需要推送的更新')
      await refresh()
    } catch (remoteError) {
      message.error(remoteError instanceof Error ? remoteError.message : '关联并推送失败')
    } finally {
      setBusy(null)
    }
  }, [remoteForm, refresh, workspaceRoot, message])

  const moreItems: MoreActionItem[] = [
    { key: 'refresh', label: '刷新', onSelect: () => void refresh() },
    ...(multiSelect
      ? [{ key: 'exit-multi', label: '退出多选', onSelect: exitMultiSelect }]
      : []),
    { key: 'pull', label: '拉取', onSelect: () => void handlePull() },
    { key: 'push', label: '推送', onSelect: () => void ensureRemoteThenPush() },
    { key: 'init', label: '初始化仓库', onSelect: () => void handleInit() },
    { key: 'remove-workspace', label: '移除工作区…', danger: true, onSelect: () => void handleRemoveWorkspace(entry) },
  ]

  /**
   * 右键菜单项(变更文件操作):查看差异 / 放弃更改(未跟踪=删除文件,对齐
   * VS Code)/ 多选进出。多选模式下右键勾选行 → 放弃作用于整个勾选集
   * (对齐 VS Code:操作作用于包含右键行的选择),并提供「提交选中」快捷入口。
   */
  const fileMenuItems: MenuListItem[] = (() => {
    if (!contextMenu) return []
    const { path, statusKey } = contextMenu
    const batch = multiSelect && selectedPaths.has(path) && selectedPaths.size > 0
    const discardTargets = batch ? Array.from(selectedPaths) : [path]
    const items: MenuListItem[] = [
      { key: 'diff', label: '查看差异', onSelect: () => void openFileDiff(path) },
    ]
    if (statusKey === 'untracked') {
      items.push({ key: 'delete', label: '删除文件', danger: true, onSelect: () => void handleDeleteUntracked(path) })
    } else {
      items.push({
        key: 'discard',
        label: batch ? `放弃更改(${discardTargets.length} 个文件)` : '放弃更改',
        danger: true,
        onSelect: () => void handleDiscard(discardTargets),
      })
    }
    if (multiSelect) {
      items.push({
        key: 'commit-selection',
        label: `提交选中(${selectedPaths.size} 文件)`,
        disabled: selectedPaths.size === 0 || !commitMessage.trim(),
        onSelect: () => void handleCommit(),
      })
      items.push({ key: 'exit-multi', label: '退出多选', onSelect: exitMultiSelect })
    } else {
      items.push({ key: 'multi', label: '多选', onSelect: () => enterMultiSelect(path) })
    }
    return items
  })()

  return (
    <div style={groupStyle}>
      <div style={groupHeaderStyle}>
        <div style={groupToggleStyle}>
          <span
            style={groupChevronButtonStyle}
            title={collapsed ? '展开' : '折叠'}
            onClick={() => setCollapsed((value) => !value)}
          >
            <ChevronDownIcon
              size={13}
              style={collapsed ? { ...groupChevronStyle, ...groupChevronCollapsedStyle } : groupChevronStyle}
            />
          </span>
          <span style={groupTitleStyle} title={workspaceRoot}>{getWorkspaceDisplayName(workspaceRoot)}</span>
          {initialized && status?.branch ? (
            <span style={headerBranchPillStyle} title={`当前分支 ${status.branch}`}>
              <BranchIcon />
              <span style={headerBranchTextStyle}>{status.branch}</span>
            </span>
          ) : null}
          <span style={{ flex: 1, minWidth: 0 }} aria-hidden />
          {isDefault ? <span style={groupBadgeStyle}>默认</span> : null}
        </div>
        <div style={sectionHeaderActionsStyle}>
          <Button type="text" style={iconButtonStyle} onClick={() => void refresh()} disabled={busy !== null} title="刷新">
            {busy === 'status' ? <InlineSpinner size={13} color="currentColor" trackColor="transparent" /> : <RefreshIcon />}
          </Button>
          <Button type="text" style={iconButtonStyle} onClick={() => void handlePull()} disabled={busy !== null || !initialized} title="拉取">
            <PullIcon />
          </Button>
          <Badge
            count={status?.ahead ?? 0}
            showZero={false}
            size="small"
            overflowCount={99}
            color="var(--accent-blue)"
            style={{ color: '#fff', boxShadow: 'none' }}
            offset={[-4, 4]}
            title={status && (status.ahead ?? 0) > 0 ? `${status.ahead} 个提交待推送` : undefined}
          >
            <Button type="text" style={iconButtonStyle} onClick={() => void ensureRemoteThenPush()} disabled={busy !== null || !initialized} title="推送">
              <PushIcon />
            </Button>
          </Badge>
          <MoreActionsButton items={moreItems} title="更多操作" disabled={busy !== null} />
        </div>
      </div>
      {!collapsed && (
        <>
          <div style={workspaceRootStyle} title={workspaceRoot}>{workspaceRoot}</div>
          {initialized === false ? (
        <GitNotInitializedView
          disabled={busy !== null}
          onInit={() => void handleInit()}
          onClone={(url, dir) => void handleClone(url, dir)}
        />
      ) : (
        <>
          <div style={commitAreaStyle}>
            <Input.TextArea
              value={commitMessage}
              onChange={(event) => setCommitMessage(event.target.value)}
              placeholder={multiSelect
                ? `提交选中的 ${selectedPaths.size} 个文件…`
                : '消息(提交全部更改)'}
              autoSize={{ minRows: 2, maxRows: 6 }}
              styles={{ textarea: { resize: 'none' } }}
            />
            <Button
              block
              type="primary"
              onClick={() => void handleCommit()}
              disabled={busy !== null
                || !commitMessage.trim()
                || leaves.length === 0
                || (multiSelect && selectedPaths.size === 0)}
            >
              {busy === 'commit'
                ? '提交中…'
                : multiSelect
                  ? `提交选中(${selectedPaths.size} 文件)`
                  : '提交'}
            </Button>
          </div>

          <div style={changesHeaderStyle}>
            <h3 style={columnTitleStyle}>更改</h3>
            <Badge count={leaves.length} showZero color="var(--accent-blue)" style={{ color: '#fff' }} />
            {multiSelect ? (
              <div style={multiSelectBarStyle}>
                <span style={multiSelectCountStyle}>已选 {selectedPaths.size}/{leaves.length}</span>
                <Button
                  type="text"
                  size="small"
                  style={miniButtonStyle}
                  disabled={busy !== null || leaves.length === 0}
                  onClick={() => setSelectedPaths(
                    selectedPaths.size === leaves.length
                      ? new Set()
                      : new Set(leaves.map((leaf) => leaf.path)),
                  )}
                >
                  {selectedPaths.size === leaves.length && leaves.length > 0 ? '清空' : '全选'}
                </Button>
                <Button type="text" size="small" style={miniButtonStyle} disabled={busy !== null} onClick={exitMultiSelect}>
                  完成
                </Button>
              </div>
            ) : null}
          </div>
          {leaves.length === 0 ? (
            <div style={columnEmptyStyle}>工作区干净</div>
          ) : (
            <div ref={treeRef}>
              <Tree.DirectoryTree
                treeData={treeData}
                showIcon
                defaultExpandAll
                selectable={false}
                onDoubleClick={(_event, node) => {
                  const key = (node as { key?: React.Key }).key
                  if (typeof key === 'string' && key.startsWith('file:')) {
                    const path = key.slice('file:'.length)
                    void openFileDiff(path)
                  }
                }}
                onRightClick={({ event, node }) => {
                  const key = (node as { key?: React.Key }).key
                  if (typeof key !== 'string' || !key.startsWith('file:')) return
                  const path = key.slice('file:'.length)
                  const statusKey = leaves.find((leaf) => leaf.path === path)?.statusKey
                  if (!statusKey) return
                  setContextMenu({ x: event.clientX, y: event.clientY, path, statusKey })
                }}
                checkable={multiSelect}
                onCheck={(checked: React.Key[] | { checked: React.Key[]; halfChecked: React.Key[] }) => {
                  if (!multiSelect) return
                  const keys = Array.isArray(checked) ? checked : checked.checked
                  const paths = (keys as string[])
                    .filter((k) => k.startsWith('file:'))
                    .map((k) => k.slice('file:'.length))
                  setSelectedPaths(new Set(paths))
                }}
                checkedKeys={multiSelect ? Array.from(selectedPaths).map((p) => `file:${p}`) : []}
              />
            </div>
          )}
          {diffLoadingPath ? (
            <div style={diffLoadingStyle}>
              <InlineSpinner size={13} color="currentColor" trackColor="transparent" /> 正在加载 {diffLoadingPath} 的差异…
            </div>
          ) : null}

          {contextMenu && treeRef.current ? (
            <MenuList
              anchor={treeRef.current}
              anchorPoint={{ x: contextMenu.x, y: contextMenu.y }}
              anchorPointMode="top-start"
              items={fileMenuItems}
              onClose={() => setContextMenu(null)}
              title="变更文件操作"
            />
          ) : null}
        </>
      )}
        </>
      )}

      <Modal
        title="关联远程仓库"
        open={remoteModalOpen}
        onOk={() => void handleRemoteModalOk()}
        onCancel={() => setRemoteModalOpen(false)}
        okText="关联并推送"
        destroyOnClose
      >
        <p style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', margin: '0 0 12px' }}>
          当前仓库没有关联任何远程。填写远程地址以关联后推送。
        </p>
        <Form form={remoteForm} layout="vertical" initialValues={{ name: 'origin' }}>
          <Form.Item name="name" label="远程名称" rules={[{ required: true, message: '请填写远程名称' }]}>
            <Input placeholder="origin" />
          </Form.Item>
          <Form.Item name="url" label="远程仓库 URL" rules={[{ required: true, message: '请填写远程仓库 URL' }]}>
            <Input placeholder="https://… 或 git@…" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="需要登录"
        open={credentialPrompt !== null}
        onOk={() => void handleCredentialOk()}
        onCancel={handleCredentialCancel}
        okText="提交并重试"
        cancelText="取消"
        confirmLoading={credentialSubmitting}
        destroyOnClose
      >
        <p style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', margin: '0 0 12px' }}>
          {credentialPrompt
            ? `${credentialPrompt.action === 'clone' ? '克隆' : credentialPrompt.action === 'pull' ? '拉取' : '推送'}需要认证:${credentialPrompt.host}`
            : ''}
        </p>
        <Form form={credentialForm} layout="vertical" initialValues={{ save: true }}>
          <Form.Item name="username" label="账号" rules={[{ required: true, message: '请填写账号' }]}>
            <Input placeholder="用户名 / 邮箱" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请填写密码' }]}>
            <Input.Password placeholder="密码或访问令牌" autoComplete="current-password" />
          </Form.Item>
          <Form.Item name="save" valuePropName="checked">
            <Checkbox>保存凭证到工作区(加密存储)</Checkbox>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

/**
 * 未初始化引导视图(对齐 VS Code 的「初始化仓库 / 克隆」多方式)。
 */
function GitNotInitializedView({
  disabled,
  onInit,
  onClone,
}: {
  disabled: boolean
  onInit: () => void
  onClone: (url: string, dir: string) => void
}) {
  const [mode, setMode] = React.useState<'choose' | 'clone'>('choose')
  const [url, setUrl] = React.useState('')
  const [dir, setDir] = React.useState('')

  if (mode === 'choose') {
    return (
      <div style={emptyStyle}>
        <GitIcon size={32} />
        <p style={emptyTextStyle}>此工作区尚未初始化为 Git 仓库。</p>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', justifyContent: 'center' }}>
          <button type="button" style={initCardStyle} disabled={disabled} onClick={() => void onInit()}>
            <div style={cardTitleStyle}>初始化本地仓库</div>
            <div style={cardDescStyle}>在此工作区根创建新的 Git 仓库。</div>
          </button>
          <button type="button" style={initCardStyle} disabled={disabled} onClick={() => setMode('clone')}>
            <div style={cardTitleStyle}>克隆远程仓库</div>
            <div style={cardDescStyle}>从远端克隆到本工作区(可指定空子目录)。</div>
          </button>
        </div>
      </div>
    )
  }

  return (
    <div style={cloneFormStyle}>
      <h3 style={columnTitleStyle}>克隆远程仓库</h3>
      <p style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', margin: '0 0 12px' }}>
        目标目录必须为空:留空则克隆到工作区根(根非空则不允许);若工作区非空,请在下方指定一个工作区内的空子目录。
      </p>
      <label style={fieldLabelStyle}>远程仓库 URL</label>
      <Input
        value={url}
        onChange={(e) => setUrl(e.target.value)}
        placeholder="https://… 或 git@…"
        style={{ marginBottom: 12 }}
      />
      <label style={fieldLabelStyle}>目标子目录(可选,工作区内相对路径)</label>
      <Input
        value={dir}
        onChange={(e) => setDir(e.target.value)}
        placeholder="留空 = 工作区根"
        style={{ marginBottom: 16 }}
      />
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
        <Button onClick={() => setMode('choose')} disabled={disabled}>返回</Button>
        <Button type="primary" disabled={disabled || !url.trim()} onClick={() => onClone(url.trim(), dir.trim())}>
          克隆
        </Button>
      </div>
    </div>
  )
}

/** 工作区显示名:根路径最后一段(如 D:\projects\novel → novel)。 */
function getWorkspaceDisplayName(root: string): string {
  const normalized = root.replace(/[\\/]+$/, '')
  const index = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
  return index >= 0 ? normalized.slice(index + 1) : normalized
}

/** 移除工作区注册:挂靠该工作区的任务数据一并删除,工作区目录文件不受影响。 */
async function handleRemoveWorkspace(entry: WorkspaceEntry): Promise<void> {
  const confirmed = await new Promise<boolean>((resolve) => {
    antdConfirm({
      title: '移除工作区注册',
      content: `移除工作区注册?该工作区下的任务数据将一并删除,工作区目录文件不受影响:${entry.root}`,
      okText: '移除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
  if (!confirmed) {
    return
  }
  try {
    await workspaceRegistry.remove(entry.workerId, entry.root)
  } catch {
    // 失败静默:注册表广播会带回最新状态;失败详情可从控制台网络请求排查。
  }
}

// ---- 内联图标(轻量,避免引入额外依赖) ----

function BranchIcon() {
  return <span style={{ fontSize: 12, marginRight: 2 }}>⎇</span>
}
function RefreshIcon() {
  return <span style={{ fontSize: 14 }}>⟳</span>
}
function PullIcon() {
  return <span style={{ fontSize: 14 }}>⇩</span>
}
function PushIcon() {
  return <span style={{ fontSize: 14 }}>⇧</span>
}

// ---- 布局样式(与资源管理器 OpenFilesSidebarPanel 分组卡片对齐) ----

const panelStyle: React.CSSProperties = {
  width: '100%',
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
  minWidth: 0,
  minHeight: 0,
  background: 'var(--bg-secondary)',
}

const bodyStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
}

const sectionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
}

const sectionHeaderActionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
}

const groupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '8px 6px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border-light)',
  background: 'var(--bg-primary)',
}

const groupToggleStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
  flex: 1,
  borderRadius: 'var(--radius-sm)',
}

/** 折叠/展开触发图标:仅点击该图标才折叠或展开(标题/路径等区域不触发)。 */
const groupChevronButtonStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  cursor: 'pointer',
  padding: 2,
  borderRadius: 'var(--radius-sm)',
  color: 'var(--text-muted)',
}

const groupChevronStyle: React.CSSProperties = {
  flexShrink: 0,
  color: 'var(--text-muted)',
  transition: 'transform 0.15s ease',
}

const groupChevronCollapsedStyle: React.CSSProperties = {
  transform: 'rotate(-90deg)',
}

const groupHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
}

const groupTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
  flex: '0 1 auto',
}

const groupBadgeStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-blue)',
  flexShrink: 0,
}

const workspaceRootStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  padding: '0 2px',
}

/** 头部行内分支胶囊:紧跟工作区名展示当前分支(折叠态也可见);长分支名内部省略。 */
const headerBranchPillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
  borderRadius: 999,
  padding: '1px 8px',
  fontSize: 'var(--text-xs)',
  fontFamily: 'var(--font-mono, monospace)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-muted)',
  flexShrink: 0,
  maxWidth: '45%',
  overflow: 'hidden',
}

/** 胶囊内分支名(flex 子项,省略号需落在自身)。 */
const headerBranchTextStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const iconButtonStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: 28,
  height: 28,
  fontSize: 'var(--text-sm)',
  color: 'var(--text-muted)',
}

const commitAreaStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  paddingBottom: 8,
  borderBottom: '1px solid var(--border-light)',
}

const changesHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexShrink: 0,
}

/** 多选模式工具条(标题行右缘):已选计数 + 全选/清空 + 完成。 */
const multiSelectBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 2,
  marginLeft: 'auto',
}

const multiSelectCountStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  whiteSpace: 'nowrap',
}

const miniButtonStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  height: 22,
  padding: '0 6px',
  color: 'var(--text-muted)',
}

const columnTitleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  color: 'var(--text-muted)',
}

const columnEmptyStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  padding: '8px 4px',
}

const diffLoadingStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  padding: '6px 4px',
}

const emptyStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 12,
  color: 'var(--text-muted)',
  padding: 16,
}

const emptyTextStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  textAlign: 'center',
}

const initCardStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  alignItems: 'flex-start',
  width: 160,
  padding: '12px 14px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border-light)',
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  cursor: 'pointer',
  textAlign: 'left',
}

const cardTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
}

const cardDescStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const cloneFormStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  maxWidth: 420,
  margin: '16px auto',
  padding: '0 8px',
}

const fieldLabelStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 600,
  color: 'var(--text-muted)',
  marginBottom: 4,
}
