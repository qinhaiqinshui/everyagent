/**
 * Electron safeStorage 凭据编解码适配器——整个 desktop 中**唯一** import electron 的
 * 凭据装配点:credentials.ts 保持零 electron 依赖(node:test 进程可直接单测),由本文件
 * 把 electron safeStorage(Windows DPAPI,绑定当前用户与机器)适配为 SecretCodec。
 *
 * 语义要点:
 * - 必须在 app ready 之后调用(safeStorage 依赖);index.ts 已把 whenReady 提前到
 *   loadConfig 之前。
 * - 换机器/换用户/系统凭据库重置后旧密文不可解密 → decode 抛错,由 resolveSecret 的
 *   「密文损坏重生成」分支兜底(重新生成随机密钥并告警 ownerKey 数据归属漂移)。
 */
import { safeStorage } from 'electron'
import { PLAINTEXT_CODEC, SAFE_PREFIX, type SecretCodec } from './credentials'

/** safeStorage 密文 codec:encode = 'safe:' + base64(DPAPI 密文),decode 逆向、失败抛错。 */
export const SAFE_STORAGE_CODEC: SecretCodec = {
  name: 'safeStorage',
  isAvailable(): boolean {
    try {
      return safeStorage.isEncryptionAvailable()
    } catch {
      return false
    }
  },
  encode(plain: string): string {
    return SAFE_PREFIX + safeStorage.encryptString(plain).toString('base64')
  },
  decode(stored: string): string {
    // 前缀不符即非本 codec 产物:抛错交由 resolveSecret 走对应分支,不静默返回原值。
    if (!stored.startsWith(SAFE_PREFIX)) {
      throw new Error(`非 ${SAFE_PREFIX} 密文形态`)
    }
    // decryptString 对无效/损坏密文抛错(真实损坏主要来自换机器后 DPAPI 解密失败)。
    return safeStorage.decryptString(Buffer.from(stored.slice(SAFE_PREFIX.length), 'base64'))
  },
}

/**
 * 装配落盘 codec(须在 app ready 后调用):safeStorage 可用 → 密文落盘;
 * 不可用(极少数受限会话/精简系统)→ 降级明文 codec 并告警——功能可用性优先,
 * 行为与历史明文版本一致,不阻塞启动。
 */
export function resolveSecretCodec(): SecretCodec {
  if (SAFE_STORAGE_CODEC.isAvailable()) return SAFE_STORAGE_CODEC
  console.warn(
    '[desktop] 系统 safeStorage(DPAPI)不可用,凭据将以明文落盘(降级运行;建议排查系统凭据库)',
  )
  return PLAINTEXT_CODEC
}
