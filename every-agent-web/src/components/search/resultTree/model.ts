/**
 * 通用搜索结果树原语 · 数据模型。
 *
 * 此前 SearchResultsTree(文件)与 TaskSearchResultsTree(任务)各维护一套
 * 「分组 + 命中行」渲染:buildLineSegments 整函数复制、MAX_LINE_LENGTH 常量抄
 * 两份、样式对象成对重复、折叠 Set 的键 files=文件路径 / tasks=任务 ID 双语义。
 * 本模块把两棵树的公共结构收敛为一份数据模型,两棵结果树退化为
 * 「目标结果 → 通用模型」的薄适配器:
 * - 折叠状态 Set<string> 的元素统一为 SearchResultGroup.key(单一语义);
 * - 组头(header)承载目标间的小差异展示数据:文件组头 = 文件图标 + 文件名 +
 *   相对目录,任务组头 = 任务标题 + 状态标签;
 * - 命中行(hits)承载正文 / 定位 / 打开动作,行内前缀标记(文件 = 行号,
 *   任务 = 轮次 + 字段)由 prefix 描述。
 */
/** 组头图标(目前仅 files:按文件名选型;任务组头无图标)。 */
export interface SearchResultGroupIcon {
  kind: 'file'
  /** 参与文件类型图标选型的文件名。 */
  fileName: string
}

/** 组头次要信息(灰字后缀):文件 = 相对目录,任务 = 状态标签。 */
export interface SearchResultGroupDetail {
  /** 展示文本。 */
  text: string
  /**
   * true 时占据组头剩余宽度(文件相对目录,超长省略,把命中数徽章推到最右);
   * false(缺省)仅随文排布不伸展(任务状态标签)。
   */
  grow?: boolean
}

/**
 * 内置搜索引擎 provider id 前缀(worker 对内置 provider 标记 `builtin.*`:
 * `builtin.file-content` / `builtin.file-name` / `builtin.task`)。
 * 组头来源标记的静默哨兵:`providerId` 缺省或以该前缀开头时不显示任何标记,
 * 保持内置搜索界面零变化;插件 provider 用其自身 id,组头显示轻量来源标记。
 */
export const BUILTIN_PROVIDER_ID_PREFIX = 'builtin.'

/** 是否内置 provider(缺省或 `builtin.*`):是则组头不显示来源标记。 */
export function isBuiltinProviderId(providerId?: string): boolean {
  return !providerId || providerId.startsWith(BUILTIN_PROVIDER_ID_PREFIX)
}

/** 组头渲染数据(路径 / 任务标题 + 状态)。 */
export interface SearchResultGroupHeader {
  /** 组头悬停提示(文件全路径 / 任务标题)。 */
  title: string
  /** 主名(粗体、超长省略):文件名 / 任务标题。 */
  name: string
  /** 次要信息(可选)。 */
  detail?: SearchResultGroupDetail
  /** 组头图标(可选)。 */
  icon?: SearchResultGroupIcon
  /**
   * 结果来源 provider id(可选,由结果树的适配层从结果项映射):内置 provider
   * (isBuiltinProviderId,即缺省或 `builtin.*`)时组头不显示来源标记;外部 provider id 时
   * 在 detail 区尾部显示轻量来源 tag(title 提示完整来源)。
   */
  providerId?: string
  /**
   * 叶子组(组内命中列表为空)的组头点击动作:file-name 等「每个命中即一个条目、
   * 无子命中行」的类型把组头本身作为可点击行(打开文件);缺省时叶子组头不可点击。
   */
  onOpen?: () => void
}

/** 命中行前缀标记(右对齐灰字:文件 = 行号,任务 = 轮次 + 字段)。 */
export interface SearchResultHitPrefix {
  /** 前缀文本。 */
  text: string
  /** 最小宽度(文件行号 30 / 任务轮次标记 84,对齐两棵树的既有排版)。 */
  minWidth?: number
}

/** 单条命中(通用形态)。 */
export interface SearchResultHit {
  /** 命中行正文(已含上下文)。 */
  label: string
  /** 文件行号(任务命中无)。 */
  line?: number
  /** 行内前缀标记(可选)。 */
  prefix?: SearchResultHitPrefix
  /** 命中片段在 label 内的起始列(0-based;-1 = 无可定位命中,整行不高亮)。 */
  matchIndex: number
  /** 命中片段文本(空串 = 不高亮;非空时 label 内全部出现位置均高亮)。 */
  matchText: string
  /** 命中行悬停提示。 */
  title?: string
  /** 打开:文件定位行 / 打开任务。 */
  onOpen(): void
}

/** 一个可折叠组(一个文件 / 一个任务)。 */
export interface SearchResultGroup {
  /** 统一 groupKey(折叠 Set 单一语义:files = 文件路径,tasks = 任务 ID)。 */
  key: string
  /**
   * 组所属结果类别(kind,如 `file-content` / `file-name` / `task` / 插件自定)。
   * 消费方据此把组派发给对应类型的默认树或插件 `ResultView`;缺省视为未知。
   */
  kind?: string
  /** 组头渲染数据。 */
  header: SearchResultGroupHeader
  /** 组内命中列表(长度即组头命中数徽章)。 */
  hits: SearchResultHit[]
}
