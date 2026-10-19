package dev.everyagent.plugin.api.spi;

/**
 * 工具执行拦截链(per-call)的下一环;链尾 = 框架原生 observation + 调用 + 异常处理
 * (下行合成哨兵置位时直接返回合成串,跳过真实调用)。
 */
@FunctionalInterface
public interface ToolExecutionChain {
    String proceed(ToolExecutionContext ctx) throws Exception;
}

