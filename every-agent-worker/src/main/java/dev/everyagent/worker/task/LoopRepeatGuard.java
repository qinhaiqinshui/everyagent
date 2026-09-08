package dev.everyagent.worker.task;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 死循环检测(移植自 novel_agent-n 的 {@code agent.execute.loop_repeat_guard} 节点 +
 * {@code loopOps.computeToolCallSignature}):每轮模型响应后(工具执行前)计算本轮工具
 * 调用集合的稳定签名,连续重复达阈值即收口。
 *
 * <p>签名 = 各调用 {@code name(arguments)} 排序后以 {@code |} 拼接(顺序无关:调用集合
 * 相同但顺序不同的两轮视为相同)。本轮无工具调用不会死循环,计数归零。
 *
 * <p>实例随 advisor 每 run 新建(run 间物化隔离,等价 n 版工厂闭包);收口即重置检测态,
 * 用户重新发送消息续跑时从第一次出现重新计数。阈值 {@code <=0} 关闭检测。
 */
public final class LoopRepeatGuard {

    private final int maxRepeated;
    private String lastSignature;
    private int repeatCount;

    public LoopRepeatGuard(int maxRepeated) {
        this.maxRepeated = maxRepeated;
    }

    /**
     * 本轮工具调用集合的稳定签名;空集返回 null(不会死循环)。
     */
    static String signature(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(toolCalls.size());
        for (AssistantMessage.ToolCall call : toolCalls) {
            parts.add(call.name() + "(" + (call.arguments() == null ? "" : call.arguments()) + ")");
        }
        Collections.sort(parts);
        return String.join("|", parts);
    }

    /**
     * 每轮模型响应聚合后调用(工具执行前)。
     *
     * @return 触发收口的提示文案;{@code null} = 放行本轮
     */
    public synchronized String check(List<AssistantMessage.ToolCall> toolCalls) {
        if (maxRepeated <= 0) {
            return null; // 检测关闭
        }
        String sig = signature(toolCalls);
        if (sig == null) {
            lastSignature = null;
            repeatCount = 0;
            return null;
        }
        if (sig.equals(lastSignature)) {
            repeatCount += 1;
        } else {
            repeatCount = 0;
            lastSignature = sig;
        }
        if (repeatCount >= maxRepeated) {
            // 收口并重置检测态(n 版语义):续跑/下一 run 从第一次出现重新计数。
            lastSignature = null;
            repeatCount = 0;
            return "AI 已连续 " + maxRepeated + " 轮执行完全相同的工具调用(疑似死循环),已停止本轮运行;重新发送消息可继续(检测重新计数)";
        }
        return null;
    }
}
