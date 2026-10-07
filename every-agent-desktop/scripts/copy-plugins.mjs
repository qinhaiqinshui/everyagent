/**
 * 内置插件产物打包脚本:把 every-agent-plugins/ 下已启用插件的构建产物
 * staging 到 every-agent-desktop/resources/every-agent-plugins/,
 * 供 electron-builder extraResources 搬进安装包的 <resourcesPath>/every-agent-plugins/。
 *
 * 为什么需要这个脚本(对齐 BuiltInPluginScanner 的扫描结构,缺一不可):
 *   - worker 打包态 cwd = resourcesPath,未传 --worker.builtin-plugins-dir,
 *     Scanner 默认扫 <resourcesPath>/every-agent-plugins/;
 *   - Java 插件:<id>/plugin.json(根清单,enabled 判定)+ target/classes/plugin.json
 *     + target/ 下非 sources/javadoc 的 jar(URLClassLoader 加载源);
 *   - web 产物(java+web 与纯 web 插件均适用):<id>/web/ 下的构建产物(index.js/
 *     index.css 等,排除 .ts/.tsx 源码与 .map)——前端经 plugin.webSource RPC 按相对
 *     路径从插件目录读取,jar 内不含 web 产物(pom resources 只拷 plugin.json);
 *   - README.md:插件根的 README 原文件名一并 staging——扩展详情页 README 区经
 *     plugin.webSource("readme.md") 读取(worker 侧同目录大小写不敏感回退),
 *     缺 README 的插件跳过不报错。
 *   - 插件根 bin/ 目录:插件自带可执行文件(如 sandbox-windows-codex/bin/rg.exe)——插件
 *     运行期按 <pluginDir>/bin/<name> 定位附属资源(架构 §7.10 rg 三档解析的第一档),
 *     漏搬会让该资源在安装包里静默缺席,只能靠程序根 runtime/bin 回退兜住。
 *
 * 防残留(硬性要求):staging 目录在脚本开头**整体清空重建**——
 *   - enabled=false 的插件(如 sandbox-*)不复制,上次打包留下的旧 jar 也不会残留;
 *   - 校验失败(fail-fast)时 staging 已清空,任何退出路径都不会把过期产物带进安装包。
 *
 * 防静默降级:按 pom.xml 存在与否判型(与 scripts/build-plugins.py 同口径)。
 * Java 插件若缺 target 产物,staging 只剩根 plugin.json 时 Scanner 会把它误判为
 * 「纯 web 声明式插件」而静默加载——因此 jar/manifest/bundle 任一缺失即报错退出,
 * dist 链中止,绝不带病打包。
 *
 * 用法:
 *   npm run copy:plugins                  # 全量(由 build:assets 调用)
 *   node scripts/copy-plugins.mjs --only pdf-viewer,secret-redaction   # 本地小范围验证
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const desktopRoot = resolve(__dirname, '..')
const repoRoot = resolve(desktopRoot, '..')

const pluginsDir = join(repoRoot, 'every-agent-plugins')
const stagingDir = join(desktopRoot, 'resources', 'every-agent-plugins')

/** 与 BuiltInPluginScanner.findTargetJars 同口径:排除 sources/javadoc。 */
function findTargetJars(pluginDir) {
  const target = join(pluginDir, 'target')
  if (!existsSync(target)) return []
  return readdirSync(target)
    .filter((n) => n.endsWith('.jar') && !n.endsWith('-sources.jar') && !n.endsWith('-javadoc.jar'))
    .sort()
}

/** 读取 plugin.json 的 enabled 字段(缺省/解析失败视为 true,与 Scanner 一致)。 */
function isEnabled(pluginDir) {
  try {
    return JSON.parse(readFileSync(join(pluginDir, 'plugin.json'), 'utf8')).enabled !== false
  } catch {
    return true
  }
}

const only = process.argv.find((a) => a.startsWith('--only='))?.slice('--only='.length).split(',')

// ---------------------------------------------------------------------------
// 0. staging 整体清空重建(防禁用插件/旧版本 jar 残留进安装包)
// ---------------------------------------------------------------------------
if (existsSync(stagingDir)) {
  rmSync(stagingDir, { recursive: true, force: true })
  console.log('[copy:plugins] 已清空 staging 目录(整体重建,防残留)')
}

// ---------------------------------------------------------------------------
// 1. 扫描 + 校验(fail-fast:错误全量列出后统一退出,不带病打包)
// ---------------------------------------------------------------------------
const enabled = []
const errors = []

for (const entry of readdirSync(pluginsDir).sort()) {
  if (entry === 'target') continue
  const pluginDir = join(pluginsDir, entry)
  if (!statSync(pluginDir).isDirectory()) continue
  if (!existsSync(join(pluginDir, 'plugin.json'))) continue
  if (only && !only.includes(entry)) continue

  if (!isEnabled(pluginDir)) {
    console.log(`[copy:plugins] ${entry}: 已禁用(enabled=false),不打包`)
    continue
  }

  const isJava = existsSync(join(pluginDir, 'pom.xml')) // 与 build-plugins.py 同口径
  const hasWeb = existsSync(join(pluginDir, 'web', 'index.ts'))
  const jars = findTargetJars(pluginDir)
  const webDir = join(pluginDir, 'web')
  const binDir = join(pluginDir, 'bin') // 插件自带可执行(rg.exe 等),整体 staging
  const hasBin = existsSync(binDir) && statSync(binDir).isDirectory()

  if (isJava) {
    if (!existsSync(join(pluginDir, 'target', 'classes', 'plugin.json'))) {
      errors.push(`${entry}: Java 插件缺 target/classes/plugin.json —— 请先 npm run build:plugins`)
    }
    if (jars.length === 0) {
      errors.push(`${entry}: Java 插件 target/ 下无非 sources/javadoc 的 jar —— 请先 npm run build:plugins`)
    } else {
      // 与 build-plugins.py own_artifact_jars 同口径:伴生依赖 jar(如 sandbox-windows-codex
      // 由 dependency-plugin 复制进 target/ 的 slf4j-simple)是 findTargetJars 的预期
      // 加载源(Scanner 会连它一起塞进插件 classloader),不算残留,必须一并 staging;
      // 真正的遮蔽风险是插件自身产物出现多个版本(clean 残留旧 jar 会遮蔽新类)。
      const own = jars.filter(
        (n) => n === `${entry}.jar` || (n.startsWith(`${entry}-`) && /^\d/.test(n.slice(entry.length + 1))),
      )
      if (own.length > 1) {
        errors.push(`${entry}: 插件自身产物有 ${own.length} 个 jar(字典序遮蔽风险,请 clean 重建): ${own.join(', ')}`)
      }
    }
  }
  // web bundle 不在 jar 内(pom resources 只拷 plugin.json):前端经 plugin.webSource
  // RPC 从插件目录读 web/index.js / index.css —— java+web 与纯 web 插件一律要求
  // bundle 存在且必须打包,否则安装包里插件 UI 静默缺失。
  if (hasWeb && !existsSync(join(webDir, 'index.js'))) {
    errors.push(`${entry}: 缺 web/index.js(esbuild bundle 未构建) —— 请先 npm run build:plugins`)
  }

  enabled.push({ name: entry, dir: pluginDir, isJava, jars, webDir, hasWeb, binDir, hasBin })
}

if (errors.length > 0) {
  console.error(`[copy:plugins] ✗ ${errors.length} 个插件产物不完整,中止打包(不带病出包):`)
  for (const e of errors) console.error(`  - ${e}`)
  process.exit(1)
}

// ---------------------------------------------------------------------------
// 2. 复制(staging 已是空目录)
// ---------------------------------------------------------------------------
let files = 0
for (const { name, dir, isJava, jars, webDir, hasWeb, binDir, hasBin } of enabled) {
  const dst = join(stagingDir, name)
  const copy = (src, rel) => {
    const to = join(dst, rel)
    mkdirSync(dirname(to), { recursive: true })
    copyFileSync(src, to)
    files++
  }
  // 根 plugin.json:Scanner 的 enabled 判定 + 纯 web 插件的唯一清单
  copy(join(dir, 'plugin.json'), 'plugin.json')
  // 根 README.md:扩展详情页 README 区经 plugin.webSource('readme.md') 读取——
  // worker 侧有同目录大小写不敏感回退,原文件名直接 staging 即可;缺 README 跳过不报错。
  const readme = readdirSync(dir).find((f) => /^readme\.md$/i.test(f) && statSync(join(dir, f)).isFile())
  if (readme) copy(join(dir, readme), readme)
  if (isJava) {
    copy(join(dir, 'target', 'classes', 'plugin.json'), 'target/classes/plugin.json')
    for (const jar of jars) copy(join(dir, 'target', jar), `target/${jar}`)
  }
  // web 产物(index.js/index.css 等):plugin.webSource RPC 按相对路径从插件目录读取,
  // java+web 与纯 web 插件都要搬;排除 .ts/.tsx 源码与 .map(前端运行时不加载)。
  if (hasWeb && existsSync(webDir)) {
    const walk = (d, base) => {
      for (const f of readdirSync(d)) {
        const full = join(d, f)
        const rel = base ? `${base}/${f}` : f
        if (statSync(full).isDirectory()) {
          walk(full, rel)
        } else if (!/\.(ts|tsx|map)$/.test(f)) {
          copy(full, `web/${rel}`)
        }
      }
    }
    walk(webDir, '')
  }
  // 插件根 bin/:插件自带可执行文件(如 sandbox-windows-codex/bin/rg.exe)——运行期按
  // <pluginDir>/bin/<name> 定位(§7.10 rg 三档解析第一档),必须整体搬;二进制不过滤后缀,
  // 也不平铺进共享 runtime/(保持「插件自带资源归插件」的归属口径)。
  if (hasBin && existsSync(binDir)) {
    for (const f of readdirSync(binDir)) {
      const full = join(binDir, f)
      if (statSync(full).isFile()) copy(full, `bin/${f}`)
    }
  }
}

const java = enabled.filter((p) => p.isJava).length
console.log(`[copy:plugins] ✓ staging 完成: ${enabled.length} 个插件(java ${java} / web ${enabled.length - java}),共 ${files} 个文件 → ${stagingDir}`)
