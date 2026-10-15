/**
 * 设置页「Skill 列表」表格(架构 §7.17 暴露控制)。
 *
 * - 数据源:对每台已连 worker 并发 `skill.list` → `[{id,name,description,knowledgePath,blacklist?,whitelist?}]`,
 *   **按 worker 分组展示**(不同 worker 的 skillsDir 不同,同名 id 不是同一个 skill,不跨 worker 合并);
 * - 自动暴露列:单元格显示「全部工作区 / 排除 N 个 / 仅限 N 个 / 排除N 仅限M」,点击弹配置框,
 *   同时显示黑名单和白名单两组勾选列表(黑名单=勾选的工作区不自动暴露;白名单=非空时仅勾选的暴露),
 *   黑名单优先(同时命中时按黑名单处理),确定 → 该 worker 的 `skill.exposure.set`(blacklist + whitelist);
 *   未注册但存在于配置中的工作区不展示、保存时原样保留(防打开一次弹窗就清掉历史配置);
 * - 详情列:经 `openGlobalFileTab` 以**可编辑**(`mode: 'readwrite'`)标签页打开 skill.md——
 *   用户点「打开」即可直接改正文/脚本引用,不经二次点「编辑」;读写走既有 fs.readRaw/fs.writeRaw
 *   绝对路径通道(人工行为,不经沙箱/权限门,`skills/` 本身也是免授权读写例外,§7.17),
 *   workspaceRoot 仅作 tab 组织锚点。同一 skill.md 的标签已存在时按同 id upsert,模式被本次
 *   打开覆盖为 readwrite(从别处只读打开过也能就地升级);
 *   注意:内置/插件包是物化产物,worker 重启/插件 activate 时按内容逐字节比对会**覆盖**掉人工
 *   修改(用户自制 skill 目录不受影响);表格的 name/描述列来自 `SkillCatalog` 首次扫描的内存
 *   快照,改 frontmatter 后要重启 worker 才会在表格/`/` 菜单上体现(正文改动立即生效,AI 按需读盘);
 * - 刷新:`config.changed{keys:["skills"]}` 广播定向刷新来源 worker(机制同 modelConfigs),
 *   worker 连接集合变化时全量重载;组件卸载时清理全部订阅。
 */
import React from 'react'
import { Button, Checkbox } from '@/components/shared/ui'
import ConfirmDialog from '@/components/shared/ConfirmDialog'
import { useHub } from '@/hub/HubProvider'
import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { useAppUi } from '@/components/app/AppUiContext'
import { onWorkerAliasesChanged, workerDisplayName } from '@/settings/workerAliases'

/** worker skill.list 应答单项(wire 形状)。 */
interface SkillInfo {
  id: string
  /** 展示标题(frontmatter name;缺省回落目录名)。 */
  name: string
  description: string
  /** skill.md 的 worker 机器绝对路径。 */
  knowledgePath: string
  /** 黑名单:列出的工作区不自动暴露该 skill。 */
  blacklist?: string[]
  /** 白名单:非空时仅列出的工作区自动暴露,其余不暴露;空=不限制。 */
  whitelist?: string[]
}

/** 单台 worker 的 skill 分组状态。 */
interface SkillGroupState {
  status: 'loading' | 'loaded' | 'error'
  skills: SkillInfo[]
  error: string
}

function emptyGroup(status: SkillGroupState['status'], error = ''): SkillGroupState {
  return { status, skills: [], error }
}

/** skill.list 应答形状校验(异常形状静默过滤,不让单条脏数据拖垮整组)。 */
function parseSkillList(result: unknown): SkillInfo[] {
  if (!Array.isArray(result)) return []
  const out: SkillInfo[] = []
  for (const item of result) {
    if (!item || typeof item !== 'object') continue
    const rec = item as Record<string, unknown>
    if (typeof rec.id !== 'string' || rec.id === '') continue
    // 保持 worker 返回顺序(与 `/` 菜单同源同序),前端不重排。
    out.push({
      id: rec.id,
      name: typeof rec.name === 'string' ? rec.name : '',
      description: typeof rec.description === 'string' ? rec.description : '',
      knowledgePath: typeof rec.knowledgePath === 'string' ? rec.knowledgePath : '',
      blacklist: Array.isArray(rec.blacklist)
        ? rec.blacklist.filter((root): root is string => typeof root === 'string')
        : undefined,
      whitelist: Array.isArray(rec.whitelist)
        ? rec.whitelist.filter((root): root is string => typeof root === 'string')
        : undefined,
    })
  }
  return out
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return typeof error === 'string' ? error : '未知错误'
}

/** 行内标题展示名:name 为空回落 id。 */
function skillLabel(skill: SkillInfo): string {
  return skill.name || skill.id
}

export default function SkillListTable() {
  const hub = useHub()
  const { showToast } = useAppUi()
  const { openGlobalFileTab } = useWorkspaceShell()

  /** workerId → 该 worker 的 skill 分组(加载中/已加载/失败互不影响)。 */
  const [groups, setGroups] = React.useState<Record<string, SkillGroupState>>({})
  /** 打开中的自动暴露配置框(workerId + skill 快照)。 */
  const [dialog, setDialog] = React.useState<{ workerId: string; skill: SkillInfo } | null>(null)
  /** 配置框黑名单勾选态(已勾选的工作区根集合=不暴露)。 */
  const [checkedBlacklist, setCheckedBlacklist] = React.useState<Set<string>>(new Set())
  /** 配置框白名单勾选态(已勾选的工作区根集合=仅暴露)。 */
  const [checkedWhitelist, setCheckedWhitelist] = React.useState<Set<string>>(new Set())
  const [savingExclusions, setSavingExclusions] = React.useState(false)

  // 别名/注册表是组件外单例:订阅变化触发重渲染(组头显示名、配置框工作区列表)。
  const [, setAliasesVersion] = React.useState(0)
  React.useEffect(() => onWorkerAliasesChanged(() => setAliasesVersion((v) => v + 1)), [])
  const [, setRegistryVersion] = React.useState(0)
  React.useEffect(
    () => workspaceRegistry.subscribe(() => setRegistryVersion((v) => v + 1)),
    [],
  )

  const connectedWorkerIds = React.useMemo(
    () => hub.directory.filter((w) => w.connected).map((w) => w.workerId).sort(),
    [hub.directory],
  )
  // config.changed 来源判不出来源时的全量校准兜底需要最新集合,镜像一份 ref。
  const connectedWorkerIdsRef = React.useRef(connectedWorkerIds)
  connectedWorkerIdsRef.current = connectedWorkerIds

  /** 定向拉取单台 worker 的 skill 列表(独立成败,不牵连其他组)。 */
  const loadWorkerSkills = React.useCallback(async (workerId: string) => {
    setGroups((prev) => ({ ...prev, [workerId]: emptyGroup('loading') }))
    try {
      const result = await hubSession.rpcTo(workerId, 'skill.list') as unknown
      setGroups((prev) => ({
        ...prev,
        [workerId]: { status: 'loaded', skills: parseSkillList(result), error: '' },
      }))
    } catch (error) {
      setGroups((prev) => ({
        ...prev,
        [workerId]: { status: 'error', skills: [], error: errorMessage(error) },
      }))
    }
  }, [])

  // 连接集合变化(连接/断开/重连):裁掉已断开 worker 的分组并全量重载
  // (与 modelConfigs 的 refreshAllConnected 姿态一致)。
  const workerSetKey = connectedWorkerIds.join('\n')
  React.useEffect(() => {
    const ids = workerSetKey === '' ? [] : workerSetKey.split('\n')
    setGroups((prev) => {
      let changed = ids.length !== Object.keys(prev).length
      const next: Record<string, SkillGroupState> = {}
      for (const id of ids) {
        if (!prev[id]) changed = true
        next[id] = prev[id] ?? emptyGroup('loading')
      }
      return changed ? next : prev
    })
    for (const id of ids) {
      void loadWorkerSkills(id)
    }
  }, [workerSetKey, loadWorkerSkills])

  // config.changed{keys:["skills"]} 广播 → 定向刷新来源 worker(机制同 modelConfigs);
  // 本页保存 / 其他端修改都会触发该广播。卸载时清理订阅。
  React.useEffect(() => {
    const unsubscribe = hubSession.onFrame((frame) => {
      if (frame.event !== 'config.changed') return
      const keys: unknown = (frame.payload as Record<string, unknown> | null)?.keys
      if (!Array.isArray(keys) || !keys.includes('skills')) return
      const workerId = hubSession.workerIdOfFrame(frame)
      if (workerId) {
        void loadWorkerSkills(workerId)
        return
      }
      // 判不出来源(如目录连接帧):全量校准兜底。
      for (const id of connectedWorkerIdsRef.current) {
        void loadWorkerSkills(id)
      }
    })
    return unsubscribe
  }, [loadWorkerSkills])

  /** 组头显示名:别名 > hostname > workerId(与 Worker 列表同口径)。 */
  const workerNameOf = (workerId: string): string => {
    const info = hub.directory.find((w) => w.workerId === workerId)
    return info ? workerDisplayName(info) : workerId
  }

  /** 该 worker 来源的已注册工作区(配置框展示列表)。 */
  const displayedWorkspacesOf = (workerId: string) => workspaceRegistry.workspacesOf(workerId)

  // ---- 自动暴露配置框 ----

  const openExclusionDialog = (workerId: string, skill: SkillInfo) => {
    // 打开时:勾选态 = 当前配置的工作区 ∩ 展示的已注册工作区;
    // 不在展示列表内的配置项(已删/未注册)不展示,保存时原样保留。
    const displayedRoots = new Set(displayedWorkspacesOf(workerId).map((entry) => entry.root))
    const currentBlacklist = skill.blacklist ?? []
    const currentWhitelist = skill.whitelist ?? []
    setCheckedBlacklist(new Set(currentBlacklist.filter((root) => displayedRoots.has(root))))
    setCheckedWhitelist(new Set(currentWhitelist.filter((root) => displayedRoots.has(root))))
    setDialog({ workerId, skill })
  }

  const toggleBlacklistRoot = (root: string, checked: boolean) => {
    setCheckedBlacklist((prev) => {
      const next = new Set(prev)
      if (checked) next.add(root)
      else next.delete(root)
      return next
    })
  }

  const toggleWhitelistRoot = (root: string, checked: boolean) => {
    setCheckedWhitelist((prev) => {
      const next = new Set(prev)
      if (checked) next.add(root)
      else next.delete(root)
      return next
    })
  }

  const handleSaveExclusions = async () => {
    if (!dialog || savingExclusions) return
    const { workerId, skill } = dialog
    const displayedRoots = new Set(displayedWorkspacesOf(workerId).map((entry) => entry.root))
    // 保存时:勾选项 ∪ 当前配置中不在展示列表内的项(原样保留,防清掉历史配置)。
    const preservedBlack = (skill.blacklist ?? []).filter((root) => !displayedRoots.has(root))
    const preservedWhite = (skill.whitelist ?? []).filter((root) => !displayedRoots.has(root))
    const blacklist = [...checkedBlacklist, ...preservedBlack]
    const whitelist = [...checkedWhitelist, ...preservedWhite]
    setSavingExclusions(true)
    try {
      await hubSession.rpcTo(workerId, 'skill.exposure.set', {
        skillId: skill.id,
        blacklist,
        whitelist,
      })
      showToast('已保存「' + skillLabel(skill) + '」的自动暴露配置', 'success')
      setDialog(null)
      // config.changed{keys:["skills"]} 广播会触发表格刷新;此处再定向拉一次兜底(幂等)。
      void loadWorkerSkills(workerId)
    } catch (error) {
      showToast('保存自动暴露配置失败:' + errorMessage(error), 'error')
    } finally {
      setSavingExclusions(false)
    }
  }

  const renderDialogBody = (): React.ReactNode => {
    if (!dialog) return null
    const workspaces = displayedWorkspacesOf(dialog.workerId)
    if (workspaces.length === 0) {
      return (
        <p style={hintStyle}>该 worker 暂无已注册的工作区——默认全部工作区自动暴露,暂无可配置项。</p>
      )
    }
    return (
      <div style={dialogBodyStyle}>
        <div style={sectionStyle}>
          <div style={sectionHeaderStyle}>
            <span style={sectionTitleStyle}>黑名单</span>
            <span style={sectionHintStyle}>勾选的工作区不自动暴露该 skill(黑名单优先)</span>
          </div>
          <div style={wsListStyle}>
            {workspaces.map((entry) => {
              const missing = Boolean(entry.missing)
              return (
                <label key={entry.root} style={wsRowStyle(missing)} title={entry.root}>
                  <Checkbox
                    checked={checkedBlacklist.has(entry.root)}
                    disabled={missing || savingExclusions}
                    onChange={(event) => toggleBlacklistRoot(entry.root, event.target.checked)}
                  />
                  <span style={wsRootStyle(missing)}>{entry.root}</span>
                  {missing ? <span style={wsMissingBadgeStyle}>目录缺失,不可选</span> : null}
                </label>
              )
            })}
          </div>
        </div>
        <div style={sectionStyle}>
          <div style={sectionHeaderStyle}>
            <span style={sectionTitleStyle}>白名单</span>
            <span style={sectionHintStyle}>非空时仅勾选的工作区自动暴露;空 = 不限制</span>
          </div>
          <div style={wsListStyle}>
            {workspaces.map((entry) => {
              const missing = Boolean(entry.missing)
              return (
                <label key={entry.root} style={wsRowStyle(missing)} title={entry.root}>
                  <Checkbox
                    checked={checkedWhitelist.has(entry.root)}
                    disabled={missing || savingExclusions}
                    onChange={(event) => toggleWhitelistRoot(entry.root, event.target.checked)}
                  />
                  <span style={wsRootStyle(missing)}>{entry.root}</span>
                  {missing ? <span style={wsMissingBadgeStyle}>目录缺失,不可选</span> : null}
                </label>
              )
            })}
          </div>
        </div>
      </div>
    )
  }

  // ---- 详情 ----

  const handleOpenKnowledge = (workerId: string, skill: SkillInfo) => {
    const entries = displayedWorkspacesOf(workerId)
    // workspaceRoot 只作文件标签页的组织锚点(读写走 fs.readRaw/fs.writeRaw 绝对路径),取任一已注册
    // 工作区即可;优先非 missing 的工作区。无任何已注册工作区时无法打开。
    const anchor = entries.find((entry) => !entry.missing) ?? entries[0]
    if (!anchor) {
      showToast('该 worker 没有已注册的工作区,无法打开 skill 详情', 'error')
      return
    }
    // 以可编辑模式打开:详情页即编辑位(改正文/改 frontmatter 一处完成),不再要求先点标签页里的「编辑」。
    openGlobalFileTab(
      { workspaceRoot: anchor.root, filePath: skill.knowledgePath },
      { mode: 'readwrite' },
    )
  }

  // ---- 渲染 ----

  if (connectedWorkerIds.length === 0) {
    return (
      <p style={hintStyle}>无已连接的 worker——连接后自动读取各 worker 的 skill 列表。</p>
    )
  }

  const renderGroup = (workerId: string): React.ReactNode => {
    const group = groups[workerId] ?? emptyGroup('loading')
    if (group.status === 'loading') {
      return <p style={hintStyle}>读取中…</p>
    }
    if (group.status === 'error') {
      return (
        <div style={groupErrorStyle}>
          <span>读取失败:{group.error || '未知错误'}(老 worker 可能不含 skill.list)</span>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => void loadWorkerSkills(workerId)}
          >
            重试
          </Button>
        </div>
      )
    }
    if (group.skills.length === 0) {
      return <p style={hintStyle}>该 worker 暂无 skill(系统技能目录为空)。</p>
    }
    return (
      <div style={tableWrapStyle}>
      <table style={tableStyle}>
        <thead>
          <tr>
            <th style={thStyle}>id</th>
            <th style={thStyle}>name</th>
            <th style={{ ...thStyle, width: 'auto' }}>描述</th>
            <th style={thStyle}>自动暴露</th>
            <th style={thStyle}>详情</th>
          </tr>
        </thead>
        <tbody>
          {group.skills.map((skill) => {
            const bl = skill.blacklist ?? []
            const wl = skill.whitelist ?? []
            const hasConfig = (skill.blacklist !== undefined || skill.whitelist !== undefined)
              && (bl.length > 0 || wl.length > 0)
            const blCount = bl.length
            const wlCount = wl.length
            let label: string
            let title: string
            if (!hasConfig) {
              label = '全部工作区'
              title = '全部工作区自动暴露;点击配置'
            } else {
              const parts: string[] = []
              if (blCount > 0) parts.push('排除 ' + blCount + ' 个')
              if (wlCount > 0) parts.push('仅限 ' + wlCount + ' 个')
              label = parts.join(' / ') || '全部工作区'
              const detailParts: string[] = []
              if (blCount > 0) detailParts.push('黑名单(不暴露):\n' + bl.join('\n'))
              if (wlCount > 0) detailParts.push('白名单(仅暴露):\n' + wl.join('\n'))
              title = detailParts.join('\n\n')
            }
            const isActive = hasConfig
            return (
              <tr key={skill.id}>
                <td style={idTdStyle} title={skill.id}>{skill.id}</td>
                <td style={nameTdStyle} title={skill.name}>{skill.name || '—'}</td>
                <td style={descTdStyle} title={skill.description || undefined}>
                  {skill.description || '—'}
                </td>
                <td style={cellTdStyle}>
                  <button
                    type="button"
                    style={isActive ? exposureActiveStyle : exposureIdleStyle}
                    title={title}
                    onClick={() => openExclusionDialog(workerId, skill)}
                  >
                    {label}
                  </button>
                </td>
                <td style={cellTdStyle}>
                  <button
                    type="button"
                    style={linkButtonStyle}
                    title={(skill.knowledgePath ? skill.knowledgePath + '\n' : '') + '以可编辑模式打开 skill.md'}
                    onClick={() => handleOpenKnowledge(workerId, skill)}
                  >
                    打开
                  </button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      </div>
    )
  }

  return (
    <div style={wrapStyle}>
      {connectedWorkerIds.map((workerId) => {
        const group = groups[workerId] ?? emptyGroup('loading')
        return (
          <div key={workerId} style={groupStyle}>
            <div style={groupHeaderStyle}>
              <span style={groupTitleStyle}>{workerNameOf(workerId)}</span>
              <span style={groupSubStyle} title={workerId}>{workerId}</span>
              <span style={groupMetaStyle}>
                {group.status === 'loading'
                  ? '读取中…'
                  : group.status === 'error'
                    ? '读取失败'
                    : group.skills.length + ' 项'}
              </span>
            </div>
            {renderGroup(workerId)}
          </div>
        )
      })}
      <ConfirmDialog
        open={Boolean(dialog)}
        title={'配置自动暴露:' + (dialog ? skillLabel(dialog.skill) : '')}
        subtitle="黑名单=勾选的工作区不暴露(优先);白名单=非空时仅勾选的暴露。两个清单可同时使用。(`/` 菜单不受影响,永远全量。)"
        confirmLabel={savingExclusions ? '保存中…' : '保存'}
        hideDefaultMessageBlock
        body={renderDialogBody()}
        onConfirm={() => void handleSaveExclusions()}
        onCancel={() => {
          if (!savingExclusions) setDialog(null)
        }}
      />
    </div>
  )
}

// ---- 样式(与设置页现有表格/弹窗风格一致,复用设计令牌,不引新依赖) ----

const wrapStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  maxWidth: 860,
}

const groupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
}

const groupHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 8,
  minWidth: 0,
}

const groupTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  whiteSpace: 'nowrap',
}

const groupSubStyle: React.CSSProperties = {
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
}

const groupMetaStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  whiteSpace: 'nowrap',
  flexShrink: 0,
}

const groupErrorStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-red)',
  border: '1px solid var(--accent-red)',
  borderRadius: 'var(--radius-md)',
  padding: '8px 10px',
  maxWidth: 720,
  lineHeight: 1.5,
}

const tableWrapStyle: React.CSSProperties = {
  overflowX: 'auto',
  maxWidth: '100%',
}

const tableStyle: React.CSSProperties = {
  borderCollapse: 'collapse',
  width: '100%',
  minWidth: 600,
  tableLayout: 'fixed',
}

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--text-muted)',
  borderBottom: '1px solid var(--border)',
  padding: '5px 8px',
  whiteSpace: 'nowrap',
  width: 120,
}

const idTdStyle: React.CSSProperties = {
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-primary)',
  borderBottom: '1px solid var(--border-light)',
  padding: '6px 8px',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const nameTdStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-primary)',
  borderBottom: '1px solid var(--border-light)',
  padding: '6px 8px',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const descTdStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-secondary)',
  borderBottom: '1px solid var(--border-light)',
  padding: '6px 8px',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const cellTdStyle: React.CSSProperties = {
  borderBottom: '1px solid var(--border-light)',
  padding: '4px 8px',
  whiteSpace: 'nowrap',
}

const exposureIdleStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
  padding: '2px 6px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
}

const exposureActiveStyle: React.CSSProperties = {
  ...exposureIdleStyle,
  color: 'var(--accent-amber)',
  fontWeight: 700,
}

const linkButtonStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: 'var(--accent-blue)',
  fontSize: 'var(--text-xs)',
  padding: '2px 6px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
}

const hintStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const wsListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
}

const dialogBodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
}

const sectionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '8px 10px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border-light)',
  background: 'var(--bg-secondary, transparent)',
}

const sectionHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 8,
}

const sectionTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  whiteSpace: 'nowrap',
}

const sectionHintStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const wsRowStyle = (missing: boolean): React.CSSProperties => ({
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '5px 8px',
  borderRadius: 'var(--radius-md)',
  cursor: missing ? 'default' : 'pointer',
  opacity: missing ? 0.55 : 1,
})

const wsRootStyle = (missing: boolean): React.CSSProperties => ({
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: 'var(--text-xs)',
  color: missing ? 'var(--text-muted)' : 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
  textDecoration: missing ? 'line-through' : 'none',
})

const wsMissingBadgeStyle: React.CSSProperties = {
  marginLeft: 'auto',
  flexShrink: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  whiteSpace: 'nowrap',
}
