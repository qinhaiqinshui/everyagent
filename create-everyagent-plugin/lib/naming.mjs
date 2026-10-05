/**
 * lib/naming.mjs —— 插件 id → 包名 / 类名 / 模板变量 的推导（唯一事实源）。
 *
 * 命名规则（与 create-everyagent-plugin/README.md「命名与校验规则」节逐字一致）：
 *   id 必须匹配 ^[a-z0-9][a-z0-9-]{1,38}$，且额外拒绝以连字符开头或结尾
 *   （即首尾必须是字母或数字，总长 2~39，中间允许连字符，允许连续连字符）。
 *
 *   {{pluginId}}    = id
 *   {{pluginName}}  = --name（缺省 = id）
 *   {{description}} = --desc
 *   {{author}}      = --author（缺省 everyagent）
 *   {{version}}     = 固定 0.1.0
 *   {{kind}}        = java | web | full      （仅供 README 等文本使用，模板无分支语法）
 *   {{mode}}        = builtin | standalone   （同上）
 *   {{package}}     = dev.everyagent.plugin.<camelCase(id)>
 *   {{packagePath}} = {{package}} 的点换成 /（供模板目录名使用）
 *   {{className}}   = PascalCase(id)
 *   {{entryClass}}  = {{className}} + "Plugin"      （入口类简名）
 *   {{mainClass}}   = {{package}} + "." + {{entryClass}}（plugin.json 的 main 字段值）
 *   {{camelName}}   = camelCase(id)
 *
 * 中文 / 特殊字符处理：分词时只保留 ASCII 字母与数字，其余（中文、下划线、点、
 * 空格、全角符号）一律视为分隔符剔除；剔除后若没有任何 ASCII 字母数字，或派生的
 * 类名以数字开头（不是合法 Java 标识符），则由 validatePluginId 直接报错，不静默兜底。
 */

import { ValidationError } from './errors.mjs'

/** id 长度下限 / 上限。 */
export const ID_MIN = 2
export const ID_MAX = 39

/** 文档/报错里展示的 id 正则（字面量取自计划文档）。 */
export const ID_PATTERN_TEXT = '^[a-z0-9][a-z0-9-]{1,38}$'

/** 允许的字符集（首尾另做检查）。 */
const ID_CHARCLASS_RE = /^[a-z0-9-]+$/
const ID_START_RE = /^[a-z0-9]/
const ID_END_RE = /[a-z0-9]$/

/** Java 包名前缀（与 every-agent-plugin-api 及既有内置插件一致）。 */
export const PACKAGE_PREFIX = 'dev.everyagent.plugin'

/** 脚手架固定的插件版本（步骤 4~6 的 plugin.json / pom.xml 模板直接取 {{version}}）。 */
export const DEFAULT_VERSION = '0.1.0'

/** author 缺省值。 */
export const DEFAULT_AUTHOR = 'everyagent'

/**
 * 把 id 切成单词：只认 ASCII 字母数字，其余字符一律作分隔符并丢弃。
 * 例：'pdf-viewer' → ['pdf','viewer']；'task-input-queue' → ['task','input','queue']；
 *     'html5' → ['html5']；'中文插件' → []。
 * @param {string} raw
 * @returns {string[]}
 */
export function splitWords(raw) {
  const s = String(raw == null ? '' : raw).toLowerCase()
  return s.split(/[^a-z0-9]+/).filter(Boolean)
}

function capitalize(word) {
  if (!word) return ''
  return word.charAt(0).toUpperCase() + word.slice(1)
}

/** camelCase('empty-response-retry') → 'emptyResponseRetry'。 */
export function camelCase(raw) {
  const words = splitWords(raw)
  if (words.length === 0) return ''
  return words
    .map((w, i) => (i === 0 ? w : capitalize(w)))
    .join('')
}

/** PascalCase('empty-response-retry') → 'EmptyResponseRetry'。 */
export function pascalCase(raw) {
  return splitWords(raw)
    .map(capitalize)
    .join('')
}

/**
 * Java 包名：dev.everyagent.plugin.<camelCase(id)>。
 * @param {string} id
 * @returns {string}
 */
export function javaPackage(id) {
  const camel = camelCase(id)
  if (!camel) {
    throw new ValidationError(`id "${id}" 剔除非法字符后没有任何 ASCII 字母数字，无法推导 Java 包名`)
  }
  return `${PACKAGE_PREFIX}.${camel}`
}

/** 包路径（点 → 斜杠），供模板目录名 {{packagePath}} 展开成多层目录。 */
export function javaPackagePath(id) {
  return javaPackage(id).split('.').join('/')
}

/** 入口类简名：PascalCase(id) + 'Plugin'。 */
export function entryClassName(id) {
  const pascal = pascalCase(id)
  if (!pascal) {
    throw new ValidationError(`id "${id}" 无法推导 Java 类名（没有 ASCII 字母数字）`)
  }
  return `${pascal}Plugin`
}

/** plugin.json 的 main 字段值（全限定入口类名）。 */
export function mainClassName(id) {
  return `${javaPackage(id)}.${entryClassName(id)}`
}

/**
 * id 校验：返回 { ok, reason }，reason 精确指出违反了哪条规则。
 * 同时预校验派生名合法性（包名片段、类名必须是合法 Java 标识符）。
 * @param {unknown} id
 * @returns {{ok: boolean, reason?: string}}
 */
export function validatePluginId(id) {
  if (typeof id !== 'string' || id.length === 0) {
    return { ok: false, reason: 'id 不能为空' }
  }
  if (/[^\x20-\x7e]/.test(id)) {
    const bad = [...new Set([...id].filter((c) => c.charCodeAt(0) < 0x20 || c.charCodeAt(0) > 0x7e))]
    return {
      ok: false,
      reason: `id 含非 ASCII 字符 ${JSON.stringify(bad.join(' '))}（只允许小写字母、数字与连字符）`,
    }
  }
  if (id.includes(' ')) {
    return { ok: false, reason: `id 不能含空格（用连字符分隔单词），当前 "${id}"` }
  }
  if (id.length < ID_MIN) {
    return { ok: false, reason: `id 长度至少 ${ID_MIN} 个字符，当前 "${id}"` }
  }
  if (id.length > ID_MAX) {
    return {
      ok: false,
      reason: `id 长度至多 ${ID_MAX} 个字符，当前 ${id.length} 个字符`,
    }
  }
  if (!ID_CHARCLASS_RE.test(id)) {
    const bad = [...new Set([...id].filter((c) => !/[a-z0-9-]/.test(c)))]
    const upper = bad.filter((c) => /[A-Z]/.test(c))
    const other = bad.filter((c) => !/[A-Z]/.test(c))
    const parts = []
    if (upper.length) parts.push(`含大写字母 ${upper.join('')}`)
    if (other.length) parts.push(`含非法字符 ${other.join(' ')}`)
    return { ok: false, reason: `${parts.join('，')}（规则 ${ID_PATTERN_TEXT}）` }
  }
  if (!ID_START_RE.test(id)) {
    return { ok: false, reason: `id 不能以连字符开头，当前 "${id}"` }
  }
  if (!ID_END_RE.test(id)) {
    return { ok: false, reason: `id 不能以连字符结尾，当前 "${id}"` }
  }

  // 派生名合法性：包名片段与类名必须是合法 Java 标识符
  let camel, pascal
  try {
    camel = camelCase(id)
    pascal = pascalCase(id)
  } catch (err) {
    return { ok: false, reason: err.message }
  }
  if (/^[0-9]/.test(pascal)) {
    return {
      ok: false,
      reason: `id "${id}" 派生的类名 "${pascal}Plugin" 以数字开头，不是合法 Java 类名；请给 id 加字母前缀（如 p-${id}）`,
    }
  }
  if (/^[0-9]/.test(camel)) {
    return {
      ok: false,
      reason: `id "${id}" 派生的包名片段 "${camel}" 以数字开头，不是合法 Java 包名；请给 id 加字母前缀`,
    }
  }
  return { ok: true }
}

/**
 * 从 --name（中文名等自由文本）推导输出目录缺省名用不到的东西，这里只提供
 * 一个「把任意文本压成安全文件名片段」的工具，供 id 缺省推导与提示使用。
 * 例：'PDF 预览' → 'pdf-预览' 不可取；本函数只保留 ASCII 字母数字，
 *     非 ASCII 全剔除，再以单个连字符连接：'PDF 预览' → 'pdf'。
 * @param {string} raw
 * @returns {string}
 */
export function slugify(raw) {
  const s = String(raw == null ? '' : raw)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  return s
}

/**
 * 生成模板渲染用的全量变量表（render.mjs 认识的占位符集合 = 本函数返回的键集合）。
 * @param {object} spec 归一化后的任务参数
 * @param {string} spec.id
 * @param {string} [spec.name]
 * @param {string} [spec.desc]
 * @param {string} [spec.author]
 * @param {string} [spec.version]
 * @param {string} spec.kind
 * @param {string} spec.mode
 * @returns {Record<string,string>}
 */
export function deriveVars(spec) {
  const id = spec.id
  const name = spec.name && spec.name.trim() ? spec.name.trim() : id
  const description = spec.desc && spec.desc.trim() ? spec.desc.trim() : `${name} 插件`
  const author = spec.author && spec.author.trim() ? spec.author.trim() : DEFAULT_AUTHOR
  const version = spec.version && spec.version.trim() ? spec.version.trim() : DEFAULT_VERSION
  const pkg = javaPackage(id)
  const className = pascalCase(id)
  return {
    pluginId: id,
    pluginName: name,
    description,
    author,
    version,
    kind: spec.kind,
    mode: spec.mode,
    package: pkg,
    packagePath: pkg.split('.').join('/'),
    className,
    camelName: camelCase(id),
    entryClass: entryClassName(id),
    mainClass: mainClassName(id),
  }
}

/** 模板可用占位符键名列表（render.mjs 的报错提示与 README 都从这里取）。 */
export const VAR_KEYS = [
  'pluginId',
  'pluginName',
  'description',
  'author',
  'version',
  'kind',
  'mode',
  'package',
  'packagePath',
  'className',
  'camelName',
  'entryClass',
  'mainClass',
]
