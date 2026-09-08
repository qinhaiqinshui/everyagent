/**
 * 前端构建接入:在 every-agent-web 执行 npm run build,并把 dist 复制到
 * every-agent-desktop/resources/web/。
 *
 * 用法:npm run build:web
 * 前置:node/npm 在 PATH,every-agent-web 的 node_modules 已安装(npm install)。
 */
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, cpSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const desktopRoot = resolve(__dirname, '..')
const repoRoot = resolve(desktopRoot, '..')
const webDir = join(repoRoot, 'every-agent-web')
const webDist = join(webDir, 'dist')
const outDir = join(desktopRoot, 'resources', 'web')

console.log('[build-web] 构建 every-agent-web(vite build)...')
const r = spawnSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', 'build'], {
  cwd: webDir,
  stdio: 'inherit',
  shell: process.platform === 'win32',
})
if (r.status !== 0) {
  console.error(`[build-web] 前端构建失败,退出码 ${r.status}`)
  process.exit(r.status ?? 1)
}

if (!existsSync(webDist)) {
  console.error('[build-web] 未找到 every-agent-web/dist')
  process.exit(1)
}

rmSync(outDir, { recursive: true, force: true })
mkdirSync(outDir, { recursive: true })
cpSync(webDist, outDir, { recursive: true })
console.log(`[build-web] 复制完成:${webDist} -> ${outDir}`)