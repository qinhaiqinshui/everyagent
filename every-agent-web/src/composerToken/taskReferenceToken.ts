/**
 * src/composerToken/taskReferenceToken.ts
 *
 * 「任务引用」胶囊 token 的本地工厂（@ 弹层选中任务名建议时插入）。
 *
 * <b>中性引用语义</b>：token 只承载「引用了哪个任务」——label=任务标题、summary=taskId、
 * payload {taskId, title}；对该任务做什么操作由胶囊外的普通文本描述。前端不注册提交
 * 解析（opaque 原串上行）——worker 侧 SlashTokenHandler 收集的 TaskReferenceTokenResolver
 * 把它替换为「[引用任务] 标题 (taskId=…)」。
 */

import { buildOpaqueTokenText } from '@/composerToken/composerOpaqueToken'
import type { ChatComposerToken } from '@/types'
import { createSnowflakeId } from '@/utils/snowflakeId'

/** 任务引用 token 的固定 kind（与 worker 侧 TaskReferenceTokenResolver.KIND 逐字一致）。 */
export const TASK_REFERENCE_TOKEN_KIND = 'task.reference'

/** 构造「任务标题」引用胶囊（taskId 必带；title 空时 label 退用 taskId）。 */
export function buildTaskReferenceToken(taskId: string, title: string): ChatComposerToken {
  const name = title && title.trim() !== '' ? title : taskId
  return {
    id: createSnowflakeId('composer_token'),
    kind: TASK_REFERENCE_TOKEN_KIND,
    label: name,
    summary: taskId,
    opaqueText: buildOpaqueTokenText(TASK_REFERENCE_TOKEN_KIND, {
      label: name,
      summary: taskId,
      payload: { taskId, title },
    }),
  }
}
