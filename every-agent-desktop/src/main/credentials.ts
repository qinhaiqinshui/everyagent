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
 *   Spring 对 list 属性跨配置文件整表替换、不合并——desktop-worker.yml 若只写本机
 *   一条,用户在 application-worker.yaml 里自配的远程 hub 会被整体覆盖丢失(表现为
 *   远程 hub 侧 worker 恒离线)。故生成时读取用户文件合并:hubs[0] 恒为本机条目
 *   (worker /admin 认证与 hubs[0].api-key 比对,不可移位),远程条目原序追加,
 *   回环地址条目丢弃(本机连接由 desktop 独占供数),${HUB_KEY:默认值} 占位符还原为
 *   字面默认值(子进程 env 的 HUB_KEY 恒为本机随机密钥,占位符不还原必被解析成错误凭据)。
 *
 * 落盘加密(safeStorage 迁移):desktop-config.json 中的密钥经 SecretCodec 编码后存储——
 * 生产装配为 Electron safeStorage(Windows DPAPI,绑定当前用户与机器;适配器在
 * safe-storage.ts,是唯一 import electron 的凭据装配点,本文件保持零 electron 依赖、
 * node:test 可直接单测),safeStorage 不可用时降级明文 codec。存储值带 'safe:' 前缀
 * 即密文形态;无前缀即旧版明文,下次启动一次性迁移为密文(值不变,ownerKey 数据归属
 * 不漂移);密文解密失败(换机器/凭据库重置/损坏)重新生成随机密钥并显式告警。
 */
import { randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
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

/** 用户级 worker 配置路径(<EVERYAGENT_HOME>/application-worker.yaml,worker 经 spring.config.import 加载)。 */
export function userWorkerYmlPath(home: string): string {
  return join(home, 'application-worker.yaml')
}

/** 用户 worker.hubs 条目的可合并子集:恰与 worker HubConfig{url,apiKey,hubKey} 一一对应。 */
export interface UserHubEntry {
  url: string
  apiKey: string
  hubKey: string
}

/** 去掉行内注释:YAML 规则子集——# 位于行首或空白之后、且不在引号内才算注释。 */
function stripYamlComment(line: string): string {
  let quote: string | null = null
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (quote) {
      if (ch === quote) quote = null
    } else if (ch === '"' || ch === "'") {
      quote = ch
    } else if (ch === '#' && (i === 0 || /\s/.test(line[i - 1]))) {
      return line.slice(0, i)
    }
  }
  return line
}

/** 剥掉成对的包裹引号(YAML 单/双引号标量);未包裹则原样(已 trim)返回。 */
function unquoteScalar(raw: string): string {
  const t = raw.trim()
  if (t.length >= 2 && ((t[0] === '"' && t.endsWith('"')) || (t[0] === "'" && t.endsWith("'")))) {
    return t.slice(1, -1)
  }
  return t
}

function splitKeyValue(t: string): { key: string; value: string } | null {
  const m = /^([A-Za-z][\w.-]*)\s*:\s*(.*)$/.exec(t)
  return m ? { key: m[1], value: unquoteScalar(m[2]) } : null
}

function applyField(kv: { key: string; value: string }, entry: Partial<UserHubEntry>): void {
  if (kv.key === 'url') entry.url = kv.value
  else if (kv.key === 'api-key') entry.apiKey = kv.value
  else if (kv.key === 'hub-key') entry.hubKey = kv.value
  // 条目内其余键 worker HubConfig 无对应字段(Spring binder 不绑定),忽略即可。
}

/**
 * 解析用户 application-worker.yaml 的 worker.hubs(极小 YAML 子集:块式列表 +
 * url/api-key/hub-key 标量;models: 等兄弟块及其任意嵌套整体跳过)。
 * 结构超出预期(缩进错乱/hubs 值非列表项)返回 null——调用方退回"仅本机条目"并告警,
 * 绝不猜测半懂的结构把可能错误的凭据写进 desktop-worker.yml。
 */
export function parseUserWorkerHubs(text: string): UserHubEntry[] | null {
  const lines = text.split(/\r?\n/)
  let inWorker = false // 当前顶层键是否 worker:
  let hubsIndent = -1 // worker.hubs: 的缩进(≥0 即处于收集态)
  let itemIndent = -1 // 列表项 "- " 的缩进(由首个列表项登记)
  let current: Partial<UserHubEntry> | null = null
  const out: UserHubEntry[] = []
  const pushCurrent = () => {
    if (current) {
      out.push({ url: current.url ?? '', apiKey: current.apiKey ?? '', hubKey: current.hubKey ?? '' })
    }
    current = null
  }
  for (const raw of lines) {
    const line = stripYamlComment(raw)
    if (!line.trim()) continue
    const indent = line.length - line.trimStart().length
    const t = line.trim()
    if (indent === 0) {
      pushCurrent()
      hubsIndent = -1
      itemIndent = -1
      inWorker = /^worker\s*:/.test(t)
      continue
    }
    if (!inWorker) continue
    if (hubsIndent < 0) {
      const m = /^hubs\s*:\s*(.*)$/.exec(t)
      if (m) {
        if (m[1].trim()) return null // hubs: 行内还有内容(flow 形态等),结构未识别
        hubsIndent = indent
      }
      continue
    }
    if (indent <= hubsIndent) {
      // hubs: 块结束(worker 下的兄弟键,如 models:)——其后内容整体不再关心
      pushCurrent()
      hubsIndent = -1
      itemIndent = -1
      continue
    }
    if (itemIndent < 0) {
      if (!/^-\s/.test(t)) return null // hubs 的值不是块式列表项(如 flow 映射),放弃
      itemIndent = indent
    }
    if (indent === itemIndent && /^-\s/.test(t)) {
      pushCurrent()
      const kv = splitKeyValue(t.replace(/^-\s+/, ''))
      if (!kv) return null
      current = {}
      applyField(kv, current)
      continue
    }
    if (indent > itemIndent && current) {
      const kv = splitKeyValue(t)
      if (kv) applyField(kv, current)
      continue
    }
    return null // 缩进不属于任何已知形态
  }
  pushCurrent()
  return out
}

/** 回环地址判定:localhost/127.0.0.1/[::1]/::1/0.0.0.0——本机 hub 连接由 desktop 独占供数。 */
export function isLoopbackWsUrl(url: string): boolean {
  try {
    const host = new URL(url).hostname.toLowerCase()
    return (
      host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host === '::1' || host === '0.0.0.0'
    )
  } catch {
    return false
  }
}

/**
 * ${HUB_KEY:默认值} → 字面默认值。desktop 给子进程注入的 env HUB_KEY 恒为本机 hub 的
 * 随机密钥;透传用户远程条目时若不还原占位符,Spring 会拿 env 值解析占位符 → 远程
 * hub 认证必败。无默认值的 ${HUB_KEY} 语义即"取 env",desktop 场景下无正确值可还原,
 * 保持原样(用户自担)。
 */
export function materializeHubKeyPlaceholder(value: string): string {
  return value.replace(/\$\{\s*HUB_KEY\s*:\s*([^}]*)\}/g, (_m, d: string) => d.trim())
}

/** writeWorkerOverrideYml 可选项。 */
export interface WorkerOverrideOptions {
  /** 告警通道(默认 console.warn);desktop 侧注入自身 logger 以落 desktop.log。 */
  warn?: (message: string) => void
}

/**
 * 生成/刷新 worker 覆盖配置,返回其 file: URL(--spring.config.additional-location 参数用)。
 * 每次拉起 worker 前重写,保证与 desktop-config.json 当前值一致。
 * 列表语义(核心):Spring 对 list 属性跨配置文件整表替换、不合并——本文件优先级最高,
 * 故必须在此写全最终 hubs 列表:hubs[0]=本机条目(与 desktop-config.json 凭据同源,
 * /admin 认证与 hubs[0].api-key 比对,不可移位);用户 application-worker.yaml 的
 * worker.hubs 远程条目原序合并(回环条目剔除;${HUB_KEY:默认值} 还原为字面值)。
 * 用户文件不存在/不可读/结构未识别 → 退回仅本机条目并经 warn 告警(不阻塞启动)。
 * 值经 JSON.stringify 输出——JSON 字符串是合法的 YAML 双引号标量(转义规则子集兼容),
 * 用户自定义密钥含特殊字符时也不会破坏 YAML 结构。
 */
export function writeWorkerOverrideYml(cfg: DesktopConfig, home: string, opts: WorkerOverrideOptions = {}): string {
  if (!cfg.hubKey || !cfg.workerApiKey) {
    throw new Error(
      'hubKey/workerApiKey 为空,拒绝生成 worker 覆盖配置(首启应已自动生成,请检查 desktop-config.json)',
    )
  }
  const warn = opts.warn ?? ((message: string) => console.warn(`[desktop] ${message}`))
  const file = workerOverrideYmlPath(home)
  mkdirSync(dirname(file), { recursive: true })
  const hubsYml: string[] = [
    `    - url: ${JSON.stringify(`ws://127.0.0.1:${cfg.hubPort}/ws`)}`,
    `      api-key: ${JSON.stringify(cfg.workerApiKey)}`,
    `      hub-key: ${JSON.stringify(cfg.hubKey)}`,
  ]
  const userFile = userWorkerYmlPath(home)
  if (existsSync(userFile)) {
    let userHubs: UserHubEntry[] | null = null
    let readFailed = false
    try {
      userHubs = parseUserWorkerHubs(readFileSync(userFile, 'utf8'))
    } catch (error) {
      readFailed = true
      warn(`读取 ${userFile} 失败(${(error as Error).message}),worker 覆盖配置仅保留本机 hub 条目`)
    }
    if (userHubs) {
      for (const hub of userHubs) {
        if (isLoopbackWsUrl(hub.url)) continue // 本机连接由 desktop 供数:防重复、防凭据错配
        hubsYml.push(
          `    - url: ${JSON.stringify(materializeHubKeyPlaceholder(hub.url))}`,
          `      api-key: ${JSON.stringify(materializeHubKeyPlaceholder(hub.apiKey))}`,
          `      hub-key: ${JSON.stringify(materializeHubKeyPlaceholder(hub.hubKey))}`,
        )
      }
    } else if (!readFailed) {
      warn(`解析 ${userFile} 的 worker.hubs 未识别(结构超出预期),worker 覆盖配置仅保留本机 hub 条目`)
    }
  }
  const content =
    '# 本文件由 Every Agent 桌面版自动生成,请勿手工编辑。\n' +
    '# 本机 hub 凭据与 desktop-config.json 保持一致,恒为 hubs[0](worker /admin 认证\n' +
    '# 与 hubs[0].api-key 比对);其余条目自 application-worker.yaml 的 worker.hubs 合并\n' +
    '# 而来——Spring 对 list 属性跨文件整表替换,此处必须写全最终列表,否则用户自配的\n' +
    '# 远程 hub 会被本文件整体覆盖丢失;回环地址条目已剔除;${HUB_KEY:默认值} 占位符\n' +
    '# 已还原为字面默认值(子进程 env 的 HUB_KEY 恒为本机随机密钥,占位符不还原会被\n' +
    '# 解析成错误凭据)。\n' +
    'worker:\n' +
    '  hubs:\n' +
    hubsYml.join('\n') +
    '\n'
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
