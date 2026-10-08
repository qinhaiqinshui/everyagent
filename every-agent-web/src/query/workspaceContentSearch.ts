/**
 * src/query/workspaceContentSearch.ts
 *
 * 工作区内容搜索的结果形状:WorkspaceContentSearchResult 等类型是 UI 与 worker 端
 * rg 搜索(fs.search / fs.find,见 platform/fs/workspaceGateway)共用的契约类型。
 * 搜索执行已在 worker 端完成,前端不保留纯算法匹配实现。
 */
/** 命中行的上下文行（行号 + 文本，结构化，便于 UI 渲染）。 */
export interface WorkspaceContentSearchContextLine {
  /** 1-based 行号。 */
  lineNumber: number
  /** 该行文本。 */
  line: string
}

/** 单条命中。 */
export interface WorkspaceContentSearchHit {
  /** 1-based 行号。 */
  lineNumber: number
  /** 命中行文本。 */
  line: string
  /** 命中片段在行内的起始列（0-based）；同一行多个命中时保留第一个（UI 逐行渲染）。 */
  matchIndex?: number
  /** 命中片段文本（与 matchIndex 配对）；供 UI 三段式渲染：前段 + 高亮段 + 后段。 */
  matchText?: string
  /** 命中行之前的上下文（beforeContext > 0 时填充）。 */
  before?: WorkspaceContentSearchContextLine[]
  /** 命中行之后的上下文（afterContext > 0 时填充）。 */
  after?: WorkspaceContentSearchContextLine[]
}

/** 单个文件的搜索结果。name 模式仅命中文件名时不带 matches。 */
export interface WorkspaceContentSearchFileResult {
  /** 文件路径。 */
  path: string
  /** content 模式下的命中列表；name 模式无此字段。 */
  matches?: WorkspaceContentSearchHit[]
  /**
   * 结果项种类(开放集合,如 "file"):结果来源统一模型的可选增补字段。
   * 未知值必须容忍忽略;缺省按现有结构解释(即文件结果)。
   */
  kind?: string
  /**
   * 结果来源 provider id:内置 rg 恒为 "builtin.rg",插件 provider 用其 id。
   * 可选增补字段,缺省视为内置来源(UI 不显示来源标记)。
   */
  providerId?: string
  /** 可选排序提示分;仅排序提示,前端不依它重排。 */
  score?: number
}

/** 搜索结果。 */
export interface WorkspaceContentSearchResult {
  /** 命中总数（触顶时为 maxResults；shouldStop 中断时保留已得值）。 */
  matchCount: number
  /** 是否提前停止：命中数到达上限，或 shouldStop 中断。 */
  truncated: boolean
  /** 按文件聚合的命中结果。 */
  files: WorkspaceContentSearchFileResult[]
}
