/**
 * 桌面版运行时配置:从 <EVERYAGENT_HOME>/desktop-config.json 读取,缺省用默认值。
 * EVERYAGENT_HOME 未设置时默认 ~/.everyagent(与 worker 系统目录一致,数据复用)。
 *
 * 语义:desktop-config.json 承载 desktop 层自身的偏好与**连接凭据**(前端 bootstrap
 * 凭证、GPU 兼容开关)。凭据(hubKey/workerApiKey)不再内置默认值(安全审计 H-1:
 * 历史硬编码密钥已随仓库公开):缺失/为空时首启自动生成随机密钥并持久化;密钥经
 * SecretCodec 编码后落盘(生产为 safeStorage 密文,装配点在 safe-storage.ts;默认
 * 明文降级 codec 供单测与无 electron 环境),已有值只迁移落盘形态、明文值不变(升级
 * 兼容);hub/worker 子进程凭据由 credentials.ts 接线(env HUB_KEY + desktop-worker.yml),
 * 保证与 hub 一致且不落回 jar 内公开默认密钥。
 */
import { homedir } from 'node:os'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ensureSecretKeys, PLAINTEXT_CODEC, type SecretCodec } from './credentials'

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
  // 安全审计 H-1:删除硬编码默认密钥(已随仓库公开,等同无鉴权)。空串 = 未配置:
  // loadConfig 检测到空值即生成随机密钥并持久化(credentials.ensureSecretKeys);
  // 已有配置文件的非空值保持不变(升级兼容,ownerKey 数据归属不漂移)。
  hubKey: '',
  workerApiKey: '',
  workerId: 'company-pc',
  hubPort: 6101,
  workerPort: 6102,
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

/**
 * 读取配置(缺省用默认值)。hubKey/workerApiKey 缺失/为空时生成随机密钥,旧明文一次性
 * 迁移为密文,密文损坏时重新生成,并原子回写 desktop-config.json(credentials.ensureSecretKeys);
 * 生成先于 backend 拉起子进程(index.ts 中 loadConfig 先于 startBackend)。codec 为密文
 * codec(safeStorage)时必须在 app ready 之后调用(index.ts 已保证);默认明文降级 codec,
 * 供 node:test 单测直接使用。其余字段缺省即默认,不写文件。
 */
export function loadConfig(home: string, codec: SecretCodec = PLAINTEXT_CODEC): DesktopConfig {
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

  const keys = ensureSecretKeys(raw, p.configFile, codec)
  return {
    hubKey: keys.hubKey,
    workerApiKey: keys.workerApiKey,
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