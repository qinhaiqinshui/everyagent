/**
 * 等待任务 token 的前端契约常量与工具（kind / payload 形状 / 胶囊 label / payload 安全读取）。
 *
 * 本文件不碰 opaque 线格式（编码由宿主 `ctx.ui.buildComposerToken` 单点负责），
 * 只承载「插件自己认识」的结构：kind 字符串、payload 形状、label 生成与安全读取
 * （仿 selection-reference/web/token.ts 的 readSelectionPayload 范式）。
 */

/** 等待任务胶囊 / trace 事件的 kind；与后端 `WaitTaskToken.KIND` 逐字一致。 */
export const KIND = 'task.wait'

/** 胶囊 payload 形状（构建侧写入 / 点击胶囊侧安全读出）。 */
export interface WaitPayload {
  /** 被等待的目标任务 ID。 */
  targetTaskId: string
  /** 目标任务标题（打开标签时作为标题）。 */
  title: string
}

/** 由目标任务标题生成胶囊显示文字：`等待「标题」`。 */
export function buildLabel(title: string): string {
  return `等待「${title}」`
}

/**
 * 从胶囊 payload（宿主解码 opaque token 后的载荷袋）中安全读取回跳所需字段。
 *
 * 非对象 / 缺 `targetTaskId` / `title`（或非字符串 / 空串）→ 返回 null，
 * 调用方静默 no-op、不抛错（仿 selection-reference 的 readSelectionPayload）。
 */
export function readPayload(payload: Record<string, unknown>): WaitPayload | null {
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
    return null
  }
  const record = payload as Record<string, unknown>
  const targetTaskId = record.targetTaskId
  const title = record.title
  if (typeof targetTaskId !== 'string' || targetTaskId.length === 0) {
    return null
  }
  if (typeof title !== 'string' || title.length === 0) {
    return null
  }
  return { targetTaskId, title }
}
