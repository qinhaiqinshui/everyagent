/**
 * 通用插件 runtime 资源打包脚本。
 *
 * 设计原则:desktop 模块不感知任何具体插件。脚本只做两件事:
 *   1. 清理 shared runtime/ 目录下所有插件管理的子目录(防止残留打进安装包)
 *   2. 遍历 every-agent-plugins/ 下所有插件,读取 plugin.json 的 enabled 字段,
 *      把已启用插件的 runtime/ 子目录内容合并复制到 shared runtime/
 *
 * 插件如何声明 runtime 资源:在插件根目录下建 runtime/ 子目录,其中放需要
 * 分发到 <resourcesPath>/runtime/ 的文件/子目录。脚本会原样合并到 shared runtime/。
 * 插件被禁用(enabled=false)时,其 runtime/ 不会被复制,已有的残留会被清理。
 *
 * shared runtime/ 中的核心资源(rg.exe、eagent-run.py 等)不受影响:
 * 清理只针对插件 runtime/ 子目录中出现的路径,不会删除核心文件。
 *
 * 用法:npm run build:plugin-runtime(由 build:assets 调用)。
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const desktopRoot = resolve(__dirname, '..')
const repoRoot = resolve(desktopRoot, '..')

const pluginsDir = join(repoRoot, 'every-agent-plugins')
const sharedRuntimeDir = join(repoRoot, 'runtime')

/**
 * 读取 plugin.json 的 enabled 字段。
 * 缺省(无该字段)视为 true。解析失败也视为 true(宽容)。
 */
function isEnabled(pluginDir) {
  const manifest = join(pluginDir, 'plugin.json')
  if (!existsSync(manifest)) return true
  try {
    const json = JSON.parse(readFile(manifest))
    return json.enabled !== false
  } catch {
    return true
  }
}

function readFile(path) {
  return readFileSync(path, 'utf8')
}

/**
 * 递归收集 dir 下所有相对路径(相对 dir)。
 */
function collectRelativePaths(dir, base = '') {
  const result = []
  if (!existsSync(dir)) return result
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    const rel = base ? `${base}/${entry}` : entry
    if (statSync(full).isDirectory()) {
      result.push(...collectRelativePaths(full, rel))
    } else {
      result.push(rel)
    }
  }
  return result
}

// ---------------------------------------------------------------------------
// 1. 收集所有插件管理的 runtime 路径(包括禁用的插件,确保清理覆盖)
// ---------------------------------------------------------------------------
const pluginManagedPaths = new Set()
const enabledPlugins = []

if (existsSync(pluginsDir)) {
  for (const entry of readdirSync(pluginsDir)) {
    if (entry === 'target') continue
    const pluginDir = join(pluginsDir, entry)
    if (!statSync(pluginDir).isDirectory()) continue
    if (!existsSync(join(pluginDir, 'plugin.json'))) continue

    const pluginRuntimeDir = join(pluginDir, 'runtime')
    if (existsSync(pluginRuntimeDir) && statSync(pluginRuntimeDir).isDirectory()) {
      const rels = collectRelativePaths(pluginRuntimeDir)
      for (const rel of rels) {
        pluginManagedPaths.add(rel)
      }
    }

    if (isEnabled(pluginDir)) {
      enabledPlugins.push({ name: entry, dir: pluginDir, runtimeDir: pluginRuntimeDir })
    } else {
      console.log(`[build:plugin-runtime] 插件 ${entry} 已禁用(enabled=false),跳过`)
    }
  }
}

// ---------------------------------------------------------------------------
// 2. 清理 shared runtime/ 下插件管理的文件
// ---------------------------------------------------------------------------
let cleaned = 0
for (const rel of pluginManagedPaths) {
  const target = join(sharedRuntimeDir, rel)
  if (existsSync(target)) {
    rmSync(target, { force: true })
    cleaned++
  }
}
// 清理空目录(插件管理的子目录现在可能空了)
const pluginManagedDirs = new Set()
for (const rel of pluginManagedPaths) {
  const parts = rel.split('/')
  if (parts.length > 1) {
    pluginManagedDirs.add(parts[0])
  }
}
for (const dir of pluginManagedDirs) {
  const target = join(sharedRuntimeDir, dir)
  if (existsSync(target) && readdirSync(target).length === 0) {
    rmSync(target, { recursive: true, force: true })
  }
}
if (cleaned > 0) {
  console.log(`[build:plugin-runtime] 清理了 ${cleaned} 个旧文件`)
}

// ---------------------------------------------------------------------------
// 3. 复制已启用插件的 runtime/ 资源到 shared runtime/
// ---------------------------------------------------------------------------
let copied = 0
for (const { name, runtimeDir } of enabledPlugins) {
  if (!existsSync(runtimeDir) || !statSync(runtimeDir).isDirectory()) {
    continue
  }
  const rels = collectRelativePaths(runtimeDir)
  for (const rel of rels) {
    const src = join(runtimeDir, rel)
    const dst = join(sharedRuntimeDir, rel)
    mkdirSync(dirname(dst), { recursive: true })
    copyFileSync(src, dst)
    copied++
  }
  if (rels.length > 0) {
    console.log(`[build:plugin-runtime] ${name}: 复制 ${rels.length} 个文件`)
  }
}

if (copied === 0) {
  console.log('[build:plugin-runtime] 无插件 runtime 资源需要复制(可能尚未构建)')
} else {
  console.log(`[build:plugin-runtime] 共复制 ${copied} 个文件`)
}
