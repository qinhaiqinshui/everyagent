/**
 * 桌面版运行时配置:从 <EVERYAGENT_HOME>/desktop-config.json 读取,缺省用默认值。
 * EVERYAGENT_HOME 未设置时默认 ~/.everyagent(与 worker 系统目录一致,数据复用)。
 *
 * 语义:desktop-config.json **只承载 desktop 层自身的偏好**(前端 bootstrap 地址/凭证、
 * GPU 兼容开关),不传给 hub/worker 进程——hub/worker 进程配置由 jar 内 application.yml
 * 默认 + ~/.everyagent/application-*.yaml 用户覆盖决定(desktop 不感知)。
 * 文件不存在时不自动生成,直接使用内置默认(零配置文件)。
 */
import { homedir } from 'node:os'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

export interface DesktopConfig {
  /** hub 级连接凭证(前端连接 hub 用)。 */
  hubKey: string
  /** worker apiKey(前端与 worker 建连,决定 ownerKey/数据归属)。 */
  workerApiKey: string
  /** worker 身份。 */
  workerId: string
  /** 本地 hub 端口(desktop 健康检查 + 前端 bootstrap 用)。 */
  hubPort: number
  /** 本地 worker 端口(desktop 健康检查 + 前端 bootstrap 用)。 */
  workerPort: number
  /**
   * GPU 兼容开关:默认 true(启用)。
   * 启用后在 app ready 之前追加 in-process-gpu 等绕过开关,兼容「无独立显卡 / 远程桌面 /
   * 虚拟机 / 受限会话」下 Chromium GPU 子进程创建失败(error_code=18)→ FATAL 崩溃的环境;
   * 有独立显卡的机器此开关无副作用(渲染能力不降级)。如确需关闭,显式置 false。
   */
  gpuWorkaround: boolean
}

export interface DesktopPaths {
  /** EVERYAGENT_HOME(worker 系统目录,复用现有数据)。 */
  home: string
  configFile: string
  logsDir: string
}

const DEFAULTS: DesktopConfig = {
  hubKey: 'sljlw23948LKS',
  workerApiKey: 'dev-key',
  workerId: 'company-pc',
  hubPort: 9100,
  workerPort: 9200,
  gpuWorkaround: true,
}

/** 解析 EVERYAGENT_HOME:环境变量优先,否则 ~/.everyagent。 */
export function resolveHome(): string {
  const env = process.env.EVERYAGENT_HOME
  if (env && env.trim()) return env.trim()
  return join(homedir(), '.everyagent')
}

/**
 * 轻量读取 GPU 兼容开关(无副作用,不创建/写入任何文件):
 * 供主进程在 app ready 之前决定是否追加 --in-process-gpu 等开关。
 * 默认启用(true);仅当配置文件中显式写 gpuWorkaround=false 时才关闭。
 * 文件不存在/解析失败一律视为 true(默认启用)。
 */
export function resolveGpuWorkaround(home: string): boolean {
  try {
    const p = join(home, 'desktop-config.json')
    if (!existsSync(p)) return true
    const parsed = JSON.parse(readFileSync(p, 'utf8'))
    if (!parsed || typeof parsed !== 'object') return true
    return (parsed as Record<string, unknown>).gpuWorkaround !== false
  } catch {
    return true
  }
}

export function pathsFor(home: string): DesktopPaths {
  return {
    home,
    configFile: join(home, 'desktop-config.json'),
    logsDir: join(home, 'logs'),
  }
}

function coercePort(value: unknown, fallback: number): number {
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isInteger(n) && n > 0 && n < 65536 ? n : fallback
}

function coerceString(value: unknown, fallback: string): string {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback
}

/** 读取配置(缺省用默认值);文件不存在时不生成,直接使用内置默认(零配置文件)。 */
export function loadConfig(home: string): DesktopConfig {
  const p = pathsFor(home)
  let raw: Record<string, unknown> = {}
  if (existsSync(p.configFile)) {
    try {
      const parsed = JSON.parse(readFileSync(p.configFile, 'utf8'))
      if (parsed && typeof parsed === 'object') raw = parsed as Record<string, unknown>
    } catch (error) {
      console.error('[desktop] 配置解析失败,回退默认值:', (error as Error).message)
    }
  }

  return {
    hubKey: coerceString(raw.hubKey, DEFAULTS.hubKey),
    workerApiKey: coerceString(raw.workerApiKey, DEFAULTS.workerApiKey),
    workerId: coerceString(raw.workerId, DEFAULTS.workerId),
    hubPort: coercePort(raw.hubPort, DEFAULTS.hubPort),
    workerPort: coercePort(raw.workerPort, DEFAULTS.workerPort),
    gpuWorkaround: raw.gpuWorkaround !== false,
  }
}

/** 前端 bootstrap 注入用(经 preload 暴露,不做持久化)。 */
export interface DesktopBootstrap {
  hubUrl: string
  hubKey: string
  workerId: string
  workerApiKey: string
}

export function bootstrapFor(cfg: DesktopConfig): DesktopBootstrap {
  return {
    hubUrl: `ws://127.0.0.1:${cfg.hubPort}/ws`,
    hubKey: cfg.hubKey,
    workerId: cfg.workerId,
    workerApiKey: cfg.workerApiKey,
  }
}