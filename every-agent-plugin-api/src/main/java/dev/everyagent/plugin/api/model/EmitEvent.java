package dev.everyagent.plugin.api.model;

/**
 * 事件发射统一载荷标记接口。
 *
 * <p>插件发射的事件必须实现此接口。当前唯一实现 {@link TraceData}，
 * 未来会有 DATA 类实现（如 DeltaData、MessageData 等）。
 * TaskEvents 通过 {@code instanceof} 判断类型，零 case 硬编码。
 */
public interface EmitEvent {

    /**
     * TRACE 类事件载荷——承载所有 trace 字段。
     *
     * <p>映射为 wire 事件 {@code task.trace}（kind = {@link #kind()}）。
     * 前端按 kind 选渲染器，按 traceId upsert。
     *
     * @param id       traceId（null=新建，同 id 后续事件 upsert）
     * @param kind     kind（如 "model_failover"），映射为 trace.kind
     * @param title    可选标题
     * @param summary  收起态摘要文案
     * @param content  展开态完整内容（可选）
     * @param status   状态（done/waiting/error 等，可选）
     * @param persist  是否落盘
     */
    record TraceData(
        String id,
        String kind,
        String title,
        String summary,
        String content,
        String status,
        boolean persist
    ) implements EmitEvent {

        /**
         * 持久化 trace 便捷工厂。
         */
        public static TraceData of(String kind, String id, String summary, String content, String status) {
            return new TraceData(id, kind, null, summary, content, status, true);
        }

        /**
         * 瞬态 trace 便捷工厂（不落盘）。
         */
        public static TraceData transientOf(String kind, String id, String summary, String content, String status) {
            return new TraceData(id, kind, null, summary, content, status, false);
        }
    }
}
