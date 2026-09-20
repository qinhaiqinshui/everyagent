import type { Delete, Parent, PhrasingContent, Root, Text } from 'mdast'
import type { Plugin } from 'unified'

/**
 * 删除线语法约定:仅**四个波浪号** `~~~~text~~~~` 渲染为删除线;
 * 单个/两个波浪号(GFM 默认删除线语法)一律按字面文本渲染。
 *
 * remark-gfm 的 strikethrough 只识别 1~2 个波浪号,micromark 对 3 个及以上
 * 的波浪号串不产出 delete 节点(行内 `~~~~` 原样保留为文本),因此本插件分两步:
 * 1. 把 gfm 已解析出的 delete 节点还原为字面波浪号文本(个数由 position 偏移推得);
 * 2. 在各父节点的行内子节点中配对「恰好 4 个波浪号」的分隔符,中间内容包成 delete 节点。
 *
 * 已知限制:`~~~~` 出现在行首时会被 CommonMark 优先解析为代码围栏,与删除线语法无法区分。
 */
export const remarkFourTildeStrikethrough: Plugin<[], Root> = () => {
  return (tree) => transformParent(tree)
}

/** 波浪号分隔符:恰好 4 个波浪号的最长连续串才算删除线分隔符。 */
const DELIMITER = '~~~~'
const TILDE_RUN = /~+/g

/** 先还原 gfm 删除线,再配对四波浪号分隔符,最后递归处理子节点。 */
function transformParent(parent: Parent): void {
  const restored = restoreGfmDelete(parent.children as PhrasingContent[])
  parent.children = pairFourTilde(restored) as Parent['children']
  for (const child of parent.children) {
    if ('children' in child) transformParent(child as Parent)
  }
}

/**
 * 第一步:把 gfm 解析出的 delete 节点还原为字面文本。
 * 波浪号个数由 position 偏移量推得(delete 节点跨度与首尾子节点跨度之差),
 * 保证 `~~x~~` 还原为 `~~x~~`、`~x~` 还原为 `~x~`,而非统一写死两个波浪号。
 */
function restoreGfmDelete(children: PhrasingContent[]): PhrasingContent[] {
  const out: PhrasingContent[] = []
  for (const node of children) {
    if (node.type !== 'delete') {
      out.push(node)
      continue
    }
    const { open, close } = tildeMarkersOf(node)
    if (open) out.push(text(open))
    out.push(...node.children)
    if (close) out.push(text(close))
  }
  return out
}

function tildeMarkersOf(node: Delete): { open: string; close: string } {
  const first = node.children[0]
  const last = node.children[node.children.length - 1]
  const openCount =
    node.position?.start.offset !== undefined && first?.position?.start.offset !== undefined
      ? first.position.start.offset - node.position.start.offset
      : 2
  const closeCount =
    node.position?.end.offset !== undefined && last?.position?.end.offset !== undefined
      ? node.position.end.offset - last.position.end.offset
      : 2
  return { open: '~'.repeat(openCount), close: '~'.repeat(closeCount) }
}

/**
 * 第二步:在行内子节点序列中配对 `~~~~` 分隔符。
 * 先拆分文本节点,把「恰好 4 个波浪号」的最长连续串隔离为独立分隔符文本节点
 * (其余长度的波浪号串保持字面文本);再线性扫描:首个分隔符开、下一个分隔符闭,
 * 中间内容包成 delete 节点;未配对的分隔符保留为字面文本。
 */
function pairFourTilde(children: PhrasingContent[]): PhrasingContent[] {
  const split: PhrasingContent[] = []
  for (const node of children) {
    if (node.type === 'text') splitTextNode(node, split)
    else split.push(node)
  }

  const out: PhrasingContent[] = []
  let openerIdx = -1
  for (const node of split) {
    if (isDelimiter(node)) {
      if (openerIdx < 0) {
        openerIdx = out.length
        out.push(node)
      } else {
        const inner = out.splice(openerIdx + 1)
        out.pop()
        const del: Delete = { type: 'delete', children: inner }
        out.push(del)
        openerIdx = -1
      }
    } else {
      out.push(node)
    }
  }
  return out
}

function isDelimiter(node: PhrasingContent): node is Text {
  return node.type === 'text' && node.value === DELIMITER
}

/** 把文本节点按波浪号连续串拆分;恰好 4 个的串成为独立分隔符节点。 */
function splitTextNode(node: Text, out: PhrasingContent[]): void {
  const { value } = node
  let last = 0
  for (const match of value.matchAll(TILDE_RUN)) {
    const index = match.index
    if (index > last) out.push(text(value.slice(last, index)))
    out.push(text(match[0]))
    last = index + match[0].length
  }
  if (last < value.length) out.push(text(value.slice(last)))
  if (value.length === 0) out.push(node)
}

function text(value: string): Text {
  return { type: 'text', value }
}
