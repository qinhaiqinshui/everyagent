/**
 * 全局异常兜底捕获。
 *
 * 注册 window 'error' 与 'unhandledrejection' 监听，把未被业务 try/catch
 * 捕获的同步错误与 Promise rejection 写入运行日志（/logs/runtime），
 * 使日志页可见，避免错误静默丢失。
 *
 * 必须在应用启动最早处调用 `installGlobalErrorHandlers()`（main.tsx 中先于
 * bootstrap）。前端日志只进 console（文件写入已随运行时下沉 worker），handler
 * 内部仅依赖 `logger` 之前置的吞异常能力，配合 `safeLog` 自身 try/catch 兜底，
 * 保证兜底路径自身绝不抛出，不会触发 unhandledrejection 形成循环。
 */
import { createLogger } from '@/utils/logger'

const globalLogger = createLogger('GlobalError')

/** 是否已安装，避免重复注册。 */
let installed = false

function formatError(error: unknown): {
  name?: string
  message: string
  stack?: string
} {
  if (error instanceof Error) {
    return { name: error.name, message: error.message, stack: error.stack }
  }
  if (typeof error === 'string') {
    return { message: error }
  }
  try {
    return { message: JSON.stringify(error) }
  } catch {
    return { message: String(error) }
  }
}

type SafeLogLevel = 'error' | 'warn' | 'info'

/** 安全写日志：写文件失败时仅 console，绝不抛出。 */
function safeLog(level: SafeLogLevel, message: string, data?: unknown): void {
  try {
    if (level === 'error') {
      globalLogger.error(message, data)
    } else if (level === 'warn') {
      globalLogger.warn(message, data)
    } else {
      globalLogger.info(message, data)
    }
  } catch {
    // 兜底：logger 内部已吞掉写入异常，此处再兜一层，确保绝不向上抛
  }
}

/**
 * 安装全局未捕获异常兜底。重复调用安全（幂等）。
 *
 * 捕获范围：
 * - `window.error`：同步脚本异常、资源加载错误。
 * - `unhandledrejection`：未被 `.catch()` 的 Promise rejection。
 */
export function installGlobalErrorHandlers(): void {
  if (installed) return
  installed = true

  // 同步未捕获错误（脚本异常、资源加载错误等）
  window.addEventListener('error', (event: ErrorEvent) => {
    // 资源加载错误 event.error 可能为 null，仍记录 message 与目标
    const detail = {
      type: 'error',
      message: event.message || '未捕获的运行时错误',
      filename: event.filename,
      lineno: event.lineno,
      colno: event.colno,
      error: event.error ? formatError(event.error) : undefined,
    }
    safeLog('error', '全局未捕获错误 (window.error)', detail)
    // 不阻止默认行为，保持浏览器原有错误展示
    return false
  })

  // 未处理的 Promise rejection
  window.addEventListener('unhandledrejection', (event: PromiseRejectionEvent) => {
    const reason = event.reason
    const detail = {
      type: 'unhandledrejection',
      reason: formatError(reason),
    }
    safeLog('error', '全局未处理的 Promise rejection', detail)
    return false
  })

  safeLog('info', '全局异常兜底已安装')
}
