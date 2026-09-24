package dev.everyagent.plugin.api.task;

/**
 * 任务生命周期节点（Servlet Filter 风格）。
 * invoke() 内调用 next.proceed(ctx) 之前的代码 = 下行；之后的代码 = 上行。
 */
public interface TaskLifecycleNode {
    String id();
    /** 链上位置：升序 = 外→内（洋葱下行序）。float 允许任意插位；同 order 按注册顺序（稳定排序）。 */
    float order();
    /**
     * 契约：
     * - 下行段可否决：不调 next 直接 return TaskOutcome（短路，内层不执行）或抛异常（执行器译为 FAILED）。
     * - 收口必达靠节点自己的 try/finally：推荐形态「下行动作在 try 外，next+收口包进 try/finally」——
     *   下行抛异常时本节点不收口（未进入不收口），next 之后无论成败收口必达。
     * - next() 拿到的一律是值（内核已把一切异常翻译成 TaskOutcome），上行段通常无需 catch。
     * - 上行段可改写 result（如补 error 上下文）后返回，外层节点看到改写值。
     * - next 恰好调用一次：不调=否决；重复调=状态未定义（执行器打 ERROR 日志防御）。
     */
    TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception;
}
