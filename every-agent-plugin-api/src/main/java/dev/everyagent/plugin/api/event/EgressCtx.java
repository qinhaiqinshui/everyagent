package dev.everyagent.plugin.api.event;

/**
 * 出网(egress)过滤上下文:域中性的<b>主体标识</b>——task 域传 taskId、workflow 域传 workflowId。
 *
 * <p>只携带主体身份,不含任何 task 专属字段,保证 task / workflow(及未来域)共用同一套出网链 SPI。
 * 过滤链节点(id/order/apply)与主体无关;主体专用的判定一律经 {@link #subjectId()} 自行解析。
 *
 * @param subjectId 主体稳定 id(task 域 = taskId;workflow 域 = workflowId;不为 null)
 */
public record EgressCtx(String subjectId) {
}