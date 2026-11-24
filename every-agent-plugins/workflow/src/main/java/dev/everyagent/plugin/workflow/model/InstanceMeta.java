package dev.everyagent.plugin.workflow.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 实例元数据（instances/&lt;wi_id&gt;/meta.json；仅状态迁移点原子重写，≈task meta.json）。
 *
 * <p>节点运行态<b>不进</b> meta.json：前端实例页从 events.jsonl 投影重建；
 * nodeStates 仅终态一次性投影（列表页不读 events 也能出「12/15 节点完成」）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstanceMeta(
        Integer schemaVersion,
        String id,
        String definitionId,
        String definitionName,
        String workspaceId,
        /**
         * 关联的 origin 任务 ID（任务内运行时非 null；RPC 直启/独立运行为 null）。
         * AI 节点会话/呈现的唯一载体在任务域（tasks/&lt;taskId&gt;/&lt;agentId&gt;.jsonl），
         * 本字段是 rerun/桥接的锚点；wf.created 事件 payload 同步携带。
         */
        String originTaskId,
        /** 定义深拷贝快照（定义后续修改/删除不影响本实例）。 */
        Definition definitionSnapshot,
        /** 运行时用户实输入（name → value）。 */
        Map<String, String> variables,
        InstanceStatus status,
        Long createdAt,
        Long startedAt,
        Long endedAt,
        Long durationMs,
        /** 终态失败时非 null。 */
        Error error,
        /** askUser 挂起时非 null（与 supervisor 决策同构，前端同一组决策按钮）。 */
        PendingDecision pendingDecision,
        /** 运行中=null（权威在内存+events）；终态时投影快照，供列表页显示进度。 */
        Map<String, NodeState> nodeStates,
        /** 已分配的节点运行序号（runs 文件名前缀）。 */
        Integer runCounter) {

    public static final int SCHEMA_VERSION = 1;

    /** 实例级失败信息（终态 failed 时）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(
            String message,
            String nodeId,
            Integer runNo) {
    }

    /** askUser 挂起的待决策（options 与 supervisor 四 action 同构）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PendingDecision(
            String nodeId,
            Integer runNo,
            String question,
            List<String> options,
            String context,
            Long createdAt) {
    }

    /** 终态节点投影（列表页进度显示用；运行中权威在内存+events）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeState(
            Integer runCount,
            String status,
            Integer lastRunNo) {
    }

    /** null 安全：运行变量（无输入=空表）。 */
    public Map<String, String> variablesOrEmpty() {
        return variables == null ? Map.of() : variables;
    }
}
