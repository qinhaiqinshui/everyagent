package dev.everyagent.plugin.workflow.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一次节点运行记录（instances/&lt;wi&gt;/runs/&lt;0007_wn_b7&gt;.json——每运行一文件，
 * 并行节点写不同文件天然无并发冲突；首次创建+原子重写）。
 *
 * <p>{@code output} 二形态：命令节点 {exitCode, stdout, stderr}；AI 节点 {text}
 * （NON_NULL 序列化各呈其形，见 {@link RunOutput}）。{@code supervisor} 为该次运行上的
 * 介入链（attempt 递增，连续介入决策留档）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunRecord(
        Integer runNo,
        String nodeId,
        String nodeName,
        String nodeType,
        Long startedAt,
        Long endedAt,
        Long durationMs,
        RunInput input,
        RunOutput output,
        List<String> activatedEdges,
        AgentTrace agent,
        Error error,
        List<SupervisorEntry> supervisor) {

    /** 节点输入三视图：raw=模板原文；rendered={{var}} 渲染后；visibleToAi=token 拍平后（AI 实际所见）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunInput(
            String raw,
            String rendered,
            String visibleToAi) {
    }

    /**
     * 节点输出：命令节点填 exitCode/stdout/stderr（全量留档）；AI 节点填 text（最终回复全文）。
     * NON_NULL 序列化即得计划契约的两种形态。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunOutput(
            Integer exitCode,
            String stdout,
            String stderr,
            String text) {

        /** 命令节点输出。 */
        public static RunOutput command(int exitCode, String stdout, String stderr) {
            return new RunOutput(exitCode, stdout, stderr, null);
        }

        /** AI 节点输出。 */
        public static RunOutput ai(String text) {
            return new RunOutput(null, null, null, text);
        }
    }

    /** 命名 agent 会话线观测（匿名 AI 节点为 null）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentTrace(
            String name,
            String modelConfigId,
            Integer seedTurns,
            Integer resultTurns,
            Usage usage) {
    }

    /** 该次运行的 token 用量。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(
            Long inputTokens,
            Long outputTokens) {
    }

    /** 该次运行失败信息（exitCode 命令节点可判）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(
            String message,
            Integer exitCode) {
    }

    /** supervisor 决策协议输出（纯 JSON；action=retry|skip|fail|retryWithInput）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SupervisorDecision(
            String action,
            String input,
            String reason) {
    }

    /** 介入链条目：attempt 从 1 递增（每节点介入上限 3 次后 fail）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SupervisorEntry(
            Integer attempt,
            SupervisorDecision decision,
            String reason) {
    }
}
