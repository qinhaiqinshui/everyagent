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
  /** 单元格源文本起始 offset(经 resolveCellRange 规范化,不含分隔符)。 */
  start: number
  /** 单元格源文本结束 offset(开区间,经 resolveCellRange 规范化)。 */
  end: number
  /** 编辑初值(进入编辑时的源片段),提交前校验源未被并发修改。 */
  original: string
  /** 编辑后文本(需先经 escapeTableCellText 转义)。 */
  next: string
}

/** 规范化后的单元格源区间(仅内容,边界分隔符已剥除)。 */
export type MarkdownTableCellRange = {
  start: number
  end: number
  original: string
}

/**
 * 规范化 hast td/th 的 position offset 区间为「纯单元格内容」区间。
 *
 * remark-gfm 产出的单元格节点区间包含边界分隔符:前导 `|` 与空白;行内
 * 最后一个单元格还包含行尾 `|`。直接用原始区间做编辑/替换会把分隔符吃进
 * 单元格(编辑初值开头出现 `|`,提交后与相邻单元格合并、行尾管道丢失)。
 * 这里把边界上未转义的 `|` 与空白逐个剥除;`\|` 转义竖线是内容,不动。
 * 全部剥空(空单元格)时返回零宽区间(original='')。
 */
export function resolveCellRange(
  source: string,
  rawStart: number,
  rawEnd: number,
): MarkdownTableCellRange | null {
  if (!Number.isInteger(rawStart) || !Number.isInteger(rawEnd)) return null
  if (rawStart < 0 || rawEnd > source.length || rawStart > rawEnd) return null

  let start = rawStart
  let end = rawEnd

  // 前导:空白与未转义 `|`(行首分隔符、与左格之间的分隔符)。
  while (start < end && /\s/.test(source[start])) start += 1
  while (start < end && source[start] === '|' && source[start - 1] !== '\\') {
    start += 1
    while (start < end && /\s/.test(source[start])) start += 1
  }

  // 尾部:空白与未转义 `|`(行内末格的行尾分隔符)。
  while (end > start && /\s/.test(source[end - 1])) end -= 1
  while (end > start && source[end - 1] === '|' && source[end - 2] !== '\\') {
    end -= 1
    while (end > start && /\s/.test(source[end - 1])) end -= 1
  }

  if (start > end) return null
  return { start, end, original: source.slice(start, end) }
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

/** 一次触摸/笔轻触的时空坐标(用于双击检测)。 */
export type TouchTap = {
  time: number
  x: number
  y: number
}

/**
 * 触屏双击(double-tap)检测:两次轻触间隔与位移都在阈值内才算双击。
 * 移动端 dblclick 事件不可靠(iOS Safari 常不派发),单元格编辑进入需自行判定;
 * 位移阈值放宽(40px)以容忍手指落点的自然抖动。
 */
export function isTouchDoubleTap(
  previous: TouchTap | null,
  next: TouchTap,
  maxIntervalMs = 350,
  maxDistancePx = 40,
): boolean {
  if (previous == null) return false
  if (next.time - previous.time > maxIntervalMs) return false
  const distance = Math.hypot(next.x - previous.x, next.y - previous.y)
  return distance <= maxDistancePx
}
