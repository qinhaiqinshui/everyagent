/**
 * 搜索目标 · 工作区文件(files,默认目标)。
 *
 * 数据源:worker 内置 rg 的 fs.search(内容)/ fs.find(文件名),搜索范围
 * (rootPath)经可选 path 参数下推给 rg(缺省整根);前端不编译正则、不逐目录
 * walk,不保留纯前端降级路径(需 worker ≥ fs.find 版本)。
 */
import { workspaceGateway, type WorkspaceSearchParams } from '@/platform/fs/workspaceGateway'
import { INTERNAL_DIR_NAMES, normalizeWorkspaceRelativePath } from '@/platform/fs/pathUtils'
import type { WorkspaceContentSearchResult } from '@/query/workspaceContentSearch'
import type { WorkspaceSearchResultShape } from '../useWorkspaceSearch'
import type { SearchTargetDefinition } from './types'

/** 内部保留目录(如 .git)的逗号串:includeInternalFiles=false 时追加进 excludeGlobs(rg --hidden 会进 .git)。 */
const INTERNAL_DIR_GLOBS = Array.from(INTERNAL_DIR_NAMES).join(',')

/** 命中上限(内容与文件名模式一致,文件名搜索按文件计)。 */
const MAX_RESULTS = 1000

export const filesTarget: SearchTargetDefinition = {
  id: 'files',
  label: '搜索工作区文件',
  supports: { regex: true, wholeWord: true, fileNameMode: true, globs: true, scope: true },
  highlightMenuWhenCurrent: false,
  resultTree: 'files',
  placeholder: (state) => (state.nameOnly ? '搜索文件名（支持正则）' : '搜索（支持正则）'),
  introHint: (state) => (state.nameOnly ? '输入关键词搜索工作区文件名' : '输入关键词搜索工作区文件内容'),
  buildParams(state) {
    // 文件搜索统一走 worker 内置 rg(架构 §7 契约表):内容 = fs.search、文件名 =
    // fs.find;搜索范围(rootPath,业务绝对形态先归一为工作区相对路径)经可选 path
    // 参数下推给 rg(缺省整根),不前端逐目录 walk(原路径中型仓库即数千次串行
    // fs.list RPC,且单个不可读条目会整树报错)。pattern 与开关原样透传,由 worker
    // 侧 rg / Java 正则语义解释(字面量 --fixed-strings / 全字 \b 包裹);非法正则
    // 已在 hook 的预检拦下。includeInternalFiles=false 时内部保留目录(如 .git)追加
    // 进排除 glob(rg --hidden 会进 .git)。
    const params: WorkspaceSearchParams = {
      pattern: state.pattern.trim(),
      isRegex: state.useRegex,
      caseSensitive: state.caseSensitive,
      wholeWord: state.wholeWord,
      includeGlobs: state.includePatterns,
      excludeGlobs: state.includeInternalFiles
        ? state.excludePatterns
        : [state.excludePatterns, INTERNAL_DIR_GLOBS].filter(Boolean).join(','),
      maxResults: MAX_RESULTS,
      path: normalizeWorkspaceRelativePath(state.rootPath),
    }
    return {
      kind: 'files',
      matchMode: state.matchMode ?? 'content',
      workspaceRoot: state.workspaceRoot,
      params,
    }
  },
  async execute(execution): Promise<WorkspaceSearchResultShape> {
    if (execution.kind !== 'files') {
      throw new Error(`files 搜索目标收到非法执行参数：${execution.kind}`)
    }
    return execution.matchMode === 'name'
      ? workspaceGateway.findNames(execution.workspaceRoot, execution.params)
      : workspaceGateway.search(execution.workspaceRoot, execution.params)
  },
  summarize(result, matchMode) {
    const fileResult = result as WorkspaceContentSearchResult
    const truncatedSuffix = fileResult.truncated ? '（已达上限，结果被截断）' : ''
    // 文件名搜索:每个命中即一个文件(fs.find 结果项无 matches),摘要以「N 个文件」表述。
    if (matchMode === 'name') {
      return `${fileResult.files.length} 个文件${truncatedSuffix}`
    }
    return `${fileResult.matchCount} 个结果 · ${fileResult.files.length} 个文件${truncatedSuffix}`
  },
  resultGroups(result) {
    return (result as WorkspaceContentSearchResult).files.map((file) => ({
      key: file.path,
      matches: file.matches?.length ?? 0,
    }))
  },
}
