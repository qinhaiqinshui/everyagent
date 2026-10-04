/**
 * 插件 runtime 资源打包:把已启用插件声明的附属文件复制到仓库根 runtime/<subdir>/。
 *
 * 工作原理:
 *   1. 从 every-agent-plugins/pom.xml 解析所有未被注释的 <module> 名称
 *   2. 遍历下方 PLUGIN_RESOURCES 声明表:
 *      - 插件启用 → 从源目录复制资源文件到 runtime/<subdir>/
 *      - 插件被注释 → 清理 runtime/<subdir>/ 下的旧文件(防止残留打进安装包)
 *   3. electron-builder extraResources(from: ../runtime)会把 runtime/ 原样打进安装包
 *
 * 新增插件资源 = 在 PLUGIN_RESOURCES 数组追加一项,无需 if-else。
 *
 * 用法:npm run build:wsl(由 build:assets 调用)。
 * 源目录不存在时不失败(WARN 跳过)——允许先打包、后补镜像;但分发给用户前
 * 必须执行过对应资源的构建脚本,否则桌面 preflight 会提示「未找到镜像」。
 */
import { copyFileSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { getActivePluginModules } from './plugin-modules.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const desktopRoot = resolve(__dirname, '..')
const repoRoot = resolve(desktopRoot, '..')

// ---------------------------------------------------------------------------
// 插件 runtime 资源声明表
//
// 每个需要往 runtime/ 下复制附属文件的插件在此声明一项。
// 脚本遍历此表:插件启用 → 复制资源,插件被注释 → 清理旧文件。
// ---------------------------------------------------------------------------
const PLUGIN_RESOURCES = [
  {
    /** 插件模块名(对应 every-agent-plugins/pom.xml 中的 <module>) */
    module: 'sandbox-wsl-ubuntu',
    /** 资源目标子目录(最终落到 <resourcesPath>/runtime/wsl/) */
    destSubdir: 'wsl',
    /** 需要复制的文件名列表 */
    files: ['eagent-rootfs.tar.gz', 'eagent-rootfs.tar.gz.sha256'],
    /**
     * 定位资源源目录(优先级:环境变量 > 仓库根 dist/ > 插件自身 wsl/ 目录)。
     * 返回 null 表示源不存在(跳过复制,仅做清理)。
     */
    findSourceDir() {
      if (process.env.EAGENT_ROOTFS_DIR?.trim()) {
        return resolve(process.env.EAGENT_ROOTFS_DIR)
      }
      const distDir = join(repoRoot, 'dist')
      if (existsSync(join(distDir, 'eagent-rootfs.tar.gz'))) return distDir
      const pluginDir = join(repoRoot, 'every-agent-plugins', 'sandbox-wsl-ubuntu', 'wsl')
      if (existsSync(join(pluginDir, 'eagent-rootfs.tar.gz'))) return pluginDir
      return null
    },
  },
]

// ---------------------------------------------------------------------------
// 主逻辑
// ---------------------------------------------------------------------------
const runtimeDir = join(repoRoot, 'runtime')
const activeModules = getActivePluginModules(repoRoot)

for (const { module, destSubdir, files, findSourceDir } of PLUGIN_RESOURCES) {
  const destDir = join(runtimeDir, destSubdir)
  const isActive = activeModules.has(module)

  if (!isActive) {
    console.log(`[build:wsl] 插件 ${module} 已注释,跳过资源打包并清理旧文件`)
    for (const file of files) {
      const f = join(destDir, file)
      if (existsSync(f)) {
        rmSync(f, { force: true })
        console.log(`[build:wsl]   清理: ${f}`)
      }
    }
    continue
  }

  const srcDir = findSourceDir()
  if (!srcDir) {
    console.warn(`[build:wsl] 插件 ${module} 已启用,但未找到资源源目录`)
    console.warn(`[build:wsl]   请先运行 scripts/wsl-rootfs-build.ps1 构建镜像,`)
    console.warn(`[build:wsl]   或设置 EAGENT_ROOTFS_DIR 指向镜像所在目录。`)
    // 即使没有新镜像,也要清理上次构建可能残留的旧文件
    for (const file of files) {
      const f = join(destDir, file)
      if (existsSync(f)) {
        rmSync(f, { force: true })
        console.log(`[build:wsl]   清理旧文件: ${f}`)
      }
    }
    continue
  }

  mkdirSync(destDir, { recursive: true })
  for (const file of files) {
    const src = join(srcDir, file)
    const dst = join(destDir, file)
    if (!existsSync(src)) {
      console.warn(`[build:wsl]   缺少 ${src}(跳过)`)
      continue
    }
    copyFileSync(src, dst)
    console.log(`[build:wsl]   ${src} -> ${dst}`)
  }
}
