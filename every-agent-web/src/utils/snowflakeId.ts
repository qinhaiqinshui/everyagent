const BASE62_ALPHABET = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
const RUN_SALT_LENGTH = 2
const RUN_SALT_SPACE = BigInt(BASE62_ALPHABET.length ** RUN_SALT_LENGTH)

/** 运行期随机盐，负责把不同启动实例隔开。 */
const RUN_SALT = createRunSalt()
/** 每个前缀独立递增，避免 task / agent / message 互相抢号。 */
const prefixSequences = new Map<string, bigint>()

/**
 * 把整数编码成 base62。
 */
function encodeBase62(value: bigint): string {
  if (value === 0n) {
    return '0'
  }
  let current = value
  let output = ''
  const base = BigInt(BASE62_ALPHABET.length)
  while (current > 0n) {
    const index = Number(current % base)
    output = BASE62_ALPHABET[index] + output
    current /= base
  }
  return output
}

/**
 * 生成运行期随机盐。
 * 只保留很短的前缀，用来降低重启后复用旧 ID 的概率。
 */
function createRunSalt(): string {
  let randomValue = 0n
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const buffer = new Uint32Array(1)
    crypto.getRandomValues(buffer)
    randomValue = BigInt(buffer[0] ?? 0)
  } else {
    randomValue = BigInt(Math.floor(Math.random() * Number.MAX_SAFE_INTEGER))
  }
  return encodeBase62(randomValue % RUN_SALT_SPACE).padStart(RUN_SALT_LENGTH, '0')
}

/**
 * 读取并推进某个前缀自己的序号。
 * 同一前缀的 ID 保持单调递增，不依赖时间戳。
 */
function nextPrefixSequence(prefix: string): bigint {
  const current = prefixSequences.get(prefix)
  if (current !== undefined) {
    prefixSequences.set(prefix, current + 1n)
    return current
  }
  prefixSequences.set(prefix, 1n)
  return 0n
}

/**
 * 生成全局唯一 ID。
 * 仅用业务前缀、短运行期盐和递增序号，去掉固定时间段，让 ID 更短。
 */
export function createSnowflakeId(prefix = 'id'): string {
  const nextSequence = nextPrefixSequence(prefix)
  return `${prefix}_${RUN_SALT}${encodeBase62(nextSequence)}`
}
