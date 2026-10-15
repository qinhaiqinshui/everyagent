package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.event.EgressCtx;
import dev.everyagent.plugin.api.event.EventEgressFilter;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.event.Events;

import java.util.Set;

/**
 * 事件级出网过滤器:丢弃 AI 审议 agent 的<b>会话内容</b>事件,让「AI 审议过程」不出网(但仍照常落盘)。
 *
 * <p>审议 agent 以 per-task 固定 agentId {@code review-<subjectId>} 注册(§8.3)。其
 * {@code run()} 期间由 advisor 链以自身身份发射的<b>会话内容</b>——思考(delta/thinking)、
 * 正文(message)、工具结果(tool.result)、错误(error)与重试/容灾 trace 族——都带 {@code review-}
 * 前缀的 agentId,一律在此丢弃(返回 {@code null});事件体本身照旧进 {@code EventLog} 落盘,只是
 * <b>客户端不可见</b>(出网过滤,§5.3/§14.13),落盘与 seq 空间完全不变。
 *
 * <p><b>「会话内容」与「非内容事件」的分界</b>:审议 agent 的<b>状态与用量</b>必须保持出网——
 * 前端「输入框上方 agent 胶囊列表」({@code AgentListPanel})的状态与用量数据源就是流里的
 * {@code agent.started}/{@code agent.status}/{@code usage} 与台账 {@code task.agents}(§5.5/§7.20.1);
 * 若把三者一并丢弃,审议胶囊在整轮运行期间只能停在「空闲 + 无用量」,直到任务收口后前端重拉台账
 * 才补齐(实测 bug)。故本过滤器只丢弃「内容」,放行下列三种<b>不含任何审议内容</b>的事件:
 * <ul>
 *   <li>{@code usage}——单次模型调用的 token 用量(round/total/上下文窗口/模型名);</li>
 *   <li>{@code agent.started} / {@code agent.status}——per-run 生命周期的权威状态源
 *       (标题/创建时间/running→completed),payload 不带正文;</li>
 *   <li>{@code auth.review}——审议<b>结果</b> trace(decision/confidence/reason),本就不属「过程」,
 *       保持客户端可见(前端仍显示「允许 置信度 90%」,§7.9/§14.13)。</li>
 * </ul>
 * {@code agent.done} 仍被丢弃:它除状态/累计用量外还携带 payload 顶层 {@code content}(本轮输出正文,
 * 即审议结论原文),属「内容」;其传递的状态与用量已由 {@code agent.status} + {@code usage} 覆盖。
 */
public class AiReviewEventEgressFilter implements EventEgressFilter {

    /** 过滤器 id(本插件内稳定唯一)。 */
    public static final String ID = "ai-review-hide-process";

    /**
     * 链上位置:纯丢弃策略,取一个明确靠后的次序值({@value})——给未来更靠前、更细粒度的
     * payload 改写过滤器留出插入位;本插件内只有这一个事件级 filter,次序不与其它节点竞争。
     */
    public static final int ORDER = 1000;

    /**
     * 审议 agent 仍需出网的事件(全部不含审议内容):状态/用量数据源 + 审议结果 trace。
     * 前三者是前端 agent 胶囊列表实时数据的唯一供体——审议胶囊据此在运行中即显示
     * 「运行中 + 累计用量/上下文占用」,无须等任务收口后重拉台账。
     */
    private static final Set<String> AGENT_LIST_EVENTS = Set.of(
            Events.USAGE, Events.AGENT_STARTED, Events.AGENT_STATUS);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public EventRecord apply(EgressCtx ctx, EventRecord record) {
        if (record == null) {
            return null;
        }
        // 非审议 agent 事件一律放行(主/子 agent 的全部事件原样出网)。
        if (!AiAuthReviewer.isReviewAgentId(record.agentId())) {
            return record;
        }
        // 审议 agent 的「非内容」事件保持可见:状态/用量(驱动 agent 胶囊)+ 审议结果 trace。
        // 注意 agent.done 不在放行之列——它带 payload 顶层 content(审议结论原文),属会话内容。
        String event = record.event();
        if (AGENT_LIST_EVENTS.contains(event) || AiAuthReviewer.AUTH_REVIEW_KIND.equals(event)) {
            return record;
        }
        // 其余(思考/正文/工具结果/错误/重试容灾 trace/agent.done)= 审议会话内容 → 丢弃
        // (客户端不可见,事件仍已落盘,§14.13)。
        return null;
    }
}