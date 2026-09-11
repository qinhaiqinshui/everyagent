/**
 * 后端打包流水线:构建 hub/worker 的 Spring Boot 可执行 jar 并复制到
 * every-agent-desktop/resources/backend/。
 *
 * 用法:npm run build:backend
 * 前置:JAVA_HOME 指向 JDK 25,mvn 在 PATH(或 MAVEN_HOME 指向 mvn 目录)。
 */
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, copyFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const desktopRoot = resolve(__dirname, '..')
const repoRoot = resolve(desktopRoot, '..')

const backendDir = join(desktopRoot, 'resources', 'backend')

function findJar(moduleDir, prefix, suffix = '.jar') {
  const target = join(repoRoot, moduleDir, 'target')
  if (!existsSync(target)) return null
  const files = readdirSync(target).filter(
    (f) => f.startsWith(prefix) && f.endsWith(suffix) && !f.endsWith('-sources.jar') && !f.endsWith('-javadoc.jar'),
  )
  if (files.length === 0) return null
  // ① 优先 exec 分类器(hub 配了 classifier 的 Spring Boot 可执行 jar);
  // ② 同组取 mtime 最新:target 常残留多版本旧 jar,按文件名猜版本不可靠——
  //   曾按「文件名最长」选,SNAPSHOT 旧包恒最长相中,导致打包进的是陈年 worker
  //   (前端新而 fs.browse 能力缺失,外部文件选择降级仅目录的根因)。
  const newest = (a, b) => statSync(join(target, b)).mtimeMs - statSync(join(target, a)).mtimeMs
  const exec = files.filter((f) => f.endsWith('-exec.jar')).sort(newest)
  if (exec.length > 0) return join(target, exec[0])
  return join(target, [...files].sort(newest)[0])
}

function run(cmd, args) {
  const mvn = resolveMaven(cmd)
  const r = spawnSync(mvn, args, { cwd: repoRoot, stdio: 'inherit', shell: process.platform === 'win32' })
  if (r.status !== 0) {
    console.error(`[build-backend] ${cmd} 失败,退出码 ${r.status}`)
    process.exit(r.status ?? 1)
  }
}

/**
 * 定位 mvn:优先 MAVEN_HOME,其次 PATH;都不在时探测本机常见安装位置。
 * (AGENTS.md 已知安装:D:\maven、D:\Program Files\apache-maven-3.9.15)
 */
function resolveMaven(cmd) {
  if (process.env.MAVEN_HOME) return join(process.env.MAVEN_HOME, 'bin', cmd)
  const onPath = spawnSync(cmd, ['-v'], { shell: process.platform === 'win32', encoding: 'utf8' })
  if (onPath.status === 0) return cmd
  const candidates = ['D:\\maven', 'D:\\Program Files\\apache-maven-3.9.15']
  for (const dir of candidates) {
    if (existsSync(join(dir, 'bin', cmd))) return join(dir, 'bin', cmd)
  }
  console.error(`[build-backend] 找不到 ${cmd}:请设置 MAVEN_HOME 或将 maven 加入 PATH`)
  process.exit(1)
}

console.log('[build-backend] 构建 every-agent-hub + every-agent-worker(含 contract 依赖)...')
run(
  process.platform === 'win32' ? 'mvn.cmd' : 'mvn',
  ['-pl', 'every-agent-hub,every-agent-worker', '-am', '-DskipTests', 'package'],
)

mkdirSync(backendDir, { recursive: true })

const hubJar = findJar('every-agent-hub', 'every-agent-hub-')
const workerJar = findJar('every-agent-worker', 'every-agent-worker-')
if (!hubJar || !workerJar) {
  console.error('[build-backend] 未找到 hub/worker jar,target 目录:', join(repoRoot, 'every-agent-hub', 'target'))
  process.exit(1)
}

const hubOut = join(backendDir, 'hub.jar')
const workerOut = join(backendDir, 'worker.jar')
copyFileSync(hubJar, hubOut)
copyFileSync(workerJar, workerOut)
console.log(
  `[build-backend] 复制完成:\n  ${hubJar} -> ${hubOut} (${statSync(hubOut).size} bytes)\n  ${workerJar} -> ${workerOut} (${statSync(workerOut).size} bytes)`,
)