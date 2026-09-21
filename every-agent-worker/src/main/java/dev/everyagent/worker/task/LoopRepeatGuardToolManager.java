package dev.everyagent.worker.task;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 死循环守卫工具调用管理器(框架原生扩展点,不重写工具循环):装饰共享的
 * {@link ToolCallingManager},在 {@link #executeToolCalls} 处拦截每轮工具调用,委托
 * {@link LoopRepeatGuard} 判定处置动作。
 *
 * <p>行为(对应 {@link LoopRepeatGuard.Action}):
 * <ul>
 *   <li>{@link LoopRepeatGuard.Action#EXECUTE} — 正常:透传委托给真实 manager 执行。</li>
 *   <li>{@link LoopRepeatGuard.Action#WARN} — 连续重复达阈值:本轮<b>不执行</b>工具,构造一条
 *       提醒文本作为 {@link ToolResponseMessage}(与本轮 toolCall id 配对)回传模型,
 *       并令框架以 {@code returnDirect=false} 递归进入下一轮,给 AI 一次纠正机会;
 *       与 {@code MissingToolCallbackResolver} 同构——错误信息作为工具结果回传,由 AI 自纠。</li>
 *   <li>{@link LoopRepeatGuard.Action#STOP} — 已提醒后仍重复:抛 {@link LoopRepeatException}
 *       中断工具循环,任务经 TaskManager 统一 error 收口(FAILED)。</li>
 * </ul>
 *
 * <p>实例随 advisor 每 run 新建,guard 检测态随实例物化隔离;被装饰的 manager 仍是共享
 * 无状态单例。守卫的判定与真实工具执行互斥(同一轮只走其一):WARN 轮不触达真实工具,
 * STOP 轮在执行前即抛出,故不会产生副作用泄漏。
 */
public final class LoopRepeatGuardToolManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final LoopRepeatGuard guard;
    private final int maxRepeated;

    public LoopRepeatGuardToolManager(ToolCallingManager delegate, int maxRepeated) {
        this.delegate = delegate;
        this.maxRepeated = maxRepeated;
        this.guard = new LoopRepeatGuard(maxRepeated);
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        // 与 DefaultToolCallingManager 同源取「带工具调用的那条 assistant 消息」,
        // 保证签名口径与真实执行完全一致。
        AssistantMessage assistant = chatResponse.getResults().stream()
                .filter(g -> g.getOutput() != null && g.getOutput().hasToolCalls())
                .map(Generation::getOutput)
                .findFirst()
                .orElse(null);
        if (assistant == null) {
            // 无工具调用(不应到达,框架已用 isToolCallResponse 过滤):保守透传
            return delegate.executeToolCalls(prompt, chatResponse);
        }
        LoopRepeatGuard.Action action = guard.check(assistant.getToolCalls());
        switch (action) {
            case STOP -> throw new LoopRepeatException(
                    "AI 连续下发完全相同的工具调用(疑似死循环),经提醒后仍未纠正,已停止本轮运行;"
                            + "重新发送消息可继续(检测重新计数)");
            case WARN -> {
                return fabricateWarningResult(prompt, assistant);
            }
            default -> {
                return delegate.executeToolCalls(prompt, chatResponse);
            }
        }
    }

    /**
     * 构造「提醒即工具结果」的执行结果:本轮不真正执行工具,改为对每个 toolCall 回传
     * 同一条提醒文本(id/name 与本轮 assistant 消息配对联发 tool.result 事件,
     * 并随 conversationHistory 递归回喂模型)。conversationHistory 结构对齐
     * {@code DefaultToolCallingManager.buildConversationHistoryAfterToolExecution}:
     * 原 instructions + assistantMessage + toolResponseMessage。
     */
    private ToolExecutionResult fabricateWarningResult(Prompt prompt, AssistantMessage assistant) {
        String warning = "⚠️ 死循环检测:你已连续 " + maxRepeated
                + " 轮下发完全相同的工具调用,本轮不会真正执行这些调用。请改变调用策略或调整参数;"
                + "若下一轮仍下发完全相同的工具调用,任务将被终止。";
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
            responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), warning));
        }
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(responses).build();
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistant);
        history.add(toolResponseMessage);
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
    }
}
