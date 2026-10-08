export type MarkdownHeadingLevel = 1 | 2 | 3 | 4 | 5 | 6

export type MarkdownHeading = {
  id: string
  level: MarkdownHeadingLevel
  text: string
  line: number
}

export function matchMarkdownHeading(line: string): { level: MarkdownHeadingLevel; text: string } | null {
  const match = /^(#{1,6})\s+(.*)$/.exec(line)
  if (!match) return null
  const level = match[1].length
  if (level < 1 || level > 6) return null
  const text = match[2].trim()
  if (!text) return null
  return {
    level: level as MarkdownHeadingLevel,
    text,
  }
}

/**
 * 标题文本 → 锚点 slug,与 GitHub 的标题锚点(目录锚点的事实来源)同口径:
 * 1. 剥离行内 markdown 语法:链接/图片只保留显示文本(丢弃目标 URL);
 * 2. 小写;
 * 3. 只保留字母、数字、空白与 `-` `_`(标点、符号、emoji、变体选择符一律剔除——
 *    GitHub 正是这样产出 `#-架构速览` 这类以 `-` 开头的锚点;中文原样保留);
 * 4. 空白折叠为 `-`,再合并连续 `-` 并去掉首尾 `-`(对作者手写的 `#架构速览` 也更宽容)。
 *
 * 例:`## 🏗️ 架构速览` → `架构速览`,与 GitHub 生成的 `#-架构速览` 锚点可互相匹配;
 * `### 方式二:Docker(自己托管 hub + worker + web)` → `方式二docker自己托管-hub-worker-web`。
 */
export function slugifyMarkdownHeadingText(text: string): string {
  return stripMarkdownInlineTargets(text)
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .replace(/\s+/g, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
}

/** 剥离行内链接/图片的目标部分,只保留显示文本(`[文本](url)` → `文本`,`![alt](url)` → `alt`)。 */
function stripMarkdownInlineTargets(text: string): string {
  return text
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
}

export function buildMarkdownHeadingId(text: string, index: number): string {
  const slug = slugifyMarkdownHeadingText(text)
  return slug ? `md-heading-${index}-${slug}` : `md-heading-${index}`
}

export function parseMarkdownHeadings(content: string): MarkdownHeading[] {
  // 归一化 CRLF/CR 行尾：避免 matchMarkdownHeading 因行尾残留 \r 而全部失效（heading 解析失败）。
  const lines = content.replace(/\r\n?/g, '\n').split('\n')
  const headings: MarkdownHeading[] = []
  let inCodeBlock = false

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    if (line.startsWith('```')) {
      inCodeBlock = !inCodeBlock
      continue
    }
    if (inCodeBlock) continue
    const heading = matchMarkdownHeading(line)
    if (!heading) continue
    headings.push({
      id: buildMarkdownHeadingId(heading.text, headings.length),
      level: heading.level,
      text: heading.text,
      line: index,
    })
  }

  return headings
}

/**
 * 把链接锚点(#后的片段,如「-架构速览」「hard-guarantees」)解析为标题 id。
 *
 * 锚点形态:文档作者通常直接复制 GitHub 目录的锚点,而 react-markdown 会把链接 href
 * 里的非 ASCII 字符 percent 编码(如 `#-架构速览` → `#-%E6%9E%B6%E6%9E%84%E9%80%9F%E8%A7%88`),
 * 故匹配前先做 percent 解码。
 *
 * 匹配顺序:
 * 1. 片段(或其 percent 解码形式)恰为完整标题 id(复制自大纲的形态);
 * 2. 片段(或其解码形式)的 slug 与标题文本的 slug 等值——文档作者手写锚点
 *    通常即标题的 GitHub 风格 slug,与 buildMarkdownHeadingId 同一规则。
 * 找不到返回 null(调用方自行提示或忽略)。
 */
export function resolveMarkdownHeadingAnchor(headings: MarkdownHeading[], anchor: string): string | null {
  const trimmed = anchor.trim()
  if (!trimmed) return null
  const candidates: string[] = [trimmed]
  try {
    const decoded = decodeURIComponent(trimmed)
    if (decoded !== trimmed) candidates.push(decoded)
  } catch {
    /* 非法 percent 编码序列:按原文参与匹配 */
  }
  for (const candidate of candidates) {
    const exact = headings.find((heading) => heading.id === candidate)
    if (exact) return exact.id
  }
  for (const candidate of candidates) {
    const slug = slugifyMarkdownHeadingText(candidate)
    if (!slug) continue
    const hit = headings.find((heading) => slugifyMarkdownHeadingText(heading.text) === slug)
    if (hit) return hit.id
  }
  return null
}
