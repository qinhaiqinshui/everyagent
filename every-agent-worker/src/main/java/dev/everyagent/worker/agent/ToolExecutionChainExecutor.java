package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工具执行拦截链组装器（filter 形态，与 TaskLifecycleExecutor 同构）。
 * <p>按 order 升序把拦截器折叠为嵌套链，链尾接真实 ToolCallingManager.executeToolCalls。
 * 拦截器下行段不调 next = 短路（合成结果）；调 next = 放行到下一节点/真实执行。
 */
public final class ToolExecutionChainExecutor {

    /**
     * 组装并执行拦截链。
     * @param interceptors 拦截器列表（将被稳定排序）
     * @param delegate 真实工具执行管理器（链尾）
     * @param ctx 执行上下文
     * @return 工具执行结果
     */
    public ToolExecutionResult run(List<ToolExecutionInterceptor> interceptors,
            ToolCallingManager delegate, ToolExecutionContext ctx) {
        // 稳定排序：同 order 按注册顺序
        List<ToolExecutionInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(Comparator.comparingDouble(ToolExecutionInterceptor::order));

        // 链尾 = 真实工具执行
        ToolExecutionChain chain = c -> delegate.executeToolCalls(c.prompt(), c.chatResponse());

        // 从内到外折叠
        for (int i = sorted.size() - 1; i >= 0; i--) {
            ToolExecutionInterceptor node = sorted.get(i);
            ToolExecutionChain inner = chain;
            chain = c -> node.invoke(c, inner);
        }

        try {
            return chain.proceed(ctx);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }
}