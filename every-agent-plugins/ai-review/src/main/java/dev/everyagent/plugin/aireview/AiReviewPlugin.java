package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.slash.SlashTokenResolver;

/**
 * AI 审议插件入口。
 *
 * <p>activate() 中实例化 AiAuthReviewer，注册 AiReviewAuthHandler、
 * AiReviewSlashProvider、AiReviewSlashResolver。
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
    }
}
