package dev.everyagent.plugin.api.event;

/**
 * 事件级出网过滤器 SPI:作用于 <b>pre-wire 的单条 {@link EventRecord}</b>。
 *
 * <p>出网 = 「客户端可见形态」的产出。所有出网口(stream 定向推送 / task.poll /
 * task.roundTail / task.rounds)都已收敛到 worker 的<b>单点投影器</b>,投影器内按 {@link #order()}
 * 跑本链。因此「隐藏某 agent 的过程输出」这类策略只需注册一个过滤器,无需触碰任何产生方。
 *
 * <p>契约:
 * <ul>
 *   <li>输入是<b>落盘态镜像</b>(与 EventLog 记录同形)。过滤器可改写 <b>payload</b> 内的展示内容,
 *       改写须在 payload 内自成信封(不得把新字段上提到记录顶层去借用外部语义);</li>
 *   <li><b>不得改动 {@code ext}</b>——ext 是落盘态镜像(persist / operate 等),改写它会破坏落盘与
 *       去重语义;</li>
 *   <li>返回 {@code null} = <b>丢弃</b>该事件(不进入任何出网口,但事件仍照常落盘,
 *       游标按未过滤口径推进);</li>
 *   <li>返回原记录或改写后的记录 = <b>放行</b>(继续链与后续 wire / 推送)。</li>
 * </ul>
 */
public interface EventEgressFilter {

    /** 过滤器 id(同插件内稳定唯一,便于诊断/去重)。 */
    String id();

    /** 链上位置:升序 = 过滤序;同 order 按注册顺序稳定。 */
    int order();

    /**
     * @param ctx    出网上下文(主体标识)
     * @param record pre-wire 的单条事件记录
     * @return 放行/改写的记录;{@code null} 表示丢弃该事件
     */
    EventRecord apply(EgressCtx ctx, EventRecord record);
}