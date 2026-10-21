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

function coerceSecret(value: unknown): string {
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}

/**
 * 确保两个密钥非空:缺失/为空者生成随机值,并把「原配置对象 ∪ 最终密钥」原子回写
 * desktop-config.json(保留用户既有字段;已有非空值保持原样,绝不重新生成)。
 * 两者均已非空时直接返回,不碰文件(升级场景配置字节不变)。
 * 回写失败只记日志并以内存值继续——但下次启动会重新生成,故同时显式告警。
 */
export function ensureSecretKeys(
  raw: Record<string, unknown>,
  configFile: string,
): SecretKeys {
  const hubKey = coerceSecret(raw.hubKey)
  const workerApiKey = coerceSecret(raw.workerApiKey)
  if (hubKey && workerApiKey) return { hubKey, workerApiKey }

  const keys: SecretKeys = {
    hubKey: hubKey || generateSecret(),
    workerApiKey: workerApiKey || generateSecret(),
  }
  const missing = [
    !hubKey ? 'hubKey' : null,
    !workerApiKey ? 'workerApiKey' : null,
  ].filter((name): name is string => name !== null)
  try {
    mkdirSync(dirname(configFile), { recursive: true })
    // 先写临时文件再原子改名:中断不会留下截断的 desktop-config.json
    //(密钥决定 ownerKey 数据归属,配置文件损坏即数据"失联")。
    const tmp = `${configFile}.tmp`
    writeFileSync(tmp, JSON.stringify({ ...raw, ...keys }, null, 2) + '\n', 'utf8')
    renameSync(tmp, configFile)
    console.info(
      `[desktop] 检测到 ${missing.join('/')} 缺失,已自动生成随机密钥并持久化到 ${configFile}` +
        '(已有非空值保持不变;密钥决定数据归属,请勿随意删除或泄露)',
    )
  } catch (error) {
    console.error(
      `[desktop] 密钥已生成但回写 ${configFile} 失败,下次启动将重新生成(数据归属可能漂移,请检查目录可写性):`,
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
