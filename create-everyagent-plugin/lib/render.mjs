/**
 * lib/render.mjs —— 模板层叠加 + 占位符渲染（文件内容与文件/目录名同时渲染）。
 *
 * ==================================================================================
 * 模板目录契约 v1（步骤 4~6 的模板作者只看这一份；README「模板目录契约」节与此逐字一致）
 * ==================================================================================
 *
 * 一、模板根解析顺序
 *   1) 环境变量 EA_PLUGIN_TEMPLATES（绝对路径或相对 cwd 的路径）—— 自测与本机调试用
 *   2) <create-everyagent-plugin 包目录>/templates
 *
 * 二、层目录与叠加顺序（后面的层可以覆盖前面的同相对路径文件）
 *   L1  common/                 所有 kind × mode 共有（README.md.tpl、gitignore.tpl）
 *   L2  java/                   kind ∈ {java, full} 时叠加
 *   L2  web/                    kind ∈ {web,  full} 时叠加
 *   L3  overlay/<kind>/         kind 整合层：overlay/java | overlay/web | overlay/full
 *   L4  overlay/<mode>/         mode 通用层：overlay/builtin | overlay/standalone
 *   L5  overlay/<kind>-<mode>/  最特异层：overlay/web-standalone、overlay/full-builtin …
 *   不存在的层自动跳过（不必创建全部目录）；模板根不存在或一层都没命中 → 报错。
 *
 * 三、冲突语义
 *   - L1+L2 是「基础层」：同一相对路径被两个基础层命中 → 直接报错，绝不静默覆盖。
 *     ⇒ kind=full 时 java/ 与 web/ 不得互相撞名，也不得与 common/ 撞名。
 *   - L3~L5 是「覆盖层」：允许覆盖基础层与更弱的覆盖层；被覆盖项在 --dry-run 树里标注
 *     「(覆盖 <来源模板>)」。
 *     ⇒ full 形态那份「同时含 main + webMain 的 plugin.json」应放 overlay/full/plugin.json.tpl，
 *       java/ 与 web/ 各放自己单 kind 用的 plugin.json.tpl，靠覆盖层解决，不算冲突。
 *   - standalone 专属文件（tsconfig.json / package.json / vendor/*.d.ts / scripts/build.mjs）
 *     应放 overlay/standalone/ 或 overlay/<kind>-standalone/，builtin 形态自然不生成。
 *   - 模板没有分支语法：不要在 .tpl 里写 if。kind/mode 的内容差异一律靠「把整文件放进对应
 *     overlay 层」表达；{{kind}}/{{mode}} 只是可替换的文本变量。
 *
 * 四、文件与目录命名
 *   - 路径里两种占位符形态都支持：{{ident}} 与 __ident__；内容里只支持 {{ident}}。
 *   - 后缀 .tpl 渲染后去掉；后缀 .raw 表示「内容按字节原样复制、不做替换」（仍渲染路径、
 *     仍去掉后缀；.raw.tpl 与 .tpl.raw 都接受）。含大量 JSX 双花括号的 tsx 可用 .raw 兜底。
 *   - 目录名里的 {{packagePath}} 会展开成 dev/everyagent/plugin/fooBar，天然生成多层目录。
 *     例：java/src/main/java/{{packagePath}}/__className__Plugin.java.tpl
 *       → src/main/java/dev/everyagent/plugin/fooBar/FooBarPlugin.java
 *   - 路径里禁止使用 {{package}}（带点会生成单层怪目录），要用 {{packagePath}}。
 *   - npm 打包会吃掉模板里的 .gitignore，故模板文件名请写 gitignore.tpl（不带前导点）：
 *     CLI 落盘时会把 gitignore / npmignore / npmrc / editorconfig / gitkeep 自动补回前导点。
 *   - 需要占位空目录时放一个空的 gitkeep.tpl（渲染成 .gitkeep）。
 *   - 渲染后路径不得含 ..、绝对路径、Windows 非法字符与控制字符，段尾不得是点或空格。
 *
 * 五、内容占位符（严格识别 {{ident}}，ident = [A-Za-z][A-Za-z0-9_]*）
 *   {{pluginId}} {{pluginName}} {{description}} {{author}} {{version}} {{kind}} {{mode}}
 *   {{package}} {{packagePath}} {{className}} {{camelName}} {{entryClass}} {{mainClass}}
 *   - 未识别的 {{标识符}} → 报错并列出模板相对路径、行号、列号与所在行上下文
 *     （防止模板漏字段悄悄发布）。
 *   - {{color:'red'}} 这类非标识符形态不是占位符，按字面保留（JSX 双花括号安全）。
 *   - 需要字面花括号时写转义：反斜杠 + 双花括号（输出时去掉反斜杠）。
 *
 * 六、文件作用一句话（--dry-run 树右侧的说明）
 *   - CLI 内置一份按文件名/路径前缀匹配的作用表（lib/tree.mjs）；模板若要覆盖它，
 *     在文件前 10 行内、用注释写一行 ea: 一句话作用（必须是注释前缀 // # ; -- /* <!-- ，裸写会污染产物），例：
 *         // ea: 插件入口类，activate() 里注册扩展点
 *
 * 七、写盘约定
 *   - 内容一律 UTF-8 无 BOM（读模板时剥 BOM）、换行一律 LF（读时 CRLF→LF，写时按 LF 落盘）。
 * ==================================================================================
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { TemplateError } from './errors.mjs'
import { VAR_KEYS } from './naming.mjs'

/** 本 CLI 包根目录（.../create-everyagent-plugin）。 */
export const PACKAGE_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/** 覆盖模板根的环境变量名。 */
export const TEMPLATE_ENV_VAR = 'EA_PLUGIN_TEMPLATES'

/** 扫描时忽略的噪声（层目录里出现也不参与渲染）。 */
const SKIP_NAMES = new Set(['.git', 'node_modules', '.DS_Store', 'Thumbs.db', '.idea'])

/** 落盘时补前导点的惯用名（npm 发布会吃掉模板里的 .gitignore，所以模板里不能带点）。 */
const DOTTED_RENAMES = new Map([
  ['gitignore', '.gitignore'],
  ['npmignore', '.npmignore'],
  ['npmrc', '.npmrc'],
  ['editorconfig', '.editorconfig'],
  ['gitkeep', '.gitkeep'],
])

/**
 * 解析模板根目录。
 * @param {object} [opts]
 * @param {Record<string,string|undefined>} [opts.env]
 * @param {string} [opts.cwd]
 * @returns {{root: string, fromEnv: boolean}}
 */
export function resolveTemplateRoot({ env = process.env, cwd = process.cwd() } = {}) {
  const fromEnv = env[TEMPLATE_ENV_VAR]
  if (fromEnv && fromEnv.trim()) {
    return { root: path.resolve(cwd, fromEnv.trim()), fromEnv: true }
  }
  return { root: path.join(PACKAGE_DIR, 'templates'), fromEnv: false }
}

/**
 * 按 kind/mode 给出有序层计划。
 * @param {object} input
 * @param {string} input.root 模板根
 * @param {string} input.kind java|web|full
 * @param {string} input.mode builtin|standalone
 * @returns {Array<{name:string, dir:string, level:'base'|'override', order:number}>}
 */
export function layerPlan({ root, kind, mode }) {
  const layers = [{ name: 'common', dir: path.join(root, 'common'), level: 'base', order: 1 }]
  if (kind === 'java' || kind === 'full') {
    layers.push({ name: 'java', dir: path.join(root, 'java'), level: 'base', order: 2 })
  }
  if (kind === 'web' || kind === 'full') {
    layers.push({ name: 'web', dir: path.join(root, 'web'), level: 'base', order: 2 })
  }
  const overrides = [`overlay/${kind}`, `overlay/${mode}`, `overlay/${kind}-${mode}`]
  overrides.forEach((rel, i) => {
    layers.push({
      name: rel.split('/').pop(),
      dir: path.join(root, ...rel.split('/')),
      level: 'override',
      order: 3 + i,
    })
  })
  return layers
}

function isDirectory(abs) {
  try {
    return fs.statSync(abs).isDirectory()
  } catch {
    return false
  }
}

/** 递归列出层目录下全部文件（相对该层的 posix 风格路径），目录优先、名字典序。 */
function walkFiles(dir, prefix = '', out = []) {
  const entries = fs
    .readdirSync(dir, { withFileTypes: true })
    .sort((a, b) => {
      if (a.isDirectory() !== b.isDirectory()) return a.isDirectory() ? -1 : 1
      return a.name.localeCompare(b.name)
    })
  for (const entry of entries) {
    if (SKIP_NAMES.has(entry.name)) continue
    const rel = prefix ? `${prefix}/${entry.name}` : entry.name
    const abs = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      walkFiles(abs, rel, out)
      continue
    }
    if (entry.isFile()) out.push({ rel, abs })
  }
  return out
}

/**
 * 收集本次渲染用到的模板文件清单（层叠加 + 基础层冲突判定，尚未渲染）。
 * @param {object} input
 * @param {string} input.root
 * @param {string} input.kind
 * @param {string} input.mode
 * @returns {{templates: Array<object>, layersUsed: string[], root: string}}
 */
export function collectTemplates({ root, kind, mode }) {
  if (!isDirectory(root)) {
    throw new TemplateError(`模板根目录不存在：${root}`, {
      hints: [
        `正式模板由计划步骤 4~6 落地；本机调试可用 ${TEMPLATE_ENV_VAR}=<模板根>`,
        `默认模板根 = <包目录>/templates（当前解析值：${root}）`,
      ],
    })
  }
  const plan = layerPlan({ root, kind, mode })
  const byRel = new Map()
  const layersUsed = []
  for (const layer of plan) {
    if (!isDirectory(layer.dir)) continue
    layersUsed.push(layer.name)
    for (const file of walkFiles(layer.dir)) {
      const prev = byRel.get(file.rel)
      if (prev && prev.level === 'base' && layer.level === 'base') {
        throw new TemplateError('模板冲突：同一相对路径被两个基础层命中', {
          hints: [
            `模板路径：${file.rel}`,
            `来源 A：${prev.layer}/${prev.tplRel}`,
            `来源 B：${layer.name}/${file.rel}`,
            'common/ 与 java/ 与 web/ 都是基础层，kind=full 时不得互相撞名；',
            '要合并或替换，请把文件放进 overlay/<kind> | overlay/<mode> | overlay/<kind>-<mode> 覆盖层。',
          ],
        })
      }
      byRel.set(file.rel, {
        tplRel: file.rel,
        tplAbs: file.abs,
        layer: layer.name,
        level: layer.level,
        overriddenSource: prev ? `${prev.layer}/${prev.tplRel}` : null,
      })
    }
  }
  if (byRel.size === 0) {
    throw new TemplateError(`模板根下没有任何可用文件：${root}`, {
      hints: [
        `kind=${kind} mode=${mode} 命中的层：${layersUsed.length ? layersUsed.join(', ') : '（无）'}`,
        '至少需要一个层目录：common/、java/、web/、overlay/<kind>/、overlay/<mode>/、overlay/<kind>-<mode>/',
      ],
    })
  }
  return { templates: [...byRel.values()], layersUsed, root }
}

const BOM = '\uFEFF'

/** 读取模板文件：Buffer + 解码后的 LF 文本（已剥 BOM）。 */
function readTemplate(abs) {
  const buffer = fs.readFileSync(abs)
  let text = buffer.toString('utf8')
  if (text.startsWith(BOM)) text = text.slice(1)
  text = text.replace(/\r\n?/g, '\n')
  return { buffer, text }
}

/**
 * 取模板文件作用说明：优先文件前 10 行里的 `ea:` 标记行。
 * @param {string} text
 * @returns {string|null}
 */
export function extractPurpose(text) {
  const lines = text.split('\n').slice(0, 10)
  for (const raw of lines) {
    const m = /^\s*(?:\/\/|#|;|--|<!--|\/\*+)\s*ea:\s*(.+?)\s*(?:\*\/|-->)?$/.exec(raw)
    if (m) return m[1].trim()
  }
  return null
}

/** 计算偏移对应的 1-based 行号与列号。 */
function lineCol(text, offset) {
  let line = 1
  let lastBreak = -1
  const limit = Math.min(offset, text.length)
  for (let i = 0; i < limit; i++) {
    if (text.charCodeAt(i) === 10) {
      line++
      lastBreak = i
    }
  }
  return { line, column: offset - lastBreak }
}

function snippet(text, offset) {
  const start = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1
  let end = text.indexOf('\n', start)
  if (end === -1) end = text.length
  return text.slice(start, Math.min(end, start + 120)).trim()
}

function throwUnknownPlaceholder(source, text, offset, key) {
  const { line, column } = lineCol(text, offset)
  throw new TemplateError(`模板 ${source} 第 ${line} 行第 ${column} 列有未识别的占位符 {{${key}}}。`, {
    hints: [
      `所在行：${snippet(text, offset)}`,
      `可用占位符：${VAR_KEYS.map((k) => `{{${k}}}`).join(' ')}`,
      `需要字面花括号请转义：\\{{${key}\\}；文件名/目录名里还可用 __${key}__ 形态。`,
    ],
  })
}

const PLACEHOLDER_RE = /^\{\{([A-Za-z][A-Za-z0-9_]*)\}\}/
const UNDERSCORE_RE = /^__([A-Za-z][A-Za-z0-9_]*)__/

/**
 * 渲染一个字符串（文件内容或文件/目录路径）。
 * @param {string} text
 * @param {Record<string,string>} vars
 * @param {string} source 报错定位用的模板相对路径
 * @param {object} [opts]
 * @param {boolean} [opts.path] 路径模式：额外支持 __ident__，并禁用 package 占位符
 * @returns {string}
 */
export function renderString(text, vars, source, { path: isPath = false } = {}) {
  const out = []
  let i = 0
  const take = (key, offset) => {
    if (!Object.prototype.hasOwnProperty.call(vars, key)) {
      throwUnknownPlaceholder(source, text, offset, key)
    }
    return vars[key]
  }
  while (i < text.length) {
    const ch = text[i]
    if (ch === '\\' && (text[i + 1] === '{' || text[i + 1] === '}')) {
      out.push(text[i + 1])
      i += 2
      continue
    }
    if (ch === '{' && text[i + 1] === '{') {
      const m = PLACEHOLDER_RE.exec(text.slice(i))
      if (m) {
        if (isPath && m[1] === 'package') {
          throw new TemplateError(`模板 ${source}：文件/目录名里不能用 {{package}}（带点会生成单层怪目录）。`, {
            hints: ['目录名请用 {{packagePath}}，它会展开成 dev/everyagent/plugin/fooBar 多层目录。'],
          })
        }
        out.push(take(m[1], i))
        i += m[0].length
        continue
      }
      out.push('{')
      i += 1
      continue
    }
    if (isPath && ch === '_' && text[i + 1] === '_') {
      const m = UNDERSCORE_RE.exec(text.slice(i))
      if (m) {
        if (m[1] === 'package') {
          throw new TemplateError(`模板 ${source}：文件/目录名里不能用 __package__，请改成 __packagePath__。`)
        }
        out.push(take(m[1], i))
        i += m[0].length
        continue
      }
    }
    out.push(ch)
    i += 1
  }
  return out.join('')
}

/** Windows 语义最严，按它统一禁止的段内字符。 */
const ILLEGAL_SEG_CHARS = new Set(['<', '>', ':', '"', '|', '?', '*'].map((c) => c.codePointAt(0)))

/**
 * 渲染模板相对路径 → 产物相对路径（含 {{packagePath}} 的多层展开、后缀剥离、补前导点）。
 * @param {string} tplRel 模板层内相对路径
 * @param {Record<string,string>} vars
 * @param {string} source 报错定位用
 * @returns {string} posix 风格相对路径
 */
export function renderPath(tplRel, vars, source) {
  // 1) 剥后缀（先 .tpl 再 .raw，两种书写顺序都接受）
  let base = tplRel
  if (base.endsWith('.tpl')) base = base.slice(0, -'.tpl'.length)
  if (base.endsWith('.raw')) base = base.slice(0, -'.raw'.length)
  if (base.endsWith('.tpl')) base = base.slice(0, -'.tpl'.length)

  // 2) 渲染（{{ident}} 与 __ident__）
  const rendered = renderString(base.split('\\').join('/'), vars, source, { path: true })

  // 3) 规范化路径段（含 {{packagePath}} 展开出来的多层目录）
  const segments = []
  for (const raw of rendered.split('/')) {
    for (const seg of raw.split('/')) {
      if (seg === '' || seg === '.') continue
      if (seg === '..') {
        throw new TemplateError(`模板 ${source}：渲染后的产物路径含 ..，越出插件根，已拒绝。`, {
          hints: [`渲染结果：${rendered}`],
        })
      }
      if (path.isAbsolute(seg) || /^[a-zA-Z]:/.test(seg)) {
        throw new TemplateError(`模板 ${source}：渲染后的路径段是绝对路径，已拒绝。`, {
          hints: [`渲染结果：${rendered}`],
        })
      }
      for (let k = 0; k < seg.length; k++) {
        const code = seg.codePointAt(k)
        if (code < 0x20 || code === 0x7f || ILLEGAL_SEG_CHARS.has(code)) {
          throw new TemplateError(
            `模板 ${source}：渲染后的文件/目录名含非法字符 ${JSON.stringify(seg[k])}（来自变量值）。`,
            { hints: [`渲染结果：${rendered}`, 'id 只允许小写字母/数字/连字符；name/desc 若被放进文件名请避免符号。'] },
          )
        }
      }
      if (/[. ]$/.test(seg)) {
        throw new TemplateError(`模板 ${source}：渲染后的路径段 "${seg}" 以点或空格结尾，不适合作文件名。`)
      }
      segments.push(seg)
    }
  }
  if (segments.length === 0) {
    throw new TemplateError(`模板 ${source}：渲染后的产物路径为空。`, { hints: [`模板路径：${tplRel}`] })
  }
  const last = segments[segments.length - 1]
  if (DOTTED_RENAMES.has(last)) segments[segments.length - 1] = DOTTED_RENAMES.get(last)
  return segments.join('/')
}

/**
 * 完整渲染：模板清单 → 产物文件清单。
 * @param {object} input
 * @param {Array<object>} input.templates collectTemplates() 的产物
 * @param {Record<string,string>} vars
 * @param {string} input.root 模板根（仅用于报错展示）
 * @returns {Array<{relPath:string, source:string, content:string, verbatim:boolean, purpose:string|null, overriddenSource:string|null}>}
 */
export function renderFiles({ templates, vars, root }) {
  const relToTpl = new Map()
  const files = []
  for (const tpl of templates.slice().sort((a, b) => a.tplRel.localeCompare(b.tplRel))) {
    const sourceLabel = `${tpl.layer}/${tpl.tplRel}`
    const verbatim = /(^|\.)raw(\.tpl)?$/.test(tpl.tplRel)
    const { buffer, text } = readTemplate(tpl.tplAbs)
    const relPath = renderPath(tpl.tplRel, vars, sourceLabel)
    const content = verbatim ? text : renderString(text, vars, sourceLabel)
    const purpose = verbatim ? null : extractPurpose(text)
    const prev = relToTpl.get(relPath)
    if (prev) {
      throw new TemplateError('模板渲染后产物路径冲突（两个模板落到同一个文件）', {
        hints: [
          `产物路径：${relPath}`,
          `来源 A：${prev}`,
          `来源 B：${sourceLabel}`,
          `模板根：${root}`,
          '请改掉其中一个的模板文件名/所在目录（基础层之间尤其注意 java/ 与 web/）。',
        ],
      })
    }
    relToTpl.set(relPath, sourceLabel)
    files.push({
      relPath,
      source: sourceLabel,
      content,
      bytes: verbatim ? buffer : null,
      verbatim,
      purpose,
      overriddenSource: tpl.overriddenSource,
    })
  }
  return files
}

/**
 * 一步式：解析模板根 → 收集层 → 渲染。
 * @param {object} input
 * @param {string} input.kind
 * @param {string} input.mode
 * @param {Record<string,string>} input.vars
 * @param {Record<string,string|undefined>} [input.env]
 * @param {string} [input.cwd]
 * @returns {{root:string, fromEnv:boolean, layersUsed:string[], files:ReturnType<typeof renderFiles>}}
 */
export function planRender({ kind, mode, vars, env = process.env, cwd = process.cwd() }) {
  const { root, fromEnv } = resolveTemplateRoot({ env, cwd })
  const collected = collectTemplates({ root, kind, mode })
  const files = renderFiles({ templates: collected.templates, vars, root })
  return { root, fromEnv, layersUsed: collected.layersUsed, files }
}

/**
 * 把渲染结果写到目标目录（LF / UTF-8 无 BOM；越界路径直接拒绝）。
 * @param {string} target 输出目录（绝对路径）
 * @param {ReturnType<typeof renderFiles>} files
 * @returns {string[]} 已写盘文件的绝对路径
 */
export function writeFiles(target, files) {
  const written = []
  const rootAbs = path.resolve(target)
  for (const file of files) {
    const abs = path.resolve(rootAbs, ...file.relPath.split('/'))
    if (abs !== rootAbs && !abs.startsWith(rootAbs + path.sep)) {
      throw new TemplateError(`产物路径越出目标目录，已拒绝写盘：${file.relPath}`)
    }
    fs.mkdirSync(path.dirname(abs), { recursive: true })
    const payload = file.verbatim && file.bytes && !hasUtf8Bom(file.bytes)
      ? file.bytes
      : Buffer.from(file.content.replace(/\r\n?/g, '\n'), 'utf8')
    fs.writeFileSync(abs, payload)
    written.push(abs)
  }
  return written
}

/** verbatim 文件仍要剥 BOM 才允许直接写字节。 */
function hasUtf8Bom(buffer) {
  return buffer.length > 0 && buffer[0] === 0xef && buffer[1] === 0xbb && buffer[2] === 0xbf
}
