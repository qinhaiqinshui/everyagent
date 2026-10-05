#!/usr/bin/env node
// ea: standalone 前端构建脚本：esbuild 打包 web/index.ts → web/index.js，选项与宿主 build-plugins.mjs 逐项一致
/**
 * standalone 形态前端构建脚本（脱离宿主仓库也能出包）。
 *
 * 1. esbuild 选项与宿主 every-agent-web/scripts/build-plugins.mjs **逐项一致**：
 *      bundle: true / format: 'esm' / target: 'es2022' / platform: 'browser'
 *      sourcemap: true / jsx: 'automatic'
 *    —— 产物形态必须与宿主 pluginLoader 的期待一致（ESM 模块、blob URL import），
 *      不要私自改 target / format / jsx，否则宿主加载即失败。
 * 2. external 的 5 项（react / react-dom / react/jsx-runtime / antd / @ant-design/icons）
 *    运行时由宿主 window.__EA_REACT__ / __EA_REACT_DOM__ / __EA_REACT_JSX__ /
 *    window.__EA_antd__ / window.__EA_ICONS__ 全局提供，故 bundle 里不打包；
 *    宿主 rewriteBareImports 只改写这 5 个 bare import，多引一个都会在加载时抛错。
 * 3. esbuild 版本与宿主同源：宿主经 vite 传递依赖解析到 0.21.x，
 *    本工程 package.json 的 devDependencies 固定 ^0.21.5（见 package.json）。
 * 4. @everyagent/plugin-api 是纯类型包，esbuild 编译期自动擦除，无需安装。
 * 5. 产物 web/index.js（CSS import 另产 index.css，均带 .map）是 gitignored 的构建产物；
 *    分发时随插件目录拷到 worker 机器的 ~/.everyagent/plugins/<id>/ 下（plugin.json 在顶层）。
 */
import { existsSync } from 'node:fs'
import { build } from 'esbuild'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const pluginRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const entry = resolve(pluginRoot, 'web', 'index.ts')

if (!existsSync(entry)) {
  console.error(`[build] 找不到前端入口 ${entry}`)
  process.exit(1)
}

// 与宿主 build-plugins.mjs 完全一致的 external 白名单（顺序也保持一致，便于 diff 对照）。
const external = [
  'react',
  'react-dom',
  'react/jsx-runtime',
  'antd',
  '@ant-design/icons',
]

await build({
  entryPoints: [entry],
  bundle: true,
  format: 'esm',
  target: 'es2022',
  platform: 'browser',
  sourcemap: true,
  external,
  // automatic JSX runtime：JSX 会被编译成 import { jsx } from "react/jsx-runtime"，
  // 宿主 rewriteBareImports 将其改写为 window.__EA_REACT_JSX__ 全局引用。
  // 本模板入口用 React.createElement，不依赖此项，但保持与宿主一致以支持后续引入 JSX。
  jsx: 'automatic',
  outfile: resolve(pluginRoot, 'web', 'index.js'),
  logLevel: 'info',
})

console.log('[build] 完成：web/index.js（如有 CSS import 另产 index.css；产物均已 gitignore）')
