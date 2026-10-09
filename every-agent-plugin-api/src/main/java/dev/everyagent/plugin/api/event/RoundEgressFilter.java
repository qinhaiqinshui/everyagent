package dev.everyagent.plugin.api.event;

/**
 * 轮次级出网过滤器 SPI:作用于 <b>pre-wire 的轮次载荷</b>(如 task 域的一轮 rounds 条目)。
 *
 * <p>因 plugin-api 是三层共享契约、<b>不能依赖 worker 类型</b>,round 载荷以<b>域中性的 JSON 视图</b>
 * 承载(运行时类型 {@code tools.jackson.databind.node.ObjectNode},故入参/返回值声明为 {@link Object})。
 * 视图形状(字段名与前端 rounds 形态对齐,区别仅在 seq 仍为数值、未闭合端为 JSON null):
 * <pre>{
 *   "index": 1,
 *   "startSeq": 100,
 *   "endSeq": 160,              // 未闭合 = JSON null
 *   "user": "...",
 *   "finalReply": "...",
 *   "durationMs": 0,
 *   "roundId": "r_xxx",         // 可选(缺失即不写)
 *   "userMessage": { ... },     // 可选
 *   "agentRanges": [ { "agentId": "sub_1", "title": "...", "startSeq": 120, "endSeq": 150 } ]
 * }</pre>
 *
 * <p>契约:
 * <ul>
 *   <li>过滤器可增删/改写视图字段(典型:<b>裁剪 {@code agentRanges}</b>,移除某 agent 在该轮的进出区间);</li>
 *   <li>返回 {@code null} = <b>丢弃整轮</b>;返回视图(原样或改写)= 放行;</li>
 *   <li>worker 侧投影器在链跑完后,再把该视图 wire 为前端 rounds 形态(seq 字符串化、未闭合端空串等),
 *       故过滤发生在 wire <b>之前</b>,过滤链看到的是数值 seq。</li>
 * </ul>
 */
public interface RoundEgressFilter {

    /** 过滤器 id(同插件内稳定唯一,便于诊断/去重)。 */
    String id();

    /** 链上位置:升序 = 过滤序;同 order 按注册顺序稳定。 */
    int order();

    /**
     * @param ctx   出网上下文(主体标识)
     * @param round pre-wire 的轮次 JSON 视图(运行时 {@code ObjectNode})
     * @return 放行/改写的视图;{@code null} 表示丢弃整轮
     */
    Object apply(EgressCtx ctx, Object round);
}