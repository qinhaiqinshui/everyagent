/**
 * 配置与凭据单测(node:test,零额外依赖):
 * - 首启(无配置/密钥为空)自动生成随机 hubKey/workerApiKey 并持久化;
 * - 已有合法配置升级:密钥保持不变、文件不被重写;
 * - hub/worker 子进程凭据接线(HUB_KEY env、desktop-worker.yml)。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { bootstrapFor, loadConfig, type DesktopConfig } from '../main/config'
import {
  buildBackendEnv,
  ensureSecretKeys,
  workerOverrideYmlPath,
  writeWorkerOverrideYml,
} from '../main/credentials'

/** 随机密钥形状:32 字节 base64url = 43 字符,[A-Za-z0-9_-]。 */
const SECRET_RE = /^[A-Za-z0-9_-]{43}$/

function tmpHome(): string {
  return mkdtempSync(join(tmpdir(), 'ea-desktop-test-'))
}

function baseCfg(overrides: Partial<DesktopConfig> = {}): DesktopConfig {
  return {
    hubKey: 'hub-key-x',
    workerApiKey: 'worker-key-x',
    workerId: 'company-pc',
    hubPort: 6101,
    workerPort: 6102,
    gpuWorkaround: true,
    ...overrides,
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

test('首启(desktop-config.json 不存在):自动生成随机密钥并持久化', () => {
  const home = tmpHome()
  try {
    const cfg = loadConfig(home)
    assert.match(cfg.hubKey, SECRET_RE)
    assert.match(cfg.workerApiKey, SECRET_RE)
    assert.notEqual(cfg.hubKey, cfg.workerApiKey, '两个密钥应各自独立生成')
    // 持久化到 desktop-config.json,值与返回值一致
    const persisted = JSON.parse(readFileSync(join(home, 'desktop-config.json'), 'utf8'))
    assert.equal(persisted.hubKey, cfg.hubKey)
    assert.equal(persisted.workerApiKey, cfg.workerApiKey)
    // 其余字段保持默认
    assert.equal(cfg.workerId, 'company-pc')
    assert.equal(cfg.hubPort, 6101)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('二次加载沿用已生成密钥,不重新生成', () => {
  const home = tmpHome()
  try {
    const first = loadConfig(home)
    const second = loadConfig(home)
    assert.equal(second.hubKey, first.hubKey)
    assert.equal(second.workerApiKey, first.workerApiKey)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('升级场景:已有合法配置(两密钥非空)值保持不变,文件不被重写', async () => {
  const home = tmpHome()
  try {
    const file = join(home, 'desktop-config.json')
    const original =
      '{\n  "hubKey": "my-hub-key",\n  "workerApiKey": "my-worker-key",\n' +
      '  "workerId": "pc-1",\n  "hubPort": 7777\n}\n'
    writeFileSync(file, original, 'utf8')
    const before = statSync(file).mtimeMs
    await sleep(30)
    const cfg = loadConfig(home)
    assert.equal(cfg.hubKey, 'my-hub-key')
    assert.equal(cfg.workerApiKey, 'my-worker-key')
    assert.equal(cfg.workerId, 'pc-1')
    assert.equal(cfg.hubPort, 7777)
    // 未重写:内容逐字节不变 + mtime 未变
    assert.equal(readFileSync(file, 'utf8'), original)
    assert.equal(statSync(file).mtimeMs, before)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('部分缺失(仅 workerApiKey 为空):只补缺失项,保留已有值与其他字段', () => {
  const home = tmpHome()
  try {
    const file = join(home, 'desktop-config.json')
    writeFileSync(
      file,
      JSON.stringify({ hubKey: 'keep-me', workerApiKey: '', workerId: 'pc-9' }),
      'utf8',
    )
    const cfg = loadConfig(home)
    assert.equal(cfg.hubKey, 'keep-me', '已有非空 hubKey 不应重新生成')
    assert.match(cfg.workerApiKey, SECRET_RE)
    const persisted = JSON.parse(readFileSync(file, 'utf8'))
    assert.equal(persisted.hubKey, 'keep-me')
    assert.equal(persisted.workerApiKey, cfg.workerApiKey)
    assert.equal(persisted.workerId, 'pc-9', '回写应保留用户既有字段')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('配置文件损坏(非法 JSON):生成新密钥并回写覆盖为合法配置', () => {
  const home = tmpHome()
  try {
    const file = join(home, 'desktop-config.json')
    writeFileSync(file, '{broken json!!', 'utf8')
    const cfg = loadConfig(home)
    assert.match(cfg.hubKey, SECRET_RE)
    assert.match(cfg.workerApiKey, SECRET_RE)
    const persisted = JSON.parse(readFileSync(file, 'utf8'))
    assert.equal(persisted.hubKey, cfg.hubKey)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('bootstrapFor 透传配置密钥(前端连接用)', () => {
  const cfg = baseCfg()
  const boot = bootstrapFor(cfg)
  assert.equal(boot.hubKey, cfg.hubKey)
  assert.equal(boot.workerApiKey, cfg.workerApiKey)
  assert.equal(boot.hubUrl, `ws://127.0.0.1:${cfg.hubPort}/ws`)
})

test('ensureSecretKeys:两密钥均已非空时不写文件', () => {
  const home = tmpHome()
  try {
    const keys = ensureSecretKeys(
      { hubKey: 'a-key', workerApiKey: 'b-key' },
      join(home, 'desktop-config.json'),
    )
    assert.deepEqual(keys, { hubKey: 'a-key', workerApiKey: 'b-key' })
    // 未生成配置文件(密钥无缺失时不回写)
    assert.equal(statSync(join(home, 'desktop-config.json'), { throwIfNoEntry: false }), undefined)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('buildBackendEnv:注入 EVERYAGENT_HOME 与 HUB_KEY,且覆盖继承的 HUB_KEY', () => {
  const home = tmpHome()
  const inherited = process.env.HUB_KEY
  process.env.HUB_KEY = 'inherited-secret'
  try {
    const env = buildBackendEnv(baseCfg({ hubKey: 'generated-hub-key' }), home)
    assert.equal(env.HUB_KEY, 'generated-hub-key', 'env 中的 HUB_KEY 必须来自配置(覆盖任何继承)')
    assert.equal(env.EVERYAGENT_HOME, home)
  } finally {
    if (inherited === undefined) delete process.env.HUB_KEY
    else process.env.HUB_KEY = inherited
  }
})

test('buildBackendEnv:hubKey 为空时拒绝启动(绝不落回 jar 内公开默认密钥)', () => {
  assert.throws(() => buildBackendEnv(baseCfg({ hubKey: '' }), tmpHome()), /hubKey 为空/)
})

test('writeWorkerOverrideYml:生成与配置一致的凭据文件并随配置刷新', () => {
  const home = tmpHome()
  try {
    const cfg = baseCfg({ hubKey: 'H1', workerApiKey: 'W1', hubPort: 6101 })
    const url = writeWorkerOverrideYml(cfg, home)
    assert.match(url, /^file:\/\/\/{1,3}/, '返回 --spring.config.additional-location 可用的 file: URL')
    const text = readFileSync(workerOverrideYmlPath(home), 'utf8')
    assert.ok(text.includes('url: "ws://127.0.0.1:6101/ws"'))
    assert.ok(text.includes('api-key: "W1"'))
    assert.ok(text.includes('hub-key: "H1"'), 'hub-key 必须与 hub 一致')

    // 配置变化后重写,内容随之刷新(端口与凭据同步)
    writeWorkerOverrideYml(baseCfg({ hubKey: 'H2', workerApiKey: 'W2', hubPort: 7001 }), home)
    const text2 = readFileSync(workerOverrideYmlPath(home), 'utf8')
    assert.ok(text2.includes('url: "ws://127.0.0.1:7001/ws"'))
    assert.ok(text2.includes('api-key: "W2"'))
    assert.ok(text2.includes('hub-key: "H2"'))
    assert.ok(!text2.includes('"W1"'), '旧密钥不得残留')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('writeWorkerOverrideYml:密钥为空时拒绝生成', () => {
  assert.throws(() => writeWorkerOverrideYml(baseCfg({ workerApiKey: '' }), tmpHome()), /为空/)
})
