/**
 * 任务运行时服务(视图遥控出口)。
 *
 * n 分支的 runtimeService 在浏览器内驱动任务执行;本前端只做视图与遥控,
 * 任务运行在 worker。deleteTask 走 task.delete RPC(worker 校验归属后删
 * data/<ownerKey>/<taskId>/ 目录——任务永久保留,用户删除是唯一出口)。
 */
import { hubSession } from '@/hub/session'
import { taskStore } from '@/hub/taskStore'

export interface TaskRuntimeService {
  deleteTask(taskId: string): Promise<void>
}

const remoteRuntimeService: TaskRuntimeService = {
  async deleteTask(taskId: string) {
    // 多 worker 下按任务归属 worker 定向删除(避免发到当前选中 worker 导致误删/404)。
    const workerId = taskStore.get(taskId)?.workerId
    if (!workerId) {
      throw new Error('无法确定任务所属 worker(任务数据不可用)')
    }
    await hubSession.rpcTo(workerId, 'task.delete', { taskId })
  },
}

export function getDefaultRuntimeService(): TaskRuntimeService {
  return remoteRuntimeService
}
