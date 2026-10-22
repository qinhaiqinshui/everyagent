/**
 * 凭据 safeStorage 落盘迁移单测(node:test,零额外依赖):
 * - SecretCodec 契约与 PLAINTEXT_CODEC 降级(恒等编解码、密文形态拒绝解码);
 * - resolveSecret 三分支:生成 / 明文一次性迁移 / 密文沿用与损坏重生成
 *   (含 codec 不可用时 encoded===stored → changed:false 的升级兼容关键修正);
 * - ensureSecretKeys:密文落盘、明文迁移保留字段、无变化不重写、部分损坏只重生成该键;
 * - 下游接线(writeWorkerOverrideYml)拿到的是解码后的明文,而非 'safe:' 密文形态;
 * - writeWorkerOverrideYml 合并语义:用户 application-worker.yaml 的远程 hub 条目
 *   原序追加(hubs[0] 恒为本机)、回环条目剔除、${HUB_KEY:默认值} 占位符还原、
 *   结构未识别退回仅本机并告警。
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
  isLoopbackWsUrl,
  materializeHubKeyPlaceholder,
  parseUserWorkerHubs,
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

/** 生成用户级 application-worker.yaml 到 home(模拟真实文件:注释、引号、占位符、models 兄弟块)。 */
function writeUserWorkerYml(home: string, hubsBlock: string): void {
  writeFileSync(
    join(home, 'application-worker.yaml'),
    [
      '# 用户配置(注释应被忽略,包括行内注释)',
      'worker:',
      '  hubs:',
      ...hubsBlock.split('\n'),
      '  models:',
      '    - config-id: "池"',
      '      provider: model-pool',
      '      model: "a,b"',
      '      is-default: true',
      '    - config-id: "智谱"',
      '      provider: zai',
      '      base-url: "https://open.bigmodel.cn/api"',
      '      params:',
      '        reasoningEffort: high',
      '        contextWindowTokens: 1000000',
    ].join('\n') + '\n',
    'utf8',
  )
}

test('parseUserWorkerHubs:提取 hubs 条目(含引号/行内注释/占位符),跳过 models 兄弟块', () => {
  const text = [
    '# 头部注释',
    'worker:',
    '  hubs:',
    "    - url: ws://localhost:6101/ws   # 行内注释",
    '      api-key: dev-key',
    '      hub-key: ${HUB_KEY:sljlw23948LKS}',
    '    - url: "ws://120.25.206.241:6101/ws"',
    `      api-key: "worksdls*239sdfj # 引号内的#不是注释"`,
    "      hub-key: '${HUB_KEY:HubLslwoo2452}'",
    '  models:',
    '    - config-id: "池"',
    '      provider: model-pool',
    '      params:',
    '        reasoningEffort: high',
  ].join('\n')
  const hubs = parseUserWorkerHubs(text)
  assert.ok(hubs, '应解析成功')
  assert.equal(hubs.length, 2)
  assert.equal(hubs[0].url, 'ws://localhost:6101/ws')
  assert.equal(hubs[0].apiKey, 'dev-key')
  assert.equal(hubs[0].hubKey, '${HUB_KEY:sljlw23948LKS}')
  assert.equal(hubs[1].url, 'ws://120.25.206.241:6101/ws')
  assert.equal(hubs[1].apiKey, 'worksdls*239sdfj # 引号内的#不是注释')
  assert.equal(hubs[1].hubKey, '${HUB_KEY:HubLslwoo2452}')
})

test('parseUserWorkerHubs:hubs 值非块式列表(flow 形态)→ null(退回仅本机,不猜结构)', () => {
  assert.equal(parseUserWorkerHubs('worker:\n  hubs: {url: x}\n'), null)
  assert.equal(parseUserWorkerHubs('worker:\n  hubs: []\n'), null, 'flow 空列表同样未识别(告警,不静默覆盖用户意图)')
  assert.equal(parseUserWorkerHubs('worker:\n  hubs:\n    ???\n'), null)
  assert.deepEqual(parseUserWorkerHubs(''), [], '无 worker 块 = 无用户 hub 条目')
})

test('isLoopbackWsUrl:回环各形态判定;非回环/不可解析为 false', () => {
  assert.ok(isLoopbackWsUrl('ws://localhost:6101/ws'))
  assert.ok(isLoopbackWsUrl('ws://127.0.0.1:6101/ws'))
  assert.ok(isLoopbackWsUrl('ws://[::1]:6101/ws'))
  assert.ok(isLoopbackWsUrl('ws://0.0.0.0:6101/ws'))
  assert.ok(!isLoopbackWsUrl('ws://120.25.206.241:6101/ws'))
  assert.ok(!isLoopbackWsUrl('wss://example.com/ws'))
  assert.ok(!isLoopbackWsUrl('not a url'))
})

test('materializeHubKeyPlaceholder:${HUB_KEY:默认值} → 默认值;无默认值保持原样', () => {
  assert.equal(materializeHubKeyPlaceholder('${HUB_KEY:HubLslwoo2452}'), 'HubLslwoo2452')
  assert.equal(materializeHubKeyPlaceholder('${ HUB_KEY : sk-x }'), 'sk-x')
  assert.equal(materializeHubKeyPlaceholder('${HUB_KEY}'), '${HUB_KEY}', '无默认值=取 env,无可还原值,原样透传')
  assert.equal(materializeHubKeyPlaceholder('${OTHER_VAR:a}'), '${OTHER_VAR:a}', '非 HUB_KEY 占位符不动')
  assert.equal(materializeHubKeyPlaceholder('plain-value'), 'plain-value')
})

test('writeWorkerOverrideYml:合并用户远程 hub(占位符还原、回环剔除、本机恒为 hubs[0])', () => {
  const home = tmpHome()
  try {
    writeUserWorkerYml(
      home,
      [
        '    - url: ws://localhost:6101/ws',
        '      api-key: dev-key',
        '      hub-key: ${HUB_KEY:sljlw23948LKS}',
        '    - url: ws://120.25.206.241:6101/ws',
        '      api-key: worksdls*239sdfj',
        '      hub-key: ${HUB_KEY:HubLslwoo2452}',
      ].join('\n'),
    )
    writeWorkerOverrideYml(baseCfg(), home)
    const yml = readFileSync(join(home, 'desktop', 'desktop-worker.yml'), 'utf8')
    const entries = yml.split('\n').filter((l) => l.startsWith('    - url:'))
    assert.equal(entries.length, 2, '本机 1 条 + 用户远程 1 条')
    assert.ok(
      entries[0].includes(`ws://127.0.0.1:6101/ws`),
      'hubs[0] 恒为本机条目(/admin 认证与 hubs[0].api-key 比对)',
    )
    assert.ok(entries[1].includes('ws://120.25.206.241:6101/ws'), '远程条目原序追加')
    assert.ok(yml.includes(`"worksdls*239sdfj"`), '远程 api-key 原样透传')
    assert.ok(yml.includes(`"HubLslwoo2452"`), '${HUB_KEY:默认值} 还原为字面默认值')
    const valueLines = yml.split('\n').filter((l) => !l.trimStart().startsWith('#'))
    assert.ok(
      !valueLines.some((l) => l.includes('${HUB_KEY')),
      '值行不得残留 HUB_KEY 占位符(env 值是本机密钥,会解析成错误凭据)',
    )
    assert.ok(!yml.includes('dev-key') && !yml.includes('sljlw23948LKS'), '用户回环条目整体剔除(防凭据错配)')
    assert.ok(yml.indexOf('ws://127.0.0.1:6101/ws') < yml.indexOf('ws://120.25.206.241:6101/ws'), '本机在前')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('writeWorkerOverrideYml:用户文件仅回环条目 → 输出仅本机一条(不重复)', () => {
  const home = tmpHome()
  try {
    writeUserWorkerYml(home, '    - url: ws://127.0.0.1:6101/ws\n      api-key: a\n      hub-key: b')
    writeWorkerOverrideYml(baseCfg(), home)
    const yml = readFileSync(join(home, 'desktop', 'desktop-worker.yml'), 'utf8')
    assert.equal(yml.split('\n').filter((l) => l.startsWith('    - url:')).length, 1)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('writeWorkerOverrideYml:用户文件结构未识别 → 退回仅本机并告警', () => {
  const home = tmpHome()
  const warn = captureWarn()
  try {
    writeFileSync(join(home, 'application-worker.yaml'), 'worker:\n  hubs:\n      ???\n', 'utf8')
    writeWorkerOverrideYml(baseCfg(), home)
    const yml = readFileSync(join(home, 'desktop', 'desktop-worker.yml'), 'utf8')
    assert.equal(yml.split('\n').filter((l) => l.startsWith('    - url:')).length, 1, '退回仅本机条目')
    assert.ok(warn.messages.some((m) => m.includes('未识别')), '应经 warn 告警解析失败')
  } finally {
    warn.restore()
    rmSync(home, { recursive: true, force: true })
  }
})

test('writeWorkerOverrideYml:无用户配置文件 → 行为与旧版一致(仅本机一条,无告警)', () => {
  const home = tmpHome()
  const warn = captureWarn()
  try {
    writeWorkerOverrideYml(baseCfg({ hubPort: 7101 }), home)
    const yml = readFileSync(join(home, 'desktop', 'desktop-worker.yml'), 'utf8')
    assert.ok(yml.includes('"ws://127.0.0.1:7101/ws"'))
    assert.equal(yml.split('\n').filter((l) => l.startsWith('    - url:')).length, 1)
    assert.deepEqual(warn.messages, [])
  } finally {
    warn.restore()
    rmSync(home, { recursive: true, force: true })
  }
})
