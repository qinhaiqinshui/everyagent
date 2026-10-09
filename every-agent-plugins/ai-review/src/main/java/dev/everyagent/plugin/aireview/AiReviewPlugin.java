package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.slash.SlashTokenResolver;

/**
 * AI 审议插件入口。
 *
 * <p>activate() 中实例化 AiAuthReviewer，注册 AiReviewAuthHandler、
 * AiReviewSlashProvider、AiReviewSlashResolver，并注册出网过滤链
 * ({@link AiReviewEventEgressFilter} / {@link AiReviewRoundEgressFilter})——
 * 丢弃 AI 审议 agent 的过程事件(落盘不动、客户端不可见),结果 trace 保留可见。
 */
public class AiReviewPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "ai-review"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        // 1. 实例化 AI 审议器(仅 WorkerConfig;agent 装配经 req.context().agentFactory(),§8.3)
        AiAuthReviewer reviewer = new AiAuthReviewer(ctx.services().config());

        // 2. 注册授权链节点
        ctx.registerAuthorizationHandler(new AiReviewAuthHandler(reviewer));

        // 3. 注册 /AI 审议 命令提供者
        ctx.registerSlashProvider("ai-review", () ->
                AiReviewSlashProvider.items(ctx.services()));

        // 4. 注册 token 提交解析器
        ctx.registerSlashTokenResolver(new AiReviewSlashResolver());

        // 5. 注册出网过滤链(隐藏 AI 审议过程,落在 worker 的单点出网投影器上,对 4 个出网口统一生效):
        //    事件级丢弃审议 agent 的过程事件 + 轮次级裁剪审议 agent 的 agentRanges。
        ctx.registerEventEgressFilter(new AiReviewEventEgressFilter());
        ctx.registerRoundEgressFilter(new AiReviewRoundEgressFilter());
    }
}
