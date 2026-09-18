/**
 * CodeMirror 6 语言映射：按文件扩展名解析对应的 LanguageSupport。
 *
 * 与 codeHighlight.ts 中的 Prism 语言注册一一对应，确保编辑器与
 * Markdown 代码块使用相同的语言覆盖范围。
 */
import { LanguageSupport } from '@codemirror/language'
import { javascript } from '@codemirror/lang-javascript'
import { java } from '@codemirror/lang-java'
import { python } from '@codemirror/lang-python'
import { go } from '@codemirror/lang-go'
import { sql } from '@codemirror/lang-sql'
import { yaml } from '@codemirror/lang-yaml'
import { html } from '@codemirror/lang-html'
import { css } from '@codemirror/lang-css'
import { json } from '@codemirror/lang-json'
import { markdown } from '@codemirror/lang-markdown'

/**
 * 扩展名(小写,不含点)→ LanguageSupport 工厂函数。
 * 惰性构造,只在首次调用时创建。
 */
const EXTENSION_MAP: Record<string, () => LanguageSupport> = {
  // JavaScript / TypeScript
  js: () => javascript(),
  mjs: () => javascript(),
  cjs: () => javascript(),
  jsx: () => javascript({ jsx: true }),
  ts: () => javascript({ typescript: true }),
  tsx: () => javascript({ typescript: true, jsx: true }),
  // Java
  java: () => java(),
  // Go
  go: () => go(),
  // Python
  py: () => python(),
  // Shell — CM6 无 bash 语言包,降级为纯文本(无高亮)
  // sh: () => yaml(),  ← 错误：YAML 高亮不适用于 shell 脚本
  // SQL
  sql: () => sql(),
  // YAML
  yaml: () => yaml(),
  yml: () => yaml(),
  // HTML / XML / Vue / SVG
  html: () => html(),
  htm: () => html(),
  xml: () => html(),
  svg: () => html(),
  vue: () => html(),
  // CSS
  css: () => css(),
  // JSON
  json: () => json(),
  // Markdown
  md: () => markdown(),
}

const cache = new Map<string, LanguageSupport>()

/**
 * 按文件扩展名(不含点,大小写不敏感)获取 LanguageSupport。
 * 未注册时返回 null(纯文本,无高亮)。
 */
export function languageByExtension(ext: string): LanguageSupport | null {
  const key = ext.toLowerCase()
  if (cache.has(key)) return cache.get(key)!
  const factory = EXTENSION_MAP[key]
  if (!factory) return null
  const lang = factory()
  cache.set(key, lang)
  return lang
}

/**
 * 从文件路径中提取扩展名(小写,不含点)。
 */
function getExtension(filePath: string): string {
  const normalized = filePath.replace(/\\/g, '/')
  const fileName = normalized.slice(normalized.lastIndexOf('/') + 1)
  const dotIndex = fileName.lastIndexOf('.')
  if (dotIndex <= 0 || dotIndex >= fileName.length - 1) return ''
  return fileName.slice(dotIndex + 1).toLowerCase()
}

/**
 * 按文件路径获取 LanguageSupport。未注册时返回 null。
 */
export function languageByPath(filePath: string): LanguageSupport | null {
  return languageByExtension(getExtension(filePath))
}
