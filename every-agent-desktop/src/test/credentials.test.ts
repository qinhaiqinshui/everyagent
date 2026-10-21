/**
 * 凭据 safeStorage 落盘迁移单测(node:test,零额外依赖):
 * - SecretCodec 契约与 PLAINTEXT_CODEC 降级(恒等编解码、密文形态拒绝解码);
 * - resolveSecret 三分支:生成 / 明文一次性迁移 / 密文沿用与损坏重生成
 *   (含 codec 不可用时 encoded===stored → changed:false 的升级兼容关键修正);
 * - ensureSecretKeys:密文落盘、明文迁移保留字段、无变化不重写、部分损坏只重生成该键;
 * - 下游接线(writeWorkerOverrideYml)拿到的是解码后的明文,而非 'safe:' 密文形态。
 * 真实 safeStorage 绑定 electron 运行时,无法在 node:test 进程内使用,故用 XOR
 * fakeCodec 模拟同形状的可逆编解码(safe: 前缀 + base64 + 往返校验,篡改即抛错)。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  PLAINTEXT_CODEC,
  SAFE_PREFIX,
  ensureSecretKeys,
  resolveSecret,
  writeWorkerOverrideYml,
  type SecretCodec,
} from '../main/credentials'
import type { DesktopConfig } from '../main/config'

/** 随机密钥形状:32 字节 base64url = 43 字符,[A-Za-z0-9_-]。 */
const SECRET_RE = /^[A-Za-z0-9_-]{43}$/

/** 捕获 console.warn 输出(结束时须 restore);resolveSecret 的分支告警经它断言。 */
function captureWarn(): { messages: string[]; restore(): void } {
  const original = console.warn
  const messages: string[] = []
  console.warn = (...args: unknown[]) => {
    messages.push(args.map((a) => (typeof a === 'string' ? a : String(a))).join(' '))
  }
  return { messages, restore: () => { console.warn = original } }
}

const XOR_KEY = 0x5a
function xorBuf(buf: Buffer): Buffer {
  return Buffer.from(buf.map((b) => b ^ XOR_KEY))
}

/**
 * XOR fakeCodec:与真实 safeStorage 形状一致——encode 加 'safe:' 前缀 + 追加校验和
 * 字节(sum&0xff,模拟 DPAPI 的完整性校验),decode 校验失败(篡改/损坏)即抛错。
 * 无校验和时 XOR 逐字节双射会把篡改密文解成 garbage 明文而非报错,测不出损坏路径。
 */
const fakeCodec: SecretCodec = {
  name: 'fake-xor',
  isAvailable: () => true,
  encode(plain: string): string {
    const cipher = xorBuf(Buffer.from(plain, 'utf8'))
    const checksum = cipher.reduce((acc, b) => (acc + b) & 0xff, 0)
    return SAFE_PREFIX + Buffer.concat([cipher, Buffer.from([checksum])]).toString('base64')
  },
  decode(stored: string): string {
    if (!stored.startsWith(SAFE_PREFIX)) throw new Error('非密文形态(缺 safe: 前缀)')
    const raw = Buffer.from(stored.slice(SAFE_PREFIX.length), 'base64')
    if (raw.length === 0) throw new Error('密文长度非法')
    const cipher = raw.subarray(0, -1)
    const checksum = raw[raw.length - 1]
    const expected = cipher.reduce((acc, b) => (acc + b) & 0xff, 0)
    if (checksum !== expected) throw new Error('密文校验失败(损坏或被篡改)')
    return xorBuf(cipher).toString('utf8')
  },
}

/** 确定性篡改密文:替换 SAFE_PREFIX 后第一个 base64 字符(解码字节必变 → 往返校验必败)。 */
function tamper(encoded: string): string {
  const at = SAFE_PREFIX.length
  const flipped = encoded[at] === 'A' ? 'B' : 'A'
  return encoded.slice(0, at) + flipped + encoded.slice(at + 1)
}

function tmpHome(): string {
  return mkdtempSync(join(tmpdir(), 'ea-credentials-test-'))
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

test('PLAINTEXT_CODEC 契约:encode 恒等、decode 明文原样、safe: 前缀一律抛错', () => {
  assert.ok(PLAINTEXT_CODEC.isAvailable())
  assert.equal(PLAINTEXT_CODEC.encode('abc'), 'abc')
  assert.equal(PLAINTEXT_CODEC.decode('abc'), 'abc')
  assert.throws(() => PLAINTEXT_CODEC.decode('safe:cipher'), /无法解密/)
})

test('fakeCodec:encode 产 safe: 密文且不含明文,decode 往返还原', () => {
  const encoded = fakeCodec.encode('my-plain-secret')
  assert.ok(encoded.startsWith(SAFE_PREFIX), '密文形态必须带 safe: 前缀')
  assert.ok(!encoded.includes('my-plain-secret'), '密文不得包含明文子串')
  assert.equal(fakeCodec.decode(encoded), 'my-plain-secret')
})

test('fakeCodec:decode 被篡改的密文抛错(支撑"密文损坏重生成"路径)', () => {
  const encoded = fakeCodec.encode('my-plain-secret')
  assert.throws(() => fakeCodec.decode(tamper(encoded)), /校验失败|损坏/)
})

test('resolveSecret:空值生成随机密钥并按 codec 编码落盘(generated/changed)', () => {
  const r = resolveSecret('', 'hubKey', fakeCodec)
  assert.match(r.value, SECRET_RE)
  assert.equal(r.encoded, fakeCodec.encode(r.value))
  assert.equal(r.changed, true)
  assert.equal(r.reason, 'generated')
})

test('resolveSecret:密文形态解密成功则原样沿用(kept/不重写)', () => {
  const stored = fakeCodec.encode('existing-secret')
  const r = resolveSecret(stored, 'hubKey', fakeCodec)
  assert.equal(r.value, 'existing-secret')
  assert.equal(r.encoded, stored)
  assert.equal(r.changed, false)
  assert.equal(r.reason, 'kept')
})

test('resolveSecret:密文损坏(解密抛错)重新生成并告警 ownerKey 漂移', () => {
  const warn = captureWarn()
  try {
    const r = resolveSecret(tamper(fakeCodec.encode('old-secret')), 'hubKey', fakeCodec)
    assert.match(r.value, SECRET_RE)
    assert.notEqual(r.value, 'old-secret')
    assert.equal(r.changed, true)
    assert.equal(r.reason, 'regenerated')
    assert.ok(
      warn.messages.some((m) => m.includes('hubKey') && m.includes('重新生成')),
      '应告警密文解密失败并重新生成',
    )
  } finally {
    warn.restore()
  }
})

test('resolveSecret:旧版明文一次性迁移为密文,明文值不变(migrated)', () => {
  const warn = captureWarn()
  try {
    const r = resolveSecret('legacy-plain-key', 'hubKey', fakeCodec)
    assert.equal(r.value, 'legacy-plain-key', '迁移不改变明文值,数据归属不漂移')
    assert.equal(r.encoded, fakeCodec.encode('legacy-plain-key'))
    assert.ok(r.encoded.startsWith(SAFE_PREFIX))
    assert.equal(r.changed, true)
    assert.equal(r.reason, 'migrated')
    assert.ok(warn.messages.some((m) => m.includes('一次性迁移')))
  } finally {
    warn.restore()
  }
})

test('resolveSecret:旧明文 + 明文降级 codec → encoded===stored,changed:false(升级兼容关键修正)', () => {
  const r = resolveSecret('legacy-plain-key', 'hubKey', PLAINTEXT_CODEC)
  assert.equal(r.value, 'legacy-plain-key')
  assert.equal(r.encoded, 'legacy-plain-key', '明文降级 encode 恒等')
  assert.equal(r.changed, false, 'encoded 与 stored 相同 → 不触发文件重写')
  assert.equal(r.reason, 'kept')
})

test('resolveSecret:密文 + 明文降级 codec(凭据库不可用)→ 解密失败重生成并告警', () => {
  const warn = captureWarn()
  try {
    const stored = fakeCodec.encode('cipher-from-other-machine')
    const r = resolveSecret(stored, 'workerApiKey', PLAINTEXT_CODEC)
    assert.match(r.value, SECRET_RE)
    assert.equal(r.changed, true)
    assert.equal(r.reason, 'regenerated')
    assert.equal(r.encoded, r.value, '明文降级落盘即明文值')
    assert.ok(warn.messages.some((m) => m.includes('重新生成')))
  } finally {
    warn.restore()
  }
})

test('ensureSecretKeys:首启落盘为 safe: 密文,文件不含明文,内存值为明文', () => {
  const home = tmpHome()
  try {
    const file = join(home, 'desktop-config.json')
    const keys = ensureSecretKeys({}, file, fakeCodec)
    const text = readFileSync(file, 'utf8')
    assert.ok(keys.hubKey.startsWith(SAFE_PREFIX) === false, '内存值应为明文')
    assert.match(keys.hubKey, SECRET_RE)
    assert.ok(text.includes(fakeCodec.encode(keys.hubKey)), '文件中应出现 hubKey 密文形态')
    assert.ok(!text.includes(keys.hubKey), '文件中不得出现明文 hubKey')
    const persisted = JSON.parse(text)
    assert.equal(fakeCodec.decode(persisted.workerApiKey), keys.workerApiKey, 'workerApiKey 密文可解回内存值')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('ensureSecretKeys:旧明文一次性迁移为密文,保留其他字段,明文值不变', () => {
  const home = tmpHome()
  try {
    const file = join(home, 'desktop-config.json')
    writeFileSync(file, JSON.stringify({ hubKey: 'legacy-hub', workerApiKey: 'legacy-worker', workerId: 'pc-7' }), 'utf8')
    const keys = ensureSecretKeys(JSON.parse(readFileSync(file, 'utf8')), file, fakeCodec)
    assert.equal(keys.hubKey, 'legacy-hub', '迁移不改变明文值(数据归属不漂移)')
    assert.equal(keys.workerApiKey, 'legacy-worker')
    const persisted = JSON.parse(readFileSync(file, 'utf8'))
    assert.ok(persisted.hubKey.startsWith(SAFE_PREFIX))
    assert.ok(persisted.workerApiKey.startsWith(SAFE_PREFIX))
    assert.equal(fakeCodec.decode(persisted.hubKey), 'legacy-hub')
    assert.equal(fakeCodec.decode(persisted.workerApiKey), 'legacy-worker')
    assert.equal(persisted.workerId, 'pc-7', '回写保留用户既有字段')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('ensureSecretKeys:密文完好时二次加载幂等,不重写文件(字节与 mtime 不变)', async () => {
  const home = tmpHome()
  try {
    const file = join(home, 'desktop-config.json')
    ensureSecretKeys({}, file, fakeCodec)
    const before = readFileSync(file, 'utf8')
    const mtime = statSync(file).mtimeMs
    await new Promise((r) => setTimeout(r, 30))
    const warn = captureWarn()
    let keys
    try {
      keys = ensureSecretKeys(JSON.parse(readFileSync(file, 'utf8')), file, fakeCodec)
    } finally {
      warn.restore()
    }
    assert.equal(readFileSync(file, 'utf8'), before, '密文完好时文件不得重写')
    assert.equal(statSync(file).mtimeMs, mtime)
    const persisted = JSON.parse(before)
    assert.equal(keys.hubKey, fakeCodec.decode(persisted.hubKey), '内存值与密文解密结果一致')
    assert.deepEqual(warn.messages, [], '无告警')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('ensureSecretKeys:仅 hubKey 密文损坏时只重生成该键,workerApiKey 原样保留', () => {
  const home = tmpHome()
  try {
    const file = join(home, 'desktop-config.json')
    const raw = {
      hubKey: tamper(fakeCodec.encode('dead-hub-key')),
      workerApiKey: fakeCodec.encode('alive-worker-key'),
      workerId: 'pc-3',
    }
    writeFileSync(file, JSON.stringify(raw), 'utf8')
    const keys = ensureSecretKeys(raw, file, fakeCodec)
    assert.match(keys.hubKey, SECRET_RE)
    assert.notEqual(keys.hubKey, 'dead-hub-key', '损坏密钥不得沿用其明文')
    assert.equal(keys.workerApiKey, 'alive-worker-key', '完好密钥明文不变')
    const persisted = JSON.parse(readFileSync(file, 'utf8'))
    assert.ok(persisted.hubKey.startsWith(SAFE_PREFIX))
    assert.equal(persisted.workerApiKey, raw.workerApiKey, '完好密钥密文原样保留')
    assert.equal(fakeCodec.decode(persisted.hubKey), keys.hubKey)
    assert.equal(persisted.workerId, 'pc-3')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('ensureSecretKeys:明文降级 codec 下既有明文配置不写文件(旧"文件不重写"语义)', () => {
  const home = tmpHome()
  try {
    const file = join(home, 'desktop-config.json')
    const keys = ensureSecretKeys(
      { hubKey: 'a-key', workerApiKey: 'b-key' },
      file,
      PLAINTEXT_CODEC,
    )
    assert.deepEqual(keys, { hubKey: 'a-key', workerApiKey: 'b-key' })
    assert.equal(
      statSync(file, { throwIfNoEntry: false }),
      undefined,
      'encode 恒等 → changed:false → 不创建/不重写文件',
    )
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('ensureSecretKeys:密文配置经明文降级 codec 整体重生成并明文落盘', () => {
  const home = tmpHome()
  try {
    const file = join(home, 'desktop-config.json')
    const raw = { hubKey: fakeCodec.encode('x'), workerApiKey: fakeCodec.encode('y') }
    const keys = ensureSecretKeys(raw, file, PLAINTEXT_CODEC)
    const persisted = JSON.parse(readFileSync(file, 'utf8'))
    assert.match(keys.hubKey, SECRET_RE)
    assert.notEqual(persisted.hubKey, raw.hubKey, '旧密文被替换为重生成值')
    assert.equal(persisted.hubKey, keys.hubKey, '明文降级落盘即明文值')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('writeWorkerOverrideYml:接线用的是解码后的明文,yml 不含 safe: 形态', () => {
  const home = tmpHome()
  try {
    // 模拟从密文配置解出的明文密钥走下游接线
    const keys = ensureSecretKeys({}, join(home, 'desktop-config.json'), fakeCodec)
    writeWorkerOverrideYml(baseCfg({ hubKey: keys.hubKey, workerApiKey: keys.workerApiKey }), home)
    const yml = readFileSync(join(home, 'desktop', 'desktop-worker.yml'), 'utf8')
    assert.ok(yml.includes(`api-key: ${JSON.stringify(keys.workerApiKey)}`))
    assert.ok(yml.includes(`hub-key: ${JSON.stringify(keys.hubKey)}`))
    assert.ok(!yml.includes(SAFE_PREFIX), 'worker 接线文件不得出现密文形态(其无法解密)')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})
