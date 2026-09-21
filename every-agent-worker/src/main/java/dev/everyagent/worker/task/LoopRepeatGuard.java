package dev.everyagent.worker.task;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 死循环检测(移植自 novel_agent-n 的 {@code agent.execute.loop_repeat_guard} 节点 +
 * {@code loopOps.computeToolCallSignature}):每轮模型响应后(工具执行前)计算本轮工具
 * 调用集合的稳定签名,连续重复达阈值时<b>不再直接收口</b>,而是回传一条提醒文本作为
 * 工具执行结果给 AI,留一次纠正机会;若提醒后下一轮仍下发完全相同的工具调用,才抛
 * {@link LoopRepeatException} 终止任务(与 {@code MissingToolCallbackResolver} 同构:
 * 错误信息作为工具结果回传模型,由 AI 自纠,而非直接致命)。
 *
 * <p>签名 = 各调用 {@code name(arguments)} 排序后以 {@code |} 拼接(顺序无关:调用集合
 * 相同但顺序不同的两轮视为相同)。本轮无工具调用不会死循环,计数归零。
 *
 * <p>状态机(每 run 新建实例,run 间物化隔离,等价 n 版工厂闭包):
 * <ul>
 *   <li>{@link Action#EXECUTE} — 正常:委托被装饰的真实 ToolCallingManager 执行工具。</li>
 *   <li>{@link Action#WARN} — 连续重复达阈值({@code repeatCount >= maxRepeated}):本轮
 *       不执行工具,改由 {@link LoopRepeatGuardToolManager} 构造一条提醒文本作为
 *       {@code ToolResponseMessage} 回传模型;置 {@code warned} 标记,等待下一轮判定。</li>
 *   <li>{@link Action#STOP} — 已提醒({@code warned})后仍下发相同签名:抛
 *       {@link LoopRepeatException} 终止任务;收口并重置检测态,续跑/下一 run 从第一次
 *       出现重新计数。</li>
 * </ul>
 *
 * <p>阈值 {@code <=0} 关闭检测(恒返回 {@link Action#EXECUTE})。
 */
public final class LoopRepeatGuard {

    /** 守卫对本轮工具调用的处置动作。 */
    public enum Action { EXECUTE, WARN, STOP }

    private final int maxRepeated;
    private String lastSignature;
    private int repeatCount;
    private boolean warned;

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
     * 每轮工具执行前调用(经 {@link LoopRepeatGuardToolManager} 拦截)。
     *
     * @return 本轮处置动作;{@link Action#EXECUTE} = 放行执行
     */
    public synchronized Action check(List<AssistantMessage.ToolCall> toolCalls) {
        if (maxRepeated <= 0) {
            return Action.EXECUTE; // 检测关闭
        }
        String sig = signature(toolCalls);
        if (sig == null) {
            // 纯文本轮不会死循环:计数与提醒标记一并归零
            lastSignature = null;
            repeatCount = 0;
            warned = false;
            return Action.EXECUTE;
        }
        if (sig.equals(lastSignature)) {
            repeatCount += 1;
        } else {
            // 签名变化(含首次出现):重新计数,清除上一轮的提醒标记
            repeatCount = 0;
            lastSignature = sig;
            warned = false;
        }
        if (!warned && repeatCount >= maxRepeated) {
            // 达阈值但尚未提醒过:回传提醒文本作为工具结果,给 AI 一次纠正机会
            warned = true;
            return Action.WARN;
        }
        if (warned) {
            // 已提醒后仍下发相同签名:终止任务,并重置检测态(续跑/下一 run 重新计数)
            lastSignature = null;
            repeatCount = 0;
            warned = false;
            return Action.STOP;
        }
        return Action.EXECUTE;
    }
}
