/**
 * 连接凭据的生成与接线(不依赖 electron,可独立单测)。
 *
 * 背景(安全审计 H-1):历史版本把默认 hubKey/workerApiKey 硬编码进 desktop 源码,
 * 密钥已随仓库公开——任何读过仓库的人都能连上未改配置的部署(hub 默认绑 0.0.0.0)。
 * 现在的接线方式:
 * - 首启(或密钥缺失/为空)用 crypto.randomBytes 生成 32 字节随机密钥(base64url),
 *   持久化到 desktop-config.json;已有非空值一律保持不变(升级兼容,ownerKey 数据归属不漂移);
 * - hub 子进程经 env HUB_KEY 显式注入(覆盖任何继承)——不注入则 hub 落回 jar 内
 *   `${HUB_KEY:公开默认密钥}` 占位符的默认值;
 * - worker 子进程经 --spring.config.additional-location 注入生成的 desktop-worker.yml
 *   (实测优先级高于 jar 内默认值与 ~/.everyagent/application-worker.yaml 用户覆盖),
 *   使 worker 连接本机 hub 的 api-key/hub-key 与 hub 凭据恒一致。
 *
 * 落盘加密(safeStorage 迁移):desktop-config.json 中的密钥经 SecretCodec 编码后存储——
 * 生产装配为 Electron safeStorage(Windows DPAPI,绑定当前用户与机器;适配器在
 * safe-storage.ts,是唯一 import electron 的凭据装配点,本文件保持零 electron 依赖、
 * node:test 可直接单测),safeStorage 不可用时降级明文 codec。存储值带 'safe:' 前缀
 * 即密文形态;无前缀即旧版明文,下次启动一次性迁移为密文(值不变,ownerKey 数据归属
 * 不漂移);密文解密失败(换机器/凭据库重置/损坏)重新生成随机密钥并显式告警。
 */
import { randomBytes } from 'node:crypto'
import { mkdirSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { pathToFileURL } from 'node:url'
import type { DesktopConfig } from './config'

/** 随机密钥:32 字节熵,base64url(43 字符,A-Za-z0-9_-;JSON/env/YAML 双引号标量均安全)。 */
export function generateSecret(): string {
  return randomBytes(32).toString('base64url')
}

export interface SecretKeys {
  hubKey: string
  workerApiKey: string
}

/** 密文形态标记前缀:desktop-config.json 中带此前缀的密钥值即 safeStorage 密文。 */
export const SAFE_PREFIX = 'safe:'

/**
 * 凭据编解码器:明文密钥 ↔ 落盘形态。生产装配为 Electron safeStorage(DPAPI)适配器
 * (见 safe-storage.ts);单测与降级用 PLAINTEXT_CODEC。本模块零 electron 依赖。
 */
export interface SecretCodec {
  /** codec 名称(日志与测试用)。 */
  readonly name: string
  /** 是否可用:密文 codec 依赖系统凭据库,不可用时装配方必须降级为明文 codec。 */
  isAvailable(): boolean
  /** 明文 → 落盘形态。密文 codec 必须自带 SAFE_PREFIX 标记;明文 codec 恒等。 */
  encode(plain: string): string
  /**
   * 落盘形态 → 明文。无法解密(标记不符/密文损坏/凭据库不可用)必须抛错,
   * 由 resolveSecret 走重新生成分支——绝不能静默返回原值(否则密文串会被当作密钥接线)。
   */
  decode(stored: string): string
}

/** 明文降级 codec:safeStorage 不可用时的兜底,恒等编解码、不加前缀(与历史行为一致)。 */
export const PLAINTEXT_CODEC: SecretCodec = {
  name: 'plaintext',
  isAvailable: () => true,
  encode: (plain: string) => plain,
  decode(stored: string) {
    if (stored.startsWith(SAFE_PREFIX)) {
      throw new Error(`明文 codec 无法解密 ${SAFE_PREFIX} 前缀的密文形态`)
    }
    return stored
  },
}

function coerceSecret(value: unknown): string {
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}

/** 密钥落盘解析分支(日志摘要用)。 */
export type SecretReason = 'generated' | 'kept' | 'migrated' | 'regenerated'

export interface ResolvedSecret {
  /** 明文密钥(hub/worker/前端接线用,内存中恒为明文)。 */
  value: string
  /** 落盘形态:密文 codec 为 'safe:' 密文;明文降级即原值。 */
  encoded: string
  /** 落盘形态是否相对原值变化(决定是否回写配置文件)。 */
  changed: boolean
  /** 分支:generated=新生成 / kept=原样沿用 / migrated=明文迁移密文 / regenerated=密文损坏重生成。 */
  reason: SecretReason
}

/**
 * 解析单个密钥的落盘形态,三分支:
 * 1. 空(缺失/为空)→ 生成随机密钥并按 codec 编码落盘;
 * 2. SAFE_PREFIX 密文形态 → codec.decode 成功则原样沿用(明文只在内存中还原);
 *    decode 抛错(密文损坏/换机器/凭据库重置,或明文降级 codec 遇到密文)→ 告警并重新生成;
 * 3. 无前缀的旧版明文 → 按 codec 重新编码做一次性迁移(value 不变,数据归属不漂移)。
 *    codec 为明文降级时 encode 恒等,encoded === stored → changed:false 不重写文件——
 *    这是「无 safeStorage 环境下既有明文配置字节不变」的关键(升级兼容)。
 */
export function resolveSecret(
  stored: string,
  name: string,
  codec: SecretCodec = PLAINTEXT_CODEC,
  warn: (message: string) => void = (message) => console.warn(`[desktop] ${message}`),
): ResolvedSecret {
  if (!stored) {
    const value = generateSecret()
    return { value, encoded: codec.encode(value), changed: true, reason: 'generated' }
  }
  if (stored.startsWith(SAFE_PREFIX)) {
    try {
      return { value: codec.decode(stored), encoded: stored, changed: false, reason: 'kept' }
    } catch (error) {
      warn(
        `${name} 密文解密失败(${error instanceof Error ? error.message : String(error)}),` +
          '已重新生成随机密钥——ownerKey 数据归属将漂移,旧任务请沿用旧密钥或从备份恢复',
      )
      const value = generateSecret()
      return { value, encoded: codec.encode(value), changed: true, reason: 'regenerated' }
    }
  }
  const encoded = codec.encode(stored)
  if (encoded !== stored) {
    warn(`${name} 为旧版明文形态,已一次性迁移为 ${codec.name} 密文落盘(值不变)`)
    return { value: stored, encoded, changed: true, reason: 'migrated' }
  }
  return { value: stored, encoded, changed: false, reason: 'kept' }
}

/**
 * 确保两个密钥可用:逐个经 resolveSecret 解析(生成/明文迁移/密文解密,三分支见其注释),
 * 任一密钥落盘形态变化(changed)才把「原配置对象 ∪ 编码后密钥」原子回写 desktop-config.json
 * (保留用户既有字段;回写的是 encoded 形态——密文 codec 即 'safe:' 密文)。
 * 两者均无变化时直接返回,不碰文件(升级场景配置字节不变)。
 * 回写失败只记日志并以内存值继续——但下次启动会重新解析,故同时显式告警。
 */
export function ensureSecretKeys(
  raw: Record<string, unknown>,
  configFile: string,
  codec: SecretCodec = PLAINTEXT_CODEC,
): SecretKeys {
  const hub = resolveSecret(coerceSecret(raw.hubKey), 'hubKey', codec)
  const worker = resolveSecret(coerceSecret(raw.workerApiKey), 'workerApiKey', codec)
  if (!hub.changed && !worker.changed) {
    return { hubKey: hub.value, workerApiKey: worker.value }
  }
  const keys: SecretKeys = { hubKey: hub.value, workerApiKey: worker.value }
  const summary = [
    hub.changed ? `hubKey:${hub.reason}` : null,
    worker.changed ? `workerApiKey:${worker.reason}` : null,
  ].filter((part): part is string => part !== null).join(', ')
  try {
    mkdirSync(dirname(configFile), { recursive: true })
    // 先写临时文件再原子改名:中断不会留下截断的 desktop-config.json
    //(密钥决定 ownerKey 数据归属,配置文件损坏即数据"失联")。
    const tmp = `${configFile}.tmp`
    writeFileSync(
      tmp,
      JSON.stringify(
        { ...raw, hubKey: hub.encoded, workerApiKey: worker.encoded },
        null,
        2,
      ) + '\n',
      'utf8',
    )
    renameSync(tmp, configFile)
    console.info(
      `[desktop] 密钥已更新(${summary},codec=${codec.name})并持久化到 ${configFile}` +
        '(密钥决定数据归属,请勿随意删除或泄露)',
    )
  } catch (error) {
    console.error(
      `[desktop] 密钥已解析但回写 ${configFile} 失败,下次启动将重新生成(数据归属可能漂移,请检查目录可写性):`,
      (error as Error).message,
    )
  }
  return keys
}

/** worker 连接本机 hub 的覆盖配置路径(<EVERYAGENT_HOME>/desktop/desktop-worker.yml)。 */
export function workerOverrideYmlPath(home: string): string {
  return join(home, 'desktop', 'desktop-worker.yml')
}

/**
 * 生成/刷新 worker 覆盖配置,返回其 file: URL(--spring.config.additional-location 参数用)。
 * 每次拉起 worker 前重写,保证与 desktop-config.json 当前值一致。
 * 值经 JSON.stringify 输出——JSON 字符串是合法的 YAML 双引号标量(转义规则子集兼容),
 * 用户自定义密钥含特殊字符时也不会破坏 YAML 结构。
 */
export function writeWorkerOverrideYml(cfg: DesktopConfig, home: string): string {
  if (!cfg.hubKey || !cfg.workerApiKey) {
    throw new Error(
      'hubKey/workerApiKey 为空,拒绝生成 worker 覆盖配置(首启应已自动生成,请检查 desktop-config.json)',
    )
  }
  const file = workerOverrideYmlPath(home)
  mkdirSync(dirname(file), { recursive: true })
  const content =
    '# 本文件由 Every Agent 桌面版自动生成,请勿手工编辑。\n' +
    '# worker 连接本机 hub 的凭据(与 desktop-config.json 保持一致);经\n' +
    '# --spring.config.additional-location 注入,优先级高于 jar 内默认值与\n' +
    '# application-worker.yaml 用户覆盖文件,确保 worker 与 hub 凭据一致。\n' +
    'worker:\n' +
    '  hubs:\n' +
    `    - url: ${JSON.stringify(`ws://127.0.0.1:${cfg.hubPort}/ws`)}\n` +
    `      api-key: ${JSON.stringify(cfg.workerApiKey)}\n` +
    `      hub-key: ${JSON.stringify(cfg.hubKey)}\n`
  writeFileSync(file, content, 'utf8')
  return pathToFileURL(file).href
}

/**
 * hub/worker 子进程共享 env:显式注入 EVERYAGENT_HOME 与 HUB_KEY。
 * HUB_KEY 必须来自配置并覆盖任何继承——hub 的 hub.hub-key 读 `${HUB_KEY:...}` 占位符,
 * 不注入就会落回 jar 内公开默认密钥;worker 侧同一 env 使其占位符与 hub 一致
 * (desktop-worker.yml 已显式写全 hubs[0],此处 env 是双保险)。
 */
export function buildBackendEnv(cfg: DesktopConfig, home: string): NodeJS.ProcessEnv {
  if (!cfg.hubKey) {
    throw new Error(
      'hubKey 为空,拒绝启动后端(首启应已自动生成,请检查 desktop-config.json)',
    )
  }
  return { ...process.env, EVERYAGENT_HOME: home, HUB_KEY: cfg.hubKey }
}
