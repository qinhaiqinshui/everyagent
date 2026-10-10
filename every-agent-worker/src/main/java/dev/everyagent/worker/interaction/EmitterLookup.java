package dev.everyagent.worker.interaction;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.model.EventEmitter;

public interface EmitterLookup {

    EventEmitter emitterFor(String subjectId);

    /**
     * 取本主体的活动 agent 实体（per-run 生命周期状态机持有者，供 ask 生命周期翻转
     * agent 级状态 {@code waiting-user ⇄ running}）。
     *
     * <p>{@code agentId} 空 → 回退主体主 agent；主体不在内存（已终态驱逐）或该 agent
     * 未注册 → null，调用方判空跳过——agent 级状态翻转是展示增强，缺它不影响 ask 本身。
     */
    AgentContext agentFor(String subjectId, String agentId);
}

