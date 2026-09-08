/**
 * 日志工具模块
 *
 * 支持：
 * - 命名日志器（按 module 名创建，类似 Java 的 `Logger.getLogger(name)`）
 * - 按名字判定级别是否显示：每个 module 可独立设置生效级别，
 *   未单独设置则回退到全局默认级别（类 Java 的 per-logger level）
 * - 分级日志：TRACE / DEBUG / INFO / WARN / ERROR
 * - 控制台输出（浏览器 DevTools 可见）
 * - 文件写入（logs/ 目录，按天分割，异步不阻塞；仅 INFO 及以上落盘，DEBUG/TRACE 仅控制台，避免排查日志拖慢主流程）
 * - 生产构建默认抑制 DEBUG/TRACE（仅 INFO 及以上输出），排查期可经 localStorage 临时放开
 *
 * 级别控制（按优先级判定，仅「生效级别 <= 日志级别」才输出）：
 * - 全局默认：DEV → DEBUG（开发期诊断日志可见）；PROD → INFO（发版时 DEBUG/TRACE 静默）
 * - 运行时覆盖（排查问题时用，无需改代码、重新打包）：
 *     localStorage.setItem('logLevel', 'DEBUG')            // 全局放开
 *     localStorage.setItem('log:TaskRunner', 'TRACE')       // 仅放开某个 module
 *     localStorage.removeItem('logLevel')                    // 恢复默认
 * - 代码内覆盖（程序化配置）：
 *     setLogLevel('DEBUG')              // 全局
 *     setModuleLogLevel('TaskRunner', 'TRACE')  // 单个 module
 *
 * 性能：级别未开启时，`log()` 在构造日志条目之前即早退——
 * 不捕获堆栈、不写文件、不格式化，调用方传入的 data 对象（如已构造）除外。
 * 热路径若需彻底零开销，用 `if (log.isDebugEnabled()) log.debug(...)` 守卫，
 * 或把 data 以 `() => ({...})` 形式惰性传入（仅启用时求值）。
 */
/** 文件日志已裁剪(运行时在 worker 侧),仅控制台输出。 */

export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'

/** 级别优先级：值越小越详细。`isLevelEnabled` 用 `日志级别 >= 生效级别` 判定。 */
const LEVEL_VALUE: Record<LogLevel, number> = {
  TRACE: 10,
  DEBUG: 20,
  INFO: 30,
  WARN: 40,
  ERROR: 50,
}

const DEV_DEFAULT_LEVEL: LogLevel = 'DEBUG'
const PROD_DEFAULT_LEVEL: LogLevel = 'INFO'

function readEnvDefault(): LogLevel {
  return import.meta.env.DEV ? DEV_DEFAULT_LEVEL : PROD_DEFAULT_LEVEL
}

function isKnownLevel(value: unknown): value is LogLevel {
  return typeof value === 'string' && value.toUpperCase() in LEVEL_VALUE
}

/** 从 localStorage 读取全局级别覆盖（运行时排查用）。 */
function readGlobalOverride(): LogLevel | null {
  try {
    const raw = globalThis.localStorage?.getItem('logLevel')
    if (raw && isKnownLevel(raw)) return raw.toUpperCase() as LogLevel
  } catch {
    /* localStorage 不可用时忽略 */
  }
  return null
}

/** 从 localStorage 读取某个 module 的级别覆盖。 */
function readModuleOverride(moduleName: string): LogLevel | null {
  try {
    const raw = globalThis.localStorage?.getItem(`log:${moduleName}`)
    if (raw && isKnownLevel(raw)) return raw.toUpperCase() as LogLevel
  } catch {
    /* localStorage 不可用时忽略 */
  }
  return null
}

/** 全局生效级别（受 setLogLevel / localStorage('logLevel') 控制）。 */
let globalLevel: LogLevel = readGlobalOverride() ?? readEnvDefault()
/** 代码内显式设置的 per-module 级别（优先级最高）。 */
const moduleLevelOverrides = new Map<string, LogLevel>()
/** per-module 生效级别缓存（override → localStorage → global 懒计算结果）。 */
const moduleLevelCache = new Map<string, LogLevel>()

function invalidateCache(moduleName?: string): void {
  if (moduleName) {
    moduleLevelCache.delete(moduleName)
  } else {
    moduleLevelCache.clear()
  }
}

/** 计算某个 module 的生效级别。优先级：代码 override > localStorage > 全局默认。 */
function effectiveLevel(moduleName: string): LogLevel {
  const explicit = moduleLevelOverrides.get(moduleName)
  if (explicit) return explicit
  const cached = moduleLevelCache.get(moduleName)
  if (cached) return cached
  const fromStorage = readModuleOverride(moduleName)
  const resolved = fromStorage ?? globalLevel
  moduleLevelCache.set(moduleName, resolved)
  return resolved
}

function isLevelEnabled(moduleName: string, level: LogLevel): boolean {
  return LEVEL_VALUE[level] >= LEVEL_VALUE[effectiveLevel(moduleName)]
}

/**
 * 全局设置生效级别（影响所有未单独配置的 module）。
 * 排查问题时也可改用 `localStorage.setItem('logLevel', 'DEBUG')` 后刷新页面。
 */
export function setLogLevel(level: LogLevel): void {
  globalLevel = level
  invalidateCache()
}

/**
 * 单独设置某个 module 的生效级别（优先级高于全局与 localStorage）。
 */
export function setModuleLogLevel(moduleName: string, level: LogLevel): void {
  moduleLevelOverrides.set(moduleName, level)
  invalidateCache(moduleName)
}

/** 查询某个 module 当前的生效级别（便于排查"为何某条日志没输出"）。 */
export function getEffectiveLogLevel(moduleName: string): LogLevel {
  return effectiveLevel(moduleName)
}

/**
 * 判断某个 module 的生效级别是否「达到或超过」给定级别（阈值开关）。
 *
 * 与 `isWarnEnabled()` 等「该级别是否被启用」判定相反：后者在更详细的 DEBUG/INFO 下也返回
 * true（更详细的级别必然包含更严重的级别），无法表达「仅当日志收紧到 WARN 及以上才做事」。
 * 本函数用于按阈值开关特定日志行为，例如 AI 调用审计日志仅在生产环境被显式放宽到 WARN
 * 及以上时才落盘——默认 DEV(DEBUG)/PROD(INFO) 均不满足阈值，故默认关闭。
 */
export function isModuleLevelAtLeast(moduleName: string, level: LogLevel): boolean {
  return LEVEL_VALUE[effectiveLevel(moduleName)] >= LEVEL_VALUE[level]
}

export interface TaskLogContext {
  /** 当前日志关联的 Agent ID。 */
  agentId?: string
}

interface LogEntry {
  timestamp: string
  level: LogLevel
  module: string
  message: string
  source?: string
  data?: unknown
}

function hasTaskContext(context: TaskLogContext): boolean {
  return Boolean(context.agentId)
}

class Logger {
  private moduleName: string
  private taskContext: TaskLogContext | null = null

  constructor(moduleName: string) {
    this.moduleName = moduleName
  }

  /**
   * 设置当前日志实例写入文件时附带的 Task/Agent 上下文。
   */
  setTaskContext(context: TaskLogContext | null) {
    this.taskContext = context ? { ...context } : null
  }

  isTraceEnabled() {
    return isLevelEnabled(this.moduleName, 'TRACE')
  }

  isDebugEnabled() {
    return isLevelEnabled(this.moduleName, 'DEBUG')
  }

  isInfoEnabled() {
    return isLevelEnabled(this.moduleName, 'INFO')
  }

  isWarnEnabled() {
    return isLevelEnabled(this.moduleName, 'WARN')
  }

  isErrorEnabled() {
    return isLevelEnabled(this.moduleName, 'ERROR')
  }

  trace(message: string, data?: unknown) {
    this.log('TRACE', message, data)
  }

  debug(message: string, data?: unknown) {
    this.log('DEBUG', message, data)
  }

  info(message: string, data?: unknown) {
    this.log('INFO', message, data)
  }

  warn(message: string, data?: unknown) {
    this.log('WARN', message, data)
  }

  error(message: string, data?: unknown) {
    this.log('ERROR', message, data)
  }

  private log(level: LogLevel, message: string, data?: unknown) {
    // 早退：级别未开启则完全不处理（不构造条目、不捕获堆栈、不写文件）。
    if (!isLevelEnabled(this.moduleName, level)) return

    // data 支持惰性求值：传入函数时仅启用时调用，热路径可用 () => ({...}) 避免无谓构造。
    const resolvedData = typeof data === 'function' ? (data as () => unknown)() : data

    const entry: LogEntry = {
      timestamp: new Date().toISOString(),
      level,
      module: this.moduleName,
      message,
      source: captureLogSource(),
      data: resolvedData,
    }

    // 1. 控制台输出
    const consoleMsg = `[${entry.timestamp}] [${level}] [${this.moduleName}] ${message}`
    switch (level) {
      case 'ERROR':
        console.error(consoleMsg, resolvedData ?? '')
        break
      case 'WARN':
        console.warn(consoleMsg, resolvedData ?? '')
        break
      case 'INFO':
        console.info(consoleMsg, resolvedData ?? '')
        break
      case 'DEBUG':
        // 用 console.log 而非 console.debug：Chrome 默认隐藏 console.debug（需切到 Verbose），
        // 用 log 保证 DEBUG 在开发期默认可见。
        console.log(consoleMsg, resolvedData ?? '')
        break
      case 'TRACE':
      default:
        console.debug(consoleMsg, resolvedData ?? '')
        break
    }

    // 2. 文件写入——已随运行时下沉 worker 裁剪(n 分支写 ZenFS logs/ 目录)。
    // 前端日志只进控制台;需要留存时由浏览器 DevTools 导出。
  }

  private safeStringify(data: unknown): string {
    try {
      if (data instanceof Error) {
        return JSON.stringify({ name: data.name, message: data.message, stack: data.stack })
      }
      return JSON.stringify(data, null, 0)
    } catch {
      return String(data)
    }
  }
}

/**
 * 捕获当前日志调用源，便于在运行日志里直接定位源码文件与行号。
 */
function captureLogSource(): string | undefined {
  try {
    const stack = new Error().stack
    if (!stack) return undefined
    const lines = stack.split('\n').map((line) => line.trim()).filter(Boolean)
    for (const line of lines) {
      if (
        line.includes('captureLogSource')
        || line.includes('Logger.log')
        || line.includes('Logger.debug')
        || line.includes('Logger.info')
        || line.includes('Logger.warn')
        || line.includes('Logger.error')
        || line.includes('Logger.trace')
        || line.includes('/src/utils/logger.ts')
        || line.includes('\\src\\utils\\logger.ts')
      ) {
        continue
      }
      const normalized = normalizeStackSourceLine(line)
      if (normalized) {
        return normalized
      }
    }
    return undefined
  } catch {
    return undefined
  }
}

/**
 * 规范化堆栈行，提取相对源码路径与行列号。
 */
function normalizeStackSourceLine(line: string): string | undefined {
  const matched = line.match(/(?:at\s+.*?\s+\()?(.*?):(\d+):(\d+)\)?$/)
  if (!matched) return undefined
  const rawPath = matched[1].replace(/[?#].*$/, '').replace(/\\/g, '/')
  const lineNumber = matched[2]
  const columnNumber = matched[3]
  const srcIndex = rawPath.lastIndexOf('/src/')
  const normalizedPath = srcIndex >= 0
    ? rawPath.slice(srcIndex + 1)
    : rawPath.split('/').slice(-3).join('/')
  return `${normalizedPath}:${lineNumber}:${columnNumber}`
}

/**
 * 创建日志实例（按 module 命名，类似 Java 的 `Logger.getLogger(name)`）。
 *
 * 用法：
 *   import { createLogger } from '@/utils/logger'
 *   const log = createLogger('PlanEngine')
 *   log.info('开始执行任务', { taskId: 'xxx' })
 *   log.debug('排查用的细粒度日志')   // 生产构建默认不输出
 *
 * 级别选择约定（详见 AGENTS.md）：
 *   - DEBUG/TRACE：排查用的诊断日志，开发期可见、发版静默，可长期保留在代码中
 *   - INFO：关键业务节点（任务启动 / 停止等），生产保留
 *   - WARN：可恢复异常 / 预期外但已兜底的状况
 *   - ERROR：需要排查的失败
 */
export function createLogger(moduleName: string): Logger {
  return new Logger(moduleName)
}
