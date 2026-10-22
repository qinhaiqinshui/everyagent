/**
 * hub 连接配置类型(前端本地设置)。
 *
 * 双道鉴权模型:
 * - hubKey 保护 hub(连接 hub 的凭证,与 worker 进程一致);
 * - 每台 worker 一条前端连接,用该 worker 自己的 apiKey(保护 worker 数据)。
 *
 * 凭据策略(审计 M-9,与 apiKey 统一):hubKey 落盘只存密文(hubKeyEnc);明文 hubKey
 * 仅运行时内存使用,saveConnectionConfig 落盘时剥离。落盘结构 = {hubUrl, hubKeyEnc, workers}。
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
  /** hub WebSocket 地址,如 wss://hub.example.com:6101/ws 或 ws://192.168.1.10:6101/ws。 */
  hubUrl: string
  /** hub 级连接凭证明文(保护 hub);仅运行时内存使用,永不落盘。
   *  页面刚载入时为空串,由 hydrateConnectionConfig 解密 hubKeyEnc 回填。 */
  hubKey: string
  /** AES-GCM 加密后的 hubKey(base64,'v1:' 前缀)——落盘字段;空 = 未设置,
   *  或非安全上下文(http)下加密失败、凭据不落盘(仅本次会话内存使用)。 */
  hubKeyEnc: string
  /** 已配置过的 worker 凭证表(按 workerId 索引;可为空,目录发现后补填)。 */
  workers: WorkerCredential[]
}