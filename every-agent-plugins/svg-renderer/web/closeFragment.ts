/**
 * XML 流式片段容错闭合（svg-renderer 内部工具）。
 *
 * 输入是流式输出中截断在任意位置的 SVG 源码片段，输出是尽力补齐闭合标签后
 * 可整体解析的合法 XML 文档；连 `<svg` 根开始标签都尚未完整到达时返回 null。
 *
 * 容错策略（保守丢弃，不猜测修复）：
 *   1. 尾部残缺开始标签（截在标签名/属性中间）→ 从最后一个未配对的 `<` 截断；
 *   2. 尾部未闭合注释 `<!--` → 截到注释起点；
 *   3. 尾部不完整实体引用（`&am` 这种无分号尾巴）→ 截掉；
 *   4. 维护开标签栈，按逆序补闭合标签（含根 `</svg>`）。
 *
 * 已知边界（解析失败统一由调用方降级占位，不会崩溃）：
 *   - 属性值含裸 `>`（如 `data-x="a>b"`）会让标签栈误算，产出非法 XML；
 *   - 文本节点含未转义 `<` 同理。模型正常生成的 SVG 不会出现这两种病态。
 *
 * 为何放插件内：plugin-api 是纯类型包（零运行时代码），插件又禁止 import 宿主
 * 内部模块；同类能力将来若被 mermaid 等插件复用，再评估抽独立共享包。
 */

/** 标签起始定位：`<svg`、`<svg …`、`</svg`（后随空白或 `>`/`/` 才算命中，防 `<svgfoo>`）。 */
const SVG_ROOT_OPEN_RE = /<svg(?=[\s/>])/

/**
 * 单个完整标签（开始/结束/自闭合）。
 * 分组：1=是否闭标签斜杠；2=标签名；3=属性区（含自闭合尾斜杠）。
 * 属性值含裸 `>` 的病态输入会被误切，交由上层 parsererror 兜底。
 */
const TAG_RE = /<(\/?)([a-zA-Z][\w:.-]*)((?:"[^"]*"|'[^']*'|[^'"<>])*?)\/?>/g

/** 尾部不完整实体引用（`&`、`&am`、`&#12` 等无分号收尾）。 */
const TRAILING_ENTITY_RE = /&[a-zA-Z#0-9]{0,10}$/

/** 容错闭合结果。 */
export interface CloseFragmentResult {
  /** 尽力闭合后的合法 XML 文档；根标签尚未完整到达 / 无可渲染内容时为 null。 */
  closed: string | null
}

/** 截掉流式尾部的不完整部分（残缺标签 / 未闭合注释 / 半个实体），返回安全正文结束位置。 */
function safeBodyEnd(body: string): number {
  let end = body.length
  // 尾部未闭合注释：注释起点晚于最后一个注释终点 → 截到起点。
  const commentStart = body.lastIndexOf('<!--')
  if (commentStart !== -1 && commentStart > body.lastIndexOf('-->')) {
    end = Math.min(end, commentStart)
  }
  // 半个实体引用（`&am` → 截掉；完整 `&amp;` 不受影响）。
  const entity = TRAILING_ENTITY_RE.exec(body.slice(0, end))
  if (entity) {
    end = Math.min(end, end - entity[0].length)
  }
  // 残缺开始/结束标签：最后一个 `<` 晚于最后一个 `>` → 从 `<` 截断。
  // （截在纯文本中间时 lastLt < lastGt，不触发，无害。）
  const lastLt = body.lastIndexOf('<', end - 1)
  const lastGt = body.lastIndexOf('>', end - 1)
  if (lastLt > lastGt) {
    end = Math.min(end, lastLt)
  }
  return end
}

/** 容错闭合流式 SVG 片段。 */
export function closeSvgFragment(source: string): CloseFragmentResult {
  const trimmed = source.trim()
  if (!trimmed) {
    return { closed: null }
  }
  // 定位根开始标签；尚未到达 → 无可渲染内容。
  const rootMatch = SVG_ROOT_OPEN_RE.exec(trimmed)
  if (!rootMatch) {
    return { closed: null }
  }
  // 根开始标签必须已完整（有配对 `>`）；否则连 viewBox 都拿不到，等下一帧。
  TAG_RE.lastIndex = 0
  const rootTag = TAG_RE.exec(trimmed)
  if (!rootTag || rootTag.index !== rootMatch.index || rootTag[1] === '/') {
    return { closed: null }
  }
  const bodyStart = rootTag.index + rootTag[0].length
  const bodyEnd = safeBodyEnd(trimmed)
  const body = bodyEnd > bodyStart ? trimmed.slice(bodyStart, bodyEnd) : ''

  // 维护开标签栈，逆序补闭合（栈底即 svg 根自身）。
  // 自闭合判定用 m[0].endsWith('/>')：属性分组是惰性匹配，尾斜杠会被 `\/?>`
  // 优先消耗（<path d="a"/> 的分组不含 `/`），不能依赖分组内容判断。
  const stack: string[] = [rootTag[2]]
  TAG_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = TAG_RE.exec(body)) !== null) {
    const [, slash, name] = m
    if (slash) {
      // 闭标签：栈顶同名才弹（错位闭标签忽略，容错）。
      if (stack[stack.length - 1] === name) {
        stack.pop()
      }
    } else if (!m[0].endsWith('/>')) {
      // 非自闭合开始标签入栈。
      stack.push(name)
    }
  }

  const closers = stack
    .slice()
    .reverse()
    .map((name) => `</${name}>`)
    .join('')
  return { closed: trimmed.slice(rootTag.index, bodyStart) + body + closers }
}
