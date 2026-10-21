package dev.everyagent.worker.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具调用限额(照抄自 Spring AI 2.0.1 {@code org.springframework.ai.model.tool.ToolCallLimits}
 * ——框架侧为 <b>package-private</b>,worker 包外不可引用,故自建逐语义等价物;
 * 升级 Spring AI 版本时 diff 框架源码对齐,不自行重设计)。
 *
 * <p>语义:按「本轮(turn,自最后一条 UserMessage 起)」计数——单工具上限
 * ({@link #maxCallsPerTool} 按名覆盖,缺省 {@link #defaultMaxCallsPerTool})与
 * 总调用上限({@link #maxTotalToolCalls}),超限即 {@link Breach};{@code -1} = 不限。
 * {@link #NO_LIMIT} 与行为枚举({@code ToolCallLimitBehavior.THROW /
 * RETURN_ERROR_RESPONSE})的组合语义见 {@code PerCallToolCallingManager.executeToolCall}。
 */
public final class ToolCallLimits {

    /** 不限哨照(与框架一致:-1)。 */
    static final int NO_LIMIT = -1;

    private final int defaultMaxCallsPerTool;
    private final Map<String, Integer> maxCallsPerTool;
    private final Set<String> toolsExcludedFromLimit;
    private final int maxTotalToolCalls;
    private final org.springframework.ai.model.tool.ToolCallLimitBehavior onLimitExceeded;

    public ToolCallLimits(int defaultMaxCallsPerTool, Map<String, Integer> maxCallsPerTool,
            Set<String> toolsExcludedFromLimit, int maxTotalToolCalls,
            org.springframework.ai.model.tool.ToolCallLimitBehavior onLimitExceeded) {
        this.defaultMaxCallsPerTool = defaultMaxCallsPerTool;
        this.maxCallsPerTool = Map.copyOf(maxCallsPerTool);
        this.toolsExcludedFromLimit = Set.copyOf(toolsExcludedFromLimit);
        this.maxTotalToolCalls = maxTotalToolCalls;
        this.onLimitExceeded = onLimitExceeded;
    }

    org.springframework.ai.model.tool.ToolCallLimitBehavior onLimitExceeded() {
        return this.onLimitExceeded;
    }

    /** 统计本轮(自最后一条 UserMessage 起)已产生的各工具调用次数。 */
    static Map<String, Integer> countPriorToolCalls(List<Message> messages) {
        Map<String, Integer> count = new HashMap<>();
        for (Message message : currentTurnMessages(messages)) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                    count.merge(toolResponse.name(), 1, Integer::sum);
                }
            }
        }
        return count;
    }

    /** 从后往前找最后一条 UserMessage,返回自它起的尾段(无 UserMessage 则整段)。 */
    private static List<Message> currentTurnMessages(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return messages.subList(i, messages.size());
            }
        }
        return messages;
    }

    /** 超限判定:命中返回 {@link Breach},未超限返回 null。 */
    ToolCallLimits.Breach check(String toolName, int currentToolCallCount, int totalToolCalls) {
        if (!this.toolsExcludedFromLimit.contains(toolName)) {
            int maxCalls = this.maxCallsPerTool.getOrDefault(toolName, this.defaultMaxCallsPerTool);
            if (maxCalls != NO_LIMIT && currentToolCallCount > maxCalls) {
                return new ToolCallLimits.Breach(toolName, maxCalls,
                        "Tool call limit (%d) exceeded for tool '%s'. No further calls to this tool are allowed in this turn."
                                .formatted(maxCalls, toolName));
            }
        }
        if (this.maxTotalToolCalls != NO_LIMIT && totalToolCalls > this.maxTotalToolCalls) {
            return new ToolCallLimits.Breach(null, this.maxTotalToolCalls,
                    "Total tool call limit (%d) exceeded for this turn. No further tool calls are allowed."
                            .formatted(this.maxTotalToolCalls));
        }
        return null;
    }

    /** 超限事实(照抄:record(toolName, limit, message);总限超限时 toolName 为 null)。 */
    record Breach(String toolName, int limit, String message) {
    }
}
