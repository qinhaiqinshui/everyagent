/**
 * 草稿页「浏览并选择工作区」意图(外部 → 草稿页工作区选择器)。
 *
 * 空态页快捷入口「新建工作区(项目)」只做导航:先打开新建任务草稿标签,再发一次浏览意图,
 * 由草稿页的 `TaskWorkspacePicker` 接收并自动打开 `WorkspaceBrowserModal`——快捷入口自身
 * 不弹窗、不持有目录选择状态。
 *
 * 双通道配合,解决两种时序(与 settings/settingsNavigation 同构):
 * - **pending 单槽**:草稿标签懒加载首挂、选择器尚未挂载时无人订阅,意图先记在模块级槽位,
 *   选择器挂载时经 `consumePendingDraftWorkspaceBrowse` 一次性消费(读后清空);
 * - **订阅**:选择器已挂载(草稿标签本来就在)时的即时通知。
 *
 * 消费即清空,故同一次请求不会被两条通道重复兑现:已挂载时订阅侧消费掉 pending,
 * 之后选择器重挂不会因陈旧 pending 再次弹窗。
 */

let pending = false
const listeners = new Set<() => void>()

/** 发出「打开目录浏览弹窗」意图(通知已挂载的选择器,并留 pending 兜底未挂载)。 */
export function requestDraftWorkspaceBrowse(): void {
  pending = true
  for (const fn of listeners) fn()
}

/** 消费 pending 意图(选择器挂载时调用;已在订阅回调里调用,读后清空)。 */
export function consumePendingDraftWorkspaceBrowse(): boolean {
  const consumed = pending
  pending = false
  return consumed
}

/** 订阅浏览意图;返回退订函数。 */
export function subscribeDraftWorkspaceBrowse(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}