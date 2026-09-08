package dev.everyagent.worker.task;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * 死循环检测 advisor(移植自 novel_agent-n 的 {@code agent.execute.loop_repeat_guard},
 * order 52.15):每轮模型响应聚合后、工具执行前,检查本轮工具调用是否与上一轮完全相同
 * (名称+参数集合签名),连续重复达 {@code worker.limits.max-repeated-tool-rounds}
 * (默认 3,≤0 关闭)即抛 {@link LoopRepeatException} 中断工具循环——本轮的重复调用
 * 不再执行,任务经 TaskManager 统一 error 收口(FAILED);用户重新发送消息冷启动续跑,
 * 守卫随新 advisor 实例(每 run 新建)自然重置计数。
 *
 * <p>为何继承 {@link WorkerToolEventAdvisor} 而非独立 advisor:流式模式下 Spring AI 2.0
 * 把工具轮的聚合帧 filter 在 {@code ToolCallingAdvisor} 内部,链上其他 advisor 只能看到
 * 逐字 chunk,唯一能按轮看到「聚合后的工具调用集合」的钩子是 {@code ToolCallingAdvisor}
 * 的 {@code doAfterStream}——而工具循环的驱动位已由 {@link WorkerToolEventAdvisor} 占用,
 * 故以子类在其上叠加守卫层(事件发射逻辑全部复用 super,本类只做检测与中断)。
 *
 * <p>时序与 n 版一致:先经 super 发出本轮权威事件(message/toolCall),再做守卫判定
 * (流式 delta 已出、收口在后);守卫在 {@code executeToolCalls} 之前抛出,重复轮的工具
 * 不会被执行。Spring AI 2.0.1 的 {@code ToolCallLimits} 只是调用次数配额(每工具/总量),
 * 不识别「连续相同调用」,故 worker 自带此守卫。
 */
public class LoopRepeatGuardAdvisor extends WorkerToolEventAdvisor {

    private final LoopRepeatGuard guard;

    public LoopRepeatGuardAdvisor(ToolCallingManager toolCallingManager, AgentEntity a, int maxRepeated) {
        super(toolCallingManager, a);
        this.guard = new LoopRepeatGuard(maxRepeated);
    }

    @Override
    protected ChatClientResponse doAfterStream(ChatClientResponse chatClientResponse,
            StreamAdvisorChain streamAdvisorChain) {
        ChatClientResponse resp = super.doAfterStream(chatClientResponse, streamAdvisorChain);
        ChatResponse cr = resp.chatResponse();
        if (cr == null || cr.getResult() == null) {
            return resp;
        }
        AssistantMessage out = cr.getResult().getOutput();
        if (out == null) {
            return resp;
        }
        String stop = guard.check(out.getToolCalls());
        if (stop != null) {
            throw new LoopRepeatException(stop);
        }
        return resp;
    }
}
