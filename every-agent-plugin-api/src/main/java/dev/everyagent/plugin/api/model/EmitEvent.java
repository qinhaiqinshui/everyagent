package dev.everyagent.plugin.api.model;

/**
 * 统一事件发射载荷（record）。
 *
 * <p>所有插件/底层组件发射事件时构造此 record，经 {@link EventEmitter#emit} 投递。
 * 产生方管理 {@code id}（Snowflake ID），落 EventLog 即 seq——新 id=新内容（前端插入），
 * 同 id=原地更新（upsert）。{@code kind} 直接沿用现有事件名字符串作为 wire event 名。
 *
 * @param id       产生方管理；落 EventLog 即 seq。新 id=新内容(前端插入)，同 id=原地更新(upsert)
 * @param kind     渲染语义（沿用事件名族 + trace 展开族），落盘/wire 作 event 名
 * @param agentId  agent 层包装 lambda 填入（低层产生方留 null → task 层兜底 mainAgentId）
 * @param title    通用展示字段（可空）
 * @param summary  收起态摘要文案（可空）
 * @param content  展开态完整内容（可空）
 * @param status   状态（done/waiting/error 等，可空）
 * @param data     kind 专属结构化载荷 → payload.data（Json.toJson 序列化）
 * @param persist  落盘 vs 瞬态 → ext.persist
 * @param mode     APPEND=流式追加；REPLACE=整体替换 → ext.operate
 */
public record EmitEvent(
        long id,
        String kind,
        String agentId,
        String title,
        String summary,
        String content,
        String status,
        Object data,
        boolean persist,
        Mode mode
) {

    /** 流式追加 vs 整体替换。 */
    public enum Mode { APPEND, REPLACE }

    /**
     * 持久化事件便捷工厂（persist=true）。
     */
    public static EmitEvent of(long id, String kind, String agentId, String title,
            String summary, String content, String status, Object data, Mode mode) {
        return new EmitEvent(id, kind, agentId, title, summary, content, status, data, true, mode);
    }

    /**
     * 瞬态事件便捷工厂（persist=false，不落盘）。
     */
    public static EmitEvent transientOf(long id, String kind, String agentId, String title,
            String summary, String content, String status, Object data, Mode mode) {
        return new EmitEvent(id, kind, agentId, title, summary, content, status, data, false, mode);
    }

    /**
     * 替换 agentId 的便捷拷贝（agent 层包装 emitter 用）。
     */
    public EmitEvent withAgentId(String agentId) {
        return new EmitEvent(id, kind, agentId, title, summary, content, status, data, persist, mode);
    }
}
