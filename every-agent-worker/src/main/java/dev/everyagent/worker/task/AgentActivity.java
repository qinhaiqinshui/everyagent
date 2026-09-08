package dev.everyagent.worker.task;

/**
 * 子 agent 最近一次 AI 返回的快照(架构 §5.6,list_agents/wait_agents 工具契约的
 * latestActivity 字段):思考 / 正文 / 错误 + 首末时间戳。不可变记录,经
 * {@link AgentEntity#updateActivity} 合并写入,读侧靠 volatile 引用无锁可见。
 */
public record AgentActivity(
        /** 思考内容(reasoning 累积值)。 */
        String reasoning,
        /** 最近一次助手输出文本。 */
        String content,
        /** 错误信息(终态 error/stopped 时写入)。 */
        String error,
        /** 首次快照时间。 */
        Long createdAt,
        /** 最近更新时间。 */
        Long updatedAt) {
}
