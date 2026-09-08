const dirtyFileTabIds = new Set<string>()

/**
 * 记录单个文件页是否存在未保存修改。
 */
export function setFileTabDirtyState(fileTabId: string, dirty: boolean): void {
  if (dirty) {
    dirtyFileTabIds.add(fileTabId)
    return
  }
  dirtyFileTabIds.delete(fileTabId)
}

/**
 * 清理单个文件页的脏状态记录。
 */
export function clearFileTabDirtyState(fileTabId: string): void {
  dirtyFileTabIds.delete(fileTabId)
}

/**
 * 判断当前是否存在未保存文件页。
 */
export function hasDirtyFileTabs(): boolean {
  return dirtyFileTabIds.size > 0
}
