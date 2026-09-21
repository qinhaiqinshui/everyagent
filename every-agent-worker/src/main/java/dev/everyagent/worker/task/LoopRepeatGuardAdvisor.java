package dev.everyagent.worker.task;

import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * 死循环检测 advisor(移植自 novel_agent-n 的 {@code agent.execute.loop_repeat_guard},
 * order 52.15):每轮工具执行前,检查本轮工具调用是否与上一轮完全相同(名称+参数集合签名)。
 * 连续重复达 {@code worker.limits.max-repeated-tool-rounds}(默认 3,≤0 关闭)时<b>不再直接
 * 收口</b>,而是把一条提醒文本作为该轮的工具执行结果回传给 AI,留一次纠正机会;若提醒后
 * 下一轮仍下发完全相同的工具调用,才抛 {@link LoopRepeatException} 中断工具循环,任务经
 * TaskManager 统一 error 收口(FAILED);用户重新发送消息冷启动续跑,守卫随新 advisor 实例
 * (每 run 新建)自然重置计数。
 *
 * <p>守卫本身不在本 advisor 体内,而是落在构造时注入的 {@link LoopRepeatGuardToolManager}
 * 里:它装饰共享 {@link ToolCallingManager} 并在 {@code executeToolCalls} 处拦截——这是框架
 * 唯一允许「既阻止真实工具执行、又能注入一条合成工具结果回传模型」的扩展点(纯 advisor hook
 * 只能 throw 中断或旁观事件,无法改写本轮的工具执行产出)。本类因而只负责「把守卫装饰器
 * 与事件发射 advisor 装配到同一工具循环入口」,不重写任何 hook,事件逻辑全部继承
 * {@link WorkerToolEventAdvisor}。
 *
 * <p>时序与 n 版一致:先经 super 发出本轮权威事件(message/toolCall),再由
 * {@link LoopRepeatGuardToolManager} 在执行前判定;WARN 轮的合成 tool.result 由
 * {@link WorkerToolEventAdvisor#doGetNextInstructionsForToolCallStream} 按 callId 配对正常发射。
 */
public class LoopRepeatGuardAdvisor extends WorkerToolEventAdvisor {

    public LoopRepeatGuardAdvisor(ToolCallingManager toolCallingManager, AgentEntity a, int maxRepeated) {
        super(new LoopRepeatGuardToolManager(toolCallingManager, maxRepeated), a);
    }
}

