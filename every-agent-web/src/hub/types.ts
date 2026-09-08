/**
 * hub 连接配置类型(前端本地设置)。
 *
 * 双道鉴权模型:
 * - hubKey 保护 hub(连接 hub 的凭证,与 worker 进程一致);
 * - 每台 worker 一条前端连接,用该 worker 自己的 apiKey(保护 worker 数据)。
 * 因此配置 = {hubUrl, hubKey} + workers[]({workerId, apiKeyEnc(加密), enabled})。
 */

/** 单台 worker 的前端纳管凭证(localStorage 持久化;apiKey 加密存储)。 */
export interface WorkerCredential {
  workerId: string
  /** AES-GCM 加密后的 apiKey(base64,'v1:' 前缀);空 = 未填写。 */
  apiKeyEnc: string
  /** 是否启用(启用才建立该 worker 连接并纳入合并)。 */
  enabled: boolean
  /** sha256(apiKey) 完整 64 hex;保存时本地计算,用于 presence 指纹匹配。 */
  ownerKey?: string
}

export interface HubConnectionConfig {
  /** hub WebSocket 地址,如 wss://hub.example.com:9100/ws 或 ws://192.168.1.10:9100/ws。 */
  hubUrl: string
  /** hub 级连接凭证(保护 hub);前端与 worker 进程都必须携带。 */
  hubKey: string
  /** 已配置过的 worker 凭证表(按 workerId 索引;可为空,目录发现后补填)。 */
  workers: WorkerCredential[]
}