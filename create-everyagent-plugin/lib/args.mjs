/**
 * lib/args.mjs —— 极简 flag 解析（零依赖，不引 commander/minimist）。
 *
 * 支持形态：--flag value / --flag=value / --bool / -h / -v / -y / -f / --
 * 位置参数：第一个若是保留子命令（pack）则为子命令，否则是输出目录 [dir]。
 * 未知 flag、缺值、重复出现一律 UsageError，绝不静默忽略。
 */

import { UsageError } from './errors.mjs'

/** 需要取值的 flag。`output` 为 `pack` 子命令（步骤 7）预留。 */
export const VALUE_FLAGS = ['id', 'name', 'desc', 'author', 'kind', 'mode', 'output']

/** 布尔 flag。 */
export const BOOL_FLAGS = ['yes', 'force', 'dry-run', 'help', 'version']

/** 单字母别名（只允许 -x 形式）。 */
export const ALIASES = { h: 'help', v: 'version', y: 'yes', f: 'force', o: 'output' }

/** 保留子命令（步骤 7 实现 .eap 打包）。 */
export const SUBCOMMANDS = ['pack']

/**
 * @typedef {object} ParsedArgs
 * @property {string[]} positional 位置参数（不含子命令）
 * @property {Record<string,string>} values 取值 flag
 * @property {Record<string,boolean>} bools 布尔 flag
 * @property {string|null} subcommand 命中的保留子命令
 * @property {string|null} dirArg 输出目录位置参数
 * @property {string[]} raw 原始 argv
 */

/**
 * 解析 argv（不含 node 与脚本路径两项）。
 * @param {string[]} argv
 * @returns {ParsedArgs}
 */
export function parseArgs(argv) {
  const values = {}
  const bools = {}
  const positional = []
  const consumed = new Set()
  let onlyPositional = false

  function putFlag(name, inlineValue, index) {
    if (BOOL_FLAGS.includes(name)) {
      if (inlineValue !== null) {
        throw new UsageError(`--${name} 是开关，不接受取值（写成 --${name} 即可）`)
      }
      if (bools[name]) throw new UsageError(`--${name} 重复出现`)
      bools[name] = true
      return
    }
    if (!VALUE_FLAGS.includes(name)) {
      throw new UsageError(`未知 flag：--${name}`, { hints: ['用 --help 查看全部可用 flag'] })
    }
    let value = inlineValue
    if (value === null) {
      const next = argv[index + 1]
      if (next === undefined || (next.length > 1 && next.startsWith('-'))) {
        throw new UsageError(`flag --${name} 缺少取值`)
      }
      value = next
      consumed.add(index + 1)
    }
    if (value === '') throw new UsageError(`flag --${name} 的取值为空`)
    if (values[name] !== undefined) {
      throw new UsageError(`--${name} 重复出现（已有值 "${values[name]}"）`)
    }
    values[name] = value
  }

  for (let i = 0; i < argv.length; i++) {
    if (consumed.has(i)) continue
    const token = argv[i]
    if (onlyPositional) {
      positional.push(token)
      continue
    }
    if (token === '--') {
      onlyPositional = true
      continue
    }
    if (token.startsWith('--')) {
      const body = token.slice(2)
      const eq = body.indexOf('=')
      const name = eq >= 0 ? body.slice(0, eq) : body
      const inline = eq >= 0 ? body.slice(eq + 1) : null
      if (!name) throw new UsageError(`非法 flag：${token}`)
      if (ALIASES[name]) throw new UsageError(`未知 flag：--${name}（短写法是 -${name}）`)
      putFlag(name, inline, i)
      continue
    }
    if (token.length > 1 && token.startsWith('-')) {
      const name = token.slice(1)
      if (name.length !== 1 || !ALIASES[name]) {
        throw new UsageError(`未知 flag：${token}`, { hints: ['用 --help 查看全部可用 flag'] })
      }
      putFlag(ALIASES[name], null, i)
      continue
    }
    positional.push(token)
  }

  const subcommand = positional.length > 0 && SUBCOMMANDS.includes(positional[0]) ? positional[0] : null
  const rest = subcommand ? positional.slice(1) : positional
  if (rest.length > 1) {
    throw new UsageError(`只能接受 1 个位置参数（输出目录），收到 ${rest.length} 个：${rest.join(' ')}`)
  }
  return {
    positional: rest,
    values,
    bools,
    subcommand,
    dirArg: rest.length === 1 ? rest[0] : null,
    raw: argv.slice(),
  }
}
