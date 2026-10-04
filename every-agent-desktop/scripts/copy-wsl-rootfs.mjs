/**
 * 把托管发行版镜像复制到程序根 runtime/wsl/(程序附属文件统一目录)。
 *
 * 镜像源(优先级):
 *   1. 环境变量 EAGENT_ROOTFS_DIR 指向的目录(含 eagent-rootfs.tar.gz)
 *   2. 仓库根 dist/(wsl-rootfs-build.ps1 默认输出)
 *
 * 产物:<仓库根>/runtime/wsl/{eagent-rootfs.tar.gz, eagent-rootfs.tar.gz.sha256}
 * 随 electron-builder extraResources(from: ../runtime)打进安装包 <resourcesPath>/runtime;
 * 运行时 preflight(wsl-distro.ts)直接用该镜像自动 wsl --import EveryAgent(不再复制到
 * <EVERYAGENT_HOME>/wsl/)。
 *
 * 插件感知:若 sandbox-wsl-ubuntu 插件在 every-agent-plugins/pom.xml 中被注释掉,
 * 则不复制镜像,并清理 runtime/wsl/ 下已有的旧镜像文件——避免 ~42MB rootfs 无谓打进
 * 安装包(electron-builder 会原样复制整个 runtime/ 目录)。
 *
 * 用法:npm run build:wsl(由 build:assets 调用)。
 * 镜像不存在时不失败(WARN 跳过)——允许先打包、后补镜像;但分发给用户前必须
 * 执行过 build:wsl,否则桌面 preflight 会提示「未找到镜像」。
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const desktopRoot = resolve(__dirname, '..')
const repoRoot = resolve(desktopRoot, '..')

const NAME = 'eagent-rootfs'
const DEST_DIR = join(repoRoot, 'runtime', 'wsl')

/**
 * 检查 sandbox-wsl-ubuntu 插件是否在 every-agent-plugins/pom.xml 中被启用(未注释)。
 *
 * 判断依据:pom.xml 中是否存在未被 XML 注释包裹的
 * `<module>sandbox-wsl-ubuntu</module>` 行。
 *
 * @returns true = 插件已启用;false = 插件被注释掉
 */
function isWslUbuntuPluginEnabled() {
  const pluginsPom = join(repoRoot, 'every-agent-plugins', 'pom.xml')
  if (!existsSync(pluginsPom)) {
    return false
  }
  const content = readFileSync(pluginsPom, 'utf8')
  // 匹配未被 <!-- --> 包裹的 <module>sandbox-wsl-ubuntu</module>
  // 策略:先移除所有 XML 注释块,再检查是否包含该 module 声明
  const withoutComments = content.replace(/<!--[\s\S]*?-->/g, '')
  return /<module>\s*sandbox-wsl-ubuntu\s*<\/module>/.test(withoutComments)
}

/**
 * 清理 runtime/wsl/ 下已有的旧镜像文件,防止上次构建残留被打进安装包。
 */
function cleanStaleRootfs() {
  for (const suffix of ['tar.gz', 'tar.gz.sha256']) {
    const file = join(DEST_DIR, `${NAME}.${suffix}`)
    if (existsSync(file)) {
      rmSync(file, { force: true })
      console.log(`[build:wsl] 已清理旧镜像(插件被注释): ${file}`)
    }
  }
}

// ---- 插件感知:sandbox-wsl-ubuntu 被注释时不打包镜像 ----
if (!isWslUbuntuPluginEnabled()) {
  console.log('[build:wsl] sandbox-wsl-ubuntu 插件已注释,跳过镜像打包并清理旧文件')
  cleanStaleRootfs()
  process.exit(0)
}

function sourceDir() {
  if (process.env.EAGENT_ROOTFS_DIR && process.env.EAGENT_ROOTFS_DIR.trim()) {
    return resolve(process.env.EAGENT_ROOTFS_DIR)
  }
  const guess = join(repoRoot, 'dist')
  return existsSync(join(guess, `${NAME}.tar.gz`)) ? guess : null
}

const srcDir = sourceDir()
if (!srcDir) {
  console.warn('[build:wsl] 未找到镜像(dist/eagent-rootfs.tar.gz)。先运行 scripts/wsl-rootfs-build.ps1,')
  console.warn('[build:wsl] 再执行 build:wsl;或设置 EAGENT_ROOTFS_DIR 指向镜像所在目录。')
  // 即使没有新镜像,也要清理上次构建可能残留的旧镜像
  cleanStaleRootfs()
  process.exit(0)
}

mkdirSync(DEST_DIR, { recursive: true })

for (const suffix of ['tar.gz', 'tar.gz.sha256']) {
  const src = join(srcDir, `${NAME}.${suffix}`)
  const dst = join(DEST_DIR, `${NAME}.${suffix}`)
  if (!existsSync(src)) {
    console.warn(`[build:wsl] 缺少 ${src}(跳过)`)
    continue
  }
  copyFileSync(src, dst)
  console.log(`[build:wsl] ${src} -> ${dst}`)
}