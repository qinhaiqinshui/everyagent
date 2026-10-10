/**
 * lib/prompts.mjs —— create-vite 式逐项交互问答（零依赖，走 node:readline/promises）。
 *
 * 问答顺序：id → kind → mode → name → desc → author
 * 规则：
 *   - 已有值（flag 给过）的问题不再问；
 *   - 回车取缺省值；无缺省值时重新提问（id 无缺省）；
 *   - kind / mode 只接受给定枚举（也接受 1/2/3 速选）；
 *   - Ctrl+C（readline 'SIGINT'）→ CanceledError，退出码 130。
 */

import * as readline from 'node:readline/promises'
import { CanceledError, ValidationError } from './errors.mjs'
import { DEFAULT_AUTHOR, DEFAULT_VERSION, validatePluginId } from './naming.mjs'
import { KINDS, MODES, assertKind, assertMode, sanitizeText } from './validate.mjs'

/** kind 选项说明（交互提示里必须讲清三者差异）。 */
export const KIND_HELP = {
  java: '只有后端（Java 工具 / Advisor / RPC，产出 jar，无前端）',
  web: '只有前端 UI（web/index.ts 导出 PluginModule，无 worker 端代码）',
  full: '前后端都要（一份 plugin.json 同时含 main 与 webMain）',
}

/** mode 选项说明。 */
export const MODE_HELP = {
  builtin: '生成到仓库内 every-agent-plugins/<id>/，复用宿主依赖与 typecheck',
  standalone: '仓库外独立工程，自带 plugin-api 类型副本 + tsconfig + 构建脚本',
}

/** 非交互（--yes / 无 TTY）时使用的缺省值。 */
export const NON_INTERACTIVE_DEFAULTS = {
  kind: 'full',
  mode: 'builtin',
  author: DEFAULT_AUTHOR,
  version: DEFAULT_VERSION,
}

/**
 * stdin 是否可用于交互问答。
 * @param {object} [io]
 * @returns {boolean}
 */
export function isInteractive(io = process) {
  return Boolean(io.stdin && io.stdin.isTTY)
}

function choicePrompt(values, help) {
  const lines = values.map((v, i) => `  ${i + 1}) ${v} —— ${help[v]}`)
  return lines.join('\n')
}

function normalizeAnswer(raw) {
  return String(raw == null ? '' : raw).trim()
}

/**
 * 逐项补齐 spec 中缺失的字段。
 * @param {object} spec 已含 id/kind/mode/name/desc/author 中部分字段（缺的为 null）
 * @param {object} [opts]
 * @param {object} [opts.io] 读写流（默认 process）
 * @returns {Promise<object>} 补齐后的 spec
 */
export async function promptSpec(spec, { io = process } = {}) {
  const rl = readline.createInterface({ input: io.stdin, output: io.stdout })
  const state = { canceled: false }
  let rejectCancel
  const cancelPromise = new Promise((_, reject) => {
    rejectCancel = reject
  })
  rl.on('SIGINT', () => {
    state.canceled = true
    rl.close()
    rejectCancel(new CanceledError('已取消（Ctrl+C），未写入任何文件'))
  })

  /** @param {string} text */
  const line = (text) => io.stdout.write(`${text}\n`)

  async function ask(question) {
    try {
      const answer = await Promise.race([rl.question(question), cancelPromise])
      if (state.canceled) throw new CanceledError('已取消（Ctrl+C），未写入任何文件')
      return normalizeAnswer(answer)
    } catch (err) {
      if (err instanceof CanceledError) throw err
      throw new ValidationError(`读取输入失败：${err.message}`)
    }
  }

  try {
    const next = { ...spec }

    // ---- id ----
    if (!next.id) {
      const fallback = next.dirHint || null
      const shown = fallback ? `（回车取 ${JSON.stringify(fallback)}）` : ''
      for (let guard = 0; guard < 10; guard++) {
        const answer = await ask(
          `插件 id ${shown}\n  规则：小写字母/数字/连字符，2~39 字符，首尾不能是连字符\n  例如 empty-response-retry > `,
        )
        const value = answer || fallback
        if (!value) {
          line('  插件 id 不能为空，请重新输入。')
          continue
        }
        const check = validatePluginId(value)
        if (!check.ok) {
          line(`  插件 id "${value}" 不合规：${check.reason}`)
          continue
        }
        next.id = value
        break
      }
      if (!next.id) throw new ValidationError('未能获取合法的插件 id')
    }

    // ---- kind ----
    if (!next.kind) {
      line(`插件形态 kind：\n${choicePrompt(KINDS, KIND_HELP)}`)
      for (let guard = 0; guard < 10; guard++) {
        const answer = await ask('选择插件形态 [java/web/full]（回车取 full）> ')
        const value = answer || 'full'
        const shortcut = /^[123]$/.test(value) ? KINDS[Number(value) - 1] : value.toLowerCase()
        if (!KINDS.includes(shortcut)) {
          line(`  未知形态 "${value}"，只能是 java / web / full。`)
          continue
        }
        next.kind = shortcut
        break
      }
    }

    // ---- mode ----
    if (!next.mode) {
      line(`工程形态 mode：\n${choicePrompt(MODES, MODE_HELP)}`)
      for (let guard = 0; guard < 10; guard++) {
        const answer = await ask('选择工程形态 [builtin/standalone]（回车取 builtin）> ')
        const value = answer || 'builtin'
        const shortcut = /^[12]$/.test(value) ? MODES[Number(value) - 1] : value.toLowerCase()
        if (!MODES.includes(shortcut)) {
          line(`  未知 mode "${value}"，只能是 builtin / standalone。`)
          continue
        }
        next.mode = shortcut
        break
      }
    }

    // ---- name ----
    if (!next.name) {
      const answer = await ask(`插件显示名 name（回车取 ${JSON.stringify(next.id)}）> `)
      next.name = answer || next.id
    }

    // ---- desc ----
    if (!next.desc) {
      const fallback = `${next.name} 插件`
      const answer = await ask(`插件描述 desc（回车取 ${JSON.stringify(fallback)}）> `)
      next.desc = answer || fallback
    }

    // ---- author ----
    if (!next.author) {
      const answer = await ask(`作者 author（回车取 ${JSON.stringify(DEFAULT_AUTHOR)}）> `)
      next.author = answer || DEFAULT_AUTHOR
    }

    return next
  } finally {
    rl.close()
  }
}

/**
 * 非交互路径：全部取缺省值，必填项缺失直接报错。
 * @param {object} spec
 * @returns {object}
 */
export function fillDefaults(spec) {
  const next = { ...spec }
  // 位置参数 [dir] 的目录名可以当作 id 的缺省值（node create-everyagent-plugin my-tool --yes）
  if (!next.id && next.dirHint) next.id = next.dirHint
  if (!next.id) {
    throw new ValidationError('非交互模式（--yes 或 stdin 非 TTY）必须提供插件 id', {
      hints: [
        '位置参数即目录名会被当作 id 缺省值：node create-everyagent-plugin my-tool --kind java --yes',
        '或显式给出：--id my-tool',
      ],
    })
  }
  next.kind = next.kind || NON_INTERACTIVE_DEFAULTS.kind
  next.mode = next.mode || NON_INTERACTIVE_DEFAULTS.mode
  next.name = next.name || next.id
  next.desc = next.desc || `${next.name} 插件`
  next.author = next.author || NON_INTERACTIVE_DEFAULTS.author
  next.version = next.version || NON_INTERACTIVE_DEFAULTS.version
  return next
}

/** 供 index.mjs 复用的枚举校验（避免各处重复写 if）。 */
export { assertKind, assertMode, sanitizeText }
