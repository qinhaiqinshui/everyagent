package dev.everyagent.plugin.api.spi;

import org.springframework.ai.model.tool.ToolExecutionResult;

/**
 * 工具执行拦截链节点 SPI（filter 形态，与任务洋葱 §3.1 同一范式）。
 * <p>invoke() 内调用 next.proceed(ctx) 之前 = 下行（执行前检查）；之后 = 上行（结果后处理）。
 * <ul>
 *   <li>下行段不调 next 直接 return = 短路（合成结果，等价原 beforeToolExecution 非 null）；</li>
 *   <li>下行段 {@code return next.proceed(ctx)} = 放行（等价原 beforeToolExecution 返回 null）；</li>
 *   <li>上行段（next 返回后）可做结果后处理/审计/计时——本范式新增的能力。</li>
 * </ul>
 */
public interface ToolExecutionInterceptor {

    String id();

    /** 链上位置：升序 = 执行序。float 允许任意插位；同 order 按注册顺序。 */
    float order();

    /**
     * @param ctx 执行上下文（prompt/chatResponse/toolCalls + 显式任务上下文，替代 ThreadLocal）
     * @param next 链的下一环
     * @return 工具执行结果（短路=合成结果；放行=真实执行结果）
     */
    ToolExecutionResult invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception;
}
