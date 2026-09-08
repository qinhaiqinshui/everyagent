/**
 * 频道名构造(架构 §3.2)。K 即 ownerKey = sha256(apiKey)。
 * 与 every-agent-worker 的 proto/Channels.java 逐字对齐:
 * 输入走 worker 级频道(taskId 入 payload),任务永久后 worker 不按任务订阅。
 */

/**
 * ownerKey = sha256(apiKey),64 位小写 hex(与 every-agent-contract 的 Ids.ownerKey 逐字对齐)。
 *
 * 优先用 WebCrypto crypto.subtle;但在非安全上下文(http:// 局域网 IP 等)下
 * crypto.subtle 为 undefined,直接调用会抛 "Cannot read properties of undefined
 * (reading 'digest')" 这类误导性错误,导致「还没开始连 ws」就先失败、设置页只报一个
 * 与连接毫无关系的 TypeError。这里在 WebCrypto 不可用/意外失败时回退到纯 JS SHA-256,
 * 保证 ws:// hub 从 http 页面也能正常握手;真正连不上时报的是网络层错误(拒绝/超时)。
 */
export async function ownerKey(apiKey: string): Promise<string> {
  const data = new TextEncoder().encode(apiKey)
  if (typeof crypto !== 'undefined' && crypto.subtle?.digest) {
    try {
      const digest = await crypto.subtle.digest('SHA-256', data)
      return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
    } catch {
      // WebCrypto 意外失败(如浏览器策略限制)时走纯 JS 回退,不让加密实现细节污染连接错误。
    }
  }
  return sha256Hex(data)
}

/** 32 位循环右移(按 int32 语义)。 */
function rotr32(x: number, n: number): number {
  return (x >>> n) | (x << (32 - n))
}

/**
 * 纯 JS SHA-256 小写 hex:仅在 crypto.subtle 不可用的环境作为回退。
 * 输入为 UTF-8 字节,输出与 WebCrypto / Java MessageDigest("SHA-256") 完全一致。
 */
function sha256Hex(data: Uint8Array): string {
  const K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ]
  let h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a
  let h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19

  // 填充:0x80 + 0…0 + 64 位大端原始比特长度。
  const bitLen = data.length * 8
  const paddedLen = (((data.length + 8) >> 6) + 1) << 6
  const padded = new Uint8Array(paddedLen)
  padded.set(data)
  padded[data.length] = 0x80
  const dv = new DataView(padded.buffer)
  dv.setUint32(paddedLen - 8, Math.floor(bitLen / 0x100000000), false)
  dv.setUint32(paddedLen - 4, bitLen >>> 0, false)

  const w = new Int32Array(64)
  for (let i = 0; i < paddedLen; i += 64) {
    for (let t = 0; t < 16; t++) {
      w[t] = dv.getInt32(i + t * 4, false)
    }
    for (let t = 16; t < 64; t++) {
      const s0 = rotr32(w[t - 15], 7) ^ rotr32(w[t - 15], 18) ^ (w[t - 15] >>> 3)
      const s1 = rotr32(w[t - 2], 17) ^ rotr32(w[t - 2], 19) ^ (w[t - 2] >>> 10)
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0
    }
    let a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7
    for (let t = 0; t < 64; t++) {
      const S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (h + S1 + ch + K[t] + w[t]) | 0
      const S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (S0 + maj) | 0
      h = g; g = f; f = e; e = (d + temp1) | 0
      d = c; c = b; b = a; a = (temp1 + temp2) | 0
    }
    h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0
    h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0
  }

  let hex = ''
  for (const word of [h0, h1, h2, h3, h4, h5, h6, h7]) {
    hex += (word >>> 0).toString(16).padStart(8, '0')
  }
  return hex
}

export const channels = {
  workers: (k: string) => `u.${k}.workers`,
  workerCmd: (k: string, workerId: string) => `u.${k}.worker.${workerId}.cmd`,
  workerEvt: (k: string, workerId: string) => `u.${k}.worker.${workerId}.evt`,
  /** worker 级输入:task.input{taskId,text} / ask.reply{askId,answer}。 */
  workerInput: (k: string, workerId: string) => `u.${k}.worker.${workerId}.input`,
  tasks: (k: string) => `u.${k}.tasks`,
  taskStream: (k: string, taskId: string) => `u.${k}.task.${taskId}.stream`,
} as const;
