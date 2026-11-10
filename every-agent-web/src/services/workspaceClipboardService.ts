/**
 * 工作区文件剪贴板(应用内内存态单例,对标 VSCode explorer 的 Copy/Cut/Paste)。
 *
 * 与系统剪贴板无关:复制/剪切的是**文件/目录引用**(workspaceRoot + 业务绝对路径集合),
 * 不搬运文件内容;粘贴时由面板组件调 fs.move(剪切)/fs.copy(复制) 落盘。
 * VSCode 行为对齐:
 * - 复制后剪贴板保留 → 可多次粘贴(每次粘贴生成新副本,同名自动「- 副本」后缀);
 * - 剪切粘贴一次后清空;
 * - 剪切中的条目在树中半透明显示(订阅状态驱动,源工作区分组内);
 * - 多工作区分组各自独立判断:剪贴板记录源 workspaceRoot,跨工作区粘贴由面板提示拒绝。
 *
 * 订阅模型:极简 listener 集合(set → 通知),供树行(cut 半透明)与菜单(粘贴可用态)
 * 感知变化;不做 React context——树行按钮/菜单项经由面板组件订阅后 props 下发。
 */

export type WorkspaceClipboardMode = 'copy' | 'cut'

export type WorkspaceClipboard = {
  mode: WorkspaceClipboardMode
  /** 源工作区根(worker 机器绝对路径);粘贴校验同工作区才放行。 */
  workspaceRoot: string
  /** 被复制/剪切的业务绝对路径集合(保持放入顺序)。 */
  paths: string[]
  /** 放入时间戳(ms),仅用于诊断。 */
  setAt: number
}

type Listener = (clip: WorkspaceClipboard | null) => void

let current: WorkspaceClipboard | null = null
const listeners = new Set<Listener>()

function emit(): void {
  for (const listener of listeners) {
    listener(current)
  }
}

export const workspaceClipboardService = {
  /** 当前剪贴板内容(null = 空)。 */
  get(): WorkspaceClipboard | null {
    return current
  },

  /** 复制/剪切一组路径(业务绝对路径,已按选中集合归一;源必须在同一工作区)。 */
  set(mode: WorkspaceClipboardMode, workspaceRoot: string, paths: string[]): void {
    current = paths.length > 0
      ? { mode, workspaceRoot, paths: Array.from(paths), setAt: Date.now() }
      : null
    emit()
  },

  /** 清空剪贴板(剪切粘贴完成 / 源被删除等)。 */
  clear(): void {
    if (current === null) return
    current = null
    emit()
  },

  /** 订阅剪贴板变化;返回退订函数。 */
  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
}
