package dev.everyagent.plugin.api.model;

/**
 * 通用事件发射器（plugin-api 接口）。
 *
 * <p>作为插件 / 底层组件发射事件的统一出口。插件只管发 {@link EmitEvent} 载荷，
 * 不知道 wire 格式（task.trace / wf.trace）。语义 → wire 映射在 task 层完成。
 *
 * <p>{@link #emit} 返回 seq（EventLog 分配的 Snowflake ID），供产生方关联后续事件
 * （如轮首分配 id，本轮 delta/thinking/message 共用）。
 */
public interface EventEmitter {
    long emit(EmitEvent event);
}
