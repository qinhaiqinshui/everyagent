/**
 * WSL 发行版 preflight:desktop 启动后端前检查托管发行版(eagent)是否已导入。
 *
 * 与 worker 侧 OsSandbox.resolveWslDirect → WslBwrapSandbox.autoImport 行为对齐:
 * 发行版在位 → 跳过;发行版缺失 + 镜像在位且 sha256 校验通过 → 自动 `wsl --import eagent`
 * (免管理员、离线);镜像缺失/校验失败 → 仅提示,不阻断桌面启动(worker 探测失败会自行
 * 回退 windows-mic)。
 *
 * 镜像来源:程序根 runtime/wsl/(随安装/解压分发,`<程序根>/runtime/wsl/eagent-rootfs.tar.gz`),
 * 直接使用、不再复制到 `<home>/wsl/`。`wsl --import` 的发行版 rootfs 仍落 `<home>/wsl/distro/`。
 *
 * 说明:本模块只做「提前、可视化」的检查与自动导入,幂等——若 worker 后续探测时
 * 再次 autoImport 会因为发行版已存在而跳过,不产生重复导入。
 */
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

export interface WslDistroCheck {
  /** wsl.exe 是否可调用(WSL 平台是否安装且调用方非降权)。 */
  wslAvailable: boolean
  /** 目标发行版是否已在位。 */
  distroPresent: boolean
  /** 本次是否执行了自动导入。 */
  importedNow: boolean
  /** 面向用户/日志的单行结论。 */
  detail: string
}

type StatusFn = (message: string) => void

const MANAGED_DISTRO = 'eagent'
const TARBALL_NAME = 'eagent-rootfs.tar.gz'

/**
 * 执行 wsl.exe 命令,返回 { ok, stdout, stderr, code, error }。
 *
 * 用 spawn(而非 execFile)以获得完整事件(close/error)与可控超时;
 * windowsHide:true 避免 Electron 主进程 spawn 控制台程序时弹黑窗/潜在崩溃。
 * 每次调用都打日志,便于 desktop.log 排查 wsl 交互每一步。
 *
 * @param onLog 过程日志回调(传 log 时逐事件输出;传 null 则静默,供内部探测复用)
 */
function runWsl(
  args: string[],
  timeoutMs = 120_000,
  onLog?: (msg: string) => void,
): Promise<{ ok: boolean; stdout: string; stderr: string; code: number | null; error: string }> {
  return new Promise((resolve) => {
    let done = false
    const finish = (r: {
      ok: boolean
      stdout: string
      stderr: string
      code: number | null
      error: string
    }): void => {
      if (done) return
      done = true
      if (timer) clearTimeout(timer)
      resolve(r)
    }
    let timer: ReturnType<typeof setTimeout> | null = null
    try {
      onLog?.(`spawn wsl.exe ${args.join(' ')} (timeout=${timeoutMs}ms)`)
      const child = spawn('wsl.exe', args, {
        windowsHide: true,
        env: { ...process.env, WSL_UTF8: '1' },
      })
      let stdout = ''
      let stderr = ''
      child.stdout?.on('data', (d: Buffer) => {
        stdout += d.toString('utf8')
        if (stdout.length > 4 * 1024 * 1024) {
          stdout = stdout.slice(0, 4 * 1024 * 1024)
          child.kill()
        }
      })
      child.stderr?.on('data', (d: Buffer) => {
        stderr += d.toString('utf8')
      })
      child.on('error', (err) => {
        const code = (err as NodeJS.ErrnoException).code ?? ''
        onLog?.(`wsl.exe error event: ${code} ${err.message}`)
        finish({ ok: false, stdout, stderr, code: null, error: err.message })
      })
      child.on('close', (code) => {
        onLog?.(`wsl.exe close event: code=${code} stderr=${stderr.slice(0, 200)}`)
        finish({ ok: code === 0, stdout, stderr, code, error: '' })
      })
      timer = setTimeout(() => {
        onLog?.(`wsl.exe timeout(${timeoutMs}ms), kill`)
        try {
          child.kill()
        } catch {
          /* ignore */
        }
        finish({ ok: false, stdout, stderr, code: null, error: 'timeout' })
      }, timeoutMs)
    } catch (error) {
      onLog?.(`wsl.exe spawn 同步异常: ${(error as Error).message}`)
      finish({ ok: false, stdout: '', stderr: '', code: null, error: (error as Error).message })
    }
  })
}

/** 解析 `wsl.exe -l -q` 输出为发行版名列表(每行一个,剥 NUL/CR/空白)。 */
function parseDistroList(raw: string): string[] {
  return raw
    .replace(/\u0000/g, '')
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

/** 计算文件 sha256 十六进制摘要(小写);文件不存在/读失败 → null。 */
function sha256(file: string): string | null {
  try {
    const data = readFileSync(file)
    return createHash('sha256').update(data).digest('hex').toLowerCase()
  } catch {
    return null
  }
}

/** 读取 `<镜像>.sha256` 的期望摘要(取首个空白分隔 token,64 位十六进制);失败 → null。 */
function expectedSha256(tarball: string): string | null {
  try {
    const text = readFileSync(tarball + '.sha256', 'utf8').trim()
    const hex = text.split(/\s+/)[0].toLowerCase()
    return /^[0-9a-f]{64}$/.test(hex) ? hex : null
  } catch {
    return null
  }
}

/**
 * 检查并(必要时)自动导入托管发行版。
 *
 * @param home     EVERYAGENT_HOME(发行版 rootfs 最终落在 <home>/wsl/distro/)
 * @param distro   目标发行版名(默认 eagent)
 * @param onStatus 进度回调(透传到桌面启动页)
 * @param bundledDir 程序根 runtime/(由 paths.runtimeDir() 提供);镜像实际在 <bundledDir>/wsl/。
 *                   为空则跳过打包资源。
 */
export async function ensureWslDistro(
  home: string,
  distro: string = MANAGED_DISTRO,
  onStatus?: StatusFn,
  bundledDir?: string,
): Promise<WslDistroCheck> {
  const log = (m: string): void => {
    console.info('[desktop] ' + m)
    onStatus?.(m)
  }
  log(`ensureWslDistro 开始: home=${home} distro=${distro} bundledDir=${bundledDir ?? '(无)'}`)

  // 整体兜底:任何异常/卡顿都不让本函数 reject(调用方 await 会卡死启动流程)
  try {
    // 1) wsl 平台可用性
    const ver = await runWsl(['--version'], 15_000, log)
    if (!ver.ok) {
      const detail = `wsl.exe 不可用(WSL 未安装或调用方受限),发行版检查跳过: ${ver.error} ${ver.stderr.trim().slice(0, 120)}`
      log(detail)
      return { wslAvailable: false, distroPresent: false, importedNow: false, detail }
    }
    log(`wsl.exe --version 输出: ${ver.stdout.trim().slice(0, 120)}`)

    // 2) 发行版是否已在位
    const list = await runWsl(['-l', '-q'], 15_000, log)
    const installed = list.ok ? parseDistroList(list.stdout) : []
    if (!list.ok) {
      log(
        `wsl -l -q 失败,继续按「未安装 ${distro}」处理: ${(list.error || list.stderr || list.stdout)
          .trim()
          .slice(0, 160)}`,
      )
    }
    log(`wsl -l -q 已装发行版: [${installed.join(', ')}]`)
    if (installed.includes(distro)) {
      const detail = `托管发行版 ${distro} 已在位`
      log(detail)
      return { wslAvailable: true, distroPresent: true, importedNow: false, detail }
    }

    // 3) 镜像来源:程序根 runtime/wsl/(随安装/解压分发),直接使用、不再复制到 <home>/wsl
    const tarball = bundledDir ? join(bundledDir, 'wsl', TARBALL_NAME) : ''

    if (!tarball || !existsSync(tarball)) {
      const detail = `托管发行版 ${distro} 缺失,且未找到镜像(已检查程序根 ${tarball || '(未传入)'}),请先运行 scripts/wsl-rootfs-build.ps1 构建镜像`
      log(detail)
      return { wslAvailable: true, distroPresent: false, importedNow: false, detail }
    }

    const expect = expectedSha256(tarball)
    const actual = sha256(tarball)
    if (!expect || !actual || expect !== actual) {
      const detail = `镜像 ${tarball} 的 sha256 校验失败(缺 .sha256 伴生文件或文件损坏),放弃自动导入`
      log(detail)
      return { wslAvailable: true, distroPresent: false, importedNow: false, detail }
    }

    const installDir = join(home, 'wsl', 'distro')
    // wsl.exe --import 不会递归创建 InstallLocation 的父目录;父目录缺失时报
    // ERROR_PATH_NOT_FOUND(系统找不到指定的路径)。这里先递归建目录。
    try {
      mkdirSync(installDir, { recursive: true })
    } catch (error) {
      const detail = `创建发行版安装目录失败 ${installDir}: ${(error as Error).message}`
      log(detail)
      return { wslAvailable: true, distroPresent: false, importedNow: false, detail }
    }
    log(`托管发行版 ${distro} 缺失,自动导入: ${tarball}(解包约 1~5 分钟)...`)
    const imp = await runWsl(
      ['--import', distro, installDir, tarball, '--version', '2'],
      300_000,
      log,
    )
    if (!imp.ok) {
      const errText = (imp.error || imp.stderr || imp.stdout).trim().slice(0, 200)
      const hint = /E_ACCESSDENIED|拒绝访问|Access is denied/i.test(errText)
        ? '(WSL 拒绝访问:通常是应用以受限/降权或提权身份运行所致,请以普通用户双击启动应用)'
        : ''
      const detail = `wsl --import ${distro} 失败: ${errText}${hint}`
      log(detail)
      return { wslAvailable: true, distroPresent: false, importedNow: false, detail }
    }

    const detail = `托管发行版 ${distro} 自动导入完成`
    log(detail)
    return { wslAvailable: true, distroPresent: true, importedNow: true, detail }
  } catch (error) {
    const detail = `ensureWslDistro 异常(发行版检查跳过): ${(error as Error).message}`
    log(detail)
    return { wslAvailable: false, distroPresent: false, importedNow: false, detail }
  }
}
