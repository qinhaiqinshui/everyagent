/**
 * 后端子进程编排:用 jlink 精简 JRE 的 javaw.exe 启动本地 hub 与 worker。
 * 不再生成任何 yml——hub/worker 进程配置由 jar 内 application.yml 默认 +
 * ~/.everyagent/application-*.yaml 用户覆盖决定(optional:file 自动加载),
 * desktop 只负责拉起进程、注入 EVERYAGENT_HOME,并把 cwd 设为程序根
 * (worker 以字面相对路径 ./runtime 定位程序附属文件,见 paths.programRoot)。
 *
 * 外部进程复用:启动前调 /admin/identify(认证探测)判断 hub/worker 是否已在运行——
 * 已在运行则跳过启动,退出时只停 desktop 自己启动的进程(「退出桌面」)或全部 HTTP shutdown(「全部退出」)。
 */
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdirSync, openSync } from 'node:fs'
import { join } from 'node:path'
import { app, utilityProcess } from 'electron'
import type { DesktopConfig, DesktopPaths } from './config'
import { runtimeDir, programRoot, hubJar, jreJavaExe, jreJavaExeFallback, workerJar } from './paths'

export interface BackendHandles {
  /**
   * hub 子进程;null 表示 hub 为外部进程(desktop 未启动它,退出时不停止)。
   * 外部进程典型来源:任务计划程序经 start-backend.bat 启动。
   */
  hub: ChildProcess | null
  /**
   * worker 子进程;null 表示 worker 为外部进程(desktop 未启动它,退出时不停止)。
   */
  worker: ChildProcess | null
  /**
   * worker /health 上报的实际 workerId(可能不同于 desktop-config.json 配置值:
   * worker 侧 application-worker.yaml 或 WORKER_ID 环境变量可覆盖默认)。
   * 前端建连/发 RPC 必须使用这个真实值,否则 cmd 频道名对不上,RPC 被 hub 丢弃。
   */
  workerId: string
  /** 优雅停止:先 worker 后 hub,超时强杀;外部进程(null)跳过。 */
  stop: () => Promise<void>
  /**
   * 全部停止:对所有 hub/worker(含外部进程)发送 POST /admin/shutdown,触发 Spring 优雅关闭。
   * desktop 自己启动的进程:HTTP shutdown 超时后 child.kill() 兜底。
   * 外部进程:HTTP shutdown 超时只记日志(不按端口强杀)。
   */
  stopAll: () => Promise<void>
}

type StatusFn = (message: string) => void

/** 解析可用的 java 可执行文件:优先 jlink 精简 JRE 的 javaw.exe,回退 java.exe/系统 java。 */
function resolveJavaExe(): { exe: string; windowsHide: boolean } {
  if (existsSync(jreJavaExe())) return { exe: jreJavaExe(), windowsHide: false }
  if (existsSync(jreJavaExeFallback())) return { exe: jreJavaExeFallback(), windowsHide: true }
  if (process.platform === 'win32') return { exe: 'java', windowsHide: true }
  return { exe: 'java', windowsHide: false }
}

/**
 * 认证探测:GET /admin/identify,携带 X-Admin-Key 请求头。
 * 返回 'hub' / 'worker' 表示识别到本服务;null 表示端口无响应、认证失败或非本服务。
 */
async function identifyService(
  port: number,
  adminKey: string,
  timeoutMs: number,
): Promise<string | null> {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/admin/identify`, {
      headers: { 'X-Admin-Key': adminKey },
      signal: AbortSignal.timeout(timeoutMs),
    })
    if (res.ok) {
      const body = (await res.json().catch(() => null)) as { service?: unknown } | null
      if (body && typeof body.service === 'string') return body.service
    }
    return null
  } catch {
    return null
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
 * 返回后等待端口变为不可达(进程退出),最多等 waitMs。
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
      // 等待端口变为不可达
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
    // 连接被拒说明进程已经不在了
    log(`外部 ${label}:shutdown 请求失败(可能已停止): ${(error as Error).message}`)
  }
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
  // 认证探测已有 hub:GET /admin/identify 携带 hubKey 认证。
  // 识别成功 = 我们的服务,复用;不可达 = 启动;401 = 端口被别的程序占用(报错)。
  // ------------------------------------------------------------------
  let hub: ChildProcess | null = null
  const hubIdentity = await identifyService(cfg.hubPort, cfg.hubKey, 2000)
  if (hubIdentity === 'hub') {
    log('hub 已在运行(外部进程),跳过启动')
  } else if (hubIdentity !== null) {
    // 端口能响应但非 hub 服务(401 或 service 不匹配)
    throw new Error(
      `端口 ${cfg.hubPort} 已被其他程序占用(/admin/identify 返回非 hub),请更换 hubPort 或释放端口`,
    )
  } else {
    log(`启动 hub (${hubJarPath}) ...`)
    hub = spawnJava('hub', hubJarPath, [], hubLog, env, programRoot())
    log(`等待 hub 健康检查 ${hubHealthUrl} (30s)...`)
    await waitHttp(hubHealthUrl, 30000, 'hub', hub)
    log('hub 健康检查通过')
  }

  // ------------------------------------------------------------------
  // 认证探测已有 worker:GET /admin/identify 携带 workerApiKey 认证。
  // ------------------------------------------------------------------
  let worker: ChildProcess | null = null
  let actualWorkerId = ''
  const workerIdentity = await identifyService(cfg.workerPort, cfg.workerApiKey, 2000)
  if (workerIdentity === 'worker') {
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
  } else if (workerIdentity !== null) {
    throw new Error(
      `端口 ${cfg.workerPort} 已被其他程序占用(/admin/identify 返回非 worker),请更换 workerPort 或释放端口`,
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

  // 优雅停止:只停 desktop 自己启动的进程;外部进程(null)跳过。
  const stop = async (): Promise<void> => {
    if (worker) {
      log('停止 worker...')
      await stopProcess(worker, 8000)
    } else {
      log('worker 为外部进程,跳过停止')
    }
    if (hub) {
      log('停止 hub...')
      await stopProcess(hub, 5000)
    } else {
      log('hub 为外部进程,跳过停止')
    }
  }
  // 全部停止:对所有 hub/worker 发送 HTTP shutdown 触发 Spring 优雅关闭。
  // desktop 自己启动的进程:HTTP shutdown 超时后 child.kill() 兜底。
  // 外部进程:HTTP shutdown 超时只记日志(不按端口强杀,避免误杀)。
  const stopAll = async (): Promise<void> => {
    // 先停 worker 再停 hub(worker 先断开 hub 连接)
    if (worker) {
      log('停止 worker(HTTP shutdown + 兜底 kill)...')
      await shutdownViaHttp(cfg.workerPort, cfg.workerApiKey, 'worker', log, 8000)
      await stopProcess(worker, 3000)
    } else {
      log('停止外部 worker(HTTP shutdown)...')
      await shutdownViaHttp(cfg.workerPort, cfg.workerApiKey, 'worker', log, 8000)
    }
    if (hub) {
      log('停止 hub(HTTP shutdown + 兜底 kill)...')
      await shutdownViaHttp(cfg.hubPort, cfg.hubKey, 'hub', log, 5000)
      await stopProcess(hub, 3000)
    } else {
      log('停止外部 hub(HTTP shutdown)...')
      await shutdownViaHttp(cfg.hubPort, cfg.hubKey, 'hub', log, 5000)
    }
  }
  return { hub, worker, workerId: actualWorkerId, stop, stopAll }
}

function stopProcess(child: ChildProcess, graceMs: number): Promise<void> {
  return new Promise((resolve) => {
    if (!child || child.exitCode !== null || child.killed) return resolve()
    const timer = setTimeout(() => {
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
    }, graceMs)
    child.once('exit', () => {
      clearTimeout(timer)
      resolve()
    })
    try {
      // 注意:Node 在 Windows 上 child.kill('SIGTERM') 实际走 TerminateProcess(硬杀),
      // 不会触发 Spring Boot 的 @PreDestroy 优雅停机;worker 的磁盘写入本就是 fire-and-forget
      // 增量落盘,硬杀至多损失极少量未 flush 的尾事件,重开时由磁盘冷启动重建。可接受。
      child.kill('SIGTERM')
    } catch {
      clearTimeout(timer)
      resolve()
    }
  })
}
