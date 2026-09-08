/** `/` 候选被选中后的内容放置位置。 */
export type SlashDisplayPosition = 'inline' | 'bottom'

/** `/` 候选被选中后的插入结果：要插入的 token/文本 + 放置位置。 */
export interface SlashSelectionResult {
  /** 要插入的 opaque token 串或纯文本。 */
  token: string
  /** 放置位置：'inline' → 原地插入 token/文本；'bottom' → 走底部胶囊（由调用方处理）。 */
  position: SlashDisplayPosition
  /**
   * 胶囊归属条目 id（可选）：多结果联动时用（如「无人值守」一次返回「无人值守」+「AI 审议」
   * 两个 bottom 胶囊，各自 id 指向各自注册条目，前端据此各自 apply / cancel）。
   * 缺省时回退父条目 id。
   */
  id?: string
}

/** `/` 浮层中的一个候选项。 */
export interface SlashCommandItem {
  /** 全局唯一 ID（用于 React key 与跨 provider 去重）。 */
  id: string
  /** 展示标题（必填）；也是胶囊显示文字的来源（经 select 写入 opaque 顶层 label）。 */
  title: string
  /** 副内容（可选）。 */
  subtitle?: string
  /** 内联 SVG 字符串（可选）。缺省使用通用图标。 */
  icon?: string
  /** 分组名（可选，直接作为展示标题）。缺省 → 收进统一无标题分组。 */
  group?: string
  /**
   * 新建任务时是否默认选中（默认 false）：true 时新建任务的草稿会预置该条目
   * （bottom 项预置底部胶囊，用户仍可 ✕ 取消；inline 项当前不做自动预置）。
   */
  defaultSelected?: boolean
  /**
   * 选中时调用，传入选中的 item 自身，返回单个或一组 `{ token, position, id? }`：
   * - `token` 为 opaque token 串或纯文本（isOpaqueTokenText(token)===true → 显示为胶囊，否则原样为纯文本）；
   * - `position` = 'inline' → 原地插入；= 'bottom' → 走底部胶囊（由调用方处理）；
   * - 数组支持一次返回多个 bottom 结果（联动场景，如「无人值守」同时返回双胶囊）。
   * slash 层不解析/不关心内容本质，只判断上述形态。
   */
  select: (
    item: SlashCommandItem,
  ) => SlashSelectionResult | SlashSelectionResult[] | Promise<SlashSelectionResult | SlashSelectionResult[]>
}

/** provider loader：返回该来源的全部条目（可异步）；workerId 为可选透传参数。 */
export type SlashCommandProvider = (workerId?: string) => SlashCommandItem[] | Promise<SlashCommandItem[]>

/** 可被发现并注册的 `/` 命令来源。 */
export interface SlashCommandProviderDefinition {
  id: string
  load: SlashCommandProvider
}
