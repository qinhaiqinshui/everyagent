/**
 * 草稿任务标签占位 taskId:新建任务先落草稿标签,首次发送经 task.run
 * 换成真实 taskId(见 TaskChat 草稿态)。
 * 独立成常量模块,避免 Layout 为一个常量静态引入 TaskChat 大组件
 * (TaskChat 走懒加载分包)。
 */
export const DRAFT_TASK_ID = '__draft__'

/**
 * 草稿预设(任务页组"+" → 在此工作区新建任务)。
 * 一次带全 workspace + workerId:点击某工作区组的"+"即默认选中该工作区
 * 及其所属 worker,草稿页不再要求手动选 worker/工作区。
 * 草稿标签常驻复用:已打开时点其他组的"+"也要能即时切换预设,
 * 故用可订阅的模块级 holder,TaskChat 草稿态订阅后更新默认值(草稿文本保留)。
 */
export interface DraftPreset {
  /** 预设工作区根(空 = 未预设,走草稿默认回退)。 */
  workspace: string
  /** 预设 worker(该工作区的归属 worker;空 = 未预设,仍需手动选择)。 */
  workerId: string
}

const presetListeners = new Set<(preset: DraftPreset) => void>()
let preset: DraftPreset = { workspace: '', workerId: '' }

/** 更新草稿预设(部分字段合并,未传字段保留上次值)。 */
export function setDraftPreset(next: Partial<DraftPreset>): void {
  preset = { ...preset, ...next }
  for (const fn of presetListeners) fn(preset)
}

export function getDraftPreset(): DraftPreset {
  return preset
}

export function subscribeDraftPreset(fn: (preset: DraftPreset) => void): () => void {
  presetListeners.add(fn)
  return () => presetListeners.delete(fn)
}
