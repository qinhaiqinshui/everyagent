package dev.everyagent.plugin.api.model;

/**
 * 通用事件发射器（plugin-api 接口）。
 *
 * <p>作为插件 / 底层组件发射事件的统一出口。插件只管发 {@link EmitEvent} 载荷，
 * 不知道 wire 格式（task.trace / wf.trace）。语义 → wire 映射在 task 层完成。
 *
 * <p>当前唯一实现 {@link EmitEvent.TraceData} 映射为 {@code task.trace}（kind = trace.kind）。
 * 未来 DATA 类实现将原样透传 wire 事件名。
 */
public interface EventEmitter {
    void emit(EmitEvent event);
}
