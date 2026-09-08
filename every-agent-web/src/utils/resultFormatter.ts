/**
 * 任务产物格式化工具
 *
 * 将 AI 返回的结果对象转换为可读的 Markdown 文本，
 * 用于：
 * 1. 后续任务的上下文中（AI 可以看到前序任务产物）
 * 2. Markdown 产物文件的内容
 */

/** 常见产物字段映射到中文标题。 */
const FIELD_LABELS: Record<string, string> = {
  title: '标题',
  name: '名称',
  summary: '摘要',
  description: '说明',
  content: '内容',
  result: '结果',
  analysis: '分析',
  review: '审阅',
  suggestions: '建议',
}

/**
 * 将 AI 返回的原始结果格式化为可读的 Markdown 文本
 *
 * 处理策略：
 * 1. 字符串 → 直接返回
 * 2. 对象含 text/content/result 等长文本字段 → 提取该字段
 * 3. 对象含常见结构化字段 → 按字段排版为 Markdown 章节
 * 4. 其他 → JSON.stringify
 */
export function formatResultToMarkdown(result: unknown): string {
  if (typeof result === 'string') return result

  if (typeof result !== 'object' || result === null) {
    return String(result)
  }

  const r = result as Record<string, unknown>

  // 优先提取主内容字段（长文本）
  const mainTextFields = ['text', 'content', 'result']
  for (const f of mainTextFields) {
    if (typeof r[f] === 'string' && (r[f] as string).length > 50) {
      return r[f] as string
    }
  }

  // 按常见产物字段排版为 Markdown
  const parts: string[] = []
  for (const [key, value] of Object.entries(r)) {
    if (value === undefined || value === null || value === '') continue
    const label = FIELD_LABELS[key] ?? key

    if (typeof value === 'string') {
      if (value.length > 200) {
        // 长文本 → 独立小结
        parts.push(`## ${label}\n\n${value}`)
      } else {
        parts.push(`**${label}**：${value}`)
      }
    } else if (Array.isArray(value)) {
      parts.push(`## ${label}\n\n${formatArray(value)}`)
    } else if (typeof value === 'object') {
      parts.push(`## ${label}\n\n\`\`\`json\n${JSON.stringify(value, null, 2)}\n\`\`\``)
    } else {
      parts.push(`**${label}**：${String(value)}`)
    }
  }

  return parts.join('\n\n') || JSON.stringify(result, null, 2)
}

/**
 * 将数组格式化为编号列表
 */
function formatArray(arr: unknown[]): string {
  return arr
    .map((item, i) => {
      if (typeof item === 'string') {
        return `${i + 1}. ${item}`
      }
      if (typeof item === 'object' && item !== null) {
        // 对象项：提取 name/title 作为标签
        const o = item as Record<string, unknown>
        const tag = o.name ?? o.title ?? `项 ${i + 1}`
        const fields = Object.entries(o)
          .filter(([k]) => k !== 'name' && k !== 'title')
          .map(([k, v]) => `  - **${FIELD_LABELS[k] ?? k}**：${typeof v === 'string' ? v.substring(0, 200) : String(v)}`)
          .join('\n')
        return `**${i + 1}. ${tag}**\n${fields || `  ${JSON.stringify(item)}`}`
      }
      return `${i + 1}. ${String(item)}`
    })
    .join('\n\n')
}
