/**
 * src/composerToken/workflowReferenceToken.ts
 *
 * 「工作流引用」胶囊 token 的本地工厂（@ 弹层选中工作流定义建议时插入）。
 *
 * <b>中性引用语义</b>：token 只承载「引用了哪个工作流」——label「工作流『名』」、
 * summary=definitionId、payload {definitionId, definitionName}（无 variables，参数写在
 * 胶囊外的普通文字）；对该工作流做什么操作由普通文本描述。字段名/键序与 worker 侧
 * WorkflowReferenceToken.buildToken 逐字一致（同一编码 buildOpaqueTokenText，两侧产出
 * 相同 token 串）。前端不注册提交解析（对齐 external_file 先例）——opaque 原串上行，
 * worker 侧 SlashTokenResolveAdvisor 按 kind 分发 WorkflowReferenceSlashResolver 渲染
 * 「[引用工作流] 名 (definitionId)」。
 */

import { buildOpaqueTokenText } from '@/composerToken/composerOpaqueToken'
import type { ChatComposerToken } from '@/types'
import { createSnowflakeId } from '@/utils/snowflakeId'

/** 工作流引用 token 的固定 kind（与 worker 侧 WorkflowReferenceToken.KIND 逐字一致）。 */
export const WORKFLOW_REFERENCE_TOKEN_KIND = 'workflow.reference'

/**
 * 构造「工作流『名』」引用胶囊（definitionId 必带；definitionName 空时 label 退用 id，
 * payload 原样携带空串——worker 侧经 WorkflowStore 反查补全）。
 */
export function buildWorkflowReferenceToken(
  definitionId: string,
  definitionName: string,
): ChatComposerToken {
  const name = definitionName && definitionName.trim() !== '' ? definitionName : definitionId
  return {
    id: createSnowflakeId('composer_token'),
    kind: WORKFLOW_REFERENCE_TOKEN_KIND,
    label: `工作流「${name}」`,
    summary: definitionId,
    opaqueText: buildOpaqueTokenText(WORKFLOW_REFERENCE_TOKEN_KIND, {
      label: `工作流「${name}」`,
      summary: definitionId,
      payload: { definitionId, definitionName },
    }),
  }
}
