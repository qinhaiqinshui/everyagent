/**
 * utilityProcess 子进程入口:在独立的 Node 子进程中执行 WSL 发行版检查。
 *
 * 为什么用 utilityProcess 而非主进程直接 spawn wsl.exe:
 *   Electron 主进程(Chromium)直接 spawn 系统 console 程序(wsl.exe)存在 native
 *   崩溃风险(GPU 进程与子进程启动竞争、完整性级别等);把 wsl 交互放到独立 Node
 *   子进程,彻底隔离——即使子进程出问题,也只崩子进程,不影响主进程与 hub/worker 启动。
 *
 * 通信协议(parentPort):
 *   主进程 → 子进程: { home, distro, bundledDir }
 *   子进程 → 主进程: { type:'log', line } | { type:'result', result }
 *
 * parentPort 获取双保险:utilityProcess 子进程的标准通信端口是 Node 侧
 * `process.parentPort`;`require('electron').parentPort` 在部分打包/版本环境不可用
 * (历史崩溃根因:模块加载期取不到端口 → parentPort.on 抛异常 → 子进程 code=1 零日志
 * 退出,自动导入从未执行)。这里先取 process.parentPort,再回退 electron 模块,并加
 * 崩溃兜底(uncaughtException/unhandledRejection 统一转 result 上报,绝不静默)。
 */
import { ensureWslDistro } from './wsl-distro'

interface CheckRequest {
  home: string
  distro: string
  bundledDir?: string
}

interface CheckResult {
  wslAvailable: boolean
  distroPresent: boolean
  importedNow: boolean
  detail: string
}

/** 通信端口的最小接口(避免对 Electron 类型强依赖,运行期以对象形状工作)。 */
interface PortLike {
  on(event: 'message', listener: (event: { data?: CheckRequest }) => void): unknown
  postMessage(msg: unknown): void
}

function failResult(detail: string): CheckResult {
  return { wslAvailable: false, distroPresent: false, importedNow: false, detail }
}

function resolvePort(): PortLike | null {
  // 1) 标准接口:utilityProcess 子进程的 process.parentPort(Node 侧注入)
  const nodePort = (process as unknown as { parentPort?: PortLike }).parentPort
  if (nodePort) return nodePort
  // 2) 回退:require('electron').parentPort(部分版本在子进程同样暴露)
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const electron = require('electron') as { parentPort?: PortLike }
    if (electron && electron.parentPort) return electron.parentPort
  } catch {
    /* require('electron') 不可用:走兜底 */
  }
  return null
}

const parentPort = resolvePort()

function post(msg: unknown): void {
  try {
    parentPort?.postMessage(msg)
  } catch {
    /* 端口已关,忽略 */
  }
}

// 崩溃兜底:任何未捕获异常都尽量转成 result 上报,绝不让主进程干等/静默失败
process.on('uncaughtException', (err) => {
  const message = err instanceof Error && err.stack ? err.stack : String(err)
  try {
    process.stderr.write('[wsl-check] 未捕获异常: ' + message + '\n')
  } catch {
    /* ignore */
  }
  post({ type: 'result', result: failResult(`wsl 检查子进程异常: ${err instanceof Error ? err.message : String(err)}`) })
  process.exit(1)
})
process.on('unhandledRejection', (reason) => {
  const message = reason instanceof Error ? (reason.stack ?? reason.message) : String(reason)
  try {
    process.stderr.write('[wsl-check] 未处理拒绝: ' + message + '\n')
  } catch {
    /* ignore */
  }
  post({ type: 'result', result: failResult(`wsl 检查子进程异常: ${message}`) })
  process.exit(1)
})

if (!parentPort) {
  // 极端兜底:端口不可得也留痕退出,而非模块加载期抛异常导致零日志崩溃
  process.stderr.write('[wsl-check] parentPort 不可用,子进程退出\n')
  process.exit(1)
}

parentPort.on('message', (event: { data?: CheckRequest }) => {
  const req = event.data
  if (!req || !req.home) {
    post({ type: 'result', result: failResult('bad request') })
    return
  }
  const onLog = (line: string): void => {
    post({ type: 'log', line })
  }
  ensureWslDistro(req.home, req.distro, onLog, req.bundledDir)
    .then((result) => {
      post({ type: 'result', result })
    })
    .catch((err) => {
      const message = err instanceof Error ? err.message : String(err)
      post({ type: 'result', result: failResult(`wsl 检查异常: ${message}`) })
    })
})

