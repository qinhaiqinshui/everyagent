package dev.everyagent.plugin.api.agent;

/**
 * 可执行的 agent 接口(plugin-api 契约)。
 *
 * <p>在 {@link AgentContext} 数据面基础上增加 {@link #run()} 执行入口。
 * 主 agent 与子 agent 共用同一运行入口,仅 {@code agentId} 不同。
 */
public interface Agent extends AgentContext {

    /**
     * 执行 agent 运行循环(ChatClient + Advisor 生态接管工具循环)。
     *
     * @throws InterruptedException 被中断(stop_agent / 任务取消级联)
     */
    void run() throws InterruptedException;
}
