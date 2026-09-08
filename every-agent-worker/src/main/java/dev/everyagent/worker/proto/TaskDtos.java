package dev.everyagent.worker.proto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * 任务域 DTO(架构 §5.8)。归 worker 所有。
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** created → running ⇄ waiting-user → done | failed | cancelled(终态可经 task.input 再次 running)。 */
    public enum TaskStatus {
        CREATED, RUNNING, WAITING_USER, DONE, FAILED, CANCELLED;

        public boolean terminal() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }

        public String wire() {
            return name().toLowerCase().replace('_', '-');
        }

        public static TaskStatus fromWire(String s) {
            return valueOf(s.toUpperCase().replace('-', '_'));
        }
    }

    /** 模型配置快照:任务创建时定死,配置后续变更不影响运行中任务。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelSnapshot(String configId, String provider, String baseUrl,
            String model, JsonNode params) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(long inputTokens, long outputTokens, long totalTokens) {

        public static Usage zero() {
            return new Usage(0, 0, 0);
        }

        public Usage plus(Usage other) {
            return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                    totalTokens + other.totalTokens);
        }
    }

    /**
     * 任务摘要里的上下文用量快照(最近一轮主 agent 实测 usage + 窗口上限 + 模型)。
     * 前端任务列表据此渲染上下文电池,与聊天页口径一致(最近一轮 inputTokens / contextWindowTokens)。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageSummary(long inputTokens, long outputTokens, long totalTokens,
            Long contextWindowTokens, String model) {
    }

    /**
     * 单 agent 元数据摘要(任务 meta.json 的 agents 数组项 + 冷启动恢复台账):
     * 由 AgentEntity 在创建/终态收口时序列化,list_agents/wait_agents 契约字段 + 运行诊断字段。
     * kind ∈ MAIN/SUB;status 为工具契约态(running/completed/stopped/error/waiting-user)。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentSummary(String agentId, String kind, String title, Long createdAt,
            String status, Usage usage, JsonNode latestActivity, String lastText) {
    }

    /**
     * 任务摘要(tasks.list / task.created / task.updated 的载体,亦即 meta.json 主体)。
     * mainAgentId = 主 agent 稳定 Id(再运行沿用,即 <mainAgentId>.jsonl 文件名,兼作新事件格式标记);
     * configId 供再运行解析模型配置。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskSummary(
            String taskId,
            String title,
            String status,
            long createdAt,
            Long startedAt,
            Long endedAt,
            Long seqFirst,
            Long seqLast,
            Long trimmedFrom,
            String summary,
            String error,
            String workspace,
            String mainAgentId,
            String configId,
            UsageSummary usage) {

        public static String statusWire(TaskStatus s) {
            return s.wire();
        }
    }
}
