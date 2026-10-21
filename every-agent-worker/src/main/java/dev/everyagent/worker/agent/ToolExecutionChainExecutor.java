package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工具执行拦截链组装器(filter 形态,与 TaskLifecycleExecutor 同构;<b>per-call 粒度</b>,
 * 由 {@link PerCallToolCallingManager} 的切口每次工具调用执行一遍,架构 §7.14.3)。
 * <p>按 order 升序把拦截器折叠为嵌套链(order 最小 = 最外层 = 下行最先执行/上行最后执行),
 * 链尾接框架原生执行(observation + 调用 + 异常处理;下行合成哨兵置位则直接返回合成串)。
 * 拦截器下行段不调 {@code next} = 硬短路;{@code ctx.synthesize(串)} + proceed = 合成
 * 当前调用结果(哨兵短路真实调用,合成串流经全部上行节点)。
 */
public final class ToolExecutionChainExecutor {

    /**
     * 组装并执行 per-call 拦截链。
     * @param interceptors 拦截器列表(将被稳定排序)
     * @param ctx          per-call 执行上下文
     * @param tail         链尾(框架原生执行;合成哨兵短路逻辑在 tail 内)
     * @return 该调用的结果串(链全部上行段处理后的最终文本)
     */
    public String runCall(List<ToolExecutionInterceptor> interceptors,
            ToolExecutionContext ctx, ToolExecutionChain tail) {
        // 稳定排序:同 order 按注册顺序
        List<ToolExecutionInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(Comparator.comparingDouble(ToolExecutionInterceptor::order));

        // 从内到外折叠:链尾 = 框架原生执行
        ToolExecutionChain chain = tail;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            ToolExecutionInterceptor node = sorted.get(i);
            ToolExecutionChain inner = chain;
            chain = c -> node.invoke(c, inner);
        }

        try {
            return chain.proceed(ctx);
        }
        catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }
}
