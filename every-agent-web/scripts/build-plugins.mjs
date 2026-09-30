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
 */

import { build } from 'esbuild'
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

async function main() {
  const entries = discoverPluginEntries()
  if (entries.length === 0) {
    console.warn('[build-plugins] 没有发现任何插件 web/index.ts 入口，退出。')
    return
  }

  console.log(`[build-plugins] 发现 ${entries.length} 个插件入口:`)
  for (const e of entries) {
    console.log(`  - ${e.id}`)
  }

  const external = [
    'react',
    'react-dom',
    'react/jsx-runtime',
    'antd',
    '@ant-design/icons',
  ]

  for (const entry of entries) {
    console.log(`\n[build-plugins] 构建 ${entry.id} ...`)
    await build({
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
    })
  }

  console.log(`\n[build-plugins] 完成，共构建 ${entries.length} 个插件。`)
}

main().catch(err => {
  console.error('[build-plugins] 构建失败:', err)
  process.exit(1)
})
