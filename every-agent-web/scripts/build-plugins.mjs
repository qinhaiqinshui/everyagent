/**
 * 内置插件 web/ 预编译脚本。
 *
 * 用 esbuild 将每个 every-agent-plugins/<id>/web/index.ts 编译为
 * every-agent-plugins/<id>/web/index.js（ESM），供后端 plugin.webSource
 * RPC 直接返回给前端执行。
 *
 * 设计要点:
 * - external: react / react-dom / react/jsx-runtime / antd / @ant-design/icons
 *   这些是宿主级依赖，由前端运行时全局提供，不打包进插件 bundle。
 * - @everyagent/plugin-api 是纯类型包（零运行时代码），esbuild 编译时
 *   自动 tree-shake 掉，无需特殊处理。
 * - target: es2022（与 Vite 默认 target 一致）
 * - sourcemap: true
 * - CSS: esbuild 默认支持 .css / .less，插件中如有 CSS import 会生成
 *   同名 .css 产物并自动注入。
 *
 * 用法:
 * - node scripts/build-plugins.mjs                全量构建一次（默认行为，桌面打包链依赖）
 * - node scripts/build-plugins.mjs --only git     只构建指定 id 的插件（逗号分隔多个）
 * - node scripts/build-plugins.mjs --watch        首次构建后监听变更自动重编（Ctrl+C 退出）
 * - --only 与 --watch 可组合，如: --only git,subagent --watch
 */

import { build, context } from 'esbuild'
import { readdirSync, statSync } from 'node:fs'
import { resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(__dirname, '..', '..')
const pluginsRoot = resolve(repoRoot, 'every-agent-plugins')

/** 扫描所有有 web/index.ts 的内置插件，返回 esbuild 入口列表。 */
function discoverPluginEntries() {
  const entries = []
  const pluginDirs = readdirSync(pluginsRoot).filter(name => {
    const p = join(pluginsRoot, name)
    return statSync(p).isDirectory() && name !== 'target'
  })
  for (const id of pluginDirs) {
    const entryTs = join(pluginsRoot, id, 'web', 'index.ts')
    let exists = false
    try { statSync(entryTs); exists = true } catch { exists = false }
    if (exists) {
      entries.push({
        id,
        in: entryTs,
        outDir: join(pluginsRoot, id, 'web'),
      })
    }
  }
  return entries
}

/** 单个插件入口的 esbuild 配置（一次性构建与 watch 共用同一份）。 */
function esbuildOptions(entry) {
  const external = [
    'react',
    'react-dom',
    'react/jsx-runtime',
    'antd',
    '@ant-design/icons',
    // Markdown 渲染：宿主经 window.__EA_REACT_MARKDOWN__ / __EA_REMARK_GFM__
    // 注入各自包的 default export（与 pluginLoader.ts 的 BARE_IMPORT_MAP 三处锁定
    // 同一份清单，改一处必同步另一处+文档）。插件侧只可用默认导入形态。
    'react-markdown',
    'remark-gfm',
  ]
  return {
    entryPoints: [entry.in],
    bundle: true,
    format: 'esm',
    target: 'es2022',
    platform: 'browser',
    sourcemap: true,
    external,
    // 使用 automatic JSX runtime（与宿主 tsconfig.json 的 "jsx": "react-jsx" 一致），
    // 产出 import { jsx } from "react/jsx-runtime" 而非 React.createElement。
    // 前端 pluginLoader 的 rewriteBareImports 会将 react/jsx-runtime 重写为
    // window.__EA_REACT_JSX__ 全局引用。
    jsx: 'automatic',
    outfile: join(entry.outDir, 'index.js'),
    logLevel: 'info',
  }
}

/** 解析命令行参数（--only <id[,id...]> / --watch），未知参数报错退出。 */
function parseArgs(argv) {
  const opts = { only: null, watch: false }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--only') {
      const value = argv[++i]
      if (value === undefined) {
        console.error('[build-plugins] 错误:--only 需要插件 id 参数，如 --only git（逗号分隔多个）。')
        process.exit(1)
      }
      opts.only = value
    } else if (arg === '--watch') {
      opts.watch = true
    } else {
      console.error(`[build-plugins] 错误:未知参数 ${arg}，用法: node scripts/build-plugins.mjs [--only <id>[,<id>...]] [--watch]`)
      process.exit(1)
    }
  }
  return opts
}

/** 按 --only 的 id 列表过滤入口；含不存在的 id 时列出可用清单并以非 0 码退出。 */
function filterEntriesByOnly(entries, onlyArg) {
  const onlyIds = onlyArg.split(',').map(s => s.trim()).filter(s => s.length > 0)
  if (onlyIds.length === 0) {
    console.error('[build-plugins] 错误:--only 参数为空，需至少一个插件 id。')
    process.exit(1)
  }
  const known = new Set(entries.map(e => e.id))
  const missing = onlyIds.filter(id => !known.has(id))
  if (missing.length > 0) {
    console.error(`[build-plugins] 错误:--only 中不存在的插件 id: ${missing.join(', ')}`)
    console.error('[build-plugins] 可用的插件 id:')
    for (const e of entries) {
      console.error(`  - ${e.id}`)
    }
    process.exit(1)
  }
  const selected = entries.filter(e => onlyIds.includes(e.id))
  console.log(`[build-plugins] --only:只构建 ${selected.length}/${entries.length} 个入口（${selected.map(e => e.id).join(', ')}）`)
  return selected
}

/** watch 模式:esbuild context 原生监听（rebuild API），首次构建后常驻，Ctrl+C 干净退出。 */
async function watchEntries(selected) {
  console.log('\n[build-plugins] watch 模式:首次构建后监听变更自动重编，Ctrl+C 退出。')
  const contexts = []
  for (const entry of selected) {
    console.log(`\n[build-plugins] 构建 ${entry.id} ...`)
    const ctx = await context(esbuildOptions(entry))
    contexts.push(ctx)
    // watch() 启动监听并执行首次构建，后续变更由 esbuild 自动 rebuild。
    await ctx.watch()
  }

  console.log('\n[build-plugins] 监听中…')

  let exiting = false
  const shutdown = async signal => {
    if (exiting) return
    exiting = true
    console.log(`\n[build-plugins] 收到 ${signal}，释放 esbuild context 后退出。`)
    await Promise.allSettled(contexts.map(ctx => ctx.dispose()))
    process.exit(0)
  }
  process.on('SIGINT', () => shutdown('SIGINT'))
  process.on('SIGTERM', () => shutdown('SIGTERM'))
  if (process.platform === 'win32') {
    // Windows 控制台 Ctrl+Break 对应 SIGBREAK，同样干净退出。
    process.on('SIGBREAK', () => shutdown('SIGBREAK'))
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2))
  const entries = discoverPluginEntries()
  if (entries.length === 0) {
    console.warn('[build-plugins] 没有发现任何插件 web/index.ts 入口，退出。')
    return
  }

  console.log(`[build-plugins] 发现 ${entries.length} 个插件入口:`)
  for (const e of entries) {
    console.log(`  - ${e.id}`)
  }

  const selected = opts.only === null ? entries : filterEntriesByOnly(entries, opts.only)

  if (opts.watch) {
    await watchEntries(selected)
    return
  }

  for (const entry of selected) {
    console.log(`\n[build-plugins] 构建 ${entry.id} ...`)
    await build(esbuildOptions(entry))
  }

  console.log(`\n[build-plugins] 完成，共构建 ${selected.length} 个插件。`)
}

main().catch(err => {
  console.error('[build-plugins] 构建失败:', err)
  process.exit(1)
})
