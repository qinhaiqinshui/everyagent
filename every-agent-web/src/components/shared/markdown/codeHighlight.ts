/**
 * Prism 语法高亮共享模块（MarkdownPreview / MarkdownDisplay / UniversalFileEditor 共用）。
 *
 * 职责:
 * 1. 注册一批常用语言的 Prism grammar(markup / css / javascript / clike 随 prismjs 核心自带)。
 * 2. 把 fence 信息串(```js)规范化为 Prism 语言名,未注册语言降级为纯文本渲染。
 * 3. 按文件扩展名解析 Prism 语言名(供 UniversalFileEditor 使用)。
 *
 * 体积考虑:只按需 import 常用语言组件,其余语言可后续懒加载。
 * 移动端考虑:Prism 核心 ~2KB,按需语言 1–5KB/个,远比 Shiki(wasm)轻。
 */
import Prism from 'prismjs'
import 'prismjs/components/prism-typescript'
import 'prismjs/components/prism-json'
import 'prismjs/components/prism-bash'
import 'prismjs/components/prism-python'
import 'prismjs/components/prism-java'
import 'prismjs/components/prism-go'
import 'prismjs/components/prism-sql'
import 'prismjs/components/prism-jsx'
import 'prismjs/components/prism-tsx'
import 'prismjs/components/prism-yaml'

/** fence 信息串 → Prism 语言名的常见别名映射。 */
const LANGUAGE_ALIASES: Record<string, string> = {
  js: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  node: 'javascript',
  ts: 'typescript',
  tsx: 'tsx',
  jsx: 'jsx',
  html: 'markup',
  vue: 'markup',
  xml: 'markup',
  svg: 'markup',
  sh: 'bash',
  shell: 'bash',
  zsh: 'bash',
  console: 'bash',
  terminal: 'bash',
  py: 'python',
  golang: 'go',
  yml: 'yaml',
}

/**
 * 文件扩展名(小写,不含点)→ Prism 语言名映射。
 * 仅覆盖已注册 grammar 的语言,与上方 import 的 Prism 组件一一对应。
 */
const FILE_EXTENSION_MAP: Record<string, string> = {
  js: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  jsx: 'jsx',
  ts: 'typescript',
  tsx: 'tsx',
  java: 'java',
  go: 'go',
  py: 'python',
  sh: 'bash',
  bash: 'bash',
  zsh: 'bash',
  json: 'json',
  sql: 'sql',
  yaml: 'yaml',
  yml: 'yaml',
  html: 'markup',
  htm: 'markup',
  xml: 'markup',
  svg: 'markup',
  vue: 'markup',
  css: 'css',
}

/**
 * 把 fence 信息串(如 ```ts / ```shell / ```App.java)规范化为已注册的 Prism 语言名。
 * 未注册或无法识别时返回 null(渲染方降级为纯文本,不丢内容)。
 */
export function normalizeCodeLanguage(infoString?: string | null): string | null {
  if (!infoString) return null
  // fence 信息串可能携带附加参数(如 ```js title=a.js),只取首个词。
  const raw = infoString.trim().split(/\s+/)[0]?.toLowerCase()
  if (!raw) return null

  // 1) 直接别名 / 语言名匹配(如 ts / shell / java)
  const candidate = LANGUAGE_ALIASES[raw] ?? raw
  if (Prism.languages[candidate]) return candidate

  // 2) 文件名按扩展名匹配(如 App.java → java / index.ts → typescript)
  const dotIndex = raw.lastIndexOf('.')
  if (dotIndex >= 0 && dotIndex < raw.length - 1) {
    const ext = raw.slice(dotIndex + 1)
    const extLang = FILE_EXTENSION_MAP[ext]
    if (extLang && Prism.languages[extLang]) return extLang
  }

  return null
}

/**
 * 按文件扩展名(不含点,大小写不敏感)解析已注册的 Prism 语言名。
 * 供 UniversalFileEditor 等按文件路径解析语言;未注册时返回 null。
 */
export function languageByExtension(ext: string): string | null {
  const lang = FILE_EXTENSION_MAP[ext.toLowerCase()]
  if (lang && Prism.languages[lang]) return lang
  return null
}

/**
 * 高亮代码文本,返回 HTML 字符串(含 Prism token span,HTML 已转义)。
 * 语言未注册时返回 null,调用方回退纯文本。
 */
export function highlightCode(code: string, language: string): string | null {
  const grammar = Prism.languages[language]
  if (!grammar) return null
  try {
    return Prism.highlight(code, grammar, language)
  } catch {
    // 语法解析异常时绝不阻塞渲染,回退纯文本。
    return null
  }
}
