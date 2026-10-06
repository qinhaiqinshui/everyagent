/**
 * lib/zip.mjs —— `pack` 子命令：把插件工程打成 `.eap` 安装包（零依赖 zip，计划步骤 7）。
 *
 * zip 格式（node:zlib 手写，不引 jszip / archiver）：
 *   - 每个文件一条 local file header（0x04034b50）+ 数据，末尾 central directory（0x02014b50）
 *     + EOCD（0x06054b50）；不写目录条目（worker 端 PluginRpcMethods.extractEap 按条目流解包，不需要）。
 *   - 压缩：DEFLATE（zlib.deflateRawSync，level 6）；压缩后不小于原文则回退 store（method 0）。
 *   - 文件名一律 UTF-8，通用标志 bit 11（0x0800）置位——中文路径安全。
 *   - CRC32 查表法自实现；条目路径一律 `/` 分隔；DOS 时间取本地当前时间。
 *   - 不做 zip64：单条目/整包须 < 4 GiB、条目数 < 65536，超出直接报错（插件包是 KB 级，碰不到）。
 *
 * .eap 布局（与 worker 端解包约定逐字对齐，PluginRpcMethods.extractEap）：
 *   <pluginId>/plugin.json                     恒有
 *   <pluginId>/lib/*.jar                       清单含 main（java/full 形态）：target/ 下非 sources/javadoc jar 全收
 *   <pluginId>/web/index.js|index.css|*.map    清单含 webMain（web/full 形态）：web/ 递归收集
 * 顶层目录名必须 = plugin.json 的 id：worker 取「第一个带 / 的条目」首段当 pluginId；条目全无 /
 * 时回退用 zip 文件名去 .eap 并把文件摊进 plugins 根（扫描器发现不了）——pack 恒产出 <id>/ 前缀，
 * 不会出现这两种坑。
 *
 * 校验和旁文件（known-issues #21 最小方案）：pack 同时产出 <id>-<version>.eap.sha256，内容为一行
 * sha256sum 兼容格式「<64 位小写十六进制摘要>␣␣<.eap 文件名>」（两个空格 + 换行），对 .eap 全文件
 * 计算；--verify 会重读盘上 .eap 复算摘要并与旁文件核对。安装侧（worker extractEap）目前不消费它。
 *
 * 退出码：1 用法（缺 pluginDir、未知 flag）；2 校验（目录不存在 / 缺 plugin.json / 坏 JSON /
 * 缺 id、version / 缺构建产物）；4 写盘失败与 --verify 自检不过。
 */

import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import zlib from 'node:zlib'
import { EXIT, UsageError, ValidationError, WriteError } from './errors.mjs'

/** 子命令是否已可用（步骤 7 已落地）。 */
export const PACK_IMPLEMENTED = true

/* ------------------------------------------------------------------ *
 * zip 二进制基础件（写侧）
 * ------------------------------------------------------------------ */

const SIG_LOCAL = 0x04034b50
const SIG_CENTRAL = 0x02014b50
const SIG_EOCD = 0x06054b50
const METHOD_STORE = 0
const METHOD_DEFLATE = 8
const FLAG_UTF8 = 0x0800
const VERSION_ZIP20 = 20

const CRC_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c >>> 0
  }
  return table
})()

/** CRC-32（IEEE 802.3 多项式，查表法）。 */
export function crc32(buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** 本地当前时间 → DOS date/time（zip 的时间戳；1980 年以前钳到 1980-01-01）。 */
function dosDateTime(now = new Date()) {
  const year = Math.min(Math.max(now.getFullYear(), 1980), 2107)
  const date = ((year - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate()
  const time = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1)
  return { date, time }
}

function u16(v) {
  const b = Buffer.alloc(2)
  b.writeUInt16LE(v & 0xffff, 0)
  return b
}

function u32(v) {
  const b = Buffer.alloc(4)
  b.writeUInt32LE(v >>> 0, 0)
  return b
}

/**
 * 把条目打成完整 zip 字节流（local headers + central directory + EOCD）。
 * 条目可带预计算的 comp/method/crc（formatTree 也要用，避免重复压缩）；缺省时在此计算。
 * @param {{ name: string, data: Buffer, comp?: Buffer, method?: number, crc?: number }[]} entries
 * @returns {Buffer}
 */
export function buildZip(entries) {
  if (entries.length > 0xffff) {
    throw new WriteError(`条目数超出无 zip64 上限（65536）：${entries.length}`)
  }
  const { date, time } = dosDateTime()
  const localParts = []
  const centralParts = []
  let offset = 0
  for (const entry of entries) {
    const nameBuf = Buffer.from(entry.name, 'utf8')
    const raw = entry.data
    let method = entry.method
    let comp = entry.comp
    let crc = entry.crc
    if (comp === undefined || method === undefined || crc === undefined) {
      method = METHOD_DEFLATE
      comp = zlib.deflateRawSync(raw, { level: 6 })
      if (comp.length >= raw.length) {
        method = METHOD_STORE
        comp = raw
      }
      crc = crc32(raw)
      entry.method = method
      entry.comp = comp
      entry.crc = crc
    }
    if (raw.length >= 0xffffffff || comp.length >= 0xffffffff) {
      throw new WriteError(`单条目超出无 zip64 上限（4 GiB）：${entry.name}`)
    }
    const localHeader = Buffer.concat([
      u32(SIG_LOCAL),
      u16(VERSION_ZIP20),
      u16(FLAG_UTF8),
      u16(method),
      u16(time),
      u16(date),
      u32(crc),
      u32(comp.length),
      u32(raw.length),
      u16(nameBuf.length),
      u16(0),
    ])
    const centralHeader = Buffer.concat([
      u32(SIG_CENTRAL),
      u16(VERSION_ZIP20), // version made by（低字节=规范版本，高字节 0=MS-DOS）
      u16(VERSION_ZIP20), // version needed
      u16(FLAG_UTF8),
      u16(method),
      u16(time),
      u16(date),
      u32(crc),
      u32(comp.length),
      u32(raw.length),
      u16(nameBuf.length),
      u16(0), // extra 长度
      u16(0), // comment 长度
      u16(0), // 起始盘号
      u16(0), // 内部属性
      u32(0o100644 << 16), // 外部属性：普通文件 0644
      u32(offset),
      nameBuf,
    ])
    localParts.push(localHeader, nameBuf, comp)
    centralParts.push(centralHeader)
    offset += localHeader.length + nameBuf.length + comp.length
  }
  const central = Buffer.concat(centralParts)
  if (offset + central.length >= 0xffffffff) {
    throw new WriteError('整包超出无 zip64 上限（4 GiB）')
  }
  const eocd = Buffer.concat([
    u32(SIG_EOCD),
    u16(0),
    u16(0),
    u16(entries.length),
    u16(entries.length),
    u32(central.length),
    u32(offset),
    u16(0), // 注释长度
  ])
  return Buffer.concat([...localParts, central, eocd])
}

/* ------------------------------------------------------------------ *
 * zip 读侧（--verify 自证用；不依赖任何外部解压工具）
 * ------------------------------------------------------------------ */

/**
 * 从尾部定位 EOCD（允许最长 65535 字节注释）。
 * @returns {number} EOCD 偏移；找不到抛错。
 */
function findEocd(buf) {
  const minTail = 22
  if (buf.length < minTail) throw new Error('文件比 EOCD 还短，不是 zip')
  const stop = Math.max(0, buf.length - minTail - 0xffff)
  for (let i = buf.length - minTail; i >= stop; i--) {
    if (buf.readUInt32LE(i) === SIG_EOCD) return i
  }
  throw new Error('尾部找不到 EOCD 记录，不是合法 zip')
}

/**
 * 读回整个 zip：EOCD → central directory → 逐条 local header 定位数据并解压，
 * 解压大小与 CRC32 逐条核对（对不上直接抛错）。
 * @param {Buffer} buf
 * @returns {{ name: string, method: number, crc: number, csize: number, usize: number, data: Buffer }[]}
 */
export function readZip(buf) {
  const eocd = findEocd(buf)
  const count = buf.readUInt16LE(eocd + 10)
  let pos = buf.readUInt32LE(eocd + 16)
  const out = []
  for (let n = 0; n < count; n++) {
    if (pos + 46 > buf.length || buf.readUInt32LE(pos) !== SIG_CENTRAL) {
      throw new Error(`第 ${n + 1} 条中央目录记录签名不符（偏移 ${pos}）`)
    }
    const method = buf.readUInt16LE(pos + 10)
    const crc = buf.readUInt32LE(pos + 16)
    const csize = buf.readUInt32LE(pos + 20)
    const usize = buf.readUInt32LE(pos + 24)
    const nameLen = buf.readUInt16LE(pos + 28)
    const extraLen = buf.readUInt16LE(pos + 30)
    const commentLen = buf.readUInt16LE(pos + 32)
    const localOffset = buf.readUInt32LE(pos + 42)
    const name = buf.subarray(pos + 46, pos + 46 + nameLen).toString('utf8')
    pos += 46 + nameLen + extraLen + commentLen

    if (buf.readUInt32LE(localOffset) !== SIG_LOCAL) {
      throw new Error(`条目 ${name} 的 local file header 签名不符`)
    }
    const lNameLen = buf.readUInt16LE(localOffset + 26)
    const lExtraLen = buf.readUInt16LE(localOffset + 28)
    const dataStart = localOffset + 30 + lNameLen + lExtraLen
    const comp = buf.subarray(dataStart, dataStart + csize)
    let data
    if (method === METHOD_DEFLATE) data = zlib.inflateRawSync(comp)
    else if (method === METHOD_STORE) data = Buffer.from(comp)
    else throw new Error(`条目 ${name} 用了不支持的压缩方法 ${method}`)
    if (data.length !== usize) throw new Error(`条目 ${name} 解压大小不符（目录 ${usize}，实际 ${data.length}）`)
    const actual = crc32(data)
    if (actual !== crc) {
      throw new Error(`条目 ${name} CRC32 不符（目录 ${hex32(crc)}，实际 ${hex32(actual)}）`)
    }
    out.push({ name, method, crc, csize, usize, data })
  }
  return out
}

/* ------------------------------------------------------------------ *
 * pack 业务：清单 → 收集 → 打包 → 写盘 → 可选自检
 * ------------------------------------------------------------------ */

const SAFE_ID = /^[a-z0-9][a-z0-9-]{1,38}$/
const SAFE_VERSION = /^[A-Za-z0-9][A-Za-z0-9._+-]*$/
const PACK_USAGE = 'node create-everyagent-plugin pack <pluginDir> [-o <输出目录>] [--verify]'

/** pack 子命令自己的帮助文本（--help 原文输出）。 */
const PACK_HELP = `create-everyagent-plugin pack —— 把插件工程打成 .eap 安装包

用法:
  ${PACK_USAGE}

参数:
  pluginDir              插件工程根目录（含 plugin.json），唯一位置参数
  -o, --output <目录>    .eap 输出目录，缺省当前目录；目录不存在自动创建，同名文件直接覆盖
  --verify               打包后用 CLI 自带的 zip 读侧解回内存自检（条目树 + CRC32 + 顶层目录名 +
                         plugin.json 可解析），不调用任何外部解压工具
  -h, --help             显示本帮助

产物命名 <id>-<version>.eap（id/version 取自 plugin.json）；zip 顶层目录名 = pluginId，
内含 plugin.json +（清单含 main）lib/*.jar +（清单含 webMain）web/ 下的构建产物。
同时产出校验和旁文件 <id>-<version>.eap.sha256（sha256sum -c 兼容：一行摘要 + 两个空格 + 文件名）。
缺 jar / 缺 web/index.js 会报错并给出对应构建命令。
退出码: 1 用法错误 / 2 校验失败 / 4 写盘或自检失败
`

/** 读 <pluginDir>/plugin.json 并做最小校验（错误一律中文 + 区分退出码）。 */
function loadManifest(absPlugin) {
  if (!fs.existsSync(absPlugin) || !fs.statSync(absPlugin).isDirectory()) {
    throw new ValidationError(`插件目录不存在：${absPlugin}`, {
      hints: ['pack 的参数应是脚手架生成的插件工程根目录（含 plugin.json）', `用法：${PACK_USAGE}`],
    })
  }
  const manifestPath = path.join(absPlugin, 'plugin.json')
  if (!fs.existsSync(manifestPath) || !fs.statSync(manifestPath).isFile()) {
    throw new ValidationError(`不是插件工程目录，找不到 plugin.json：${manifestPath}`, {
      hints: ['pack 的参数应是脚手架生成的插件工程根目录（含 plugin.json）'],
    })
  }
  let manifest
  try {
    // 剥 UTF-8 BOM：Windows PowerShell 5.1 的 utf8 写盘带 BOM，JSON.parse 不认
    manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8').replace(/^\uFEFF/, ''))
  } catch (err) {
    throw new ValidationError(`plugin.json 不是合法 JSON：${manifestPath}`, {
      hints: [`解析错误：${String(err && err.message ? err.message : err).split('\n')[0]}`],
    })
  }
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    throw new ValidationError(`plugin.json 的根必须是 JSON 对象：${manifestPath}`)
  }
  if (typeof manifest.id !== 'string' || !manifest.id) {
    throw new ValidationError('plugin.json 缺少 id 字段（zip 顶层目录名与产物文件名都取自它）')
  }
  if (!SAFE_ID.test(manifest.id)) {
    throw new ValidationError(`plugin.json 的 id 非法：${manifest.id}`, {
      hints: ['id 限定 ^[a-z0-9][a-z0-9-]{1,38}$ 且首尾不能是连字符（与脚手架一致）'],
    })
  }
  if (manifest.version === undefined || manifest.version === null || manifest.version === '') {
    throw new ValidationError('plugin.json 缺少 version 字段（产物文件名为 <id>-<version>.eap）')
  }
  const version = String(manifest.version)
  if (!SAFE_VERSION.test(version)) {
    throw new ValidationError(`plugin.json 的 version 非法：${version}`, {
      hints: ['version 限定字母数字与 . _ + -（它要进产物文件名）'],
    })
  }
  const main = typeof manifest.main === 'string' ? manifest.main : ''
  const webMain = typeof manifest.webMain === 'string' ? manifest.webMain : ''
  if (!main && !webMain) {
    throw new ValidationError('plugin.json 既无 main 也无 webMain，无法判定打包形态', {
      hints: ['java/full 形态须有 main；web/full 形态须有 webMain'],
    })
  }
  return { id: manifest.id, version, main, webMain }
}

/** 递归收集 web/ 下的构建产物（index.js / index.css / *.map，跳过 node_modules）。 */
function collectWebArtifacts(webDir) {
  const found = []
  const walk = (dir, prefix) => {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (ent.name === 'node_modules') continue
      const abs = path.join(dir, ent.name)
      if (ent.isDirectory()) walk(abs, `${prefix}${ent.name}/`)
      else if (ent.name === 'index.js' || ent.name === 'index.css' || ent.name.endsWith('.map')) {
        found.push({ rel: `${prefix}${ent.name}`, abs })
      }
    }
  }
  walk(webDir, '')
  return found
}

/**
 * 按 manifest 判定形态并收集要进 zip 的磁盘文件。
 * @returns {{ zipName: string, abs: string }[]}
 */
function collectFiles(absPlugin, manifest) {
  const files = [{ zipName: `${manifest.id}/plugin.json`, abs: path.join(absPlugin, 'plugin.json') }]

  if (manifest.main) {
    const targetDir = path.join(absPlugin, 'target')
    let jars = []
    if (fs.existsSync(targetDir) && fs.statSync(targetDir).isDirectory()) {
      jars = fs
        .readdirSync(targetDir)
        .filter((f) => f.toLowerCase().endsWith('.jar') && !/(sources|javadoc)\.jar$/i.test(f))
        .sort()
    }
    if (!jars.length) {
      throw new ValidationError(
        `清单声明了 main（java/full 形态），但 target/ 下没有可打包的 jar（sources/javadoc jar 不算）：${targetDir}`,
        {
          hints: [
            `先构建后端 jar：mvn -f "${path.join(absPlugin, 'pom.xml')}" package`,
            'standalone 工程首次构建前，需先在宿主仓库根执行：mvn -pl every-agent-plugin-api -am install -DskipTests',
          ],
        },
      )
    }
    for (const jar of jars) files.push({ zipName: `${manifest.id}/lib/${jar}`, abs: path.join(targetDir, jar) })
  }

  if (manifest.webMain) {
    const entry = path.join(absPlugin, 'web', 'index.js')
    if (!fs.existsSync(entry) || !fs.statSync(entry).isFile()) {
      throw new ValidationError(
        `清单声明了 webMain（web/full 形态），但 web/index.js 不存在：${entry}`,
        {
          hints: [
            '宿主前端硬编码加载 web/index.js（webMain 的值不被消费），必须先构建 bundle',
            '仓库内（builtin）工程：cd every-agent-web && npm run build:plugins（PowerShell 被执行策略拦截时用 npm.cmd run build:plugins）',
            'standalone 工程：cd <插件目录> && npm install && node scripts/build.mjs',
          ],
        },
      )
    }
    for (const item of collectWebArtifacts(path.join(absPlugin, 'web'))) {
      files.push({ zipName: `${manifest.id}/web/${item.rel}`, abs: item.abs })
    }
  }

  return files
}

function fmtSize(n) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

function hex32(v) {
  return v.toString(16).toUpperCase().padStart(8, '0')
}

/** 把条目渲染成目录树文本（顶层目录行 + ├──/└── 缩进 + 大小/压缩法/CRC）。 */
function formatTree(rootName, entries) {
  const root = { dirs: new Map(), files: [] }
  for (const entry of entries) {
    const segs = entry.name.split('/').slice(1)
    let node = root
    for (let i = 0; i < segs.length - 1; i++) {
      if (!node.dirs.has(segs[i])) node.dirs.set(segs[i], { dirs: new Map(), files: [] })
      node = node.dirs.get(segs[i])
    }
    node.files.push(entry)
  }
  const labels = []
  const collectLabels = (node, prefix) => {
    for (const [name, child] of node.dirs) {
      labels.push(`${prefix}${name}/`)
      collectLabels(child, `${prefix}${name}/`)
    }
    for (const f of node.files) labels.push(`${prefix}${f.name.split('/').pop()}`)
  }
  collectLabels(root, '')
  const width = Math.max(...labels.map((l) => l.length)) + 2

  const lines = [`${rootName}/  （顶层目录 = pluginId）`]
  const render = (node, prefix) => {
    const children = [...node.dirs.entries()]
      .map(([name, child]) => ({ label: `${name}/`, dir: child }))
      .concat(node.files.map((f) => ({ label: f.name.split('/').pop(), file: f })))
      .sort((a, b) => a.label.localeCompare(b.label, 'en'))
    children.forEach((child, i) => {
      const last = i === children.length - 1
      const branch = last ? '└── ' : '├── '
      if (child.dir) {
        lines.push(`${prefix}${branch}${child.label}`)
        render(child.dir, `${prefix}${last ? '    ' : '│   '}`)
      } else {
        const method = child.file.method === METHOD_DEFLATE ? 'deflate' : 'store'
        const usize = child.file.usize ?? child.file.data.length
        const meta = `${fmtSize(usize)}  ${method}  crc=${hex32(child.file.crc)}`
        lines.push(`${prefix}${branch}${child.label.padEnd(width)}${meta}`)
      }
    })
  }
  render(root, '')
  return lines.join('\n')
}

/**
 * --verify：读回刚写的 .eap，逐条核对并打印结果。
 * @returns {boolean} 是否全部通过。
 */
function verifyZipFile(target, manifest, expectedNames, out) {
  out.write('\n--verify 自检（node:zlib 读回自证，不依赖外部解压工具）：\n')
  let entries
  try {
    entries = readZip(fs.readFileSync(target))
  } catch (err) {
    out.write(`  [失败] zip 结构读取失败：${err && err.message ? err.message : String(err)}\n`)
    return false
  }
  const names = entries.map((e) => e.name)
  const okCount = names.length === expectedNames.length && expectedNames.every((n) => names.includes(n))
  out.write(
    `  [${okCount ? 'ok' : '失败'}] EOCD 与中央目录可定位，共 ${entries.length} 个条目，与收集清单一致\n`,
  )
  out.write(`  [ok] ${entries.length}/${entries.length} 条目 CRC32 与解压大小逐条核对一致（readZip 内置）\n`)

  const unsafe = entries.filter(
    (e) =>
      e.name.includes('\\') ||
      e.name.startsWith('/') ||
      e.name.split('/').includes('..') ||
      e.name.split('/').includes(''),
  )
  out.write(
    `  [${unsafe.length ? '失败' : 'ok'}] 条目路径安全（/ 分隔、无 ..、无绝对路径、无空段）\n`,
  )

  const badTop = entries.filter((e) => e.name.split('/')[0] !== manifest.id)
  out.write(
    `  [${badTop.length ? '失败' : 'ok'}] 顶层目录全部 = ${manifest.id}（与 plugin.json 的 id 一致）\n`,
  )

  const mfEntry = entries.find((e) => e.name === `${manifest.id}/plugin.json`)
  let manifestOk = false
  let detail = `找不到条目 ${manifest.id}/plugin.json`
  if (mfEntry) {
    try {
      const mf = JSON.parse(mfEntry.data.toString('utf8'))
      manifestOk = mf.id === manifest.id && String(mf.version) === manifest.version
      detail = `id=${mf.id}, version=${mf.version}`
      if (!manifestOk) detail += `（与源清单不一致：期望 id=${manifest.id}, version=${manifest.version}）`
    } catch (err) {
      detail = `解析失败：${String(err && err.message ? err.message : err).split('\n')[0]}`
    }
  }
  out.write(`  [${manifestOk ? 'ok' : '失败'}] ${manifest.id}/plugin.json 可解析（${detail}）\n`)

  // 第 6 项（known-issues #21）：旁文件摘要与「重读盘上 .eap 复算」一致——顺带兜住写盘截断。
  const shaName = `${path.basename(target)}.sha256`
  let shaOk = false
  let shaDetail = `找不到旁文件 ${shaName}`
  try {
    const sidecar = fs.readFileSync(`${target}.sha256`, 'utf8').trim()
    const actual = createHash('sha256').update(fs.readFileSync(target)).digest('hex')
    const m = sidecar.match(/^([0-9a-f]{64})(?:\s\*|\s{2}|\s)(\S+)$/)
    if (!m) {
      shaDetail = '旁文件内容不是 sha256sum 兼容格式（摘要 + 两个空格 + 文件名）'
    } else if (m[2] !== path.basename(target)) {
      shaOk = false
      shaDetail = `旁文件文件名不符（${m[2]}，期望 ${path.basename(target)}）`
    } else if (m[1] !== actual) {
      shaDetail = `摘要不符（旁文件 ${m[1]}，盘上复算 ${actual}）`
    } else {
      shaOk = true
      shaDetail = actual
    }
  } catch (err) {
    shaDetail = `读取失败：${String(err && err.message ? err.message : err).split('\n')[0]}`
  }
  out.write(`  [${shaOk ? 'ok' : '失败'}] ${shaName} 与盘上 .eap 复算摘要一致（sha256: ${shaDetail}）\n`)

  const allOk = okCount && !unsafe.length && !badTop.length && manifestOk && shaOk
  out.write(allOk ? '  自检全部通过\n' : '  自检未通过\n')
  return allOk
}

/**
 * 打包核心（占位契约保留的导出名）。
 * @param {object} input
 * @param {string} input.pluginDir 插件工程根（含 plugin.json）
 * @param {string} [input.outDir] 产物目录，缺省当前目录
 * @param {boolean} [input.verify] 写完后读回自检
 * @param {object} [input.io] 输出流（默认 process）
 * @returns {Promise<{ file: string, sha256File: string, sha256: string, entries: string[] }>}
 */
export async function packPlugin({ pluginDir, outDir, verify = false, io = process } = {}) {
  const out = io.stdout || process.stdout
  const absPlugin = path.resolve(process.cwd(), pluginDir)
  const manifest = loadManifest(absPlugin)
  const files = collectFiles(absPlugin, manifest)
  const entries = files.map((f) => ({ name: f.zipName, data: fs.readFileSync(f.abs) }))

  const absOutDir = path.resolve(process.cwd(), outDir ?? '.')
  try {
    fs.mkdirSync(absOutDir, { recursive: true })
  } catch (err) {
    throw new WriteError(`创建输出目录失败：${absOutDir}（${err && err.message ? err.message : err}）`)
  }
  const target = path.join(absOutDir, `${manifest.id}-${manifest.version}.eap`)
  // buildZip 会把每条的 method/comp/crc 写回条目对象，formatTree 直接复用（避免二次压缩）
  const zipBuf = buildZip(entries)
  try {
    fs.writeFileSync(target, zipBuf)
  } catch (err) {
    throw new WriteError(`写入 .eap 失败：${target}（${err && err.message ? err.message : err}）`)
  }

  // 校验和旁文件（known-issues #21 最小方案）：对 .eap 全文件做 sha256，
  // 内容一行「摘要 + 两个空格 + 文件名 + 换行」——sha256sum -c 直接可用（两个空格 = 二进制口径）。
  const sha256 = createHash('sha256').update(zipBuf).digest('hex')
  const shaFile = `${target}.sha256`
  try {
    fs.writeFileSync(shaFile, `${sha256}  ${path.basename(target)}\n`, 'utf8')
  } catch (err) {
    throw new WriteError(`写入 .eap.sha256 失败：${shaFile}（${err && err.message ? err.message : err}）`)
  }

  out.write(`已打包 ${target}（${entries.length} 个条目，${fmtSize(zipBuf.length)}）\n`)
  out.write(`校验和   ${shaFile}（sha256sum -c 兼容：${sha256}  ${path.basename(target)}）\n`)
  out.write(`\n${formatTree(manifest.id, entries)}\n`)
  if (verify) {
    const ok = verifyZipFile(target, manifest, entries.map((e) => e.name), out)
    if (!ok) throw new WriteError('打包自检失败：.eap 与预期不符（见上方 [失败] 行）')
  }
  out.write(
    '\n安装：把该文件复制到 worker 机器后调用 RPC plugin.install {"path":"<该文件在 worker 机器上的绝对路径>"}，重启 worker 生效。\n',
  )
  return { file: target, sha256File: shaFile, sha256, entries: entries.map((e) => e.name) }
}

/**
 * pack 子命令入口：自带 flag 集（-o/--verify 不在主命令集，index.mjs 先于 parseArgs 截过来）。
 * async 是为了 await packPlugin——它按占位契约是 Promise，内部异常须以 rejection 形式
 * 冒给 index.mjs 的顶层 try/catch，不能变成未处理 rejection。
 * @param {string[]} argv 子命令之后的参数
 * @param {object} [io]
 * @returns {Promise<number>} 退出码
 */
export async function runPack(argv, { io = process } = {}) {
  const out = io.stdout || process.stdout
  const positional = []
  let outDir = null
  let verify = false
  let onlyPositional = false

  for (let i = 0; i < argv.length; i++) {
    const token = argv[i]
    if (onlyPositional) {
      positional.push(token)
      continue
    }
    if (token === '--') {
      onlyPositional = true
      continue
    }
    if (token === '-h' || token === '--help') {
      out.write(PACK_HELP)
      return EXIT.OK
    }
    if (token === '--verify') {
      verify = true
      continue
    }
    if (token === '-o' || token === '--output') {
      const value = argv[++i]
      if (value === undefined || value === '') {
        throw new UsageError('pack 的 -o/--output 缺少取值（输出目录）', { hints: [`用法：${PACK_USAGE}`] })
      }
      if (outDir !== null) throw new UsageError('pack 的 -o/--output 重复出现')
      outDir = value
      continue
    }
    if (token.startsWith('--output=')) {
      const value = token.slice('--output='.length)
      if (value === '') throw new UsageError('pack 的 --output 取值为空')
      if (outDir !== null) throw new UsageError('pack 的 -o/--output 重复出现')
      outDir = value
      continue
    }
    if (token.length > 1 && token.startsWith('-')) {
      throw new UsageError(`pack 不认识 flag：${token}`, {
        hints: [`用法：${PACK_USAGE}`, 'pack 的 flag 只有 -o/--output <目录>、--verify、-h/--help'],
      })
    }
    positional.push(token)
  }

  if (positional.length === 0) {
    throw new UsageError('pack 需要一个插件工程目录参数', {
      hints: [`用法：${PACK_USAGE}`, '示例：node create-everyagent-plugin pack every-agent-plugins/my-tool --verify'],
    })
  }
  if (positional.length > 1) {
    throw new UsageError(`pack 只接受 1 个位置参数（插件目录），收到 ${positional.length} 个：${positional.join(' ')}`)
  }

  // packPlugin 抛出的 CliError（含 rejection）由 index.mjs 顶层统一打到 stderr
  await packPlugin({ pluginDir: positional[0], outDir: outDir ?? '.', verify, io })
  return EXIT.OK
}
