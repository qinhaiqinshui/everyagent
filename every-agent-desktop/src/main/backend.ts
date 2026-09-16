/**
 * 后端子进程编排:用 jlink 精简 JRE 的 javaw.exe 启动本地 hub 与 worker。
 * hub 始终跟随 desktop 启停(child.kill);worker 支持外部进程复用(认证探测 +
 * HTTP shutdown 优雅关闭),可脱离 desktop 经 start-backend.bat 独立启动。
 */
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdirSync, openSync } from 'node:fs'
import { join } from 'node:path'
import { app, utilityProcess } from 'electron'
import type { DesktopConfig, DesktopPaths } from './config'
import { runtimeDir, programRoot, hubJar, jreJavaExe, jreJavaExeFallback, workerJar } from './paths'

export interface BackendHandles {
  /** hub 子进程(始终由 desktop 启动,退出时一并停止)。 */
  hub: ChildProcess
  /**
   * worker 子进程;null 表示 worker 为外部进程(desktop 未启动它)。
   * 外部进程典型来源:任务计划程序经 start-backend.bat 启动。
   */
  worker: ChildProcess | null
  /**
   * worker /health 上报的实际 workerId(可能不同于 desktop-config.json 配置值:
   * worker 侧 application-worker.yaml 或 WORKER_ID 环境变量可覆盖默认)。
   * 前端建连/发 RPC 必须使用这个真实值,否则 cmd 频道名对不上,RPC 被 hub 丢弃。
   */
  workerId: string
  /**
   * 全部停止:hub 走 child.kill;worker(含外部进程)走 POST /admin/shutdown 优雅关闭。
   * desktop 自己启动的 worker:HTTP shutdown 后等 child exit,超时 child.kill 兜底。
   * 外部 worker:HTTP shutdown 后轮询 /health 等待退出,超时只记日志。
   */
  stopAll: () => Promise<void>
  /**
   * 仅停 hub(child.kill):用于「退出桌面」——hub 始终跟随 desktop,worker 保留运行。
   */
  stopHub: () => Promise<void>
}

type StatusFn = (message: string) => void

/** 解析可用的 java 可执行文件:优先 jlink 精简 JRE 的 javaw.exe,回退 java.exe/系统 java。 */
function resolveJavaExe(): { exe: string; windowsHide: boolean } {
  if (existsSync(jreJavaExe())) return { exe: jreJavaExe(), windowsHide: false }
  if (existsSync(jreJavaExeFallback())) return { exe: jreJavaExeFallback(), windowsHide: true }
  if (process.platform === 'win32') return { exe: 'java', windowsHide: true }
  return { exe: 'java', windowsHide: false }
}

type IdentifyResult =
  /** 端口无监听(连接被拒/超时),可安全启动 */
  | { status: 'port-free' }
  /** 端口被本 worker 占用,可复用 */
  | { status: 'ours' }
  /** 端口已被别的程序占用(认证失败/路径不存在/服务标识不匹配) */
  | { status: 'port-occupied'; detail: string }

/**
 * worker 认证探测:GET /admin/identify,携带 X-Admin-Key(workerApiKey)请求头。
 * 区分三种状态:
 * - port-free:连接被拒/超时 → 端口空闲,可启动;
 * - ours:认证通过且 service=worker → 本 worker,可复用;
 * - port-occupied:收到 HTTP 响应但不匹配(401/404/200 但 service 不对)→ 端口被占,报错。
 */
async function identifyWorker(
  port: number,
  adminKey: string,
  timeoutMs: number,
): Promise<IdentifyResult> {
  const url = `http://127.0.0.1:${port}/admin/identify`
  try {
    const res = await fetch(url, {
      headers: { 'X-Admin-Key': adminKey },
      signal: AbortSignal.timeout(timeoutMs),
    })
    if (res.ok) {
      const body = (await res.json().catch(() => null)) as { service?: unknown } | null
      if (body && typeof body.service === 'string' && body.service === 'worker') {
        return { status: 'ours' }
      }
      const got = body?.service ?? 'unknown'
      return { status: 'port-occupied', detail: `service=${got}(期望 worker)` }
    }
    return { status: 'port-occupied', detail: `HTTP ${res.status}` }
  } catch {
    // 连接被拒/超时 → 端口空闲,可安全启动
    return { status: 'port-free' }
  }
}

/**
 * 简单端口探测:向 /health 发 GET 请求,判断端口是否有程序在监听。
 * 返回 true = 端口可达(有程序在跑),false = 端口空闲。
 * 用于 hub:hub 始终跟随 desktop,只需判断端口是否被占(被占则报错)。
 */
async function probePortOccupied(port: number, timeoutMs: number): Promise<boolean> {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(timeoutMs) })
    return res.ok
  } catch {
    return false
  }
}

async function waitHttp(
  url: string,
  timeoutMs: number,
  label: string,
  child?: ChildProcess,
): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    // 子进程已退出则立即失败,避免"进程崩了还干等超时"。
    if (child && child.exitCode !== null) {
      throw new Error(
        `${label} 进程提前退出(code=${child.exitCode}, signal=${child.signalCode ?? 'null'}):${url}`,
      )
    }
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(1500) })
      if (res.ok) return
    } catch {
      /* 未就绪,继续等 */
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  throw new Error(`${label} 启动超时(${timeoutMs}ms):${url}`)
}

/**
 * 等待 worker 真正就绪:HTTP 200 之外,还要求 /health 响应体 hubConnected=true。
 * worker 的 Spring 端口先于 hub 连接就绪——若只等 HTTP 200,前端可能在 worker 尚未
 * 订阅 cmd 频道时发出 tasks.list 等 RPC,导致首屏任务列表为空(须手动重连才恢复)。
 *
 * 返回 worker /health 上报的实际 workerId:worker 的 worker-id 可能经
 * application-worker.yaml / WORKER_ID 覆盖为与 desktop-config.json 不同的值,
 * 前端必须用这个真实值构造 cmd 频道(否则 tasks.list 等 RPC 发到无人订阅的频道被丢弃)。
 */
async function waitWorkerReady(
  url: string,
  timeoutMs: number,
  label: string,
  child?: ChildProcess,
): Promise<string> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    // 子进程已退出则立即失败,避免"进程崩了还干等超时"。
    if (child && child.exitCode !== null) {
      throw new Error(
        `${label} 进程提前退出(code=${child.exitCode}, signal=${child.signalCode ?? 'null'}):${url}`,
      )
    }
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(1500) })
      if (res.ok) {
        const body = (await res.json().catch(() => null)) as {
          hubConnected?: unknown
          workerId?: unknown
        } | null
        if (body && body.hubConnected === true) {
          const workerId = typeof body.workerId === 'string' && body.workerId.trim()
            ? body.workerId.trim()
            : ''
          return workerId
        }
      }
    } catch {
      /* 未就绪,继续等 */
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  throw new Error(`${label} 就绪超时(${timeoutMs}ms):${url}(hubConnected 未变 true)`)
}

function spawnJava(
  label: string,
  jar: string,
  args: string[],
  logPath: string,
  env: NodeJS.ProcessEnv,
  cwd: string,
): ChildProcess {
  const { exe, windowsHide } = resolveJavaExe()
  console.info(`[desktop] ${label} 进程启动: ${exe} -jar ${jar} ${args.join(' ')} (cwd=${cwd})`)
  console.info(`[desktop] ${label} stdout/stderr -> ${logPath}`)
  const outFd = openSync(logPath, 'a')
  // stdio:忽略 stdin,stdout/stderr 写入同一日志文件(主进程不持有其内容,文件即真相源)。
  // cwd 设为程序根:worker 以字面相对路径 ./runtime 按 user.dir 定位程序附属文件。
  const child = spawn(exe, ['-jar', jar, ...args], {
    windowsHide,
    env,
    cwd,
    stdio: ['ignore', outFd, outFd],
  })
  child.on('error', (err) => {
    console.error(`[desktop] ${label} 进程启动失败:`, err.message)
  })
  child.on('spawn', () => {
    console.info(`[desktop] ${label} 进程已创建 pid=${child.pid}`)
  })
  child.on('exit', (code, signal) => {
    console.info(`[desktop] ${label} 进程退出 code=${code} signal=${signal}`)
  })
  return child
}

/**
 * 向 /admin/shutdown 发送 POST 请求,触发 Spring Boot 优雅关闭。
 * 用于外部进程(desktop 无 child 句柄):发送后轮询 /health 等待端口变为不可达,最多等 waitMs。
 */
async function shutdownViaHttp(
  port: number,
  adminKey: string,
  label: string,
  log: StatusFn,
  waitMs: number,
): Promise<void> {
  const url = `http://127.0.0.1:${port}/admin/shutdown`
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'X-Admin-Key': adminKey },
      signal: AbortSignal.timeout(5000),
    })
    if (res.ok) {
      log(`外部 ${label}:已发送 shutdown 指令,等待进程退出...`)
      const deadline = Date.now() + waitMs
      while (Date.now() < deadline) {
        try {
          await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(1000) })
          // 端口仍可达,继续等
        } catch {
          log(`外部 ${label}:进程已退出`)
          return
        }
        await new Promise((r) => setTimeout(r, 300))
      }
      log(`外部 ${label}:shutdown 已发送但进程未在 ${waitMs}ms 内退出`)
    } else if (res.status === 401) {
      log(`外部 ${label}:shutdown 认证失败(密钥不匹配),无法停止`)
    } else {
      log(`外部 ${label}:shutdown 返回 HTTP ${res.status}`)
    }
  } catch (error) {
    log(`外部 ${label}:shutdown 请求失败(可能已停止): ${(error as Error).message}`)
  }
}

/**
 * 对 desktop 自己启动的子进程:发送 HTTP shutdown 后等待 child 自然退出(不轮询 /health——
 * Spring 优雅关闭期间 /health 可能持续 200 直到 context 完全关闭,轮询会白等)。
 * 超时后 child.kill('SIGKILL') 兜底。
 */
async function shutdownChild(
  child: ChildProcess,
  port: number,
  adminKey: string,
  label: string,
  log: StatusFn,
  waitMs: number,
): Promise<void> {
  // 已退出则跳过
  if (child.exitCode !== null || child.killed) {
    log(`${label}:进程已退出`)
    return
  }
  // 发送 HTTP shutdown
  try {
    const res = await fetch(`http://127.0.0.1:${port}/admin/shutdown`, {
      method: 'POST',
      headers: { 'X-Admin-Key': adminKey },
      signal: AbortSignal.timeout(5000),
    })
    if (res.ok) {
      log(`${label}:已发送 shutdown 指令,等待进程优雅退出...`)
    } else if (res.status === 401) {
      log(`${label}:shutdown 认证失败,直接 kill`)
    } else {
      log(`${label}:shutdown 返回 HTTP ${res.status},直接 kill`)
    }
  } catch (error) {
    log(`${label}:shutdown 请求失败: ${(error as Error).message},直接 kill`)
  }
  // 等待 child 自然退出,超时强杀
  await new Promise<void>((resolve) => {
    if (child.exitCode !== null || child.killed) return resolve()
    const timer = setTimeout(() => {
      try { child.kill('SIGKILL') } catch { /* ignore */ }
    }, waitMs)
    child.once('exit', () => { clearTimeout(timer); resolve() })
  })
  log(`${label}:进程已退出`)
}

export async function startBackend(
  cfg: DesktopConfig,
  paths: DesktopPaths,
  onStatus?: StatusFn,
): Promise<BackendHandles> {
  const log = (message: string): void => {
    console.info(`[desktop] ${message}`)
    onStatus?.(message)
  }

  const hubJarPath = hubJar()
  const workerJarPath = workerJar()
  log(
    `检查后端 jar: hub=${hubJarPath} (exists=${existsSync(hubJarPath)}), worker=${workerJarPath} (exists=${existsSync(workerJarPath)})`,
  )
  if (!existsSync(hubJarPath)) {
    throw new Error(
      app.isPackaged
        ? `未找到 hub.jar: ${hubJarPath}`
        : '未找到 resources/backend/hub.jar,请先运行 npm run build:backend',
    )
  }
  if (!existsSync(workerJarPath)) {
    throw new Error(
      app.isPackaged
        ? `未找到 worker.jar: ${workerJarPath}`
        : '未找到 resources/backend/worker.jar,请先运行 npm run build:backend',
    )
  }

  log(`创建日志目录: ${paths.logsDir}`)
  mkdirSync(paths.logsDir, { recursive: true })

  // 发行版 preflight:worker 需要 wsl-direct 的托管发行版(EveryAgent)。
  // 在独立 utilityProcess 中执行(主进程不直接 spawn wsl.exe,规避 native 崩溃风险);
  // fire-and-forget:任何异常都不影响 hub/worker 启动(worker 自身 autoImport 兜底)。
  const wslCheck = utilityProcess.fork(join(__dirname, 'wsl-check-entry.js'), [], {
    serviceName: 'wsl-distro-check',
  })
  wslCheck.on('message', (msg) => {
    const m = msg as { type?: string; line?: string; result?: { detail?: string } }
    if (m.type === 'log' && m.line) log(`[wsl检查] ${m.line}`)
    else if (m.type === 'result') log(`[wsl检查] ${m.result?.detail ?? '完成'}`)
  })
  wslCheck.on('exit', (code) => {
    log(`[wsl检查] 子进程退出 code=${code}`)
  })
  try {
    wslCheck.postMessage({
      home: paths.home,
      distro: 'EveryAgent',
      bundledDir: runtimeDir(),
    })
  } catch (error) {
    log(`[wsl检查] 下发任务失败(忽略): ${(error as Error).message}`)
  }

  const env: NodeJS.ProcessEnv = { ...process.env, EVERYAGENT_HOME: paths.home }
  const hubLog = join(paths.logsDir, 'hub.out.log')
  const workerLog = join(paths.logsDir, 'worker.out.log')
  const hubHealthUrl = `http://127.0.0.1:${cfg.hubPort}/health`
  const workerHealthUrl = `http://127.0.0.1:${cfg.workerPort}/health`

  // ------------------------------------------------------------------
  // hub:始终跟随 desktop 启停。只检查端口是否被占(被占则报错)。
  // ------------------------------------------------------------------
  if (await probePortOccupied(cfg.hubPort, 2000)) {
    throw new Error(
      `端口 ${cfg.hubPort} 已被占用(hub 应由 desktop 独占管理),` +
        `请在 desktop-config.json 中修改 hubPort,或手动释放端口后重试`,
    )
  }
  log(`启动 hub (${hubJarPath}) ...`)
  const hub = spawnJava('hub', hubJarPath, [], hubLog, env, programRoot())
  log(`等待 hub 健康检查 ${hubHealthUrl} (30s)...`)
  await waitHttp(hubHealthUrl, 30000, 'hub', hub)
  log('hub 健康检查通过')

  // ------------------------------------------------------------------
  // worker 认证探测:GET /admin/identify 携带 workerApiKey 认证。
  // 识别成功 = 本 worker,复用;连接被拒 = 启动;端口被占 = 报错提示用户。
  // ------------------------------------------------------------------
  let worker: ChildProcess | null = null
  let actualWorkerId = ''
  const workerResult = await identifyWorker(cfg.workerPort, cfg.workerApiKey, 2000)
  if (workerResult.status === 'ours') {
    log('检测到 worker 已在运行(外部进程),等待 hub 连接就绪...')
    try {
      actualWorkerId = await waitWorkerReady(workerHealthUrl, 30000, 'worker')
      log(
        actualWorkerId
          ? `worker 就绪(外部,hub 连接已建立,workerId=${actualWorkerId})`
          : 'worker 就绪(外部,hub 连接已建立,未读到 workerId)',
      )
    } catch {
      // worker 端口在但 hubConnected 一直 false:可能是 worker 刚启动还在连 hub,
      // 或 hub 连接断开。无论哪种都不能再 spawn(端口已占)。尝试读 workerId 后复用。
      log('警告:外部 worker 未在 30s 内建立 hub 连接,仍标记为外部进程(不启动新实例)')
      try {
        const res = await fetch(workerHealthUrl, { signal: AbortSignal.timeout(2000) })
        if (res.ok) {
          const body = (await res.json().catch(() => null)) as { workerId?: unknown } | null
          if (body && typeof body.workerId === 'string') actualWorkerId = body.workerId.trim()
        }
      } catch { /* ignore */ }
    }
  } else if (workerResult.status === 'port-occupied') {
    throw new Error(
      `端口 ${cfg.workerPort} 已被其他程序占用(${workerResult.detail}),` +
        `请在 desktop-config.json 中修改 workerPort,或手动释放端口后重试`,
    )
  } else {
    log(`启动 worker (${workerJarPath}) ...`)
    // 程序附属文件(rg、eagent-run.py、镜像)随安装包分发到 <程序根>/runtime;
    // worker 以字面相对路径 ./runtime 按 user.dir 定位,故这里把 cwd 设为程序根。
    worker = spawnJava('worker', workerJarPath, [], workerLog, env, programRoot())
    // 启动顺序关键:必须等到 worker 就绪(健康检查通过且 hub 连接建立)再加载前端,
    // 否则前端首屏 tasks.list 落在 worker 尚未订阅 cmd 频道的窗口,任务列表恒为空,
    // 只能去设置页手动「保存并连接」重建连接后才恢复。
    log(`等待 worker 就绪(健康检查 + hub 连接) ${workerHealthUrl} (120s)...`)
    actualWorkerId = await waitWorkerReady(workerHealthUrl, 120000, 'worker', worker)
    log(
      actualWorkerId
        ? `worker 就绪(hub 连接已建立,workerId=${actualWorkerId})`
        : 'worker 就绪(hub 连接已建立,未读到 workerId)',
    )
  }

  // 全部停止:hub 走 child.kill(desktop 独占管理,始终由 desktop 启动);
  // worker(含外部进程)走 POST /admin/shutdown 优雅关闭。
  // desktop 自己启动的 worker:HTTP shutdown 后等 child 自然退出,超时 child.kill 兜底。
  // 外部 worker:HTTP shutdown 后轮询 /health 等待退出,超时只记日志。
  const stopAll = async (): Promise<void> => {
    // 先停 worker 再停 hub(worker 先断开 hub 连接)
    if (worker) {
      log('停止 worker(HTTP shutdown + 兜底 kill)...')
      await shutdownChild(worker, cfg.workerPort, cfg.workerApiKey, 'worker', log, 8000)
    } else {
      log('停止外部 worker(HTTP shutdown)...')
      await shutdownViaHttp(cfg.workerPort, cfg.workerApiKey, 'worker', log, 8000)
    }
    // hub 始终由 desktop 启动,直接 child.kill(Windows SIGTERM 即硬杀;hub 无状态,硬杀零损失)
    if (hub) {
      log('停止 hub...')
      await new Promise<void>((resolve) => {
        if (hub.exitCode !== null || hub.killed) return resolve()
        const timer = setTimeout(() => {
          try { hub.kill('SIGKILL') } catch { /* ignore */ }
        }, 5000)
        hub.once('exit', () => { clearTimeout(timer); resolve() })
        try { hub.kill('SIGTERM') } catch { /* ignore */ }
      })
    }
  }
  const stopHub = async (): Promise<void> => {
    if (hub) {
      log('停止 hub(退出桌面,worker 保留)...')
      await new Promise<void>((resolve) => {
        if (hub.exitCode !== null || hub.killed) return resolve()
        const timer = setTimeout(() => {
          try { hub.kill('SIGKILL') } catch { /* ignore */ }
        }, 5000)
        hub.once('exit', () => { clearTimeout(timer); resolve() })
        try { hub.kill('SIGTERM') } catch { /* ignore */ }
      })
    }
  }
  return { hub, worker, workerId: actualWorkerId, stopAll, stopHub }
}
