package dev.everyagent.plugin.api.task;

/**
 * Task 域插件上下文 —— 从 {@code WorkerPluginContext} 中隔离出的 task SPI 注册接口。
 *
 * <p>需要注册 task 域 SPI（准入策略、生命周期节点）的插件可通过此接口（或其父接口
 * {@code WorkerPluginContext}，后者继承了本接口）获取注册方法。
 * 不需要 task 域能力的插件只依赖基础 {@code WorkerPluginContext}，不被迫传递性 import task 域类型。
 */
public interface TaskPluginContext {

    /**
     * 注册任务准入策略（队列插件用）。
     *
     * @param policy 准入策略
     */
    void registerTaskAdmissionPolicy(TaskAdmissionPolicy policy);

    /**
     * 注册 TaskLifecycleNode（任务生命周期链节点）。
     *
     * @param node 生命周期节点
     */
    void registerTaskLifecycleNode(TaskLifecycleNode node);
}
