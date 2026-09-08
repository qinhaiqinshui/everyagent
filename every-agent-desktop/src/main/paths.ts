/**
 * 资源路径解析:区分「开发态(electron .)」与「打包态(extraResources)」。
 * 打包后 resources/web、resources/backend、resources/jre 会被 electron-builder 原样搬到
 * process.resourcesPath 下;仓库根 runtime/(程序附属文件唯一真源)经 extraResources
 * (from: ../runtime)搬到 <resourcesPath>/runtime —— 与 web/backend/jre 同层。
 */
import { app } from 'electron'
import { join, resolve } from 'node:path'

/**
 * 程序根目录(内含 runtime/ 程序附属文件;worker/hub 的 cwd 也设为此)。
 * 打包态 = resourcesPath(extraResources 把 runtime 原样放到 <resourcesPath>/runtime);
 * 开发态 = 仓库根(every-agent-desktop 的上一级)。
 * 程序附属文件(rg、eagent-run.py、WSL 镜像)随安装包分发到 <程序根>/runtime/,worker 以
 * 字面相对路径 ./runtime 按 user.dir 解析,故 spawn 时把 cwd 设为本目录。
 */
export function programRoot(): string {
  if (app.isPackaged) {
    return process.resourcesPath
  }
  // 开发态:app.getAppPath() = every-agent-desktop,程序根 = 仓库根(其上一级)
  return resolve(app.getAppPath(), '..')
}

/** 打包资源根目录(内含 web/、backend/、jre/)。 */
export function resourcesRoot(): string {
  if (app.isPackaged) {
    return process.resourcesPath
  }
  // 开发态:every-agent-desktop/resources
  return join(app.getAppPath(), 'resources')
}

export function webRoot(): string {
  return join(resourcesRoot(), 'web')
}

export function backendDir(): string {
  return join(resourcesRoot(), 'backend')
}

export function hubJar(): string {
  return join(backendDir(), 'hub.jar')
}

export function workerJar(): string {
  return join(backendDir(), 'worker.jar')
}

/** 精简 JRE 根(内含 bin/java.exe、bin/javaw.exe)。 */
export function jreDir(): string {
  return join(resourcesRoot(), 'jre')
}

export function jreJavaExe(): string {
  return join(jreDir(), 'bin', 'javaw.exe')
}

export function jreJavaExeFallback(): string {
  return join(jreDir(), 'bin', 'java.exe')
}

/** 程序附属文件目录(rg 二进制、eagent-run.py、托管发行版镜像;随安装/解压分发、运行时只读引用)。 */
export function runtimeDir(): string {
  return join(programRoot(), 'runtime')
}

/** ripgrep 二进制(Windows)。 */
export function runtimeRgExe(): string {
  return join(runtimeDir(), 'bin', 'rg.exe')
}

/** WSL 发行版启动器脚本。 */
export function runtimeRunnerPy(): string {
  return join(runtimeDir(), 'wsl', 'eagent-run.py')
}

/** 打包随附的托管发行版镜像 tar.gz 路径。 */
export function runtimeRootfs(): string {
  return join(runtimeDir(), 'wsl', 'eagent-rootfs.tar.gz')
}

/** 打包随附的托管发行版镜像 sha256 伴生文件路径。 */
export function runtimeRootfsSha256(): string {
  return join(runtimeDir(), 'wsl', 'eagent-rootfs.tar.gz.sha256')
}