/**
 * 搜索目标注册表 · 类型定义。
 *
 * 搜索面板(选项显隐/文案/结果树选择)与状态机(useWorkspaceSearch 的请求路由)此前
 * 以 `isTasks` 布尔与 `'files' | 'tasks'` 字面量分支硬编码两套行为;本模块把「一个
 * 搜索目标是什么、支持什么、怎么发请求、怎么展示结果」收敛为一份声明式定义:
 * - supports 驱动面板选项显隐(fn 开关 / 范围行 / 包含排除过滤器,替代 isTasks);
 * - label / placeholder / introHint 驱动菜单与输入区文案;
 * - buildParams + execute 收敛原 run() 内的参数组装与 RPC 选择
 *   (fs.search / fs.find vs task.search);
 * - summarize / resultGroups / resultTree 驱动结果摘要与结果树渲染
 *   (两棵结果树组件保持原样,仅「选哪棵」的决策收进注册表)。
 * 面板与状态机只查注册表(getSearchTarget / resolveSearchTarget),不再按目标二值分支;
 * 未来新增目标只需增补一个注册项,无需再改面板与状态机的分支结构。
 */
import type { TaskContentSearchParams } from '@/query/taskContentSearch'
import type { WorkspaceSearchParams } from '@/platform/fs/workspaceGateway'
import type { WorkspaceSearchResultShape, WorkspaceSearchRunOptions } from '../useWorkspaceSearch'

/** 目标支持的匹配选项与文件语义选项(驱动面板选项显隐,替代 isTasks 判定)。 */
export interface SearchTargetSupports {
  /** 正则模式开关。 */
  regex: boolean
  /** 全字匹配开关。 */
  wholeWord: boolean
  /** 仅搜索文件名模式(fn 开关 / Alt+N;files 专属)。 */
  fileNameMode: boolean
  /** 包含/排除 glob 过滤器输入区(files 专属)。 */
  globs: boolean
  /** 子目录搜索范围(files 专属)。 */
  scope: boolean
}

/** 文案函数(placeholder / introHint)的输入快照。 */
export interface SearchTargetTextState {
  /** 「仅搜索文件名」开关当前值(不支持 fileNameMode 的目标忽略)。 */
  nameOnly: boolean
}

/** files 目标执行参数(buildParams 的成功产物;经 workspaceGateway 发 fs.search / fs.find)。 */
export interface FilesSearchExecution {
  kind: 'files'
  /** 匹配维度:content = 逐行搜内容,name = 仅按文件名匹配。 */
  matchMode: 'content' | 'name'
  /** 搜索范围所属工作区根(worker 机器绝对路径)。 */
  workspaceRoot: string
  /** fs.search / fs.find 入参。 */
  params: WorkspaceSearchParams
}

/** tasks 目标执行参数(经 searchTasksContent 发 task.search)。 */
export interface TasksSearchExecution {
  kind: 'tasks'
  /** 任务内容搜索目标 worker。 */
  workerId: string
  /** task.search 入参。 */
  params: TaskContentSearchParams
}

/** 目标执行参数判别联合。 */
export type SearchTargetExecution = FilesSearchExecution | TasksSearchExecution

/** buildParams 的失败产物(目标前置校验不满足,如任务模式缺 worker / workspaceId)。 */
export interface SearchTargetParamError {
  error: string
}

/** 结果分组提取(结果树折叠状态共用:files 分组键 = 文件路径,tasks = 任务 ID)。 */
export interface SearchTargetResultGroup {
  /** 分组键(折叠状态集合的元素)。 */
  key: string
  /** 分组内命中数(默认折叠策略:命中 > 10 的分组默认折叠)。 */
  matches: number
}

/** 搜索目标定义(注册表项)。 */
export interface SearchTargetDefinition {
  /** 目标 id('files' / 'tasks' / 未来扩展;事件与状态机均以 id 引用)。 */
  id: string
  /** 目标切换菜单项文案(如「搜索任务内容」)。 */
  label: string
  /** 支持的选项(驱动面板选项显隐)。 */
  supports: SearchTargetSupports
  /** 该目标为当前目标时,目标切换菜单项的高亮标记(沿用「任务模式高亮」的既有语义)。 */
  highlightMenuWhenCurrent: boolean
  /** 结果树选择:files → SearchResultsTree,tasks → TaskSearchResultsTree。 */
  resultTree: 'files' | 'tasks'
  /**
   * 本目标可消费的结果项 kind 集合(结果来源统一模型的可选增补字段,如 files 目标
   * ["file"]、tasks 目标 ["task"]):未来按 kind 过滤/分发混合来源结果的挂点,
   * 本期仅声明、不实现过滤 UI(结果项 kind 缺省仍按现有结构解释)。
   */
  consumableKinds?: string[]
  /** 输入框占位文案(绑定就绪时)。 */
  placeholder(state: SearchTargetTextState): string
  /** 空态引导文案(绑定就绪、输入为空时)。 */
  introHint(state: SearchTargetTextState): string
  /** 参数组装(原 run() 内按 target 分支的前置校验与入参构造);失败返回 { error }。 */
  buildParams(state: WorkspaceSearchRunOptions): SearchTargetExecution | SearchTargetParamError
  /** 执行搜索(原 run() 内的 query 函数选择:fs.search/fs.find vs task.search)。 */
  execute(execution: SearchTargetExecution): Promise<WorkspaceSearchResultShape>
  /** 结果摘要文案(底部状态行);matchMode 为本次搜索的匹配维度(files 专属语义,tasks 忽略)。 */
  summarize(result: WorkspaceSearchResultShape, matchMode: 'content' | 'name'): string
  /** 结果分组提取(新结果默认折叠策略与「折叠全部」共用)。 */
  resultGroups(result: WorkspaceSearchResultShape): SearchTargetResultGroup[]
}
