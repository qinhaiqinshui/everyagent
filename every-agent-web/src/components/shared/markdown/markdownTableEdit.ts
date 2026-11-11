/**
 * Markdown 表格单元格编辑:源文本改写工具。
 *
 * 编辑定位基于 react-markdown 传入的 hast 节点 position offset(单元格源码区间),
 * 直接做区间替换而非按 `|` 重排列——天然规避单元格内 `\|` 转义竖线、内联
 * markdown 语法(`**粗体**`/`[链接](url)`)的序列化还原问题:编辑初值就是
 * 源片段本身,用户改什么写回什么。
 */

/** 一次单元格编辑提交请求(源文本 offset 区间替换)。 */
export type MarkdownTableCellEditRequest = {
  /** 单元格源文本起始 offset(hast position.start.offset)。 */
  start: number
  /** 单元格源文本结束 offset(hast position.end.offset,开区间)。 */
  end: number
  /** 编辑初值(进入编辑时的源片段),提交前校验源未被并发修改。 */
  original: string
  /** 编辑后文本(需先经 escapeTableCellText 转义)。 */
  next: string
}

/**
 * 把编辑框文本安全化为可写回 GFM 表格单元格的源文本:
 * 换行折叠为空格(单元格内不容纳块级换行);未转义的 `|` 补转义
 * (已是 `\|` 的保持原样,不二次转义)。
 */
export function escapeTableCellText(text: string): string {
  return text
    .replace(/\r?\n/g, ' ')
    .replace(/(?<!\\)\|/g, '\\|')
}

/**
 * 按 offset 区间替换源文本中的单元格内容。
 * 区间不合法、或源片段已与编辑初值不一致(文档被并发修改,如另一侧
 * CodeMirror 已改动)时返回 null,由调用方放弃本次提交(重渲染后用户重试)。
 */
export function replaceMarkdownRange(
  source: string,
  request: MarkdownTableCellEditRequest,
): string | null {
  const { start, end, original, next } = request
  if (!Number.isInteger(start) || !Number.isInteger(end)) return null
  if (start < 0 || end > source.length || start > end) return null
  if (source.slice(start, end) !== original) return null
  if (next === original) return source
  return source.slice(0, start) + next + source.slice(end)
}
