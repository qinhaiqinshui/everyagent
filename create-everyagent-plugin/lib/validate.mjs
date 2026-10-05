/**
 * lib/validate.mjs —— 输入字段校验、输出目录定位与冲突判定（不写盘）。
 *
 * 目录定位规则（README「输出目录」节同步）：
 *   1. 显式给了位置参数 [dir] → 以 cwd 为基准 resolve，直接使用；
 *   2. mode=builtin 且未给 [dir] → <仓库根>/every-agent-plugins/<id>/
 *      仓库根 = 从 cwd 向上（再从 CLI 包目录向上）找到的第一个含 every-agent-plugins 子目录的祖先目录；
 *      找不到 → 报错，提示改用 --mode standalone 或显式给目录；
 *   3. mode=standalone 且未给 [dir] → ./<id>/（cwd 下一层）。
 */

import fs from 'node:fs'
import path from 'path'
import { ConflictError, ValidationError } from './errors.mjs'
import { ID_MAX, ID_PATTERN_TEXT, validatePluginId } from './naming.mjs'

/** 内置插件根目录名（同时也是「仓库根」的判据）。 */
export const PLUGINS_DIR_NAME = 'every-agent-plugins'

/** kind / mode 合法取值。 */
export const KINDS = ['java', 'web', 'full']
export const MODES = ['builtin', 'standalone']

/**
 * 校验必填/可选文本字段：拒绝 C0 控制字符与换行（会被写进 plugin.json 与 README）。
 * 直双引号与反斜杠替换为全角对应字符（在 JSON 字符串、Java 注释、Markdown 里都安全）。
 * @param {string} label 报错用的字段名
 * @param {string} value
 * @param {object} [opts]
 * @param {number} [opts.max]
 * @param {boolean} [opts.required]
 * @returns {string} 规整后的值（trim 后内部连续空白压成单个空格；允许中文）
 */
export function sanitizeText(label, value, { max = 200, required = false } = {}) {
  let s = typeof value === 'string' ? value : ''
  // 反斜杠与换行控制符在 JSON / Java 注释里都是事故源，直接拒
  if (/[\u0000-\u001f\u007f]/.test(s)) {
    throw new ValidationError(`${label} 含控制字符或换行，请改为单行文本`)
  }
  // 直双引号会破坏 plugin.json 的 JSON 合法性，反斜杠会破坏 Java 注释与 JSON 转义，一律换全角
  s = s.trim().replace(/"/g, '”').replace(/\\/g, '＼').replace(/\s{2,}/g, ' ')
  if (!s) {
    if (required) throw new ValidationError(`${label} 不能为空`)
    return ''
  }
  if (s.length > max) {
    throw new ValidationError(`${label} 过长（${s.length} 字符，上限 ${max}）`)
  }
  return s
}

/** kind 取值校验。 */
export function assertKind(kind) {
  if (!KINDS.includes(kind)) {
    throw new ValidationError(`--kind 只能是 ${KINDS.join(' | ')}，当前 "${kind}"`, {
      hints: [
        'java = 只有后端（Java 工具/Advisor/RPC，产出 jar）',
        'web = 只有前端 UI（web/index.ts，无 worker 端代码）',
        'full = 前后端都要（一份 plugin.json 同时含 main 与 webMain）',
      ],
    })
  }
  return kind
}

/** mode 取值校验。 */
export function assertMode(mode) {
  if (!MODES.includes(mode)) {
    throw new ValidationError(`--mode 只能是 ${MODES.join(' | ')}，当前 "${mode}"`, {
      hints: [
        'builtin = 生成到仓库内 every-agent-plugins/<id>/，复用宿主的依赖与 typecheck',
        'standalone = 仓库外独立工程，自带类型包副本 / tsconfig / 构建脚本',
      ],
    })
  }
  return mode
}

/**
 * id 校验，失败抛 ValidationError（带规则原文）。
 * @param {string} id
 * @returns {string}
 */
export function assertValidId(id) {
  const result = validatePluginId(id)
  if (!result.ok) {
    throw new ValidationError(`非法插件 id："${id}" —— ${result.reason}`, {
      hints: [
        `规则 ${ID_PATTERN_TEXT}，且首尾必须是字母或数字，长度 2~${ID_MAX}。`,
        '示例 empty-response-retry / pdf-viewer / task-input-queue',
        'id 同时用于包名 dev.everyagent.plugin.<camelCase(id)> 与入口类 PascalCase(id)Plugin',
      ],
    })
  }
  return id
}

/**
 * 逐级向上找「含 every-agent-plugins 子目录」的最近祖先目录。
 * @param {string[]} startDirs 依次尝试的起点（cwd 优先，其次 CLI 包所在目录）
 * @returns {string|null}
 */
export function findRepoRoot(startDirs) {
  for (const start of startDirs) {
    let dir = path.resolve(start)
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const probe = path.join(dir, PLUGINS_DIR_NAME)
      try {
        if (fs.statSync(probe).isDirectory()) return dir
      } catch {
        /* 不存在则继续向上 */
      }
      const parent = path.dirname(dir)
      if (parent === dir) break
      dir = parent
    }
  }
  return null
}

/**
 * 计算输出目录。
 * @param {object} input
 * @param {string} input.dirArg 位置参数 [dir]（可空）
 * @param {string} input.id
 * @param {string} input.mode
 * @param {string} input.cwd
 * @param {string[]} input.searchStarts findRepoRoot 的起点列表
 * @returns {{target: string, repoRoot: string|null, auto: boolean}}
 */
export function resolveTarget({ dirArg, id, mode, cwd, searchStarts }) {
  const repoRoot = findRepoRoot(searchStarts)
  if (dirArg) {
    return { target: path.resolve(cwd, dirArg), repoRoot, auto: false }
  }
  if (mode === 'builtin') {
    if (!repoRoot) {
      throw new ValidationError(
        `mode=builtin 需要仓库根（含 ${PLUGINS_DIR_NAME}/ 的最近祖先目录），当前未找到`,
        {
          hints: [
            `已尝试从 ${searchStarts.map((p) => JSON.stringify(p)).join(' / ')} 向上查找`,
            `请改用 --mode standalone，或显式给出输出目录：node create-everyagent-plugin <目录> --mode builtin`,
          ],
        },
      )
    }
    return { target: path.join(repoRoot, PLUGINS_DIR_NAME, id), repoRoot, auto: true }
  }
  return { target: path.resolve(cwd, id), repoRoot, auto: true }
}

/**
 * 目标目录冲突判定：存在且非空且未给 --force → 抛 ConflictError。
 * @param {string} target
 * @param {boolean} force
 * @param {string[]} plannedRelPaths 将要写入的相对路径（排序后用于报错展示）
 * @returns {string[]} 冲突时（--force）将被覆盖的已有文件清单
 */
export function checkTargetConflict(target, force, plannedRelPaths) {
  let entries = []
  try {
    entries = fs.readdirSync(target)
  } catch {
    return [] // 目录不存在 → 无冲突
  }
  const stat = fs.statSync(target)
  if (!stat.isDirectory()) {
    throw new ConflictError(`目标路径不是目录，无法生成插件：${target}`, {
      hints: ['请先移除该文件，或换一个输出目录'],
    })
  }
  if (entries.length === 0) return []
  if (!force) {
    const sample = plannedRelPaths
      .filter((rel) => fs.existsSync(path.join(target, ...rel.split('/'))))
      .slice(0, 8)
    const hints = [`目标目录已存在且非空：${target}`, `现有条目（前 10 项）：${entries.slice(0, 10).join(', ')}`]
    if (sample.length) {
      hints.push('其中将被覆盖的文件：')
      hints.push(...sample.map((r) => `  ${r}`))
    }
    hints.push('确认要覆盖请加 --force（其余既有文件不会被删除）')
    throw new ConflictError('目标目录已存在且非空，拒绝覆盖', { hints })
  }
  // --force：只报告将要覆盖的既有文件
  return plannedRelPaths.filter((rel) => fs.existsSync(path.join(target, ...rel.split('/'))))
}

/**
 * 把绝对路径转成相对展示路径：优先相对仓库根，其次相对 cwd，最后绝对路径。
 * 用正斜杠，方便直接复制到命令里。
 * @param {string} abs
 * @param {{repoRoot?: string|null, cwd?: string}} [base]
 * @returns {string}
 */
export function displayPath(abs, { repoRoot = null, cwd = process.cwd() } = {}) {
  const cands = []
  if (repoRoot) cands.push(repoRoot)
  if (cwd) cands.push(cwd)
  for (const base of cands) {
    const rel = path.relative(base, abs)
    if (rel && !rel.startsWith('..') && !path.isAbsolute(rel)) {
      return rel.split(path.sep).join('/')
    }
  }
  return abs.split(path.sep).join('/')
}
