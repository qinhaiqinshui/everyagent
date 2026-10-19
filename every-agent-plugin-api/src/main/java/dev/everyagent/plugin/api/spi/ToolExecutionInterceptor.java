package dev.everyagent.plugin.api.spi;

/**
 * 工具执行拦截链节点 SPI(<b>per-call 粒度</b>,与任务洋葱 §3.1 / 授权链同一 filter 范式,
 * 见架构 §7.14.3)。每次工具调用执行一遍链:链节点下行段(调 {@code next.proceed} 之前)
 * 位于框架回调解析之后、observation 之前;上行段({@code next.proceed} 返回之后)位于
 * observation 返回之后、ToolResponse 构造之前——两处切口由链折叠结构天然覆盖。
 *
 * <p><b>切口语义 = 方案 B(严格)</b>:
 * <ul>
 *   <li><b>下行段</b>:可改写参数({@code ctx.arguments(...)},对真实执行与 observation
 *       生效);可合成当前调用结果({@code ctx.synthesize(串)} 哨兵 → 链尾跳过
 *       observation + 真实调用,合成串沿框架 ToolResponse 构造路径流经全部上行改写节点
 *       ——secret-redaction 等对合成答案照常生效;批内其余调用照常真执行,混合轮天然
 *       正确);<b>不可终止批</b>(框架循环无 mid-batch break,禁止自造「N 调用 M 结果」)。</li>
 *   <li><b>上行段</b>:可改写结果串(脱敏/hint)、可发事件,<b>不可撤销</b>已执行调用。</li>
 *   <li>{@code return next.proceed(ctx)} = 放行(真实执行);直接 {@code return} 串不调
 *       {@code next} = 经典硬短路(绕过内层节点,仅限极端场景;合成一律走 synthesize)。</li>
 * </ul>
 */
public interface ToolExecutionInterceptor {

    String id();

    /** 链上位置:升序 = 下行执行序(上行逆序);float 允许任意插位;同 order 按注册顺序。 */
    float order();

    /**
     * @param ctx  per-call 执行上下文(当前 ToolCall + ToolCallback + 可改写入参/合成哨兵
     *             + ExecContext 槽位,见 {@link ToolExecutionContext})
     * @param next 链的下一环(链尾 = 框架原生 observation + 调用 + 异常处理)
     * @return 该调用的结果串(下行合成 = 合成串;放行 = 真实执行结果,上行段可改写)
     */
    String invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception;
}

