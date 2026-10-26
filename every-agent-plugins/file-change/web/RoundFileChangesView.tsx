import { useEffect, useState, useSyncExternalStore } from 'react'
import type { RoundTailPanelProps } from '@everyagent/plugin-api'
import { CaretDownOutlined, CaretRightOutlined } from '@ant-design/icons'
import { getPluginContext } from './pluginRuntime'
import { FileTypeIcon } from './FileTypeGlyphs'
import './roundFileChanges.css'
import {
  ensureRoundChanges,
  getRoundChanges,
  getRoundChangesVersion,
  loadRoundFullChanges,
  subscribeRoundChanges,
  type RoundFileChangeSummary,
} from './roundChangesStore'

/** changeType 与 git 状态字母、中文含义的映射(A=Added/新建,M=Modified/修改,D=Deleted/删除)。 */
const CHANGE_GIT_LETTER: Record<
  RoundFileChangeSummary['changeType'],
  { letter: string; title: string }
> = {
  created: { letter: 'A', title: '新建 (Added)' },
  updated: { letter: 'M', title: '修改 (Modified)' },
  deleted: { letter: 'D', title: '删除 (Deleted)' },
}

/**
 * 归一为「完整业务路径」(带前导 / 的 '/a/b/c',与资源树行路径/文件标签 id 同一形态)。
 * worker 新版落盘的 filePath 已是锚定后的完整路径;旧数据是模型给的原始裸/相对路径
 * (根级文件与 fileName 同串,导致「路径」列退化为文件名),此处兜底补前导 / 并词法消解
 * '.'/'..' 段。Windows 盘符 / UNC 绝对路径(工作区外授权写)原样保留。
 * workspaceGateway 入参对该形态与相对形态都接受(normalize 时剥前导 /),diff 标签
 * 的 stat/read/定位不受影响。
 */
function toDisplayPath(filePath: string): string {
  const normalized = filePath.trim().replace(/\\/g, '/')
  if (!normalized) {
    return ''
  }
  if (/^[A-Za-z]:[\\/]/.test(normalized) || normalized.startsWith('//')) {
    return normalized
  }
  const segments: string[] = []
  for (const segment of normalized.split('/')) {
    if (!segment || segment === '.') continue
    if (segment === '..') {
      segments.pop()
      continue
    }
    segments.push(segment)
  }
  return `/${segments.join('/')}`
}

/**
 * 轮末文件变更视图:渲染该轮文件变更轻量摘要行(**文件图标** + 文件名 + 完整路径 + A/M/D 标签),
 * 一行两个点击目标:
 * - **点文件名(含文件图标)** → 经 `ctx.ui.openFileTab` 打开(或激活)该文件的**文件标签页**(读写模式);
 * - **点右侧完整路径** → 按 roundId 经 `task.fileChanges` 拉全文(含 beforeContent/afterContent),
 *   再开 **diff 标签**对比。
 * 删除的文件**文件名按钮禁用且不渲染图标**(文件已不在磁盘,打开必失败),该行只剩 diff 入口。
 *
 * **布局**:本组件不包根容器,汇总头 pill 与展开的文件行列表是宿主轮末工具栏行
 * (`.nagent-round-collapse__tail`,一条 wrap flex 行)的两个并列 flex 项——点击汇总头
 * 像切标签页:标签行(复制 / 文件变更)原地不动,详情列表另起一行**通栏占满整行宽度**
 * (见 roundFileChanges.css 的 `.task-file-changes__list { flex: 1 1 100% }`)。
 *
 * 数据不来自轮行(轮行不含插件业务字段,架构 §7.15.2),而来自插件自持的 roundChangesStore:
 * 真相源是本插件落盘的 `file-changes/<roundId>.json`,初次打开一次 RPC 拉全任务各轮轻量摘要
 * 按 taskId 缓存;运行中轮闭合的增量由 worker 直推 `plugin.file-change.roundChanges`(经宿主
 * `plugin-task-event` 领域事件透传,在 index.ts activate 里订阅 upsert,§7.15.2);
 * 全文按 roundId 缓存,同轮多次点击不重复 RPC。
 */
export default function RoundFileChangesView({
  taskId,
  roundId,
  workerId,
  workspaceRoot,
}: RoundTailPanelProps) {
  // 订阅插件缓存版本:摘要拉取完成 / 直推合入时重渲染本面板。
  // version 同时进 effect 依赖:直推合入只建未加载条目时,靠下一次 effect 触发全量补拉
  // (已加载/在途时 ensure 幂等直返,不会成环)。
  const cacheVersion = useSyncExternalStore(subscribeRoundChanges, getRoundChangesVersion, getRoundChangesVersion)

  // 历史任务/初次打开的全轮摘要兜底拉取(一次拉全按 taskId 缓存);运行中轮闭合的增量
  // 由 index.ts 订阅 plugin-task-event 直推 upsert,不在此处理——直推先于 round.closed
  // (refreshRounds)到达,本面板挂载时缓存必已含本轮,无需过期缓存自愈补丁(§7.15.2)。
  useEffect(() => {
    ensureRoundChanges(taskId, workerId)
  }, [taskId, workerId, cacheVersion])

  const changes = getRoundChanges(taskId, roundId) ?? []

  /** 默认折叠:只显示「文件变更」汇总头,点击展开文件行列表。 */
  const [open, setOpen] = useState(false)

  if (changes.length === 0) return null

  const openDiff = async (summary: RoundFileChangeSummary) => {
    let fullChanges: Awaited<ReturnType<typeof loadRoundFullChanges>>
    try {
      fullChanges = await loadRoundFullChanges(taskId, roundId, workerId)
    } catch (error) {
      console.warn(`[RoundFileChangesView] 拉取第 ${roundId} 轮文件变更全文失败(${taskId}):`, error)
      return
    }
    const full = fullChanges.find((c) => c.filePath === summary.filePath)
    if (!full) return
    const displayPath = toDisplayPath(full.filePath)
    getPluginContext().ui.openDiffTab({
      filePath: displayPath,
      fileName: full.fileName,
      changeType: full.changeType === 'created' ? 'created' : 'updated',
      beforeContent: full.beforeContent,
      afterContent: full.afterContent,
      title: `变更对比：${full.fileName}`,
      workspaceRoot,
    })
  }

  /**
   * 点文件名(含图标):打开(或激活)该文件的文件标签页。与宿主 diff 面板的「打开文件」同口径——
   * 读写模式(方便用户直接改 AI 写过的文件),归属工作区取任务挂靠的 workspaceRoot。
   * 调用点已保证「非删除 + workspaceRoot 已知」,此处再兜一层空路径守卫。
   */
  const openFile = (summary: RoundFileChangeSummary) => {
    const displayPath = toDisplayPath(summary.filePath)
    if (!workspaceRoot || !displayPath) return
    getPluginContext().ui.openFileTab(workspaceRoot, displayPath, { mode: 'readwrite' })
  }

  return (
    <>
      <button
        type="button"
        className="task-file-changes__toggle"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        title={open ? '收起文件变更' : '展开文件变更'}
      >
        <span className="task-file-changes__toggle-icon">
          {open ? <CaretDownOutlined /> : <CaretRightOutlined />}
        </span>
        <span className="task-file-changes__toggle-label">文件变更</span>
        <span className="task-file-changes__toggle-count">{changes.length}</span>
      </button>
      {open ? (
        <div className="task-file-changes__list">
          {changes.map((change) => {
            const displayPath = toDisplayPath(change.filePath)
            // 删除的文件禁用「打开文件」入口:磁盘上已无该路径,打开只会得到一个报 NOT_FOUND 的
            // 空标签页(与宿主 diff 面板打开前的存在性校验同一取舍,这里直接不给入口)。
            // 任务未挂靠工作区(历史/未关联场景)时同样禁用——openFileTab 必带工作区根。
            const canOpenFile = change.changeType !== 'deleted' && Boolean(workspaceRoot)
            const openTitle = canOpenFile
              ? `打开文件：${displayPath}`
              : change.changeType === 'deleted'
                ? '文件已删除，无法打开文件标签页'
                : '任务未关联工作区，无法打开文件标签页'
            return (
              <div key={`${taskId}:${roundId}:${displayPath}`} className="task-file-changes__row">
                {/* 打开文件按钮(图标槽 + 文件名):点文件名开文件标签页。
                    图标槽常驻(删除的行不渲染图标):槽位仍占宽,各行文件名纵向对齐。 */}
                <button
                  type="button"
                  className="task-file-changes__open"
                  disabled={!canOpenFile}
                  title={openTitle}
                  aria-label={`打开文件 ${change.fileName}`}
                  onClick={() => openFile(change)}
                >
                  <span className="task-file-changes__icon-slot">
                    {canOpenFile ? <FileTypeIcon fileName={change.fileName} size={15} /> : null}
                  </span>
                  <span className="task-file-changes__name">{change.fileName}</span>
                </button>
                {/* diff 路径按钮:点右侧完整路径开 diff 标签,占满剩余行宽。 */}
                <button
                  type="button"
                  className="task-file-changes__diff"
                  title={`查看变更对比：${displayPath}`}
                  onClick={() => void openDiff(change)}
                >
                  {/* 前缀 LRM(零宽强 LTR 字符):路径列 direction:rtl(长路径从左截断显尾),
                      前导 '/' 是中性字符会被 RTL 段落排到行尾(显示成 'foo.md/'),LRM 使整段
                      解析为单一 LTR 渲染串;title 供截断时 hover 查看完整路径。 */}
                  <span className="task-file-changes__path" title={displayPath}>
                    {'\u200E' + displayPath}
                  </span>
                </button>
                <span
                  className={`task-file-changes__tag task-file-changes__tag--${change.changeType}`}
                  title={CHANGE_GIT_LETTER[change.changeType].title}
                >
                  {CHANGE_GIT_LETTER[change.changeType].letter}
                </span>
              </div>
            )
          })}
        </div>
      ) : null}
    </>
  )
}
