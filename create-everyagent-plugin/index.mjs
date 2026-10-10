#!/usr/bin/env node
/**
 * create-everyagent-plugin —— Every Agent 插件工程脚手架 CLI 入口。
 *
 * 零运行时依赖（只用 node:* 内置模块），约束见 README「零依赖硬约束」节。
 * 职责很窄：解析参数 → 交互补齐 → 校验/推导命名 → 解析模板层并渲染 → 落盘或 dry-run 打印
 *          → 输出「下一步」命令块。渲染逻辑在 lib/render.mjs（模板契约也写在它顶部注释）；
 *          pack 子命令（.eap 打包，零依赖 zip）在 lib/zip.mjs，自带独立 flag 集（-o/--verify）。
 *
 * 退出码：0 成功 / 1 用法错误 / 2 参数校验失败 / 3 目标目录冲突 / 4 写盘失败 / 5 模板错误 / 130 用户取消
 */

import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { parseArgs } from './lib/args.mjs'
import { CanceledError, EXIT, UsageError, isCliError } from './lib/errors.mjs'
import { DEFAULT_VERSION, deriveVars } from './lib/naming.mjs'
import { fillDefaults, isInteractive, promptSpec } from './lib/prompts.mjs'
import { PACKAGE_DIR, planRender, writeFiles } from './lib/render.mjs'
import { formatPlan } from './lib/tree.mjs'
import {
  assertKind,
  assertMode,
  assertValidId,
  checkTargetConflict,
  displayPath,
  resolveTarget,
  sanitizeText,
} from './lib/validate.mjs'
import { runPack } from './lib/zip.mjs'

const OUT = process.stdout
const ERR = process.stderr

/** 帮助文本（--help 原文输出，无 emoji）。 */
const HELP = `create-everyagent-plugin —— Every Agent 插件工程脚手架

用法:
  node create-everyagent-plugin [dir] [选项]
  node create-everyagent-plugin pack <pluginDir> [-o <输出目录>] [--verify]   # .eap 打包
  npm create everyagent-plugin [dir] [选项]          # 发布到 npm 后等价

位置参数:
  dir                        输出目录。给了就用它；缺省时按 mode 推导：
                             builtin    → <仓库根>/every-agent-plugins/<id>/
                             standalone → ./<id>/
                           仓库根 = 含 every-agent-plugins/ 的最近祖先目录（从 cwd 与包目录向上找）

选项:
  --id <id>                  插件 id：^[a-z0-9][a-z0-9-]{1,38}$ 且首尾不能是连字符（2~39 字符）
  --name <显示名>            plugin.json 的 name，缺省 = id
  --desc <描述>              plugin.json 的 description，缺省 = "<name> 插件"
  --author <作者>            plugin.json 的 author，缺省 everyagent
  --kind <java|web|full>     java=只有后端；web=只有前端 UI；full=前后端都要（缺省 full）
  --mode <builtin|standalone>
                             builtin=建在仓库内 every-agent-plugins/ 下，复用宿主依赖与 typecheck
                             standalone=仓库外独立工程，自带 plugin-api 类型副本 + tsconfig + 构建脚本
  --yes, -y                  非交互：全部取缺省值（必须能确定 --id，否则报错）
  --force, -f                目标目录已存在且非空时允许覆盖（只写同名文件，不删除其他既有文件）
  --dry-run                  只打印将生成的文件树与每个文件的作用，不写盘
  -h, --help                 显示本帮助
  -v, --version              显示版本

示例:
  node create-everyagent-plugin my-tool --kind full --yes
  node create-everyagent-plugin --id pdf-viewer --name "PDF 预览" --kind web
  node create-everyagent-plugin ../work/my-tool --kind java --mode standalone --dry-run
  npm create everyagent-plugin my-tool
  node create-everyagent-plugin pack every-agent-plugins/my-tool --verify    # 打 .eap 安装包

模板目录契约见 lib/render.mjs 顶部注释；完整用法见 create-everyagent-plugin/README.md。
退出码: 0 成功 / 1 用法错误 / 2 校验失败 / 3 目录冲突 / 4 写盘失败 / 5 模板错误 / 130 取消
`

/** 读 CLI 自身版本（不用 import JSON，兼容 node 20）。 */
function readVersion() {
  try {
    const raw = fs.readFileSync(path.join(PACKAGE_DIR, 'package.json'), 'utf8')
    return JSON.parse(raw).version || DEFAULT_VERSION
  } catch {
    return DEFAULT_VERSION
  }
}

/** 把 flag 值收成合法文本字段。 */
function flagText(values, key, label, { max = 200 } = {}) {
  const raw = values[key]
  if (raw === undefined) return null
  return sanitizeText(label, raw, { max })
}

/**
 * 组织「下一步」提示（按 kind / mode 分支，中文说明 + 可复制命令）。
 * @param {object} input
 * @returns {string[]}
 */
export function formatNextSteps({ spec, vars, relTarget, absTarget }) {
  const lines = ['', '下一步：', '']
  const pomCmd = `mvn -f ${relTarget}/pom.xml package`
  const bundleCmd = 'npm run build:plugins'
  const packCmd = `node create-everyagent-plugin pack ${relTarget}`

  if (spec.mode === 'builtin') {
    if (spec.kind === 'java' || spec.kind === 'full') {
      lines.push('1) 构建后端 jar（插件不进根 reactor，必须 -f 单独构建；需要先装好 plugin-api）')
      lines.push('   ' + pomCmd)
      lines.push('   首次构建若报找不到 dev.everyagent:every-agent-plugin-api，先在仓库根执行：')
      lines.push('   mvn -pl every-agent-plugin-api -am install -DskipTests')
      lines.push('')
    }
    if (spec.kind === 'web' || spec.kind === 'full') {
      lines.push(`${spec.kind === 'full' ? '2' : '1'}) 构建前端 bundle（改 web/ 下任何文件都要手工重跑，它不在 dev/build 任何流水线里）`)
      lines.push('   cd every-agent-web')
      lines.push(`   ${bundleCmd}`)
      lines.push('   （PowerShell 若被执行策略拦下 npm，请改用 npm.cmd run build:plugins）')
      lines.push('')
    }
    lines.push('最后一步（两种形态都要）：重启 worker —— 插件没有热重载，且内置插件要求 cwd 在仓库根。')
    lines.push('验证是否加载：看 worker 日志「插件已激活: id=...」，或前端调用 plugin.list RPC。')
    lines.push('')
    lines.push('打包分发（.eap，重启验证前的最后一步可选）：')
    lines.push('   ' + packCmd + ' --verify')
    return lines
  }

  // standalone
  lines.push('standalone 形态（仓库外独立工程）后续步骤：')
  lines.push('')
  if (spec.kind === 'java' || spec.kind === 'full') {
    lines.push('1) 构建后端 jar。注意 every-agent-plugin-api 未发布到 Maven 中央仓库，')
    lines.push('   需先在宿主仓库根把 API 装进本地仓库，再构建本工程：')
    lines.push('   mvn -pl every-agent-plugin-api -am install -DskipTests')
    lines.push('   ' + pomCmd)
    lines.push('')
  }
  if (spec.kind === 'web' || spec.kind === 'full') {
    lines.push(`${spec.kind === 'full' ? '2' : '1'}) 构建前端 bundle（模板自带脚本，依赖 esbuild）：`)
    lines.push(`   cd ${relTarget}`)
    lines.push('   npm install')
    lines.push('   node scripts/build.mjs')
    lines.push('')
  }
  lines.push('最后一步：安装并重启 worker —— 插件没有热重载。')
  lines.push(`   把产物放进 worker 机器的 ~/.everyagent/plugins/${vars.pluginId}/`)
  lines.push('   （后端 jar 放 lib/*.jar，前端 bundle 放 web/index.js，plugin.json 在顶层）')
  lines.push('')
  lines.push('打包分发（.eap，比手工拷目录省事）：')
  lines.push('   ' + packCmd + ' --verify')
  return lines
}

/**
 * 主流程。
 * @param {string[]} argv 已剥掉 node 与脚本路径
 * @param {object} [io]
 * @returns {Promise<number>} 退出码
 */
export async function main(argv = process.argv.slice(2), io = process) {
  // pack 子命令有独立 flag 集（-o/--verify 不在主命令集，parseArgs 会当未知 flag 拒掉），
  // 必须先于 parseArgs 截走，由 lib/zip.mjs 自己解析。
  if (argv[0] === 'pack') {
    return runPack(argv.slice(1), { io })
  }

  const parsed = parseArgs(argv)

  if (parsed.bools.help) {
    io.stdout.write(HELP)
    return EXIT.OK
  }
  if (parsed.bools.version) {
    io.stdout.write(`${readVersion()}\n`)
    return EXIT.OK
  }
  if (parsed.subcommand === 'pack') {
    // 只有 `-- pack …` 这类罕见形态会走到这里（parseArgs 视 pack 为保留子命令但已消费掉参数位）：
    // 主 flag 集不认识 pack 的参数，与其猜不如直接引导正确写法。
    throw new UsageError('pack 子命令必须紧跟在命令名之后', {
      hints: [`正确写法：node create-everyagent-plugin pack <pluginDir> [-o <输出目录>] [--verify]`],
    })
  }

  const interactive = isInteractive(io) && !parsed.bools.yes
  if (!interactive && !parsed.bools.yes && !parsed.dirArg && parsed.values.id === undefined) {
    throw new UsageError('没有可交互的 stdin，也未给出任何参数', {
      hints: ['用 --help 看用法；最小调用：node create-everyagent-plugin my-tool --kind java --yes'],
    })
  }

  const dirHint = parsed.dirArg ? path.basename(path.resolve(process.cwd(), parsed.dirArg)) : null
  let spec = {
    id: parsed.values.id !== undefined ? String(parsed.values.id) : null,
    name: flagText(parsed.values, 'name', '--name', { max: 60 }),
    desc: flagText(parsed.values, 'desc', '--desc', { max: 200 }),
    author: flagText(parsed.values, 'author', '--author', { max: 60 }),
    kind: parsed.values.kind !== undefined ? assertKind(String(parsed.values.kind).toLowerCase()) : null,
    mode: parsed.values.mode !== undefined ? assertMode(String(parsed.values.mode).toLowerCase()) : null,
    version: DEFAULT_VERSION,
    dirHint,
    dirArg: parsed.dirArg,
  }

  if (spec.id) spec.id = String(spec.id).trim()
  if (spec.id === '') spec.id = null

  if (interactive) {
    spec = await promptSpec(spec, { io })
  } else {
    spec = fillDefaults(spec)
  }
  spec.name = sanitizeText('插件显示名 name', spec.name, { max: 60 }) || spec.id
  spec.desc = sanitizeText('插件描述 desc', spec.desc, { max: 200 })
  spec.author = sanitizeText('作者 author', spec.author, { max: 60 })

  // 校验 id + 推导模板变量（deriveVars 内部也会拒绝无法派生合法类名的 id）
  assertValidId(spec.id)
  const vars = deriveVars(spec)

  // 输出目录
  const { target, repoRoot, auto } = resolveTarget({
    dirArg: parsed.dirArg,
    id: vars.pluginId,
    mode: spec.mode,
    cwd: process.cwd(),
    searchStarts: [process.cwd(), PACKAGE_DIR],
  })
  const relTarget = displayPath(target, { repoRoot, cwd: process.cwd() })

  // 渲染
  const plan = planRender({ kind: spec.kind, mode: spec.mode, vars })
  io.stdout.write(
    formatPlan({
      target,
      displayTarget: relTarget,
      spec,
      vars,
      root: plan.root,
      fromEnv: plan.fromEnv,
      layersUsed: plan.layersUsed,
      files: plan.files,
    }) + '\n',
  )

  if (parsed.bools['dry-run']) {
    io.stdout.write('\n--dry-run：以上文件未写入。\n')
    return EXIT.OK
  }

  // 目录冲突判定 + --force 覆盖清单
  const plannedRels = plan.files.map((f) => f.relPath)
  const overwritten = checkTargetConflict(target, Boolean(parsed.bools.force), plannedRels)
  if (overwritten.length) {
    io.stdout.write(`\n--force：将覆盖 ${overwritten.length} 个既有文件\n`)
    overwritten.forEach((rel) => io.stdout.write(`  ${rel}\n`))
  }

  const written = writeFiles(target, plan.files)
  io.stdout.write(`\n已生成 ${written.length} 个文件 → ${target}\n`)
  if (auto && spec.mode === 'builtin') {
    io.stdout.write(`（位置：仓库内 every-agent-plugins/${vars.pluginId}/，worker 重启后即被内置扫描发现）\n`)
  }
  formatNextSteps({ spec, vars, relTarget, absTarget: target }).forEach((line) =>
    io.stdout.write(line + '\n'),
  )
  return EXIT.OK
}

/** 顶层错误处理：统一走 stderr，不用 emoji 前缀。 */
async function cli() {
  let code = EXIT.OK
  try {
    code = await main()
  } catch (err) {
    if (isCliError(err)) {
      ERR.write(`错误：${err.message}\n`)
      for (const hint of err.hints || []) {
        ERR.write(`  ${hint}\n`)
      }
      if (err instanceof CanceledError) {
        code = EXIT.CANCELED
      } else {
        code = err.exitCode
      }
    } else {
      ERR.write(`错误：脚手架内部异常：${err && err.message ? err.message : String(err)}\n`)
      if (err && err.stack) ERR.write(String(err.stack).split('\n').map((l) => `  ${l}`).join('\n') + '\n')
      code = EXIT.IO
    }
  }
  process.exitCode = code
  return code
}

/**
 * 是否被直接执行（`node create-everyagent-plugin`、`node create-everyagent-plugin/index.mjs`、
 * npm bin 链接都算；被 import 时不自动跑，便于测试）。
 * 注意：`node <目录>` 时 process.argv[1] 就是那个目录，必须按目录比较。
 */
function isDirectRun() {
  const entry = process.argv[1]
  if (!entry) return false
  try {
    const self = fileURLToPath(import.meta.url)
    const target = path.resolve(entry)
    if (fs.statSync(target).isDirectory()) {
      return fs.realpathSync(target) === fs.realpathSync(path.dirname(self))
    }
    return fs.realpathSync(target) === fs.realpathSync(self)
  } catch {
    return false
  }
}

if (isDirectRun()) {
  await cli()
}
